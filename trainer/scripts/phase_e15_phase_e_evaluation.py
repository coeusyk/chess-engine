"""E-15 Phase E: offline evaluation (issue #223). Executes
`DR-E15-stage3-first-retraining-preregistration.md` Phase E only -- section 11's metrics
(the existing, unmodified Measurement Model), section 12's A/B/C interpretation. No training,
no SPRT, no parameter change of any kind. Read-only over Phase B/C/D's already-frozen artifacts.

Evaluates, for both control and treatment arms, both the selected (step 7999) and final
(step 19999) checkpoints (`DR-E15-phase-d-training-report.md` section 7's frozen paths/hashes,
re-verified against disk below rather than assumed):

- v1-clean pooled correlation (screening)
- cp-only correlation (primary/decisive, majority-population rule)
- mate-only correlation (mechanism validation only)
- RMSE / bias / compression (secondary regression guards), overall + cp-labeled + mate-labeled
- Stage-3 held-out correlation, per arm's own held-out split (exploratory only, section 7's
  secondary held-out set) -- plus each arm's train/held-out FEN-overlap fraction, since a
  self-play control that collapsed to one repeated trajectory (Phase B/C section 12) needs this
  computed, not assumed, before its Stage-3 held-out reading is interpreted.
- Historical P3A-001 baseline, same v1/v1-clean battery, reported as secondary context only
  (section 12: never the deciding comparison).

No target-definition rubric duality is needed here (unlike P4III) -- E-15 does not change
`target_cp()` at all, so every model in this script is graded under the one existing rubric.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
from pathlib import Path
from typing import List

import torch

from trainer.contracts import PositionRecord
from trainer.dataset.selfplay_provider import SelfPlayProvider
from trainer.dataset.split import split_by_game
from trainer.model.batching import encode_batch
from trainer.model.network import NnueNet
from trainer.model.train import target_cp
from trainer.validation.validator import calibration_report, evaluate_held_out, _pearson_correlation
from scripts.train_candidate_net import combine_and_split

HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, K = 256, 127, 64, 400, 2.773456

STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_V1_DIR = Path("outputs/datasets/stage2-quiet-sf-v1")
SPLIT_SEED = 42
SENTINEL_CP_VALUES = {-9605, 9605, 20000}

STAGE3_SPLIT_SEED = 20261502
STAGE3_HELD_OUT_FRACTION = 0.10

P3A_001_BASELINE_CHECKPOINT = Path("outputs/phase3/P3A-001/checkpoints/step-016999.pt")

ARMS = {
    "control": {
        "selected": Path("outputs/phase-e15/control/checkpoints/step-007999.pt"),
        "final": Path("outputs/phase-e15/control/final.pt"),
        "ingested_dir": Path("outputs/selfplay-e15/control/ingested"),
        "identifier": "stage3-e15-control-001",
    },
    "treatment": {
        "selected": Path("outputs/phase-e15/treatment/checkpoints/step-007999.pt"),
        "final": Path("outputs/phase-e15/treatment/final.pt"),
        "ingested_dir": Path("outputs/selfplay-e15/treatment/ingested"),
        "identifier": "stage3-e15-treatment-001",
    },
}

# Frozen, pinned in DR-E15-phase-d-training-report.md section 7 -- re-verified, not trusted blindly.
EXPECTED_SHA256 = {
    "control/selected": "e4580b579fe5287a945c0db02e8e920209f3df2f35907e7ff747e8beab49e2e8",
    "control/final": "96678045d6de2fc1133a06cfc74b27a309a8664fb06ef983230340a96e6e25c0",
    "treatment/selected": "1a7d62046b0a1f57a7aa43f017a1252c63e47f4140e449f4e89b058a45e7d606",
    "treatment/final": "9cb4626d8b2f28898cd3ff04c05f6de3bd32327a7ff625328f1b6bc1518db460",
}


def _is_sentinel(record: PositionRecord) -> bool:
    return record.label.eval_cp in SENTINEL_CP_VALUES


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def _load_model(checkpoint_path: Path) -> NnueNet:
    checkpoint = torch.load(checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()
    return model


def _predictions(model: NnueNet, records: List[PositionRecord]):
    batch = encode_batch(records)
    target_cps = torch.tensor([target_cp(r.label) for r in records], dtype=torch.float32)
    with torch.no_grad():
        predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
    return predicted_cp, target_cps


def _eval_cell(model: NnueNet, records: List[PositionRecord]) -> dict:
    if not records:
        return {"n": 0}
    cp_only = [r for r in records if r.label.eval_mate is None]
    mate_only = [r for r in records if r.label.eval_mate is not None]
    held_out_eval = evaluate_held_out(model, records, K)
    cal = calibration_report(model, records)
    result = {
        "n": len(records),
        "pooled_correlation": held_out_eval.label_correlation,
        "held_out_loss": held_out_eval.held_out_loss,
        "cp_only_correlation": (
            _pearson_correlation(*_predictions(model, cp_only)) if cp_only else None
        ),
        "mate_only_correlation": (
            _pearson_correlation(*_predictions(model, mate_only)) if mate_only else None
        ),
        "cp_only_n": len(cp_only),
        "mate_only_n": len(mate_only),
        "overall": dataclasses.asdict(cal.overall),
        "cp_labeled": dataclasses.asdict(cal.cp_labeled),
        "mate_labeled": dataclasses.asdict(cal.mate_labeled),
    }
    return result


def _fen_overlap(train_records: List[PositionRecord], held_out_records: List[PositionRecord]) -> dict:
    train_fens = {r.fen for r in train_records}
    held_out_fens = {r.fen for r in held_out_records}
    shared = train_fens & held_out_fens
    return {
        "train_unique_fens": len(train_fens),
        "held_out_unique_fens": len(held_out_fens),
        "shared_fens": len(shared),
        "held_out_overlap_fraction": len(shared) / len(held_out_fens) if held_out_fens else None,
    }


def main() -> int:
    print("=== E-15 Phase E: offline evaluation (issue #223), Measurement Model unchanged ===")

    # --- Verify checkpoint identity before evaluating anything (DR-E15-phase-d section 7). ---
    checkpoints = {}
    for arm, cfg in ARMS.items():
        for kind in ("selected", "final"):
            path = cfg[kind]
            actual = _sha256(path)
            expected = EXPECTED_SHA256[f"{arm}/{kind}"]
            status = "OK" if actual == expected else "MISMATCH"
            print(f"checkpoint {arm}/{kind}: {path} sha256={actual} expected={expected} [{status}]")
            if actual != expected:
                raise RuntimeError(f"checkpoint hash mismatch for {arm}/{kind}: {path}")
            checkpoints[(arm, kind)] = _load_model(path)

    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)

    # --- Primary held-out set: v1 / v1-clean, identical to every prior Phase 3/4/5 experiment. ---
    _, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_V1_DIR, SPLIT_SEED)
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    print(f"v1 held-out: {len(held_out_records)}  v1-clean: {len(held_out_clean)}")

    matrix = {}
    for (arm, kind), model in checkpoints.items():
        matrix[f"{arm}_{kind}_on_v1"] = _eval_cell(model, held_out_records)
        matrix[f"{arm}_{kind}_on_v1_clean"] = _eval_cell(model, held_out_clean)

    matrix["p3a001_baseline_on_v1"] = _eval_cell(baseline_model, held_out_records)
    matrix["p3a001_baseline_on_v1_clean"] = _eval_cell(baseline_model, held_out_clean)

    # --- Exploratory: each arm's own Stage-3 held-out set (section 7's secondary held-out). ---
    stage3_overlap = {}
    for arm, cfg in ARMS.items():
        provider = SelfPlayProvider(
            directory=cfg["ingested_dir"], identifier=cfg["identifier"], source_ref=str(cfg["ingested_dir"])
        )
        stage3_records: List[PositionRecord] = []
        for shard in provider.shards():
            stage3_records.extend(provider.positions(shard))
        stage3_train, stage3_held_out = split_by_game(
            stage3_records, seed=STAGE3_SPLIT_SEED, held_out_fraction=STAGE3_HELD_OUT_FRACTION
        )
        overlap = _fen_overlap(stage3_train, stage3_held_out)
        stage3_overlap[arm] = overlap
        print(f"{arm} stage3 held-out: train={len(stage3_train)} held_out={len(stage3_held_out)} "
              f"overlap={overlap}")

        for kind in ("selected", "final"):
            matrix[f"{arm}_{kind}_on_stage3_held_out"] = _eval_cell(checkpoints[(arm, kind)], stage3_held_out)

    output = {
        "matrix": matrix,
        "stage3_held_out_overlap": stage3_overlap,
        "checkpoint_sha256": {f"{a}/{k}": _sha256(ARMS[a][k]) for a in ARMS for k in ("selected", "final")},
        "v1_n": len(held_out_records),
        "v1_clean_n": len(held_out_clean),
    }
    output_path = Path("outputs/phase-e15/phase_e_evaluation.json")
    output_path.write_text(json.dumps(output, indent=2))
    print(f"wrote {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
