import json

import pytest

from scripts.train_candidate_net import combine_and_split, report_dataset_quality
from trainer.dataset.lichess_perspective import UnsafeStage1PerspectiveError
from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord


def _record(fen: str) -> PositionRecord:
    return PositionRecord(fen=fen, label=PositionLabel(eval_cp=0), metadata=PositionMetadata())


_OPENING = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
_ENDGAME_WHITE = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
_ENDGAME_BLACK = "4k3/8/8/8/8/8/4Q3/4K3 b - - 0 1"


def test_report_dataset_quality_does_not_mutate_or_reorder_input():
    records = [_record(_OPENING), _record(_OPENING), _record(_ENDGAME_WHITE)]
    before = list(records)

    report_dataset_quality(records)

    assert records == before


def test_report_dataset_quality_prints_expected_counts(capsys):
    records = [_record(_OPENING), _record(_OPENING), _record(_ENDGAME_WHITE), _record(_ENDGAME_BLACK)]

    report_dataset_quality(records)

    output = capsys.readouterr().out
    assert "total=4" in output
    assert "count=1 rate=25.00%" in output  # one exact duplicate (the repeated opening FEN)
    assert "'opening': 2" in output
    assert "'endgame': 2" in output
    assert "'w': 3" in output
    assert "'b': 1" in output


def test_combine_and_split_refuses_legacy_stage1_before_reading_any_label(tmp_path):
    stage1, stage2 = tmp_path / "s1", tmp_path / "s2"
    stage1.mkdir()
    (stage1 / "manifest.json").write_text(json.dumps({"stage": "public"}))  # legacy: no marker
    # stage2_dir deliberately does not exist: the Stage 1 gate must fire first.
    with pytest.raises(UnsafeStage1PerspectiveError, match="legacy White-perspective"):
        combine_and_split(stage1, stage2, seed=1)


def _fixture_records():
    from pathlib import Path
    from trainer.dataset.text_provider import TextDatasetProvider
    provider = TextDatasetProvider(directory=Path(__file__).parent.parent / "fixtures", identifier="x", source_ref="x")
    return list(provider.positions(next(iter(provider.shards()))))


def test_train_quantize_export_records_parity_for_the_held_out_positions(tmp_path):
    # Issue #265: the real train -> quantize -> export script path measures float32-versus-quantized
    # parity on the held-out positions and writes it into the provenance manifest.
    from scripts.train_candidate_net import train_quantize_export
    from trainer.export.exporter import DatasetComposition
    from trainer.export.manifest_schema import validate_manifest

    records = _fixture_records()
    held_out, training = records[:3], records[3:]
    result, _ = train_quantize_export(
        training, [DatasetComposition("x", "public", 1.0)], k=1.0, seed=1, steps=2, batch_size=2,
        learning_rate=0.01, output_dir=tmp_path, held_out_records=held_out, log_interval=1)

    manifest = json.loads(result.manifest_path.read_text())
    validate_manifest(manifest)
    assert manifest["quantization_parity"]["position_count"] == len(held_out)
    assert manifest["quantization_parity"]["label_correlation_float"] is not None


def test_train_quantize_export_without_held_out_positions_writes_no_parity_block(tmp_path):
    from scripts.train_candidate_net import train_quantize_export
    from trainer.export.exporter import DatasetComposition

    result, _ = train_quantize_export(
        _fixture_records(), [DatasetComposition("x", "public", 1.0)], k=1.0, seed=1, steps=2, batch_size=2,
        learning_rate=0.01, output_dir=tmp_path, held_out_records=None, log_interval=1)

    assert "quantization_parity" not in json.loads(result.manifest_path.read_text())
