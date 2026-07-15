import pytest

from trainer.dataset import validate_dataset_manifest


def _valid_manifest(**overrides):
    base = {
        "dataset_identifier": "stage1-lichess-2026-07-15",
        "stage": "public",
        "trainer_commit": "abc1234",
        "created_at_epoch_seconds": 1_752_000_000,
    }
    base.update(overrides)
    return base


def test_valid_stage1_style_manifest_passes():
    validate_dataset_manifest(_valid_manifest(source_url="https://example.invalid", position_count=20000))


def test_valid_stage2_style_manifest_passes():
    # Mirrors stockfish_label.py::label_positions's actual manifest shape verbatim --
    # this validator must accept it unmodified (issue #202 Explicit Non-Scope: no
    # changes to stockfish_label.py's own logic).
    manifest = {
        "dataset_identifier": "stage2-quiet-2026-07-15",
        "stage": "sf-labeled",
        "engine_path": "/usr/games/stockfish",
        "engine_uci_id": "Stockfish 16",
        "engine_binary_sha256": "f" * 64,
        "search_policy": {"nodes": 25000, "depth": None, "threads": 1},
        "timeout_seconds": 30.0,
        "trainer_commit": "abc1234",
        "created_at_epoch_seconds": 1_752_000_000,
        "labeled_count": 20000,
        "skipped_count": 3,
    }
    validate_dataset_manifest(manifest)


def test_missing_required_field_is_rejected():
    manifest = _valid_manifest()
    del manifest["trainer_commit"]
    with pytest.raises(ValueError, match="trainer_commit"):
        validate_dataset_manifest(manifest)


def test_wrong_type_is_rejected():
    with pytest.raises(ValueError, match="created_at_epoch_seconds"):
        validate_dataset_manifest(_valid_manifest(created_at_epoch_seconds="not-a-number"))


def test_bool_is_rejected_for_int_field():
    # bool is an int subclass in Python -- must not silently pass an int check.
    with pytest.raises(ValueError, match="created_at_epoch_seconds"):
        validate_dataset_manifest(_valid_manifest(created_at_epoch_seconds=True))


def test_unknown_stage_is_rejected():
    with pytest.raises(ValueError, match="stage"):
        validate_dataset_manifest(_valid_manifest(stage="not-a-real-stage"))


def test_reports_multiple_violations_together():
    manifest = {"stage": "public"}
    with pytest.raises(ValueError) as exc_info:
        validate_dataset_manifest(manifest)
    message = str(exc_info.value)
    assert "dataset_identifier" in message
    assert "trainer_commit" in message
    assert "created_at_epoch_seconds" in message
