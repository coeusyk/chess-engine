"""Experiment P5-AUXHEAD-RMS-001: parameter-free auxiliary-input RMS normalization, Phase 5.

Design record: `docs/architecture/research/nnue/phase5-p5-auxhead-rms-design.md` -- read that
for the gradient-geometry argument, control identity, and stopping conditions; not restated
here.

**Scope**: only `TrainingConfig.aux_rms_norm` is varied (False -> True), at the same
`aux_wdl_weight=0.04` P5-AUXHEAD-001 used. `K`, architecture, optimizer, P1-G04's frozen
schedule, datasets, split/training/shuffle seeds, checkpoint-selection protocol, evaluation
protocol, and promotion protocol are all unchanged. This replaces the constant-divisor (`/QA`)
proposal rejected in adversarial review: under this repository's plain Adam (no weight decay,
no gradient clipping), a constant divisor is confounded with an effective auxiliary-head
learning-rate and backbone-coupling cut. RMS normalization is per-sample and recomputed every
forward pass, so the optimizer cannot absorb it into a learning-rate change.

**Two arms, run separately, on purpose.** `control` (aux_rms_norm=False) is the matched
unnormalized control -- byte-identical in code path to P5-AUXHEAD-001, regenerated here because
the historical P5-AUXHEAD-001 artifacts are absent from this working tree (`outputs/` is
gitignored). Its diagnostics must be inspected against the historical pathology (design record
§6) BEFORE the treatment arm is run: this script does not chain them automatically, so that
check is a real gate, not a formality. `treatment` (aux_rms_norm=True) is the RMS-normalized
arm. `report` produces the final evaluation matrix once both checkpoints exist.

Usage: `python -m scripts.phase5_auxhead_rms control|treatment|report`
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import random
import sys
import time
from pathlib import Path
from typing import List

import torch

from trainer.contracts import PositionRecord
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig, train
from trainer.validation.validator import evaluate_held_out
from scripts.train_candidate_net import combine_and_split
from scripts.phase4_p4i_k_sweep import (
    HIDDEN_WIDTH,
    QA,
    QB,
    OUTPUT_SCALE,
    K_BASE,
    SPLIT_SEED,
    STEPS,
    LEARNING_RATE,
    LR_SCHEDULE,
    WARMUP_STEPS,
    TRAINING_SEED,
    BATCH_SIZE,
    TRAIN_SAMPLE_SELECTION_SEED,
    TRAIN_SAMPLE_SIZE,
    P3A_001_BASELINE_CHECKPOINT,
    _is_sentinel,
    _load_model,
    _select_best_checkpoint,
    _eval_cell,
)

EXPERIMENT_ID = "P5-AUXHEAD-RMS-001"
AUX_WDL_WEIGHT = 0.04  # same value P5-AUXHEAD-001 trained at; only aux_rms_norm is varied
STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf-wdl")
OUTPUT_ROOT = Path("outputs/phase5") / "P5-AUXHEAD-RMS"

# Historical Phase-1 promoted research reference (display-only for this experiment; see design
# record §5 -- must never participate in pass/fail gating). "P1-G04" canonically means the
# *selected* checkpoint at step 15,999 (peak held-out pooled correlation, matching the recovery
# audit's recomputed v1 correlation 0.5315414 exactly) -- NOT outputs/phase1/P1-G04/final.pt,
# which is a different, later (step 19,999) artifact from the same run. An earlier version of
# this file pointed here at final.pt by mistake; the two files hash differently
# (acd24d68... for step-015999.pt vs 479e61e7... for final.pt) and are not interchangeable.
P1_G04_CHECKPOINT = Path("outputs/phase1/P1-G04/checkpoints/step-015999.pt")

ARMS = {"control": False, "treatment": True}


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    h.update(path.read_bytes())
    return h.hexdigest()


def _pin_provenance() -> dict:
    """Records dataset/environment identity before either arm trains, per the design record's
    'pin dataset hashes, split membership/order hashes, checkpoint identity, environment'
    requirement. Written once; each arm's meta references it rather than re-deriving it."""
    dataset_files = sorted(
        [p for p in STAGE1_DIR.iterdir() if p.is_file()]
        + [p for p in STAGE2_DIR.iterdir() if p.is_file()]
    )
    dataset_hashes = {str(p): _sha256_file(p) for p in dataset_files}

    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    # Order-sensitive: this is the exact pre-filter split combine_and_split() produced, pinned
    # so both arms (and the report step) are provably trained/evaluated against the same split.
    train_fen_blob = "\n".join(r.fen for r in training_records).encode()
    held_out_fen_blob = "\n".join(r.fen for r in held_out_records).encode()

    import torch as _torch
    import numpy as _np

    provenance = {
        "experiment_id": EXPERIMENT_ID,
        "dataset_file_sha256": dataset_hashes,
        "split_seed": SPLIT_SEED,
        "training_records_count": len(training_records),
        "held_out_records_count": len(held_out_records),
        "training_split_order_sha256": hashlib.sha256(train_fen_blob).hexdigest(),
        "held_out_split_order_sha256": hashlib.sha256(held_out_fen_blob).hexdigest(),
        "p3a_001_checkpoint": str(P3A_001_BASELINE_CHECKPOINT),
        "p3a_001_checkpoint_sha256": _sha256_file(P3A_001_BASELINE_CHECKPOINT),
        "p1_g04_checkpoint": str(P1_G04_CHECKPOINT),
        "p1_g04_checkpoint_sha256": (
            _sha256_file(P1_G04_CHECKPOINT) if P1_G04_CHECKPOINT.exists() else None
        ),
        "environment": {
            "torch": _torch.__version__,
            "numpy": _np.__version__,
            "cuda_available": _torch.cuda.is_available(),
            "cuda_device": _torch.cuda.get_device_name(0) if _torch.cuda.is_available() else None,
        },
    }
    return provenance


def _load_or_pin_provenance() -> dict:
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    path = OUTPUT_ROOT / "provenance.json"
    if path.exists():
        existing = json.loads(path.read_text())
        return existing
    provenance = _pin_provenance()
    path.write_text(json.dumps(provenance, indent=2))
    return provenance


def _load_model_allowing_aux_head(checkpoint_path: Path) -> NnueNet:
    """Same narrow non-strict-load pattern P5-AUXHEAD-001's driver uses: a checkpoint trained
    with an auxiliary head carries `wdl_head.*` keys a plain NnueNet does not declare, and the
    auxiliary head must never participate in any evaluation metric, so discarding it here on
    load is correct, not a workaround. `_load_model`'s own strict=True stays untouched -- six
    unrelated scripts import it."""
    checkpoint = torch.load(checkpoint_path, weights_only=False)
    model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE)  # primary-only, by design
    missing, unexpected = model.load_state_dict(checkpoint["model_state_dict"], strict=False)
    if missing:
        raise ValueError(f"checkpoint is missing primary parameters: {missing}")
    if any(not key.startswith("wdl_head") for key in unexpected):
        raise ValueError(f"unexpected non-auxiliary parameters in checkpoint: {unexpected}")
    return model


def _train_arm(arm: str, training_records: List[PositionRecord],
        held_out_records: List[PositionRecord], train_diagnostic_sample: List[PositionRecord],
) -> dict:
    aux_rms_norm = ARMS[arm]
    output_root = OUTPUT_ROOT / arm
    output_root.mkdir(parents=True, exist_ok=True)

    config = TrainingConfig(
        hidden_width=HIDDEN_WIDTH, qa=QA, qb=QB, output_scale=OUTPUT_SCALE, k=K_BASE,
        learning_rate=LEARNING_RATE, seed=TRAINING_SEED, steps=STEPS, batch_size=BATCH_SIZE,
        lr_schedule=LR_SCHEDULE, warmup_steps=WARMUP_STEPS, aux_wdl_weight=AUX_WDL_WEIGHT,
        aux_rms_norm=aux_rms_norm,
    )
    print(f"=== training {EXPERIMENT_ID}/{arm}: aux_wdl_weight={AUX_WDL_WEIGHT} "
          f"aux_rms_norm={aux_rms_norm} K={K_BASE} steps={STEPS} lr={LEARNING_RATE} "
          f"schedule={LR_SCHEDULE} warmup={WARMUP_STEPS} seed={TRAINING_SEED} "
          f"(P1-G04's frozen schedule; only aux_rms_norm varies between arms) ===")

    t0 = time.time()
    result = train(
        config, training_records, checkpoint_path=output_root / "final.pt",
        held_out_records=held_out_records, log_interval=max(1, STEPS // 20),
        train_diagnostic_sample=train_diagnostic_sample, checkpoint_dir=output_root / "checkpoints",
    )
    wall_clock_seconds = time.time() - t0

    diagnostics = [dataclasses.asdict(d) for d in result["diagnostics"]]
    (output_root / "training_diagnostics.json").write_text(json.dumps(diagnostics, indent=2))

    selected_step, selected_checkpoint_path = _select_best_checkpoint(diagnostics, output_root)
    final_step = STEPS - 1
    final_checkpoint_path = output_root / "checkpoints" / f"step-{final_step:06d}.pt"
    print(f"{EXPERIMENT_ID}/{arm}: selected_step={selected_step}/{STEPS - 1} "
          f"final_step={final_step} wall_clock={wall_clock_seconds:.1f}s "
          f"(measurement-model.md §8: both checkpoints reported)")

    meta = {
        "arm": arm, "aux_rms_norm": aux_rms_norm, "aux_wdl_weight": AUX_WDL_WEIGHT,
        "wall_clock_seconds": wall_clock_seconds,
        "selected_step": selected_step, "final_step": final_step,
        "selected_checkpoint_path": str(selected_checkpoint_path),
        "final_checkpoint_path": str(final_checkpoint_path),
    }
    (output_root / "arm_meta.json").write_text(json.dumps(meta, indent=2))
    return meta


def _subset_correlation(model, records: List[PositionRecord], predicate) -> "float | None":
    subset = [r for r in records if predicate(r)]
    return evaluate_held_out(model, subset, K_BASE).label_correlation if subset else None


def _prepare_data():
    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    filtered_training_records = [r for r in training_records if not _is_sentinel(r)]
    held_out_clean = [r for r in held_out_records if not _is_sentinel(r)]
    fixed_sample_indices = random.Random(TRAIN_SAMPLE_SELECTION_SEED).sample(
        range(len(filtered_training_records)), min(TRAIN_SAMPLE_SIZE, len(filtered_training_records))
    )
    train_diagnostic_sample = [filtered_training_records[i] for i in fixed_sample_indices]
    return filtered_training_records, held_out_records, held_out_clean, train_diagnostic_sample


def run_arm(arm: str) -> int:
    provenance = _load_or_pin_provenance()
    print(f"=== {EXPERIMENT_ID}: provenance pinned at {OUTPUT_ROOT / 'provenance.json'} ===")
    print(json.dumps(provenance, indent=2))

    training_records, held_out_records, held_out_clean, train_diagnostic_sample = _prepare_data()
    _train_arm(arm, training_records, held_out_records, train_diagnostic_sample)
    print(f"\n{EXPERIMENT_ID}/{arm} training complete. Run "
          f"`python -m scripts.phase5_auxhead_diagnostics "
          f"{OUTPUT_ROOT / arm} outputs/phase5/P5-AUXHEAD-RMS-DIAG-{arm} "
          f"P5-AUXHEAD-RMS-DIAG-001-{arm}` next to inspect mechanism metrics"
          + (" and verify the reproduction gate (design record §6) before running the "
             "treatment arm." if arm == "control" else "."))
    return 0


def run_report() -> int:
    control_meta_path = OUTPUT_ROOT / "control" / "arm_meta.json"
    treatment_meta_path = OUTPUT_ROOT / "treatment" / "arm_meta.json"
    if not control_meta_path.exists() or not treatment_meta_path.exists():
        raise SystemExit(
            "Both arms must be trained before `report`. Missing: "
            + ", ".join(str(p) for p in (control_meta_path, treatment_meta_path) if not p.exists())
        )
    control_meta = json.loads(control_meta_path.read_text())
    treatment_meta = json.loads(treatment_meta_path.read_text())

    training_records, held_out_records, held_out_clean, _ = _prepare_data()
    n_wdl = sum(1 for r in training_records if r.label.wdl is not None)

    baseline_model = _load_model(P3A_001_BASELINE_CHECKPOINT)  # no-auxiliary control (P3A-001)
    p1_g04_model = _load_model(P1_G04_CHECKPOINT) if P1_G04_CHECKPOINT.exists() else None

    arms_to_eval = [("baseline_p3a001", baseline_model)]
    if p1_g04_model is not None:
        arms_to_eval.append(("p1_g04_display_only", p1_g04_model))
    for tag, meta in (("control", control_meta), ("treatment", treatment_meta)):
        for ckpt_kind in ("selected", "final"):
            model = _load_model_allowing_aux_head(Path(meta[f"{ckpt_kind}_checkpoint_path"]))
            arms_to_eval.append((f"{tag}_{ckpt_kind}", model))

    is_mate = lambda r: r.label.eval_mate is not None  # noqa: E731
    is_cp = lambda r: r.label.eval_cp is not None  # noqa: E731

    matrix = {}
    for arm, model in arms_to_eval:
        matrix[f"{arm}_on_v1"] = _eval_cell(model, held_out_records, K_BASE)
        matrix[f"{arm}_on_v1_clean"] = _eval_cell(model, held_out_clean, K_BASE)
        for bench_key, bench_records in [("v1", held_out_records), ("v1_clean", held_out_clean)]:
            matrix[f"{arm}_on_{bench_key}"]["cp_subset_correlation"] = _subset_correlation(
                model, bench_records, is_cp
            )
            matrix[f"{arm}_on_{bench_key}"]["mate_subset_correlation"] = _subset_correlation(
                model, bench_records, is_mate
            )

    print(f"\n=== {EXPERIMENT_ID} evaluation matrix "
          "(Measurement Model order: cp-subset FIRST, then mate, then pooled v1-clean, v1) ===")
    print(f"{'Arm':<24}{'Benchmark':<11}{'n':>6}{'CpSubsetCorr':>14}{'MateSubsetCorr':>16}"
          f"{'PooledCorr':>12}{'RMSE':>10}{'Bias':>10}")
    for arm, _ in arms_to_eval:
        for bench_label, bench_key in [("v1-clean", "v1_clean"), ("v1", "v1")]:
            c = matrix[f"{arm}_on_{bench_key}"]
            cp_str = f"{c['cp_subset_correlation']:.4f}" if c["cp_subset_correlation"] is not None else "n/a"
            mate_str = f"{c['mate_subset_correlation']:.4f}" if c["mate_subset_correlation"] is not None else "n/a"
            print(f"{arm:<24}{bench_label:<11}{c['n']:>6}{cp_str:>14}{mate_str:>16}"
                  f"{c['correlation']:>12.4f}{c['overall']['rmse']:>10.1f}"
                  f"{c['overall']['signed_mean_error']:>10.1f}")

    # PRIMARY GATE (design record §7.2): treatment vs its matched unnormalized control AND vs
    # the P3A-001 no-auxiliary control, on cp-subset v1-clean correlation -- checkpoint
    # selection is peak held-out pooled correlation (the existing rule), never chosen by
    # auxiliary BCE.
    baseline_cp = matrix["baseline_p3a001_on_v1_clean"]["cp_subset_correlation"]
    control_cp = matrix["control_selected_on_v1_clean"]["cp_subset_correlation"]
    print()
    for arm in ("control_selected", "control_final", "treatment_selected", "treatment_final"):
        c = matrix[f"{arm}_on_v1_clean"]["cp_subset_correlation"]
        vs_baseline = c - baseline_cp
        vs_control = c - control_cp
        print(f"PRIMARY DECISION METRIC  {arm:<20} cp-only v1-clean vs P3A-001 baseline: "
              f"{vs_baseline:+.4f}   vs matched unnormalized control: {vs_control:+.4f}")

    summary = {
        "experiment_id": EXPERIMENT_ID, "k_base": K_BASE, "aux_wdl_weight": AUX_WDL_WEIGHT,
        "training_records": len(training_records),
        "wdl_fraction_of_training_corpus": n_wdl / len(training_records),
        "steps": STEPS, "learning_rate": LEARNING_RATE, "lr_schedule": LR_SCHEDULE,
        "warmup_steps": WARMUP_STEPS, "seed": TRAINING_SEED,
        "control_arm": control_meta, "treatment_arm": treatment_meta,
        "matrix": matrix,
    }
    (OUTPUT_ROOT / "summary.json").write_text(json.dumps(summary, indent=2))
    print(f"\n{EXPERIMENT_ID} report complete. Summary: {OUTPUT_ROOT / 'summary.json'}")
    return 0


def main() -> int:
    if len(sys.argv) != 2 or sys.argv[1] not in ("control", "treatment", "report"):
        print(__doc__)
        return 2
    mode = sys.argv[1]
    if mode == "report":
        return run_report()
    return run_arm(mode)


if __name__ == "__main__":
    raise SystemExit(main())
