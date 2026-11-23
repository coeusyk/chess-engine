"""Issue #265: float32-versus-quantized parity report, gate and provenance.

The thresholds used below are local to these tests: arbitrary round numbers chosen to separate the
two synthetic networks by a wide margin. They are not proposed release thresholds; those still
have to be calibrated against real checkpoints, which this repository does not contain.
"""

import json

import numpy as np
import pytest

from tests.export._fixtures import metadata
from tests.quantization._parity_data import (
    corpus_fens, poorly_scaled_network, synthetic_float_network, well_scaled_network,
)
from trainer.export.exporter import DatasetComposition
from trainer.export.manifest_schema import validate_manifest
from trainer.export.nnue_reader import read_nnue
from trainer.quantization.inference import eval_quantized_fens
from trainer.validation.quantization_parity import (
    ParityThresholds,
    QuantizationParityError,
    compute_quantization_parity,
    quantize_validate_export,
    threshold_violations,
)

LOCAL_GATE = ParityThresholds(max_mae_cp=20.0, max_abs_cp=60.0, min_pearson_float_quantized=0.99)


@pytest.fixture(scope="module")
def fens():
    return corpus_fens()


@pytest.fixture(scope="module")
def labels(fens):
    return np.random.default_rng(7).normal(0, 300, len(fens))


def test_a_well_scaled_network_passes_and_a_poorly_scaled_one_is_flagged(fens):
    well, _ = compute_quantization_parity(well_scaled_network(), fens)
    poor, _ = compute_quantization_parity(poorly_scaled_network(), fens)

    assert threshold_violations(well, LOCAL_GATE) == []
    violations = threshold_violations(poor, LOCAL_GATE)
    assert len(violations) == 3, violations  # mae, max |delta| and correlation all trip

    # Same comparison without any threshold: the distortion is large and real, not a borderline case.
    assert poor.mae_cp > 5 * well.mae_cp
    assert poor.max_abs_cp > 5 * well.max_abs_cp
    assert poor.pearson_float_quantized < 0.96 < 0.999 < well.pearson_float_quantized


def test_relative_error_separates_the_two_regimes_even_though_their_score_spread_is_similar(fens):
    from trainer.quantization.inference import eval_float_canonical
    well_net, poor_net = well_scaled_network(), poorly_scaled_network()
    well, _ = compute_quantization_parity(well_net, fens)
    poor, _ = compute_quantization_parity(poor_net, fens)
    well_spread = eval_float_canonical(well_net, fens).std()
    poor_spread = eval_float_canonical(poor_net, fens).std()
    assert 0.5 < poor_spread / well_spread < 2.0  # comparable spread, so the gap is not a scale artifact
    assert (poor.mae_cp / poor_spread) > 5 * (well.mae_cp / well_spread)


def test_error_is_split_into_weight_rounding_and_integer_arithmetic(fens):
    for network in (well_scaled_network(), poorly_scaled_network()):
        report, _ = compute_quantization_parity(network, fens)
        assert report.integer_arithmetic_max_abs_cp < 1.0   # truncation toward zero only
        assert report.weight_rounding_mae_cp == pytest.approx(report.mae_cp, rel=0.05)


def test_report_values_are_recomputable_from_the_two_score_vectors(fens):
    report, quantized = compute_quantization_parity(well_scaled_network(), fens)
    from trainer.quantization.inference import eval_float_canonical
    float_scores = eval_float_canonical(well_scaled_network(), fens)
    delta = eval_quantized_fens(quantized, fens) - float_scores
    assert report.mae_cp == pytest.approx(np.abs(delta).mean())
    assert report.rmse_cp == pytest.approx(np.sqrt((delta ** 2).mean()))
    assert report.max_abs_cp == pytest.approx(np.abs(delta).max())
    assert report.mean_signed_cp == pytest.approx(delta.mean())
    assert report.pearson_float_quantized == pytest.approx(np.corrcoef(float_scores, float_scores + delta)[0, 1])
    assert report.position_count == len(fens)


def test_label_correlation_change_is_reported_only_when_labels_are_given(fens, labels):
    without, _ = compute_quantization_parity(well_scaled_network(), fens)
    assert (without.label_correlation_float, without.label_correlation_quantized, without.label_correlation_delta) \
        == (None, None, None)

    # Labels that follow the float network's own scores, so the float correlation is high.
    from trainer.quantization.inference import eval_float_canonical
    network = poorly_scaled_network()
    follows = eval_float_canonical(network, fens) + np.random.default_rng(3).normal(0, 20, len(fens))
    report, _ = compute_quantization_parity(network, fens, follows)
    assert report.label_correlation_float > 0.95
    assert report.label_correlation_delta == pytest.approx(
        abs(report.label_correlation_float - report.label_correlation_quantized))
    assert report.label_correlation_delta > 0.02  # the poor network measurably loses correlation


def test_the_report_is_deterministic_and_records_its_inputs(fens, labels):
    first, _ = compute_quantization_parity(well_scaled_network(), fens, labels)
    second, _ = compute_quantization_parity(well_scaled_network(), fens, labels)
    assert first == second
    assert first.fen_corpus_sha256 == compute_quantization_parity(well_scaled_network(), fens)[0].fen_corpus_sha256
    assert first.fen_corpus_sha256 != compute_quantization_parity(well_scaled_network(), fens[:-1])[0].fen_corpus_sha256
    assert (first.qa, first.qb, first.output_scale) == (127, 64, 400)


def test_a_label_bound_without_labels_fails_loudly_instead_of_passing(fens):
    report, _ = compute_quantization_parity(well_scaled_network(), fens)
    assert report.label_correlation_delta is None
    violations = threshold_violations(report, ParityThresholds(max_label_correlation_delta=0.005))
    assert violations and "no labels were given" in violations[0]


def test_label_identity_is_recorded_when_labels_are_given(fens, labels):
    with_labels, _ = compute_quantization_parity(well_scaled_network(), fens, labels)
    other, _ = compute_quantization_parity(well_scaled_network(), fens, labels + 1.0)
    assert len(with_labels.labels_sha256) == 64 and with_labels.labels_sha256 != other.labels_sha256
    assert compute_quantization_parity(well_scaled_network(), fens)[0].labels_sha256 is None


def test_input_validation(fens):
    with pytest.raises(ValueError, match="at least one FEN"):
        compute_quantization_parity(well_scaled_network(), [])
    with pytest.raises(ValueError, match="labels_cp has"):
        compute_quantization_parity(well_scaled_network(), fens, [1.0])


def test_no_thresholds_means_report_only_never_a_failure(fens):
    report, _ = compute_quantization_parity(poorly_scaled_network(), fens)
    assert threshold_violations(report, ParityThresholds()) == []


def test_export_pipeline_records_parity_in_the_manifest_and_exports_the_network_it_measured(tmp_path, fens, labels):
    canonical = well_scaled_network()
    result, report = quantize_validate_export(
        canonical, metadata(), [DatasetComposition("fixture", "public", 1.0)], tmp_path, fens, labels, LOCAL_GATE)

    manifest = json.loads(result.manifest_path.read_text())
    validate_manifest(manifest)
    assert manifest["quantization_parity"] == report.to_manifest_dict()
    assert manifest["quantization_parity"]["position_count"] == len(fens)
    assert len(manifest["quantization_parity"]["fen_corpus_sha256"]) == 64

    exported, _ = read_nnue(result.nnue_path)
    np.testing.assert_array_equal(eval_quantized_fens(exported, fens),
                                  eval_quantized_fens(compute_quantization_parity(canonical, fens)[1], fens))


def test_a_failing_gate_stops_the_export_before_anything_is_written(tmp_path, fens):
    output_dir = tmp_path / "out"
    with pytest.raises(QuantizationParityError) as raised:
        quantize_validate_export(poorly_scaled_network(), metadata(), [], output_dir, fens, thresholds=LOCAL_GATE)
    assert len(raised.value.violations) == 3
    assert raised.value.report.mae_cp > LOCAL_GATE.max_mae_cp
    assert not output_dir.exists() or not any(output_dir.iterdir())


def test_without_thresholds_the_pipeline_still_exports_and_records_parity(tmp_path, fens):
    result, report = quantize_validate_export(poorly_scaled_network(), metadata(), [], tmp_path, fens)
    assert json.loads(result.manifest_path.read_text())["quantization_parity"]["mae_cp"] == report.mae_cp


def test_unsafe_accumulator_bounds_still_stop_quantization_before_any_parity_claim(fens):
    # #264 compatibility: quantize() refuses a network whose accumulator could wrap, and the parity
    # pipeline must surface that instead of measuring a net the engine would not load.
    risky = synthetic_float_network(seed=4, ft_sigma=16, out_sigma=12, bias_mean=32000, bias_sigma=1)
    with pytest.raises(ValueError, match="accumulator"):
        compute_quantization_parity(risky, fens)
