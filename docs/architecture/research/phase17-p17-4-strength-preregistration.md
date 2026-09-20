# P17-4: Phase 17 strength-gate (SPRT) protocol, frozen before any game

Governs issue #229 step 4 and issue #230 step 4. Freezes every parameter the isolated SPRT
called for in `docs/architecture/research/phase16-p16-3-intervention-preregistration.md`
section 7, stage 4 ("one isolated, same-baseline SPRT against the current Threads=1 build. No
Elo expectation is predeclared") and section 8's stop rule ("proceed to exactly one isolated
SPRT with frozen terms"). No game has been played under this protocol. This document is the
freeze; running `tools/p17-4-sprt.ps1` against it is a separate, later action.

## Orchestration checkout vs. the two engines under test

`tools/p17-4-sprt.ps1` runs from a checkout of `phase/17-pvs-experiment` (the "orchestration
checkout"), but that checkout's own HEAD is never built as either engine under test. It
supplies only the script itself, the preregistration documents, and the location run evidence
is written to; the script requires its tracked files to have no modified or staged changes, but
does not require an empty set of untracked files (a known, pre-existing untracked directory,
`.claude/agent-memory/`, is unrelated to either engine and is recorded as evidence rather than
treated as a failure), and does not identify, diff, or otherwise check its production source at
all. Its HEAD may legitimately
carry later documentation/tooling commits without changing either engine, since that is exactly
what happened between the candidate commit and the orchestration checkout's actual HEAD when
this document was written (`e6afbb1`, `f1b40a2`, `8797512`, and possibly more by the time a run
happens).

Both engines are instead built from their own exact, frozen commit, each via its own disposable
detached `git worktree`, using the same code path (`Build-FrozenEngineJar` in
`tools/p17-4-sprt.ps1`) for both. Before building either, the script resolves the frozen SHA
with `git rev-parse` and refuses to proceed unless it resolves to exactly itself; after `git
worktree add --detach`, it independently re-checks the worktree's own `HEAD` against that same
SHA before invoking Maven. This closes a gap an earlier version of this script had: checking
only whether `engine-core/src/main`/`engine-uci/src/main` differed from the frozen candidate
commit could not see a change to `pom.xml`, a build-plugin configuration, the shade
configuration, a compiler setting, or any other build input outside `src/main` that could still
produce a different JAR while that diff stayed empty. Building from the exact commit makes that
class of gap structurally impossible rather than merely checked for, so the prior source-tree
diff and the still-earlier `Searcher.java` grep for the repaired `childIsPvNode` expression are
both removed rather than kept alongside the exact-commit build: once both engines are actually
built from git-rev-parse-verified commits, a second, weaker identity mechanism would only risk
disagreeing with the strong one later, not add real assurance.

## 1. Candidate

- Source: commit `f9b152ca4f45e8e8aa5a48092b03416aba79b230` (the isPvNode-propagation repair),
  built from its own disposable worktree as described above, never from the orchestration
  checkout's HEAD.
- This is the same candidate that passed Gate 1 (mechanism, main nodes 73,089,246 ->
  40,878,283), Gate 2 (native throughput, median elapsed 218,267 ms -> 120,860 ms), and Gate 3
  (correctness: full relevant Maven suite green, PV-node-propagation defect repaired, PV
  legality green). See `dev-entries/phase-17.md` for the full evidence trail.
- Not a command-line parameter. A different candidate commit requires a preregistration
  amendment and a code change to this script before game 1.

## 2. Baseline

- Source: commit `ebe513eabd50e853a4e24a0260c64b41a5a4b224` (the `develop` merge commit Phase
  17 itself branched from), also built from its own disposable worktree, using the identical
  build procedure as the candidate. Pinned to the full SHA, not the short form, so an accidental
  short-hash collision elsewhere in this repository's history could never silently resolve to a
  different commit.
- This is the exact pre-PVS source that produced every "pre-PVS baseline" figure already used
  throughout this phase (Gate 1's 73,089,246 nodes, Gate 2's 218,267 ms median). Confirmed
  search-source-identical to P16-2's own measured commit `31e2243`
  (`git diff 31e2243..ebe513e -- engine-core/.../search/`, empty), so "same baseline" in the
  P16-3 preregistration's own wording is unambiguous here: it is the one baseline this entire
  phase has compared every gate against, not a separately-chosen release artifact.
- Explicitly **not** the latest GitHub release JAR that `.github/workflows/nightly-sprt.yml`
  downloads. That nightly baseline convention exists for an unrelated purpose (guarding
  `develop` against regressions release-to-release) and has no documented connection to this
  phase's frozen, same-baseline requirement. Using it here would silently substitute a
  different, undocumented baseline identity for the one every other Phase 17 gate has used.
- Not a command-line parameter. A different baseline commit requires a preregistration
  amendment and a code change to this script before game 1, exactly like the candidate and the
  concurrency amendment recorded in section 4 and `dev-entries/phase-17.md`.

## 3. Engine settings

- Threads: 1 for both engines. This is a Threads=1 experiment throughout Phase 16/17; the
  strength gate measures the same substrate the mechanism and throughput gates did.
- Hash: 16 MB for both engines, set explicitly via `option.Hash=16` on both sides rather than
  left at `UciApplication`'s UCI default (64 MB). No Phase 16/17 document freezes a specific
  Hash value for strength testing specifically (`BenchRunner`'s hardcoded 16 MB only applies to
  the `--bench`/`--bench-raw` CLI path, a separate code path from real UCI play). 16 MB was
  chosen here for consistency with every other Phase 17 measurement rather than for any
  strength-specific reason; the requirement this section actually freezes is that both engines
  use the *same* explicit value, not this specific number. If a different Hash value is judged
  more representative of real play before any game is run, this document should be amended
  first, symmetrically for both engines.
- Evaluator: Classical (the default; no NNUE-specific option is set for either engine).
- No other UCI option is set for either engine; both otherwise run their own defaults.

## 4. Match

- cutechess-cli: v1.5.1. Originally frozen at v1.4.0, matching `.github/workflows/nightly-
  sprt.yml`'s then-pinned version; bumped to v1.5.1 during this hardening pass after checking
  `gh api repos/cutechess/cutechess/releases/latest`. The v1.4.0 -> v1.5.1 changelog (releases
  v1.5.0 and v1.5.1) contains only bug fixes unrelated to SPRT/game-management logic for a
  standard-variant UCI match (a Ctrl+A GUI selection bug, an off-by-one `WesternBoard`
  edge-case affecting non-standard variants, a Knight-Relay GUI crash, a `setoption` parsing
  fix, XBoard PV parsing, and a Qt5 -> Qt6 build-tooling change) and v1.5.1 itself is a
  Windows-release-build fix only, so there is no reproducibility reason to stay pinned to the
  older release. `.github/workflows/nightly-sprt.yml`'s `CUTECHESS_VERSION` was updated to
  match. **Verified, not merely recorded or assumed**: `tools/p17-4-sprt.ps1` runs the
  installed `cutechess-cli --version` and fails closed if its output does not contain
  `1.5.1`, rather than silently running whatever version happens to be on `$PATH` or
  `$env:CUTECHESS`. The exact version-output string and the pass/fail result are both written
  to the run's environment evidence.
- TC: `5+0.05` (5 s base, 0.05 s increment). This project's documented standard single-change
  convention, `docs/sprt-guidelines.md` section 1.
- Concurrency: 6 (amended from the original 2, `tools/sprt.ps1`'s and `nightly-sprt.yml`'s
  shared default, before any game was played; see the Gate 4 concurrency-amendment dev-entry
  for the full record of that change). Target host: AMD Ryzen 7 7700X, 8 physical cores / 16
  logical (SMT) threads. Each engine instance runs with `Threads=1` (section 3 above), so 6
  simultaneous games is an operational capacity choice for this specific host, not a claim that
  one game maps exactly to one physical core; engine processes, OS scheduling, SMT, and the
  idle side of every game all complicate that mapping in practice. 6 stays below the 8 physical
  cores, leaving roughly two physical cores of scheduling headroom for Windows, JVM/process
  overhead, cutechess-cli itself, and interactive desktop use during what may be a long run,
  deliberately not attempting to saturate all 16 logical/SMT threads.
  Changing concurrency does not change the formal `elo0`/`elo1`/`alpha`/`beta` hypotheses being
  tested; those are mathematical properties of the SPRT itself. But concurrency is not for that
  reason a free knob outside the experimental conditions: at a wall-clock time control like
  `5+0.05`, concurrency is part of the execution environment, and CPU contention among
  simultaneous game processes can change the effective compute available to each engine per
  move. That can affect observed game outcomes, or a measured relative Elo, particularly if the
  candidate and baseline have different search-efficiency characteristics (as this experiment
  itself expects, since that is what PVS is supposed to change) and therefore respond
  differently to a given amount of contention. For this reason concurrency is frozen the same
  as every other experimental-conditions term in this document: 6 is fixed before game 1, an
  operational compromise for this specific 8-core/16-thread host that leaves scheduling
  headroom rather than a value chosen for any effect on the outcome, not user-adjustable in
  `tools/p17-4-sprt.ps1` (moved from a command-line parameter into the same frozen-constant
  block as TC/elo0/elo1/Threads/Hash/game cap), and not changeable mid-run precisely because it
  could otherwise change the measured outcome distribution between games run at different
  concurrency levels within the same match. The choice of 6 itself is not reopened here.
  `tools/benchmark_concurrency.ps1` exists to find a throughput-optimal value empirically for a
  specific machine; this document does not use a benchmarked value because none has been run
  for this exact host, and inventing one without evidence would violate the "no post-hoc
  parameter selection" requirement just as much as inventing an Elo bound would. 6 was chosen
  as a capacity heuristic from the host's own known core count, not from a throughput
  benchmark; if a benchmarked value becomes available before the run, updating this document to
  match would be a legitimate protocol amendment (a measured fact about the host, not a tuned
  expectation about PVS).
- Opening corpus: `tools/noob_3moves.epd`, SHA-256
  `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`, 150,932 lines, one FEN per
  line at ply depth up to the position's own recorded ply (this is a set of already-diversified
  positions, not raw PGN moves). `tools/sprt.ps1` invokes it with
  `-openings file=... format=epd order=random plies=4` (cutechess selects a random line and
  plays out to ply 4 from the position given, then hands off to the engines) and `-repeat`
  (each selected opening is played twice, once with each engine as White, giving paired
  colors). **This file is gitignored, not tracked in this repository** (`.gitignore` line 72);
  it is a large (9.4 MB), pre-existing local file this session found already present on the
  machine that will run Gate 4, with no in-repo record of its exact origin or a script to
  regenerate it from scratch. `tools/p17-4-sprt.ps1` refuses to run if the file is missing or
  its SHA-256 does not match the value frozen above, so the match cannot silently substitute a
  different corpus or silently fall back to no opening file (all games from startpos) without
  the run failing loudly first. This is flagged as **Gate 4 measurement-infrastructure
  incomplete**, not resolved: the file being untracked is a real gap in this project's
  reproducibility story for every SPRT that has ever used it, not something Phase 17
  introduced. The smallest reproducible fix, proposed here rather than done as part of this
  preparation task, is to either commit a compressed copy of `tools/noob_3moves.epd` (or a
  script that regenerates an equivalent corpus from a documented public source) in its own
  follow-up issue, scoped to measurement infrastructure the same way #231 was, not to Phase
  17/PVS.
- Adjudication: `-resign movecount=5 score=400`, `-draw movenumber=40 movecount=8 score=10`
  (both frozen by `tools/sprt.ps1`'s own hardcoded defaults; not overridden here).
- Game cap: 20,000, `docs/sprt-guidelines.md` section 4's own worked example for this exact
  H0=0/H1=50 convention ("may terminate in as few as 400 games for a large effect or take the
  full 20,000-game cap for a marginal effect").
- Execution host: native Windows, non-WSL, PowerShell (verified the same way the throughput
  gate's own scripts verify it: refuse a `\\wsl*`/`wsl.localhost` current directory).
  **Explicitly not required to be the same physical Ryzen 7 7700X machine the throughput gate
  used.** The throughput gate's native-only requirement (CLAUDE.md section 6, "SPRTs run on
  native Windows only, never WSL") exists because wall-clock nodes-per-second is a hardware-
  sensitive measurement that a different CPU would invalidate outright. A paired-color SPRT
  measures game outcomes (win/loss/draw), not wall-clock throughput; both engines run on
  whatever single host plays a given game, so as long as both engines run on the *same* host
  for every game (which cutechess-cli already guarantees, since both processes are started
  locally by the same `-engine`/`-engine` invocation), a different native-Windows host does not
  introduce the asymmetry the throughput gate's hardware requirement exists to prevent. No
  project document requires the identical physical machine for strength testing specifically;
  this reasoning is this task's own, stated so it can be checked rather than left implicit.

## 5. SPRT

- elo0 = 0, elo1 = 50, alpha = 0.05, beta = 0.05.
- This is this project's documented standard convention for testing a single isolated change
  (`docs/sprt-guidelines.md` section 1, "Standard SPRT Usage (single change)"), reused by
  `tools/README.md`'s own SPRT section and by `.github/workflows/nightly-sprt.yml`. The Phase
  16 preregistration explicitly names it too: section 8's stop rule says to "proceed to exactly
  one isolated SPRT with frozen terms (elo0/elo1/alpha/beta fixed before the run starts,
  matching this project's existing SPRT convention)". "This project's existing SPRT
  convention" is exactly the 0/50/0.05/0.05 quadruple documented above, not something invented
  for Phase 17 or selected after seeing how promising PVS looked. Retained unchanged for that
  reason.
- "No Elo expectation is predeclared" (P16-3 section 7, stage 4) is consistent with reusing
  this project-wide default: the bounds were fixed as this project's general single-change
  convention before Phase 16 or Phase 17 existed, not chosen to target a specific expected PVS
  effect size. Predeclaring an *expectation* would mean picking elo1 based on how large a gain
  PVS is expected to produce; applying the same bounds this project already applies to every
  other single-change SPRT is the opposite of that.

## 6. Verdict semantics

Corrected in issue #231 and `.github/workflows/nightly-sprt.yml`; restated here so this
document is self-contained:

- **H1_ACCEPTED**: the evidence favors the elo1=50 hypothesis over the elo0=0 hypothesis under
  this SPRT's likelihood-ratio test. It does not establish the true Elo gain is exactly 50,
  only that the test favors that hypothesis over the 0 Elo one.
- **H0_ACCEPTED**: the evidence favors the elo0=0 hypothesis over the elo1=50 hypothesis under
  this SPRT. It does **not** by itself establish that the candidate has negative Elo, or that a
  regression occurred; it means a +50 Elo gain was not demonstrated. A genuinely neutral,
  0-Elo candidate is expected to accept H0 under these exact bounds just as reliably as an
  actual regression would, since 0 sits inside H0's own hypothesis point rather than outside
  it. If this project's policy is that a demonstrated +50 Elo gain is required to accept a
  candidate, that is a legitimate reason to not promote the candidate on an H0 verdict, but the
  reason must be stated as policy ("did not meet the required bar"), not reported as a
  confirmed regression.
- **INCONCLUSIVE**: neither the elo0 nor the elo1 boundary was reached before the 20,000-game
  cap. The test has not produced a decision either way.

## 7. Amendment rule

No parameter in sections 1-6 may change after the first game of this protocol's run without
invalidating that run and restarting under a newly-frozen document. This includes the
candidate/baseline commit identity, engine settings, TC, concurrency, opening corpus and its
SHA-256, adjudication settings, game cap, and SPRT bounds.

**Amendment log** (pre-run amendments only; no game has been played, so none of these
invalidated a run):

- 2026-09-20: concurrency changed from 2 to 6, before game 1. See section 4 for the full
  rationale (target-host physical-core capacity, not a throughput benchmark or a change to any
  SPRT hypothesis) and `dev-entries/phase-17.md` for the session record of this amendment.
- 2026-09-20 (same day, later pass): cutechess-cli frozen version changed from v1.4.0 to
  v1.5.1 (section 4), candidate identity verification strengthened from a single-file grep to
  a full production-source-tree diff against `f9b152c` (section 1), baseline `-BaselineRef`
  command-line override removed in favor of a frozen internal constant with a `git rev-parse`
  resolve-and-verify step before building (section 2), and the concurrency rationale in
  section 4 corrected to state that concurrency is part of the experimental conditions (able
  to affect observed outcomes via CPU contention at a wall-clock TC) even though it does not
  change the SPRT's mathematical hypotheses, rather than claiming it has no effect at all. All
  before game 1; none of these invalidated a run, since none had been played.
- 2026-09-20 (same day, final pre-run pass): candidate identity verification replaced again,
  this time architecturally rather than by strengthening the check in place. The prior pass's
  production-source-tree diff against `f9b152c`, and the still-earlier grep for the repaired
  `childIsPvNode` expression, could not see a change to `pom.xml`, the shade plugin
  configuration, compiler settings, or any other build input outside `src/main` that still
  produces a different JAR while both checks stayed green. Both engines are now built from
  their own exact, `git rev-parse`-verified frozen commit via a disposable detached worktree
  (sections 1 and 2), and the diff and grep checks are removed rather than kept alongside the
  exact-commit build, since a second, weaker identity mechanism only risks disagreeing with the
  strong one later. The orchestration checkout (`phase/17-pvs-experiment`) is no longer treated
  as either engine's source; it supplies the script, docs, and evidence location only. All
  before game 1; none of these invalidated a run, since none had been played.

## 8. Status

**Run complete, 2026-09-20.** `tools/p17-4-sprt.ps1` executed on the target native host
(RENEGADE, AMD Ryzen 7 7700X) under `-IReallyMeanIt`. Both engine JARs were built from their
exact frozen commits and verified against the SHA-256 values recorded in the run's own
evidence (`tools/results/p17-4/20260920-103500/`, `candidate_jar_sha256` and
`baseline_jar_sha256` in `05-environment.json`, matching the JAR files themselves
byte-for-byte). See section 9 below for the result and section 10 for the audit of a
post-run evidence-plumbing bug found while collecting this evidence (the bug is in
`tools/p17-4-sprt.ps1`'s own log-path handling after the match already finished; it does not
affect the match itself).

## 9. Result

- **Score: NEW (candidate) 0, OLD (baseline) 13, draws 1, from 14 scored games** (of 19
  started; the remaining 5 were in-flight games cancelled once the SPRT boundary was crossed,
  see section 10's audit, not additional losses or failures). NEW White: 0-6-1. NEW Black:
  0-7-0.
- **SPRT: LLR -3.10, lower bound -2.94, upper bound +2.94. H0 accepted.**
- **Per section 6's verdict semantics: this means the evidence favors elo0=0 over elo1=+50
  under this preregistered SPRT. It does not mean a -572 Elo regression is proven, that PVS is
  proven weaker in some absolute sense, or that the candidate's true Elo is zero.** The
  cutechess-reported point estimate (-572.5 +/- nan from 14 games) is not a usable effect-size
  estimate; a `+/- nan` confidence interval means the interval itself could not be computed
  from this few games, and 14 games is far too small a sample for any Elo point estimate to be
  reliable regardless. What the SPRT decision does establish, under the preregistered bounds,
  is that this run does not meet the project's frozen +50 Elo gate for promoting the candidate.
- **This does not revise Gates 1-3.** Mechanism (Gate 1, PASS, main nodes 73,089,246 ->
  40,878,283, -44.07%), throughput (Gate 2, PASS, median fixed-depth elapsed 218,267 ms ->
  120,860 ms, -44.63%), and correctness (Gate 3, PASS) all measured search efficiency and
  correctness properties that remain true regardless of this gate's outcome. An efficiency
  improvement (fewer nodes, less wall-clock time to a fixed depth) does not imply a
  playing-strength improvement; Gate 4 tests playing strength directly and specifically, and
  this run's evidence, once validated (section 10), is what settles that question for this
  candidate under this protocol.

## 10. Run-validity audit (post-run, evidence-only, no new game played)

Performed strictly from the existing run's own artifacts: `tools/results/p17-4/20260920-103500/`
(build/environment evidence, both JARs), and `tools/results/sprt_phase17-pvs_20260920_160513.log`
/ `.pgn` (the log and PGN `tools/sprt.ps1` itself wrote for this call, copied into the run's own
evidence directory as `06a-sprt-authoritative.log` / `06b-sprt-authoritative.pgn`, with SHA-256
confirmed identical to the originals before and after copying). No game was replayed to produce
any of the following.

- **Candidate/baseline identity confirmed.** `05-environment.json`'s `candidate_actual_commit`
  and `baseline_actual_commit` both equal their respective `*_frozen_commit` values
  (`f9b152ca4f45e8e8aa5a48092b03416aba79b230`, `ebe513eabd50e853a4e24a0260c64b41a5a4b224`), and
  `candidate_jar_sha256` / `baseline_jar_sha256` match the actual JAR files in that directory
  byte-for-byte (independently recomputed, not merely re-read from the JSON). Neither JAR could
  have been silently swapped or rebuilt from a different commit.
- **NEW/OLD configuration symmetry confirmed** from `tools/sprt.ps1`'s own argument
  construction (read from source, not assumed): both engines are launched with the identical
  `cmd=$Java`, `arg=--add-modules jdk.incubator.vector`, `proto=uci`, `option.Threads=1`, and
  identical `-NewOptions "Hash=16"` / `-OldOptions "Hash=16"`, so both receive `Hash=16` with no
  asymmetric UCI option on either side. `-each tc=5+0.05` applies the same time control to both
  engines (there is no per-engine TC override in `tools/sprt.ps1`). `-repeat` pairs each opening
  twice with colors swapped, confirmed directly in the PGN (games 1/2, 3/4, ... 17/18 each share
  an identical starting FEN with White/Black reversed); NEW played White in every odd-numbered
  game and Black in every even-numbered game, exactly the alternation `-repeat` produces, and
  the per-color score split reported by cutechess (White 0-6-1, Black 0-7-0) is internally
  consistent with that alternation over the 14 scored games. Candidate and baseline were not
  reversed: `-New $candidateJarPath` and `-Old $baselineJarPath` map directly to the JAR paths
  `Build-FrozenEngineJar` returned for the candidate and baseline commits respectively, and the
  PGN's `[White "NEW"]` / `[White "OLD"]` tags track the same `-engine name=NEW` / `name=OLD`
  labels cutechess was given for those same two JAR paths.
- **No engine or protocol failure signature found.** Grepped both the authoritative log and PGN
  for `illegal`, `disconnect`, `timeout`, `crash`, `stall`, `error`, `communication`, and
  `forfeit` (case-insensitive): zero matches in either file. All 13 decisive games and the one
  draw carry `[Termination "adjudication"]` or ended by 3-fold repetition; none carry a
  crash/timeout/illegal-move termination tag.
- **The five "No result" games (cutechess's games 15-19) are ordinary post-SPRT cancellation,
  not engine failures.** Their PGN `[Termination]` tag is `"unterminated"` (cutechess's tag for
  a game cut short while in progress), not any error-specific tag, and their ply counts are
  short and varied (31, 26, 16, 9, 5), consistent with mid-game snapshots at whatever point each
  one happened to be when the match stopped, not a uniform failure signature. Chronologically,
  the log's `SPRT: llr -3.1 ... H0 was accepted` line and the five `{No result}` lines for games
  15-19 both appear immediately after the 14th scored game (game 12) finishes, in the same log
  block, with no further `Started game` lines afterward and `Finished match` as the log's last
  line: this is cutechess's standard behavior under `-concurrency 6` with an active `-sprt`. 14
  games had already been scored when game 12 (the game whose completion pushed the running LLR
  past the lower bound) finished, and the 5 games still running at that moment under the
  concurrency-6 scheduling were cancelled rather than played to completion, since the SPRT
  decision no longer depended on them. This is not a validity failure.
- **No pathological or self-contradictory evaluation found in the completed games.** Where both
  engines' move comments could be compared near a game's end, NEW's and OLD's self-reported
  scores agree in sign and rough magnitude from each side's own perspective (for example, game
  1's final comments run `+7.85/+7.60/+8.09` for one side against `-7.59/-7.84/-8.34` for the
  other), i.e. both engines recognized the same side was winning by comparable amounts, rather
  than NEW reporting itself ahead while actually losing. This is the pattern expected from a
  real, mutually-recognized decisive advantage, not from a broken or inverted candidate
  evaluation.
- **Validity decision: VALID.** The frozen protocol executed with the correct candidate and
  baseline JARs, symmetric engine settings, correctly paired openings, no engine or protocol
  failure in any of the 14 scored games, and the five uncompleted games are explained in full by
  ordinary post-SPRT-decision cancellation under concurrency. The 0-13-1 score and the H0
  verdict are surprising, but surprise alone is not a basis for invalidation, and nothing found
  in this audit constitutes a concrete execution or configuration failure.

## 11. Post-run evidence-plumbing bug (does not affect match validity)

`tools/p17-4-sprt.ps1`'s own post-processing step failed after this match had already finished
and cutechess had already written its full SPRT decision: it tried to read
`tools/results/p17-4/20260920-103500/06-sprt-console.log` via `Get-Content`, which did not
exist, because `tools/sprt.ps1` accepts no output-path parameter and writes its own log/pgn
under `tools/results/` directly using a timestamp it generates internally when it starts, not
under this run's `$outDir`. The wrapper's own transcript file was meant to be populated by
piping `tools/sprt.ps1`'s console output through `Tee-Object`, but the pipe used `2>&1`, which
merges only the error stream into the success stream; `tools/sprt.ps1` reports nearly everything
via `Write-Host`, which writes to PowerShell's information stream and is not carried by `2>&1`.
This is a defect in the wrapper's own evidence-capture code, occurring entirely after cutechess
had already completed the match and printed its decision (`Finished match`, the per-player
termination summary, and the `H0 was accepted` SPRT line are all present, complete, and correct
in `tools/sprt.ps1`'s own authoritative log). It is fixed in `tools/p17-4-sprt.ps1` (switched to
`*>&1`, and the script now locates and copies `tools/sprt.ps1`'s own authoritative log/pgn into
the run's evidence directory instead of relying solely on a console transcript); see
`dev-entries/phase-17.md` for the fix's full record. This bug did not run any additional games,
alter any experimental parameter, or affect the result reported in section 9.
