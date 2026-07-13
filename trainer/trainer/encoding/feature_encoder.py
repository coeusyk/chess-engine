"""Python mirror of FeatureExtractor.java's `featureIndex()` formula
(NNUE_TRAINER_ARCHITECTURE.md Section 4). Unlike the Java side (hardcoded, hot-path,
allocation-free per CLAUDE.md Section 3), this module derives its constants from the
versioned Feature Specification (`feature_spec.py`) at import time -- the asymmetric
resolution recorded in Section 4.1.

No JNI, no codegen, no shared runtime parser with Java -- an independent
re-implementation, pinned against the same golden-value corpus
(`docs/architecture/feature-spec/parity-corpus-v1.json`) that
`FeatureIndexParityTest.java` is pinned against.
"""

from __future__ import annotations

from typing import Iterator, List, Tuple

from trainer.encoding.feature_spec import load_feature_spec

WHITE = "white"
BLACK = "black"

_PIECE_CHAR_TO_TYPE_INDEX = {"p": 0, "n": 1, "b": 2, "r": 3, "q": 4, "k": 5}

_SPEC = load_feature_spec()


def feature_index(perspective_color: str, piece_color: str, piece_type_index: int, square: int) -> int:
    """One (piece, square) fact's feature index as seen from `perspective_color` --
    the Python mirror of FeatureExtractor.featureIndex(). `piece_type_index` is
    0-based (pawn=0 .. king=5), matching `_SPEC.piece_types`' order and Java's
    `pieceType - 1`.
    """
    relative_color = 0 if piece_color == perspective_color else 1
    relative_square = square if perspective_color == WHITE else (square ^ 56)
    return (
        relative_color * (len(_SPEC.piece_types) * _SPEC.squares)
        + piece_type_index * _SPEC.squares
        + relative_square
    )


def _iter_board_pieces(fen: str) -> Iterator[Tuple[str, int, int]]:
    """Yields (piece_color, piece_type_index, square) for every piece on `fen`'s
    board, in FEN reading order (rank 8 to rank 1, file a to h within a rank) --
    which is square index order 0..63 directly, per `_SPEC.square_numbering`'s
    "a8=0, row-major top-to-bottom" convention (matches Board.getChessSquare:
    `rank = 8 - square/8`, verified directly against engine-core/.../Board.java).
    """
    board_field = fen.split(" ", 1)[0]
    square = 0
    for ch in board_field:
        if ch == "/":
            continue
        if ch.isdigit():
            square += int(ch)
            continue
        piece_color = WHITE if ch.isupper() else BLACK
        piece_type_index = _PIECE_CHAR_TO_TYPE_INDEX[ch.lower()]
        yield piece_color, piece_type_index, square
        square += 1


def active_feature_indices(fen: str, perspective_color: str) -> List[int]:
    """The Python mirror of FeatureExtractor.activeFeatureIndices() -- every active
    feature index for `perspective_color` on the position in `fen`, sorted ascending.
    """
    return sorted(
        feature_index(perspective_color, piece_color, piece_type_index, square)
        for piece_color, piece_type_index, square in _iter_board_pieces(fen)
    )
