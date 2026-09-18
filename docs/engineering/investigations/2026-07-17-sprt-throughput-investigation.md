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
