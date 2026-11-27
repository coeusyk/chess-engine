"""Compare two Stage 2 shard directories record for record (#263/#265, plan #329 readiness step).

    python -m scripts.compare_stage2_records A_DIR A_LAYOUT B_DIR B_LAYOUT

`LAYOUT` is `v1` (the current versioned shard, read with `read_shard`), `legacy119` (the pre-WDL layout, for
example `stage2-quiet-sf`) or `legacy124` (the WDL layout, for example `stage2-quiet-sf-wdl`). A legacy
directory is read by migrating each shard into a temporary directory with the existing `migrate_legacy*_to_v1`
functions and reading that, so the source is never modified and no legacy layout is decoded here.

The outcome is exactly one of:
  identical          the same records in the same order
  ordering-mismatch  the same records (as a multiset) in a different order
  content-mismatch   a different multiset: a record changed, was dropped or was added (counts are reported)
  reader-failure     a directory could not be read (no shards, a wrong layout, a corrupt file); `side` says
                     which and `error` carries the exception type and message with the file's base name only
Exit status 0 only for `identical`. Nothing is written except the JSON report on stdout.
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from collections import Counter
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from trainer.contracts import PositionRecord, ShardRef
from trainer.dataset.mmap_shard import migrate_legacy119_to_v1, migrate_legacy124_to_v1, read_shard

LAYOUTS = ("v1", "legacy119", "legacy124")
_MIGRATE = {"legacy119": migrate_legacy119_to_v1, "legacy124": migrate_legacy124_to_v1}


class ReaderFailure(Exception):
    pass


def read_records(directory: Path, layout: str) -> List[PositionRecord]:
    if layout not in LAYOUTS:
        raise ValueError(f"layout must be one of {LAYOUTS}")
    shards = sorted(Path(directory).glob("*.bin"))
    if not shards:
        raise ReaderFailure(f"{Path(directory).name}: no *.bin shards")
    records: List[PositionRecord] = []
    with tempfile.TemporaryDirectory() as scratch:
        for shard in shards:
            try:
                path = shard
                if layout != "v1":
                    path = Path(scratch) / shard.name
                    _MIGRATE[layout](shard, path)
                records.extend(read_shard(ShardRef(locator=str(path))))
            except Exception as error:  # any failure to read is a reader failure, whatever its type
                raise ReaderFailure(f"{shard.name}: {type(error).__name__}: {str(error).replace(str(shard), shard.name)}") from error
    return records


def compare_records(a: Sequence[PositionRecord], b: Sequence[PositionRecord]) -> Dict[str, Any]:
    keys_a, keys_b = [repr(r) for r in a], [repr(r) for r in b]
    report: Dict[str, Any] = {"records_a": len(a), "records_b": len(b)}
    if keys_a == keys_b:
        return {**report, "outcome": "identical"}
    if Counter(keys_a) == Counter(keys_b):
        first = next(i for i, (x, y) in enumerate(zip(keys_a, keys_b)) if x != y)
        return {**report, "outcome": "ordering-mismatch", "first_difference_index": first}
    only_a, only_b = Counter(keys_a) - Counter(keys_b), Counter(keys_b) - Counter(keys_a)
    return {**report, "outcome": "content-mismatch", "records_only_in_a": sum(only_a.values()),
            "records_only_in_b": sum(only_b.values())}


def compare_directories(a_dir: Path, a_layout: str, b_dir: Path, b_layout: str) -> Dict[str, Any]:
    loaded: Dict[str, List[PositionRecord]] = {}
    for side, directory, layout in (("a", a_dir, a_layout), ("b", b_dir, b_layout)):
        try:
            loaded[side] = read_records(directory, layout)
        except ReaderFailure as error:
            return {"outcome": "reader-failure", "side": side, "layout": layout, "error": str(error)}
    return {**compare_records(loaded["a"], loaded["b"]), "a": Path(a_dir).name, "b": Path(b_dir).name,
            "layout_a": a_layout, "layout_b": b_layout}


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("a_dir", type=Path)
    parser.add_argument("a_layout", choices=LAYOUTS)
    parser.add_argument("b_dir", type=Path)
    parser.add_argument("b_layout", choices=LAYOUTS)
    args = parser.parse_args(argv)
    report = compare_directories(args.a_dir, args.a_layout, args.b_dir, args.b_layout)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["outcome"] == "identical" else 1


if __name__ == "__main__":
    sys.exit(main())
