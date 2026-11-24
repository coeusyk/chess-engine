"""Report-only comparison of weight-scale alternatives on a real checkpoint (#265).

Rescales the canonical network so the float function is unchanged (first layer and QA by one factor, output
layer and QB by another, output bias by both), then runs the production `quantize()` and the parity and
scale-utilization measurements on each combination. Nothing here changes the quantizer, picks a scale, or
applies a threshold. The float scores of every cell are compared with the baseline cell as a check that the
rescaling really preserved the function.

Usage (from trainer/): python -m scripts.analyze_scale_alternatives CHECKPOINT.pt --fens FENS [--fens ...] --output OUT.json
"""
from __future__ import annotations

import argparse
import json
from dataclasses import replace
from pathlib import Path
from typing import Any, Dict, List, Sequence

import numpy as np

from scripts.audit_checkpoint_quantization import load_checkpoint, read_fens
from trainer.export.canonical import CanonicalNetwork, checkpoint_to_canonical
from trainer.quantization.inference import eval_float_canonical
from trainer.quantization.quantizer import quantize
from trainer.validation.quantization_parity import compute_quantization_parity
from trainer.validation.scale_utilization import compute_scale_utilization

INT16_MAX = 32767
INT32_MAX = 2 ** 31 - 1
MAX_ACTIVE_FEATURES = 32
FT_FACTORS = (1, 2, 4, 8)
OUT_FACTORS = (1, 2, 4)


def rescale(net: CanonicalNetwork, ft_factor: int, out_factor: int) -> CanonicalNetwork:
    """Same float function, different integer grid."""
    return replace(
        net,
        ft_weights=(net.ft_weights * ft_factor).astype(np.float32),
        ft_biases=(net.ft_biases * ft_factor).astype(np.float32),
        output_weights=(net.output_weights * out_factor).astype(np.float32),
        output_bias=float(net.output_bias) * ft_factor * out_factor,
        qa=net.qa * ft_factor,
        qb=net.qb * out_factor,
    )


def headroom(q) -> Dict[str, float]:
    ft = np.abs(q.ft_weights.astype(np.int64))
    worst_accumulator = float(np.max(q.ft_biases.astype(np.int64) + MAX_ACTIVE_FEATURES * ft.max(axis=0)))
    output_abs_sum = float(np.abs(q.output_weights.astype(np.int64)).sum())
    return {
        "ft_weight_max_int": float(ft.max()),
        "worst_case_accumulator_over_int16": worst_accumulator / INT16_MAX,
        "output_weight_max_int": float(np.abs(q.output_weights).max()),
        "output_weight_max_over_int16": float(np.abs(q.output_weights).max()) / INT16_MAX,
        "worst_case_output_sum_over_int32": (q.qa * output_abs_sum + abs(q.output_bias)) / INT32_MAX,
    }


def analyze(checkpoint: Path, fens: Sequence[str], trust: bool = False) -> Dict[str, Any]:
    base = checkpoint_to_canonical(load_checkpoint(checkpoint, trust))
    baseline_float = eval_float_canonical(base, fens)
    cells: List[Dict[str, Any]] = []
    for ft_factor in FT_FACTORS:
        for out_factor in OUT_FACTORS:
            net = rescale(base, ft_factor, out_factor)
            cell: Dict[str, Any] = {"qa": net.qa, "qb": net.qb}
            try:
                parity, quantized = compute_quantization_parity(net, fens)
                utilization = compute_scale_utilization(net, fens)
            except ValueError as error:
                cell["rejected"] = str(error)
                cells.append(cell)
                continue
            cell.update({
                "mae_cp": parity.mae_cp,
                "rmse_cp": parity.rmse_cp,
                "max_abs_cp": parity.max_abs_cp,
                "mean_signed_cp": parity.mean_signed_cp,
                "weight_rounding_mae_cp": parity.weight_rounding_mae_cp,
                "integer_arithmetic_max_abs_cp": parity.integer_arithmetic_max_abs_cp,
                "float_function_max_diff_cp": float(np.abs(eval_float_canonical(net, fens) - baseline_float).max()),
                "accumulator_max_over_qa": utilization.accumulator_max / net.qa,
                "frac_accumulator_at_or_above_qa": utilization.frac_accumulator_at_or_above_qa,
                "ft_weight_fraction_rounding_to_zero": utilization.ft_weight_fraction_rounding_to_zero,
                "ft_rounding_error_to_weight_rms_ratio": utilization.ft_rounding_error_to_weight_rms_ratio,
            })
            cell.update(headroom(quantized))
            cells.append(cell)
    return {"checkpoint_file": checkpoint.name, "positions": len(fens), "cells": cells}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--fens", type=Path, nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--trust-checkpoint", action="store_true")
    args = parser.parse_args(argv)
    fens = [fen for path in args.fens for fen in read_fens(path)]
    result = analyze(args.checkpoint, fens, args.trust_checkpoint)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    for cell in result["cells"]:
        if "rejected" in cell:
            print(f"qa={cell['qa']:5d} qb={cell['qb']:4d} rejected: {cell['rejected'][:70]}")
        else:
            print(f"qa={cell['qa']:5d} qb={cell['qb']:4d} mae={cell['mae_cp']:6.2f} rmse={cell['rmse_cp']:6.2f} "
                  f"max={cell['max_abs_cp']:6.1f} fnDiff={cell['float_function_max_diff_cp']:.1e}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
