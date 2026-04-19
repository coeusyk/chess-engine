# Coverage Audit Analysis & Next Steps
## Phase 13 Tuner Overhaul — Post-Audit Action Plan
**Date:** 2026-04-13  
**Branch:** `phase/13-tuner-overhaul`  
**Audit corpus:** `tools/quiet-labeled.epd` (703,754 positions, in-check + 32-piece filtered)  
**Audit run:** 100-position sample (smoke test only — full corpus audit pending on PC)

---

## 1. What the audit is actually telling you

The audit conclusion is correct and the threshold is sound:

> *773/826 STARVED reflects genuine corpus sparsity in a quiet-start self-play corpus, not a broken threshold.*

The TEMPO Fisher value is ~1.76e-7 (highest non-material scalar). The STARVED cutoff sits well below it. Every parameter that shows `STARVED` on a 703k-position quiet corpus is starved because the **feature is genuinely rare** in the corpus, not because the corpus is small. The corpus enrichment path (seed EPDs) is the only correct response.

However, the audit also reveals something more useful: **not all STARVED params are equally starved**. There is a 6-orders-of-magnitude spread in Fisher values across STARVED scalars. That lets you prioritise exactly which seeds to build and which groups to tune first.

---

## 2. The two tiers of STARVED scalars

### Tier A — "Barely STARVED": high activation, acceptable Fisher

These params appear frequently in the corpus but still fall below TEMPO/10 because the **outcome signal per activation is weak** (the feature is present but doesn't discriminate win/loss well in quiet positions). Seed EPDs will help but are not critical. A larger WDL corpus (game-outcome labels) would fix these naturally.

| Param | Fisher | Activation count | Notes |
|---|---|---|---|
| `DOUBLED_EG` | 1.57e-8 | 161,655 | High activation — outcome signal is just weak |
| `KNIGHTOUTPOST_EG` | 1.47e-8 | 174,611 | Widely seen, weakly discriminating in quiet positions |
| `ROOKOPENFILE_MG` | 1.40e-8 | 258,990 | Pinned at max=80; PARAMMAX audit needed (see §4) |
| `PASSED_EG_2/3/4/5` | 1.1–2.2e-8 | 88k–170k | Broadly covered — gradient is diffuse across many PST interactions |
| `OPENFILE_PENALTY` | 1.07e-8 | 265,112 | High activation; king-file openness frequently seen but not always decisive |

**Action:** These do not need dedicated seeds. They will gain gradient naturally once the PARAMMAX bounds are audited (so they aren't silently capped) and once the WDL pipeline uses real game-outcome labels.

### Tier B — "Deeply STARVED": low activation AND low Fisher

These params have low activation counts because the specific **structural configuration** they measure appears rarely in the 703k quiet-start corpus. These are the ones that need targeted seed EPDs.

| Param | Fisher | Activation count | Gap vs TEMPO | Seed file exists? |
|---|---|---|---|---|
| `QUEEN_MG` | 4.66e-10 | 82,664 | ~380× | ❌ No |
| `QUEEN_EG` | 9.94e-10 | 82,623 | ~180× | ❌ No |
| `ROOK_MG` | 4.39e-9 | 175,766 | ~40× | ❌ No |
| `ROOK_EG` | 9.97e-9 | 175,728 | ~18× | ❌ No |
| `ROOK7TH_MG` | 2.40e-9 | 91,370 | ~73× | ✅ `rook_7th_seeds.epd` |
| `ROOK7TH_EG` | 8.80e-9 | 91,282 | ~20× | ✅ `rook_7th_seeds.epd` |
| `ROOKBEHINDPASSER_MG` | 1.65e-9 | 98,208 | ~107× | ✅ `rook_behind_passer_seeds.epd` |
| `ROOKBEHINDPASSER_EG` | 6.68e-9 | 92,150 | ~26× | ✅ `rook_behind_passer_seeds.epd` |
| `BISHOPPAIR_MG` | 1.24e-8 | 87,422 | ~14× | ✅ `bishop_pair_seeds.epd` |
| `BISHOPPAIR_EG` | 3.96e-9 | 87,405 | ~44× | ✅ `bishop_pair_seeds.epd` |
| `PASSED_MG_1–6` | 2.3–8.0e-9 | 39k–382k | varies | ✅ `passed_pawn_seeds.epd` |
| `PASSED_EG_1,2,6` | 5.1–9.7e-9 | 39k–89k | ~18–34× | ✅ `passed_pawn_seeds.epd` |

**Key finding — material values for QUEEN and ROOK are STARVED.** Queens are present in only ~12% of training positions (82k / 703k). This means any PST changes the tuner makes to queen PSTs are happening with almost no material calibration signal to anchor them. This is a likely root cause of QUEEN and ROOK PST drift in previous tuning runs.

---

## 3. Immediate actions: what to build and in what order

### 3.1 PARAMMAX bound audit (do this first, before any re-tuning)

From the audit CSV, these parameters are at or very near their current max bound:

| Param | Current value | Current max | Action |
|---|---|---|---|
| `ROOKOPENFILE_MG` (idx 817) | 50 | 80 | Has been at cap in previous runs — raise max to 100 in `EvalParams.buildMax` |
| `KNIGHTOUTPOST_MG` (idx 821) | 40 | 60 | Near cap — raise max to 80 |
| `KNIGHTOUTPOST_EG` (idx 822) | 30 | 50 | At mid-range — fine for now |
| `OPENFILE_PENALTY` (idx 798) | 45 | 80 | ~56% of cap — watch in next run |

For `ROOKOPENFILE_MG` specifically: the value has been 50 across multiple tuning runs and is always pushing against the bound, suggesting the true optimum is above 50.

### 3.2 Seed EPD status and gaps

Current seed files in `tools/seeds/`:

| File | Target params | Status |
|---|---|---|
| `attacker_weight_seeds.epd` | ATK_KNIGHT/B/R/Q | ✅ 51 positions — adequate |
| `bishop_pair_seeds.epd` | BISHOPPAIR_MG/EG | ✅ 41 positions — adequate |
| `backward_pawn_seeds.epd` | BACKWARDPAWN_MG/EG | ✅ ~305k bytes — good |
| `connected_pawn_seeds.epd` | CONNECTEDPAWN_MG/EG | ✅ ~294k bytes — good |
| `king_safety_seeds.epd` | ATK_*, SHIELDRANK* | ✅ ~282k bytes — good |
| `knight_outpost_seeds.epd` | KNIGHTOUTPOST_MG/EG | ✅ ~296k bytes — good |
| `passed_pawn_seeds.epd` | PASSED_MG/EG 1–6 | ✅ ~260k bytes — good |
| `rook_7th_seeds.epd` | ROOK7TH_MG/EG | ✅ ~258k bytes — good |
| `rook_behind_passer_seeds.epd` | ROOKBEHINDPASSER_MG/EG | ✅ ~308k bytes — good |
| `rook_open_file_seeds.epd` | ROOKOPENFILE_MG/EG | ✅ ~271k bytes — good |
| `rook_semi_open_seeds.epd` | ROOKSEMIOPEN_MG/EG | ✅ ~298k bytes — good |

**Missing seed files (build these):**
- **Queen endgame seeds** — positions with 1–2 queens per side, past pawn exchanges, where queen material calibration is the primary signal. Highest-priority missing file given QUEEN_MG/EG Fisher of ~5e-10. Target: 40–60 positions.
- **Rook endgame seeds** — positions with rooks only (no queens, few pawns) where ROOK_MG/EG material signal is isolated. Target: 40–60 positions.
- **Open-file king seeds** (optional) — positions where the king is on a genuinely open file in the middlegame, to give OPENFILE_PENALTY more signal. Can defer to WDL corpus.

### 3.3 Full corpus audit on PC (EXP-C1)

The audit that produced `coverage-audit-report.csv` was run on a **100-position sample**, not the full 703k corpus. Run this on the PC before any further tuning:

```powershell
java -Xmx8g -jar engine-tuner-shaded.jar tools/quiet-labeled.epd 703755 1 --coverage-audit > tools/coverage-audit-full.csv
```

The full-corpus audit may show that some params currently STARVED on the sample come alive at scale (PASSED_EG_3/4/5 already show `ok` in this audit). This gates which seed sets you actually need to build vs which ones already have enough signal.

---

## 4. What the audit does NOT tell you (important gaps)

### 4.1 HANGING_PENALTY is LOCKED with value 0.0

The audit shows `HANGING_PENALTY` (idx 811) as LOCKED with `value=0.0`, `min=0.0`, `max=0.0`. This means the bounds in `EvalParams.buildMin`/`buildMax` are set to zero — it's completely excluded from optimization.

This is intentional per the Phase 13 `hangingPenalty` suppression fix (the conditional that fires only when king ≤ 1 safe square). But verify: the LOCKED status should reflect a deliberate architectural decision, not an accidental bound collapse from a previous `apply-tuned-params.ps1` run where HANGING_PENALTY was not in the output section.

### 4.2 PST coverage: the 773-STARVED count is almost entirely PST cells

773 STARVED entries are dominated by PST cells. This is expected and correct — individual PST cells for opening/endgame configurations are genuinely rare in any corpus. **Do not try to fix PST coverage with seeds.** PST cells get their gradient from the aggregate of all positions where that piece is on that square.

The correct fix for PST coverage is more positions in the corpus (which you already have at 703k) and running PST tuning as a **separate group with the full 703k corpus and no seeds mixed in**, so the PST gradient isn't diluted or corrupted by skewed seed positions.

### 4.3 ATK_QUEEN min bound anomaly

The audit shows `ATK_QUEEN` (idx 803) with `min=3.0`, `max=80.0`, current `value=3.0`. The value is sitting **exactly at its lower bound**. This was the parameter that went negative in earlier runs (the wrong-sign bug you fixed). The floor of 3 is correct to prevent sign collapse, but verify that the Adam optimizer isn't being held at this bound by momentum from earlier negative-direction gradient steps. If so, reset `ATK_QUEEN` to the v0.5.4 baseline value (6) and re-run.

---

## 5. Tuning group priority given audit results

### Priority 1 — Unblock before anything else
1. Run full corpus coverage audit on PC (`--coverage-audit` on all 703k positions)
2. PARAMMAX bound audit: raise `ROOKOPENFILE_MG` max → 100, `KNIGHTOUTPOST_MG` max → 80
3. Verify `HANGING_PENALTY` LOCKED status is intentional

### Priority 2 — Corpus enrichment
4. Build `queen_eg_seeds.epd` (40–60 positions: queen + pawns, no rooks)
5. Build `rook_eg_seeds.epd` (40–60 positions: rooks only, mid-late endgame)

### Priority 3 — Tuning runs (with existing seeds)
6. **Pawn structure group** (PASSED_MG/EG 1–6, DOUBLED, ISOLATED, CONNECTEDPAWN, BACKWARDPAWN): best Fisher/seed coverage combination. Run with `--freeze-psts` and existing `passed_pawn_seeds.epd` + `backward_pawn_seeds.epd` + `connected_pawn_seeds.epd` mixed at 10:1 ratio (1 seed position per 10 corpus positions).
7. **Rook bonus group** (ROOKOPENFILE, ROOKSEMIOPEN, ROOKBEHINDPASSER, ROOK7TH): all seeds exist. Run with PSTs and material both frozen.
8. **King safety group** (BISHOPPAIR, SHIELDRANK, OPENFILE_PENALTY, ATK_*): seeds exist. Run after confirming ATK_QUEEN is not floor-pinned.

### Priority 4 — Post-current-tuning
9. **Queen/rook material**: only after queen endgame and rook endgame seeds are built AND full corpus audit shows QUEEN_MG/EG Fisher > TEMPO/10.

---

## 6. One-line summary per STARVED non-PST param

| Param | Fisher | Root cause | Fix |
|---|---|---|---|
| `QUEEN_MG/EG` | ~5–10e-10 | Queens absent in 88% of positions | Build queen EG seeds |
| `ROOK_MG/EG` | ~4–10e-9 | Rooks in endgames underrepresented | Build rook EG seeds |
| `ROOK7TH_MG/EG` | ~2–9e-9 | Rook-on-7th rare in quiet corpus | Seeds exist ✓ — use in next run |
| `ROOKBEHINDPASSER_MG/EG` | ~2–7e-9 | Passer + rook combination rare | Seeds exist ✓ — use in next run |
| `BISHOPPAIR_MG/EG` | ~4–12e-9 | Bishop pair positions uncommon | Seeds exist ✓ — use in next run |
| `ROOKOPENFILE_MG` | 1.4e-8 | PARAMMAX cap silencing gradient | Raise max to 100 first |
| `PASSED_MG_1–6` | 2–8e-9 | Early-rank passers rare in quiet games | Seeds exist ✓ — use in next run |
| `PASSED_EG_1,2,6` | 5–10e-9 | Extreme-rank passers rare | Seeds exist ✓ — use in next run |
| `DOUBLED_EG` | 1.57e-8 | Outcome signal weak in quiet positions | WDL corpus (game labels) will fix |
| `KNIGHTOUTPOST_EG` | 1.47e-8 | EG outposts rare (pieces traded off) | Seeds exist ✓ — verify mixing ratio |
| `OPENFILE_PENALTY` | 1.07e-8 | King on open file rare in quiet start | Build dedicated seeds OR accept via WDL |
| `ATK_QUEEN` | 2.24e-7 | **ok** — pinned at min=3 floor | Reset to 6 if floor-stuck |

---

*Generated from `coverage-audit-report.csv` (100-position sample — full PC audit is the required next step)*
