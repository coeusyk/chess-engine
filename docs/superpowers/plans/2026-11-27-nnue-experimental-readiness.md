# NNUE experimental readiness Implementation Plan

> Executed under the owner's engineering-plan policy (the `engineering-plan` skill). The execution record at the end of this file is the tracker. Steps use checkbox syntax.

**Goal:** Make the existing NNUE checkpoints and the repaired Stage 1 data trustworthy enough to start strength development, in three independently deliverable slices: a strict mode that stops an explicitly requested NNUE experiment from silently playing Classical, a complete non-destructive Stage 1 repair with evidence, and a deduplicated, hash-identified, parity-checked checkpoint inventory ready for the staged screening and the corrected-label comparison. It is not another general engine audit, and no production default changes.

**Architecture:** Slice 1 adds one opt-in UCI option and one small pre-flight tool, and wires the existing NNUE launchers to both. Slice 2 reuses `repair_stage1_perspective.py` as it is and adds only a verification script and the evidence. Slice 3 composes the existing exporter, quantizer, parity and scale-utilization code behind one export wrapper and one inventory script, and drives the real engine through the slice 1 pre-flight for the Java side. No exporter, quantizer, loader or evaluator change.

**Tech Stack:** Java 21 and JUnit 5 (engine-uci), Python and pytest (trainer and tools), the existing Cute Chess launchers.

**Spec:** `docs/engineering/investigations/2026-11-27-nnue-strength-assessment.md` (merged in #327, roadmap steps 1 to 4), issues #263 and #265, `docs/NNUE_PRD.md` (US-2 and the engine-uci integration point), `docs/architecture/research/nnue/2026-11-24-real-checkpoint-quantization-audit.md`.

**Status:** Approved by the owner on 2026-11-27 with the amendments below (revision 2). Implementation proceeds slice by slice. Plan branch `docs/nnue-readiness-plan` (worktree from develop at `3496281`).

## Owner approval and amendments (2026-11-27)

The owner approved this plan, with D1 to D7 accepted, the filtered dataset left unchanged and the sidecar approach retained, and the amendments below, which are folded into the slices. In-scope corrections do not need another planning approval. Real-dataset repair, full checkpoint rescoring and other substantial local workloads wait until the owner confirms the host is free. No training, matches, production-default changes, higher-QA experiments or unrelated optimization. #296, #315 and #316 stay unchanged. Work uses descriptive topic branches, not phase-number branches (this supersedes the `phase/N` convention in `CLAUDE.md` for this work), and all of it stays within Phase 22.

- **Slice 1:** the engine's strict-mode guarantee is separated from launcher configuration verification, because `StrictNnue` cannot detect a misspelled option such as a misspelled `EvalType` that leaves Classical selected. The launcher validates the effective options of each engine (duplicates, conflicts, unadvertised names) and the pre-flight uses the real Java executable, JVM arguments, JAR, network and execution options. Screening needs a pinned network identity, rechecked before launch, and an absent manifest never establishes verified provenance. Classical and non-strict behavior are preserved. Strict verification is recorded as a prerequisite for future NNUE SPRTs, nothing more is built.
- **Slice 2:** the label semantics of the historical shards are established from the acquisition code, the source convention and the data before the sign repair is authorized, since commit age and a missing marker are not enough. Source-record identity and split membership are verified, and repair invariance is separated from reproduction of the historical P5 held-out set. The contradiction in the corrected-label control (production `combine_and_split` rejects legacy unmarked data) is resolved by specifying an isolated historical-label control that neither weakens production validation nor marks labels falsely. It is specified, not implemented, and no training is launched.
- **Slice 3:** content identities cover every inference-relevant parameter. Exact parity, identity and loader safety are hard gates. Orientation is validated on controlled positions with sign-inversion negative controls, and corpus material-sign agreement, side gaps and mirror differences are diagnostics, not gates (correlation and sign agreement are different metrics). The shortlist is finalized before any rescoring, which removes the Task 6 and Task 10 cycle, keeps experiment pairs intact and fixes the cap tie-breaks.

## What the investigation established (2026-11-27, develop `3496281`)

Method: read the UCI option and network-resolution code, the existing fallback tests, the NNUE launchers, the repair, acquire, combine-and-split, export, quantization-parity and audit code, and the 2026-11-24 audit note. Then, read-only, ran the existing `audit` command on the two local Stage 1 datasets (20,000 and 100,000 rows, 1.1 MB and 5.4 MB) and inspected the local Stage 2 directories. No training, labeling, repair, match or benchmark was run, and nothing was written to the datasets.

**Slice 1, fail-open today (confirmed in code).**

| Condition | What `UciApplication` does today |
|---|---|
| `EvalType=NNUE`, `EvalFile` empty (`resolveNnueNetworkForSearch`) | prints `info string NNUE evaluator requested but EvalFile is not set; falling back to Classical.` and searches with Classical |
| `EvalFile` missing, unreadable or invalid | prints `info string Failed to load NNUE network from '<file>' (<reason>); falling back to Classical.`, clears the cache, searches with Classical |
| `EvalType` value other than Classical or NNUE | prints `info string Unknown EvalType value ... Keeping <previous>.` and keeps the previous type |
| Option name misspelled | ignored silently (UCI rule), so `EvalFile` stays empty and the previous row applies |
| Any of the above under Cute Chess | the line goes to engine output nobody keeps, `readyok` is sent, games are played with Classical, the match file looks normal |

The network is loaded lazily on the first `go`, `eval` or `nnue` command, not at `isready`. That is why a bad network is invisible until play has started. Tests that pin the fallback today: `UciApplicationIntegrationTest` (the `EvalFile is not set` line, the `Failed to load NNUE network` line, and the classical breakdown after fallback). The PRD (US-2) intends "never crash", so the default must keep falling back. The E-5 launch command is not in the artifacts, so whether E-5's NNUE side actually used the net is unprovable, which is exactly the evidence gap this slice closes for future runs.

**Slice 2, what exists and what the real data looks like.**

- `trainer/scripts/repair_stage1_perspective.py` already has `audit` and `repair`, with the safety properties listed in its docstring (input read-only, new directory only, refuses without `--confirm-white-perspective`, refuses a manifest already marked `label_perspective: mover-relative`, refuses non-`public` stages, flips only Black-to-move rows, preserves row order and extra columns, writes `repaired_from` with input manifest and shard SHA-256 and a `repair-report.json` with before and after audits). It is tested by `tests/scripts/test_repair_stage1_perspective.py` and `test_acquire_stage1_lichess.py`. Nothing in this plan changes it.
- Local datasets (gitignored, `trainer/outputs/datasets/`):

| Directory | Manifest identifier | Rows | Marker | Black to move | Notes |
|---|---|---:|---|---:|---|
| `stage1-lichess` | `stage1-lichess-evals-2026-07-15` | 20,000 | none (legacy) | 9,476 | `output_sha256` `a9d338b2...` equals the file; trainer commit `15e021a1` |
| `stage1-lichess-100k` | `stage1-lichess-evals-2026-07-20-100k` | 100,000 | none (legacy) | 47,313 | `5be437e0...`; trainer commit `6cbb4915` |
| `stage1-lichess-filtered` | `...-2026-07-15-filtered-3a` | not measured | none | not measured | derived by a label filter, manifest has no `stage`, no hashes and no `created_at`, so `repair` would refuse it |

The audit output on the two real datasets (read-only, this session): 20k has White to move 10,524 rows (cp positive 5,235, negative 1,945, zero 1,036; mate +2,032 / -276) and Black to move 9,476 rows (cp positive 4,415, negative 2,281, zero 1,148; mate +1,295 / -337). 100k has 52,687 White rows and 47,313 Black rows (Black cp positive 21,240, negative 12,829; mate +6,148 / -1,579). Both marker fields are empty, as expected for pre-#263 data.
- Stage 2 directories: `stage2-quiet-sf` and `stage2-quiet-sf-wdl` hold legacy headerless shards (the file starts with a FEN string). `stage2-quiet-sf-v1` and `stage2-quiet-sf-wdl-v1` hold shards with the `VEXSHRD` header (the format added 2026-09-14), and `stage2-quiet-sf-wdl-v1` has no `manifest.json`. The P5 provenance records the legacy WDL shard hash `4955f2e5...` and the ordered held-out hash `0a183465b4fa890be1a78e329f30b88d688aa93f76a215171be81fcf88a87134` (4,000 positions, split seed 42, 36,000 training), produced with the old code at `3579a3c`. Whether the `-v1` shards hold the same records in the same order as the legacy ones is not established, and it decides whether the historical held-out set can be reproduced on current code.
- `combine_and_split(stage1_dir, stage2_dir, seed)` refuses an unmarked Stage 1 directory (`require_mover_relative_stage1`), unions the two sources, shuffles with the seed and takes the first 10 percent as held-out. Split membership therefore depends only on the order and length of the union, and a repair that keeps row order and count keeps every position in the same split. Only the labels change.
- #281 left three items open on #263: the per-source audit of the real shards, repairing them into new directories, and rescoring frozen checkpoints against corrected held-out labels. Nothing was run for them.

**Slice 3, what exists.**

- Reusable and tested: `checkpoint_to_canonical`, `quantize`, `assert_java_loadable`, `eval_quantized_fens` and `eval_float_canonical` (`quantization/inference.py`), `compute_quantization_parity` and `ParityThresholds` (`validation/quantization_parity.py`), `compute_scale_utilization`, `read_nnue` (`export/nnue_reader.py`), the public `export(network, experiment_metadata, dataset_composition, output_dir, ...)`, and `scripts/audit_checkpoint_quantization.py` (checkpoint SHA-256, `weights_only` loading, `--output` JSON, optional gate bounds). The Java loader rejects bad magic, versions, architecture, feature set, widths, `qa`, `qb`, size mismatch and accumulator overflow risk.
- `export()` assigns a fresh `uuid4` and the current time on every call, so exporting the same checkpoint twice gives two different `.nnue` files with different SHA-256. File hashes cannot detect a duplicate network. The 2026-11-24 audit shows the consequence: `P1-G04` (file hash `479e61e7...`) and `M-1 cp-only seed 42` (`80c029e8...`) have identical figures on both corpora, and the project already treats them as bit-identical. The same shape applies to any pair of checkpoints with identical tensors.
- The Java side can be driven through the real engine without any engine change. With `EvalType=NNUE`, `EvalFile=<net>` and `position fen <fen>`, the UCI `eval` command prints a line `  int16 score           +N cp` (and the float32 oracle score) from `NnueEvaluator.explainEval`. That is the exact integer the search sees, mover-relative.
- There is no committed score-orientation check. The 2026-11-27 assessment measured it by hand (float evaluation against mover material 0.94 to 0.98 for both colours on the 393-position corpus for five nets), which is the reference for the thresholds below.
- The 2026-11-24 audit measured 25 files (6 on the P5 held-out set, 19 on the 393-position corpus), listed by checkpoint hash prefix. That table is the starting inventory.

**Not established (and what this plan will and will not claim):** play strength of any net; whether the `-v1` Stage 2 shards equal the legacy ones; the effect of the 24 percent inverted rows on play; whether corrected labels help. This plan makes those questions answerable and claims none of them.

## Global Constraints

- Production defaults unchanged: `EvalType` stays Classical, the new option defaults to false, and the PRD US-2 fallback stays the behavior whenever strict mode is off. The demonstration launchers outside the repository are not edited.
- No change to `quantize()`, the exporter's byte format, `NnueNetwork`, `NnueEvaluator`, the Java loader or the NNUE architecture. No new network. No unrelated optimization.
- Original shards, checkpoints and datasets are never modified, renamed or deleted. New outputs go to new directories under gitignored `trainer/outputs/` and a new sibling dataset identifier.
- The existing repair tool is not duplicated. The verification script reads its output and calls the shared `trainer.dataset.lichess_perspective` helpers only to check, never to relabel.
- No training, no strength matches, no labeling, no intensive benchmark. The data and checkpoint steps below are CPU-light (a few MB of CSV, 4,000-position held-out evaluations, 393-position corpora), but every real-data step waits until the owner says the host is free, and runs niced.
- #296, #315 and #316 stay unchanged. #268 is not touched (the early-ponderhit and `go nodes` items stay recorded there).
- No absolute paths in any committed file. Records use basenames and paths relative to the repository root.
- Native Windows is the default for anything involving the Cute Chess launchers. The only launcher runs here are 1-second UCI handshakes, not matches.
- Commits use the repository format, with `Refs #263` or `Refs #265` and never `Closes` on those issues. Phase 22 dev entries. One PR and one branch per slice (the #328 precedent), named `fix/nnue-strict-mode`, `fix/stage1-repair-workflow` and `feat/nnue-screening-readiness`; this plan is on `docs/nnue-readiness-plan`.

## Review Focus

1. A strict-mode failure must end the process with a nonzero exit before `readyok`, and must never emit `bestmove` from Classical, whichever of the five fail-open rows above caused it (Slice 1, Tasks 1 and 2).
2. `EvalType=Classical` with strict mode on, and strict mode off with every bad-network case, must behave exactly as today (Slice 1, Task 1 regression tests).
3. A repair must refuse to run twice on the same data, and must not change any White-to-move row, any extra column, the row count or the row order (Slice 2, Task 5).
4. The held-out set must not move when labels move: same ordered positions, only the label of Black-to-move Stage 1 rows differs (Slice 2, Task 5 and Task 6).
5. Two checkpoints with identical tensors but different file hashes must be reported as one network, and an export of an already-exported network must be refused (Slice 3, Tasks 7 and 8).
6. A net whose scores have the wrong sign for one colour must fail the orientation check even when its float-versus-quantized parity is perfect (Slice 3, Task 9).

## Dependency order

```
Slice 1  Task 1 -> Task 2 -> Task 3          (engine option, pre-flight tool, launcher wiring)
Slice 2  Task 4 -> Task 5 -> Task 6          (provenance, verify script, real-data run and rescoring)
Slice 3  Task 7 -> Task 8 -> Task 9 -> Task 10   (inventory, export wrapper, orientation, screening inventory)
```

Slice 1 and Slice 2 Tasks 4 and 5 are independent and can run in either order or in parallel (different files, different branches). Slice 3 Tasks 7 to 9 are independent of both. Slice 3 Task 8's engine loading check and Task 10 need Slice 1 merged, because they reuse `nnue_preflight.py` and `StrictNnue`. There is no cycle: Slice 3 Task 7 fixes the shortlist from the inventory and a committed group file, and Slice 2 Task 6 step 5 (rescoring) consumes that shortlist, so the order is Task 7, then Task 6 step 5. The corrected-label comparison design (Task 10) uses the Slice 2 identities and the control specification in Task 6 step 4.

Recommended order: Slice 1 first (the only one that changes the engine, and screening matches must not run without it), then Slice 2 Tasks 4 and 5 and Slice 3 Tasks 7 to 9, then the real-data steps (Slice 2 Task 6, Slice 3 Task 10) once the owner says the host is free.

## Decisions I will take unless you object

- **D1. Strict mode is an opt-in UCI option, `StrictNnue` (check, default false), not a change to what `EvalType=NNUE` means.** Changing the default would break PRD US-2 and every existing script that relies on the fallback. The option is order-independent: it is evaluated at `isready` and again at `go`, `eval` and `nnue`, after all `setoption` lines have been applied, so Cute Chess can send it in any order.
- **D2. A strict failure prints one `info string NNUE strict mode: <reason>` line, flushes, and exits with status 2 without sending `readyok`.** The alternatives were to stay silent and never answer `isready` (Cute Chess waits until a timeout, then reports the engine as unresponsive or a time forfeit, the failure mode you ruled out) or to answer `readyok` and refuse each `go` (games forfeit one by one). Exiting makes Cute Chess report the engine as failing to start, before any game.
- **D3. What strict mode treats as failure:** `EvalType` requested NNUE and `EvalFile` empty, file missing or unreadable, file rejected by the loader, and any `EvalType` value that is neither Classical nor NNUE (a mistyped `nnue ` or `NNUE2` would otherwise leave the previous evaluator). `EvalType=Classical` is always valid and is unaffected by strict mode.
- **D4. Pinning which network is checked outside the engine.** The engine already prints `info string NNUE network loaded: <uuid>`. The pre-flight tool compares that uuid and the file SHA-256 with the export manifest. I am not adding an expected-uuid option to the engine.
- **D5. `stage1-lichess-filtered` (3a) is not repaired (accepted; left unchanged).** It is a derived artifact that only `P3A-001` used, its manifest cannot pass `repair`, and re-deriving it adds tooling for a dataset the corrected-label comparison does not need. If you want 3a covered, say so and it becomes one extra task that re-applies the recorded filter to the repaired 20k dataset.
- **D6. Duplicate and provenance identity for screening lives in a sidecar** `<uuid>.screening.json` next to each exported net, not in the exporter or its manifest schema, so the production exporter and `manifest_schema.py` stay untouched.
- **D7. The shortlist rule is written down before any number is looked at (Task 10):** the E-3 anchor, then one network per distinct tensor hash, taking for each experiment arm its selected step where a selection rule exists and its final checkpoint otherwise, capped at the 10 nets the #327 protocol budgets. Held-out correlation is a report column, not a selector.

---

## Slice 1: Fail-closed NNUE experiment integrity

Delivers two separate guarantees. **The engine guarantee:** with `StrictNnue=true`, an engine that was told `EvalType=NNUE` never searches with Classical, and ends the process instead. **The launcher guarantee:** a screening run is only started after the effective configuration of each engine (the real Java executable, JVM arguments, JAR, network and every option) has been verified, against a pinned network identity. They are separate because the engine cannot know what it was not told: `StrictNnue` cannot detect a misspelled `EvalType` option name (UCI says unknown options are ignored, so Classical stays selected and strict mode has nothing to complain about), a duplicated option or a wrong JAR. Only a check of the effective options against what the engine advertises can catch those, and that check lives in the pre-flight and the launcher. Branch `fix/nnue-strict-mode`. A new issue (assigned to coeusyk, linking #327 roadmap step 2) tracks it.

Scope limit: this slice does not touch the SPRT scripts, which hard-code `EvalType=Classical`. Strict verification is recorded as a prerequisite for any future NNUE SPRT in the investigation note, and nothing more is built for that here.

### Task 1: `StrictNnue` option and strict resolution in `UciApplication`

**Files:**
- Modify: `engine-uci/src/main/java/coeusyk/game/chess/uci/UciApplication.java` (option listing near the `EvalType` line, `handleSetOption`, `isready`, `handleGo`, `handleEval`, `handleNnueDebug`, `runBench` entry, `resolveNnueNetworkForSearch`)
- Test: `engine-uci/src/test/java/coeusyk/game/chess/uci/StrictNnueIntegrationTest.java` (new; child-process tests using the existing `UciHarness`)

**Interfaces:**
- Consumes: `resolveNnueNetworkForSearch()` (unchanged for the non-strict path), `NnueNetwork.load(Path)`.
- Produces: UCI option `option name StrictNnue type check default false`; package-private `String strictNnueFailure()` returning null when the requested evaluator can be used or strict is off, otherwise the reason; process exit status 2 on a strict failure.

The engine guarantee, stated as tests: it covers `EvalType` set to NNUE with `EvalFile` empty, missing, unreadable or rejected by the loader, and an `EvalType` value the engine recognizes as neither Classical nor NNUE. It does not cover an option the engine never receives under the right name, and the tests say so (see `misspelledOptionNameIsOutOfScopeForTheEngine`).

- [ ] **Step 1: Write the failing tests** in `StrictNnueIntegrationTest`, each starting a fresh engine process:
  - `uciListsStrictNnueOptionDefaultFalse`: the `uci` output contains `option name StrictNnue type check default false`.
  - `strictWithEmptyEvalFileExitsBeforeReadyok`, `strictWithMissingFileExitsBeforeReadyok`, `strictWithCorruptFileExitsBeforeReadyok` (a temp file of garbage bytes), `strictWithUnknownEvalTypeValueExitsBeforeReadyok` (`setoption name EvalType value NNUE2`): `isready` gives no `readyok`, exactly one line starting `info string NNUE strict mode:` naming the reason, and the process exits with status 2 within 5 seconds.
  - `strictWithValidNetworkAnswersReadyokAndSearchesWithNnue`: a valid tiny network (`writeTinyNnueFile` from `UciApplicationIntegrationTest`) gives `readyok`, the `NNUE network loaded: <uuid>` line, and `go depth 2` produces a `bestmove`.
  - `strictBackstopAtGoWhenIsreadyWasSkippedOrOptionsChangedLater`: valid options, `isready`, then `setoption EvalFile` repointed at a missing path, then `go depth 1`: no `bestmove`, exit status 2.
  - `strictWithClassicalIsUnaffected`: `EvalType=Classical`, strict true, a bad `EvalFile` set: `readyok` and a normal `bestmove`.
  - `nonStrictFallbackBehaviorIsUnchanged`: the existing fallback assertions (the `EvalFile is not set` line, the `Failed to load NNUE network` line, `readyok`, a Classical `bestmove`) hold with strict unset and with `StrictNnue=false`. The three existing tests in `UciApplicationIntegrationTest` are not edited.
  - `strictOrderIndependent`: `StrictNnue true` sent before and after `EvalType` and `EvalFile` gives the same result.
  - `misspelledOptionNameIsOutOfScopeForTheEngine`: `setoption name Evaltype2 value NNUE` with strict on and nothing else yields `readyok` and Classical play. This pins the limit, so nobody later believes strict mode covers it.
- [ ] **Step 2: Run them to see them fail.** `mvn -pl engine-core,engine-uci -am test -Dtest=StrictNnueIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`.
- [ ] **Step 3: Implement.** Add the `strictNnue` field and the `StrictNnue` branch in `handleSetOption`, remembering a rejected raw `EvalType` value so it is a strict failure. Implement `strictNnueFailure()` per D3, reusing the existing resolver so a successful strict load fills the same cache. At `isready` and at the start of `handleGo`, `handleEval`, `handleNnueDebug` and the bench entry, call it, and on a non-null result print `info string NNUE strict mode: <reason>`, flush, `System.exit(2)`. The non-strict path stays byte for byte as it is.
- [ ] **Step 4: Run the new tests and the whole suite.** `mvn -pl engine-core,engine-uci,engine-tuner -am test`.
- [ ] **Step 5: Red-green check.** With `System.exit(2)` replaced by a plain return the exit-status and no-`readyok` tests fail; restore (touch the file so Maven recompiles).
- [ ] **Step 6: Commit.**

### Task 2: `tools/nnue_preflight.py`, verification of the effective engine configuration

**Files:**
- Create: `tools/nnue_preflight.py`
- Test: `tools/tests/test_nnue_preflight.py` (fake engine scripts, no JVM needed for most cases)

**Interfaces:**
- Consumes: Task 1's option, the exact engine command, and optional export sidecar and manifest.
- Produces: `effective_engine_config(cc_args, engine_name) -> EngineConfig` (parses one `-engine` block of a Cute Chess argument array: `cmd`, every `arg`, `proto`, and every `option.Name=Value` in order); `preflight(config, pins, *, timeout) -> PreflightRecord`; `parse_eval_score(output) -> int`; CLI `python tools/nnue_preflight.py --jar JAR --eval-file NET --expected-uuid U --expected-nnue-sha256 H [--java CMD --jvm-arg A ...] [--option Name=Value ...] [--output OUT.json]`. Exit 0 only when every check passes.

What the pre-flight does, in order:
1. **Launch exactly what the match will launch:** the same Java executable (`cmd=`), the same JVM arguments (every `arg=` before `-jar`), the same JAR, and the same option list in the same order. It does not substitute `java` from the PATH or add `--add-modules` itself when the match configuration lacks it. For the CLI form it takes `--java`, repeated `--jvm-arg` and `--option`.
2. **Validate the effective option list:** reject (a) duplicate option names within one engine, case-insensitively, whether the values agree or conflict (a conflicting `Threads` given both by the launcher default and by a user option is the common case); (b) any option name the engine did not advertise in its `uci` output, which is what catches a misspelled `EvalType` or `EvalFile`; (c) an `EvalType` value other than exactly `Classical` or `NNUE`; (d) `EvalType=NNUE` without `EvalFile`, `EvalFile` without `EvalType=NNUE` (a net that would never be used), and `EvalType=NNUE` without `StrictNnue=true`; (e) a missing `StrictNnue` option in the advertised list (a pre-strict JAR), because Cute Chess would only warn.
3. **Pinned identity, rechecked:** the file SHA-256 is computed and compared with `--expected-nnue-sha256`, and the engine's `info string NNUE network loaded: <uuid>` with `--expected-uuid`. A pin is mandatory in screening mode. A sibling `<uuid>.json` manifest or `<uuid>.screening.json` sidecar, when present, must agree with the pins and the file. When no manifest or sidecar exists the record says `provenance: unverified-manifest-absent`, and the run is accepted only because the pins matched the bytes, never described as having verified provenance. The check runs again immediately before the launch command, and the record carries both hashes, so a file replaced in between is caught.
4. **Handshake:** `uci`, the options, `isready`, require `readyok` and the loaded line within a bounded wait, then `quit`. Exit codes: 0 pass, 3 configuration rejected (reason lists every violation), 4 engine failed before `readyok` (its strict line is copied), 5 timeout, 6 pin or hash mismatch.

- [ ] **Step 1: Write the failing tests:** each rejection above (duplicate and conflicting `Threads`, a misspelled `Evaltype`, an unadvertised option, `EvalType=nnue2`, NNUE without `EvalFile`, `EvalFile` with Classical, NNUE without `StrictNnue=true`, pre-strict JAR without the option); the launched command line is exactly the configured one (a fake engine records its own `argv` and the test compares it, including a JAR path and a JVM argument that differ from the defaults); a wrong pin gives exit 6 and no engine is left running; a file changed between the first and the pre-launch check gives exit 6; a manifest that disagrees with the file gives exit 6; no manifest gives `unverified-manifest-absent` and is still accepted when the pins match; the engine exiting before `readyok` gives exit 4 with its strict line; a hung engine gives exit 5 and is killed; records hold basenames only; `parse_eval_score` extracts `+37` and `-5` from `  int16 score           +37 cp` and raises when the line is absent.
- [ ] **Step 2: Run to see them fail.** `cd tools && python -m pytest tests/test_nnue_preflight.py -q`.
- [ ] **Step 3: Implement** with stdlib `subprocess` and a reader thread.
- [ ] **Step 4: Run the tests, then a real handshake** (about one second, no match) with the existing `dfffd3da` network against a built JAR from Task 1, on native Windows through the same Java the launcher will use. Also run it against a nonexistent file (expect exit 4 in under 3 seconds) and against a JAR built before Task 1 (expect exit 3).
- [ ] **Step 5: Commit.**

### Task 3: Launchers refuse an unverified NNUE side

**Files:**
- Modify: `tools/fixed_count_match.py` (option validation, pre-flight call, provenance record), `tools/nnue-gauntlet.ps1` (adds `option.StrictNnue=true` and calls the pre-flight), `tools/match_validation.py` only if its checks do not already reject a log in which an engine failed to start (decided in Step 1)
- Test: `tools/tests/test_fixed_count_match_nnue.py` (new)

**Interfaces:**
- Consumes: Task 2's `effective_engine_config` and `preflight`.
- Produces: `fixed_count_match.py` refuses before starting Cute Chess when any side with `EvalType=NNUE` (a) lacks the pins (`--new-nnue-sha256`, `--new-nnue-uuid`, and the `--old-` equivalents for an NNUE baseline), (b) fails the pre-flight on the exact arguments in `cc_args` (the real `cmd`, JVM arguments, JAR and options, including the launcher's own `option.Threads` merged with user options), or (c) has a Classical side given any NNUE option, which would also be a configuration error. The pre-flight record, including both hash checks, goes into the provenance JSON. `--prepare-only` runs the same checks and writes the manifest without launching a match. Classical-only runs build the same argument array and provenance as before.

- [ ] **Step 1: Write the failing tests:** the refusals above, each before any Cute Chess process; a duplicated `Threads` (the launcher's `option.Threads` plus `--new-option Threads=8`) is refused for any side; Classical-only runs are byte-for-byte unchanged against the existing argument tests in `test_fixed_count_match.py`; the provenance holds the pre-flight record for an NNUE side; the hash is re-verified after the argument manifest is written and before launch; and a Cute Chess log in which an engine "disconnected" or "failed to start" is classified as not complete by `match_validation.py` (the case is added only if the existing validator does not already reject it, and the commit says which).
- [ ] **Step 2: Run to see them fail,** **Step 3: implement** (a guard in `run()` between argument construction and launch, plus the PowerShell edit), **Step 4:** `cd tools && python -m pytest tests -q`, then native Windows `--prepare-only` with a good network (expect exit 0, record written) and with a wrong pin (expect nonzero and no Cute Chess process). No match is started.
- [ ] **Step 5: Commit.**

**Slice 1 acceptance criteria.**
- Engine: with `StrictNnue=true` each of the four engine-detectable fail-open rows ends the process with status 2, no `readyok` and no `bestmove`, within 5 seconds, with one explanatory line. With strict off, behavior is unchanged: the three existing fallback tests pass unedited and the default `uci` output differs only by the one new option line. `EvalType=Classical` is unaffected. The engine's limit (a misspelled option name) is pinned by a test, not claimed away.
- Launcher: a screening run is refused, before Cute Chess starts, for a missing pin, a hash or uuid mismatch, a duplicate or conflicting option, an unadvertised or misspelled option, an invalid `EvalType` value, NNUE without `EvalFile` and `StrictNnue`, a JAR without the strict option, and an engine that fails the handshake. The pre-flight launches the same Java executable, JVM arguments, JAR and options as the match. An absent manifest never counts as verified provenance.
- The full `engine-core`, `engine-uci` and `engine-tuner` suites and the `tools` tests pass, and a native Windows `--prepare-only` check is recorded. No match was run.

---

## Slice 2: Stage 1 dataset repair workflow

Delivers: the sign repair authorized by evidence about what the historical labels actually are, the real Stage 1 datasets repaired into new versioned directories, an invariance proof that is separate from the question of reproducing the historical P5 held-out set, a specification (not an implementation, not a run) of an isolated historical-label control, and rescoring of the frozen checkpoints on corrected held-out labels. Branch `fix/stage1-repair-workflow`. `Refs #263`. No repair logic is added: `repair_stage1_perspective.py` is used as it is. The sign repair is not run on the real shards until the evidence in Task 4 is in and the host is free.

### Task 4: Historical label semantics, then the authorization to repair

**Files:**
- Create: `trainer/scripts/probe_stage1_label_semantics.py`, `trainer/tests/scripts/test_probe_stage1_label_semantics.py`, `docs/engineering/investigations/2026-11-27-stage1-repair-provenance.md`

Commit age and a missing marker are not evidence of what the labels are. The authorization rests on three independent kinds of evidence, all of which must agree:

1. **Acquisition code at the recorded commit.** Read `_to_record` at each dataset's recorded `trainer_commit` (`15e021a1`, `6cbb4915`) and confirm it copies `cp` and `mate` verbatim with no side-to-move handling. Done during planning for both commits: both copy `pv.get("cp")` and `pv.get("mate")` unchanged. The note records the commits, dates (2026-07-14 and 2026-07-20) and the function text hash.
2. **The source's own convention:** the Lichess evaluation dump stores White-perspective values (the claim in #263, with the protocol reference it cites). The note records that this is taken from the issue's cited source and states whether it was independently re-fetched.
3. **The data itself.** `probe_stage1_label_semantics.py` computes, per side to move, the Pearson correlation of `eval_cp` with (a) White minus Black material and (b) the mover's material balance, plus the cp sign split. White-perspective labels correlate positively with (a) in both groups. Mover-relative labels correlate positively with (b) in both groups, and negatively with (a) for Black to move. Measured read-only during planning on the real files: the correlation with White-minus-Black material is +0.260 (White to move, 8,216 rows) and +0.201 (Black to move, 7,844 rows) for the 20k dataset, and +0.236 and +0.199 for the 100k dataset, so the labels are White-perspective in both groups. The script is committed with tests on a synthetic file of each semantic, and the real figures are recorded in the note.

- [ ] **Step 1: Write the failing tests:** a synthetic White-perspective file gives positive (a) correlation for both sides and is reported `white-perspective`; a synthetic mover-relative file is reported `mover-relative`; a mixed or noise file is reported `inconclusive` and exits nonzero; the script never writes to the input directory.
- [ ] **Step 2: Run to see them fail,** **Step 3: implement** (stdlib only: FEN material count and Pearson), **Step 4:** `cd trainer && python -m pytest tests/scripts/test_probe_stage1_label_semantics.py -q`.
- [ ] **Step 5: Record the evidence note:** commits, code hashes, the three evidence kinds, the `output_sha256` against the file for each dataset, and the explicit statement that `--confirm-white-perspective` is authorized only if all three agree. If they do not, stop and report instead of repairing.
- [ ] **Step 6: Determine the Stage 2 identities** (which `stage2-quiet-sf*` the P1-G04, P3A-001 and P5 runs used, from the P5 `provenance.json` hashes) and whether `stage2-quiet-sf-v1` equals `stage2-quiet-sf` record for record, by parsing both and comparing the ordered record sequences, read-only. A mismatch is reported as such.
- [ ] **Step 7: Commit.**

### Task 5: `scripts/verify_stage1_repair.py`, source-record identity and invariants

**Files:**
- Create: `trainer/scripts/verify_stage1_repair.py`
- Test: `trainer/tests/scripts/test_verify_stage1_repair.py` (uses `trainer/tests/fixtures/stage1_sample.csv` and `tmp_path`)

**Interfaces:**
- Consumes: the original and the repaired directories, `trainer.dataset.lichess_perspective.side_to_move` and `to_mover_relative` (for the expectation only), `repair_stage1_perspective.audit`.
- Produces: `verify(original: Path, repaired: Path) -> Dict[str, Any]` with `rows_equal`, `source_record_identity` (the ordered sequence of FENs is identical, and a SHA-256 over the ordered FEN column of both is recorded), `row_order_identical`, `white_rows_byte_identical`, `black_rows_exactly_negated`, `extra_columns_identical`, `original_sha256_unchanged`, `repaired_from_matches_original`, `marker_is_mover_relative`, `accepted_by_require_mover_relative`, counts, and `main()` exiting 1 on any false field. Split membership is a separate function: `split_membership_invariance(original_stage1, repaired_stage1, stage2_dir, seed) -> Dict` that runs the shuffle-and-split machinery on the position sequences only (never on labels) and reports that membership by position index and by FEN is identical, plus `held_out_order_sha256`. It uses the production split code through its record-level inner function and does not call `require_mover_relative_stage1` for the original (which is legacy), and it does not weaken that guard anywhere.

Two claims are kept apart. **Repair invariance** (this task's gate) says that repairing did not move any position between training and held-out sets, and it is a property of original versus repaired. **Reproduction of the historical P5 held-out set** (Task 6) says that the current code reproduces the ordered hash recorded at the time. The first must always hold, the second may legitimately fail for reasons unrelated to the repair (a different Stage 2 shard format, a different record order), and a failure of the second is reported, never a reason to change the repair.

- [ ] **Step 1: Write the failing tests:** a faithfully repaired fixture passes every check; each of one White row altered, one Black row not flipped, one row dropped, two rows swapped, an extra column changed, a FEN changed, the marker removed and the `repaired_from` block missing fails the named check; verifying a directory against itself fails; the originals are re-hashed against `repaired_from`; with a small synthetic Stage 2 directory the membership report is identical for original and repaired, and different when a row is dropped.
- [ ] **Step 2: Run to see them fail,** **Step 3: implement** (read-only streaming; no flipping code of its own), **Step 4:** `cd trainer && python -m pytest tests/scripts/test_verify_stage1_repair.py tests/scripts/test_repair_stage1_perspective.py tests/scripts/test_acquire_stage1_lichess.py -q` (existing tests pass unedited).
- [ ] **Step 5: Commit.**

### Task 6: Repair, verify, reproduce, specify the control, rescore (real data, host free)

**Files:**
- Create: `docs/engineering/investigations/2026-11-27-stage1-repair-and-rescoring.md` and a Phase 22 entry. Datasets and JSON reports stay under gitignored `trainer/outputs/`.
- No new repair code.

- [ ] **Step 1 (only after Task 4 authorizes it and the owner says the host is free):** `repair_stage1_perspective repair` for the 20k dataset to a new directory beside the original with identifier `stage1-lichess-evals-2026-07-15-mover-relative-v2`, and for the 100k dataset with `...-2026-07-20-100k-mover-relative-v2`. Expected: 9,476 and 47,313 rows flipped, matching the audits.
- [ ] **Step 2: `verify_stage1_repair.py` on both and `split_membership_invariance`,** re-hashing the originals before and after (`a9d338b2...` and `5be437e0...` unchanged), confirming a second repair is refused and that production `combine_and_split` accepts the repaired directories.
- [ ] **Step 3: Reproduction attempt of the historical P5 held-out set** (reported separately from step 2): with the repaired 20k Stage 1 and the Stage 2 directory identified in Task 4, seed 42, compare the ordered held-out hash with `0a183465b4fa890be1a78e329f30b88d688aa93f76a215171be81fcf88a87134`. If equal, state it. If not, report both hashes and the cause found in Task 4 step 6, and use the old commit `3579a3c` in a throwaway worktree only to confirm the original-label figure.
- [ ] **Step 4: Specify the isolated historical-label control (specification only, nothing implemented, nothing run).** The problem: production `combine_and_split` correctly refuses legacy unmarked Stage 1 data, so a corrected-versus-original comparison cannot load its original-label arm. The mechanism must not weaken the production guard and must not mark white-perspective labels as mover-relative. Specified design, to be implemented only after the owner approves the comparison:
  - A separate module `trainer/trainer/dataset/legacy_label_control.py` with `combine_and_split_legacy_control(stage1_dir, stage2_dir, seed, *, acknowledge_white_perspective_labels: bool, expected_stage1_manifest_sha256: str)`. It refuses without the acknowledgement, refuses a directory whose manifest carries `label_perspective: mover-relative` (that is the repaired one, which must use the production function), requires the manifest hash to equal the pinned value, and requires the Task 4 semantics probe verdict to be `white-perspective` recorded for that exact manifest hash.
  - The production `combine_and_split` and `require_mover_relative_stage1` are not edited. The shuffle and split code is shared by extracting it into a record-level function that takes already-loaded tagged records, called by both; a test proves the production function still refuses an unmarked directory and still produces the identical split as before the extraction.
  - The control's output is labelled: every `DatasetComposition` and the training record's provenance carry `label_perspective: white-perspective-legacy-control` and the exported manifest cannot be mistaken for a production net (the screening sidecar records it, and the Stage 0 and screening tools refuse to treat it as a candidate for promotion).
  - Acceptance test list for that future work: production refuses unmarked data; control refuses marked data; control refuses without acknowledgement and with a wrong pin; the control and production splits are identical on the repaired and original directories for the same seed (membership invariance from Task 5); no path marks a legacy directory as mover-relative.
- [ ] **Step 5: Rescore** the shortlisted checkpoints (the Task 7 output, at most the shortlist and anchor, never the 599 snapshots) on the repaired held-out set with `evaluate_held_out`, reporting the held-out correlation against the original labels and against the corrected labels, split by side to move. This step depends on Slice 3 Task 7 having produced the shortlist. It is inference on 4,000 positions per net, no training. A net whose Black-to-move correlation changes after the correction is reported as that and no interpretation beyond the numbers is added.
- [ ] **Step 6: Write the note and the Phase 22 entry** with every figure taken from the commands above, claiming nothing about strength. `Refs #263`. Whether #263 can close is the owner's decision.

**Slice 2 acceptance criteria.**
- The repair is authorized only by the three-part semantics evidence (acquisition code, source convention, data statistic), recorded before any repair runs.
- Both datasets are repaired into new versioned directories, the originals are byte-identical before and after (SHA-256 recorded), and a second repair is refused.
- Row count, row order, the ordered FEN sequence, every White-to-move row and every extra column are identical to the original, and every Black-to-move `cp` and `mate` is exactly negated. The manifest carries `label_perspective: mover-relative`, the new identifier and a matching `repaired_from` block. `verify_stage1_repair.py` is tested against nine deliberately broken copies.
- Repair invariance holds: the training and held-out membership, by index and by FEN, is the same for the original and the repaired Stage 1 for a given seed. Reproduction of the historical P5 held-out hash is reported separately, as achieved or as not achieved with the cause.
- The isolated historical-label control is specified without any change to production validation and without marking legacy labels as mover-relative, and it is not implemented or run.
- The three items open on #263 after #281 are each done or reported blocked: audit, repair into new directories, rescoring on corrected held-out labels.
- `stage1-lichess-filtered` is left alone (D5). No training, no relabeling.

---

## Slice 3: Checkpoint export and screening readiness

Delivers: a checkpoint inventory that identifies networks by every inference-relevant parameter, the deterministic shortlist (fixed before anything is rescored), an export wrapper that reuses the public exporter and refuses duplicates, hard gates on exact identity, loader safety and Java-versus-Python parity, an orientation check validated on controlled positions with sign-inversion negative controls, corpus diagnostics that are reported and not used as gates, and the screening-ready inventory. Branch `feat/nnue-screening-readiness`. `Refs #265`. Tasks 7 to 9 do not need Slice 1. Task 8's engine loading check and Task 10 need Slice 1 merged.

### Task 7: Content identity, the inventory, and the shortlist

**Files:**
- Create: `trainer/trainer/export/identity.py`, `trainer/scripts/checkpoint_inventory.py`, `trainer/trainer/export/shortlist.py`
- Test: `trainer/tests/export/test_identity.py`, `trainer/tests/export/test_shortlist.py`, `trainer/tests/scripts/test_checkpoint_inventory.py`

**Interfaces:**
- Consumes: `checkpoint_to_canonical`, `quantize`, the audit script's `file_sha256` and `load_checkpoint` (reused).
- Produces: `tensor_sha256(canonical: CanonicalNetwork) -> str` and `network_content_sha256(q: QuantizedCanonicalNetwork) -> str`. Each hashes, in a fixed order with a name, dtype and shape prefix for every array: every array (`ft_weights`, `ft_biases`, `output_weights`), `output_bias`, and every inference-relevant scalar: `hidden_width`, `qa`, `qb`, `output_scale`, `architecture_id`, `feature_set_id`, the quantization version (`QUANT_VERSION`) and the identity schema version. It excludes uuid, trainer commit, timestamps and every checkpoint metadata field. Two networks that differ only in `qa` or `output_scale` or `feature_set_id` therefore hash differently even with equal arrays. `checkpoint_inventory.py CKPT... --output OUT.json` writes one record per checkpoint (basename, `checkpoint_sha256`, `tensor_sha256`, `network_content_sha256`, trainer commit and seed when recorded, `duplicate_of`). `shortlist.select(records, groups) -> ShortlistResult` is a pure function of the inventory and a committed `trainer/trainer/export/shortlist_groups.json` (the experiment groups and their order).

The shortlist is finalized before any rescoring or Stage 0 number is looked at, which removes the cycle between rescoring and the inventory: rescoring (Slice 2 Task 6) takes its list from this task's output, and nothing in this task reads a held-out or strength figure.

Shortlist rule (written in the module docstring and the note before any run):
- Unit of selection is the **experiment group**: the anchor, a baseline, or a paired comparison. Pairs are kept intact (control and treatment, or both arms of a seed pair) or dropped together, so a comparison is never split across the cap.
- One network per distinct `network_content_sha256`; a duplicate is represented by the earliest group in the fixed order.
- Within a group, the selected-step checkpoint where the experiment recorded a selection rule (P5), otherwise the final checkpoint. A group with several seeds contributes its lowest seed.
- Cap 10 networks (the #327 stage 1 budget). Groups are taken in the fixed order in `shortlist_groups.json`: anchor (E-3 `nets/checkpoint.pt`), baseline P1-G04, P3A-001, P4IV, then the paired groups in chronological order (P5 selected pair, E-15 pair, E-16 seed 42 pair). A pair that would exceed the cap is skipped whole and the next group is considered, so the cap tie-break is the fixed order and never held-out correlation, quantization error or any measured quality.
- The result lists every excluded group and the reason (`duplicate-of`, `cap`, `missing`). Stage 0 hard gates still run on every unique network, the shortlist only limits what enters the match stages.

- [ ] **Step 1: Write the failing tests:** identity equal for the same network saved twice with different metadata and key order; one changed weight, `qa`, `qb`, `output_scale`, `feature_set_id` or `hidden_width` changes it; two checkpoints with equal tensors and different `experiment_metadata` are duplicates while their file hashes differ (the `P1-G04` and `M-1 seed 42` regression); a record holds no absolute path and loading uses `weights_only=True`; the shortlist is deterministic (same inventory in a shuffled input order gives the same result), keeps both arms of a pair or neither, takes the selected step for P5, and a synthetic cap test shows the tie-break follows the fixed order and ignores any quality field even when one is injected.
- [ ] **Step 2: Run to see them fail,** **Step 3: implement,** **Step 4:** `cd trainer && python -m pytest tests/export/test_identity.py tests/export/test_shortlist.py tests/scripts/test_checkpoint_inventory.py -q`.
- [ ] **Step 5 (host free, hashing about 25 audited checkpoints):** real inventory over the checkpoints named in the 2026-11-24 audit note, not all 599 snapshots; compare with that note's hash prefixes and confirm the `P1-G04` and `M-1 seed 42` pair is one network. Read-only.
- [ ] **Step 6: Commit.**

### Task 8: `scripts/export_screening_net.py`

**Files:**
- Create: `trainer/scripts/export_screening_net.py`
- Test: `trainer/tests/scripts/test_export_screening_net.py`

**Interfaces:**
- Consumes: Task 7's identities, the public `export(...)`, `quantize`, `assert_java_loadable`, `read_nnue`, `compute_quantization_parity`, `compute_scale_utilization`, and (optionally) Slice 1's `preflight`.
- Produces: `export_screening_net.py CKPT --out-dir DIR [--engine-jar JAR] [--allow-duplicate REASON]` writing `<uuid>.nnue`, `<uuid>.json` (from `export`, unchanged) and `<uuid>.screening.json` (the sidecar, D6) holding `source_checkpoint_sha256`, `tensor_sha256`, `network_content_sha256`, `nnue_sha256`, the parity and scale-utilization summaries and `hard_gates`. Exit 0 all hard gates passed, 2 duplicate refused, 3 a hard gate failed (files kept for inspection, the sidecar says `failed`).

Hard gates (each pass or fail, none statistical): (1) identity: the read-back network from `read_nnue` equals the quantized network array for array and scalar for scalar, the header uuid equals the manifest uuid, and the file SHA-256 equals the manifest's `nnue_sha256`; (2) loader safety: `assert_java_loadable` passes, and with `--engine-jar` the real strict engine loads the file through the pre-flight with the pinned uuid and hash; (3) exact parity: Java versus Python difference zero (Task 9). A gate that did not run is `not run` and the net is never called verified for it. Float-versus-quantized parity and scale utilization are recorded as measurements, not gates.

- [ ] **Step 1: Write the failing tests:** a first export writes all three files; a second export of a network with the same `network_content_sha256` into the same directory is refused (exit 2) and writes nothing, and `--allow-duplicate REASON` overrides it with the reason recorded; a network that fails `assert_java_loadable` is not exported; a flipped byte in the written file fails the identity gate; with a fake engine the engine gate is `run`, and a failing fake gives exit 3; without `--engine-jar` the sidecar says `not run` and the final message states no Java loadability is claimed; the sidecar holds no absolute path.
- [ ] **Step 2: Run to see them fail,** **Step 3: implement** as a thin composition with no quantization or byte-format code of its own, **Step 4:** `cd trainer && python -m pytest tests/scripts/test_export_screening_net.py tests/export -q`.
- [ ] **Step 5: Commit.**

### Task 9: Orientation validation and Java-versus-Python parity

**Files:**
- Create: `trainer/trainer/validation/orientation.py`, `trainer/scripts/screen_stage0.py`, `trainer/tests/fixtures/orientation_positions.epd` (the controlled positions)
- Test: `trainer/tests/validation/test_orientation.py`, `trainer/tests/scripts/test_screen_stage0.py`

**Interfaces:**
- Consumes: `eval_quantized_fens`, `read_nnue`, `tools/nnue_preflight.parse_eval_score`.
- Produces: `controlled_orientation(scores, positions) -> OrientationResult` (a hard gate), `corpus_diagnostics(scores, fens) -> Dict[str, float]` (reported, never gating), and `screen_stage0.py NET.nnue [--engine-jar JAR] --output OUT.json`.

Orientation is validated, not statistically scored:
- **Controlled positions:** a small committed set (`orientation_positions.epd`) of quiet positions with a decisive, unambiguous material difference (for example queen or two rooks against a bare king and a few pawns), each given in four forms: the advantaged colour is White or Black, and it is to move or not. The expected mover-relative sign is known by construction (positive when the side to move is the advantaged colour, negative otherwise). The hard gate requires the correct sign on every controlled position, because a net that gets a queen-up position wrong in sign for one colour is broken, not unlucky. Calibration (Step 5) records which controlled positions the five known nets get right; a real net failing one is a finding about that net, reported with the position, and the gate is not loosened to pass it.
- **Sign-inversion negative controls (tests):** a copy of a synthetic network with the output layer negated fails every controlled position; a score list with the sign flipped for Black to move only fails exactly the Black-to-move controlled positions, even though the corresponding float-versus-quantized parity and the corpus correlation of the unflipped half are perfect. This proves that parity cannot stand in for orientation.
- **Diagnostics, not gates:** on the 393-position corpus, `corpus_diagnostics` reports per side to move the sign agreement between the score and the mover's material balance (positions with imbalance of at least 3 pawns, count stated), the gap between the sides, the Pearson correlation separately (a different metric from sign agreement, never merged with it), and the mirror difference (score of a position against the score of its colour-flipped mirror, as mean and maximum). These are recorded in the sidecar and the note with no pass or fail threshold, because material is a poor proxy for the score in tactical positions and the measured 0.94 to 0.98 figures are not a calibration of a rejection limit.
- **Parity gate:** `screen_stage0.py` scores the corpus in Python and, with `--engine-jar`, through the real strict engine using `position fen` and the UCI `eval` command, and requires an integer difference of exactly zero on every position (bit-exactness was established for committed fixtures in #265).

- [ ] **Step 1: Write the failing tests** as listed above, plus: a correct synthetic net passes the controlled gate for all four forms; parity with a fake engine returning the Python scores is zero and one changed score gives a reported nonzero difference and exit 1; the diagnostics function returns the stated fields and `screen_stage0` never fails on them.
- [ ] **Step 2: Run to see them fail,** **Step 3: implement,** **Step 4:** `cd trainer && python -m pytest tests/validation/test_orientation.py tests/scripts/test_screen_stage0.py -q`.
- [ ] **Step 5 (host free):** calibrate on E-3 and the four networks measured in the 2026-11-27 assessment and on a deliberately sign-flipped copy of one. Expected: the controlled gate passes on the real nets (or any failure is reported as a finding) and fails on the flipped copy; the diagnostics are recorded.
- [ ] **Step 6: Commit.**

### Task 10: The screening-ready inventory and the corrected-label comparison design

**Files:**
- Create: `docs/engineering/investigations/2026-11-27-screening-readiness-inventory.md` (the table, the rule, the commands, the comparison design). Machine-readable inventory in gitignored `trainer/outputs/`.

- [ ] **Step 1 (host free):** run Task 7's inventory and shortlist (already fixed, nothing is re-selected), export each shortlisted network with Task 8 against a Slice 1 JAR, run Task 9's Stage 0 on each, and fill the table: checkpoint, tensor and network content hashes, nnue hash and uuid, the training-data identity and whether it used inverted Stage 1 labels, quantization MAE and max, scale utilization, hard-gate results, controlled-orientation result, and the diagnostics as measurements.
- [ ] **Step 2: Write the corrected-label comparison design:** the two arms (the historical white-perspective control specified in Slice 2 Task 6 step 4, and the repaired mover-relative dataset), same Stage 2, same seed 42, same split membership (Task 5 invariance), same recipe and step budget as P1-G04, with only the Stage 1 labels differing, scored by held-out correlation on the repaired held-out set and then by the strength screen. Nothing is launched without the owner. The historical-label control is not implemented.
- [ ] **Step 3: Phase 22 entry, commit and push.**

**Slice 3 acceptance criteria.**
- Every inventoried checkpoint has a file hash, a tensor hash and a network content hash covering all inference-relevant parameters, no record holds an absolute path, and equal tensors are one network (the `P1-G04` and `M-1 seed 42` pair is the regression case).
- The shortlist is a pure function of the inventory and the committed group file, is fixed before any rescoring, keeps experiment pairs whole, and its cap tie-break is the fixed group order with no quality input. Excluded groups are listed with their reason.
- Exporting an already-exported network is refused unless `--allow-duplicate` is given with a recorded reason.
- Hard gates, each pass or fail with `not run` never counted as pass: identity (read-back equals the quantized network, uuid and SHA-256 equal the manifest), loader safety (`assert_java_loadable`, and the real strict engine when a JAR is given), exact Java-versus-Python parity (difference 0), and controlled-position orientation. A net with a sign inversion fails orientation even with perfect parity.
- Float-versus-quantized parity, scale utilization, corpus material-sign agreement, side gaps, correlations and mirror differences are recorded as measurements with no threshold.
- The corrected-label comparison is designed to the command and hashes, and not launched. The exporter, quantizer, loader and evaluator are unchanged (`git diff` shows no edit to those files).

---

## Verification commands (exact)

- Engine and Java suites: `mvn -pl engine-core,engine-uci,engine-tuner -am test` (expected after Slice 1: the current 564, 82 and 162 tests plus the new strict tests, 0 failures).
- Slice 1 targeted: `mvn -pl engine-core,engine-uci -am test -Dtest=StrictNnueIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`, then `cd tools && python -m pytest tests -q`.
- Trainer: `cd trainer && .venv/bin/python -m pytest -q` for the whole suite (524 tests passed on develop per #299; the new total is recorded in the execution record), and the targeted files named in each task.
- Native Windows (no match): `python tools/fixed_count_match.py ... --prepare-only` with a good and a missing network, and `python tools/nnue_preflight.py --jar <jar> --eval-file <net>`.
- Red-green: each behavioral test is shown failing against the old behavior (reverting the change or the fix temporarily, touching the file so Maven recompiles) before it is accepted, as in #328.

## Delivery boundaries

- Three PRs, one per slice, each against `develop`, each with a Phase 22 dev entry and a short investigation note where the slice produces evidence. The PR bodies follow the #299 level of detail.
- Slice 1 PR: engine and tools only. Slice 2 PR: two new trainer scripts (label-semantics probe and repair verification), their tests, and the evidence notes (the datasets themselves are never committed). Slice 3 PR: new trainer modules and scripts, their tests, and the inventory note.
- No PR closes #263 or #265. Each ends with a statement of what remains open on those issues for the owner to decide.
- Pushing branches and opening PRs is authorized by the approval. Merging needs the owner's go-ahead after the CI and evidence report, as for #328. After each slice, one independent read-only review runs on the integrated diff with this plan and the evidence, and its outcome is recorded below.
- Out of scope, recorded so nobody adds it silently: changing the default evaluator or any default option, editing the demonstration launchers, an expected-uuid option in the engine, implementing the historical-label control, repairing `stage1-lichess-filtered`, repairing Stage 2 or Stage 3 data, training or relabeling anything, play-strength claims, higher-QA requantization (#265 stays deferred), `go nodes` and the ponderhit-order item (#268), #296, #315 and #316.

## Execution record

- **Approved revision and approval reference:** revision 2 (this file with the 2026-11-27 amendments folded in), approved by the owner in the session on 2026-11-27: D1 to D7 accepted, filtered dataset unchanged, sidecar approach retained, topic branches instead of phase-number branches, work within Phase 22. Approval covers Slices 1 to 3. Real-dataset repair, full checkpoint rescoring and other substantial local workloads are approved in scope but wait for the owner to confirm the host is free. No training, matches, production-default changes, higher-QA experiments or unrelated optimization.
- **Slice progress and verification evidence** (2026-11-27). Owner instruction to merge: #329, #331 and #332 once heads, CI and sequential integration were clean, and to correct the shortlist policy before #333.
  - **Merged to `develop`:** #329 (this plan) at `7207748` as `18155a9`; #331 (Slice 1, strict execution, #330) at `c8cef02`; #332 (Slice 2, Stage 1 repair evidence) after a merge of `develop` into its branch to resolve the Phase 22 dev-entry conflict (both entries kept) and a fresh green CI at `b20e5e9`.
  - **Slice 1 (#331):** `StrictNnue` with 14 child-process tests, `tools/nnue_preflight.py` (39 tests), `fixed_count_match.py` and `nnue-gauntlet.ps1` pre-flight (22 tests), native Windows `--prepare-only` refusals checked, one independent review with all in-scope findings fixed.
  - **Slice 2 (#332):** the label-semantics probe and `verify_stage1_repair.py`, trainer suite 602 passing, one independent review with all findings fixed (per-shard row comparison, the probe's stated majority-only limit and block check, the `--drop-duplicates` split, terminators and repaired manifest hashes). Task 6 (the real repair) has not been started.
  - **Slice 3 (#333, merged at `a6f7c7a` as `e55ca5c` on owner instruction after unchanged head and green CI):** Tasks 7 to 9 in code, trainer suite 663 passing after merging `develop`. One independent review with all findings fixed (`6a2cfba`, `3bafee3`), and a one-off run of the real strict JAR on a synthetic network (export with identity and engine loading, Stage 0 parity on 417 positions with 0 mismatches). **Shortlist policy corrected on owner instruction (`81d768a`):** network execution is deduplicated and experimental relationships are not (a comparison reuses an identical arm and adds only its distinct counterpart, a fully duplicate comparison is kept at no cost, identical arms are flagged), the cap counts distinct deployed identities once each (the anchor was first counted inside the 10, corrected in the follow-up below), and every deployed network lists all its checkpoint labels and hashes. The order is now by evidence tier with a written reason per group, because #327 states no order and the first chronological order is not supported by it: P3A-001 (+0.0002 held-out over P1-G04, inside the M-1 seed spread 0.5898 to 0.5977) no longer precedes the M-1 seed replicates, and E-15 stays ahead of them as a distinct data source. With all identities distinct the first version took the anchor, P1-G04, P4IV, the P5 selected pair, the E-16 seed 42 pair, the E-15 pair and M-1 seed 43. Verified by 15 shortlist tests (shared arm, fully duplicate comparison, identical arms, cap by identity, provenance labels, the committed tiers and cut) and two mutations (cap charged per member, no reuse) that fail them. No broader review was requested for this change.
  - **Follow-up PR (`feat/screening-rescore-and-budget`), three readiness gaps closed on owner instruction, no real data touched:** (1) the budget is now 10 distinct candidate networks plus the E-3 anchor, at most 11 distinct networks (`candidate_cap` in the group file, the anchor group outside it, a candidate arm identical to the anchor reuses it free), shared-arm deduplication and the revised tier order unchanged, frozen before any real checkpoint was measured; with all identities distinct the first eight groups take the anchor and 10 candidates and P3A-001 and tier D are cut. 17 shortlist tests including two new ones for the anchor outside the cap. (2) `scripts/rescore_held_out.py`: builds the held-out set once the way `combine_and_split` does, from the original and corrected Stage 1 directories plus Stage 2, so both label sets are on the same ordered records (checked: same FEN sequence, same metadata, identical prediction vectors), scores each shortlisted network with `evaluate_held_out` overall, per side, per source and per source and side with record counts, differing-label counts, ordered FEN and record-identity hashes, both label-set hashes, dataset and checkpoint hashes and prediction hashes. 9 tests including the one that proves only the reference labels differ (only Stage 1 Black-to-move rows, exactly negated) and equality with `combine_and_split` for both `drop_duplicates` settings; mutations (corrected set built from the original labels, wrong seed, wrong held-out fraction, identical-prediction guard) each fail a test. (3) `scripts/compare_stage2_records.py`: a legacy directory is migrated into a temporary directory with the existing `migrate_legacy*_to_v1` functions and compared record for record with its v1 copy, with the outcomes `identical`, `ordering-mismatch`, `content-mismatch` and `reader-failure` kept apart (10 tests on synthetic legacy119, legacy124 and v1 fixtures; three mutations fail them). Found while writing it: the production Stage 2 reader cannot read a legacy shard (no header), so the held-out steps must read `stage2-quiet-sf-wdl-v1`, and the sequence was corrected accordingly. Trainer suite 684 passing.
- **Outstanding work and next action:** merge the follow-up PR on the owner's authorization. After that, once the owner confirms the host is free, the complete real-data readiness sequence runs as one batch with its existing stop conditions and without separate approvals (the steps are in the "Real-data execution sequence" section of `docs/engineering/investigations/2026-11-27-screening-readiness-inventory.md`: probe, audit, repair, verify, Stage 2 identity, historical held-out reproduction, inventory and shortlist, rescoring, export, Stage 0, results note). The `--confirm-white-perspective` repair of the two identified legacy datasets is authorized by the owner on the conditions listed in that section's Step 3. Until the host is confirmed free, no real-data repair, inventory, rescoring, export or Stage 0 runs. The isolated historical-label control stays specified and unimplemented. #263 and #265 stay open. #296, #315 and #316 are unchanged.
- **Integrated verification and final review status:** each slice has had one independent read-only review with its findings addressed; the integrated verification of the real-data steps is not done.
