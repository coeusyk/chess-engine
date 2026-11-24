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


def test_drop_duplicate_positions_keeps_the_first_entry_and_ignores_clocks(capsys):
    from scripts.train_candidate_net import _drop_duplicate_positions

    stage1_copy = _record("4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")
    stage2_copy = _record("4k3/8/8/8/8/8/4Q3/4K3 w - - 9 55")  # same position, different clocks
    tagged = [
        (stage1_copy, "s1", "public"),
        (stage2_copy, "s2", "sf-labeled"),
        (_record("4k3/8/8/8/8/8/4Q3/4K3 b - - 0 1"), "s2", "sf-labeled"),
    ]
    result = _drop_duplicate_positions(tagged)
    assert [(entry[1]) for entry in result] == ["s1", "s2"]
    assert result[0][0] is stage1_copy
    assert "removed 1 duplicate" in capsys.readouterr().out


def test_combine_and_split_never_puts_one_position_in_both_the_training_and_held_out_sets(monkeypatch, tmp_path):
    # Stage 1 and Stage 2 both hold the same position under different clocks, plus enough distinct
    # positions for a held-out set to exist. Providers are replaced by fixed lists so no dataset is read.
    from scripts import train_candidate_net as tcn
    from trainer.dataset.transform import position_key

    shared = "4k3/8/8/8/8/8/4Q3/4K3 w - - {} 1"
    stage1_records = [_record(shared.format(0))] + [_record(f"4k3/8/8/8/8/8/{chr(97 + i)}7/4K3 w - - 0 1") for i in range(8)]
    stage2_records = [_record(shared.format(7))] + [_record(f"4k3/8/8/8/8/8/{chr(97 + i)}6/4K3 w - - 0 1") for i in range(8)]

    class _Fake:
        def __init__(self, records, identifier):
            self._records, self._identifier = records, identifier

        def metadata(self):
            return type("M", (), {"identifier": self._identifier})()

    fakes = {"s1": _Fake(stage1_records, "s1"), "s2": _Fake(stage2_records, "s2")}
    monkeypatch.setattr(tcn, "require_mover_relative_stage1", lambda directory: None)
    monkeypatch.setattr(tcn, "TextDatasetProvider", lambda directory, identifier, source_ref: fakes["s1"])
    monkeypatch.setattr(tcn, "StockfishLabeledProvider", lambda directory, identifier, source_ref: fakes["s2"])
    monkeypatch.setattr(tcn, "_load_all", lambda provider: list(provider._records))

    training, held_out, _ = tcn.combine_and_split(tmp_path / "a", tmp_path / "b", seed=3, drop_duplicates=True)

    keys = [position_key(r.fen) for r in training + held_out]
    assert len(keys) == len(set(keys)), "a position must appear once across the whole split"
    assert len(training) + len(held_out) == 17  # 18 loaded, one duplicate removed
    assert held_out, "the fixture must be large enough to produce a held-out set"

    # Default (historical) behaviour is unchanged: nothing is removed, so a seeded split that a frozen
    # checkpoint was trained on is reproduced exactly.
    kept, held, _ = tcn.combine_and_split(tmp_path / "a", tmp_path / "b", seed=3)
    assert len(kept) + len(held) == 18
