"""Exporter (NNUE_TRAINER_ARCHITECTURE.md Section 8/9, issue #197): writes a
`QuantizedCanonicalNetwork` to the **frozen** `.nnue` binary layout
`NnueNetwork.java` already implements and loads, plus the sidecar `<uuid>.json`
provenance manifest (Section 9). This module does not get to redesign the `.nnue`
byte layout -- "Exporter must produce bytes `NnueNetwork.load()` accepts
unmodified" (Section 8) -- it only implements a writer for the format that
already exists and has shipped since Phase B/C.

Byte layout (verbatim from `NnueNetwork.java`'s documented format, big-endian --
Java's `DataInput`/`DataOutput` interfaces are always big-endian, by
specification, so this writer uses `struct`'s `>` prefix and NumPy's `>i2` dtype
rather than the little-endian layout a from-scratch design might otherwise pick):

    u8[4]  magic = "VNUE"
    i32    formatVersion, architectureId, featureSetId, hiddenWidth, quantVersion
    i32    qa, qb, outputScale
    utf    networkUuid, trainerCommit   (Java DataOutput#writeUTF-compatible)
    i64    createdAtEpochSeconds
    i16[]  ftWeights, ftBiases, outputWeights
    i32    outputBias

No checksum lives in this binary -- the frozen layout has no field for one.
Integrity checking (SHA-256) lives in the sidecar manifest instead, alongside
the rest of PRD Section "Provenance"'s sidecar schema -- recorded as this repo's
resolution in NNUE_TRAINER_ARCHITECTURE.md Section 9.1 (added D-6): keep the
shipped byte-for-byte layout unmodified, and get the Deep Research report's
integrity-check goal from the manifest instead of a binary-format change. See
Section 9.1 for who actually consumes `nnue_sha256` (a release-pipeline/CI
check, never the Java engine at runtime).
"""

from __future__ import annotations

import hashlib
import json
import os
import struct
import tempfile
import time
import tomllib
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

import numpy as np

from trainer.encoding.feature_spec import repo_root
from trainer.export.canonical import QuantizedCanonicalNetwork
from trainer.reproducibility.experiment_metadata import ExperimentMetadata

MAGIC = b"VNUE"
FORMAT_VERSION = 1
# Reserved in the .nnue header for a future quantization scheme change --
# NnueNetwork.java reads and discards this field today (see its own comment).
# No such future scheme exists yet, so this is the only value ever written.
QUANT_VERSION = 1

# PRD "Provenance": the embedded header field is a *short* hash ("enough to
# identify a stray file found on disk"); the manifest's own trainer_commit field
# carries the full hash. Both are correct simultaneously -- they serve different
# audiences (a human staring at a binary vs. an exact reproducibility record).
_SHORT_COMMIT_LENGTH = 8


@dataclass(frozen=True)
class DatasetComposition:
    """One dataset's contribution to a training run -- PRD "Provenance" sidecar
    schema: "dataset identifier(s) with stage labels (public/SF-labeled/self-play
    mix proportions)". `stage` matches `DatasetMetadata.stage`'s own convention
    (`trainer/trainer/contracts/dataset.py`): "public", "sf-labeled", or
    "self-play". `proportion` is this dataset's share of the training run's
    positions, in `[0.0, 1.0]`.
    """

    identifier: str
    stage: str
    proportion: float


@dataclass(frozen=True)
class ExportResult:
    nnue_path: Path
    manifest_path: Path
    network_uuid: str


def _trainer_version() -> str:
    """The `vex-trainer` package version (`trainer/pyproject.toml`'s `[project].version`)
    -- distinct from `trainer_commit`: PRD "Provenance" lists both "trainer version"
    and "full git commit" as separate manifest fields (a version tracks intentional
    releases; a commit hash tracks every change, including ones between releases).
    """
    pyproject_path = repo_root(Path(__file__).resolve()) / "trainer" / "pyproject.toml"
    with open(pyproject_path, "rb") as f:
        data = tomllib.load(f)
    return data["project"]["version"]


def _write_i32(value: int) -> bytes:
    return struct.pack(">i", value)


def _write_i64(value: int) -> bytes:
    return struct.pack(">q", value)


def _write_utf(value: str) -> bytes:
    """Java `DataOutput#writeUTF`-compatible: a 2-byte big-endian length prefix
    (of the encoded byte length) followed by the encoded bytes. Standard UTF-8
    and Java's "modified UTF-8" are byte-identical for any string containing no
    NUL bytes and no characters outside the Basic Multilingual Plane -- true of
    every value this function is ever called with (a UUID's hex/dash form, a git
    commit's hex form), so plain UTF-8 encoding is safe here. Asserted, not
    assumed: a non-ASCII value would silently produce a wire-incompatible file
    with no error otherwise.
    """
    if not value.isascii():
        raise ValueError(f"writeUTF-compatible encoding requires an ASCII-only string, got {value!r}")
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def _write_int16_array(array: np.ndarray) -> bytes:
    return array.astype(">i2").tobytes()


def _atomic_write(path: Path, data: bytes) -> None:
    """Writes `path` via a same-directory temp file + `os.replace` (POSIX rename is
    atomic within one filesystem) so a crash mid-write can never leave a truncated
    or partially-written file at `path` -- only a fully-written file or none at all.
    This does not give cross-file atomicity across the `.nnue`/manifest pair (no
    two-phase commit is attempted, since neither the loader nor any consumer needs
    it): a crash between the two calls in `export()` can still leave an orphaned
    `.nnue` with no manifest, just never a corrupt one.
    """
    fd, tmp_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp_name, path)
    except BaseException:
        Path(tmp_name).unlink(missing_ok=True)
        raise


def _nnue_bytes(network: QuantizedCanonicalNetwork, network_uuid: str, trainer_commit: str,
                 created_at_epoch_seconds: int) -> bytes:
    if network.qa <= 0:
        raise ValueError(f"qa must be positive, got {network.qa}")
    if network.qb <= 0:
        raise ValueError(f"qb must be positive, got {network.qb}")

    short_commit = trainer_commit[:_SHORT_COMMIT_LENGTH]

    parts = [
        MAGIC,
        _write_i32(FORMAT_VERSION),
        _write_i32(network.architecture_id),
        _write_i32(network.feature_set_id),
        _write_i32(network.hidden_width),
        _write_i32(QUANT_VERSION),
        _write_i32(network.qa),
        _write_i32(network.qb),
        _write_i32(network.output_scale),
        _write_utf(network_uuid),
        _write_utf(short_commit),
        _write_i64(created_at_epoch_seconds),
        _write_int16_array(network.ft_weights),
        _write_int16_array(network.ft_biases),
        _write_int16_array(network.output_weights),
        _write_i32(network.output_bias),
    ]
    return b"".join(parts)


def _write_manifest(manifest_path: Path, network: QuantizedCanonicalNetwork, network_uuid: str,
                     trainer_commit: str, created_at_epoch_seconds: int,
                     experiment_metadata: ExperimentMetadata, dataset_composition: List[DatasetComposition],
                     label_engine_version: Optional[str], nnue_sha256: str) -> None:
    manifest: Dict[str, Any] = {
        "format_version": FORMAT_VERSION,
        "architecture_id": network.architecture_id,
        "feature_set_id": network.feature_set_id,
        "hidden_width": network.hidden_width,
        "quant_version": QUANT_VERSION,
        "network_uuid": network_uuid,
        "trainer_version": _trainer_version(),
        "trainer_commit": trainer_commit,
        "created_at_epoch_seconds": created_at_epoch_seconds,
        "dataset_composition": [
            {"identifier": d.identifier, "stage": d.stage, "proportion": d.proportion}
            for d in dataset_composition
        ],
        "training_config": experiment_metadata.config,
        "training_seed": experiment_metadata.seed,
        "label_engine_version": label_engine_version,
        "nnue_sha256": nnue_sha256,
    }
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
    _atomic_write(manifest_path, manifest_bytes)


def export(network: QuantizedCanonicalNetwork, experiment_metadata: ExperimentMetadata,
           dataset_composition: List[DatasetComposition], output_dir: Path,
           label_engine_version: Optional[str] = None) -> ExportResult:
    """Writes `<output_dir>/<uuid>.nnue` and `<output_dir>/<uuid>.json`. Each file is
    written atomically (temp file + `os.replace`, see `_atomic_write`) -- a crash
    mid-write can never leave a truncated file for either artifact, though the pair
    itself is not a single cross-file transaction (a crash between the two writes
    below can leave an orphaned `.nnue` with no manifest, never a corrupt one).
    `network_uuid`/`created_at_epoch_seconds` are assigned fresh here, never carried
    on `QuantizedCanonicalNetwork` itself (Section 5's provenance-timing rule:
    identity is assigned at export time, not IR state).

    `label_engine_version` is `None` for every source reachable today (Stage 1 text
    data has no label-generating engine of its own); PRD "Provenance" marks this
    field "where applicable", so `None` is a valid, complete value, not a missing one.
    """
    network_uuid = str(uuid.uuid4())
    created_at_epoch_seconds = int(time.time())
    trainer_commit = experiment_metadata.trainer_commit

    nnue_bytes = _nnue_bytes(network, network_uuid, trainer_commit, created_at_epoch_seconds)
    nnue_sha256 = hashlib.sha256(nnue_bytes).hexdigest()

    output_dir.mkdir(parents=True, exist_ok=True)
    nnue_path = output_dir / f"{network_uuid}.nnue"
    manifest_path = output_dir / f"{network_uuid}.json"

    _atomic_write(nnue_path, nnue_bytes)
    _write_manifest(manifest_path, network, network_uuid, trainer_commit, created_at_epoch_seconds,
                     experiment_metadata, dataset_composition, label_engine_version, nnue_sha256)

    return ExportResult(nnue_path=nnue_path, manifest_path=manifest_path, network_uuid=network_uuid)
