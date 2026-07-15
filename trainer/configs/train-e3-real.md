# E-3: first real candidate net — run record

Issue #203 (Phase E, Track A). Produces `nets/dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.json`
via `trainer/scripts/train_candidate_net.py`.

`dataset_composition` proportions are computed from the *actual post-split training
set* (36,000 records: 49.983%/50.017%), not the pre-split 40,000-record union
(which would read exactly 50/50 and silently diverge from what was really trained
on for any future unequal-sized mix) — code-review finding, fixed. `label_engine_version`
is left `null` in the manifest: this corpus mixes public (no label engine) and
sf-labeled (Stockfish 18) sources, and a single flat non-null value would misstate
a 50/50 mix as 100% Stockfish-labeled — `dataset_composition` already carries the
accurate per-source stage breakdown; `null` is `exporter.py`'s own documented,
valid "not applicable to a single flat field" value, not a missing one —
code-review finding, fixed.

## K provenance

`train.py`'s sigmoid loss requires K "taken from an existing `KFinder` run" (PRD
"Trainer Requirements", a hard requirement). Several stale K values exist in
gitignored `tools/*.log` from past Texel-tuning sessions (0.5, 1.145, 1.42 —
different eras/corpora, none marked as authoritative for today's `EvalParams`).
Rather than reuse any of those, K was freshly recalibrated against the
**currently-live** `EvalParams` using `engine-tuner`'s existing `--freeze-params`
isolation mode (commit `47ff6cb`, "K calibration isolation" — calibrates K only,
never touches classical eval weights):

```
mvn -pl engine-core,engine-tuner -am package -DskipTests
java -jar engine-tuner/target/engine-tuner-0.5.8-SNAPSHOT-shaded.jar \
  tools/quiet-labeled.epd --freeze-params --corpus-format epd
```

Output: `K = 2.773456` (MSE = 0.06203967), against 703,755 real WDL-outcome-labeled
positions from `tools/quiet-labeled.epd`, val split (56,300 positions,
train/val/test = 80/10/10, seed 42 — `TunerMain`'s own existing defaults). The
`tuned_params.txt` side effect of this run was reverted (`git checkout --`) since
`--freeze-params` does not change `EvalParams` and nothing in it was meant to be
committed by this issue.

Code-review sanity-check on this value: `2.773456` sits `0.226544` below
`KFinder.K_MAX = 3.0` — over 2000x `KFinder.TOLERANCE` (`1e-4`) away from the wall,
so this is a genuine interior minimum the ternary search converged to, not a
clamped/boundary artifact (a boundary result would land within `TOLERANCE` of
`3.0`). Plausible given `EvalParams` has been re-tuned repeatedly since the older
0.5/1.145/1.42 logs this doc's earlier draft found — a shrinking effective eval-cp
scale from repeated re-tuning pushes K upward over time. Deferred, non-blocking
follow-up (not required for this issue): a confirmatory rerun with a temporarily
widened `K_MAX` would fully rule out "the true optimum is beyond 3.0."

## Training data

Both real E-2 datasets, combined:

- `trainer/outputs/datasets/stage1-lichess/` — 20,000 positions, public (Lichess
  evaluated positions).
- `trainer/outputs/datasets/stage2-quiet-sf/` — 20,000 positions, sf-labeled
  (Stockfish 18, `nodes=25000, threads=1`).

Combined (40,000), shuffled with seed 42, split 90/10 → 36,000 training / 4,000
held-out.

## Network / training config

`hidden_width=256` (PRD §4 Network Specification: "(768 -> 256) x2 -> 1");
`qa=127`/`qb=64`/`output_scale=400` (matching the existing `TestNetworks.java`/
`train-tiny.json` convention); `k=2.773456` (above); `learning_rate=0.01`,
`steps=2000`, `batch_size=256`, `seed=42`.

```
cd trainer
.venv/bin/python scripts/train_candidate_net.py \
  outputs/datasets/stage1-lichess outputs/datasets/stage2-quiet-sf \
  outputs/nets --k 2.773456 --steps 2000 --batch-size 256 --learning-rate 0.01 --seed 42
```

Final training loss: 0.066687. Clipping report: zero clipped values across
`ft_weights`/`ft_biases`/`output_weights` (no quantization range violations).

## Validation reports (recorded verbatim)

```
evaluate_held_out: ValidationReport(held_out_loss=0.08225942403078079, label_correlation=0.5043603181838989, position_count=4000)
eval_scale_check:  EvalScaleCheck(mean_absolute_difference_cp=735.5965576171875, position_count=393)
```

`eval_scale_check` ran against E-1's real classical corpus
(`bench/nnue-corpus/classical-golden-evals.csv`) for the first time beyond its
hand-built test fixture. The 735.6cp mean absolute difference is **not** a
pass/fail gate here — per issue #203's own explicit non-scope, this issue produces
and records the number; judging whether it (or overall playing strength) is
acceptable is Track B's job (E-4/E-5), not this issue's.

Reproduction (code-review finding — this snippet was missing, fixed):

```python
# from trainer/, with .venv active
from pathlib import Path
import torch
from trainer.model.network import NnueNet
from trainer.validation.validator import evaluate_held_out, eval_scale_check, load_classical_eval_corpus
from scripts.train_candidate_net import combine_and_split

_, held_out_records, _ = combine_and_split(
    Path("outputs/datasets/stage1-lichess"), Path("outputs/datasets/stage2-quiet-sf"), seed=42)

checkpoint = torch.load("outputs/nets/checkpoint.pt", weights_only=False)
config = checkpoint["config"]
model = NnueNet(config["hidden_width"], config["qa"], config["qb"], config["output_scale"])
model.load_state_dict(checkpoint["model_state_dict"])

print(evaluate_held_out(model, held_out_records, k=config["k"]))
print(eval_scale_check(model, load_classical_eval_corpus(Path("../bench/nnue-corpus/classical-golden-evals.csv"))))
```

Re-running this reproduces both reported values bit-for-bit, which also confirms
no train/held-out leakage (the held-out split is re-derived the same way from the
same seed, not read from a stored split).

## Reproducibility check at real scale

`train_quantize_export()` run twice with identical seed/config/data (200 steps,
for check speed) against the full 36,000-record real training set: quantized
`ft_weights`/`ft_biases`/`output_weights`/`output_bias` bit-identical across runs,
both manifests schema-valid via `validate_manifest()`. No wall-clock or memory
surprise at this scale (each 2000-step real run completes in ~20s).

## Java round-trip verification

Loaded the real `.nnue` via `NnueNetwork.load()` (jshell against `engine-core/target/classes`):
`hiddenWidth=256`, `networkUuid=dfffd3da-7f8f-4fc9-92dc-b3873c97fb21`,
`trainerCommit=76db953f`, `createdAtEpochSeconds=1784108373` — every field matches
the committed manifest exactly. Since the real `.nnue` binary itself is gitignored
(never committed, see below), a permanent regression test can't reference it
directly; `NnueNetworkLoaderTest.loadRoundTripsAtRealCandidateNetworkScale`
(code-review finding, added) instead round-trips synthetic weights at the same
real scale (`hidden_width=256`) the D-6 fixture test's `hidden_width=2` was too
small to exercise.

## Storage convention

The `.nnue` binary and `checkpoint.pt` live only in gitignored
`trainer/outputs/nets/` (never committed — architecture doc §9: "manifests
only — never the weight binaries, except the tiny CI net"). Only the manifest
is committed, to the new `nets/` registry at the repo root. Reproducible from
the committed manifest by re-running the command above against the same
`trainer_commit`.
