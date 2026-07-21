"""Reads `outputs/phase4/P4I/summary.json` (written by `phase4_p4i_k_sweep.py`) and applies the
Adaptive Decision / Promotion Criteria this experiment's design specifies (research doc §46,
this task's own instructions), printing a machine-checked decision rather than an eyeballed one.

Promotion criteria (as specified): a candidate is promotable only if it improves correlation
*and* at least one of RMSE / calibration / mate behavior / extreme-evaluation behavior.
Improving correlation alone is insufficient.

**Honesty note carried forward from the training script and research doc §46.4's own caveat**:
only correlation has a measured noise floor in this project (§27.2, std=0.0019, n=3, one
configuration -- not yet re-measured at this 20,000-step cosine schedule). RMSE/calibration/mate/
extreme-eval have no equivalent measured threshold, so "improves" for those is reported as a
directional delta (candidate better than baseline, by how much) rather than gated by a
statistical bound that doesn't exist yet -- this script does not fabricate one.
"""

from __future__ import annotations

import json
from pathlib import Path

SUMMARY_PATH = Path("outputs/phase4/P4I/summary.json")
PRIMARY_BENCHMARK = "v1_clean"  # research doc §46.4's declared primary benchmark


def _corr_meaningful(candidate_corr: float, baseline_corr: float, threshold: float) -> tuple[bool, float]:
    delta = candidate_corr - baseline_corr
    return delta > threshold, delta


def _directional(candidate: float, baseline: float, lower_is_better: bool) -> tuple[bool, float]:
    delta = baseline - candidate if lower_is_better else candidate - baseline
    return delta > 0, delta


def _calibration_improves(cand_overall: dict, base_overall: dict) -> tuple[bool, dict]:
    """"Calibration improves" = both |bias| decreases AND compression moves closer to 1.0,
    without regressing the cp-labeled majority (research doc §23.5's failure-mode check) --
    checked by the caller separately via cp_labeled bias/MAE, not folded into this boolean."""
    bias_improves, bias_delta = _directional(abs(cand_overall["signed_mean_error"]),
                                              abs(base_overall["signed_mean_error"]), lower_is_better=True)
    comp_improves, comp_delta = _directional(abs(cand_overall["compression_ratio"] - 1.0),
                                              abs(base_overall["compression_ratio"] - 1.0), lower_is_better=True)
    return (bias_improves and comp_improves), {
        "bias_abs_delta_toward_zero": bias_delta, "compression_distance_from_1_delta": comp_delta,
    }


def _mate_improves(cand_mate: dict, base_mate: dict) -> tuple[bool, dict]:
    bias_improves, bias_delta = _directional(abs(cand_mate["signed_mean_error"]),
                                              abs(base_mate["signed_mean_error"]), lower_is_better=True)
    comp_improves, comp_delta = _directional(abs(cand_mate["compression_ratio"] - 1.0),
                                              abs(base_mate["compression_ratio"] - 1.0), lower_is_better=True)
    return (bias_improves or comp_improves), {
        "mate_bias_abs_delta_toward_zero": bias_delta, "mate_compression_distance_from_1_delta": comp_delta,
    }


def _extreme_improves(cand_buckets: dict, base_buckets: dict) -> tuple[bool, dict]:
    cand_extreme, base_extreme = cand_buckets["extreme"], base_buckets["extreme"]
    if cand_extreme["n"] == 0 or base_extreme["n"] == 0:
        return False, {"note": "empty extreme bucket on this benchmark, cannot compare"}
    mae_improves, mae_delta = _directional(cand_extreme["mae"], base_extreme["mae"], lower_is_better=True)
    bias_improves, bias_delta = _directional(abs(cand_extreme["bias"]), abs(base_extreme["bias"]),
                                              lower_is_better=True)
    return (mae_improves or bias_improves), {
        "extreme_mae_delta": mae_delta, "extreme_bias_abs_delta_toward_zero": bias_delta,
    }


def _cp_majority_regressed(cand_cp: dict, base_cp: dict, noise_floor_bias_cp: float = 10.0) -> tuple[bool, dict]:
    """research doc §23.5's specific failure mode: a fix that helps mate/extreme at the
    cp-labeled majority's expense. No measured noise floor exists for cp-labeled bias in cp
    units either -- 10cp is a small, explicitly-arbitrary guard band stated as such, not a
    calibrated threshold, to avoid flagging noise-sized wiggles as a regression."""
    bias_delta = abs(cand_cp["signed_mean_error"]) - abs(base_cp["signed_mean_error"])
    mae_delta = cand_cp["mae"] - base_cp["mae"]
    regressed = bias_delta > noise_floor_bias_cp
    return regressed, {"cp_bias_abs_delta": bias_delta, "cp_mae_delta": mae_delta}


def analyze_arm(arm: str, summary: dict) -> dict:
    baseline_v1 = summary["matrix"]["baseline_on_v1"]
    baseline_clean = summary["matrix"]["baseline_on_v1_clean"]
    cand_v1 = summary["matrix"][f"{arm}_on_v1"]
    cand_clean = summary["matrix"][f"{arm}_on_v1_clean"]

    threshold = summary["correlation_threshold"]
    corr_meaningful_v1, corr_delta_v1 = _corr_meaningful(cand_v1["correlation"], baseline_v1["correlation"], threshold)
    corr_meaningful_clean, corr_delta_clean = _corr_meaningful(
        cand_clean["correlation"], baseline_clean["correlation"], threshold)

    # Primary benchmark per research doc §46.4's declared choice.
    primary_cand = cand_clean if PRIMARY_BENCHMARK == "v1_clean" else cand_v1
    primary_base = baseline_clean if PRIMARY_BENCHMARK == "v1_clean" else baseline_v1
    corr_meaningful_primary = corr_meaningful_clean if PRIMARY_BENCHMARK == "v1_clean" else corr_meaningful_v1
    corr_delta_primary = corr_delta_clean if PRIMARY_BENCHMARK == "v1_clean" else corr_delta_v1

    rmse_improves, rmse_delta = _directional(primary_cand["overall"]["rmse"], primary_base["overall"]["rmse"],
                                              lower_is_better=True)
    calibration_improves, calibration_detail = _calibration_improves(primary_cand["overall"], primary_base["overall"])
    mate_improves, mate_detail = _mate_improves(primary_cand["mate_labeled"], primary_base["mate_labeled"])
    extreme_improves, extreme_detail = _extreme_improves(primary_cand["magnitude_buckets"],
                                                           primary_base["magnitude_buckets"])
    cp_regressed, cp_detail = _cp_majority_regressed(primary_cand["cp_labeled"], primary_base["cp_labeled"])

    secondary_any = rmse_improves or calibration_improves or mate_improves or extreme_improves
    promotable = corr_meaningful_primary and secondary_any and not cp_regressed

    return {
        "arm": arm,
        "correlation_delta_v1": corr_delta_v1, "correlation_meaningful_v1": corr_meaningful_v1,
        "correlation_delta_v1_clean": corr_delta_clean, "correlation_meaningful_v1_clean": corr_meaningful_clean,
        "primary_benchmark": PRIMARY_BENCHMARK,
        "rmse_improves": rmse_improves, "rmse_delta": rmse_delta,
        "calibration_improves": calibration_improves, "calibration_detail": calibration_detail,
        "mate_improves": mate_improves, "mate_detail": mate_detail,
        "extreme_improves": extreme_improves, "extreme_detail": extreme_detail,
        "cp_majority_regressed": cp_regressed, "cp_majority_detail": cp_detail,
        "secondary_criterion_met": secondary_any,
        "promotable": promotable,
    }


def main() -> int:
    summary = json.loads(SUMMARY_PATH.read_text())
    print(f"=== P4I adaptive decision (primary benchmark: {PRIMARY_BENCHMARK}, "
          f"correlation threshold: {summary['correlation_threshold']:.4f} = "
          f"{summary['meaningful_sigma']}x measured noise floor std={summary['noise_floor_std']}) ===\n")

    results = {}
    for arm in ("low", "high"):
        r = analyze_arm(arm, summary)
        results[arm] = r
        print(f"--- {arm} (K={summary['k_low'] if arm == 'low' else summary['k_high']}) ---")
        print(f"  correlation delta (v1):       {r['correlation_delta_v1']:+.4f}  meaningful={r['correlation_meaningful_v1']}")
        print(f"  correlation delta (v1-clean): {r['correlation_delta_v1_clean']:+.4f}  meaningful={r['correlation_meaningful_v1_clean']}")
        print(f"  RMSE improves:        {r['rmse_improves']}  (delta={r['rmse_delta']:+.2f})")
        print(f"  calibration improves: {r['calibration_improves']}  {r['calibration_detail']}")
        print(f"  mate behavior improves: {r['mate_improves']}  {r['mate_detail']}")
        print(f"  extreme-eval improves: {r['extreme_improves']}  {r['extreme_detail']}")
        print(f"  cp-labeled majority regressed: {r['cp_majority_regressed']}  {r['cp_majority_detail']}")
        print(f"  => PROMOTABLE: {r['promotable']}\n")

    any_meaningful = any(results[a]["correlation_meaningful_v1_clean"] for a in ("low", "high"))
    any_promotable = any(results[a]["promotable"] for a in ("low", "high"))

    print("=== ADAPTIVE DECISION ===")
    if not any_meaningful:
        decision = "TERMINATE"
        print("Neither endpoint produces a statistically meaningful correlation improvement "
              "over baseline on the primary benchmark (v1-clean). Per this experiment's design: "
              "terminate K exploration. Do not evaluate intermediate K values.")
    elif any_promotable:
        decision = "CONTINUE_LOCALIZE"
        winner = [a for a in ("low", "high") if results[a]["promotable"]]
        print(f"Endpoint(s) {winner} show a meaningful correlation improvement AND clear the "
              f"secondary promotion criterion. Recommend the minimum additional experiments "
              f"required to localize the optimum -- NOT executed automatically.")
    else:
        decision = "MEANINGFUL_BUT_NOT_PROMOTABLE"
        print("At least one endpoint shows a statistically meaningful correlation improvement, "
              "but fails the secondary promotion criterion (no RMSE/calibration/mate/extreme-eval "
              "improvement, or the cp-labeled majority regressed) -- not promotable as-is, but "
              "correlation-moving evidence exists. Document and route to review, do not terminate "
              "K exploration as if correlation were flat.")

    output = {"decision": decision, "primary_benchmark": PRIMARY_BENCHMARK, "results": results}
    out_path = Path("outputs/phase4/P4I/decision.json")
    out_path.write_text(json.dumps(output, indent=2))
    print(f"\nDecision written to {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
