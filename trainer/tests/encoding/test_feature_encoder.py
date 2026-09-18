import json
from pathlib import Path

import pytest

from trainer.encoding.feature_encoder import BLACK, WHITE, active_feature_indices, feature_index
from trainer.encoding.feature_spec import repo_root

CORPUS_PATH = repo_root(Path(__file__).resolve()) / "docs" / "architecture" / "feature-spec" / "parity-corpus-v1.json"


def _load_corpus():
    with open(CORPUS_PATH, "r", encoding="utf-8") as f:
        return json.load(f)["positions"]


CORPUS = _load_corpus()


@pytest.mark.parametrize("position", CORPUS, ids=[p["fen"] for p in CORPUS])
def test_matches_java_feature_extractor_white_perspective(position):
    assert active_feature_indices(position["fen"], WHITE) == position["white_indices"]


@pytest.mark.parametrize("position", CORPUS, ids=[p["fen"] for p in CORPUS])
def test_matches_java_feature_extractor_black_perspective(position):
    assert active_feature_indices(position["fen"], BLACK) == position["black_indices"]


def test_startpos_is_symmetric_across_perspectives():
    # Symmetric starting position -> white's and black's perspectives must look
    # numerically identical, the same cross-check FeatureIndexParityTest.java runs.
    startpos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    white = active_feature_indices(startpos, WHITE)
    black = active_feature_indices(startpos, BLACK)
    assert white == black


def test_every_corpus_position_produces_well_formed_indices():
    for position in CORPUS:
        piece_count = sum(1 for ch in position["fen"].split(" ", 1)[0] if ch.isalpha())
        for perspective, expected in ((WHITE, position["white_indices"]), (BLACK, position["black_indices"])):
            indices = active_feature_indices(position["fen"], perspective)
            assert len(indices) == piece_count
            assert len(set(indices)) == len(indices), "duplicate feature index"
            assert all(0 <= i < 768 for i in indices)


def test_feature_index_relative_color_is_zero_for_own_perspective():
    assert feature_index(WHITE, WHITE, 0, 0) == 0
    # Square 56 (a1) is black's mirrored "square 0" -- isolates relative_color from
    # relative_square's mirroring rather than confounding the two.
    assert feature_index(BLACK, BLACK, 0, 56) == 0


def test_feature_index_relative_color_is_one_for_opposing_perspective():
    assert feature_index(WHITE, BLACK, 0, 0) == 384
    assert feature_index(BLACK, WHITE, 0, 56) == 384


def test_feature_index_mirrors_square_vertically_for_black_perspective():
    # a1 (square 56) as seen from black must mirror to a8 (square 0).
    assert feature_index(BLACK, BLACK, 0, 56) == 0
    assert feature_index(WHITE, WHITE, 0, 56) == 56
