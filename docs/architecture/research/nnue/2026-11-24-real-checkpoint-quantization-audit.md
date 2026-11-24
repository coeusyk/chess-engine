# Float32 against quantized error of the surviving real checkpoints (#265)

2026-11-24. #265 asks for the error between a checkpoint's float32 network and the quantized network the engine would load, measured on real checkpoints before anyone chooses a scale contract for retraining. The audit entry point already exists (`trainer/scripts/audit_checkpoint_quantization.py`, merged with the parity work). This note records what survives on disk, where each checkpoint came from, which FEN sets it can be measured on, the commands, and the numbers they gave. Everything below is a measurement of a real checkpoint. None of it comes from the synthetic network the issue used as a proxy.

## What survives

The checkpoints are not in the repository. They sit in the gitignored `trainer/outputs/` of the local checkout, with their training diagnostics: `phase1` (P1-G00 to P1-G04, P2A, P2B), `phase3/P3A-001`, `phase4` (P4I to P4IV), `phase5/P5-AUXHEAD-RMS` (control and treatment, 20 checkpoints each plus `final.pt`), `phase-e15`, `phase-e16` (three seeds per arm), `phase-m1` (three seeds), and `nets/checkpoint.pt` with its `.nnue`. I did not find the E-3 checkpoint the issue names, and no checkpoint is named `P5-AUXHEAD-001`. The surviving P5 family is `P5-AUXHEAD-RMS-001`. Its control final checkpoint has an output-weight L2 norm of 546.0 and an activation RMS of 4.64 on the held-out set, which matches the 546.0 and 4.6118 the issue cites from the committed diagnostics, so I take that to be the checkpoint the issue's diagnostics describe.

## Provenance of the P5 pair

`phase5/P5-AUXHEAD-RMS/provenance.json` records the experiment id, split seed 42, 4,000 held-out records, and the SHA-256 of the input files: the Stage 1 Lichess dataset (`manifest.json` `213c3d2b...`, `stage1.csv` `a9d338b2...`) and the Stage 2 quiet Stockfish WDL dataset (`manifest.json` `d68370cf...`, `shard-0.bin` `4955f2e5...`). The checkpoints record trainer commit `8edfc663` and seed 42. Each arm selected step 3999 and finished at step 19999, and both are audited below because they behave very differently.

## Which FEN sets fit

The held-out set of P5 is reproducible. I read it with the code at the commit that added the P5 script (`3579a3c`, in a separate worktree), calling `combine_and_split` on the two dataset directories with seed 42. That gives 36,000 training and 4,000 held-out positions, and the SHA-256 of the held-out FENs in order, `0a183465b4fa890be1a78e329f30b88d688aa93f76a215171be81fcf88a87134`, equals the `held_out_split_order_sha256` in the provenance file. Current develop cannot do this on purpose. It refuses the Stage 1 dataset because it is the legacy White-perspective one (the #263 guard), and the shard format gained a header on 2026-09-14, one day after that commit. Repairing or migrating the datasets was out of scope, so I used the old commit read-only.

The same split applies to P1-G04 and P3A-001, because `phase5_auxhead_rms.py` evaluates both as baselines on that held-out list. For the other checkpoints (P4IV, E-15, E-16, M-1, `nets/`) I did not find the dataset identity in the files I read, so they are measured on the committed `bench/nnue-corpus` (393 positions, `fen_corpus_sha256` `08ea3239...`) only. That set is not their held-out distribution.

The audit takes FENs and no labels, so the label-correlation fields are empty. No strength-relevant accuracy figure came out of this.

## Commands

All run report-only, so the exit status is 0 whatever the numbers are and no threshold is applied. They read a checkpoint and a FEN list, and write one JSON record. From `trainer/` with the trainer environment:

```
python -m scripts.audit_checkpoint_quantization CHECKPOINT.pt --output OUT.json
python -m scripts.audit_checkpoint_quantization CHECKPOINT.pt --fens HELD_OUT.fens --output OUT.json
```

`HELD_OUT.fens` is one FEN per line, taken from the `combine_and_split` call above at commit `3579a3c`. The runs were pinned to one niced core, because a native match was running.

## Results on the P5 held-out set (4,000 positions)

| Checkpoint | SHA-256 (12) | Positions | MAE cp | RMSE cp | Max abs cp | Pearson | Mean signed cp | Activation RMS | Output weight L2 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| P5-AUXHEAD-RMS control, selected step 3999 | `12722b595305` | 4000 | 8.18 | 10.46 | 42.2 | 0.99415 | +1.78 | 3.56 | 287 |
| P5-AUXHEAD-RMS control, final step 19999 | `fef3ee900c59` | 4000 | 14.79 | 18.72 | 87.6 | 0.99271 | +0.87 | 4.64 | 546 |
| P5-AUXHEAD-RMS treatment, selected step 3999 | `77ac99781341` | 4000 | 9.07 | 11.51 | 48.9 | 0.99501 | +5.97 | 4.69 | 258 |
| P5-AUXHEAD-RMS treatment, final step 19999 | `427b31692e16` | 4000 | 13.81 | 17.48 | 61.8 | 0.99344 | +0.45 | 6.15 | 474 |
| P1-G04, final | `479e61e73278` | 4000 | 15.70 | 19.29 | 77.6 | 0.99612 | -12.73 | 12.64 | 442 |
| P3A-001, final | `ad7566d9212d` | 4000 | 12.51 | 16.10 | 61.9 | 0.99561 | +4.78 | 12.86 | 440 |

## Results on the committed corpus (393 positions)

| Checkpoint | SHA-256 (12) | Positions | MAE cp | RMSE cp | Max abs cp | Pearson | Mean signed cp | Activation RMS | Output weight L2 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| P5 control, selected 3999 | `12722b595305` | 393 | 8.32 | 10.28 | 30.6 | 0.99900 | -1.09 | 4.13 | 287 |
| P5 control, final | `fef3ee900c59` | 393 | 14.91 | 19.10 | 61.2 | 0.99880 | +1.86 | 5.39 | 546 |
| P5 treatment, selected 3999 | `77ac99781341` | 393 | 9.78 | 12.84 | 40.6 | 0.99898 | +6.59 | 5.35 | 258 |
| P5 treatment, final | `427b31692e16` | 393 | 13.83 | 17.65 | 58.4 | 0.99895 | -1.07 | 7.08 | 474 |
| P1-G04, final | `479e61e73278` | 393 | 22.21 | 27.03 | 68.7 | 0.99875 | -19.54 | 13.03 | 442 |
| P3A-001, final | `ad7566d9212d` | 393 | 12.69 | 16.01 | 58.6 | 0.99910 | +2.67 | 13.18 | 440 |
| P4IV-001 WDL blend, final | `66a1b7edcec1` | 393 | 13.72 | 17.08 | 54.4 | 0.99904 | +4.21 | 13.18 | 439 |
| nets/checkpoint.pt (trainer commit 76db953) | `f59c02fb63c6` | 393 | 5.26 | 6.48 | 20.4 | 0.99954 | +4.10 | 6.98 | 141 |
| E-15 control, final | `96678045d6de` | 393 | 11.01 | 13.98 | 43.9 | 0.99931 | +3.31 | 11.36 | 388 |
| E-15 treatment, final | `9cb4626d8b2f` | 393 | 10.15 | 12.82 | 36.3 | 0.99947 | +3.45 | 11.75 | 392 |
| E-16 control seed 42, final | `bb7d188be30c` | 393 | 9.88 | 12.28 | 39.2 | 0.99946 | -0.62 | 12.04 | 390 |
| E-16 control seed 43, final | `f08e3ee61da0` | 393 | 11.30 | 14.54 | 48.7 | 0.99924 | -1.58 | 11.99 | 395 |
| E-16 control seed 44, final | `8e7971be1c19` | 393 | 12.34 | 15.20 | 44.4 | 0.99934 | +6.71 | 11.85 | 394 |
| E-16 treatment seed 42, final | `3ca102658bea` | 393 | 14.17 | 17.40 | 51.5 | 0.99917 | +8.41 | 11.80 | 399 |
| E-16 treatment seed 43, final | `d2e88c5c537a` | 393 | 11.27 | 13.91 | 44.3 | 0.99929 | +2.18 | 11.95 | 397 |
| E-16 treatment seed 44, final | `cf177debbe82` | 393 | 14.49 | 18.60 | 68.6 | 0.99904 | +8.87 | 11.96 | 395 |
| M-1 cp-only seed 42, final | `80c029e85d57` | 393 | 22.21 | 27.03 | 68.7 | 0.99875 | -19.54 | 13.03 | 442 |
| M-1 cp-only seed 43, final | `e493001dc527` | 393 | 13.61 | 16.73 | 56.9 | 0.99934 | +8.96 | 13.15 | 441 |
| M-1 cp-only seed 44, final | `2850b5ee196c` | 393 | 13.58 | 17.52 | 60.2 | 0.99913 | +7.92 | 12.92 | 443 |

## What the numbers say, and what they do not

- Real checkpoints are better than the synthetic proxy in the issue. On the same 393-position corpus the proxy gave MAE 26.93, RMSE 35.02 and max 102.57 cp. The P5 control final checkpoint gives 14.91, 19.10 and 61.2. The range over all 25 audited files is MAE 5.3 to 22.2 cp on that corpus.
- The error moves with how far training went. For the P5 control arm, the selected step 3999 checkpoint has MAE 8.18 cp on the held-out set and an output weight norm of 286.8. The final checkpoint has 14.79 cp and 546.0. The treatment arm shows the same direction (9.07 against 13.81 cp). The selected step is the one a selection rule would keep, so which checkpoint is audited matters.
- Almost all of the error is weight rounding. For the P5 control final checkpoint the weight-rounding MAE is 14.84 cp against a total MAE of 14.79, and the exact integer arithmetic differs from float by at most 0.998 cp, which is the integer truncation the issue described for the fixture.
- On the held-out set the Pearson correlation between float and quantized outputs is between 0.9927 and 0.9961, lower than on the corpus (0.9988 to 0.9995). The largest single difference seen is 87.6 cp (P5 control final, held-out).
- The `M-1 cp-only seed 42` and `P1-G04` rows agree to every printed digit although their file hashes differ. I did not look into why.

Not done, and why: no threshold is applied, because none has been calibrated against these numbers and #265 says not to invent one. No scale contract was chosen. Whether any of these errors matters for play needs a strength measurement, which this does not give. Float against quantized error on labelled data (correlation with the labels) needs a labelled held-out set with the audit extended to take labels, and the labelled datasets are the ones in the legacy format.
