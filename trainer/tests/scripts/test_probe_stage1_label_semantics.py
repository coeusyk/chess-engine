"""The label-semantics probe (issue #263). Runs on synthetic files only; the real datasets are never touched."""

import hashlib
import json
import random
from pathlib import Path

import pytest

from scripts.probe_stage1_label_semantics import main, probe

_PIECES = "PNBRQpnbrq"


def _fen(rng: random.Random, side: str) -> str:
    squares = rng.sample(range(8, 56), rng.randint(2, 9))  # never the back ranks, so kings stay legal
    board = {0: "K", 63: "k"}
    for square in squares:
        board[square] = rng.choice(_PIECES)
    ranks = []
    for rank in range(7, -1, -1):
        row, empty = "", 0
        for file in range(8):
            piece = board.get(rank * 8 + file)
            if piece is None:
                empty += 1
            else:
                row += (str(empty) if empty else "") + piece
                empty = 0
        ranks.append(row + (str(empty) if empty else ""))
    return "/".join(ranks) + f" {side} - -"


def _material_white_minus_black(fen: str) -> int:
    value = {"p": 1, "n": 3, "b": 3, "r": 5, "q": 9}
    return sum((value[c.lower()] if c.isupper() else -value[c.lower()]) for c in fen.split()[0] if c.lower() in value)


def _write(root: Path, perspective: str, rows: int = 1200, seed: int = 7, noise: float = 120.0) -> Path:
    rng = random.Random(seed)
    directory = root / f"{perspective}-data"
    directory.mkdir()
    lines = []
    for _ in range(rows):
        side = rng.choice("wb")
        fen = _fen(rng, side)
        white_view = 100 * _material_white_minus_black(fen) + rng.gauss(0, noise)
        cp = white_view if (perspective == "white" or side == "w") else -white_view
        lines.append(f"{fen},{int(cp)},")
    (directory / "stage1.csv").write_text("\n".join(lines) + "\n", encoding="ascii")
    (directory / "manifest.json").write_text(json.dumps({"stage": "public"}))
    return directory


def _tree_digest(root: Path) -> dict:
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(root.rglob("*")) if p.is_file()}


def test_white_perspective_labels_are_recognised(tmp_path):
    report = probe(_write(tmp_path, "white"))

    assert report["verdict"] == "white-perspective"
    assert report["white_to_move"]["correlation_with_white_minus_black_material"] > 0.5
    assert report["black_to_move"]["correlation_with_white_minus_black_material"] > 0.5
    assert report["black_to_move"]["correlation_with_mover_material_balance"] < 0


def test_mover_relative_labels_are_recognised(tmp_path):
    report = probe(_write(tmp_path, "mover"))

    assert report["verdict"] == "mover-relative"
    assert report["black_to_move"]["correlation_with_white_minus_black_material"] < 0
    assert report["black_to_move"]["correlation_with_mover_material_balance"] > 0.5


def test_labels_unrelated_to_material_are_inconclusive(tmp_path):
    rng = random.Random(3)
    directory = tmp_path / "noise"
    directory.mkdir()
    (directory / "stage1.csv").write_text(
        "\n".join(f"{_fen(rng, rng.choice('wb'))},{rng.randint(-300, 300)}," for _ in range(1200)) + "\n", encoding="ascii")

    assert probe(directory)["verdict"] == "inconclusive"
    assert main([str(directory)]) == 1


def test_too_few_rows_are_inconclusive_even_when_the_signal_is_clear(tmp_path):
    directory = _write(tmp_path, "white", rows=300)

    assert probe(directory)["verdict"] == "inconclusive"
    assert probe(directory, min_rows=50)["verdict"] == "white-perspective"


def test_the_probe_is_read_only_and_records_the_manifest_hash_but_no_path(tmp_path):
    directory = _write(tmp_path, "white")
    before = _tree_digest(tmp_path)

    report = probe(directory)

    assert _tree_digest(tmp_path) == before
    assert report["manifest_sha256"] == hashlib.sha256((directory / "manifest.json").read_bytes()).hexdigest()
    assert report["directory"] == "white-data"
    assert str(tmp_path) not in json.dumps(report)


def test_mate_only_rows_are_skipped_and_an_empty_directory_is_an_error(tmp_path):
    directory = _write(tmp_path, "white")
    with open(directory / "stage1.csv", "a", encoding="ascii") as handle:
        handle.write("4k3/8/8/8/8/8/3Q4/4K3 w - -,,3\n")

    assert probe(directory)["white_to_move"]["rows_with_cp"] > 0
    empty = tmp_path / "empty"
    empty.mkdir()
    with pytest.raises(ValueError, match="no \\*.csv shards"):
        probe(empty)
