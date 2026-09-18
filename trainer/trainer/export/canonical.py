"""Canonical Network intermediate representation
(NNUE_TRAINER_ARCHITECTURE.md Section 5, revised 2026-07-14): two immutable
dataclasses -- `CanonicalNetwork` (mathematical network state, float32) and
`QuantizedCanonicalNetwork` (deployable engine state, int16 tensors + int32
`output_bias`) -- plus `checkpoint_to_canonical()`, the "Checkpoint Loader" pipeline
stage. Neither dataclass imports `torch`; only `checkpoint_to_canonical()` does,
locally, so the IR types stay usable with no torch import at all (Section 5 tradeoff).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict

import numpy as np

from trainer.encoding.feature_spec import load_feature_spec

FEATURES_PER_PERSPECTIVE = load_feature_spec().features_per_perspective

# Matches NnueNetwork.java's SUPPORTED_ARCHITECTURE_ID / SUPPORTED_FEATURE_SET_ID --
# "This build implements exactly one network topology and one feature set... a file
# declaring any other id is rejected outright" -- hardcoded here for the same reason
# general topology config was already rejected (architecture doc Section 6), not
# threaded through TrainingConfig.
ARCHITECTURE_ID = 1
FEATURE_SET_ID = 1


def _freeze(array: np.ndarray) -> np.ndarray:
    """Defensive copy + read-only lock (architecture doc Section 5's immutability
    paragraph): copying first means aliasing a live PyTorch tensor's `.numpy()` view
    can't silently mutate this array later through the shared buffer; `flags.writeable
    = False` gives `frozen=True`'s attribute-reassignment guard a matching
    in-place-mutation guard.
    """
    frozen = np.array(array, copy=True)
    frozen.flags.writeable = False
    return frozen


_ARRAY_FIELDS = ("ft_weights", "ft_biases", "output_weights")


def _freeze_array_fields(obj: Any) -> None:
    """Shared by both dataclasses' `__post_init__` and `__setstate__`. Pickling an
    ndarray does not preserve its `writeable` flag -- unpickling reconstructs a fresh,
    writeable array regardless of the source's flags -- and pickle's default restore
    for a frozen dataclass bypasses `__post_init__` entirely (it restores `__dict__`
    directly). Without `__setstate__` re-freezing explicitly, a `pickle.loads()`
    round-trip would silently produce a mutable array on an object that claims to be
    immutable.
    """
    for field in _ARRAY_FIELDS:
        object.__setattr__(obj, field, _freeze(getattr(obj, field)))


def _validate_shapes(hidden_width: int, ft_weights: np.ndarray, ft_biases: np.ndarray,
                      output_weights: np.ndarray) -> None:
    """Mirrors NnueNetwork.java's constructor validation -- rejects a malformed shape
    before any bad state can exist, rather than failing obscurely downstream."""
    expected_ft_weights = (FEATURES_PER_PERSPECTIVE, hidden_width)
    if ft_weights.shape != expected_ft_weights:
        raise ValueError(f"ft_weights shape {ft_weights.shape} != {expected_ft_weights}")
    if ft_biases.shape != (hidden_width,):
        raise ValueError(f"ft_biases shape {ft_biases.shape} != {(hidden_width,)}")
    if output_weights.shape != (2, hidden_width):
        raise ValueError(f"output_weights shape {output_weights.shape} != {(2, hidden_width)}")


@dataclass(frozen=True)
class CanonicalNetwork:
    """Mathematical network state -- float32, framework-agnostic, not yet deployable."""

    hidden_width: int
    ft_weights: np.ndarray      # float32, shape [768, hidden_width], row-major per feature
    ft_biases: np.ndarray       # float32, shape [hidden_width]
    output_weights: np.ndarray  # float32, shape [2, hidden_width], perspective-major ("us" then "them")
    output_bias: float
    qa: int
    qb: int
    output_scale: int
    architecture_id: int
    feature_set_id: int

    def __post_init__(self) -> None:
        _validate_shapes(self.hidden_width, self.ft_weights, self.ft_biases, self.output_weights)
        _freeze_array_fields(self)

    def __setstate__(self, state: Dict[str, Any]) -> None:
        self.__dict__.update(state)
        _freeze_array_fields(self)


@dataclass(frozen=True)
class QuantizedCanonicalNetwork:
    """Deployable engine state -- int16 tensors, int32 `output_bias`; byte-for-byte
    what Exporter (D-6) needs to write into the .nnue body (Section 8)."""

    hidden_width: int
    ft_weights: np.ndarray      # int16, shape [768, hidden_width], row-major per feature
    ft_biases: np.ndarray       # int16, shape [hidden_width]
    output_weights: np.ndarray  # int16, shape [2, hidden_width], perspective-major
    output_bias: int
    qa: int
    qb: int
    output_scale: int
    architecture_id: int
    feature_set_id: int

    def __post_init__(self) -> None:
        _validate_shapes(self.hidden_width, self.ft_weights, self.ft_biases, self.output_weights)
        _freeze_array_fields(self)

    def __setstate__(self, state: Dict[str, Any]) -> None:
        self.__dict__.update(state)
        _freeze_array_fields(self)


def checkpoint_to_canonical(checkpoint: Dict[str, Any]) -> CanonicalNetwork:
    """The "Checkpoint Loader" pipeline stage (Section 5): extracts exactly the
    tensors `CanonicalNetwork` needs from a training checkpoint (as produced by
    `trainer.model.train.train()`), absorbing PyTorch checkpoint/state_dict internals
    in one place instead of leaking them into `Quantizer`/`Exporter`.
    """
    import torch  # noqa: F401 -- local import, see module docstring.

    state_dict = checkpoint["model_state_dict"]
    config = checkpoint["config"]
    hidden_width = config["hidden_width"]

    ft_weights = state_dict["ft.weight"].detach().numpy().astype(np.float32)
    ft_biases = state_dict["ft_bias"].detach().numpy().astype(np.float32)

    # output_layer.weight: shape [1, 2*hidden_width]. Columns [0:hidden_width] are
    # "us", [hidden_width:2*hidden_width] are "them" -- exactly the order
    # NnueNet.forward() concatenates activation_us/activation_them in
    # (trainer/trainer/model/network.py), matching NnueNetwork.java's perspective-major
    # outputWeights layout (Section 8).
    output_weight = state_dict["output_layer.weight"].detach().numpy().astype(np.float32)
    output_bias_tensor = state_dict["output_layer.bias"].detach().numpy().astype(np.float32)
    output_weights = np.stack([output_weight[0, :hidden_width], output_weight[0, hidden_width:]])

    return CanonicalNetwork(
        hidden_width=hidden_width,
        ft_weights=ft_weights,
        ft_biases=ft_biases,
        output_weights=output_weights,
        output_bias=float(output_bias_tensor[0]),
        qa=int(config["qa"]),
        qb=int(config["qb"]),
        output_scale=int(config["output_scale"]),
        architecture_id=ARCHITECTURE_ID,
        feature_set_id=FEATURE_SET_ID,
    )
