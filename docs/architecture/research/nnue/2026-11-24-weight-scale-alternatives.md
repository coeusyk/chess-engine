# Weight-scale alternatives on the real checkpoints (#265)

2026-11-24. This follows the real-checkpoint audit (`2026-11-24-real-checkpoint-quantization-audit.md`). That note measured how far the quantized network is from the float network at today's scales, QA 127 and QB 64. This one asks what the same checkpoints would give on other integer grids, and how much room those grids leave in the int16 and int32 ranges. It changes no quantizer, sets no threshold, and retrains nothing. Everything here is a measurement on existing checkpoints.

## Method

`trainer/scripts/analyze_scale_alternatives.py` rescales the canonical network so the float function stays the same: the first layer weights and biases and QA are multiplied by one factor, the output weights and QB by another, and the output bias by both. It then runs the production `quantize()` and the existing parity and scale-utilization measurements on each combination. As a check, it compares the float scores of every combination with the baseline's. The largest difference was 0.0 cp on all 13 runs, so each combination is the same network on a finer or coarser grid, and the differences below are quantization error alone.

The grid is QA in 127, 254, 508, 1016 and QB in 64, 128, 256. Checkpoints with a known held-out set (the P5 pair, P1-G04, P3A-001) use the reproduced 4,000-position P5 held-out set. The others use the committed bench corpus, which is not their own distribution. Nothing was done to the checkpoints, and the runs used one niced core.

Command, from `trainer/`: `python -m scripts.analyze_scale_alternatives CHECKPOINT.pt --fens FENS --output OUT.json`

## Rounding error

The error against the float network falls steeply with QA and hardly at all with QB. For the P5 control final checkpoint on the 4,000 held-out positions:

| QA | QB | MAE cp | RMSE cp | Max abs cp | Accumulator max / QA | FT weights rounding to zero | Worst-case accumulator / int16 | Worst-case output sum / int32 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 127 | 64 | 14.79 | 18.72 | 87.6 | 0.347 | 0.167 | 0.018 | 0.001 |
| 127 | 128 | 14.89 | 18.80 | 85.6 | 0.347 | 0.167 | 0.018 | 0.001 |
| 127 | 256 | 14.73 | 18.63 | 85.6 | 0.347 | 0.167 | 0.018 | 0.003 |
| 254 | 64 | 7.23 | 9.23 | 37.7 | 0.347 | 0.085 | 0.038 | 0.001 |
| 254 | 128 | 7.14 | 9.09 | 38.7 | 0.347 | 0.085 | 0.038 | 0.003 |
| 254 | 256 | 7.16 | 9.13 | 39.7 | 0.347 | 0.085 | 0.038 | 0.005 |
| 508 | 64 | 4.63 | 5.96 | 23.0 | 0.347 | 0.042 | 0.076 | 0.003 |
| 508 | 128 | 4.22 | 5.43 | 22.1 | 0.347 | 0.042 | 0.076 | 0.005 |
| 508 | 256 | 4.31 | 5.56 | 22.0 | 0.347 | 0.042 | 0.076 | 0.011 |
| 1016 | 64 | 2.30 | 2.97 | 12.7 | 0.347 | 0.021 | 0.152 | 0.005 |
| 1016 | 128 | 1.96 | 2.50 | 9.8 | 0.347 | 0.021 | 0.152 | 0.011 |
| 1016 | 256 | 1.87 | 2.40 | 9.7 | 0.347 | 0.021 | 0.152 | 0.021 |

Across all 13 runs, mean absolute error in cp:

| Checkpoint | FEN set | Positions | QA 127 / QB 64 (today) | QA 254 / QB 64 | QA 508 / QB 64 | QA 1016 / QB 64 | QA 1016 / QB 128 | Accumulator max / QA today |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| P5 control, selected step 3999 | P5 held-out | 4000 | 8.18 | 4.75 | 2.46 | 1.43 | 1.18 | 0.289 |
| P5 control, final | P5 held-out | 4000 | 14.79 | 7.23 | 4.63 | 2.30 | 1.96 | 0.347 |
| P5 treatment, selected step 3999 | P5 held-out | 4000 | 9.07 | 4.05 | 2.48 | 1.92 | 1.14 | 0.319 |
| P5 treatment, final | P5 held-out | 4000 | 13.81 | 6.86 | 3.72 | 2.43 | 2.08 | 0.369 |
| P1-G04, final | P5 held-out | 4000 | 15.70 | 5.98 | 4.71 | 3.74 | 1.97 | 0.545 |
| P3A-001, final | P5 held-out | 4000 | 12.51 | 6.57 | 3.23 | 2.65 | 2.40 | 0.533 |
| P4IV-001 WDL blend, final | bench corpus | 393 | 13.72 | 7.06 | 6.93 | 6.00 | 2.37 | 0.513 |
| nets/checkpoint.pt | bench corpus | 393 | 5.26 | 3.10 | 1.70 | 1.55 | 1.06 | 0.316 |
| E-15 control, final | bench corpus | 393 | 11.01 | 7.29 | 5.86 | 3.61 | 1.72 | 0.485 |
| E-15 treatment, final | bench corpus | 393 | 10.15 | 5.78 | 3.11 | 2.82 | 2.02 | 0.479 |
| E-16 control seed 42, final | bench corpus | 393 | 9.88 | 5.32 | 3.14 | 2.32 | 2.10 | 0.483 |
| E-16 treatment seed 42, final | bench corpus | 393 | 14.17 | 7.57 | 3.85 | 3.54 | 2.74 | 0.455 |
| M-1 cp-only seed 42, final | bench corpus | 393 | 22.21 | 6.71 | 3.96 | 3.00 | 2.43 | 0.541 |

Raising QA from 127 to 1016 with QB at 64 takes the error on the P5 held-out set from 14.79 to 2.30 cp for the control final checkpoint and from 8.18 to 1.43 cp for the selected one, and QB 128 gives a further gain on most of them. P4IV is the exception with QB at 64 (6.00 cp at QA 1016, against 2.37 at QB 128), so its output layer is the limit there. The first layer carries most of the error today. At QA 127 the first layer weights have an RMS of about 2.6 grid steps, so rounding to the nearest integer is a large relative error, and about 17 percent of them round to zero for the P5 control final checkpoint.

## Headroom and clipping

- Activation clipping. The accumulators peak at 29 to 55 percent of QA at today's scale, and none reach QA on any run (the fraction at or above QA is 0.000 everywhere). The clip at QA is not engaged on these positions, so the clip range is not what limits accuracy. It is also why the rescaling above leaves the float function unchanged here.
- Accumulator range. The quantizer checks that 32 active features at the largest weight of a neuron cannot overflow int16. At QA 1016 the worst case is 0.07 to 0.15 of int16 on these checkpoints, so there is room for a larger factor than eight, roughly a factor of six on top of that before the worst case reaches the limit.
- Output layer. The largest output weight at QB 256 is under 0.6 percent of int16 (P5 control final: 183 of 32767), and the worst-case sum of activations times output weights is about 2 percent of int32 at QA 1016.
- These are static bounds from the weights and from the positions listed. They are not a proof about every position.

## What this does and does not say

It says that, on every real checkpoint measured, most of today's quantization error is the coarse first layer grid, that a larger QA with the same checkpoint would cut it by several times without touching the float function, and that the int16 and int32 ranges have room for it.

It does not say a larger QA is deployable. The engine reads qa and qb from the file header, with qa limited to the int16 range, so the format allows it, but the NNUE vector paths (the subject of #272) have not been checked against QA values above 127, for example products of activations and weights in a narrow integer type. It does not say how play changes. The accuracy figures are float against quantized agreement, not strength, and the QA and QB a retrained network would pick for itself (clipping at a larger QA may become active when training sees it) is a training question this does not answer. A threshold, a scale contract and any retraining stay undecided.

## Open points

- Check the vector inference paths for the largest product and the accumulator type at QA above 127 before treating any of these grids as usable.
- P4IV needs a larger QB than the others to benefit, which is worth understanding before QB is fixed.
- A labelled held-out set would let the same grid report label correlation and not only float agreement.
