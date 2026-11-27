# Screening readiness: how checkpoints are identified, selected and checked

2026-11-27. Plan #329 (slice 3), referring to #265 and the staged protocol in the strength assessment (#327). This note records what exists to make the existing checkpoints ready for staged screening, how the shortlist is decided, which checks are hard gates and which are only measurements, and how to run all of it. **Nothing has been run on the real checkpoints yet.** The real inventory, the exports and the Stage 0 runs wait until the host is free, and no result below is a measurement of a real network.

## Identifying a network

`export()` assigns a fresh uuid and the current time on every call, so two exports of one checkpoint have different file hashes, and two checkpoints with the same tensors but different training metadata have different checkpoint hashes. The 2026-11-24 audit shows the consequence: `P1-G04` and `M-1 cp-only seed 42` have identical figures and the project already treats them as one network, yet their file hashes differ. File hashes cannot find duplicates.

`trainer/export/identity.py` adds two content hashes. `tensor_sha256` covers the float network and `network_content_sha256` covers the int16 network the engine would load. Both cover every inference-relevant parameter, not only the arrays: `hidden_width`, `qa`, `qb`, `output_scale`, the architecture id, the feature-set id, `output_bias`, the quantization version, and each array with its name, dtype and shape. They exclude the uuid, trainer commit, timestamps, dataset composition and all checkpoint metadata. Two networks that differ only in `qa` or `output_scale` hash differently even with equal arrays.

## The shortlist

`trainer/export/shortlist.py` with the committed `shortlist_groups.json` is a pure function of the inventory (labels, `network_content_sha256`, and the checkpoint hash when present). It reads no measured quality, so it can be fixed before anything is rescored.

**Policy: deduplicate network execution, not experimental relationships.** The unit is the group (the anchor, a baseline, or a paired comparison), kept whole or dropped whole so a control and its treatment are never split across the cap. A network is its `network_content_sha256`. A comparison with an arm identical to an already selected network reuses that network and adds only its distinct counterpart, a comparison whose arms are all selected costs nothing and is kept, and a comparison whose two arms are identical to each other is kept and flagged `identical_arms`. Each selected group lists what it deployed (`new_networks`) and what it reused and from which group (`reused`), so the arm relationship survives. The cap of 10 counts distinct deployed identities, once each, with the E-3 anchor included (the #327 stage 1 budget reads "about 10 nets and the E-3 anchor", and this reading keeps the total at 10, so the anchor is one of them). A group that would take the count over the cap is skipped whole and the next one considered, so the tie-break at the cap is the fixed order and nothing else. A group that adds no new network always fits. Checkpoint-level provenance is kept: the output also lists every deployed network with every inventory label that has its identity and their checkpoint hashes, including checkpoints that are in no group (this is where `M-1 seed 42` appears next to `P1-G04`).

This replaces the first version, which dropped a whole comparison when either arm duplicated an earlier network. That would have removed the E-16 seed 42 pair if its control turned out identical to the baseline, which discards a relationship in order to save a network that costs nothing to reuse.

**Priority, and why it changed.** The first committed order was chronological: the anchor, P1-G04, P3A-001, P4IV, then the P5, E-15 and E-16 pairs, then the M-1 seeds. #327 states no shortlist order, only the budget and the screen's purpose (drop nets scoring below 0.10 in 60 games), and the first order does not follow from that. A 60 game screen that only drops hopeless nets learns most from one representative of each distinct training signal, because nets that share a recipe are expected to land in the same place. The order is now written down in the file with a reason for each group:

- **Tier A, the reference and the base recipe:** E-3 (the only net that has played) and P1-G04. M-1 seed 42 is the same network and is recorded as a label of it.
- **Tier B, one representative of each distinct training signal:** P4IV (outcome blended into the target), the P5 pair at its selected step (auxiliary WDL head, because that run recorded a selection rule), the E-16 seed 42 pair (Stage 3 self-play with a shared opening prefix, the cleaner design) and the E-15 pair (the earlier self-play design, whose control collapsed).
- **Tier C, replicates of a represented recipe, which give the spread needed to read any difference between recipes:** M-1 seeds 43 and 44 (pure seed replicates) and P3A-001. P3A-001 differs from the base recipe by 75 sentinel rows and its held-out figure (+0.0002 over P1-G04) is inside the M-1 seed spread (0.5898 to 0.5977), so on the one metric available it is no more informative than a seed replicate. It no longer precedes the M-1 seeds. E-15 stays in tier B, ahead of the seed replicates, because it is a different data source, not because its design was good.
- **Tier D, later checkpoints and further seeds of represented recipes:** the P5 pair at the final step and the E-16 seeds 43 and 44.

With all identities distinct the first seven groups take exactly 10 networks (the anchor, P1-G04, P4IV, P5 selected pair, E-16 seed 42 pair, E-15 pair and M-1 seed 43), and P3A-001, M-1 seed 44 and tier D are cut by the cap with the reason listed. If a shared arm or a duplicate is found on the real checkpoints, the freed slots go to the next groups in order. The order is a judgment and not a measurement. Moving a group is a one-line change to the file, and nothing is selected before the inventory has run.

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
