import json
import struct
from pathlib import Path

import numpy as np
import pytest
import torch

from trainer.export.canonical import ARCHITECTURE_ID, FEATURE_SET_ID, FEATURES_PER_PERSPECTIVE
from trainer.export.exporter import DatasetComposition, MAGIC, QUANT_VERSION, _nnue_bytes, _write_utf, export
from trainer.export.canonical import QuantizedCanonicalNetwork
from trainer.reproducibility.experiment_metadata import ExperimentMetadata

from tests.export._fixtures import metadata as _metadata, quantized_network as _quantized_network


def test_write_utf_matches_java_writeutf_length_prefix_format():
    encoded = _write_utf("abc")
    length = struct.unpack(">H", encoded[:2])[0]
    assert length == 3
    assert encoded[2:] == b"abc"


def test_write_utf_rejects_non_ascii():
    with pytest.raises(ValueError, match="ASCII-only"):
        _write_utf("café")


def test_nnue_bytes_field_layout_matches_java_header_exactly():
    network = _quantized_network(hidden_width=2)
    raw = _nnue_bytes(network, network_uuid="11111111-1111-1111-1111-111111111111",
                       trainer_commit="deadbeefcafefeed", created_at_epoch_seconds=123456789)

    offset = 0
    assert raw[offset : offset + 4] == MAGIC
    offset += 4

    def read_i32():
        nonlocal offset
        value = struct.unpack(">i", raw[offset : offset + 4])[0]
        offset += 4
        return value

    assert read_i32() == 1  # formatVersion
    assert read_i32() == ARCHITECTURE_ID
    assert read_i32() == FEATURE_SET_ID
    assert read_i32() == 2  # hiddenWidth
    assert read_i32() == QUANT_VERSION
    assert read_i32() == 127  # qa
    assert read_i32() == 64  # qb
    assert read_i32() == 400  # outputScale

    uuid_len = struct.unpack(">H", raw[offset : offset + 2])[0]
    offset += 2
    uuid_bytes = raw[offset : offset + uuid_len]
    offset += uuid_len
    assert uuid_bytes.decode("utf-8") == "11111111-1111-1111-1111-111111111111"

    commit_len = struct.unpack(">H", raw[offset : offset + 2])[0]
    offset += 2
    commit_bytes = raw[offset : offset + commit_len]
    offset += commit_len
    # Header carries the *short* (8-char) commit, not the full 16-char input here.
    assert commit_bytes.decode("utf-8") == "deadbeef"

    created_at = struct.unpack(">q", raw[offset : offset + 8])[0]
    offset += 8
    assert created_at == 123456789

    ft_weights_count = FEATURES_PER_PERSPECTIVE * 2
    ft_weights = np.frombuffer(raw[offset : offset + ft_weights_count * 2], dtype=">i2")
    offset += ft_weights_count * 2
    np.testing.assert_array_equal(ft_weights.reshape(FEATURES_PER_PERSPECTIVE, 2), network.ft_weights)

    ft_biases = np.frombuffer(raw[offset : offset + 2 * 2], dtype=">i2")
    offset += 2 * 2
    np.testing.assert_array_equal(ft_biases, network.ft_biases)

    output_weights = np.frombuffer(raw[offset : offset + 4 * 2], dtype=">i2")
    offset += 4 * 2
    np.testing.assert_array_equal(output_weights.reshape(2, 2), network.output_weights)

    output_bias = struct.unpack(">i", raw[offset : offset + 4])[0]
    offset += 4
    assert output_bias == 7

    assert offset == len(raw)  # no trailing checksum in the binary


def test_nnue_bytes_rejects_non_positive_qa():
    network = _quantized_network()
    object.__setattr__(network, "qa", 0)
    with pytest.raises(ValueError, match="qa must be positive"):
        _nnue_bytes(network, "u", "c" * 8, 0)


def test_nnue_bytes_rejects_non_positive_qb():
    network = _quantized_network()
    object.__setattr__(network, "qb", -1)
    with pytest.raises(ValueError, match="qb must be positive"):
        _nnue_bytes(network, "u", "c" * 8, 0)


def test_export_writes_nnue_and_manifest_with_matching_uuid(tmp_path: Path):
    result = export(
        _quantized_network(),
        _metadata(),
        dataset_composition=[DatasetComposition(identifier="stage1-text-v1", stage="public", proportion=1.0)],
        output_dir=tmp_path,
    )

    assert result.nnue_path.exists()
    assert result.manifest_path.exists()
    assert result.nnue_path.name == f"{result.network_uuid}.nnue"
    assert result.manifest_path.name == f"{result.network_uuid}.json"

    manifest = json.loads(result.manifest_path.read_text())
    assert manifest["network_uuid"] == result.network_uuid
    assert manifest["trainer_commit"] == "a" * 40  # full commit, not truncated
    assert manifest["dataset_composition"] == [{"identifier": "stage1-text-v1", "stage": "public", "proportion": 1.0}]
    assert manifest["training_config"]["hidden_width"] == 2
    assert manifest["training_seed"] == 42
    assert manifest["quant_version"] == QUANT_VERSION
    assert manifest["trainer_version"] == "0.1.0"
    assert manifest["label_engine_version"] is None


def test_export_manifest_sha256_matches_actual_nnue_file(tmp_path: Path):
    import hashlib

    result = export(_quantized_network(), _metadata(), dataset_composition=[], output_dir=tmp_path)
    manifest = json.loads(result.manifest_path.read_text())

    actual_sha256 = hashlib.sha256(result.nnue_path.read_bytes()).hexdigest()
    assert manifest["nnue_sha256"] == actual_sha256


def test_export_produces_a_fresh_uuid_each_call(tmp_path: Path):
    first = export(_quantized_network(), _metadata(), dataset_composition=[], output_dir=tmp_path)
    second = export(_quantized_network(), _metadata(), dataset_composition=[], output_dir=tmp_path)
    assert first.network_uuid != second.network_uuid


def test_export_accepts_an_explicit_label_engine_version(tmp_path: Path):
    result = export(
        _quantized_network(), _metadata(), dataset_composition=[], output_dir=tmp_path,
        label_engine_version="stockfish-16",
    )
    manifest = json.loads(result.manifest_path.read_text())
    assert manifest["label_engine_version"] == "stockfish-16"


def test_export_rejects_non_positive_qa_end_to_end(tmp_path: Path):
    network = _quantized_network()
    object.__setattr__(network, "qa", 0)
    with pytest.raises(ValueError, match="qa must be positive"):
        export(network, _metadata(), dataset_composition=[], output_dir=tmp_path)
