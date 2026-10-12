"""Converts a batch of `PositionRecord` into the flat (indices, offsets) tensors
`NnueNet`'s `EmbeddingBag`-based forward pass consumes. "us"/"them" are relative to
each position's own side to move (matching `PositionLabel`'s documented sign
convention, `trainer/contracts/dataset.py`), not fixed to white/black -- the same
relative-perspective convention `FeatureExtractor.java`/`NnueEvaluator.java` use.
"""

from __future__ import annotations

from typing import Iterable, List, NamedTuple, Tuple

import torch

from trainer.contracts import PositionRecord
from trainer.encoding.feature_encoder import BLACK, WHITE, active_feature_indices


def _side_to_move(fen: str) -> str:
    return WHITE if fen.split(" ")[1] == "w" else BLACK


def _opposite(color: str) -> str:
    return BLACK if color == WHITE else WHITE


class EncodedBatch(NamedTuple):
    us_indices: torch.Tensor
    us_offsets: torch.Tensor
    them_indices: torch.Tensor
    them_offsets: torch.Tensor


def _flatten(index_lists: List[List[int]]) -> Tuple[torch.Tensor, torch.Tensor]:
    offsets = [0]
    flat: List[int] = []
    for indices in index_lists:
        flat.extend(indices)
        offsets.append(len(flat))
    offsets.pop()  # EmbeddingBag offsets are bag *start* positions, not end positions
    return (
        torch.tensor(flat, dtype=torch.long),
        torch.tensor(offsets, dtype=torch.long),
    )


def encode_fens(fens: Iterable[str]) -> EncodedBatch:
    """The FEN-only half of `encode_batch()` -- for callers (e.g. `Validator`'s
    `eval_scale_check`) that have positions with no training label and would
    otherwise need to construct a throwaway `PositionRecord` just to reach this
    encoding, which reads only `.fen` in the first place.
    """
    us_lists: List[List[int]] = []
    them_lists: List[List[int]] = []
    for fen in fens:
        mover = _side_to_move(fen)
        us_lists.append(active_feature_indices(fen, mover))
        them_lists.append(active_feature_indices(fen, _opposite(mover)))

    us_indices, us_offsets = _flatten(us_lists)
    them_indices, them_offsets = _flatten(them_lists)
    return EncodedBatch(us_indices, us_offsets, them_indices, them_offsets)


def encode_batch(records: Iterable[PositionRecord]) -> EncodedBatch:
    """Encodes a batch of positions into flat `EmbeddingBag` (indices, offsets)
    tensors, two perspectives ("us" = side to move, "them" = opponent).
    """
    return encode_fens(record.fen for record in records)
