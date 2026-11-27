# What the historical Stage 1 labels are, before anything is repaired

2026-11-27. Issue #263 and the plan in #329. `repair_stage1_perspective.py` negates `cp` and `mate` for every Black-to-move row, which is only right if the shard really holds White-perspective labels. The tool asks the caller to assert that with `--confirm-white-perspective`. This note records the evidence behind that assertion for the two local Stage 1 datasets. Nothing was repaired and nothing was written to the datasets. The repair itself waits until the host is free.

A commit date and a missing manifest marker are not evidence of what the labels are, so three independent kinds of evidence are used. All three have to agree.

## 1. The acquisition code

Both manifests record a `trainer_commit`. For the 20,000-row dataset it is `15e021a1` (2026-07-14), and `trainer/scripts/acquire_stage1_lichess.py` does not exist at that commit. The script was first committed in `44dce25` (2026-07-15 08:57 UTC), and the manifest's `created_at_epoch_seconds` is 2026-07-15 08:28:57 UTC, 28 minutes earlier. So the dataset was acquired with a version of the script that was not yet committed, and the recorded commit says nothing about what code ran. That is why the commit alone cannot authorize anything.

What can be said: `44dce25` is the only version of the script in history before the #263 fix (`ff70846`, merged as `e2dd7bf` in #281, both 2026-11-21), `_to_record` in it copies `pv.get("cp")` and `pv.get("mate")` unchanged with no side-to-move handling, and nothing in it imports the perspective helper. The 100,000-row dataset (2026-07-20 11:21 UTC) records `6cbb4915`, which contains that same single version. For the 20k dataset the match between the uncommitted run and the committed script is an inference, which the data check below tests.

## 2. The source's convention

The Lichess evaluation dump stores `cp` and `mate` from White's perspective (the claim in #263, with the Lichess protocol reference it cites). I did not re-fetch that source for this note, so it rests on the issue.

## 3. The data itself

`trainer/scripts/probe_stage1_label_semantics.py` (new, read-only, tested on synthetic files of each kind) correlates `eval_cp` with White's material minus Black's, and with the mover's material balance, separately for each side to move. White-perspective labels follow the first quantity for both sides. Mover-relative labels follow the second, so for Black to move their correlation with the first is negative. Measured on the real files:

| Dataset | Manifest SHA-256 (first 12) | White to move: rows with cp, r with White-minus-Black | Black to move: rows with cp, r with White-minus-Black | Verdict |
|---|---|---|---|---|
| `stage1-lichess` (20,000 rows) | `213c3d2b7aba` | 8,216, +0.260 | 7,844, +0.201 | white-perspective |
| `stage1-lichess-100k` (100,000 rows) | `98251637b15f` | 41,652, +0.236 | 39,586, +0.199 | white-perspective |

The 20k manifest hash equals the one recorded in the P5 provenance (`213c3d2b...`), and its `stage1.csv` (`a9d338b2...`) equals the manifest's `output_sha256`, so this is the dataset P5 used. The threshold in the probe (0.10) is a sign test, not a fit, and material is a crude proxy for the score. The margin is about a factor of two on the Black-to-move side, and the probe only establishes the majority convention of a file. A minority of rows that were already corrected, interleaved at random, is not visible to it: in a synthetic check a tenth of the Black rows flipped back still reads as white-perspective, and the verdict is withdrawn well before half. It also refuses a file in which a contiguous tenth of either side has a correlation of the wrong sign, which catches a re-acquired or separately repaired block, and nothing finer. On the real files the lowest tenth has a correlation of +0.06 to +0.18, so none is of the wrong sign. That every row was written by one adapter version is therefore a claim for section 1, and the probe supports it only in the aggregate, which is why the two have to be read together.

All three agree for both datasets, so the evidence supports `--confirm-white-perspective` for them. Whether to run the repair is the operator's decision. The 20k conclusion leans on the data check more than the 100k one does, for the reason in section 1.

The datasets are gitignored, so these figures cannot be re-derived from the repository alone. They come from `python -m scripts.probe_stage1_label_semantics <directory>` run on the two local directories, and the same command reproduces them on any copy.

## What the repair will and will not change

Row count, order, FENs, White-to-move rows and extra columns stay identical, and only Black-to-move `cp` and `mate` are negated. `scripts/verify_stage1_repair.py` (new, read-only, tested against deliberately broken copies: a changed White row, a wrong Black row, a dropped middle or tail row, swapped rows, a row moved between shards, a changed extra column or FEN, a CRLF to LF conversion, a missing marker, a missing or stale provenance or manifest hash, and an original altered after the repair) checks exactly that against the original, compares rows shard by shard, re-hashes the original shards against the repaired manifest's `repaired_from` block and the repaired shards against the repaired manifest, and confirms the production guard accepts the repaired directory and still refuses the original.

Repair invariance (the repair moved no position between the training and held-out sets) is a different claim from reproducing the historical P5 held-out set, and they are reported separately. The first follows from the split depending only on the order and length of the combined position list. The split computed here is the historical path (`drop_duplicates=False`) that the frozen checkpoints and the P5 hash used. `train_candidate_net.main` trains with `drop_duplicates=True`, so `--drop-duplicates` reports the split a new run would get, and the two are not interchangeable. The second also depends on Stage 2 and on code history. For Stage 2, the `-v1` shard of the non-WDL directory has a recorded field-by-field migration from the legacy shard (20,000 records, zero mismatches), in the `migration_provenance` block of its local, gitignored manifest. The WDL pairing used by P5 (`stage2-quiet-sf-wdl`, shard `4955f2e5...`) has a `-wdl-v1` copy with no manifest, so its equality with the legacy shard is not recorded and is checked when the real-data step runs.

## Not done here

The repair of the real shards, the held-out reproduction, the rescoring of frozen checkpoints and the isolated historical-label control (specified in the plan, not implemented) all wait for the host to be free and for the shortlist from the screening slice. `stage1-lichess-filtered` is left alone.
