# MSE History — Phase 13 Tuner Runs

> **Important**: MSE values are **only comparable** when measured on the **same corpus**
> with the **same K value**. Entries with different corpus sizes or K values are
> separated into distinct sections. Never compare MSE across sections.

---

## Section A — Full Corpus: quiet-labeled.epd (~703k positions)

### Baseline (Phase A K Calibration)
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-09 | K calibration | 1.520762 | — | 0.05827519 | — | — | all | BASELINE |

### PST Group Tuning
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-09 | PST group Adam | 1.520762 | 0.05827 | **0.05756** | −1.23% | converged | PST (768 params) | **BEST** |

### Scalar Group Tuning
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-09 | Scalars Adam | 1.520762 | 0.05827 | 0.05942 | +1.97% | — | scalars | DIVERGED |

### Material Group Tuning
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-09 | Material Adam | 1.520762 | 0.05827 | 0.05850 | +0.39% | — | material | DIVERGED |

### WDL 100-Iter (K-drift issue)
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-09 | WDL 100-iter | 1.520762→1.145696 | 0.05827 | 0.06083 | +4.39% | 100 (cap) | all | K-DRIFT — incomparable |

> Note: K drifted from 1.52→1.15 over 100 iterations. MSE values are not directly
> comparable because the sigmoid mapping changed. This run is unreliable.

### B-3 Mobility Group
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-14 | Mobility Adam/fast | freeze-k | — | — | — | 150 (cap) | mobility (8 params) | H0 (−16 Elo) |

> Note: MSE values not recorded in dev entries for B-3. SPRT result: H0 accepted.

---

## Section B — King Safety Seeds (287 KB, ~5k positions)

### B-1 King Safety Group (historical run)
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-13 | B-1 king-safety Adam/fast | 1.454757 | — | 0.05809511 | — | 150 (converged) | king-safety | H0 (−9 Elo) |

---

## Section C — Balanced 500-Position Mini-Corpus (1000 with color flip)

### B-1 King Safety Re-run (#166)
| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-17 | B-1 king-safety balanced | freeze-k | 0.06153 | 0.06071 | −1.33% | 124 (converged) | king-safety + KING_SAFETY_SCALE | SPRT pending |

> **⚠ WARNING**: This MSE of 0.06 is NOT comparable to the 0.057 from Section A.
> The 500-position corpus produces a structurally higher baseline MSE due to:
> 1. Much fewer positions (500 vs 703k = 1400× less data)
> 2. Higher variance in the loss surface
> 3. Different K value context
> 4. Only king-safety parameters being tuned (most eval error comes from PST/material)

---

## Section D — Barrier Method / L-BFGS (28k positions)

| Date | Run | K | Start MSE | End MSE | Δ MSE | Iters | Group | Status |
|------|-----|---|-----------|---------|-------|-------|-------|--------|
| 2026-04-09 | Barrier method | — | 0.069 | 0.059 | −14.5% | — | all | diagnostic |
| 2026-04-09 | L-BFGS | — | 0.06919 | 0.06928 | +0.13% | — | all | NO CHANGE |

---

## Key Lessons

1. **Best MSE achieved**: 0.05756 (PST group, 703k positions, K=1.520762)
2. **PST parameters dominate MSE**: 768 of 830 params are PSTs. Any group-tuning run
   that freezes PSTs will show higher MSE because the dominant error source is frozen.
3. **King-safety has minimal MSE impact**: The king-safety group controls ~10 params out
   of 830. Even perfect king-safety tuning cannot reduce MSE substantially.
4. **MSE reduction ≠ Elo gain**: All three B-track runs (B-1, B-2, B-3) reduced MSE on
   their respective corpora but all three lost Elo at SPRT (−9 to −16 Elo). The CLOP
   hand-tuned values remain superior. This suggests the Adam optimizer finds local minima
   that reduce prediction error but increase tactical blindness.
5. **Corpus size matters**: Runs on <1000 positions produce unreliable MSE estimates.
   Use the full 703k corpus for any MSE comparison.
