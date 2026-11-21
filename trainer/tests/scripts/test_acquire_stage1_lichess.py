"""Tests run entirely against a locally-generated `.jsonl.zst` fixture (never the
real network) -- built at test time via `zstandard`'s compressor rather than
committing a binary fixture file, matching this repo's preference for plain-text,
human-diffable test fixtures wherever practical.
"""

import json
import os
from pathlib import Path

import pytest

if os.environ.get("REQUIRE_ACQUIRE_TESTS") == "1":
    # Trainer CI (issue #269): a missing `zstandard` must be a hard failure, never a
    # silent skip of every Stage 1 test.
    import zstandard
else:
    zstandard = pytest.importorskip(
        "zstandard", reason="zstandard is an 'acquire'-extra-only dependency (trainer/pyproject.toml) -- "
                            "not installed under the project's default `--extra dev --extra train` test "
                            "invocation (issue #202 Required Validation); these tests skip rather than "
                            "error when it's absent, install via `uv sync --extra acquire` to run them."
    )

from scripts.acquire_stage1_lichess import acquire, stream_records
from trainer.dataset.text_provider import TextDatasetProvider

STARTPOS = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"


def _line(fen: str, evals: list) -> str:
    return json.dumps({"fen": fen, "evals": evals})


def _write_fixture(path: Path, lines: list) -> Path:
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    path.write_bytes(zstandard.ZstdCompressor().compress(payload))
    return path


def _sample_lines() -> list:
    return [
        # Two eval entries at different depths -- the higher-depth one (depth=30,
        # cp=25) must win, not the first-listed or the higher-cp one.
        _line(STARTPOS, [
            {"pvs": [{"cp": 999, "line": "e2e4"}], "knodes": 10, "depth": 5},
            {"pvs": [{"cp": 25, "line": "e2e4 e7e5"}], "knodes": 500, "depth": 30},
        ]),
        # A forced-mate position: mate must be captured, cp must stay absent.
        _line("4k3/8/8/8/8/8/4Q3/4K3 w - -", [
            {"pvs": [{"mate": 3, "line": "e2e7"}], "knodes": 50, "depth": 20},
        ]),
        # No evals at all -- must be skipped, not fabricated as a zero eval.
        _line("8/8/8/8/8/8/8/8 w - -", []),
        # An eval entry with an empty pvs list -- must also be skipped.
        _line("8/8/8/8/8/8/8/k6K w - -", [{"pvs": [], "knodes": 1, "depth": 1}]),
    ]


def test_stream_records_selects_highest_depth_eval(tmp_path):
    fixture = _write_fixture(tmp_path / "dump.jsonl.zst", _sample_lines())

    records = list(stream_records(str(fixture)))

    assert len(records) == 2  # the two skip cases above are excluded
    assert records[0].fen == STARTPOS
    assert records[0].eval_cp == 25  # depth=30 entry, not depth=5's 999
    assert records[0].eval_mate is None
    assert records[1].eval_cp is None
    assert records[1].eval_mate == 3


def test_acquire_stops_at_target_count_without_reading_the_rest(tmp_path):
    lines = _sample_lines() + [_line(f"8/8/8/8/8/8/8/{i}K6 w - -", [
        {"pvs": [{"cp": i}], "knodes": 1, "depth": 1},
    ]) for i in range(1, 50)]
    fixture = _write_fixture(tmp_path / "dump.jsonl.zst", lines)

    result = acquire(str(fixture), target_count=3, output_dir=tmp_path / "out",
                      dataset_identifier="test-stage1")

    assert result.position_count == 3
    rows = result.csv_path.read_text().splitlines()
    assert len(rows) == 3


def test_acquire_writes_normalized_csv_rows(tmp_path):
    fixture = _write_fixture(tmp_path / "dump.jsonl.zst", _sample_lines())

    result = acquire(str(fixture), target_count=10, output_dir=tmp_path / "out",
                      dataset_identifier="test-stage1")

    rows = result.csv_path.read_text().splitlines()
    assert rows[0] == f"{STARTPOS},25,"
    assert rows[1] == "4k3/8/8/8/8/8/4Q3/4K3 w - -,,3"


def test_acquire_manifest_carries_provenance(tmp_path):
    fixture = _write_fixture(tmp_path / "dump.jsonl.zst", _sample_lines())

    result = acquire(str(fixture), target_count=10, output_dir=tmp_path / "out",
                      dataset_identifier="test-stage1")

    manifest = json.loads(result.manifest_path.read_text())
    assert manifest["dataset_identifier"] == "test-stage1"
    assert manifest["stage"] == "public"
    assert manifest["source_url"] == str(fixture)
    assert manifest["position_count"] == 2
    assert manifest["output_sha256"]
    assert manifest["trainer_commit"]
    assert manifest["created_at_epoch_seconds"] > 0


def test_acquire_rejects_non_positive_target_count(tmp_path):
    fixture = _write_fixture(tmp_path / "dump.jsonl.zst", _sample_lines())
    import pytest
    with pytest.raises(ValueError, match="target_count must be positive"):
        acquire(str(fixture), target_count=0, output_dir=tmp_path / "out", dataset_identifier="x")



# ---------------------------------------------------------------------------
# Label perspective (issue #263). Lichess scores are White-perspective; the
# acquisition adapter must emit mover-relative labels.
#
# Independently verifiable positions (no engine needed):
#   BLACK_MATE_IN_1   Black to move, Ra8-a1# (White king g1 boxed in by its own f2/g2/h2 pawns).
#   WHITE_MATE_IN_1   White to move, Ra1-a8# (Black king g8 boxed in by its own f7/g7/h7 pawns).
#   *_UP_QUEEN / *_DOWN_QUEEN   queen vs. bare king, material is unambiguous.
# ---------------------------------------------------------------------------

BLACK_MATE_IN_1 = "r5k1/8/8/8/8/8/5PPP/6K1 b - -"
WHITE_MATE_IN_1 = "6k1/5ppp/8/8/8/8/8/R5K1 w - -"
BLACK_UP_QUEEN = "4k3/3q4/8/8/8/8/8/4K3 b - -"
BLACK_DOWN_QUEEN = "4k3/8/8/8/8/8/3Q4/4K3 b - -"
WHITE_UP_QUEEN = "4k3/8/8/8/8/8/3Q4/4K3 w - -"
WHITE_DOWN_QUEEN = "4k3/3q4/8/8/8/8/8/4K3 w - -"


def _one(tmp_path, fen, **pv):
    fixture = _write_fixture(tmp_path / "d.jsonl.zst", [_line(fen, [{"pvs": [pv], "knodes": 1, "depth": 20}])])
    (record,) = list(stream_records(str(fixture)))
    return record


@pytest.mark.parametrize("fen,lichess_cp,expected_cp", [
    (WHITE_UP_QUEEN, 900, 900),          # White to move, White ahead: unchanged
    (WHITE_DOWN_QUEEN, -900, -900),      # White to move, White behind: negative preserved
    (BLACK_UP_QUEEN, -900, 900),         # Black to move, Black ahead: Lichess says -900
    (BLACK_DOWN_QUEEN, 900, -900),       # Black to move, Black behind: Lichess says +900
])
def test_cp_is_mover_relative(tmp_path, fen, lichess_cp, expected_cp):
    record = _one(tmp_path, fen, cp=lichess_cp)
    assert record.eval_cp == expected_cp
    assert record.eval_mate is None


@pytest.mark.parametrize("fen,lichess_mate,expected_mate", [
    (WHITE_MATE_IN_1, 1, 1),             # White mates: positive for the mover
    (BLACK_MATE_IN_1, -1, 1),            # Black mates: Lichess says -1, mover sees +1
    # The two bare-king rows below only pin the sign convention (those positions are not
    # real forced mates); the genuine, verifiable mates are the two above.
    ("4k3/8/8/8/8/8/8/4K3 w - -", -2, -2),
    ("4k3/8/8/8/8/8/8/4K3 b - -", 2, -2),
])
def test_mate_is_mover_relative(tmp_path, fen, lichess_mate, expected_mate):
    record = _one(tmp_path, fen, mate=lichess_mate)
    assert record.eval_mate == expected_mate
    assert record.eval_cp is None


def test_four_field_and_six_field_fens_both_classify_side_to_move(tmp_path):
    four = _one(tmp_path, "4k3/3q4/8/8/8/8/8/4K3 b - -", cp=-300)
    six = _one(tmp_path, "4k3/3q4/8/8/8/8/8/4K3 b - - 0 1", cp=-300)
    assert four.eval_cp == six.eval_cp == 300


def test_malformed_side_to_move_is_rejected_not_guessed(tmp_path):
    fixture = _write_fixture(tmp_path / "d.jsonl.zst", [
        _line("4k3/8/8/8/8/8/8/4K3 x - -", [{"pvs": [{"cp": 10}], "knodes": 1, "depth": 1}]),
    ])
    with pytest.raises(ValueError, match="side to move"):
        list(stream_records(str(fixture)))


def test_acquire_writes_mover_relative_csv_and_manifest_marker(tmp_path):
    fixture = _write_fixture(tmp_path / "d.jsonl.zst", [
        _line(BLACK_UP_QUEEN, [{"pvs": [{"cp": -900}], "knodes": 1, "depth": 20}]),
        _line(BLACK_MATE_IN_1, [{"pvs": [{"mate": -1}], "knodes": 1, "depth": 20}]),
        _line(WHITE_UP_QUEEN, [{"pvs": [{"cp": 900}], "knodes": 1, "depth": 20}]),
    ])

    result = acquire(str(fixture), target_count=10, output_dir=tmp_path / "out", dataset_identifier="t")

    assert result.csv_path.read_text().splitlines() == [
        f"{BLACK_UP_QUEEN},900,",
        f"{BLACK_MATE_IN_1},,1",
        f"{WHITE_UP_QUEEN},900,",
    ]
    assert json.loads(result.manifest_path.read_text())["label_perspective"] == "mover-relative"


def test_text_provider_reads_labels_verbatim_without_a_second_flip(tmp_path):
    # Readers must stay perspective-agnostic: Stage 2/3 text sources are already
    # mover-relative, so TextDatasetProvider may never flip anything itself.
    fixture = _write_fixture(tmp_path / "d.jsonl.zst", [
        _line(BLACK_UP_QUEEN, [{"pvs": [{"cp": -900}], "knodes": 1, "depth": 20}]),
    ])
    out = tmp_path / "out"
    acquire(str(fixture), target_count=1, output_dir=out, dataset_identifier="t")

    provider = TextDatasetProvider(out, identifier="t", source_ref="test")
    (shard,) = list(provider.shards())
    (position,) = list(provider.positions(shard))
    assert position.label.eval_cp == 900

    # A Black-to-move row from an already mover-relative source passes through unchanged.
    already_relative = tmp_path / "rel"
    already_relative.mkdir()
    (already_relative / "s.csv").write_text(f"{BLACK_UP_QUEEN},-50,\n", encoding="ascii")
    provider2 = TextDatasetProvider(already_relative, identifier="r", source_ref="test")
    (shard2,) = list(provider2.shards())
    assert next(iter(provider2.positions(shard2))).label.eval_cp == -50
