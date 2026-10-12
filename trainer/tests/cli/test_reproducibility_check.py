"""Trainer reproducibility CI check (issue #198, architecture doc Section 12): runs
`run_pipeline()` twice with the same fixed-seed config/dataset and asserts the
reproducibility guarantees Section 10/Invariant 5 actually make.

Deliberately does NOT assert the two runs' `.nnue`/manifest files are byte-identical as
whole files -- `Exporter.export()` assigns a fresh `network_uuid` and
`created_at_epoch_seconds` on every call, by design (Section 5, D-6). Asserting whole-
file equality would either be always-false (a real bug signal masked by an expected
difference) or require weakening `export()`'s own frozen design. Instead this asserts
the two provable, in-scope guarantees separately: quantization determinism (Invariant
5 -- the int16 arrays themselves) and export-writer determinism (`_nnue_bytes` is a
pure function of its arguments, checked by supplying matching arguments to both runs'
outputs rather than relying on export() to have produced them by chance).
"""

import json

import numpy as np
import pytest

from trainer.cli.reproducibility_check import run_pipeline
from trainer.export.exporter import _nnue_bytes
from trainer.export.manifest_schema import validate_manifest


@pytest.fixture(scope="module")
def two_runs(tmp_path_factory):
    dir_a = tmp_path_factory.mktemp("run_a")
    dir_b = tmp_path_factory.mktemp("run_b")
    return run_pipeline(dir_a), run_pipeline(dir_b)


def test_quantized_weights_are_bit_identical_across_runs(two_runs):
    result_a, result_b = two_runs
    net_a, net_b = result_a.quantized_network, result_b.quantized_network

    np.testing.assert_array_equal(net_a.ft_weights, net_b.ft_weights)
    np.testing.assert_array_equal(net_a.ft_biases, net_b.ft_biases)
    np.testing.assert_array_equal(net_a.output_weights, net_b.output_weights)
    assert net_a.output_bias == net_b.output_bias
    assert (net_a.qa, net_a.qb, net_a.output_scale) == (net_b.qa, net_b.qb, net_b.output_scale)


def test_nnue_byte_writer_is_deterministic_given_matching_inputs(two_runs):
    result_a, result_b = two_runs
    net_a, net_b = result_a.quantized_network, result_b.quantized_network

    # Same fixed uuid/commit/timestamp fed to both -- isolates the writer's own
    # determinism from export()'s by-design-fresh identity fields (see module docstring).
    bytes_a = _nnue_bytes(net_a, network_uuid="fixed-for-test", trainer_commit="c" * 40,
                           created_at_epoch_seconds=0)
    bytes_b = _nnue_bytes(net_b, network_uuid="fixed-for-test", trainer_commit="c" * 40,
                           created_at_epoch_seconds=0)

    assert bytes_a == bytes_b


def test_run_a_manifest_is_schema_valid(two_runs):
    result_a, _ = two_runs
    manifest = json.loads(result_a.export_result.manifest_path.read_text())
    validate_manifest(manifest)  # must not raise


def test_run_b_manifest_is_schema_valid(two_runs):
    _, result_b = two_runs
    manifest = json.loads(result_b.export_result.manifest_path.read_text())
    validate_manifest(manifest)  # must not raise


def test_both_runs_produce_a_nonempty_nnue_file(two_runs):
    result_a, result_b = two_runs
    assert result_a.export_result.nnue_path.stat().st_size > 0
    assert result_b.export_result.nnue_path.stat().st_size > 0
