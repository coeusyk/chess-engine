"""Content identity of a network, independent of everything that is not the network (#265, plan #329).

`export()` assigns a fresh uuid and the current time on every call, so two exports of the same checkpoint
give two different `.nnue` files with different file hashes, and two checkpoints with the same tensors but
different training metadata give different checkpoint file hashes. File hashes therefore cannot tell
whether two artifacts are the same network. These hashes can.

Both cover every inference-relevant parameter, not just the arrays: two networks that differ only in `qa`,
`qb`, `output_scale`, `hidden_width`, the architecture id, the feature-set id or the quantization version
evaluate differently and must hash differently even when their arrays are equal.

* `tensor_sha256(CanonicalNetwork)`: the float32 network (what a training checkpoint holds).
* `network_content_sha256(QuantizedCanonicalNetwork)`: the int16 network the engine would load (what the
  `.nnue` body holds), plus the quantization version the exporter writes.

Excluded on purpose: uuid, trainer commit, timestamps, dataset composition, and every checkpoint metadata
field. Every array is hashed with its name, dtype (little-endian) and shape in front of its C-order bytes,
so an array cannot be confused with another of the same bytes.
"""

from __future__ import annotations

import hashlib
import struct
from typing import Iterable, Tuple

import numpy as np

from trainer.export.canonical import CanonicalNetwork, QuantizedCanonicalNetwork
from trainer.export.exporter import QUANT_VERSION

#: Bump when the set of hashed fields or their encoding changes, so old and new identities never collide.
IDENTITY_SCHEMA = 1


def _update_array(digest: "hashlib._Hash", name: str, array: np.ndarray, dtype: str) -> None:
    if array.dtype != np.dtype(dtype):
        raise ValueError(f"{name} has dtype {array.dtype}, expected {dtype}: refusing to cast it into a hash")
    data = np.ascontiguousarray(array)
    digest.update(f"array:{name}:{data.dtype.str}:{','.join(str(d) for d in data.shape)}\n".encode("ascii"))
    digest.update(data.tobytes())


def _update_scalars(digest: "hashlib._Hash", items: Iterable[Tuple[str, object]]) -> None:
    for name, value in items:
        if isinstance(value, float):
            text = float(value).hex()  # exact, platform independent
        else:
            text = str(int(value))
        digest.update(f"scalar:{name}={text}\n".encode("ascii"))


def tensor_sha256(canonical: CanonicalNetwork) -> str:
    digest = hashlib.sha256()
    digest.update(f"vex-tensor-identity/{IDENTITY_SCHEMA}\n".encode("ascii"))
    _update_scalars(digest, (
        ("hidden_width", canonical.hidden_width), ("qa", canonical.qa), ("qb", canonical.qb),
        ("output_scale", canonical.output_scale), ("architecture_id", canonical.architecture_id),
        ("feature_set_id", canonical.feature_set_id), ("output_bias", float(canonical.output_bias)),
    ))
    _update_array(digest, "ft_weights", canonical.ft_weights, "<f4")
    _update_array(digest, "ft_biases", canonical.ft_biases, "<f4")
    _update_array(digest, "output_weights", canonical.output_weights, "<f4")
    return digest.hexdigest()


def network_content_sha256(network: QuantizedCanonicalNetwork) -> str:
    digest = hashlib.sha256()
    digest.update(f"vex-network-identity/{IDENTITY_SCHEMA}\n".encode("ascii"))
    _update_scalars(digest, (
        ("quant_version", QUANT_VERSION), ("hidden_width", network.hidden_width), ("qa", network.qa),
        ("qb", network.qb), ("output_scale", network.output_scale), ("architecture_id", network.architecture_id),
        ("feature_set_id", network.feature_set_id), ("output_bias", network.output_bias),
    ))
    _update_array(digest, "ft_weights", network.ft_weights, "<i2")
    _update_array(digest, "ft_biases", network.ft_biases, "<i2")
    _update_array(digest, "output_weights", network.output_weights, "<i2")
    return digest.hexdigest()
