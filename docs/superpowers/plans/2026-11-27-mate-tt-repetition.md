# Mate scores and repetition history implementation plan

**Revision 3 (Production approval recorded).** The owner approved the bounded candidate comparison against revision 2 at `5c7e05f`, with amendments. After candidate comparison, the owner approved the blanket mate-range TT score-cutoff guard for production implementation on `a09303d`, plus a focused production PR and preparation (without launch) of the Classical Threads=1, 5+0.05 non-inferiority SPRT under #305. Root verification and continued iterative deepening are not selected and remain out of scope.

The 2500 Black game and recovered conversion matrix establish a reproducible consequence of keeping the table warm. Eight of 11 persistent-demo runs drew, and three of five runs on the same diagnostic JAR with its switch off drew. With the switch on, all five mated. All 12 cleared/fresh demo trials mated. The [case study](../../engineering/investigations/2026-11-27-manual-chesscom-games.md) contains the game, patch and run-level evidence. Issue [#287](https://github.com/coeusyk/chess-engine/issues/287) is the broader graph-history defect. Its earlier four-ply-cycle probe did not distinguish cross-history reuse from ordinary warm-table variation. Phase 21 accepted repetition as history-sensitive under #286's halfmove protection. Full UCI history now survives #260. [#296](https://github.com/coeusyk/chess-engine/issues/296) concerns the singular-verification entry condition and remains deferred.

Prior records are the [Phase 21 decisions](../../../dev-entries/phase-21.md) and the [earlier #287 probe](../../engineering/investigations/2026-11-24-tt-repetition-history-contamination.md).

## Mechanism and bounds of the evidence

- `Board.isRepetitionDraw()` uses the history stack, halfmove clock, search-root index and null-move floor. It recognizes an in-search cycle or a third occurrence across the pre-root history and current line. Two pre-root occurrences can still matter on the next move. Even a root with no repeated hash can acquire relevant repetition below it.
- `Searcher.alphaBeta` tests draw status before probing the current node. The suspected skipped adjudication is below the probed node. `applyTtBound` converts the mate distance to the current ply and applies EXACT, LOWER or UPPER conditions. An EXACT hit is sufficient even in a PV node. The key has no repetition history. An ancestor store may also embed a descendant's history-dependent value. Store-side filtering alone cannot prevent a prior valid win from being reused where a defending cycle is now available.
- The child PV is reset at node entry. A cutoff can propagate a mate-range score while contributing zero PV moves. Iterative deepening then stops at the first completed mate-range root score. A truncated PV is evidence of an incomplete reported line. A valid mate can also use cached proof and report a short PV. A complete PV proves one line only. Verify what happens under alternative defenses before treating length as a proof criterion.
- The recovered patch skips all mate-range TT returns in `alphaBeta`, including bounds and both signs. It demonstrates the leverage of that decision, while leaving the exact triggering bound, store path and root stop contribution to be traced. It does not change the singular-extension guard. Search internals are otherwise out of scope, including ordinary centipawn #287 reuse unless diagnostics show it is necessary for the mate case.

## Diagnostic work and regression seam

1. Use the existing PGN at ply 93 for a conversion trial and its complete late prefix for a deterministic fixture. Warm the TT through a fixed sequence of historical searches. Record the first root move that repeats or makes no mating progress. Isolate one post-root probe by keeping the prefix, options and fixed search depths identical. Log for that probe the key, entry generation/depth/bound/score, stored best move, post-conversion score, search ply and window, root iteration stop reason, and the history keys since the last irreversible move. Capture caller context for the corresponding store if possible. Diagnostic switches stay in disposable builds, gated and off by default.
2. Re-run the same root and move with fresh history, same-context warm history, cross-context warm history and cleared TT. For the observed probe only, force a search and exhibit the adjudicated repetition in a skipped descendant if it occurs. Include a two-way warm-history test to distinguish a cached win reused into a draw from a cached draw reused into a win. Test one arm that allows the root to continue after a cached mate and a separate arm that discards the suspect cutoff. Preserve both disproofs and successful traces. Stop before choosing production behavior if the difference is explained by move ordering alone or cannot be isolated.
3. Use the public search result and a replayed `Board` with one shared `TranspositionTable` as the regression seam. Extend the existing `TtRepetitionContaminationProbe` for investigation and add a normal test in `engine-core/src/test/java/coeusyk/game/chess/core/search/` once the failing sequence is pinned. Reuse the board reconstruction shape of `TerminalAdjudicationTest`; use UCI subprocess tests only to verify that actual game history and TT lifetime reach that seam. The red test must expose the wrong move or root outcome with a genuinely warm table, at fixed work, and pass at that same budget on the selected correction. Confirm that the negative controls can distinguish an actual draw and ordinary move-order differences. Record the exact moves, FEN and fixed depths from step 1 with the candidate decision.

## Compare corrective alternatives

| Alternative | Evidence needed | Tradeoff |
| --- | --- | --- |
| Suppress mate-range score cutoffs, retain TT ordering | Exercise both signs and keep the existing EXACT, LOWER and UPPER applicability checks. Compare fixed replay, timed conversion and per-position nodes. | Small implementation surface. Re-searches cached mates and can change search work even before the root reports a mate. |
| Revalidate a TT-dependent root mate with the same history | Trace whether a fresh search of the root with suspect mate cutoffs suppressed recovers a valid move and how often completed results survive cancellation. Trace fail-high/low and completed speculative iterations independently. | Preserves interior TT benefits in ordinary searches. With budget exhaustion it needs a defined fallback to a completed result. Simply ignoring early stop may repeat the same invalid cutoff at higher depth. |
| Continue after a mate that used cached mate scores | Keep TT cutoff behavior. Measure whether later iterations choose a better move and whether the budget or depth cap can still expose a stale mate. | Small control-flow change. Can consume more of the budget, and keeps the shortcut that skipped adjudication. |

A history-aware TT contract remains outside this comparison. A store-side repetition flag alone misses reuse of a valid old mate when the new history permits a draw.

Prefer the narrowest guard that passes the documented counterexamples. A gate based only on repeated hashes already present at the root and a gate based only on PV length are inadequate correctness criteria. Keep score conversion, move ordering and singular verification unchanged unless the trace requires a revised plan. In `Searcher.java`, the production ownership is the selected score cutoff or root acceptance condition. `TranspositionTable.java` and `Board.java` need changes only if the evidence justifies a history-aware contract. Return the selected predicate, fixture, compatibility cost and resulting scope for approval before editing production code.

Compare three arms on identical fixtures and against the unmodified policy. The blanket guard ignores any applicable mate-range score return, for either sign and every bound, and keeps the hash move. The verification arm repeats a completed, mate-range PV1 iteration if the search took any mate-range TT cutoff in that iteration. Its second search uses the same root, iteration depth, original hard-stop predicate and shared TT, with a full root window and mate score cutoffs suppressed only in the verifying Searcher. Publish only the completed second result. On cancellation, retain the latest completed iteration and use the existing legal fallback when there is none. The continued arm keeps cutoffs and skips the mate stop on a mate-range iteration that consumed one. Keep genuine fast mates and the previously valid truncated PV control. Prefer the smallest arm that passes both fixed-history fixtures and converts reliably. Extra verification must earn its cost.

For the disposable verification arm, test cancellation before the pass and during recursion, compare published results with the last completed iteration, and inspect whether a verified mate or centipawn score is published. Check that the original time-manager deadline survives the second search. With Lazy SMP, check that verification is local to each Searcher, helpers share the existing table, and the pass rejects eligible mate scores regardless of the writer. Preserve the existing UCI boundary. Request helper abort before publishing `bestmove`, then join helpers before the next state mutation. Run the controlled cancellation separately from timed conversion.

## Acceptance and verification

- Verify legal terminal mate and a warm-table conversion with a fixed defending line that previously led to a draw. Add true mate-in-one, multi-move mate with a legitimately truncated TT PV, a mate loss, a no-mate repetition draw and same-history warm/cold controls. Retain the halfmove-99 mate precedence and qsearch/null-move coverage. Assert legal moves and terminal outcomes. Treat PV replay, mate-distance progression and retained iterations as supporting evidence.
- Screen at ply 93 with one native Windows run per arm and profile, using the historical 3000 ms/150 ms budgets and a 120-ply cap. Add two serial control and proposed-arm pairs per profile, alternating order, for a bounded follow-up. Log both sides' moves, depth, score and exit reason. A profile whose control never repeats supplies conversion evidence without discriminating the repetition failure. Retain the fixed-work plys 109 and 123 as the regression gates. Run searches serially with the NNUE work idle.
- Build the disposable arms with `mvn -o -B -pl engine-core,engine-uci -am package -DskipTests`. Run focused terminal adjudication and signed mate-score controls on each arm. Measure two serial native Windows executions per arm of the 31-position Classical `java -jar engine-uci/target/engine-uci-0.6.1-SNAPSHOT.jar --bench-raw 13`, with the arm order reversed for the second round. Report the per-position and aggregate node deltas and the native NPS against the floor of 301,116. These run counts screen the options. For a production candidate, run `mvn -B clean test`, `mvn -B -pl engine-core -Psearch-regression test`, the approved conversion matrix, and the Phase 21 native NPS qualification with one discarded warm-up plus seven runs per arm. Justify every changed golden.
- Under [#305](https://github.com/coeusyk/chess-engine/issues/305), qualified WSL2 remains valid for Threads=1 strength validation. Use native Windows for absolute NPS and externally visible 1T and 8T conversion behavior. Measure correctness, deterministic nodes, native NPS and conversion before settling the strength protocol. Propose a short test at 5+0.05, Classical, Threads=1, paired openings, with non-inferiority bounds -5/0 Elo and alpha and beta 0.05. Freeze the game cap from paired variance and resource limits with owner approval. Consider a longer time control if the short test is inconclusive or if the conversion or bench results suggest a time-budget interaction. No SPRT is authorized here.

**Execution record.** The [fixed-depth trace and controls](../../engineering/investigations/2026-11-27-manual-chesscom-games.md#fixed-depth-trace-of-the-cutoff-and-the-early-stop) pin the EXACT entry `4d4b309b56cd9ad3`. Its ply-105 store and ply-109 hit have different reversible histories. With the hit bypassed the third occurrence is reached in a descendant, while bypassing early stop on its own lets a pawn-advance continuation take over at depth 6. Ply 109 exposes the baseline's premature cached mate. Ply 123 is a history-adjudication control: even baseline draws with the full prefix and returns mate with the same warm table and a FEN-only root. No reverse cached-draw counterexample was established. Candidate comparison and its review completed at Revision 3, and the owner subsequently approved the blanket guard for production implementation and SPRT preparation (recorded below).

A second fixed-depth baseline replay produced identical moves, scores, depths and PVs through ply 109. The independent review of revision 2 checked the store, hit, score conversion, descendant paths and the final full-history versus FEN-only control against the raw logs. Its cutoff wording correction is in the case study. Revision 3 superseded the earlier draft SPRT invocation; the prepared WSL2 `#305` SPRT protocol is recorded under Production implementation tracking below.

### Candidate comparison execution

The four modes use one disposable JAR with a process-level switch. `base` keeps the current behavior. All fixed-depth replays use Threads=1, Hash=64, Contempt=50 and depth cap 13. Each arm warms its own table on the played Black prefixes 93 onward. For the ply-123 control, the same arm and warm-up are used with only the target root reconstructed from FEN.

| Arm | Ply 109 full history, first pass | Ply 123 full history, forced Kf6 | Ply 123 FEN-only, forced Kf6 |
| --- | --- | --- | --- |
| Base | Kf6, depth 2, M8, 1 PV move | depth 13, -50 cp | depth 4, M10 |
| Blanket guard | Ke5, depth 10, M6, 11 PV moves | depth 13, -50 cp | depth 13, M8 |
| Verification pass | Kf5, depth 10, M6, 11 PV moves | depth 13, -50 cp | depth 13, M8 |
| Continue after cached mate | Kf5, depth 13, M6, 11 PV moves | depth 13, -50 cp | depth 13, M8 |

Repeating the identical ply-109 target in the same game retained the move and score in all four arms. With the same history and a cold table at ply 109, all four arms chose Ke5 and M6 with 11 PV moves. Baseline, blanket, and verification finished at depth 10. Continuation reached the depth-13 cap. On the warm repeat, continuation accepted its cached mate with a one-move PV at the cap. The key point at ply 123 is adjudication of the forced move with the complete prefix. Stockfish play from the earlier position is a separate conversion check. No reverse cached-draw example has been established.

The focused Maven runs of `MateCandidateProbeTest,TerminalAdjudicationTest` passed on all four arms, 14 checks executed per arm. The dedicated first-iteration verification cancellation check was skipped there by design and then passed in its own invocation. The signed EXACT seeds were +99997 and -99997 at the child of e2e4, which is an ordinary centipawn position. Base and continuation published the corresponding opposite-sign mate-range score at the depth-2 cap. Blanket and verification both returned -9 cp. The genuine mate-in-one still finished at depth 1 in every arm. A separately solved mate-in-two had a valid cached score and a one-move PV on the baseline at depth 2. The blanket and verification arms returned the same mate score at depth 3 with a complete PV. Continuation returned the same score at depth 6 with the cached one-move PV. The genuine losing side returned -99998, and the direct threefold control remained a draw.

The verification prototype never publishes the provisional mate or an aborted second search. Injecting cancellation at the first deadline poll or after 25 polls at ply 109 returned the last completed depth-1 result, `d4e5` at +960 cp, in both cases. A partial +995 cp result from the second pass was discarded in the 25-poll arm. For a real `go movetime 150` request, the original usable hard limit was 120 ms. Verification started at 117 ms, cancelled at 120 ms and returned the previously completed depth-7 result at +1074 cp. With cancellation of the first available iteration, the search returned a legal fallback with depth 0 and no mate PV. A completed pass could publish either a centipawn result or a mate using the verified move and PV.

At Threads=2, the main search and helper each entered their own verification pass. Both skipped signed mate TT returns, including current-generation records. They continued to share one table. The per-Searcher flag gates each probe after score conversion, independent of which worker wrote the entry. The trace identifies the entry generation and reader thread, without attributing every entry to its writer. The timed main search retained the 370 ms hard limit and completed verification at 218 ms with M6. The helper had exited at publication in this run, with zero pending and zero exceptions. The source requests helper abort before publication and joins the helper futures before the next state mutation. The trace does not make helper completion a prerequisite for publishing.

Five existing UCI lifecycle checks also passed with the verification property applied to the subprocesses. They cover rapid go/stop, slow helper drain and commands that change state. The independent comparison review clarified the UCI drain boundary and the role of ply 123 in the regression suite. The reviewer found no blocker to proposing the blanket guard.

### Deterministic bench and native throughput

Two serial executions per arm, reversing order in round two, used Zulu JDK 21.0.10 on native Windows. Each ran the 31-position Classical depth-13 corpus with Hash=16 MB and per-node instrumentation off. Both executions of each arm produced identical node counts for every position.

| Arm | Main nodes | Delta | Native NPS, rounds 1 and 2 |
| --- | ---: | ---: | ---: |
| Base | 23,222,204 | 0 | 355,612 / 363,147 |
| Blanket guard | 22,431,869 | -790,335 (-3.40%) | 361,710 / 362,582 |
| Verification pass | 23,222,204 | 0 | 358,865 / 369,898 |
| Continue after cached mate | 23,222,204 | 0 | 361,024 / 361,165 |

The blanket guard changed 24 positions. Position 8 expanded by 159,827 nodes, while positions 15, 31 and 29 searched 352,088, 336,180 and 193,848 fewer nodes respectively. All blanket runs cleared the 301,116 absolute floor. The sample is too small to establish a throughput gain, and the node reduction does not establish a strength gain. The fixed corpus shows the guard's effect on the tree. The conversion runs measure behavior when the TT carries across moves.

### Native conversion

All games started immediately after 47.gxh5 with the original prefix, a persistent Vex table, 3000 ms per Vex move and 150 ms per Stockfish 18 move. The 1T profile used Hash=64 and Contempt=50. The 8T profile used Hash=256 and Contempt=0. One screen per arm and profile was followed by two additional base/blanket pairs in opposite arm order.

| Arm | 1T mates / repetitions | 8T mates / repetitions | First round Vex move time, 1T / 8T |
| --- | ---: | ---: | ---: |
| Base | 2 / 1 | 1 / 2 | 6.24 s / 3.56 s |
| Blanket guard | 3 / 0 | 3 / 0 | 6.77 s / 4.27 s |
| Verification pass | 1 / 0 | 1 / 0 | 6.63 s / 6.09 s |
| Continue after cached mate | 1 / 0 | 1 / 0 | 29.77 s / 26.78 s |

Move time is the sum of the runner's measured Vex request/response times. A draw can finish earlier than a successful conversion. These are small, timed samples and do not estimate general win rate. The base repeated in both profiles, so the fixture discriminated at both settings.

### Correction proposed for implementation approval

Add one guard at the existing return from `applyTtBound`: use the applicable converted TT score only when its absolute value is below `MATE_SCORE - MAX_PLY`. Keep the table lookup, hash-move ordering, score conversion, stores, singular verification, and root mate stopping behavior. The guard covers both signs and every bound that would otherwise return a mate score. It addresses the skipped adjudication mechanism directly and needs no extra search, clock transition or worker state. Verification did not improve the observed conversion outcomes, had to reject multiple shallow provisional mates, and adds per-search cancellation and publication rules. Continuation just postpones acceptance. It kept the bad score through the fixed cap in the seeded controls and took materially longer in the first native conversion round.

Carry the two complete histories forward at the public search/UCI seam. Ply 109 and the signed synthetic scores provide the red-before coverage. Ply 123 protects the forced full-history draw with its FEN-only mate control. Include correct mate scores of both signs, true fast mates, a seeded but independently verified mate whose cached PV is legitimately truncated, and repetition and same-history warm/cold controls. Add per-bound cases if the production predicate moves inside the existing bound switch. The reverse cached-draw counterexample remains unestablished. An accepted implementation would still need full regression checks, a native throughput qualification and approval of the short 1T non-inferiority protocol.

Raw logs, source patch, fixtures runner, per-position bench summary and native conversion game traces are preserved under the ignored `tools/output/manual-games-2026-11-27/mate-tt-candidates/` directory.

### Production implementation tracking

- **Approved revision and approval reference:** Revision 3 (`docs/superpowers/plans/2026-11-27-mate-tt-repetition.md`) branched from `origin/develop` (`a09303d`); owner approved the blanket mate-range TT score-cutoff guard ("reject cached mate-range score cutoffs of either sign while preserving the TT hash move for ordering"), rejecting root verification and continued iterative deepening.
- **Behavioral test seams:**
  1. `Searcher.applyTtBound`: rejects stored mate-range scores with `Math.abs(entry.score()) >= MATE_SCORE - MAX_PLY` before `scoreFromTT` (covering all mate-range converted scores as well) for both signs across `EXACT`, `LOWER_BOUND` and `UPPER_BOUND`, while preserving ordinary centipawn cutoffs, depth gating, bound checks and `scoreFromTT` normalization.
  2. `Searcher` + `Board` + shared `TranspositionTable`: full-history ply-109 and ply-123 fixtures from `2500_black.pgn`, signed synthetic mate seeds (`+99997`, `-99997`), interior TT hash-move ordering preservation when a mate-range cutoff is rejected, genuine fast mate-in-one, valid cached mate-in-two PV recomputation, genuine mate loss, direct threefold draw and same-history cold/warm controls.
  3. `UciApplication` subprocess lifecycle: persistent table and full `position ... moves ...` history through the UCI protocol reach both the ply-109 move choice and ply-123 forced-move draw adjudication seams.
- **Slice progress and verification evidence:**
  - Slice 1 (worktree + plan approval record): done on branch `fix/287-mate-tt-cutoff-guard`.
  - Slice 2 (failing regression tests, TDD red): done; `SearcherTest.ttBoundRejectsMateRangeScoresOfBothSignsAcrossAllBounds`, `MateTtRepetitionRegressionTest` (4 of 5 methods), and `UciApplicationIntegrationTest.warmTableAndFullPositionHistoryRejectPrematureMateCutoff` failed on `a09303d` as expected.
  - Slice 3 (minimal guard in `Searcher.applyTtBound`, TDD green): done; rejects `Math.abs(entry.score()) >= MATE_SCORE - MAX_PLY` before `scoreFromTT` and the bound switch, keeping all targeted core and UCI regressions green and updating `NodeCountRegressionTest` `FEN[4]` (`7829` to `7831`, identical `d7d2`, score `1567`, 8-move PV).
  - Slice 4 (integrated suites, bench, native conversion & Phase 21 native NPS qualification): done; `mvn -B clean test` (570 core, 100 UCI, 43 API, 162 tuner, 0 failures; `MateTtRepetitionRegressionTest` 31.50 s locally), `-Psearch-regression` (3/3, WAC 20/20, 0 flips), `tools/tests` (303 passed, 9 `pwsh` skips), `NpsBenchmarkTest` (383,848 mean NPS on pre-review guard), fresh native conversion matrix on production JARs (`baseline-a09303d.jar` SHA-256 `30275026bc7bd0396e86cc772e9b39679f9bbf8a687aef6e69fb4dad4ad2f616` vs pre-review `candidate-guard.jar` SHA-256 `ec43e3774e35c4725fd8373b1e3576dfae55be1538f681dcd703e0ab2944a82a`, 6/6 mates vs 2/6 baseline across 1T and 8T, separate from Codex's screening run on disposable `candidates.jar` SHA-256 `995816c9e49383236041c25a18eaebd63679326a96947400886b6c4735d92d87` where baseline mated 3/6), Phase 21 native NPS qualification on `ec43e3774e35c4725fd8373b1e3576dfae55be1538f681dcd703e0ab2944a82a` (22,431,869 nodes, median 359,727 NPS, aggregate 361,129 NPS vs control 23,222,204 nodes, median 360,006 NPS, aggregate 360,394 NPS; floor 301,116), and post-review `candidate-guard.jar` (`f4a541077afc6814ddc05ec4a62ce512588187da56f6e997e92bca9457f219a5`, differing only by checking `entry.score()` before `scoreFromTT`) `--bench-raw 13` re-check (22,431,869 nodes, 373,136 NPS).
  - Slice 5 (independent implementation review, Phase 22 dev entry, PR & prepared WSL2 SPRT): done; Claude Code independent review cleared all code and test items (`applyTtBound` stored-score guard, T1 interior ordering test, T3 ply-123 UCI test) and identified three doc items (B1 relative openings path, B2 cap basis wording, B3 full JAR SHA-256 attribution), all resolved below.
- **Prepared WSL2 non-inferiority SPRT protocol (#305, not launched):**
  - Environment: qualified WSL2 (`tools/sprt_linux.py`) under `#305` (`docs/engineering/investigations/2026-11-24-wsl2-operational-qualification.md`), with preflight host headroom gate (30 s window, mean CPU <= 15%, >= 8 GiB host RAM, >= 10 GiB VM RAM, 0 swap) and continuous host pressure sampling.
  - Frozen artifacts: `baseline-a09303d.jar` (built from `a09303d1b1b42301ddf897006f314245c97ea81f`, SHA-256 `30275026bc7bd0396e86cc772e9b39679f9bbf8a687aef6e69fb4dad4ad2f616`) and `candidate-guard.jar` (post-review production build on `fix/287-mate-tt-cutoff-guard`, SHA-256 `f4a541077afc6814ddc05ec4a62ce512588187da56f6e997e92bca9457f219a5`), staged in `tools/output/mate-tt-sprt-2026-11-28/`.
  - Parameters: `Elo0=-5`, `Elo1=0`, `Alpha=0.05`, `Beta=0.05`, `TC=5+0.05`, `EngineThreads=1`, `Concurrency=6`, `MaxGames=30000`, `OpeningOrder=random`, `Seed=287`. Cap basis: under the clean `#296` / WSL2 Stage 3 pair variance (`0.0799` / `0.08216`), the owner-specified 30,000-game cap provides about 12% headroom over the SPRT worst-case expected length (~26,750 games at true Elo `-2.5`), though it is below a 95%-power fixed-sample size (~33,400 games), so reaching 30,000 games without hitting a bound resolves as `INCONCLUSIVE`.
  - Openings: `tools/results/phase21-stage6/20261011T061318503Z-b5a507c6/noob_3moves.epd` (tracked in-repo, 150,932 4-ply openings, SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`, identical to the gitignored `tools/noob_3moves.epd`), paired color-reversed (`-repeat`).
  - Symmetric engine options: `Threads=1`, `Hash=16`, `PawnHashSize=1`, `EvalType=Classical`, `MultiPV=1`, `Contempt=0`, `OwnBook=false`, `SyzygyOnline=false`.
  - Invocation (requires extracted `cutechess-cli` 1.5.1 AppImage binary, SHA-256 `d9448693e45bd57f1aeb32c46e94466894cd7cc5b6937effd285a02e871387b5`; run `--prepare-only --argument-manifest-path ...` first, then launch with `--expected-argument-manifest-path ...` only after explicit owner approval):
    ```sh
    CUTECHESS=/path/to/cutechess-cli python3 tools/sprt_linux.py \
      --old tools/output/mate-tt-sprt-2026-11-28/baseline-a09303d.jar \
      --new tools/output/mate-tt-sprt-2026-11-28/candidate-guard.jar \
      --tag mate-tt-noninf --elo0 -5 --elo1 0 --alpha 0.05 --beta 0.05 \
      --tc 5+0.05 --concurrency 6 --engine-threads 1 --max-games 30000 \
      --openings-file tools/results/phase21-stage6/20261011T061318503Z-b5a507c6/noob_3moves.epd \
      --opening-order random --seed 287 \
      --old-option Hash=16 --old-option PawnHashSize=1 --old-option EvalType=Classical \
      --old-option MultiPV=1 --old-option Contempt=0 --old-option OwnBook=false --old-option SyzygyOnline=false \
      --new-option Hash=16 --new-option PawnHashSize=1 --new-option EvalType=Classical \
      --new-option MultiPV=1 --new-option Contempt=0 --new-option OwnBook=false --new-option SyzygyOnline=false \
      --log-path tools/output/mate-tt-sprt-2026-11-28/match.log \
      --pgn-path tools/output/mate-tt-sprt-2026-11-28/match.pgn \
      --argument-manifest-path tools/output/mate-tt-sprt-2026-11-28/argument-manifest.json \
      --provenance-path tools/output/mate-tt-sprt-2026-11-28/provenance.json
    ```
- **Outstanding work and next action:** Commit, push, open the production PR, verify green CI, and await owner approval before launching the prepared WSL2 SPRT or merging.
- **Integrated verification and final review status:** Integrated verification complete; Claude Code independent review cleared all code and test items and doc blockers B1 through B3 were resolved.

To reproduce in a disposable worktree at `a09303d`, apply the archived `candidates.patch` and copy `MateCandidateProbeTest.java` into `engine-core/src/test/java/coeusyk/game/chess/core/search/`. Set `PYTHONPATH` to the case-study fixture directory containing `uci_probe.py` after installing `chess==1.11.2`. In the commands below, `$evidence` is the archive directory, `$fixture` is the complete 2500 Black PGN, and `$jar` is the packaged diagnostic JAR. The measured JAR SHA-256 is `995816c9e49383236041c25a18eaebd63679326a96947400886b6c4735d92d87`.

```sh
mvn -o -B -pl engine-core,engine-uci -am package -DskipTests
for arm in base blanket verify continue; do
    mvn -o -B -pl engine-core test -Dtest=MateCandidateProbeTest,TerminalAdjudicationTest -DmateCandidate="$arm"
    python3 "$evidence/replay.py" "$jar" "$arm" "$fixture" --repeat-target
    python3 "$evidence/replay.py" "$jar" "$arm" "$fixture" --cold
    python3 "$evidence/replay.py" "$jar" "$arm" "$fixture" --target 123 --force g7f6
    python3 "$evidence/replay.py" "$jar" "$arm" "$fixture" --target 123 --force g7f6 --fen-only
done
mvn -o -B -pl engine-core test -Dtest=MateCandidateProbeTest#verificationCancelledOnFirstIterationReturnsAnUnscoredLegalFallback -DmateCandidate=verify -DmateCandidate.cancelPoll=0
python3 "$evidence/replay.py" "$jar" verify "$fixture" --cancel 0
python3 "$evidence/replay.py" "$jar" verify "$fixture" --cancel 25
python3 "$evidence/replay.py" "$jar" verify "$fixture" --timed-target 150
python3 "$evidence/replay.py" "$jar" verify "$fixture" --timed-target 400 --threads 2
JAVA_TOOL_OPTIONS="-DmateCandidate=verify" mvn -o -B -pl engine-core,engine-uci -am test "-Dtest=UciSearchLifecycleTest#rapidGoStopCyclesEmitOncePerGoAndDrainEveryHelper+slowToDrainWorkerNeverLeavesPositionOrGoOnStaleState+stateChangingCommandsDrainTheRunningSearchFirst" -Dsurefire.failIfNoSpecifiedTests=false
python3 "$evidence/native_matrix.py" "$jar" "$fixture" "$evidence/replay-conversion" conversion
python3 "$evidence/native_matrix.py" "$jar" "$fixture" "$evidence/replay-bench" bench --rounds 2
python3 "$evidence/native_matrix.py" "$jar" "$fixture" "$evidence/replay-followup" conversion --arms base blanket --rounds 2 --reverse
python3 "$evidence/summarize_bench.py" "$evidence/replay-bench"
```
