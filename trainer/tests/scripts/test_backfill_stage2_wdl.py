from pathlib import Path

import numpy as np
import pytest

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef
from trainer.dataset.mmap_shard import read_shard
from scripts.backfill_stage2_wdl import (
    _LEGACY_SHARD_DTYPE_NO_WDL,
    _build_epd_outcome_map,
    _wdl_from_result,
    backfill,
)


# The correctness-critical case: PositionLabel.wdl and eval_cp are both
# mover-relative (positive/high = good for the side to move), so converting a
# White-perspective game result into wdl must flip sign when Black is to move --
# getting this backwards trains fine and evaluates fine, producing a silent,
# indistinguishable-from-a-real-null wrong result (phase4c-reranking-wdl-audit.md's
# own evidence-discipline concern, applied here explicitly).
@pytest.mark.parametrize(
    "side_to_move,result,expected",
    [
        ("w", "1-0", 1.0),  # White to move, White won -> mover won
        ("b", "1-0", 0.0),  # Black to move, White won -> mover lost
        ("w", "0-1", 0.0),  # White to move, Black won -> mover lost
        ("b", "0-1", 1.0),  # Black to move, Black won -> mover won
        ("w", "1/2-1/2", 0.5),
        ("b", "1/2-1/2", 0.5),
    ],
)
def test_wdl_from_result_sign_convention(side_to_move, result, expected):
    assert _wdl_from_result(side_to_move, result) == expected


def test_wdl_from_result_unrecognized_string_returns_none():
    assert _wdl_from_result("w", "*") is None


def _write_legacy_shard(records, path: Path) -> None:
    rows = []
    for record in records:
        fen_bytes = record.fen.encode("ascii")
        label = record.label
        rows.append(
            (
                fen_bytes,
                label.eval_cp if label.eval_cp is not None else 0,
                label.eval_cp is not None,
                label.eval_mate if label.eval_mate is not None else 0,
                label.eval_mate is not None,
                0,
                False,
                0,
                False,
                0,
                False,
            )
        )
    np.array(rows, dtype=_LEGACY_SHARD_DTYPE_NO_WDL).tofile(path)


def test_build_epd_outcome_map_drops_conflicting_duplicate_fens(tmp_path):
    epd_path = tmp_path / "quiet-labeled.epd"
    epd_path.write_text(
        'fenA c9 "1-0";\n'
        'fenA c9 "0-1";\n'  # conflicting duplicate -- must be dropped
        'fenB c9 "1/2-1/2";\n'
        'fenB c9 "1/2-1/2";\n'  # duplicate, same result -- not a conflict
        "not a c9 line at all\n",
        encoding="ascii",
    )
    outcome_map, conflict_count = _build_epd_outcome_map(epd_path)
    assert conflict_count == 1
    assert "fenA" not in outcome_map
    assert outcome_map["fenB"] == "1/2-1/2"


def test_backfill_preserves_every_record_and_attaches_wdl_where_matched(tmp_path):
    # FENs are 4-field (no halfmove/fullmove clock) -- matching the real production
    # Stage 2 shard's own stored convention (phase4c-reranking-wdl-audit.md SS5.3:
    # the shard's fen field has no trailing "0 1", and the EPD source's c9-annotated
    # lines are 4-field too), so the join is exercised realistically.
    legacy_path = tmp_path / "shard-0.bin"
    records = [
        PositionRecord(
            fen="4k3/8/8/8/8/8/4Q3/4K3 w - -",
            label=PositionLabel(eval_cp=123),
            metadata=PositionMetadata(),
        ),
        PositionRecord(
            fen="4k3/8/8/8/8/8/4q3/4K3 b - -",
            label=PositionLabel(eval_cp=-45),
            metadata=PositionMetadata(),
        ),
        PositionRecord(
            fen="8/8/8/8/8/8/8/unmatched w - -",
            label=PositionLabel(eval_mate=3),
            metadata=PositionMetadata(),
        ),
    ]
    _write_legacy_shard(records, legacy_path)

    epd_path = tmp_path / "quiet-labeled.epd"
    epd_path.write_text(
        '4k3/8/8/8/8/8/4Q3/4K3 w - - c9 "1-0";\n'
        '4k3/8/8/8/8/8/4q3/4K3 b - - c9 "1-0";\n',
        encoding="ascii",
    )

    output_dir = tmp_path / "backfilled"
    stats = backfill(legacy_path, epd_path, output_dir)

    assert stats["total_records"] == 3
    assert stats["matched_with_wdl"] == 2
    assert stats["unmatched_no_wdl"] == 1

    restored = list(read_shard(ShardRef(locator=str(output_dir / "shard-0.bin"))))
    assert len(restored) == 3
    # Original order preserved -- load-bearing for combine_and_split's fixed-seed
    # shuffle to reproduce the identical train/held-out split (audit SS5.3).
    assert [r.fen for r in restored] == [r.fen for r in records]
    assert restored[0].label.eval_cp == 123
    assert restored[0].label.wdl == 1.0  # White to move, White won
    assert restored[1].label.eval_cp == -45
    assert restored[1].label.wdl == 0.0  # Black to move, White won -> mover lost
    assert restored[2].label.eval_mate == 3
    assert restored[2].label.wdl is None  # unmatched FEN -- no wdl attached

    manifest_path = output_dir / "manifest.json"
    assert manifest_path.exists()
