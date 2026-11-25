# Match completion integrity (#319) Implementation Plan

Written under the engineering-plan policy.

## Approval and amendments (owner, 2026-11-25)

Approved with six amendments, all incorporated below and in the Amended rules section:

1. `COMPLETE_BOUNDARY` requires normal final match termination, consistent final statistics and complete opening pairs, not merely an SPRT acceptance message.
2. Validate log and PGN game identities and outcomes, not only aggregate counts. Reject duplicate identifiers and contradictory evidence.
3. Verify actual opening-pair completeness. An even game count alone is insufficient.
4. Recognize supported normal termination categories explicitly. Unknown or unparseable termination evidence must not silently pass.
5. A non-zero Cute Chess process exit prevents successful launcher completion, independently of artifact validation.
6. The fixed-count expected-manifest check verifies the relevant artifact hashes as well as the arguments, reusing the existing provenance mechanisms.

Focused regressions for each are added alongside the real-artifact fixtures. Parsing stays scoped to supported Cute Chess 1.5.1 output, with no general tournament framework. Tasks 1 to 3 are to be implemented now with lightweight fixture tests and GitHub CI, followed by one focused independent final review after integrated verification. Task 4 (preparing the #260 manifest) happens when the native resources can be read safely, modifies nothing under `history-260/` or any frozen artifact, and launches no match, which needs separate authorization. Resumable SPRT state stays out of scope.

## Amended rules (what the real artifacts showed)

A real completed SPRT run (the 2026-04-21 `phase14-a4-aspiration-25cp-s2` run: 185 finished games, H1 accepted, 1 game `No result`, PGN `unterminated`) shows that Cute Chess ends the games still in flight when a boundary is reached. So at a boundary `unterminated` games are normal, and an odd number of finished games is normal, because the in-flight game's twin is the orphan. Completeness is therefore judged by opening pair identity, not by counts:

- A pair is two games from the same opening (the PGN `FEN` tag, or the start position when there is none) with the two engines on opposite colours. For each opening, finished games with engine A as White must equal those with engine B as White. Any excess is an orphan. At a boundary an orphan is acceptable only when an `unterminated` game with the same opening and the opposite colours explains it. For a capped or fixed-count run any orphan, and any `unterminated` game, fails.
- Game identity: log game numbers must be unique, and each PGN game record must be unique as a whole (an exact duplicate record is rejected; the same opening and colour can legitimately recur in a random book, so that alone is not a duplicate). The multiset of (White, Black, Result, termination class) from the log's `Finished game` lines must equal the one from the PGN. Any difference is contradictory evidence.
- Final statistics: the last `Score of A vs B: w - l - d [x] n` line must equal the counts recomputed from the PGN (games with a result, which includes games Cute Chess scored by disconnection). At a boundary the final `SPRT:` line must carry the acceptance text and its `llr` must be consistent with it (at or beyond the matching bound within rounding).
- Final termination: the last non-empty log line must be `Finished match`, and a log with no such line is `INTERRUPTED`.
- Termination vocabulary (explicit, Cute Chess 1.5.1). Normal log texts: `White mates`, `Black mates`, `White wins by adjudication`, `Black wins by adjudication`, `White loses on time`, `Black loses on time`, `White makes an illegal move...`, `Black makes an illegal move...`, `Draw by 3-fold repetition`, `Draw by fifty moves rule`, `Draw by insufficient mating material`, `Draw by stalemate`, `Draw by adjudication`. Abnormal: `White disconnects`, `Black disconnects`, `White's connection stalls`, `Black's connection stalls` and `No result`. Normal PGN `Termination` values: absent, `normal`, `adjudication`, `time forfeit`, `rules infraction`. Abnormal: `abandoned`, `unterminated`. Each log text must also agree with its Result token (for example `White mates` with `1-0`). Anything not listed is `INVALID_EVIDENCE`, never a pass.
- A non-zero Cute Chess exit status ends the launcher with failure before any validation result is considered, and is recorded in the provenance whatever the validator says.
- The fixed-count `--expected-provenance` check compares the argument array hash, both engine JAR hashes, the opening book hash, the launcher hash, the Cute Chess executable hash and the seed with the prepared record, and refuses to launch on any difference.

**Goal:** A match launcher can no longer report success for an interrupted or damaged run, every run ends in an explicit termination state decided from its log and PGN, opening order is reproducible from a recorded seed, and the approved #260 fixed-count characterization has a launcher to run on.

**Architecture:** One pure Python module classifies a finished run from its Cute Chess log and PGN into a termination state. The two existing SPRT launchers call it after the run and refuse to print `SPRT complete` for anything but a valid completion. A new fixed-count launcher, built on the same argument construction, uses it too and passes an explicit `-srand`. Resumable SPRT state is out of scope (owner decision, 2026-11-25).

**Tech Stack:** Python 3 (stdlib only, `unittest`, matching `tools/tests/test_sprt_linux.py`), PowerShell 7 for `tools/sprt.ps1`, cutechess-cli 1.5.1.

**Spec:** issue #319; diagnosis `docs/engineering/investigations/2026-11-25-296-native-sprt-abnormal-termination.md`; the #260 design in `docs/engineering/investigations/2026-11-25-260-fixed-count-characterization-proposal.md`.

## Global Constraints

- The historical `sprt.ps1` behavior and the frozen `history-260/` preparation are not altered in any way that changes the Cute Chess arguments of an existing frozen run. Every new option is opt-in and absent from the arguments by default; the existing manifest parity tests stay green unchanged.
- cutechess-cli 1.5.1 has no tournament file or resume option. Nothing here relies on one.
- `-recover` stays in every invocation, because it is part of the frozen engine and game-flow settings. Completion is therefore decided from the log and PGN, never from the exit code alone.
- No absolute local path in any committed file, fixture or test.
- No match, training or tuning is run to develop or verify this. Fixtures are excerpts of existing artifacts.

## Review Focus

1. A run whose last game is `abandoned` or `unterminated` but whose exit code is 0 must not be reported complete.
2. A run that stopped at a boundary or reached its cap normally, with a few adjudicated and time-forfeit games, must still be reported complete (no false alarm on ordinary terminations).
3. A PGN truncated in the middle of a game, a missing PGN, and an empty log must produce an explicit invalid state, not an exception and not a pass.
4. A fixed-count run with fewer or more finished games than requested must not pass.
5. An odd number of finished games under `-repeat` leaves an orphan opening and must be flagged, since pair-level analysis needs complete pairs.

## Termination states

Decided by `classify_run(log_text, pgn_text, expected_games, sprt)`:

| State | Meaning | Reported as success |
|---|---|---|
| `COMPLETE_CAP` | Exactly `expected_games` finished games, all normal, no unterminated game, no orphan opening, consistent identities and final statistics, log ends with `Finished match` | yes |
| `COMPLETE_BOUNDARY` | SPRT only. Final `SPRT:` line carries an acceptance consistent with its llr, normal final termination, consistent identities and final statistics, every orphan opening explained by an in-flight `unterminated` twin, no abandoned or disconnected game | yes |
| `INTERRUPTED` | Any abandoned or disconnected game, an unterminated game without a boundary, an unexplained orphan, no `Finished match`, or fewer games than expected with no boundary | no |
| `OVERRUN` | More finished games than `expected_games` (fixed-count only) | no |
| `INVALID_EVIDENCE` | PGN or log missing, empty or unparseable, duplicate identifiers, an unknown termination, a contradiction between the log and the PGN or inside the log, or final statistics that do not match the PGN | no |

The only PGN `Termination` values in the #296 run are `adjudication` (7,184 games, all of its normal games), `abandoned` (6) and `unterminated` (4). A normal termination is any value other than `abandoned`, `unterminated` and a log result text containing `disconnects`, with `stalled connection` also treated as not normal. The accepted and rejected sets are pinned by a test against the real #296 PGN, and extended when other completed runs are added as fixtures.

## File Structure

- Create `tools/match_validation.py`: `classify_run`, `parse_pgn_terminations`, `parse_log_games`, a small CLI (`--log`, `--pgn`, `--expected-games`, `--sprt`) that prints the state and exits 0 only for a success state.
- Create `tools/tests/test_match_validation.py`, with fixtures under `tools/tests/fixtures/match_validation/`.
- Create `tools/fixed_count_match.py`: builds the arguments (`-games N`, no `-sprt`, explicit `-srand`), writes the argument manifest and provenance (seed, hashes, toolchain, as `sprt_linux.py` does), supports `--prepare-only` and `--expected-manifest`, runs Cute Chess, then validates.
- Create `tools/tests/test_fixed_count_match.py`.
- Modify `tools/sprt.ps1`: optional `-Seed` (becomes `-srand`, recorded in the manifest and console only when given), and a post-run call to the validator. Modify `tools/sprt_linux.py` the same way. Extend `tools/tests/test_sprt_linux.py` and its differential layer.
- Docs: a short README section in `tools/README.md` and a Phase 22 entry.

## Task 1: Validator and its fixtures

**Interfaces:** Produces `classify_run(log_text: str, pgn_text: str, expected_games: int | None, sprt: bool) -> RunVerdict`, with `RunVerdict(state: str, games: int, abandoned: int, disconnected: int, unterminated: int, orphan_openings: int, reasons: list[str])`, and `main(argv) -> int`.

- [ ] Fixtures: `interrupted-296-tail` (the last 40 games of the #296 PGN and the matching log tail, trimmed to the Cute Chess `Finished game` lines, the `Finished match` line and the summary block, no paths), `completed-boundary` and `completed-cap` (excerpts of existing completed runs under `tools/results/`, checked for paths before committing), `truncated-pgn`, `empty-log`.
- [ ] Tests, one per Review Focus line and per state: the #296 tail gives `INTERRUPTED` with 6 abandoned and 4 unterminated reported and the reasons naming them; `completed-boundary` gives `COMPLETE_BOUNDARY`; `completed-cap` with `expected_games` equal to its count gives `COMPLETE_CAP`; the same file with `expected_games` one higher gives `INTERRUPTED`; with one lower gives `OVERRUN`; an odd game count gives a nonzero `orphan_openings` and a failing state; `truncated-pgn`, a missing path and `empty-log` give `INVALID_EVIDENCE`; a PGN whose game count differs from the log's `Finished game` count gives `INVALID_EVIDENCE`.
- [ ] Run `python -m unittest tools.tests.test_match_validation -v`; expected: all pass.

## Task 2: Launchers call the validator

**Interfaces:** Consumes `classify_run`. Produces: `sprt.ps1` and `sprt_linux.py` print `SPRT complete` only for `COMPLETE_BOUNDARY` or `COMPLETE_CAP`, otherwise print the state and reasons, keep all evidence and exit non-zero (`throw` in PowerShell).

- [ ] Write failing tests first: with a stub Cute Chess that exits 0 and writes the #296 tail, `sprt_linux.run` returns non-zero, prints the state and does not print `SPRT complete`; with a stub that writes a boundary run it returns 0 and prints it.
- [ ] Add `-Seed` to both launchers as an optional parameter mapped to `-srand`, absent from the arguments when not given, recorded in the manifest and console when given. Tests: the golden argument list for the frozen #266 shape is unchanged, and with a seed the only difference is `-srand <seed>`.
- [ ] Extend the differential test (skipped without pwsh) to compare `sprt.ps1 -PrepareOnly -Seed 7` with the Python builder, and the PowerShell post-run classification with the Python one on the same fixtures.
- [ ] Run `python -m unittest tools.tests.test_sprt_linux tools.tests.test_match_validation -v`; expected: all pass, with the differential layer run in CI where pwsh exists.

## Task 3: Fixed-count launcher

**Interfaces:** Produces `fixed_count_match.py` with `build_arguments(java, new_jar, old_jar, tc, games, concurrency, seed, openings_file, order, new_options, old_options, pgn_out) -> list[str]` and `main(argv) -> int`; `--prepare-only --manifest PATH`, `--expected-manifest PATH`, `--seed INT` required, `--games INT` required.

- [ ] Tests first: the golden argument list for the #260 shape equals the list in the proposal (no `-sprt`, `-games 4000`, `-srand 260258`, otherwise identical to the `sprt.ps1` arguments for the same inputs, checked by parsing `sprt.ps1` the way `test_sprt_linux.py` does); a missing `--seed` or `--games` is refused; the manifest records the seed; `--expected-manifest` rejects any argument difference; a stub Cute Chess run is validated with `expected_games` equal to `--games`.
- [ ] Implement by reusing `sprt_linux.py` helpers (`ps_number`, `file_sha256`, `build_provenance`, `redact_option`) rather than copying them. If importing them is awkward, move them to a shared `tools/match_common.py` in the same change and keep `sprt_linux.py` behavior identical (its tests are the proof).
- [ ] Run `python -m unittest tools.tests.test_fixed_count_match tools.tests.test_sprt_linux -v`; expected: all pass.

## Task 4: Preparing the #260 manifest (not launching)

- [ ] On the native host, with the builds, book and runner in the existing `history-260` payload unchanged, run the new launcher with `--prepare-only` into a new run folder. Compare the manifest with the argument list in the proposal and record the jar, book, launcher and toolchain hashes next to the table in the proposal. This runs no engine and no match. It waits until the #307 and #313 measurements finish only because it needs the host's native checkout, not because it loads the CPU.
- [ ] Record the result in the proposal note and the Phase 22 entry. Launch needs separate explicit authorization.

## Acceptance Criteria

- The #296 final log and PGN excerpt are classified `INTERRUPTED` by the validator, by `sprt_linux.py` and by `sprt.ps1`, and the launchers exit non-zero and keep the evidence.
- A normal boundary run and a normal capped run are classified as completions and the launchers still print `SPRT complete` for them.
- With no `-Seed`, the Cute Chess arguments of both launchers are byte for byte what they are today (existing tests unchanged and passing). With a seed, `-srand` is the only difference and the seed is recorded.
- The fixed-count launcher builds exactly the argument list written in the #260 note, refuses to run without a seed and a game count, and fails any run whose log and PGN do not validate as `COMPLETE_CAP`.
- No file under `history-260/`, no frozen manifest and no existing run artifact is modified.

## Verification Commands

- `python -m unittest discover -s tools/tests -p 'test_*.py' -v` (the new and existing launcher tests).
- The CI jobs that already run the tools tests, including the pwsh differential layer.
- No match, benchmark or local heavy run.

## Delivery Boundaries

One branch (`infra/319-sprt-completion-integrity`), one PR, assigned to the owner, not merged without approval. Tasks 1 to 3 are code and test files only and need no host time. Task 4 needs the native host after the #307 and #313 measurements. Resumable SPRT state and any change to the SPRT stopping rule are out of scope.

## Execution record

- **Approved revision and approval reference:** this revision (the plan as first submitted in PR #321 plus the six amendments and the Amended rules section above), approved by the owner in the session on 2026-11-25. Approval covers Tasks 1 to 3 and the preparation in Task 4. No launch is authorized.
- **Slice progress and verification evidence:**
  - Task 1 done (commit 24d66d8). `tools/match_validation.py`, fixtures from the real completed H1 run and the real #296 tail, 39 tests. Mutation checks: each rule (orphan explanation, log versus PGN comparison, duplicate id, final statistics, llr consistency, unknown termination, duplicate record, result token agreement, final match line, orphans in a capped run) fails a test when disabled. One deviation found by running the validator on the real full #296 files: the log has five `No result` games and the PGN four (a game that never made a move has no PGN record), so a log-only `No result` game is tolerated and counted, any other log-versus-PGN difference stays invalid. The full-run check is an opt-in test (`MATCH_VALIDATION_296_DIR`) and gives `INTERRUPTED`, 6 abandoned, 5 unterminated, 5,382 complete pairs. The `.log` fixtures are stored as `.log.txt` because `*.log` is gitignored.
  - Task 2 done (commit 4e1fce6). Both launchers check the exit code first, then classify the run, and print `SPRT complete` only for a valid completion. `sprt.ps1` requires python and the validator before launching. Opt-in `-Seed` and `--seed` append `-srand` last. Existing tests that relied on exit 0 meaning success were changed to use valid recorded runs. Regressions: interrupted exit-0 run, non-zero exit with valid artifacts and with a validator that would pass it, missing PGN, seed, static pin of the PowerShell order. Mutation checks: removing the completion check and removing the exit-code check each fail tests. The pwsh differential layer (including a new seed case) runs in CI.
  - Task 3 done. `tools/fixed_count_match.py` builds its arguments from `sprt_linux.build_arguments` minus the `-sprt` group, requires `--games` (even) and `--seed`, writes the manifest and a provenance record (hashes of both JARs, the book, the launcher and the Cute Chess executable, count, seed), and `--expected-provenance-path` refuses a launch on any difference in the arguments or those hashes. Success is `COMPLETE_CAP` only. 25 tests. Mutation checks on the group removal, the schema check, a hash comparison and the completion check.
  - Local verification: `python -m unittest discover -s tools/tests -p 'test_*.py'`, 196 tests, all pass except `test_release_workflow`, which cannot import `yaml` in this environment (CI installs it). No match or benchmark was run, and the running #307 and #313 measurements were not touched.
  - Process note: a `git stash; git stash pop` run in this worktree applied an unrelated old stash from the shared stash list and conflicted. It was restored with the owner's permission (hard reset to the last commit, both original stashes intact, my work was committed). No file of the change was affected.
- **Outstanding work and next action:** CI on the pushed branch, one focused independent final review, then Task 4 (prepare the #260 manifest) when the native resources can be read safely.
- **Integrated verification and final review status:** pending.
