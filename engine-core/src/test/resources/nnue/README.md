# D-6 export-fixture — provenance

`d6-export-fixture.nnue` (issue #197's "Java-side round-trip test fixture"): a real
`.nnue` file produced by the Python `Exporter` (`trainer/trainer/export/exporter.py`),
committed so `NnueExportFixtureRoundTripTest` can assert the real Python writer's
output loads correctly via the real, unmodified `NnueNetwork.load()` — not a
hand-written Java-side encoder (that's `NnueNetworkLoaderTest`'s job).

## Generation

```python
import numpy as np
from pathlib import Path
from trainer.export.canonical import FEATURES_PER_PERSPECTIVE, QuantizedCanonicalNetwork
from trainer.export.exporter import export
from trainer.reproducibility.experiment_metadata import ExperimentMetadata

hidden_width = 2
ft_weights = np.zeros((FEATURES_PER_PERSPECTIVE, hidden_width), dtype=np.int16)
for f in range(FEATURES_PER_PERSPECTIVE):
    ft_weights[f, 0] = (f % 200) - 100
    ft_weights[f, 1] = -((f % 150) - 75)

network = QuantizedCanonicalNetwork(
    hidden_width=hidden_width,
    ft_weights=ft_weights,
    ft_biases=np.array([11, -22], dtype=np.int16),
    output_weights=np.array([[33, -44], [55, -66]], dtype=np.int16),
    output_bias=777,
    qa=127, qb=64, output_scale=400,
    architecture_id=1, feature_set_id=1,
)
metadata = ExperimentMetadata(seed=1, trainer_commit="c" * 40, started_at_epoch_seconds=1700000001, config={})
export(network, metadata, dataset_identifiers=["fixture"], output_dir=Path("."))
```

`export()` assigns a fresh `network_uuid` **and** `created_at_epoch_seconds` (wall-clock
"now" at export time) on every call — neither is taken from `ExperimentMetadata`, by
design (architecture doc §5: export identity is assigned at export time, not carried on
the IR or the training-run metadata, since a checkpoint can be exported long after
training finished). `ExperimentMetadata.started_at_epoch_seconds` above records when
*training* started, a different timestamp entirely, and is not asserted by the Java
test. The file was generated once, and the resulting UUID
(`1ac3222e-0765-4c93-b3b3-5b2718e2d92b`) and timestamp (`1784014895`) are asserted
literally in the Java test, matching the committed binary. If this fixture is ever
regenerated, update both expected values in `NnueExportFixtureRoundTripTest` to match
the new file — never hand-edit the binary itself.

## Expected values (asserted by `NnueExportFixtureRoundTripTest`)

- `hiddenWidth = 2`, `qa = 127`, `qb = 64`, `outputScale = 400`
- `networkUuid = "1ac3222e-0765-4c93-b3b3-5b2718e2d92b"`
- `trainerCommit = "cccccccc"` (header carries the *short*, 8-char commit; the input
  to `ExperimentMetadata` was `"c" * 40`, matching Exporter's documented truncation)
- `createdAtEpochSeconds = 1784014895` (wall-clock time at export, not the
  `started_at_epoch_seconds = 1700000001` given to `ExperimentMetadata` above — see note
  above)
- `ftWeights[0] = [-100, 75]`, `ftWeights[100] = [0, -25]`, `ftWeights[767] = [67, 58]`
  (row-major per feature, matching `ft_weights[f, 0] = (f % 200) - 100`,
  `ft_weights[f, 1] = -((f % 150) - 75)`)
- `ftBiases = [11, -22]`
- `outputWeights = [33, -44, 55, -66]` (perspective-major: "us" = `[33, -44]`,
  "them" = `[55, -66]`)
- `outputBias = 777`
