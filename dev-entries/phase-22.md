# Phase 22. Post-0.6.0 Engineering Hardening

Consolidated phase approved 2026-11-25. It covers the work since Phase 21 under four workstreams:

- Engine correctness
- NNUE and tuner integrity
- Search and runtime performance
- Validation infrastructure

Work goes on independent `fix/`, `perf/`, `feat/`, `research/` and `infra/` branches. No phase after 22 is allocated until a roadmap decision says so, and such decisions are recorded in this file.

**Exit criteria (outcome-based):**
- Confirmed high-priority defects are fixed or explicitly dispositioned with a reason.
- Performance changes have controlled measurements.
- Strength changes have appropriate validation (SPRT per CLAUDE.md Section 6).
- Unresolved blockers are identified here.
- Closing every audit issue is not required.

**Legacy label mapping.** The dated entries below and the `phase-23` to `phase-30` files were written under per-topic labels. They are kept as written.

| Legacy label | PRs | Log | Workstream |
|---|---|---|---|
| Phase 22, API input correctness | | this file | Engine correctness |
| Phase 23, Opening-book correctness | | `phase-23.md` | Engine correctness |
| Phase 24, tooling correctness | #301 | none | Validation infrastructure |
| Phase 25, trainer contracts | | `phase-25.md` | NNUE and tuner integrity |
| Phase 26, WSL2 qualification | | `phase-26.md` | Validation infrastructure |
| Phase 27, search history and table contamination | | `phase-27.md` | Engine correctness |
| Phase 28, search overhead and measurement protocol | #307, #310 | none | Search and runtime performance |
| Phase 29, tuner parity | #309 | none | NNUE and tuner integrity |
| Phase 30, NNUE quantization audit | | `phase-30.md` | NNUE and tuner integrity |
| Phase 31, classical evaluation defects | #311 | none | Engine correctness |
| Phase 32, NNUE vectorization | #313 | none | Search and runtime performance |

Labels 24, 28, 29, 31 and 32 have no `dev-entries` file. Their PR branches were `phase/24-tooling-correctness`, `phase/28-search-overhead`, `phase/28-measurement-protocol`, `phase/29-tuner-parity`, `phase/31-eval-defects` and `phase/32-nnue-vector-dot`. Those branches are not renamed. Record their outcomes in this file.

Phase 15 keeps its status (the self-play/Stage-3 line is paused and every `[P15]` issue is closed, see `docs/architecture/research/phase15-nnue-closeout.md`). Phase 17 keeps its status (the PVS experiment is complete and not promoted, see `docs/architecture/research/phase17-closure.md`). The A to E scope in `docs/NNUE_PRD.md` is NNUE planning and is separate from engine-wide phases. Frozen experiments, #296 and #260 are unaffected by this consolidation.

## Roadmap decisions

### [2026-11-25] Consolidate post-0.6.0 work into Phase 22

**Built:** Documentation only: this header, the branch convention and phase rules in CLAUDE.md, and the logging rule in `dev-entries/README.md`.

**Decisions Made:** One phase with four workstreams replaces per-topic phase labels. The one-branch-per-phase rule and the ban on per-issue branches are dropped, because per-scope branches (for example `fix/tt-collision-policy`) were already how the work was done. Phase exit is judged on outcomes rather than on closing every audit issue. Existing branches, history and the legacy logs are left alone.

## Logged work

### [2026-11-24] Invalid FEN responses

**Built:** The board parser reports unknown piece characters and malformed FEN fields as invalid input. The legacy game load, game API, evaluation and SSE endpoints return a JSON HTTP 400 for that error.

**Decisions Made:** Map the specific parsing exception in the existing controller advice. Unrelated argument errors reach normal server exception handling.

**Broke / Fixed:** An unknown piece character failed during integer unboxing, and analysis treated an internal argument error as bad input. The tests-only CI run reproduced both behaviors and passed all five Perft cases.

### [2026-11-24] Remaining chess-engine-api defects from #276

**Built:** Move and setup responses carry a detached copy of the board taken under the session lock. The legacy load and new-game endpoints clear the redo stack, and make-move accepts an optional promotion letter. The session store is capped at 1,000 entries and drops the least recently used one, never the default session. Analysis takes ownership of the active slot atomically, releases it when an evaluate call finishes, reports a superseded evaluation as HTTP 409 instead of a truncated 200, serializes the final SSE send against the timeout thread, and stops its daemon executor on shutdown. FEN parsing requires eight ranks of eight squares and an en-passant square on the board. The API refuses positions without exactly one king per side. The API module now builds an executable JAR and the Dockerfile builds it from the repository root on Java 21.

**Decisions Made:** The snapshot replays the move history onto a fresh board so every serialized field matches the live board and the JSON contract does not change. The king check lives in the API layer because engine-core tests and tools build partial positions on purpose. The FEN shape and en-passant bounds sit in the parser because any valid FEN already satisfies them. The en-passant rank is only bounded to 1 to 8, not restricted to 3 and 6, to avoid rejecting input the parser used to take. Eviction is by recency and not by idle time, which keeps the store free of timers. A session dropped while a request holds its lock finishes that request on the detached session, and later requests get the existing not-found response. The legacy endpoints create a session for any id, so a dropped id comes back there as a fresh game, and enough distinct ids can push other sessions out. There is no per-client quota. A promotion letter on a move that is not a promotion is ignored, so a client that always sends one keeps working. A superseded SSE stream is now completed with no terminal event instead of staying open until the emitter timeout. A finished evaluation that a newer request supersedes in the instant before the abort check is reported as 409, which is accepted because the request was superseded.

**Broke / Fixed:** A rank that was too short or too long slid pieces onto the wrong squares, and an overlong one wrapped the bit shift past square 63. An en-passant square such as a0 or i3 produced an out-of-range index. The two lifecycle fixes with no deterministic test are the SSE completion race and the timeout-thread interleaving. They are covered by review of the synchronization, not by a test.

**Measurements:** 42 targeted API tests and 11 engine-core FEN tests pass locally. Perft and the full suites run in CI, because a native SPRT is using the host.

### [2026-11-25] Tuner parity, pipeline defects and review findings (#274, #309; legacy Phase 29, NNUE and tuner integrity)

**Built:** CoreTunerParityTest compares the static score of the production Evaluator and TunerEvaluator on hand-picked, seeded game and random-placement positions. Fixes found by it and by a read of the pipeline: baseline parameters read from engine-core, connected-pawn shifts, the opposite-flank shelter discount, the #138 hanging suppression, the SmokeTestRunner side-to-move test, the CoordinateDescent restore, the L-BFGS two-loop recursion and gradient norm, TunerRunMetrics wiring, and twin-aware corpus splitting. An independent review then returned HOLD with six findings, all reproduced and fixed in the same PR: (1) coordinate descent now records its termination reason, every optimizer rejects maxIters below 1, and Gate 1 fails closed on missing metrics, no termination reason, no iterations, no MSE, or a capped run with no delta evidence; (2) the Adam and L-BFGS feature extraction shares the static evaluator's net hanging count, with a feature-objective parity test; (3 and 6) parameter ownership is one definition, EvalParams.isActive, so frozen and out-of-group parameters are not clamped at start, carry no gradient into the L-BFGS history, recursion or descent check, and are restored after material ordering, in all three optimizers; (4 and 5) the CLI builds a TuningData from the train and val partitions and hands it to every optimizer, and twins are paired by identity from loadBalanced or, for foreign lists, by equal key, opposite orientation and a mirrored outcome inside each hash run.

**Decisions Made:** The production evaluator is the reference, so the tuner's copy was changed to match it. Mop-up stays out of the tuner as its header documents, so the parity test skips positions where mop-up is active. The L-BFGS gradient reuse was left out because it changes the trajectory and cannot be judged without a tuning run. K is now calibrated on the val partition itself. It used to be fitted on the last 10 percent of the train features, which left the val partition unused, so an empty val partition is now an error and K values from before this change came from a different sample. Coordinate descent now honors --param-group, which it ignored. An L-BFGS run that converges on its initial gradient completes zero iterations. That is legitimate, so it records the starting MSE and active gradient norm (TunerRunMetrics.recordImmediateConvergence) without counting an iteration, and Gate 1 accepts zero iterations only with a CONVERGED reason, a finite starting MSE and a finite gradient norm below the L-BFGS threshold. An optimizer that never started, or one with absent or non-finite evidence, still fails. The hanging term is asserted exactly: the feature coefficient is 0 for a suppressed piece and the 0 to 40 penalty differential is 0 on both the feature and static paths, using the review's FEN (7k/6pp/1b6/8/8/8/8/K5R1 w - - 0 1) and a queen fixture, plus an unsuppressed case where both paths move by exactly -40. The seeded feature-versus-static comparison allows 25 cp and is an approximate consistency check only, not parity: the float feature path and the int-truncating static path drift by up to 15 cp on crowded boards even with hanging and king safety zeroed. That residual rounding drift was seen and not chased, and the evaluator was not redesigned.

**Broke / Fixed:** Before the parity fixes the two evaluators differed on 308 of 311 fixed positions (mean 26.7 cp, worst 156 cp). Before the review fixes coordinate descent never recorded a termination, Gate 1 passed a run with no metrics, the feature path counted a mating-net queen as hanging, an out-of-group parameter was clamped and re-ordered, frozen gradients entered the L-BFGS history, coordinate descent trained and calibrated K on the full corpus including val and test, and twin pairing depended on rounding the label to a thousandth and on a 64-bit hash with no key check.

**Measurements:** The engine-core and engine-tuner reactor passes locally with 162 tuner tests, 0 failures and 1 pre-existing skip. The new regressions use APIs the fixes add, so they could not be run against the old revision. Instead each fix was disabled in turn on the final code and the tests run again: the original hanging path fails the coefficient test on the review FEN; removing the coordinate descent termination record or the maxIters check fails three tests; disabling the Gate 1 checks fails two validator tests; disabling the ownership restore, the init clamp rule and the gradient zeroing fails the frozen-parameter and inactive-gradient tests; passing train as the K set fails the two isolation tests; exact label comparison pairs 17 of 80 twins, a constant key fails the collision test, and disabling identity pairs 0 of 80; dropping the immediate-convergence record fails the L-BFGS gate test. One test is not a failing-before test: aGeneratedCorpusPairsItsTwinsByIdentity, because exact labels pair by inference too, so identityPairsTwinsWithoutLookingAtLabels is the one that pins identity. No tuning, strength or timing run was made. Tuned parameters, K values and splits produced before this change should be read with the old behavior in mind.
