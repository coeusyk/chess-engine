# Vex NNUE Trainer

Self-written PyTorch training pipeline producing real trained `.nnue` weight files
for the Vex chess engine's NNUE evaluator. See:

- `docs/NNUE_PRD.md` §3 — original requirements
- `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` — full architecture reference
  (module boundaries, Canonical Network IR, provenance, invariants, Contract Matrix)
- `docs/adr/ADR-006-self-written-pytorch-trainer.md`,
  `docs/adr/ADR-007-staged-training-data.md`,
  `docs/adr/ADR-010-trainer-repository-location.md`

The Java engine has zero dependency on this package. The `.nnue` file is the only
contract between the two (architecture doc §1, §15 Invariant 8).

## Status

Scaffolding only (PR D-1, issue #192). No functional pipeline yet — see the
architecture doc's §2 module table and this repo's Phase D roadmap
(`docs/superpowers/plans/2026-07-13-nnue-phase-d-roadmap.md`) for what's next.

## Tooling

Dependency and virtual environment management uses [`uv`](https://docs.astral.sh/uv/),
consistent with this repo's other Python tooling (graphify's own managed venv).
`uv.lock` is committed for reproducibility (architecture doc §10, §15 Invariant 7).
