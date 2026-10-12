"""Stage 1 acquisition driver (issue #202, ADR-007, PRD Section 2 US-5).

The *dataset choice* (Lichess evaluated positions) was already resolved at PR D-2
(issue #193, closed) -- see `trainer/configs/stage1-dataset.md`. What D-2 explicitly
left out ("Converting Lichess's actual published dump into this normalized CSV is a
separate acquisition script, tracked as a real, later, small task -- not part of
D-2") is this module.

**Streaming, not downloading.** The published dump
(`https://database.lichess.org/lichess_db_eval.jsonl.zst`, ~394.7M positions as of
this writing) is one large `.zst`-compressed JSON-lines file. This driver opens it as
an HTTP stream, decompresses incrementally via `zstandard`'s `stream_reader`, and
stops consuming the connection the moment `target_count` normalized records have been
written -- it never downloads or decompresses the file in full, regardless of how
small `target_count` is relative to the dump's real size.

**Per-position label selection.** Each source line has an `evals` list (one entry per
independent engine analysis run recorded for that position, each carrying its own
`pvs` list of candidate lines). Per Lichess's own documented recommendation
(`database.lichess.org/#evals`, "Notes": "select the evaluation with the highest
depth, and use its first PV"), this driver picks the `evals` entry with the highest
`depth` and reads `cp`/`mate` from that entry's `pvs[0]`.

**Output contract.** Writes exactly the normalized shape `TextDatasetProvider`
(`trainer/trainer/dataset/text_provider.py`) already reads:
`fen,eval_cp[,eval_mate]` CSV rows (empty field = absent) -- this module does not
touch `TextDatasetProvider`'s own logic (issue #202's Explicit Non-Scope), it only
produces input for it.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterator, Optional

import zstandard

# Private-name imports, matching `trainer/scripts/stockfish_label.py`'s existing,
# already-shipped precedent (D-8, unmodified, issue #202 Non-Scope) exactly, rather
# than duplicating these five-line helpers a third time (architecture-review finding,
# E-2: a from-scratch local copy would silently diverge from two existing call sites
# doing the same thing, with no signal that either the third copy or the original two
# had gone stale relative to each other). `trainer/export/__init__.py` and
# `trainer/reproducibility/__init__.py` do not export these names -- consistent with
# `stockfish_label.py`'s own established pattern for this exact problem, this module
# follows it rather than inventing a second, divergent way to solve the same problem.
from trainer.export.exporter import _atomic_write
from trainer.reproducibility.experiment_metadata import _trainer_commit

DEFAULT_SOURCE_URL = "https://database.lichess.org/lichess_db_eval.jsonl.zst"
_HTTP_TIMEOUT_SECONDS = 30.0


@dataclass(frozen=True)
class NormalizedRecord:
    fen: str
    eval_cp: Optional[int]
    eval_mate: Optional[int]


def _best_eval(evals: list) -> Optional[dict]:
    """The `evals` entry with the highest `depth`, or None if `evals` is empty --
    matching Lichess's own documented per-position selection rule."""
    if not evals:
        return None
    return max(evals, key=lambda entry: entry.get("depth", -1))


def _to_record(raw: Dict[str, Any]) -> Optional[NormalizedRecord]:
    """None (skip) if the source line has no usable eval -- never fabricates a label
    for a position the dump itself didn't score."""
    best = _best_eval(raw.get("evals", []))
    if best is None:
        return None
    pvs = best.get("pvs")
    if not pvs:
        return None
    pv = pvs[0]
    eval_cp, eval_mate = pv.get("cp"), pv.get("mate")
    if eval_cp is None and eval_mate is None:
        return None
    return NormalizedRecord(fen=raw["fen"], eval_cp=eval_cp, eval_mate=eval_mate)


def _open_source_stream(source: str) -> io.TextIOWrapper:
    """`source` is either an http(s) URL (the real dump) or a local filesystem path to
    a `.jsonl.zst` file (used by tests, and by anyone re-running this offline against
    an already-fetched copy) -- both open as a byte stream fed through the same
    streaming decompressor, so a caller never has to branch on which was used.
    """
    if source.startswith("http://") or source.startswith("https://"):
        raw = urllib.request.urlopen(source, timeout=_HTTP_TIMEOUT_SECONDS)
    else:
        raw = open(source, "rb")
    reader = zstandard.ZstdDecompressor().stream_reader(raw)
    return io.TextIOWrapper(reader, encoding="utf-8")


def _iter_records(text: io.TextIOWrapper) -> Iterator[NormalizedRecord]:
    """Iterates an *already-open* stream -- no `with` of its own, so a caller that
    owns `text` in its own `with` block (as `acquire()` below does) gets deterministic
    close-on-`break` in that same frame, not generator-GC-dependent cleanup."""
    for line in text:
        line = line.strip()
        if not line:
            continue
        record = _to_record(json.loads(line))
        if record is not None:
            yield record


def stream_records(source: str) -> Iterator[NormalizedRecord]:
    """Yields normalized records lazily, one source line at a time -- never
    materializes the source stream. Opens and owns the stream via a `with` block
    around a `yield from`: closing on full exhaustion (this module's own tests'
    usage, `list(stream_records(...))`) is deterministic; closing on an early
    `break` is not (it depends on CPython's generator-GC-on-refcount behavior to
    run this generator's own `__exit__`, not a language guarantee). `acquire()`
    below is this module's one early-stopping caller, and does not use this
    function for exactly that reason (architecture-review finding, E-2) -- it opens
    the stream itself via `_open_source_stream`/`_iter_records` so its own `break`
    closes the stream deterministically in its own frame instead.
    """
    with _open_source_stream(source) as text:
        yield from _iter_records(text)


@dataclass(frozen=True)
class AcquisitionResult:
    csv_path: Path
    manifest_path: Path
    position_count: int


def acquire(source: str, target_count: int, output_dir: Path, dataset_identifier: str) -> AcquisitionResult:
    """Streams `source` and writes the first `target_count` normalized records to
    `output_dir/stage1.csv`, plus a provenance manifest -- the Stage 1 counterpart to
    `stockfish_label.py::label_positions` (Stage 2).
    """
    if target_count <= 0:
        raise ValueError(f"target_count must be positive, got {target_count}")

    output_dir.mkdir(parents=True, exist_ok=True)
    csv_path = output_dir / "stage1.csv"

    position_count = 0
    with open(csv_path, "w", newline="", encoding="ascii") as f, _open_source_stream(source) as stream:
        writer = csv.writer(f)
        for record in _iter_records(stream):
            cp = "" if record.eval_cp is None else str(record.eval_cp)
            mate = "" if record.eval_mate is None else str(record.eval_mate)
            writer.writerow([record.fen, cp, mate])
            position_count += 1
            if position_count >= target_count:
                break
    # `with` block above closes `stream` here deterministically -- on the `break`
    # above just as much as on normal exhaustion or an exception, since it exits in
    # this same frame (not inside a generator whose cleanup depends on GC timing).

    output_sha256 = hashlib.sha256(csv_path.read_bytes()).hexdigest()
    manifest_path = output_dir / "manifest.json"
    manifest = {
        "dataset_identifier": dataset_identifier,
        "stage": "public",
        "source_url": source,
        "output_sha256": output_sha256,
        "trainer_commit": _trainer_commit(),
        "created_at_epoch_seconds": int(time.time()),
        "position_count": position_count,
    }
    _atomic_write(manifest_path, (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"))

    return AcquisitionResult(csv_path=csv_path, manifest_path=manifest_path, position_count=position_count)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("target_count", type=int)
    parser.add_argument("dataset_identifier")
    parser.add_argument("--source", default=DEFAULT_SOURCE_URL,
                         help="HTTP URL or local path to a lichess_db_eval-shaped .jsonl.zst file")
    args = parser.parse_args()

    result = acquire(args.source, args.target_count, args.output_dir, args.dataset_identifier)
    print(f"acquired {result.position_count} positions -> {result.csv_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
