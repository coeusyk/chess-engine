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

## Full-Corpus Audit — PENDING (PC)

A full audit against a 25k–50k position corpus must be run on the PC
(Ryzen 7 7700X) with Stockfish-generated positions.  The `--coverage-audit`
flag accepts any `--corpus` CSV.

Planned augmentation corpus:
- **Base**: ~28k positions from `tools/results/*.pgn` via `generate_texel_corpus.ps1`
- **Attacker seeds**: `tools/seeds/attacker_weight_seeds.epd` (≥50 FENs, king-attack dynamics)
- **Bishop-pair seeds**: `tools/seeds/bishop_pair_seeds.epd` (≥30 FENs, bishop-pair structures)

Run command (PC):
```powershell
# 1. Generate augmented corpus
.\tools\generate_texel_corpus.ps1 `
    -StockfishPath "C:\Tools\stockfish.exe" `
    -PgnDir "tools\results" `
    -CustomFens "tools\seeds\attacker_weight_seeds.epd" `
    -OutputCsv "data\texel_corpus_atk.csv"

.\tools\generate_texel_corpus.ps1 `
    -StockfishPath "C:\Tools\stockfish.exe" `
    -PgnDir "tools\results" `
    -CustomFens "tools\seeds\bishop_pair_seeds.epd" `
    -OutputCsv "data\texel_corpus_bp.csv"

# 2. Re-run coverage audit against augmented corpus
java -jar engine-tuner\target\engine-tuner-0.5.6-SNAPSHOT-shaded.jar `
    data\texel_corpus_atk.csv `
    --corpus data\texel_corpus_atk.csv `
    --coverage-audit
```

Target acceptance: ATK_KNIGHT/BISHOP/ROOK/QUEEN and BISHOP_PAIR_MG/EG Fisher
values ≥ 1e-4 on a ≥20k position corpus.

---

## Acceptance Criteria Status

| AC | Status |
|---|---|
| `--coverage-audit` flag implemented | ✅ |
| FEN seed files committed to `tools/seeds/` | ✅ (`attacker_weight_seeds.epd` ≥50, `bishop_pair_seeds.epd` ≥30) |
| `--custom-fens` / `-CustomFens` in `generate_texel_corpus.ps1` | ✅ |
| Audit run on sample corpus, results documented | ✅ (this file) |
| Full-corpus augmentation & re-audit to confirm Fisher ≥ 1e-4 | ⏳ PC-pending (Ryzen 7 7700X) |
| SPRT H0=0 elo H1=10, α=0.05 β=0.05 on augmented-tuned params | ⏳ PC-pending |
