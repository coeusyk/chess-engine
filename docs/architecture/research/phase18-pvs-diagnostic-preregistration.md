# Phase 18 diagnostic preregistration: PVS fixed-depth versus strength divergence

Status: design only; do not execute as part of Phase 17 closure.
Purpose: explain the observed divergence, not reopen the promotion decision.

## Research question

Why did candidate `f9b152ca4f45e8e8aa5a48092b03416aba79b230` reduce fixed-depth
main nodes by about 44% and fixed-depth elapsed time by about 45%, while the
valid frozen Gate 4 test produced 0 wins, 13 losses, and 1 draw over 14 scored
games against baseline `ebe513eabd50e853a4e24a0260c64b41a5a4b224`?

The Phase 17 result remains final regardless of the diagnostic outcome. This
phase runs no new games and no SPRT.

## Inputs and invariants

- Existing Gate 4 PGN and log:
  `tools/results/sprt_phase17-pvs_20260920_160513.pgn` and `.log`.
- Existing run evidence:
  `tools/results/p17-4/20260920-103500/`.
- Candidate and baseline frozen commits listed above.
- Existing canonical benchmark corpus and the recorded Phase 17 benchmark
  results.
- Gate 4's 14 scored games are evidence about these 14 games only; divergence
  patterns are not causal proof.
- No search constants, move-ordering policy, or strength-test protocol may be
  tuned during diagnosis.

## Information-priority ranking

This order is based on information gained per cost and the ability to stop
early, not on a prior belief about which hypothesis is true.

| Priority | Hypothesis class | First question | Useful stop condition |
|---|---|---|---|
| 0 | Existing-game divergence patterns | Where is the first meaningful candidate/baseline move divergence in each scored paired opening, and do losses cluster by structure? | A reproducible cluster is found; record it without claiming causality. |
| 1 | Time-management interaction | Does the candidate spend different time per move, reach different completed depths, or stop on a different iteration under equivalent clock/movetime inputs? | A time/depth asymmetry explains the selected divergences; stop before search instrumentation. |
| 2 | Root ordering and PV instability | Do root ordering, PV changes, aspiration retries, or final root choices differ deterministically before the game result diverges? | A stable root/PV difference identifies the smallest next question. |
| 3 | Root/PVS bound semantics | Does any later sibling or re-search use a bound as an exact value, leak fail-low/fail-high values into root selection, or propagate alpha/PV incorrectly? | A deterministic correctness defect is demonstrated; stop and isolate it. |
| 4 | Effective depth and work equivalence | Is the 44% node reduction duplicate-work removal, or a materially different tree with less tactical coverage at the same nominal depth? | Fixed-depth versus supported fixed-node evidence distinguishes the two; otherwise specify the smallest harness needed. |
| 5 | Selective-search interaction | Under the changed PVS windows, do razoring, futility, losing-capture pruning, null move, LMR, singular extensions, or correction history change in a way that explains a selected divergence? | A targeted counter answers one concrete question; do not build a general instrumentation framework. |

## Bounded diagnostic ladder

### Step 0: validate and index existing evidence

Use the recorded paths and hashes to identify the exact PGN/log pair and run
directory. Parse the PGN into paired openings, scored result, color, starting
FEN, and move list. Confirm the log's SPRT decision and distinguish the 14
scored games from the five cancelled games. Do not copy generated artifacts
into Git.

### Step 1: locate existing-game divergence

For each scored paired opening, replay both games from the shared opening and
record the earliest meaningful candidate/baseline move divergence. Attach the
position FEN, ply, side to move, result, opening identity, and a coarse
structure label (tactical, quiet middlegame, or endgame). Treat this as
descriptive evidence only.

### Step 2: deterministic fixed-depth replay

For a small, predeclared sample of the earliest divergences, run candidate and
baseline independently with identical options (`Threads=1`, `Hash=16 MB`) at
fixed depths. Repeat only when determinism needs checking. Record:

- chosen move and score;
- completed depth and selective depth;
- main nodes, qnodes, and elapsed time;
- PV and root move scores when available;
- PVS zero-window probes, full-depth verifications, and full-window researches
  for the candidate;
- aspiration retries/failures when instrumentable.

Use fixed-node comparisons only if the engine already supports them correctly.
The initial diagnostic must not add a new node-budget feature merely to fill a
table.

### Step 3: time-management check

At the selected positions, compare equivalent `movetime` and clock-based
searches. Read the existing `IterationInfo`/UCI reporting and `TimeManager`
limits to compare per-move elapsed time, completed depths, stability scaling,
and the last completed iteration. Check whether aspiration failure or root/PV
instability causes extra work or an early move choice. If this explains the
divergence, stop.

### Step 4: root/PVS trace, only if needed

Add the smallest temporary or diagnostic-only trace needed for a selected
position. Capture root move order, alpha updates, zero-window probes, full-depth
verifications, full-window researches, bound/exact status, PV propagation, and
aspiration retries. Check ordinary-sibling and LMR-PVS paths separately. A
deterministic bound defect ends the phase and is isolated; it does not alter
the Phase 17 disposition.

### Step 5: selective-search and work-equivalence checks

Only after Steps 2–4 leave the cause unresolved, add counters for one concrete
question at a time. Compare razoring, futility, losing-capture pruning, null
move, LMR, singular extensions, and correction history only where a counter
can explain an observed divergence. Compare fixed-depth tree shape with a
correct fixed-node run only if existing support makes that comparison valid.

## Stop rules

- A deterministic root-bound or PV correctness defect is demonstrated: stop,
  isolate, and record the minimal reproducer.
- A time-management asymmetry explains the divergence: stop before touching
  search semantics or tuning.
- Existing evidence cannot distinguish the hypotheses: stop and specify the
  smallest next experiment required; do not broaden instrumentation.
- No search-constant tuning, move-ordering tuning, or candidate rescue is
  allowed during diagnosis.
- No new SPRT is allowed until a separately preregistered future candidate and
  promotion question exist.
- Nothing discovered here changes the final Phase 17 H0/non-promotion record.

## Deliverable

Produce a short diagnostic report with the selected positions, deterministic
replay table, the hypothesis tested, the evidence that supports or rejects it,
and the applicable stop rule. Keep raw PGNs, logs, and JARs outside Git unless
the repository's existing artifact policy changes.
