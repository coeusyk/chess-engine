# NNUE strength assessment: what the evidence supports, and the next steps

2026-11-27. A read-only assessment of why no NNUE checkpoint has shown competitive play against Vex Classical, after the correctness, throughput, tuner and quantization work of Phase 22. Nothing was trained, exported or played for it. The measurements below are label statistics, small float evaluations of existing checkpoints on the 393-position bench corpus, and a re-read of the existing match files. It changes no default and promotes no network.

## What the strength record actually contains

Only one network has ever played a game: E-3 (`dfffd3da-7f8f-4fc9-92dc-b3873c97fb21`, `nets/checkpoint.pt`). Everything else in the training history (P1 to P5, M-1, E-15, E-16) was judged on held-out label correlation, never on play.

| Evidence | What the files show |
|---|---|
| E-4 gauntlet 1 (2026-07-15, 10+0.1, 100 games) | NNUE 0 wins, 95 losses, 5 draws. The Cute Chess line `0 - 95 - 5` is wins, losses, draws from the NNUE side, so there is no inconsistency: the PGN has Classical winning 48 + 47 = 95 games and 5 draws. Score 2.5 of 100 gives -636.4 Elo. |
| E-4 gauntlet 2 (2026-07-18) | NNUE 1 win, 87 losses, 12 draws, -449.4 Elo. |
| E-5 SPRT (60+0.6) | 2,617 games, score 0.502, +1.7 +/- 9.8 Elo, LLR -1.32. Reported as the net being at parity. |

Two limits on the gauntlets. Both used one start position with no opening book (one distinct FEN, 9 and 6 distinct 8-token move prefixes in 100 games), so the games are far from independent and the interval of +/-224 is not a real 100-game interval. They also ran with the 0.5.8 jar before the vector launcher fix, at about 33 percent of the Classical NPS. The direction (the net loses heavily) is safe. The magnitude is not.

## The E-5 parity result is withdrawn as strength evidence

The research record calls the fast gauntlet against the slow SPRT "the central unexplained anomaly". The PGN evaluations answer it. For every move comment I took the engine's reported score and depth, split by side.

| File | Side | Mean absolute score | Median | Scores of 0.10 or less | Mean depth |
|---|---|---|---|---|---|
| E-4 gauntlet 1 | Classical | 6.01 | 4.24 | 0.8% | 9.7 |
| E-4 gauntlet 1 | NNUE | 2.03 | 0.51 | 20.3% | 7.9 |
| E-5 SPRT | NEW | 1.23 | 0.53 | 17.4% | 12.4 |
| E-5 SPRT | OLD | 1.23 | 0.53 | 17.4% | 12.4 |
| E-5 re-run | NEW / OLD | 1.19 / 1.19 | 0.51 / 0.52 | 17.7% / 17.5% | 12.3 / 12.3 |
| #213 SPRT (known Classical against Classical) | NEW / OLD | 1.24 / 1.23 | 0.56 / 0.56 | 16.9% / 16.8% | 12.5 / 12.6 |

A real NNUE side looks nothing like a Classical side (a third of the score magnitude, a fifth of its scores near zero, shallower). In the E-5 files the two sides are indistinguishable from each other and from a known Classical against Classical run. The two sides in the E-5 games behaved as the same evaluator. One hypothesis is that the NNUE side was not using the net: the engine falls back to Classical when `EvalFile` is missing or fails to load, and says so only in an `info string` that no log keeps (`UciApplication.resolveNnueNetworkForSearch`; I triggered that fallback with a bad path). That is a hypothesis, not a proven cause. The E-5 launch command is not in the artifacts, so the data cannot show which configuration actually ran. Whatever the cause, the "parity at 60+0.6" is withdrawn as reliable strength evidence, and no validated slow-time-control strength result for any NNUE net exists.

## Checkpoint inventory

All of these share one architecture (768 inputs, hidden width 256, one output, QA 127, QB 64, output scale 400) and the texel-sigmoid loss with K = 2.773456. Held-out correlations are on different benchmarks and are not comparable across the first and later rows (the original `v1` set has the sentinel outliers, `v1-clean` removes them).

| Net | Training data | Steps and notes | Held-out correlation | Quantization MAE at QA 127 | Strength evidence |
|---|---|---|---|---|---|
| E-3 (`nets/checkpoint.pt`) | Stage 1 (20k Lichess) and Stage 2 (20k quiet, Stockfish labels), 36k train | 2,000 steps, constant LR | 0.5044 (`v1`) | 5.3 cp (393 corpus) | Loses 0-95-5 and 1-87-12 |
| P1-G04 (M-1 seed 42 is bit-identical) | same 36k | 20,000 steps, cosine, per-epoch reshuffle | 0.5315 (`v1`), 0.5931 (`v1-clean`) | 15.7 cp (held-out), 22.2 cp (corpus) | none |
| P2A, P2B | Stage 1 grown to 100k | overfits; no gain | 0.5048, 0.5000 | not audited | none |
| P3A-001 | Stage 1 without 75 sentinel rows | same recipe as P1-G04 | 0.5941 (`v1-clean`), +0.0002 over P1-G04 | 12.5 cp | none |
| P4IV WDL blend | same corpus, outcome blended into the target | not promoted on cp correlation | 0.5904, -0.0037 | 13.7 cp | none |
| P5 control and treatment (auxiliary WDL head) | same corpus | selected step 3,999, final 19,999 | 0.5912 to 0.5924 cp-only | 8.2 to 14.8 cp | none |
| E-15 control and treatment | base 36k plus Stage 3 self-play | control design collapsed | cp-only +0.0058 and +0.0079 | 10.2 to 11.0 cp | none |
| E-16 (3 seed pairs) | base 36k plus Stage 3 self-play with a shared opening prefix | null | cp-only +0.0015 to +0.0041 paired | 9.9 to 14.5 cp | none |
| M-1 seeds 43, 44 | same 36k, seed only | seed variance | cp-only 0.5898 to 0.5977 | 13.6 cp | none |

There are 599 `.pt` files in the local `trainer/outputs`, mostly mid-training snapshots (about 20 per run). Selection so far has been by held-out pooled correlation. No snapshot has been chosen by play.

## Training deficiencies: confirmed, measured, and still hypotheses

Confirmed from code and recorded measurements:
- **Small data.** 36,000 training records for a network with about 197,000 parameters. Scaling Stage 1 to 100k overfit and did not help, but that tested only 5 times the data, of the weaker source. Stage 2 (Stockfish labels on quiet positions) was never scaled: `data/quiet-labeled.epd` has 725,000 positions and 20,000 were labeled (the analysis note puts labeling at roughly 4 hours per million positions).
- **Training length.** E-3 ran 2,000 steps. P1-G04's recipe (20,000 steps, cosine schedule) lifted correlation by 0.027, about 14 noise standard deviations, and was never played.
- **Score compression.** Mean absolute score on the bench corpus is 110 cp (E-3) and 244 to 258 cp (P1-G04, P3A-001, P5 control, E-15 control). In the gauntlet the NNUE side's mean absolute score was a third of Classical's. The search margins (aspiration 25, futility 150 and 300, razor 300 and 600) and the Cute Chess adjudication thresholds (resign at 400, draw at 10) are all in the Classical scale.
- **Stage 1 labels are sign-inverted for Black to move (#263).** Lichess scores are White-perspective and the trainer's contract is mover-relative. I measured it on the local shards: the correlation between the mover's material balance and the label is +0.37 for White-to-move rows and -0.30 for Black-to-move rows in `stage1-lichess`, and +0.38 and -0.34 in the 100k set. Black to move is 47.4 percent of Stage 1 (9,476 of 20,000), so about a quarter of the 40,000-position corpus carries inverted labels, and the same share of every Stage 1 held-out set. The fix is merged (#281) and a non-destructive audit and repair script exists. The real shards were never repaired and nothing was retrained on repaired data.

What that footprint does not show: on the 393-position bench corpus the float evaluation correlates 0.94 to 0.98 with the mover's material for both colours, for E-3, P1-G04, P3A-001, P5 control and E-15 control (White to move 0.935 to 0.956, Black to move 0.969 to 0.978). The networks are not sign-confused in aggregate. How much the 24 percent of corrupted rows costs in play is unmeasured.

Hypotheses, not established:
- That the search margins misfire on a compressed scale. #219 and the E-4 notes list it; no experiment separates it from raw speed.
- That the cp-correlation metric rejected useful interventions. A net trained partly on game outcomes (P4IV, P5) is penalized by a metric that is correlation with Stockfish centipawns, by construction. No play result exists for those nets.
- That the net is capacity-limited. No evidence either way.

## Which earlier conclusions still stand

| Conclusion | Status |
|---|---|
| Held-out correlation near 0.5 to 0.6 is a hard ceiling for this recipe | Not safe. Every held-out set containing Stage 1 has about 47 percent inverted rows in that part. E-16 on its own clean Stage 3 held-out set reads 0.81 to 0.87. |
| Stage 1 volume scaling is exhausted | Holds only for the inverted-label Stage 1 data. Stage 2 volume was never tried. |
| The loss and target interventions (P4II to P4IV, P5) are not promotable | Holds for the cp-correlation metric on that benchmark. Untested for play. |
| Optimization (P1-G04) was a real gain | Holds as a held-out result. Unplayed. |
| E-5 showed parity at 60+0.6 | Withdrawn, see above. |
| Quantization error is small next to the rest | Holds: 5 to 22 cp on nets that differ from Classical by hundreds of cp. |

## Screening protocol (proposed, not run)

Opponent is the current Vex Classical: the same jar with `EvalType=Classical`, so only the evaluator differs. `tools/fixed_count_match.py` (with `--new-option EvalType=NNUE --new-option EvalFile=<net>`, the same jar for both sides, `noob_3moves.epd`, a recorded seed, 5+0.05, one thread each) already does the games and validates completion with `match_validation.py`. Under the repository policy (`CLAUDE.md` section 6) Threads=1 strength runs may use the qualified WSL2 setup through `tools/sprt_linux.py`, under the controls in `2026-11-24-wsl2-operational-qualification.md` (headroom gate, recorded identities, paired openings, resource monitoring) and recorded as "WSL, qualified"; native Windows stays the default and is required for anything outside that policy. Every NNUE side is checked first for an `info string NNUE network loaded: <uuid>` line and no `Failed to load` line.

| Stage | Who | Games per net | What it decides | Budget at about 1,500 to 1,650 games an hour |
|---|---|---|---|---|
| 0 | all shortlisted | 0 | export at QA 127 / QB 64, loader and Java-equals-Python parity, UUID and hash recorded, float scale and mover-material sign check | minutes, no match |
| 1 | about 10 nets and the E-3 anchor | 60 (30 pairs) | drop a net scoring below 0.10 (about -380 Elo) | about 700 games, 25 to 45 minutes |
| 2 | at most 4 | 400 (200 pairs) | advance a net scoring 0.30 or more (about -150 Elo) | up to 1,600 games, about 1 hour |
| 3 | at most 2 | 2,000 (1,000 pairs) | a pooled pair estimate, about +/-14 Elo; a net at -30 Elo or better goes to the PRD SPRT (H0 0, H1 +10) | up to 4,000 games, about 2.5 hours |

A net at the E-3 level (score about 0.03) is distinguishable from one at 0.15 with 60 games. These are screening estimates, with pre-registered cutoffs, and not strength claims. A possible fixed-node variant would separate evaluation quality from speed, but the fixed-count launcher takes only a time control, so it needs a small change and is optional.

## Ordering against the higher-QA requantization experiment (#265)

Higher-QA requantization stays deferred. Screen first. The requantization test compares two grids of one net, and the measured effect is a rounding change of 15 cp to 2 cp on nets whose evaluations differ from Classical by hundreds of cp. On a net that plays 400 Elo below Classical the result would almost certainly be null and say nothing. Screening exports at the production grid, so it adds no confound, and it needs the same export wrapper the requantization test needs. Requantize only the best surviving net, and only if screening puts it near contention. The reason to run the requantization first would be a net whose quantization error is large relative to its other defects. The audit shows the worst case is 22 cp, which is not that.

## Roadmap

1. Screening: build the export wrapper (rescale factors 1 and 1, public `export()`), run Stage 0 and Stage 1 over the shortlist. Nothing is started by this note.
2. Fail closed on a network that cannot load. Today a requested but unloadable `EvalFile` (or an `EvalType=NNUE` with no `EvalFile`) prints an `info string` and plays Classical, which can invalidate any experiment without a visible error. Assess whether the existing NNUE execution can refuse to search, or otherwise fail the run, when a requested network cannot load, without changing the default Classical path. This is an assessment first, not a change.
3. Repair and audit the real Stage 1 shards with the existing non-destructive script, and rescore the frozen checkpoints on corrected held-out labels. This is the open part of #263.
4. Consider an early controlled comparison of original labels against corrected labels: the same recipe, seed, train and held-out split and step budget, with only the Stage 1 label perspective changed, scored by the strength screen as well as held-out correlation. P1-G04's 20,000 steps took about 3 minutes, so it is cheap. Whether the repaired data helps in play is the question to establish before any larger data investment.
5. Do not commit to labeling all 725,000 Stage 2 positions until step 4 shows what repaired data buys. A scaling curve (for example 20k, 50k, 100k) would be the first step.
6. Only after that, revisit outcome-aware targets (WDL) with play as the metric, and search-margin scaling for a compressed net.

Steps 1 to 3 are small and bounded and need no formal plan. A retraining campaign (steps 4 to 6) warrants an engineering plan once the screening result is in.
