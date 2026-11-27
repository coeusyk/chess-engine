# Screening readiness: how checkpoints are identified, selected and checked

2026-11-27. Plan #329 (slice 3), referring to #265 and the staged protocol in the strength assessment (#327). This note records what exists to make the existing checkpoints ready for staged screening, how the shortlist is decided, which checks are hard gates and which are only measurements, and how to run all of it. **Nothing has been run on the real checkpoints yet.** The real inventory, the exports and the Stage 0 runs wait until the host is free, and no result below is a measurement of a real network.

## Identifying a network

`export()` assigns a fresh uuid and the current time on every call, so two exports of one checkpoint have different file hashes, and two checkpoints with the same tensors but different training metadata have different checkpoint hashes. The 2026-11-24 audit shows the consequence: `P1-G04` and `M-1 cp-only seed 42` have identical figures and the project already treats them as one network, yet their file hashes differ. File hashes cannot find duplicates.

`trainer/export/identity.py` adds two content hashes. `tensor_sha256` covers the float network and `network_content_sha256` covers the int16 network the engine would load. Both cover every inference-relevant parameter, not only the arrays: `hidden_width`, `qa`, `qb`, `output_scale`, the architecture id, the feature-set id, `output_bias`, the quantization version, and each array with its name, dtype and shape. They exclude the uuid, trainer commit, timestamps, dataset composition and all checkpoint metadata. Two networks that differ only in `qa` or `output_scale` hash differently even with equal arrays.

## The shortlist

`trainer/export/shortlist.py` with the committed `shortlist_groups.json` is a pure function of the inventory (labels and `network_content_sha256`). It reads no measured quality, so it can be fixed before anything is rescored, which removes the dependency between the rescoring in the repair slice and this slice.

- The unit is the group (the anchor, a baseline, or a paired comparison). A group is kept whole or dropped whole, so a control and its treatment are never split across the cap.
- Groups are considered in file order: the E-3 anchor, the P1-G04, P3A-001 and P4IV-001 baselines, then the P5 selected-step pair, the E-15 pair and the E-16 seed 42 pair, then later groups (M-1 seed 42, the P5 final pair, the E-16 seed 43 and 44 pairs, M-1 seeds 43 and 44).
- One network per distinct `network_content_sha256`. A group any of whose networks duplicates one already selected is dropped as a duplicate of the earlier group, so the earliest group in the fixed order represents the network (M-1 seed 42 is expected to be dropped as a duplicate of P1-G04).
- The cap is 10 distinct networks, the stage 1 budget in #327. A repeated label in the inventory is an error, so the result cannot depend on record order. A group that would exceed it is skipped whole and the next one considered, so the tie-break at the cap is the fixed order and nothing else: not held-out correlation, quantization error or any other score. With the file as committed the first seven groups take exactly 10 networks, so every later group is dropped with the reason `cap`, `duplicate-of` or `missing`, listed in the output so no exclusion is silent.
- The selected step is used for P5 because that experiment recorded a selection rule, and its final checkpoints are in a later group.

## Hard gates and measurements

An export (`scripts/export_screening_net.py`) writes the `.nnue` and manifest with the public exporter, unchanged, and a sidecar `<uuid>.screening.json` next to them (no change to the exporter or the manifest schema). `scripts/screen_stage0.py` adds the checks that need the network file. Each gate is pass or fail, and `not run` is never a pass. The sidecar is written last, so a process killed between the export and that write leaves a `.nnue` and `.json` with no sidecar. The duplicate scan does not see such a pair, and it should be deleted and exported again. Only sidecars that say `exported` count for duplicate refusal, so a failed gate does not block exporting the network again.

| Check | Kind | What it is |
|---|---|---|
| Identity | hard gate | The network read back from the file equals the quantized network array for array and scalar for scalar, the header uuid equals the manifest uuid, and the file SHA-256 equals the manifest's `nnue_sha256` |
| Loader safety | hard gate | `assert_java_loadable` (the worst-case accumulator bound the Java loader enforces), and with an engine JAR the real strict engine loads the file through the pre-flight with the pinned uuid and hash |
| Java against Python parity | hard gate | The real engine's integer score (`position fen` and the UCI `eval` command, the `int16 score` line) equals the Python reference on the committed 393-position corpus and the controlled positions, difference exactly 0. #265 established bit-exactness on committed fixtures, so there is no tolerance |
| Controlled orientation | hard gate | The correct sign on every one of 24 controlled positions |
| Duplicate refusal | hard gate | An export whose `network_content_sha256` is already in the output directory is refused unless `--allow-duplicate REASON` is given, and the reason is recorded |
| Float against quantized parity, scale utilization | measurement | Recorded in the sidecar from the existing `compute_quantization_parity` and `compute_scale_utilization`, no threshold |
| Corpus material sign agreement per side, side gap, correlations, mirror difference | measurement | Reported next to the gates, with no threshold |

Orientation is checked on controlled positions, not on a corpus statistic. `orientation_positions.epd` holds six quiet positions with a decisive material difference (a queen or two rooks against a bare king, a queen and rook against pawns, a full army against two rooks), each in four forms: the advantaged colour is White or Black, and it is to move or not, 24 positions in all. The expected sign of the mover's score is known by construction, and the gate requires it on every position, with a zero score counting as wrong. The file is generated by `controlled_forms` and a test checks that it is. The negative controls in the tests are a score list inverted for every position (fails all 24), one inverted for Black to move only (fails exactly the 12 Black-to-move positions while the White-to-move half stays perfect), and a network with its output layer negated. The last two run through the real quantized evaluator: a small network that really knows material passes the gate, and the same network with its output layer negated has scores identical in magnitude (so no magnitude comparison between them can tell them apart) and fails all 24. A test also ties the gate's expected signs to the Python reference's own side-to-move convention, so a White-relative reference would fail it.

The corpus statistics are measurements and not gates because material is a poor proxy for the score in tactical positions and a trained network is not exactly mirror-symmetric. Sign agreement and correlation are different metrics and are reported separately. The 0.94 to 0.98 correlations measured by hand in #327 on five networks are a reference for reading the numbers, not a calibration of a rejection limit.

## Running it (when the host is free)

All commands run from `trainer/` in the trainer environment and write only to new files.

```
python -m scripts.checkpoint_inventory --input LABELS.json --root <checkpoint root> --output inventory.json --shortlist
python -m scripts.export_screening_net CHECKPOINT.pt --out-dir <screening dir> --engine-jar <jar>
python -m scripts.screen_stage0 <screening dir>/<uuid>.nnue --output <uuid>.stage0.json --engine-jar <jar>
```

`LABELS.json` maps the labels in `shortlist_groups.json` to checkpoint paths. The engine checks need a JAR built with `StrictNnue` (#330) and `tools/nnue_preflight.py`. Without an engine JAR the engine gates report `not run`, and the tools say that no Java loadability or parity is claimed.

## Corrected-label comparison (design only)

The comparison of original and corrected Stage 1 labels uses the same recipe, seed 42, split membership and step budget as P1-G04, with only the Stage 1 labels differing. The repaired dataset loads through production `combine_and_split`. The original arm cannot, because production correctly refuses legacy white-perspective data, so it needs the isolated historical-label control specified in the plan (a separate module that requires an acknowledgement and a pinned manifest hash, refuses a marked dataset, shares the split code with production without touching its guard, and labels everything it produces as a legacy control). That control is not implemented here, and nothing is trained or launched. Scoring would be held-out correlation on the repaired held-out set, then the strength screen, run only with the owner's go-ahead.

## Not done here

The real inventory, the exports, the Stage 0 runs and the filled results table; the historical-label control; any training or match.
