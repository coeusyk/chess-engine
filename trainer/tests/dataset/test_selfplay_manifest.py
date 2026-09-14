"""#210 section 17/20 -- self-play dataset-generation manifest schema."""

import pytest

from trainer.dataset.selfplay_manifest import (
    MANIFEST_VERSION,
    validate_selfplay_manifest,
    verify_manifest_against_shards,
)

SHA_A = "a" * 64
SHA_B = "b" * 64
SHA_SHARD = "c" * 64


def _base_manifest(**overrides) -> dict:
    manifest = {
        "manifest_version": MANIFEST_VERSION,
        "dataset_identifier": "test-selfplay",
        "created_at_epoch_seconds": 1_700_000_000,
        "output_shards": [{"path": "shard-0.bin", "sha256": SHA_SHARD, "record_count": 10}],
        "game_count": 2,
        "record_count": 10,
        "sources": [
            {
                "path": "a.vspr",
                "sha256": SHA_A,
                "run_id": "0" * 32,
                "generator_network_uuid": "net-1",
                "generator_network_sha256": "0" * 64,
                "engine_build_id": "build-1",
                "format_version": 1,
            }
        ],
        "game_id_mapping": {
            "rule": "first-occurrence-in-file-order",
            "ranges": [{"run_id": "0" * 32, "raw_game_id_start": 1, "raw_game_id_end": 2, "dataset_local_start": 0}],
        },
        "assessment": {"status": "unassessed", "outcome": None, "evidence_ref": None},
    }
    manifest.update(overrides)
    return manifest


def test_unassessed_is_valid():
    validate_selfplay_manifest(_base_manifest())  # must not raise


def test_assessed_approved_is_valid():
    m = _base_manifest(assessment={"status": "assessed", "outcome": "approved", "evidence_ref": "issue#301"})
    validate_selfplay_manifest(m)  # must not raise


def test_assessed_rejected_is_valid():
    m = _base_manifest(assessment={"status": "assessed", "outcome": "rejected", "evidence_ref": "issue#302"})
    validate_selfplay_manifest(m)  # must not raise


def test_assessed_without_evidence_is_invalid():
    m = _base_manifest(assessment={"status": "assessed", "outcome": "approved", "evidence_ref": None})
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_unassessed_with_outcome_is_invalid():
    m = _base_manifest(assessment={"status": "unassessed", "outcome": "approved", "evidence_ref": None})
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_unassessed_with_evidence_is_invalid():
    m = _base_manifest(assessment={"status": "unassessed", "outcome": None, "evidence_ref": "issue#1"})
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_duplicate_source_sha256_is_invalid():
    m = _base_manifest()
    m["sources"] = m["sources"] + [dict(m["sources"][0])]
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_bad_checksum_syntax_is_invalid():
    m = _base_manifest()
    m["sources"][0]["sha256"] = "not-hex"
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_unknown_manifest_version_is_invalid():
    m = _base_manifest(manifest_version=999)
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_missing_dataset_identity_is_invalid():
    m = _base_manifest()
    del m["dataset_identifier"]
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_invalid_assessment_status_is_invalid():
    m = _base_manifest(assessment={"status": "approved", "outcome": None, "evidence_ref": None})
    with pytest.raises(ValueError):
        validate_selfplay_manifest(m)


def test_round_trip_through_json():
    import json

    m = _base_manifest()
    round_tripped = json.loads(json.dumps(m))
    validate_selfplay_manifest(round_tripped)  # must not raise
    assert round_tripped == m


def test_verify_manifest_against_shards_detects_checksum_mismatch(tmp_path):
    shard_path = tmp_path / "shard-0.bin"
    shard_path.write_bytes(b"real shard bytes")
    import hashlib

    real_sha = hashlib.sha256(b"real shard bytes").hexdigest()
    m = _base_manifest(output_shards=[{"path": "shard-0.bin", "sha256": real_sha, "record_count": 10}])
    verify_manifest_against_shards(m, tmp_path)  # must not raise

    m_bad = _base_manifest(output_shards=[{"path": "shard-0.bin", "sha256": SHA_SHARD, "record_count": 10}])
    with pytest.raises(ValueError):
        verify_manifest_against_shards(m_bad, tmp_path)


def test_verify_manifest_against_shards_detects_missing_file(tmp_path):
    m = _base_manifest()
    with pytest.raises(ValueError):
        verify_manifest_against_shards(m, tmp_path)
