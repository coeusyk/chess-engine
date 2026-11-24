# Phase 30. NNUE quantization audit

### [2026-11-24] Real-checkpoint float against quantized audit (#265)

**Built:** A write-up of the first real-checkpoint measurements for #265: which checkpoints survive in the gitignored `trainer/outputs/`, their provenance, which FEN sets they can be measured on, the audit commands and 25 report-only audits. No code changed.

**Decisions Made:** The P5 held-out set was reproduced read-only with the trainer code at the commit that added the P5 script, because current develop refuses the legacy Stage 1 dataset (the #263 guard) and the shard format changed a day after that commit. Migrating the datasets was out of scope. The audits ran report-only, so no threshold was applied. Checkpoints whose dataset identity I could not find were measured on the committed corpus only.

**Broke / Fixed:** A helper script I first wrote carried an absolute interpreter path and was blocked; it now takes the interpreter from the environment.

**Measurements:** On the 393-position corpus the real P5 control final checkpoint has MAE 14.91 cp, RMSE 19.10 and max 61.2, against the issue's synthetic proxy at 26.93, 35.02 and 102.57. On the 4,000-position P5 held-out set the MAE runs from 8.18 cp (control, selected step 3999) to 15.70 cp (P1-G04). Error rises from the selected step to the final checkpoint, and nearly all of it is weight rounding.

### [2026-11-24] Weight-scale alternatives on the real checkpoints (#265)

**Built:** `trainer/scripts/analyze_scale_alternatives.py` and a write-up. The script rescales a canonical network so its float function is unchanged (first layer and QA by one factor, output layer and QB by another, output bias by both), runs the production `quantize()` and the existing parity and scale-utilization measurements for QA 127 to 1016 and QB 64 to 256, and checks that the float scores match the baseline's.

**Decisions Made:** Report only. No quantizer change, no threshold, no retraining. The rescaling is function-preserving, so the differences are quantization error alone. Checkpoints with a known split use the reproduced P5 held-out set and the rest use the committed bench corpus.

**Broke / Fixed:** I first read the int16 headroom ratio in the audit as too small. The canonical weights are already in integer units, so it is correct and there is no audit bug.

**Measurements:** On all 13 runs most of today's error is the first-layer grid. P5 control final on 4,000 held-out positions goes from 14.79 cp at QA 127 to 2.30 cp at QA 1016 (QB 64) and 1.96 cp with QB 128. Accumulators peak at 29 to 55 percent of QA and none reach it, and the worst-case int16 and int32 bounds stay below 0.15 and 0.02 at QA 1016. The vector inference paths have not been checked against QA above 127, so this is not yet a usable scale.
