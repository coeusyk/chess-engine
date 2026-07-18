# Network Evaluation History

Lightweight, permanent ledger of every candidate network's manifest, dataset, training
config, validation, and match results — one row per net, appended to (never rewritten
in place) as a network moves through gauntlet/SPRT/promotion. This is documentation
only, layered on top of the existing `nets/<uuid>.json` manifests; it does not change
the manifest schema or registry structure (Trainer Architecture Invariant 6,
"provenance chain").

| Field | Value |
|---|---|
| **Network UUID** | `dfffd3da-7f8f-4fc9-92dc-b3873c97fb21` |
| **Manifest** | `nets/dfffd3da-7f8f-4fc9-92dc-b3873c97fb21.json` |
| **Issue** | E-3 (#203) |
| **Dataset** | `stage1-lichess` (20,000, public) + `stage2-quiet-sf` (20,000, Stockfish-18-labeled), combined/shuffled seed 42, split 90/10 → 36,000 train / 4,000 held-out |
| **Training steps** | 2000 |
| **K** | 2.773456 (recalibrated against live `EvalParams` via `engine-tuner --freeze-params`, commit `47ff6cb`) |
| **Held-out loss** | 0.08225942403078079 |
| **Label correlation** | 0.5043603181838989 (n=4000) |
| **Classical eval error** | mean_absolute_difference_cp = 735.5965576171875 (n=393, vs. `bench/nnue-corpus/classical-golden-evals.csv`) |
| **Gauntlet result (E-4, #204)** | Vex-NNUE vs Vex-Classical: 0 - 95 - 5 (100 games, TC=10+0.1); Elo diff -636.4 ± 224.0, LOS 0.0%, DrawRatio 5.0% |
| **SPRT result (E-5, #205)** | **Inconclusive** — manually stopped 2026-07-17 after 2617 games (~2d10h at `-Concurrency 2`): Score 0.502, Elo diff 1.7±9.8, LLR -1.32 vs bounds [-2.94, +2.94] (never crossed). Re-run required — see `nets/dfffd3da-7f8f-4fc9-92dc-b3873c97fb21-release-report.md` for full detail and the re-run command (higher concurrency recommended per `tools/benchmark_concurrency.ps1`). |
| **Promotion status** | **Candidate** — undertrained (2000 steps, 735.6cp mean classical-eval error) and lost the E-4 gauntlet decisively; expected outcome for this stage of Track A/B, not a defect (see `tools/nnue-gauntlet-e4.md`). Not yet eligible for a promotion decision — the SPRT strength gate (§1 gate 3) recorded an inconclusive result, not a decision. |

---

_Append new networks below this line as future training cycles produce them. Each
entry mirrors the table shape above; do not restructure the table itself without a
corresponding ADR (Trainer Architecture Invariant 6)._
