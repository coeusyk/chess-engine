# Case Study: Diagnosing SPRT Infrastructure Throughput on a Ryzen 7 7700X

**Date:** 2026-07-17
**Systems involved:** `tools/sprt.ps1`, cutechess-cli, the Vex UCI engine (`engine-uci`), native Windows host.
**Scope:** infrastructure/throughput only. No engine strength, search, evaluation, Lazy SMP,
time control, or SPRT statistical parameters were changed as part of this investigation.

---

## 1. Problem Statement

A production SPRT (TC=60+0.6, the standard long time control for this project) had been
running for nearly two days and had completed only ~1,930 games. Windows Task Manager showed
the host — a Ryzen 7 7700X, 8 physical cores / 16 logical threads — sitting at roughly
**16% CPU utilization** for the entire run, while system RAM was already at ~86% from the OS,
a browser, and normal development tools.

The question was not "is the engine strong enough" — it was **"is our SPRT launcher using the
machine we already own?"** A two-day runtime on a mostly-idle 16-thread desktop is a testing-
infrastructure problem, not a chess problem, and conflating the two would have risked "fixing"
the wrong layer (e.g., tuning search parameters to run faster, instead of just using more of
the CPU that was already sitting idle).

## 2. Initial Observations

Given only the score line and hardware description, an early pass reasoned from `tools/sprt.ps1`'s
defaults:

- `-Concurrency 2` (default), `-EngineThreads 1` (default) → 2 concurrent games, 2 engines each
  → **4 JVM processes**.
- Naively: 4 processes × 1 search thread = 4 active threads out of 16 → **~25% expected
  utilization**.

This didn't match the observed ~16%, and the gap turned out to matter — chasing it is what
surfaced the real model in §6.

## 3. Incorrect Assumptions

The first-pass model silently assumed **both engines in a running game are actively computing
at the same time**. That's false for a non-pondering match: chess is turn-based, and
cutechess-cli was not configured to enable UCI `Ponder` (`tools/sprt.ps1`'s cutechess arguments
were checked directly — no `option.Ponder=true` anywhere, and the engine's own `Ponder` UCI
option defaults to `false`). At any instant, in an unpondered game, **exactly one side per game
is "on the clock"**; the other is blocked on stdin waiting for a `position`/`go` command. The
naive model double-counted active threads by assuming both sides burn CPU simultaneously.

A second, independent assumption that needed re-checking rather than trusting: that increasing
`-Concurrency` scales games/hour linearly. This is only true if cutechess-cli's scheduler keeps
every concurrency "slot" continuously busy regardless of how long any individual game runs. That
claim was not obviously true and had never been verified against this project's own artifacts —
it needed evidence, not intuition, especially given real game durations vary game-to-game.

## 4. Investigation Methodology

Three escalating passes, each correcting or hardening the previous one:

1. **Structural/configuration audit.** Read `tools/sprt.ps1` and `UciApplication.java` directly
   to determine, from source, how `-Concurrency` maps to JVM process count, how the `Threads`
   UCI option maps to Lazy SMP helper spawning (`UciApplication.java:701`,
   `effectiveHelpers = Math.min(threads - 1, AVAILABLE_CORES - 1)` — confirms `Threads=1` spawns
   zero helpers), and what the launcher does and doesn't log.
2. **Cross-checked, not assumed, hardware/history context.** `git log`/`grep` surfaced the actual
   benchmark CPU (Ryzen 7 7700X, `docs/phase-13-summary.md`), the real Lazy SMP bug history
   (`10a37b6`: a 64 MB per-search TT memory orphan that caused GC-pause-driven time forfeitures
   at higher thread counts — directly relevant context for a *different*, adjacent question about
   whether an old "4T worse than 2T" SMP result still holds, kept clearly separate from this
   concurrency investigation since the user explicitly ruled out re-litigating Lazy SMP here).
3. **Empirical verification against real historical artifacts**, once asked to re-derive
   independently rather than trust the prior pass's model:
   - Parsed real PGN move annotations (`{score/depth Xs}` — cutechess-cli's actual per-move
     think-time, not an estimate) from two archived SPRT runs to get **measured** game-duration
     distributions.
   - Reconstructed the *actual* concurrent-games-in-flight time series from `Started game N` /
     `Finished game N` log-line ordering, across two runs at very different time controls (872
     games at a 60+0.6-class TC, and 14,219 games at a short TC as an independent scale/variance
     check) — a direct, mechanical test of whether the scheduler ever leaves a slot idle.

## 5. Evidence Collected

- **Corrected utilization model, confirmed against observation:** no-pondering active-thread
  count ≈ `Concurrency × Threads-per-engine` (not ×2). At the defaults (Concurrency=2, Threads=1):
  2/16 = 12.5% baseline, +~4 points of JVM/GC/JIT/cutechess-cli overhead ≈ **16%** — matches the
  Task Manager reading almost exactly.
- **Real game-duration distribution** (872 games, 60+0.6-class TC): mean 164.1s, median 165.7s,
  p95 215.8s, max 271.3s. **p95/median = 1.30, max/median = 1.64** — bounded, not long-tailed,
  because the `-resign`/`-draw` adjudication rules cap runaway games.
- **Scheduler behavior, proven not assumed:** reconstructing in-flight game counts from log-line
  order showed a slot being refilled *immediately* on its own game finishing, never waiting on a
  sibling slot's longer game — confirmed at 872 games (1 unexpected idle instant, at the very end
  of the run) and again at 14,219 games with *higher* relative duration variance (max/median =
  2.14): only 4 momentary zero-in-flight events out of 28,438 logged transitions (0.03%).
- **Empirical games/hour**, derived from real per-game durations and the proven zero-idle model
  (`wall-clock = Σ(durations) / concurrency`): **43.9 games/hour at Concurrency=2** — matching
  the independent Task-Manager-based cross-check (~40-44/hour) computed from the live run.
- **Every historical SPRT log in `tools/results/` used `-Concurrency 2`** — there is no
  higher-concurrency historical data in this repository. This is an honest limit on how far the
  "linear scaling continues" claim can be verified empirically versus argued mechanically.

## 6. Revised Execution Model

- Active search threads at any instant ≈ `Concurrency × Threads` (not `× 2 engines`), because
  no pondering occurs.
- cutechess-cli's tournament scheduler is a **continuous per-slot dispatcher**: each concurrency
  slot independently and immediately starts its next queued game the instant its current game
  ends, with no batch/barrier synchronization between slots.
- Consequence: **game-duration variance does not erode concurrency scaling.** The one mechanism
  that variance could exploit (a slot idling while a sibling slot finishes a longer game) does
  not exist in this scheduler, confirmed at two very different variance profiles.
- The real constraint on *how far* concurrency can be pushed on this specific machine is **RAM
  headroom** (already at 86% from non-SPRT processes) and, past 8 concurrent slots, **SMT
  sub-linearity** (logical threads 9-16 on this CPU are hyperthreaded siblings of the first 8;
  chess search is ALU/branch-bound and typically doesn't get a full second thread's worth of work
  out of an SMT sibling) — not CPU saturation, and not variance-driven idling.

## 7. Final Conclusions

- The SPRT was **launcher-bound**, not CPU-, memory-, or scheduler-bound: `-Concurrency 2` is a
  script default, not a platform ceiling — cutechess-cli, the JVM, and the OS all support far
  more parallelism on this hardware.
- Game-length variance is **not** an additional limiting factor on top of that — it was a
  plausible-sounding hypothesis that turned out, on direct evidence, not to hold.
- The single biggest open uncertainty is not architectural: it's **how this specific machine's
  RAM headroom and SMT behavior trade off against concurrency in practice**, which reasoning
  alone cannot resolve and only a live, incremental benchmark can (see §5 of the follow-up
  benchmarking-utility work).

## 8. Practical Recommendations

- Raise `-Concurrency` from its default via an **incremental** ramp (2 → 4 → 6 → 8 → …), watching
  RAM% and games/hour at each step, not a single jump to a high value.
- Treat **RAM%, not CPU%,** as the primary stop signal when increasing concurrency on a
  shared/daily-use desktop like this one.
- Any future SMP/Lazy-SMP thread-count re-benchmark is a *separate* question from this one
  (concurrency is about how many *independent* single-threaded games run in parallel; Lazy SMP
  Threads is about how many threads cooperate *within* one game) — the two must not be conflated,
  and Threads should stay fixed while concurrency is tuned.
- Before trusting any concurrency number from reasoning, verify it with the same evidence
  standard used here: real PGN move-time annotations and real `Started`/`Finished` log-line
  ordering, not defaults or assumptions.

## 9. Lessons Learned

- **A single wrong assumption (pondering) silently doubled an estimate**, and the tell wasn't a
  crash or an error — it was a plausible-looking number (~25%) that simply didn't match one
  real observation (~16%). Reconciling that gap, rather than rounding it away as noise, is what
  surfaced the actual model.
- **cutechess-cli's PGN and log output already contain the evidence needed to answer throughput
  questions empirically** — per-move think-time annotations and `Started`/`Finished` line
  ordering — and mining them is cheap compared to reasoning from first principles or trusting
  general tool folklore about how a scheduler "probably" works.
- **"Re-derive independently, don't trust the prior audit" was the right instruction to give**,
  even though the prior audit's final recommendation (raise concurrency) survived unchanged — a
  correct conclusion reached partly by an unverified assumption is not the same epistemic state
  as the same conclusion reached from direct evidence, and the difference matters for how much
  future work should trust it.
- **Bounded variance (p95/median ≈ 1.3) plus a governor (resign/draw adjudication rules) is why
  a "hard" statistics question (does variance cause idle time) had a clean, checkable answer** —
  an unbounded, adjudication-free game-length distribution would have made this a much harder
  claim to verify from historical logs alone.

---

## Where This Belongs

This investigation produced several distinct kinds of knowledge, and they don't all belong in
the same document:

| Content | Home | Why |
|---|---|---|
| The full narrative above (problem → evidence → revised model) | **This file** (`docs/engineering/investigations/`, per CLAUDE.md §6) | Investigation-shaped knowledge — the reasoning and evidence trail matter as much as the conclusion, and this is the one place a future contributor (or a blog post) can get the whole arc. |
| "cutechess-cli is a continuous per-slot dispatcher; Threads=1 spawns zero Lazy SMP helpers (`effectiveHelpers` formula)" | **Architecture documentation** — cross-referenced from `docs/adr/ADR-009-nnue-evaluator-ownership.md`'s Lazy SMP context, not a new architecture doc | These are stable facts about how the engine and the test harness behave mechanically, useful independent of this specific investigation's throughput conclusion. |
| Whether a *new* Lazy SMP thread-count benchmark is warranted | **Not this document** | Explicitly out of scope here (see the prior turn's audit) — belongs with any future SMP re-benchmark investigation, not the concurrency case study. |
| A new ADR | **None warranted** | ADRs record architecture *decisions* with alternatives considered. Raising launcher concurrency is a tooling/config change, not an architectural one — there's no design alternative being chosen between here, just a default value to tune empirically. |
| "How to verify your actual Concurrency/Threads/Hash from a running SPRT" (log header line, shell history, no `-Xmx` set, Hash not logged) | **Developer documentation** — `tools/README.md`'s "Running SPRT" section | Practical, how-to knowledge a contributor needs *while running* a SPRT, independent of this specific case study. |
| Launcher-specific caveats (`sprt.ps1` never echoes its resolved cutechess arguments; no explicit JVM heap flags are set) | **Launcher documentation** — `tools/sprt.ps1`'s own header comment block, as a future edit | Not changed in this pass (explicitly deferred — "do not modify the existing SPRT workflow yet"), but flagged here as the correct eventual home. |
| The PGN-move-time-mining and log-line-ordering verification techniques | **Future performance benchmarking** — reused directly by `tools/benchmark_concurrency.ps1` (see below) | This is methodology, not a one-off fact — the next benchmarking tool should measure things the same evidence-grounded way rather than re-deriving from first principles again. |

A short pointer to this case study has been added to `tools/README.md`'s "Running SPRT" section;
no other existing files were modified.

---

## Addendum (2026-11-23): could qualified WSL2 testing be allowed?

Question: should the native-Windows-only rule for NPS gates and SPRTs (CLAUDE.md sections 4 and 6) be relaxed to allow WSL2 testing, and if so under what conditions. This is an evidence review only. No policy, code or SPRT parameter was changed, and no games were run.

### What the rule was actually based on

Reading the repository, the rule rests on four different things that are easy to run together:

1. **Throughput overhead (measured, small, not repeated).** The only like-for-like comparison is Phase 16 P16-2: the same commit and the same 73,089,246-node tree gave 304,059 NPS (uninstrumented) and 313,085 (instrumented) in WSL2 against a native median of 334,861, which is 9.20% and 6.50% lower. Both were single runs on a "shared, noisy" VM. Nothing back-to-back was ever repeated.
2. **Measurement noise (measured, WSL about twice as noisy).** Native Phase 16: 7 runs, CV 0.84%, spread 7,045 NPS. WSL2 P18-5: 7 runs, CV 1.70%. Different trees, so this is indicative. In this session the same search tree (23,222,204 nodes at depth 13, identical at every position) measured 327,456, 336,013, 337,169, 338,782, 343,594, 345,841 and 355,443 NPS across several builds and runs, an 8.5% spread with nothing different in the search.
3. **An absolute gate that WSL cannot meet by construction.** The NPS floor is a fixed number (5% under a native baseline). A 6 to 9% systematic overhead alone would fail it, so an absolute gate compared against a Windows number is invalid in WSL regardless of noise. This is the actual content of "WSL2 NPS is not a valid regression signal" (Phase 14 and Phase 16 entries).
4. **Infrastructure and provenance constraints.** The release and Phase 20/21 runners are PowerShell scripts that refuse WSL paths and interop, record `Platform: Win32NT`, and mark a run `LocationVerified: false` when it cannot prove it ran in the audited native checkout. Preregistrations freeze the host as the native Ryzen 7 7700X machine.

What I could not find anywhere is a measurement showing that a WSL2 SPRT gives a different or invalid statistical result. The rule is a convention. The SPRT statistics (LLR bounds, pentanomial or trinomial scoring, paired openings) depend on game results, not on absolute NPS, and in a same-host A/B both engines share the same overhead. So "statistical validity" was never the demonstrated reason; "we cannot verify the conditions" and "the absolute gate cannot be met" were.

What WSL2 is already accepted for, with evidence: node counts and node-tree equality (the 77,265,370 WSL2 count matched the native count exactly), correctness tests, Stage 0/1 and the node and overlap parts of Stage 5 in the Phase 20 qualification preregistration, and Phase 21 Stage 3 and node reproduction.

### Can WSL-to-WSL relative testing be valid without matching Windows NPS?

In principle yes for relative questions, for these reasons: search is deterministic at 1 thread (node counts are bit-identical across platforms), so any NPS difference is purely speed; a time-controlled match compares two builds that experience the same slowdown; and a constant 6 to 9% slowdown acts like a slightly shorter time control. It is not valid for anything that needs an absolute number, a comparison against a Windows baseline, or SMP scaling.

The condition that makes it conditional rather than automatic is noise. The WSL VM shares the physical cores with whatever the Windows desktop is doing, the measured spread is about twice the native one, and nobody has shown that this spread stays symmetric between two engines in a match. It probably does (they interleave on the same slots), but "probably" is why a qualification run comes first.

### WSL-specific risks

| Area | Risk | Severity for relative 1T testing |
|---|---|---|
| CPU contention | vCPUs are host threads; Windows load (browser, indexing, Defender, a native job) steals time invisibly from inside WSL. `/proc/pressure` and steal time are the only hints. | High, main risk. Mitigate by an idle host, low concurrency, and logging pressure during the run. |
| SMT and affinity | The VM does not expose which vCPUs are SMT siblings, and Windows decides placement. The Windows launcher sets no affinity either, but its host mapping is knowable. | Medium for concurrency above 8, high for Lazy SMP. |
| Lazy SMP | Thread scheduling, cache sharing and timing scaling are what Phase 20 measured natively; WSL timing was explicitly never used for them. | Not valid. Keep Threads=1 only. |
| Memory | No `memory` or `swap` limit is configured, so WSL takes up to half of host RAM and the host was already at about 86% RAM during native SPRTs. Reclaim or host pressure can stall the VM. | Medium. Record the effective limits, and pin `memory` and `swap=0` and the JVM heap explicitly for the qualified runs. |
| Clock handling | Monotonic time is fine for search and for cutechess timing. Wall clock can jump after host sleep or resume. | Low, but record the host state and avoid sleep. |
| Time forfeits | Stalls longer than the cutechess time margin show up as losses on time. They would be asymmetric noise. | High signal: measure the forfeit rate with an interval and compare it with the native record, instead of assuming zero. |
| Java 21 | Same major version family (Linux OpenJDK 21.0.13 here, Zulu 21.0.10 native). JIT and GC timing differ; `--add-modules jdk.incubator.vector` works. | Low for node counts, medium for speed. Different JDK builds must be recorded, not compared. |
| cutechess-cli | Not installed in this WSL environment; the Linux build and the Windows build are separate binaries with their own versions. | Blocking until installed and version-pinned. |
| Filesystem | Running from a `/mnt` path is much slower than the ext4 home; openings, PGN and logs must live on ext4. | Medium. |

### Qualification protocol (staged, Threads=1 only)

The protocol reuses the existing jar, `--bench-raw`, the standard opening corpus and cutechess-cli, and adds no new statistical parameters. It is staged so that each step is small, produces numbers with uncertainty, and decides whether the next step is worth its cost. It is limited to Threads=1 at first; Lazy SMP is out of scope until a separate case is made.

**Stage 0: environment record and build identity.** Before any run, record what the VM can actually use and what the host is doing, because the VM's limits are not visible from the repo:

- effective WSL resources: logical CPU count as seen by the VM, `/proc/meminfo` totals, swap, the cgroup memory and CPU limits if any, the WSL kernel and distribution versions, the effective `.wslconfig` values (an absent key means the default of all processors and about half of host RAM, not "unlimited" in any useful sense), the JDK build and the JVM heap flags;
- the cutechess-cli build, and the same set for the native side (JDK, cutechess build, power plan);
- Windows-host contention, sampled passively from the VM during the run through read-only host counters (total processor time and available memory via interop `typeperf`), together with `/proc/pressure/cpu`, `/proc/pressure/memory` and `vmstat`. Interop is used here only to observe the host, never to launch the engines under test.

Then one control jar with its SHA-256, run in WSL and native: the 31-position depth-13 node total and the per-position counts must be identical, which also re-confirms determinism.

**Stage 1: relative throughput.** One discarded warm-up and 7 `--bench-raw` runs per side, alternating blocks, in 3 separate sessions. Report the WSL to native ratio, each side's coefficient of variation, and the between-session spread of the ratio, each with its interval. This characterises how stable "about 91 to 94%" is and how noisy WSL is under the recorded host load. It does not create an absolute gate.

**Stage 2: small A/A pilot (about 100 games, 50 opening pairs).** The same jar against itself in WSL, Threads=1, Hash=16 MB, the standard corpus with paired openings and a short time control, `-Concurrency 2`. The point is to measure, not to pass or fail:

- failure rates with 95% Wilson intervals: time forfeits, crashes or disconnects, illegal-move or adjudication anomalies, per game and per move;
- the observed score and its standard error, the pentanomial pair-score variance, and how many games a 1,000-game run would actually need for a stated Elo resolution;
- game-duration distribution and per-move think-time spread compared with archived native logs (the method used earlier in this document), and the failure rate compared with the native archived rate with intervals, not with a fixed zero;
- the host and VM contention record from Stage 0 over the same window, so a bad stretch can be attributed instead of averaged away.

The pilot measures operational reliability (does the setup run cleanly and how noisy is it). It does not measure SPRT sensitivity, meaning the ability to detect a real strength difference; with an A/A match there is no difference to detect. Sensitivity is only addressed by the Stage 4 replication against a native result. The pilot's decision is a judgment recorded with its numbers: whether the failure rate and variance are close enough to the native record that a longer run is informative. Which tolerance to apply is fixed before looking at the pilot, from the native archive, and written into the run note.

**Stage 3: A/A at the size the pilot supports.** If the pilot supports it, one fixed-count A/A match of the size computed from the pilot variance (about 1,000 games if that is what the arithmetic says), with the same measurements. Report the score, its confidence interval, the failure rates with intervals, and whether the interval contains an even score. A time-control sensitivity pair (for example 60+0.6 against 40+0.4) may be run and recorded for information, but no particular Elo gap is required from it; its size is only useful later as a comparison with a native run of the same pair.

**Stage 4 (final): cross-platform replication.** Only after Stages 0 to 3 are clean, re-run one pair whose native SPRT outcome is already archived (for example the #262 SEE non-regression result) as a fixed-count match in WSL, and compare it with the native record: overlapping confidence intervals and the same verdict. This is the longest and most expensive step, so it comes last and needs its own explicit approval.

No stage launches a long match without approval. Stages 0 and 1 and the 100-game pilot are the only parts that fit a short session.

### Can the PowerShell launcher run in WSL?

Not as it stands. `tools/sprt.ps1` builds the JVM path with `bin\java.exe`, documents `cutechess-cli.exe`, carries a Windows PowerShell 5.1 stderr workaround, and the surrounding runners verify `Win32NT`, paths and process ownership. PowerShell 7 is not installed here, and cutechess-cli is not either. Even with `pwsh`, the hard-coded `\` and `.exe` would have to change, and doing that would touch the launcher that the frozen preregistrations reference. A separate small Linux launcher that emits the same cutechess argument manifest (same `-each tc`, `-recover`, `-concurrency`, openings and engine options) is the safer shape, with a test that its argument list equals the one `sprt.ps1` produces for the same inputs.

### Recommendation: ALLOW WSL CONDITIONALLY

WSL2 may be used for relative, same-session, same-host comparisons at Threads=1, but only after the staged qualification above has produced acceptable numbers, and never as an equivalent of the native environment. Specifically:

- Allowed after qualification: relative NPS between two builds measured in one WSL session; fixed-depth and node comparisons; exploratory or screening SPRTs via a Linux launcher, recorded as "WSL, qualified" and never as the deciding result for a merge unless a native confirmation is also on record.
- Still native Windows only: the absolute NPS floor (301,116 against the native baseline), anything with Lazy SMP or thread scaling, release comparisons, and any run whose preregistration freezes the host.

### Frozen and must not be edited

These name native Windows or the RENEGADE host explicitly and stay as written: the release strength preregistration and runner qualification (`release-strength-sprt-v0.5.7-v0.6.0`), `phase20-smp-stage6-preregistration.md` (laptop or WSL provides no comparable timing), `phase21-stage6-strength-policy-proposal.md`, the Phase 16 P16-2 canonical baseline and `tools/p16-2-native-baseline.ps1` with its interop refusal, `see-262-nonregression-protocol.md` and its recorded run, and `phase21-direction-and-preregistration.md` section 8 (timing reference native only). Any change would be a new, separately numbered policy document, not an edit of these.
