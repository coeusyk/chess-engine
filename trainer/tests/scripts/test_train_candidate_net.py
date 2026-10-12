from scripts.train_candidate_net import report_dataset_quality
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
