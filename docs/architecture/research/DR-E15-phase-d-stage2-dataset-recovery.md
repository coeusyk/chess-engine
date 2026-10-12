# E-15 Phase D: Stage 2 base-dataset blocker, investigation and resolution

Governs issue #223. Short, standalone investigation record per `CLAUDE.md` section 7 (preserve
reusable root-cause methodology, not just the conclusion). Blocks nothing preregistered in
`DR-E15-stage3-first-retraining-preregistration.md` -- this is an infrastructure recovery, not a
design or parameter change.

## 1. Symptom

Reconstructing E-15's "36,000-record base training set" (`DR-E15` section 8) via
`scripts/train_candidate_net.py:combine_and_split(STAGE1_DIR, STAGE2_DIR, seed=42)`, using the
established `STAGE2_DIR = outputs/datasets/stage2-quiet-sf` path every prior Phase 1/3/4 script
(`phase1_experiment_2a.py`, `phase1_experiment_2b.py`, `phase1_optimization_sweep.py`,
`phase3_experiment_3a.py`, `phase3_label_audit.py`, `phase4_p4i_k_sweep.py`) already hardcodes,
fails immediately:

```
trainer.dataset.mmap_shard.ShardFormatError:
outputs/datasets/stage2-quiet-sf/shard-0.bin: bad magic b'rnb1kbnr'
```

## 2. Root cause (not a new corruption -- a known, documented, unfinished migration)

`mmap_shard.py`'s own module docstring (`## Legacy formats and migration (#207)`) already
documents this exactly: two legacy pre-header shard layouts exist on disk --
**legacy119** (pre-WDL, itemsize 119, the D-8/#199 layout) and **legacy124** (post-WDL-backfill,
itemsize 124) -- and `read_shard()` deliberately never sniffs or guesses a legacy layout; it
rejects anything that isn't exact current-format V1.

`outputs/datasets/stage2-quiet-sf/shard-0.bin` is 2,380,000 bytes = 20,000 x 119 -- legacy119,
exactly the layout `mmap_shard.py` names as real and un-migrated at that path. It was never
corrupted by anything in this session or any other; it has simply been in a format the
current-code steady-state reader (`read_shard`) cannot open since #207 landed, because nothing
had migrated it in place. Sitting alongside it (created 2026-09-14, same day, before this
session), `outputs/datasets/stage2-quiet-sf-v1/shard-0.bin` is 2,660,028 bytes = 28-byte V1
header + 20,000 x 133 -- consistent with a `migrate_legacy119_to_v1()` output, but with no
accompanying manifest or recorded provenance linking it back to the legacy source.

## 3. Verification (proving semantic + byte equivalence before trusting the V1 copy)

**Step 1 -- field-by-field comparison, all 20,000 records.** Read `stage2-quiet-sf/shard-0.bin`
directly via `mmap_shard`'s own `_LEGACY119_DTYPE` (the same dtype `migrate_legacy119_to_v1()`
uses internally, not a newly written decoder); read `stage2-quiet-sf-v1/shard-0.bin` via the
existing, unmodified `read_shard()`. Compared `fen`, `eval_cp`, `eval_mate`, `ply`,
`search_depth`, `search_nodes` for every record, index-for-index (sequential zip, so ordering is
checked implicitly by construction, not just content). **Zero mismatches across all 20,000
records.** `label.wdl` and `metadata.game_id` are `None` on every one of the 20,000 V1 records,
exactly as expected -- legacy119 never had either field, and `migrate_legacy119_to_v1()`'s own
contract never fabricates one.

**Step 2 -- independent re-migration, byte/hash comparison.** Called the existing, unmodified
`migrate_legacy119_to_v1(source, dest)` directly against the same legacy source into a fresh
temporary destination (never touching the original `stage2-quiet-sf/` or the existing
`stage2-quiet-sf-v1/`). Result: **byte-for-byte SHA-256-identical** to the existing
`stage2-quiet-sf-v1/shard-0.bin` (`a43a0c0df1567736ab3b0f6676fccb7d8df12275cc22c89a84adbf4b0a7cf198`,
both). This is definitive: the existing V1 file is not merely "similar," it is the exact,
reproducible output of running the documented migration function against the untouched legacy
source -- there is no unaccounted-for difference a hash match of this kind could hide.

## 4. Historical base-split reproduction

No literal historical FEN-membership hash was ever recorded anywhere in this repository's prior
Phase 1/3/4/5 provenance to compare against directly -- every prior experiment records record
*counts* and correlation deltas, never a membership hash of the 36,000/4,000 split itself. Given
that gap, verification here is by exact count and composition match instead:

- Pool: 20,000 Stage 1 (`stage1-lichess`) + 20,000 Stage 2 (`stage2-quiet-sf-v1`) = 40,000.
- `combine_and_split(seed=42)`: 4,000 held out (10%), **36,000 training** -- exactly the
  "36,000/4,000" figure cited in `DR-E15` section 8 and every prior Phase 1/3/4/5 document.
- Since Step 1 above already proved the V1 shard's 20,000 records are content-identical
  (field-by-field, in order) to the legacy source every one of those prior experiments actually
  read, the resulting 36,000/4,000 split drawn from the V1 path is content-identical to what
  those historical runs would have produced from the same seed and pool -- not merely
  count-identical by coincidence.

## 5. Canonical path decision

**`outputs/datasets/stage2-quiet-sf-v1/` is the canonical, readable Stage-2 artifact for E-15
Phase D and any other current code that needs `read_shard()`-compatible access.** The legacy
`outputs/datasets/stage2-quiet-sf/` source is left in place, untouched -- `migrate_legacy119_to_v1()`'s
own contract is non-destructive (reads the source, never modifies it), and this investigation
did not delete or alter it either. A `manifest.json` was added to `stage2-quiet-sf-v1/` (it had
none), copying the original `stage2-quiet-sf/manifest.json` labeling provenance verbatim (same
underlying Stockfish-labeled data, just re-encoded) plus a `migration_provenance` block recording
the verification in section 3 above. No existing `DatasetProvider` requires a manifest to
function (`StockfishLabeledProvider` globs `*.bin` directly, its `dataset_identifier` is a
caller-supplied string, not read from disk) -- this manifest is added for auditability, not
because current code needs it to run.

**Only E-15's own data-prep path is affected.** The historical Phase 1/3/4 scripts that hardcode
the plain `stage2-quiet-sf` path are left unedited -- they are a record of what those experiments
actually ran (successfully, before this format gap existed), not live code this investigation
was asked to fix, and rewriting them would misrepresent history for no benefit (none of them are
being re-run by this task).

## 6. Outcome

Phase D preflight was rerun against the corrected path:

- Every frozen Phase-B/C Stage-3 membership hash (control/treatment train, held-out, equalized)
  reproduced exactly, unchanged.
- Base split: 36,000 training / 4,000 held-out (v1), exactly as expected.
- Final training-list construction: control and treatment share byte-identical base-record
  membership and order (36,000 records each); control's equalized Stage-3 slice (7,155 rows) and
  treatment's (7,155 rows) concatenate to **43,155 records each**, counts equal, no
  deduplication applied anywhere in the pipeline.

**Phase D training is now unblocked on data.** No initialization artifact has been created and no
training has started -- this document and its fix stop at the preflight boundary, per the task
that requested this recovery.
