"""P5-AUXHEAD-DIAG-001: read-only diagnostic investigation of why P5-AUXHEAD-001's
auxiliary task was poorly learned.

**Not an experiment.** No model is trained here, no production module is modified, no
hyperparameter is changed. This script only *reads* the checkpoints P5-AUXHEAD-001 already
wrote and recomputes statistics from them. `train.py` and `network.py` are untouched --
deliberately, since the investigation's question ("why was the auxiliary task not learned?")
is answerable from saved state plus the public `auxiliary_wdl_logit()` / `forward()` API,
so adding instrumentation to production training paths would be unnecessary risk.

Gradient measurements are *reconstructions*: at each saved checkpoint the model state is
exactly what training had at that step, so backpropagating a loss there measures the true
gradient field at that point in the trajectory. It is evaluated on one large fixed
diagnostic batch rather than the actual (small, noisy) training batch that step used --
which makes it a lower-variance estimate of the expected gradient, not a less faithful one.
Disclosed rather than glossed.

Measures, per the investigation brief:
  1. gradient flow      -- per-component norms (backbone / primary head / auxiliary head)
  2. target distribution-- WDL class balance, and the irreducible BCE floor it implies
  3. auxiliary behaviour-- logit and probability distributions, entropy, calibration
  4. representation     -- cosine similarity of primary vs auxiliary backbone gradients
  5. learning dynamics  -- all of the above across the whole trajectory
"""

from __future__ import annotations

import json
import math
import random
from collections import Counter
from pathlib import Path
from typing import Dict, List

import torch
from torch import nn

from trainer.contracts import PositionRecord
from trainer.model.batching import encode_batch
from trainer.model.network import NnueNet
from trainer.model.train import target_cp, texel_sigmoid
from scripts.train_candidate_net import combine_and_split
from scripts.phase4_p4i_k_sweep import (
    HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, K_BASE, SPLIT_SEED, _is_sentinel,
)

RUN_ROOT = Path("outputs/phase5/P5-AUXHEAD/P5-AUXHEAD-001")
OUTPUT_ROOT = Path("outputs/phase5/P5-AUXHEAD-DIAG")
STAGE1_DIR = Path("outputs/datasets/stage1-lichess")
STAGE2_DIR = Path("outputs/datasets/stage2-quiet-sf-wdl")

AUX_WDL_WEIGHT = 0.04  # the value P5-AUXHEAD-001 actually trained at
DIAGNOSTIC_BATCH = 4096
DIAGNOSTIC_BATCH_SEED = 20260803  # fixed, so every checkpoint is measured on one batch


def _backbone_params(model: NnueNet) -> List[torch.nn.Parameter]:
    return [model.ft.weight, model.ft_bias]


def _grad_vector(params) -> torch.Tensor:
    return torch.cat([p.grad.detach().reshape(-1) if p.grad is not None
                      else torch.zeros(p.numel()) for p in params])


def _norm(params) -> float:
    return float(sum((p.grad.detach() ** 2).sum() for p in params if p.grad is not None) ** 0.5)


def _primary_loss(model: NnueNet, batch, targets: torch.Tensor) -> torch.Tensor:
    predicted_cp = model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets)
    return torch.mean((texel_sigmoid(predicted_cp, K_BASE) - targets) ** 2)


def _aux_loss(model: NnueNet, batch, wdl: torch.Tensor, has_wdl: torch.Tensor) -> torch.Tensor:
    logits = model.auxiliary_wdl_logit(batch.us_indices, batch.us_offsets,
                                        batch.them_indices, batch.them_offsets)
    bce = nn.functional.binary_cross_entropy_with_logits(logits, wdl, reduction="none")
    return (has_wdl * bce).sum() / has_wdl.sum().clamp(min=1.0)


def _irreducible_bce(wdl: torch.Tensor) -> float:
    """Mean binary entropy H(y) over the targets -- the BCE a *perfect* predictor
    (outputting exactly y for every record) would still pay. Draws (y=0.5) cost
    log(2)=0.693 each and can never be driven lower, so this floor is the only honest
    reference point for 'how well could the auxiliary task possibly have been fitted'."""
    y = wdl.clamp(1e-7, 1 - 1e-7)
    return float((-(y * y.log() + (1 - y) * (1 - y).log())).mean())


def _entropy_of_predictions(p: torch.Tensor) -> float:
    q = p.clamp(1e-7, 1 - 1e-7)
    return float((-(q * q.log() + (1 - q) * (1 - q).log())).mean())


def _calibration_bins(p: torch.Tensor, y: torch.Tensor, bins: int = 10) -> List[dict]:
    out = []
    for i in range(bins):
        lo, hi = i / bins, (i + 1) / bins
        mask = (p >= lo) & (p < hi) if i < bins - 1 else (p >= lo) & (p <= hi)
        n = int(mask.sum())
        out.append({
            "bin": f"[{lo:.1f},{hi:.1f})", "n": n,
            "mean_predicted": float(p[mask].mean()) if n else None,
            "mean_actual": float(y[mask].mean()) if n else None,
        })
    return out


def _checkpoint_steps() -> List[int]:
    return sorted(int(p.stem.split("-")[1]) for p in (RUN_ROOT / "checkpoints").glob("step-*.pt"))


def main() -> int:
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    print("=== P5-AUXHEAD-DIAG-001: read-only diagnostic (no training, no production changes) ===")

    training_records, held_out_records, _ = combine_and_split(STAGE1_DIR, STAGE2_DIR, SPLIT_SEED)
    training_records = [r for r in training_records if not _is_sentinel(r)]
    held_out = [r for r in held_out_records if not _is_sentinel(r)]

    # ---- (2) target distribution -------------------------------------------------
    train_wdl = [r.label.wdl for r in training_records if r.label.wdl is not None]
    counts = Counter(train_wdl)
    total_wdl = len(train_wdl)
    dist = {f"{k}": {"n": v, "fraction": v / total_wdl} for k, v in sorted(counts.items())}
    wdl_tensor = torch.tensor(train_wdl, dtype=torch.float32)
    floor = _irreducible_bce(wdl_tensor)
    mean_wdl = float(wdl_tensor.mean())
    const_baseline = float(nn.functional.binary_cross_entropy(
        torch.full_like(wdl_tensor, mean_wdl), wdl_tensor))
    print(f"\n--- (2) WDL target distribution (training corpus) ---")
    print(f"  records with wdl: {total_wdl}/{len(training_records)} "
          f"({100*total_wdl/len(training_records):.1f}%)")
    for k, v in dist.items():
        label = {"0.0": "loss", "0.5": "draw", "1.0": "win"}.get(k, k)
        print(f"    wdl={k} ({label:<4}): {v['n']:>6}  {100*v['fraction']:.2f}%")
    print(f"  mean wdl                        : {mean_wdl:.4f}")
    print(f"  IRREDUCIBLE BCE floor (draws!)  : {floor:.4f}   <- best achievable, ever")
    print(f"  constant-predictor BCE baseline : {const_baseline:.4f}")

    # ---- fixed diagnostic batch ---------------------------------------------------
    rng = random.Random(DIAGNOSTIC_BATCH_SEED)
    diag_records: List[PositionRecord] = rng.sample(training_records, DIAGNOSTIC_BATCH)
    batch = encode_batch(diag_records)
    targets = texel_sigmoid(
        torch.tensor([target_cp(r.label) for r in diag_records], dtype=torch.float32), K_BASE)
    has_wdl = torch.tensor([r.label.wdl is not None for r in diag_records], dtype=torch.float32)
    wdl_values = torch.tensor([r.label.wdl if r.label.wdl is not None else 0.0
                                for r in diag_records], dtype=torch.float32)

    held_wdl = [r for r in held_out if r.label.wdl is not None]
    held_batch = encode_batch(held_wdl)
    held_y = torch.tensor([r.label.wdl for r in held_wdl], dtype=torch.float32)

    rows: List[Dict] = []
    steps = _checkpoint_steps()
    print(f"\n--- (1)(4)(5) per-checkpoint gradient + behaviour ({len(steps)} checkpoints,"
          f" diagnostic batch n={DIAGNOSTIC_BATCH}) ---")
    header = (f"{'step':>6}{'g_bb_prim':>11}{'g_bb_aux*w':>12}{'ratio':>8}"
              f"{'cos':>8}{'g_head_p':>10}{'g_head_a':>10}"
              f"{'logit_std':>10}{'p_std':>8}{'H(p)':>7}{'auxBCE':>8}")
    print(header)

    for step in steps:
        ckpt = torch.load(RUN_ROOT / "checkpoints" / f"step-{step:06d}.pt", weights_only=False)
        model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True)
        model.load_state_dict(ckpt["model_state_dict"])
        backbone = _backbone_params(model)

        # primary-only gradient
        model.zero_grad(set_to_none=True)
        _primary_loss(model, batch, targets).backward()
        g_bb_primary = _grad_vector(backbone).clone()
        n_bb_primary = _norm(backbone)
        n_head_primary = _norm([model.output_layer.weight, model.output_layer.bias])

        # auxiliary-only gradient (scaled by the weight actually used in training)
        model.zero_grad(set_to_none=True)
        (AUX_WDL_WEIGHT * _aux_loss(model, batch, wdl_values, has_wdl)).backward()
        g_bb_aux = _grad_vector(backbone).clone()
        n_bb_aux = _norm(backbone)
        n_head_aux = _norm([model.wdl_head.weight, model.wdl_head.bias])

        cos = float(nn.functional.cosine_similarity(
            g_bb_primary.unsqueeze(0), g_bb_aux.unsqueeze(0)).item())

        # auxiliary prediction behaviour on held-out wdl-bearing records
        model.zero_grad(set_to_none=True)
        with torch.no_grad():
            logits = model.auxiliary_wdl_logit(held_batch.us_indices, held_batch.us_offsets,
                                                held_batch.them_indices, held_batch.them_offsets)
            probs = torch.sigmoid(logits)
            bce = float(nn.functional.binary_cross_entropy_with_logits(logits, held_y))
            corr = float(torch.corrcoef(torch.stack([probs, held_y]))[0, 1])

        row = {
            "step": step,
            "backbone_grad_norm_primary": n_bb_primary,
            "backbone_grad_norm_aux_weighted": n_bb_aux,
            "aux_to_primary_backbone_ratio": n_bb_aux / n_bb_primary if n_bb_primary else None,
            "backbone_grad_cosine_primary_vs_aux": cos,
            "primary_head_grad_norm": n_head_primary,
            "aux_head_grad_norm": n_head_aux,
            "logit_mean": float(logits.mean()), "logit_std": float(logits.std()),
            "logit_min": float(logits.min()), "logit_max": float(logits.max()),
            "prob_mean": float(probs.mean()), "prob_std": float(probs.std()),
            "prob_frac_below_0p01": float((probs < 0.01).float().mean()),
            "prob_frac_above_0p99": float((probs > 0.99).float().mean()),
            "prediction_entropy": _entropy_of_predictions(probs),
            "held_out_aux_bce": bce,
            "held_out_aux_correlation": corr,
        }
        rows.append(row)
        print(f"{step:>6}{n_bb_primary:>11.4f}{n_bb_aux:>12.4f}"
              f"{row['aux_to_primary_backbone_ratio']:>8.3f}{cos:>8.3f}"
              f"{n_head_primary:>10.4f}{n_head_aux:>10.4f}"
              f"{row['logit_std']:>10.2f}{row['prob_std']:>8.3f}"
              f"{row['prediction_entropy']:>7.3f}{bce:>8.3f}")

    # ---- (3) prediction behaviour at first / last checkpoint ----------------------
    print(f"\n--- (3) auxiliary prediction behaviour (held-out, n={len(held_wdl)}) ---")
    detail = {}
    for step in (steps[0], steps[len(steps) // 2], steps[-1]):
        ckpt = torch.load(RUN_ROOT / "checkpoints" / f"step-{step:06d}.pt", weights_only=False)
        model = NnueNet(HIDDEN_WIDTH, QA, QB, OUTPUT_SCALE, with_aux_wdl_head=True)
        model.load_state_dict(ckpt["model_state_dict"])
        with torch.no_grad():
            logits = model.auxiliary_wdl_logit(held_batch.us_indices, held_batch.us_offsets,
                                                held_batch.them_indices, held_batch.them_offsets)
            probs = torch.sigmoid(logits)
        hist = {}
        for i in range(10):
            lo, hi = i / 10, (i + 1) / 10
            m = (probs >= lo) & (probs < hi) if i < 9 else (probs >= lo) & (probs <= hi)
            hist[f"[{lo:.1f},{hi:.1f})"] = int(m.sum())
        cal = _calibration_bins(probs, held_y)
        detail[str(step)] = {"histogram": hist, "calibration": cal,
                              "logit_range": [float(logits.min()), float(logits.max())]}
        print(f"  step {step}: logit range [{logits.min():.1f}, {logits.max():.1f}], "
              f"prob histogram {list(hist.values())}")

    summary = {
        "experiment_id": "P5-AUXHEAD-DIAG-001",
        "source_run": "P5-AUXHEAD-001",
        "aux_wdl_weight": AUX_WDL_WEIGHT,
        "diagnostic_batch_size": DIAGNOSTIC_BATCH,
        "diagnostic_batch_seed": DIAGNOSTIC_BATCH_SEED,
        "target_distribution": {
            "records_with_wdl": total_wdl, "training_records": len(training_records),
            "classes": dist, "mean_wdl": mean_wdl,
            "irreducible_bce_floor": floor, "constant_predictor_bce": const_baseline,
        },
        "trajectory": rows,
        "prediction_detail": detail,
    }
    (OUTPUT_ROOT / "diagnostics.json").write_text(json.dumps(summary, indent=2))
    print(f"\nDiagnostics written: {OUTPUT_ROOT / 'diagnostics.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
