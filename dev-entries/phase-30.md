# Phase 30. NNUE quantization audit

### [2026-11-24] Real-checkpoint float against quantized audit (#265)

**Built:** A write-up of the first real-checkpoint measurements for #265: which checkpoints survive in the gitignored `trainer/outputs/`, their provenance, which FEN sets they can be measured on, the audit commands and 25 report-only audits. No code changed.

**Decisions Made:** The P5 held-out set was reproduced read-only with the trainer code at the commit that added the P5 script, because current develop refuses the legacy Stage 1 dataset (the #263 guard) and the shard format changed a day after that commit. Migrating the datasets was out of scope. The audits ran report-only, so no threshold was applied. Checkpoints whose dataset identity I could not find were measured on the committed corpus only.

**Broke / Fixed:** A helper script I first wrote carried an absolute interpreter path and was blocked; it now takes the interpreter from the environment.

**Measurements:** On the 393-position corpus the real P5 control final checkpoint has MAE 14.91 cp, RMSE 19.10 and max 61.2, against the issue's synthetic proxy at 26.93, 35.02 and 102.57. On the 4,000-position P5 held-out set the MAE runs from 8.18 cp (control, selected step 3999) to 15.70 cp (P1-G04). Error rises from the selected step to the final checkpoint, and nearly all of it is weight rounding.
