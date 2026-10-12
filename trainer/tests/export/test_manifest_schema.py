import json
from pathlib import Path

import pytest

from trainer.export.exporter import DatasetComposition, export
from trainer.export.manifest_schema import validate_manifest

from tests.export._fixtures import metadata as _metadata, quantized_network as _quantized_network


def test_a_real_exported_manifest_validates(tmp_path: Path):
    result = export(
        _quantized_network(), _metadata(),
        dataset_composition=[DatasetComposition(identifier="stage1-text-v1", stage="public", proportion=1.0)],
        output_dir=tmp_path,
    )
    manifest = json.loads(result.manifest_path.read_text())

    validate_manifest(manifest)  # must not raise


def test_missing_field_is_reported():
    manifest = _valid_manifest()
    del manifest["nnue_sha256"]

    with pytest.raises(ValueError, match="nnue_sha256"):
        validate_manifest(manifest)


def test_wrong_type_is_reported():
    manifest = _valid_manifest()
    manifest["hidden_width"] = "2"

    with pytest.raises(ValueError, match="hidden_width"):
        validate_manifest(manifest)


def test_label_engine_version_none_is_valid():
    manifest = _valid_manifest()
    manifest["label_engine_version"] = None

    validate_manifest(manifest)  # must not raise


def test_malformed_dataset_composition_entry_is_reported():
    manifest = _valid_manifest()
    manifest["dataset_composition"] = [{"identifier": "x", "stage": "public"}]  # missing proportion

    with pytest.raises(ValueError, match="proportion"):
        validate_manifest(manifest)


def test_multiple_violations_all_reported():
    manifest = _valid_manifest()
    del manifest["nnue_sha256"]
    manifest["hidden_width"] = "2"

    with pytest.raises(ValueError) as exc_info:
        validate_manifest(manifest)
    assert "nnue_sha256" in str(exc_info.value)
    assert "hidden_width" in str(exc_info.value)


def _valid_manifest() -> dict:
    return {
        "format_version": 1,
        "architecture_id": 1,
        "feature_set_id": 1,
        "hidden_width": 2,
        "quant_version": 1,
        "network_uuid": "11111111-1111-1111-1111-111111111111",
        "trainer_version": "0.1.0",
        "trainer_commit": "a" * 40,
        "created_at_epoch_seconds": 123456789,
        "dataset_composition": [{"identifier": "stage1-text-v1", "stage": "public", "proportion": 1.0}],
        "training_config": {"hidden_width": 2},
        "training_seed": 42,
        "label_engine_version": None,
        "nnue_sha256": "a" * 64,
    }
