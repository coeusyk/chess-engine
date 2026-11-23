"""Reads a `.nnue` file back into a `QuantizedCanonicalNetwork` (issue #265): the inverse of
`exporter._nnue_bytes`, byte for byte, so the committed Java fixtures can be evaluated by the
Python quantized inference. A test/validation helper, not a second source of truth for the
format: the wire layout is defined by `exporter.py` and `NnueNetwork.load()`, and this reader
rejects anything that does not match it exactly rather than guessing.
"""

from __future__ import annotations

import struct
from pathlib import Path
from typing import Tuple

import numpy as np

from trainer.export.canonical import FEATURES_PER_PERSPECTIVE, QuantizedCanonicalNetwork
from trainer.export.exporter import FORMAT_VERSION, MAGIC


class _Cursor:
    def __init__(self, data: bytes):
        self.data, self.pos = data, 0

    def take(self, count: int) -> bytes:
        if self.pos + count > len(self.data):
            raise ValueError(f".nnue file truncated at byte {self.pos}, needed {count} more")
        chunk = self.data[self.pos:self.pos + count]
        self.pos += count
        return chunk

    def i32(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def utf(self) -> str:
        length = struct.unpack(">H", self.take(2))[0]
        return self.take(length).decode("utf-8")

    def int16_array(self, count: int) -> np.ndarray:
        return np.frombuffer(self.take(count * 2), dtype=">i2").astype(np.int16)


def read_nnue(path: Path) -> Tuple[QuantizedCanonicalNetwork, str]:
    """Returns `(network, network_uuid)`. Raises `ValueError` on a bad magic, version or length."""
    cursor = _Cursor(Path(path).read_bytes())
    if cursor.take(len(MAGIC)) != MAGIC:
        raise ValueError("not a .nnue file (bad magic)")
    format_version = cursor.i32()
    if format_version != FORMAT_VERSION:
        raise ValueError(f"unsupported .nnue format version {format_version}")
    architecture_id, feature_set_id, hidden_width = cursor.i32(), cursor.i32(), cursor.i32()
    cursor.i32()  # quant_version, reserved
    qa, qb, output_scale = cursor.i32(), cursor.i32(), cursor.i32()
    network_uuid = cursor.utf()
    cursor.utf()  # short trainer commit
    cursor.take(8)  # created_at_epoch_seconds
    ft_weights = cursor.int16_array(FEATURES_PER_PERSPECTIVE * hidden_width).reshape(FEATURES_PER_PERSPECTIVE, hidden_width)
    ft_biases = cursor.int16_array(hidden_width)
    output_weights = cursor.int16_array(2 * hidden_width).reshape(2, hidden_width)
    output_bias = cursor.i32()
    if cursor.pos != len(cursor.data):
        raise ValueError(f"{len(cursor.data) - cursor.pos} unexpected trailing bytes in .nnue file")
    network = QuantizedCanonicalNetwork(
        hidden_width=hidden_width, ft_weights=ft_weights, ft_biases=ft_biases, output_weights=output_weights,
        output_bias=output_bias, qa=qa, qb=qb, output_scale=output_scale,
        architecture_id=architecture_id, feature_set_id=feature_set_id,
    )
    return network, network_uuid
