# Phase 20 Stage 6: fixed-time practical qualification

## Status and scope

Frozen before execution against repository commit `1bf47c4544decb72a1fa25e7d6ad7b8e199dc115`. This document resolves Stage 6's open choices in the [qualification preregistration](phase20-smp-qualification-preregistration.md), subject to the [Stage 2 interpretation amendment](phase20-smp-stage2-interpretation-amendment.md). Stage 3's accepted record is in [Phase 20](../../../dev-entries/phase-20.md), with artifacts under `tools/results/phase20-stage3/20260926-071358/`.

Stage 1 lifecycle/correctness gates and Stage 3 fixed-depth qualification passed. Stage 3 paired median main-work factors were 1.305/1.656 at 2T/4T; corpus TTD speedups were 1.132/1.448; main NPS retention was 0.9560/0.9024. H3 contention was not detected at the frozen power, and aggregate throughput showed no additional production-SMP overhead. Stage 6 entry conditions are met. Mandatory Stage 4/5 triggers did not fire. Occasional hashfull=1000 does not require Stage 4; optional capacity characterization is omitted. Stage 5 is not indicated.

This is a plan only. No implementation, packaging, benchmark, game, SPRT, tuning, or search/time-management change is authorized by this document. Stage 2/3 are not repeated. Execution needs a separately prepared harness implementing these frozen rules.

## 1. Question and hypotheses

At the same nominal wall-time allowance, does production Lazy SMP improve the main thread's completed depth or agreement with a deeper 1T search without buying that improvement through extra time? Does the result survive Vex's existing clock allocation and stability scaling?

- Practical benefit: depth and deeper-reference agreement are both no worse than 1T, at least one improves outside the same-session 1T spread, and time use does not inflate outside that spread.
- Neutral or harmful result: more aggregate nodes need not produce better main-thread depth or agreement. A clear regression fails qualification; effects inside the spread remain unresolved.
- Time-management limitation: NT consumes more time outside the spread without a depth improvement outside the spread. Gains accompanied by inflated time cannot establish equal-time usefulness either.
- Result-selection limitation: a helper can finish deeper, choose a different move, and agree with the reference when the emitted main move does not. Report the distribution; no invented frequency threshold or helper substitution.

Depth and agreement are qualification proxies, not estimates of Elo. The reference is the same selective search at greater depth, not an oracle.

## 2. One timing protocol

Use all 31 canonical `BenchRunner.BENCH_FENS`, unchanged and in canonical order, for two complementary modes of one representative protocol:

| Mode | Exact UCI command | Frozen interpretation |
|---|---|---|
| Fixed allowance | `go movetime 1950` | 1,950 ms requested; 1,920 ms usable after overhead |
| Clock allocation | `go wtime 60000 btime 60000 winc 600 binc 600` | Equal initial clocks of 60 seconds, increment 0.6 seconds, sudden death |

Explicitly set `MoveOverhead=30` ms in every arm, matching the production default. No other movetime, clock state, increment, `movestogo`, depth cap, node cap, `searchmoves`, ponder, or external timed stop is used in measured searches. UCI's existing timed-search maximum depth remains unchanged.

The choice comes from `tools/sprt.ps1`: its default TC at this commit is `60+0.6`. It is a prospective match TC, not a claim that it is optimal. In `TimeManager.configureClock`, effective move number zero gives divisor 40, soft limit `60000/40 + 600*3/4 - 30 = 1920` ms, and hard limit `min(60000/3, 1920*2)-30 = 3810` ms. Thus 1,950 ms movetime matches the clock arm's unscaled soft budget exactly. This selects one timing scale without a pilot or an exploratory timing matrix.

Each root is sent as `position fen <canonical FEN>`, with no replayed moves. `UciApplication.runSearch` computes move number from `(boardStates.size()-1)/2`, so **effective move number is zero for every root**; the FEN fullmove field does not change this. Preserve all FEN fields and do not fabricate histories or modify the allocator to obtain a later move number. Each clock sample starts afresh at the stated clocks; clocks are not decremented between independent positions.

This exercises the real UCI clock and stability-management path on the established corpus. It characterizes the initial-clock allocation regime, not every stage of a played game. Later-game divisors, depleted clocks, and repetition history are outside this bounded qualification. A later match at another TC needs a new timing preregistration.

`go movetime 1950` has soft=hard=1920 ms after MoveOverhead=30. Stability scaling can shorten that search but cannot extend it beyond 1920 ms. This is the stricter equal-budget arm. The `60+0.6` clock arm has soft=1920 ms and hard=3810 ms, so instability can extend the search beyond its initial soft budget; this arm qualifies the real production clock allocator. Preserve that behavior and measure actual time; do not disable scaling or call an early exit a timing failure. Clock soft thresholds can be scaled by 0.75, 0.85, 1.0, or 1.20; the hard limit remains unchanged. Keep the two mode results separate.

## 3. Arms and schedule

Require `Threads=1`, `Threads=2`, and `Threads=4` in both modes. 2T is the primary practical comparison and prospective strength-test candidate. 4T is a secondary qualification comparison: Stage 3 found greater speedup there, so omitting it would leave the established scaling result unqualified at fixed time. A 4T success cannot rescue a failed or unresolved 2T result.

Use one persistent engine JVM per arm, with only one searching at a time. Discard one complete corpus warm-up per arm per mode. Then take seven measured corpus passes, matching the existing Phase 20 repetition policy. For each position, run all six arm/mode combinations consecutively before advancing to the next position.

- Thread order for passes 1–7: `(1,2,4)`, `(2,4,1)`, `(4,1,2)`, `(1,2,4)`, `(2,4,1)`, `(4,1,2)`, `(1,2,4)`.
- For each position, run one mode across that thread order, then the other. Movetime comes first when pass number plus one-based position number is even; clock comes first otherwise. Warm-up uses thread order `(1,2,4)` and the same rule with pass number zero.
- Before each search, verify the previous search's `drained` record and all helper exits, then send `ucinewgame`, set the arm's options, and wait for `isready` before setting the position. Keep TT/search history cold between roots.
- Pair observations by position, measured pass, and mode. No simultaneous arm execution, position selection, repeats of inconvenient samples, or adaptive reordering.

Budget: `31 × 3 × 2 × 8 = 1488` timed searches, of which 186 are discarded warm-up and 1302 are measured. Each arm/mode has 217 measured observations. Add exactly one reference corpus pass, at most 31 searches. No extension is allowed for inconclusive results.

## 4. Deeper 1T reference

After all timed observations are immutable, define for each position:

`Dmax = maximum completed depth of any measured main or helper result in either mode and any arm`. Including helper depth only ensures that the deeper 1T reference target exceeds all observed search work. It does not imply that a helper's deepest result existed or was usable at the main `bestmove` instant.

Warm-up results do not set the target. Freeze a target manifest before running references: `Dref = Dmax + 2`. This ensures the two confirmation depths are deeper than every timed result, including helpers, and avoids assuming that the old depth-13 baseline is always deeper than a timed search.

Run one cold, untimed, production-UCI `Threads=1` search per position with `go depth Dref`, the same evaluator, Hash, options, JVM and host. Retain completed-depth info and the final diagnostic result. The reference move is resolved only if depths `Dmax+1` and `Dmax+2` both complete and their best moves agree. Use the final move as the reference. Adjacent deeper-depth agreement is a minimal stability check, not proof of correctness; do not increase depth until a preferred move stabilizes.

If the target exceeds the supported maximum, a forced-mate stop prevents both confirmation depths, either depth is missing, the moves disagree, or the reference hits the watchdog, mark that position's reference unresolved. Record the reason, observed depths, moves and scores. Do not substitute a shallower or shorter timed reference. A reference timeout stops that reference search, verifies drainage, and leaves it unresolved; a lifecycle failure stops the stage.

Use the existing Stage 3 10-minute per-search watchdog as an execution guard, including helper drain; it is not a search-quality threshold. Reference work is at most 31 such windows (310 minutes). Timed configured search allowances total about 71 minutes across warm-up and measurement, before harness/JVM/drain costs. Set a six-hour total stage execution guard; reaching it preserves partial artifacts and makes qualification incomplete. These are resource bounds, not performance acceptance cutoffs. No retries or reference deepening beyond the frozen pass.

## 5. Required records and metrics

Keep raw stdout/stderr with arm, mode, pass, position, search ID, command, timestamps and warm-up status. Preserve the corrected Stage 3 accounting of exits captured before as well as after `bestmove`; thread scheduling does not guarantee event-print order.

For every timed and reference search record:

- **Completed depth:** final main `SearchResult.depthReached`, from `event=bestmove main_depth`; completed depth is not seldepth or an unfinished iteration's depth.
- **Nodes:** final reported main nodes, every helper's exit nodes, and their sum. Keep iteration node records separately; main UCI nodes exclude helpers. Node volume is descriptive, not a benefit gate.
- **Move:** emitted legal UCI bestmove, final main move and PV; verify main-result ownership. Preserve score/mate and aborted status for interpretation.
- **Reference agreement:** exact UCI move equality with a resolved reference. Report counts, proportions, NT-only agreement wins and 1T-only agreement losses. Do not apply centipawn equivalence tolerances.
- **Time used:** engine `search_elapsed_ns` to bestmove as the primary wall-time measure, harness monotonic go-to-bestmove as an external check, final iteration time, and `drain_elapsed_ns` separately. Do not conflate drain with thinking time. Report configured limits, raw overruns, and distributions of both bestmove and drain time.
- **Helpers:** each helper's identity, deepest completed depth, best move, nodes, start/exit times, exit cause and lifecycle/TT boundary counters. Report the maximum helper depth, every helper tied at that maximum, and each one's agreement. Do not select a favorable move among tied helpers.
- **Stability path:** retain each completed main iteration's depth, move and elapsed time; reconstruct the existing `Searcher` stable-depth counter and scale updates from that sequence, including the initial 1.0 and the depth-5 activation rule. Label this as derived telemetry. Final main depth/result must reconcile with the sequence. No DEBUG logging, JFR, GC profiling or new production instrumentation in timed arms.

Summaries use equal weight per position. For each mode and pass compute mean completed main depth over 31 roots, agreement proportion, and mean engine bestmove wall time. Also report per-position paired differences, depth win/tie/loss counts, move-change counts, and median/P10/P90/full range for nodes, depth, time and helpers. Separate modes and arms; do not average away a clock regression with a movetime success.

## 6. Frozen decision rules

For each mode and metric `X` (mean depth, agreement proportion, mean wall time), let `R_X` be the full range, maximum minus minimum, of the seven 1T pass summaries from this Stage 6 session. This is the inherited empirical noise-floor policy, not Stage 3's fixed-depth spread or a laptop/WSL percentage. Define `C_X(N)` as the median of the seven paired pass-summary differences `X_N - X_1`.

Unresolved references retain the denominator of 31. For a resolved position its agreement contrast is exactly -1, 0 or +1. For an unresolved position it is 0 if both arms emitted the same move, otherwise the interval [-1,+1]. Average these bounds within each pass and take their medians to bound the corpus agreement contrast. Likewise bound each 1T pass's agreement proportion; use the largest possible seven-pass range as `R_agreement` for regression/improvement tests. Publish resolved-only agreement descriptively, never as a replacement gate. This prevents filtering difficult reference positions into a favorable result.

An arm qualifies as **practically useful** only if, in **both modes**:

1. Lifecycle, legality, ownership and data-validity gates pass for every required timed observation.
2. `C_depth >= 0` and the lower bound of `C_agreement >= 0` (no observed corpus regression).
3. `C_depth > R_depth` or the lower bound of `C_agreement > R_agreement` (a benefit outside the 1T spread).
4. `C_time <= R_time` (no time inflation detectable at the frozen resolution).

Zero is the no-change boundary; no Elo, percentage, minimum-depth-gain or arbitrary agreement margin is introduced. A positive effect within the spread is insufficient. These empirical spread rules are a qualification convention, not a confidence interval or a formal equivalence test.

Classify a depth regression when `C_depth < -R_depth`, an agreement regression when even the upper contrast bound is below `-R_agreement`, and time inflation when `C_time > R_time`. If inflation occurs without `C_depth > R_depth`, label time-management limited. Inflated-time gains do not qualify. Otherwise a failure to meet the conjunction is neutral/inconclusive as appropriate; report the failed component and reference uncertainty. Missing timed observations or a total-budget stop make the stage incomplete, not a smaller successful experiment.

Report 2T and 4T independently. Full per-position results remain visible even when the corpus passes; this gate does not promise every position improves.

## 7. Helper-deeper/different-move cases

For each NT search enumerate helpers whose completed depth exceeds the main depth and whose move differs from the emitted move. Report their frequency, depth advantage and resolved-reference contingency counts: helper-only agreement, main-only agreement, both/neither, and unresolved. Report searches and helper records separately so three helpers do not triple-weight 4T's search frequency.

A helper-only agreement is evidence of a missed reference-agreeing candidate under existing main-only result ownership. It does not earn credit for the emitted move. Helpers are measured at exit, potentially after main completion; without per-depth helper timestamps their deepest candidate is not proven available at the main decision instant. Thus the distribution identifies a possible result-selection limitation, not an executable selection rule or demonstrated strength loss. No "often" cutoff, replacement move, tuning, or repair is introduced.

## 8. Environment and stop conditions

Preserve the qualified native environment: Ryzen 7 7700X, 8 physical cores/16 logical processors; native Windows 11 Pro build 26200; Azul Zulu OpenJDK `21.0.10+7-LTS`; Balanced power plan; no affinity; normal Windows scheduling. JVM flags remain `-Xms512m -Xmx512m -XX:+UseG1GC --add-modules jdk.incubator.vector`, with `-Dvex.smp.diagnostics=true` consistently for lifecycle/result telemetry. Freeze one executable SHA-256 and production source identity for all arms and references. A later harness commit may add tooling only; production behavior must remain identical to the inspected commit.

Classical evaluation, Hash=16 MB, PawnHashSize=1 MB, OwnBook=false, Syzygy off, MultiPV=1, Contempt=0, no ponder, and MoveOverhead=30 throughout. Record OS/JDK/CPU, JVM command, options, power plan, executable/corpus/harness hashes, schedule and a five-second pre-run background CPU sample using the established runner policy. No competing workload, simultaneous benchmark, profiler, or diagnostic overlap sketch. Continue using the real `UciApplication.runSearch` SMP path; `BenchRunner` supplies corpus data only.

Laptop or WSL inspection may prepare the harness but provides no comparable timing evidence. Do not transfer Stage 3 performance to another host. A mismatch of the frozen native environment stops execution for a new preregistration rather than silently substituting a machine or rerunning Stage 2/3.

Stop and preserve a failure transcript on helper/main exception, illegal or duplicate bestmove, wrong result ownership, missing or inconsistent lifecycle records, pending helpers at the next root, TT access after generation/clear/resize boundaries, parser/result mismatch, or a timed-search/drain watchdog expiry. Expected abort-boundary activity must be treated with the Stage 3 quiescence rules; an abort signal alone is not a generation boundary. Ordinary hard-limit aborts are valid timed results.

Stop on changed production behavior, environment or controls, exhausted total budget, or inability to collect mandatory metrics without a behavior change. Do not repair and continue within this run. Reference instability alone follows the unresolved-reference rule. Neither high hashfull nor a disappointing result opens Stage 4/5, extra timings or tuning automatically.

## 9. Evidence required for a later 2T-vs-1T strength test

All of the following are necessary before proposing execution of a separate match:

1. The accepted Stage 1 and Stage 3 gates remain valid for the same production behavior; Stage 3's 2T TTD speedup is outside its measured noise.
2. Stage 6 is complete and 2T qualifies in both modes, with agreement uncertainty unable to reverse its non-regression gate.
3. Specifically, `C_depth(2) > R_depth` in **both** modes, and neither mode has detectable time inflation. An agreement-only benefit, a 4T-only benefit, helper-only agreement, or increased node throughput is insufficient for the original section 14 strength-test requirement.
4. The prospective test uses this same JAR/configuration, native qualified host, Classical evaluator and `60+0.6` TC, with engine-specific Threads=2 versus Threads=1 confirmed in the actual launch commands.
5. Parallel games respect physical-core capacity: at most two simultaneous games is conservative even if both engines' threads are counted, `2 × (2+1) = 6 <= 8`. No oversubscription or competing timed workload.

At this commit `sprt.ps1` has `NewOptions`/`OldOptions`, appended after its common Threads option. This is newer than the original preregistration's claim that asymmetric plumbing does not exist. A later match must establish unambiguous option precedence or supply dedicated per-engine thread plumbing; do not assume duplicate Threads settings are safe. JVM/control parity, openings, SPRT hypotheses and match budget belong to that later preregistration. No match is launched here, and Stage 6 makes no strength claim.

## 10. Likely execution-harness files

- New `tools/Phase20Stage6Harness.java` and `tools/phase20-stage6.ps1`: frozen schedule, timed commands, references, artifacts and analysis; reuse the corrected accounting in `tools/Phase20Stage3Harness.java` and native environment capture in `tools/phase20-stage3.ps1`.
- `engine-uci/src/main/java/coeusyk/game/chess/uci/BenchRunner.java`: canonical corpus source, read only.
- `engine-uci/src/main/java/coeusyk/game/chess/uci/UciApplication.java`: existing UCI commands and diagnostics contract, read only.
- `engine-core/src/main/java/coeusyk/game/chess/core/search/Searcher.java` and `TimeManager.java`: completed-iteration and derived stability/allocation semantics, read only.
- Future artifacts under `tools/results/phase20-stage6/<UTC-run-id>/`: environment/options and target manifests, raw transcripts, timed/reference/helper records, paired pass summaries, decisions and failure records.

Highest-risk assumption: the cold corpus at effective move number zero and initial `60+0.6` clocks represents enough of practical time management to justify a later match. It deliberately leaves played-game clock depletion and history unmeasured; the match must supply that evidence. Deeper-reference agreement is an additional imperfect quality proxy, mitigated by confirmation at two deeper depths and explicit uncertainty bounds.
