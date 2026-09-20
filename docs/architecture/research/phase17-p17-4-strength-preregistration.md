# P17-4: Phase 17 strength-gate (SPRT) protocol, frozen before any game

Governs issue #229 step 4 and issue #230 step 4. Freezes every parameter the isolated SPRT
called for in `docs/architecture/research/phase16-p16-3-intervention-preregistration.md`
section 7, stage 4 ("one isolated, same-baseline SPRT against the current Threads=1 build. No
Elo expectation is predeclared") and section 8's stop rule ("proceed to exactly one isolated
SPRT with frozen terms"). No game has been played under this protocol. This document is the
freeze; running `tools/p17-4-sprt.ps1` against it is a separate, later action.

## 1. Candidate

- Source: `phase/17-pvs-experiment`, commit `e6afbb1` (docs-only on top of the isPvNode-
  propagation repair commit `f9b152c`; the search source at `e6afbb1` is byte-identical to
  `f9b152c`, verified with `git diff f9b152c..e6afbb1 -- engine-core/.../search/`, empty).
- This is the same candidate that passed Gate 1 (mechanism, main nodes 73,089,246 ->
  40,878,283), Gate 2 (native throughput, median elapsed 218,267 ms -> 120,860 ms), and Gate 3
  (correctness: full relevant Maven suite green, PV-node-propagation defect repaired, PV
  legality green). See `dev-entries/phase-17.md` for the full evidence trail.
- `tools/p17-4-sprt.ps1` verifies this at run time by checking that `Searcher.java` contains
  the repaired `boolean childIsPvNode = isPvNode;` expression, not merely by trusting a commit
  hash (a docs-only commit could otherwise sit on top of the repair without preserving it, or a
  local checkout could be on the wrong ref).

## 2. Baseline

- Source: commit `ebe513e` (the `develop` merge commit Phase 17 itself branched from).
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
- Built via a disposable `git worktree`, never checked out over the candidate's own working
  tree.

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

- cutechess-cli: v1.4.0 (matching `.github/workflows/nightly-sprt.yml`'s pinned version; no
  cutechess version is currently pinned in `tools/sprt.ps1` itself, so the locally-installed
  version must be recorded in the run's own environment evidence).
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
  deliberately not attempting to saturate all 16 logical/SMT threads. This value affects only
  how fast the match executes; it has no effect on the SPRT hypotheses being tested (elo0/elo1
  are properties of the games played, not of how many run at once). It is frozen the same as
  every other term in this document: fixed before game 1, not user-adjustable in
  `tools/p17-4-sprt.ps1` (moved from a command-line parameter into the same frozen-constant
  block as TC/elo0/elo1/Threads/Hash/game cap), and not changeable mid-run.
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

## 8. Status

**Preparation only. No game has been played under this protocol.** `tools/p17-4-sprt.ps1`
implements sections 1-6 above and refuses to run without an explicit `-IReallyMeanIt` switch;
it has not been executed. See `dev-entries/phase-17.md` for the preparation session's full
audit of existing SPRT infrastructure (`.github/workflows/nightly-sprt.yml`, `tools/sprt.ps1`)
and why neither was used as-is for this gate.
