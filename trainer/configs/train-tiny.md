# `train-tiny.json` — provenance of each value

A config for exercising the training loop's plumbing (D-4, issue #195's "tiny training
run, few steps, fixed seed" acceptance criterion) — not a calibrated, real training run.

- `qa: 127`, `qb: 64`, `output_scale: 400` — match the existing test-fixture
  convention already established in
  `engine-core/src/test/java/coeusyk/game/chess/core/eval/nnue/TestNetworks.java`,
  not invented values. Kept identical here so a tiny checkpoint trained with this
  config stays numerically comparable to that fixture during later manual spot-checks.
- `k: 1.0` — **an explicitly uncalibrated placeholder**, not a real `KFinder` output.
  PRD "Trainer Requirements" states K must be "taken from the existing `KFinder`
  output" — a real training run must first run `engine-tuner`'s `KFinder` (or
  `TunerMain`) against real labeled data and use *that* value here. `1.0` sits inside
  `KFinder.K_MIN`/`K_MAX`'s search range (`[0.5, 3.0]`,
  `engine-tuner/.../KFinder.java`) purely so the sigmoid loss behaves sanely for
  plumbing tests; it is not derived from any dataset.
- `hidden_width: 8`, `steps: 5`, `batch_size: 4`, `learning_rate: 0.01`, `seed: 42` —
  chosen only to keep a test run fast (milliseconds) and deterministic; not
  hyperparameters for a real net.
