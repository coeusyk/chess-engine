# Engine Tools

This directory contains scripts and utilities for testing, SPRT matching, and profiling the chess engine.

---

## Directory Structure

```
tools/
  sprt.sh          — Linux/macOS SPRT match runner (wraps cutechess-cli)
  sprt.bat         — Windows SPRT match runner
  match.sh         — Quick non-SPRT match (fixed game count)
  match.bat        — Windows equivalent of match.sh
  engines.json     — Engine definitions for cutechess-cli
  profiles/        — Profiling captures (.html, .jfr, heap/thread snapshots, GC logs)
  results/         — PGN output from SPRT/match runs
```

---

## Profiling Setup

### Linux / macOS — async-profiler (recommended)

async-profiler is the preferred profiling tool. It samples both JIT-compiled Java and
native code (including JVM internals), giving an accurate picture of where CPU time
is actually spent during a bench run.

**1. Download async-profiler**:
```bash
# Replace X.X with the latest release from https://github.com/async-profiler/async-profiler/releases
wget https://github.com/async-profiler/async-profiler/releases/download/vX.X/async-profiler-X.X-linux-x64.tar.gz
tar xzf async-profiler-X.X-linux-x64.tar.gz
```

**2. Profile a bench run** (saves flamegraph to tools/profiles/):
```bash
mkdir -p tools/profiles
java -agentpath:async-profiler-X.X-linux-x64/lib/libasyncProfiler.so=start,event=cpu,file=tools/profiles/profile_before.html \
  -jar engine-uci/target/engine-uci-0.2.0-SNAPSHOT.jar --bench 13
```

**3. Open the HTML** in a browser — it is a self-contained interactive flamegraph.

**What to look for:**
- If `MoveGenerator` or `Board.generateMoves()` is > 30% of CPU → move-gen is the bottleneck
- If evaluation methods are > 30% → evaluation caching (pawn hash) may need wider scope
- If `TranspositionTable` operations are > 20% → probe/store is hot (optimize hash function or layout)
- Q-search frame is clearly visible as a separate subtree — if it dwarfs main search, investigate Q-search horizon

**Before vs After captures:**
- Save `profile_before_<change>.html` and `profile_after_<change>.html` for each optimization
- Compare call-frame proportions, not absolute sizes (wall clock fluctuates)

---

### Windows — Java Mission Control (JMC)

async-profiler requires a Linux/macOS host. On Windows, use JMC instead:

**1. Start engine with JFR enabled**:
```powershell
java -XX:StartFlightRecording=duration=30s,filename=tools/profiles/bench.jfr `
  -jar engine-uci\target\engine-uci-0.2.0-SNAPSHOT.jar --bench 10
```

**2. Open `bench.jfr` in Java Mission Control** (`jmc` — ships with JDK or download from
  https://adoptium.net/jmc/). Use the "Method Profiling" view for hot methods.

### Windows — cutechess / engine-uci jar profiling

For profiling the real `engine-uci` jar under cutechess, use the dedicated Phase 8 helper:

```bat
tools\sprt_profile_phase8.bat
```

This does three things for the `Vex-new` JVM only:
- starts a tagged JVM and attaches `jcmd JFR.start settings=profile` after the engine process appears, so the UCI handshake stays clean
- enables Native Memory Tracking (`-XX:NativeMemoryTracking=summary`) for live `jcmd` memory snapshots
- writes a GC log alongside the `.jfr` recording

Artifacts are written to `tools/profiles/`:
- `sprt_phase8_new_<timestamp>.jfr` — CPU samples, allocation samples, lock and thread events
- `sprt_phase8_new_<timestamp>_gc.log` — GC / heap pressure timeline
- `tools/results/sprt_phase8_profiled_<timestamp>.pgn` — match output

While the match is still running, capture live JVM snapshots in another PowerShell window:

```powershell
powershell -ExecutionPolicy Bypass -File tools\capture_uci_jvm_snapshot.ps1 -Match "vex-sprt-phase8-<timestamp>"
```

The helper batch prints the exact `-Match` tag when it starts. The snapshot script writes:
- `VM.command_line`
- `GC.heap_info`
- `GC.class_histogram`
- `Thread.print`
- `VM.native_memory summary`

Use this workflow for the questions you care about:
- Method calling / hot paths: open the `.jfr` in JMC and inspect `Method Profiling`, `Hot Methods`, and the call tree under `jdk.ExecutionSample`
- Heap / allocation pressure: inspect `Allocation` and `Garbage Collections` in JMC, plus `GC.class_histogram` from the live snapshot
- Native / off-heap memory: inspect `VM.native_memory summary`
- Thread state during search: inspect `Thread.print`

For a quick text-only summary without opening JMC:

```powershell
jfr summary tools\profiles\sprt_phase8_new_<timestamp>.jfr
jfr print --events jdk.ExecutionSample tools\profiles\sprt_phase8_new_<timestamp>.jfr | more
```

If you want cleaner single-engine CPU samples, temporarily reduce cutechess concurrency to `1` in the profiling batch.

---

## Running SPRT

**Requirements:** `cutechess-cli` — install via:
- Ubuntu: `sudo apt install cutechess`
- Arch: `paru -S cutechess`
- macOS: `brew install cutechess`
- Windows: download from https://github.com/cutechess/cutechess/releases

**Linux usage:**
```bash
./tools/sprt.sh engine-uci/target/engine-uci-0.2.0-SNAPSHOT.jar path/to/baseline.jar
```

**Windows usage:**
```bat
tools\sprt.bat engine-uci\target\engine-uci-0.2.0-SNAPSHOT.jar path\to\baseline.jar
```

SPRT parameters (elo0=0, elo1=50, alpha=0.05, beta=0.05) are set in the scripts.
Results are saved to `tools/results/sprt_<timestamp>.pgn`.

---

## Tagging a New Baseline Release

After a new Elo gain is confirmed by SPRT, tag a release so future SPRT runs use this version
as the new baseline:

```bash
# strip -SNAPSHOT from poms, commit, tag, push
git tag v0.2.0
git push origin v0.2.0
# Attach engine-uci-0.2.0.jar to the GitHub Release via the web UI or gh CLI
gh release create v0.2.0 engine-uci/target/engine-uci-0.2.0.jar --title "v0.2.0" --notes "..."
# Immediately bump to next -SNAPSHOT in poms
```

The nightly CI workflow downloads the JAR attached to the latest release tag automatically.

---

## Bench Baseline Recording

After every significant performance change, run:
```bash
echo "bench 13" | java -jar engine-uci/target/engine-uci-0.2.0-SNAPSHOT.jar 2>&1
```
and record the output in `DEV_ENTRIES.md` with the format:

```
Bench baseline — [date] — [description of change]

Position 1: nodes=X qnodes=Y ms=Z nps=N q_ratio=Rx
...
```

---

## Performance Budget Targets (post-magic-bitboards)

| Metric | Target | How to measure |
|--------|--------|----------------|
| Nodes/sec | > 5M nps at bench depth 13 | Bench `[BENCH]` stdout |
| Effective branching factor | < 4.0 at depth 10 | `ebf=` in bench output |
| TT hit rate | > 40% at depth 10 | `tt_hit=` in position summary |
| Q-search ratio | < 10× main nodes | `q_ratio=` in position summary |
| Move gen time % | < 30% of CPU | async-profiler flamegraph |

---

## Bench Baselines

### 2026-03-28 — Phase 7 after magic bitboards + NMP/LMR + pawn hash + fast check detection

Bench depth 8 | 6 positions | JVM: OpenJDK 21 | Hash: 16 MB

| Pos | FEN summary | nodes | qnodes | ms | nps | q_ratio | tt_hit% | fmc% | ebf |
|-----|-------------|-------|--------|----|-----|---------|---------|------|-----|
| 1 | startpos | 16,612 | 35,854 | 2,755 | 6,029 | 2.2x | 32.6% | 96.0% | 2.37 |
| 2 | Kiwipete | 67,340 | 1,050,002 | 121,136 | 555 | 15.6x ⚠️ | 34.6% | 97.9% | 2.78 |
| 3 | CPW pos3 | 10,278 | 22,869 | 596 | 17,244 | 2.2x | 37.5% | 94.7% | 1.91 |
| 4 | CPW pos4 | 30,428 | 487,601 | 41,474 | 733 | 16.0x ⚠️ | 27.4% | 96.6% | 2.67 |

Positions 5 & 6 not timed (bench depth 8 run exceeded wall time).

**⚠️ Q-search ratio on positions 2 and 4 exceeds the 10× limit. Follow-up issue filed.**

**Notes:**
- NPS on plain positions (pos3: ~17K) is within range, but Kiwipete/pos4 dominate bench time due
  to Q-search explosion — the "before magic bitboards" NPS baseline was not captured (magic was
  implemented in #68, before this tracking was established).
- FMC% and EBF targets are met. Q-search and depth/time targets are open.
