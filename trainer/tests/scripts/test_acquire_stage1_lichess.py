"""Tests run entirely against a locally-generated `.jsonl.zst` fixture (never the
real network) -- built at test time via `zstandard`'s compressor rather than
committing a binary fixture file, matching this repo's preference for plain-text,
human-diffable test fixtures wherever practical.
"""

import json
from pathlib import Path

import pytest

zstandard = pytest.importorskip(
    "zstandard", reason="zstandard is an 'acquire'-extra-only dependency (trainer/pyproject.toml) -- "
                        "not installed under the project's default `--extra dev --extra train` test "
                        "invocation (issue #202 Required Validation); these tests skip rather than "
                        "error when it's absent, install via `uv sync --extra acquire` to run them."
)

from scripts.acquire_stage1_lichess import acquire, stream_records

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
