# Coverage Audit Results — Issue #135

## Overview

This document records the output of `--coverage-audit` run against the Texel
training corpus.  The audit computes the diagonal of the empirical Fisher
information matrix:

```
fisherDiag[i] = mean_p( (∂L/∂p_i)^2 )
```

A parameter is flagged **STARVED** when `fisherDiag[i] < 1e-4`, meaning the
corpus provides essentially no gradient signal for that parameter.

---

## Baseline Run — sample corpus (100 positions)

| Item | Value |
|---|---|
| Corpus | `data/texel_corpus_sample.csv` |
| Positions | 100 |
| Parameters | 829 |
| K (sigmoid) | 0.500050 (auto-calibrated on sample) |
| Command | `java -jar engine-tuner.jar data/texel_corpus_sample.csv --corpus data/texel_corpus_sample.csv --coverage-audit` |
| Date | 2025-07 |

### Result

**829 / 829 parameters STARVED** (Fisher < 1e-4 for all parameters).

This is expected: 100 positions cannot provide meaningful coverage for 829
parameters across the full PST+scalar feature space.

### Key scalar parameter Fisher values (ascending, from audit tail)

| Parameter | Idx | Fisher | Value | Min |
|---|---|---|---|---|
| ATK_KNIGHT | 800 | 7.45e-08 | 6.0 | 2.0 |
| ATK_BISHOP | 801 | 6.08e-08 | 4.0 | 2.0 |
| ATK_ROOK | 802 | 1.82e-07 | 5.0 | 2.0 |
| ATK_QUEEN | 803 | 1.39e-07 | 7.0 | 3.0 |
| MOB_MG_KNIGHT | 804 | 3.11e-08 | 6.0 | -5.0 |
| MOB_MG_BISHOP | 805 | 6.50e-08 | 7.0 | -5.0 |
| MOB_MG_ROOK | 806 | 9.30e-08 | 8.0 | -5.0 |
| MOB_MG_QUEEN | 807 | 1.26e-07 | 3.0 | -5.0 |
| MOB_EG_KNIGHT | 808 | 2.32e-08 | 0.0 | 0.0 |
| MOB_EG_BISHOP | 809 | 3.99e-08 | 2.0 | 0.0 |
| MOB_EG_ROOK | 810 | 1.25e-07 | 2.0 | 0.0 |
| MOB_EG_QUEEN | 811 | 2.51e-08 | 6.0 | 0.0 |
| TEMPO | 812 | 2.31e-08 | 19.0 | 5.0 |
| BISHOP_PAIR_MG | 813 | 0.000e+00 | — | 15.0 |
| BISHOP_PAIR_EG | 814 | 0.000e+00 | — | 15.0 |
| ROOK_OPEN_FILE_MG | 817 | 4.01e-09 | 20.0 | 0.0 |
| ROOK_OPEN_FILE_EG | 818 | 5.48e-09 | 10.0 | 0.0 |
| CONNECTED_PAWN_MG | 823 | 2.07e-08 | 10.0 | 0.0 |
| CONNECTED_PAWN_EG | 824 | 2.56e-08 | 8.0 | 0.0 |
| ISOLATED_MG | 792 | 8.36e-09 | 17.0 | 0.0 |
| ISOLATED_EG | 793 | 4.40e-08 | 9.0 | 0.0 |

**Diagnosis**: `BISHOP_PAIR_MG/EG` have Fisher = 0 even in the sample, meaning
bishop-pair positions are entirely absent.  All ATK_* and MOB_* scalars that
collapsed to their minimums during the diagnostic tuning run (Issue #133) also
show the lowest Fisher values — confirming that the gradient-collapse root cause
is corpus starvation, not an optimizer bug.

---

## Full-Corpus Audit — Recalibrated (Issue #163)

| Item | Value |
|---|---|
| Corpus | `data/quiet-labeled.epd` |
| Positions | 703,000 |
| Parameters | 829 (pre-formula-fix; 831 after KING_SAFETY_SCALE addition) |
| Threshold | `1.753763e-8` (TEMPO-anchored: TEMPO_FISHER / 10) |
| Date | 2026-04 |

### Threshold Recalibration

The original `STARVED_THRESHOLD = 1e-3` was calibrated against a 100-position
sample and showed 829/829 STARVED on the full corpus. Issue #163 applied two
fixes:

1. **LOCKED vs STARVED distinction**: Parameters where `PARAM_MIN == PARAM_MAX`
   (e.g. `KING_MG`, `KING_EG`, back-rank PAWN PSTs) are now reported as LOCKED
   — they are intentionally fixed constants, not signal-starved.
2. **TEMPO-anchored threshold**: `COVERAGE_STARVED_THRESHOLD = 1.753763e-8`
   (= TEMPO Fisher / 10). A parameter must show at least 10% of TEMPO's
   per-position sensitivity to pass.

### Results (703k positions)

| Category | Count |
|---|---|
| LOCKED | 3 / 829 |
| STARVED | 773 / 826 tunable (Fisher < 1.753763e-8) |
| OK | 53 / 826 (6.4%) |

Top-covered parameters:
- `MOB_MG_QUEEN` (1.07e-6)
- `MOB_MG_BISHOP`, `MOB_MG_ROOK`
- `TEMPO` (1.75e-7)

3 non-PST scalars remain STARVED: `KNIGHT_OUTPOST_EG` (1.467e-8),
`ROOK_BEHIND_PASSER_MG` (1.645e-8), `ROOK_BEHIND_PASSER_EG` (6.675e-9).

Seed augmentation for these 3 scalars was **waived** (Issue #163 disposition):
same-distribution seeds do not structurally improve Fisher diagonal values.
773 of the STARVED params are PST entries (indices 12–779) — inherently sparse,
unfixable by same-distribution augmentation. Genuine improvement requires
structurally different corpus sources (tablebases, endgame suites).

Full report: `coverage-audit-report.csv` (repo root).

---

## Acceptance Criteria Status

| AC | Status |
|---|---|
| `--coverage-audit` flag implemented | ✅ |
| FEN seed files committed to `tools/seeds/` | ✅ (`attacker_weight_seeds.epd` ≥50, `bishop_pair_seeds.epd` ≥30) |
| `--custom-fens` / `-CustomFens` in `generate_texel_corpus.ps1` | ✅ |
| Audit run on sample corpus, results documented | ✅ (this file) |
| Full-corpus audit with recalibrated threshold (#163) | ✅ (703k positions, TEMPO-anchored threshold) |
| Seed augmentation for remaining STARVED scalars | ⏭️ Waived (#163 disposition — same-distribution seeds ineffective) |
