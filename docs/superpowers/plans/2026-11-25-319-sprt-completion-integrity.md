# Match completion integrity (#319) Implementation Plan

Written under the engineering-plan policy. This is the plan for owner approval. Nothing in it is implemented yet.

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
| `COMPLETE_CAP` | Exactly `expected_games` games, all with a normal termination, no abandoned, disconnected or unterminated game, no orphan opening, log ends with `Finished match` | yes |
| `COMPLETE_BOUNDARY` | SPRT only. Log contains `SPRT: H0 was accepted` or `SPRT: H1 was accepted`, and all games are normal | yes |
| `INTERRUPTED` | Any game abandoned, disconnected or unterminated, or fewer games than expected with no boundary | no |
| `OVERRUN` | More finished games than `expected_games` (fixed-count only) | no |
| `INVALID_EVIDENCE` | PGN or log missing, empty or unparseable, or the games in the PGN disagree with the games in the log | no |

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

- **Approved revision and approval reference:** none yet. This revision is submitted for approval.
- **Slice progress and verification evidence:** none, nothing implemented.
- **Outstanding work and next action:** owner approval of this plan, then Task 1.
- **Integrated verification and final review status:** pending.
