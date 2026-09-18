"""Stage 1 DatasetProvider: a public, plain-text labeled dataset (PRD Section 2 US-5
Stage 1; docs/adr/ADR-007-staged-training-data.md; dataset choice documented in
trainer/configs/stage1-dataset.md).

Reads a directory of normalized CSV shard files, one `fen,eval_cp[,eval_mate][,ply]`
record per line (empty field = absent). Normalizing a real published dataset (e.g.
Lichess's own dump format) into this CSV shape is a separate acquisition step, out of
this module's scope -- this provider only reads the normalized form.
"""

from __future__ import annotations

import csv
from pathlib import Path
from typing import Iterator, Optional

from trainer.contracts import (
    DatasetMetadata,
    DatasetProvider,
    PositionLabel,
    PositionMetadata,
    PositionRecord,
    ShardRef,
)


def _parse_optional_int(value: str) -> Optional[int]:
    return int(value) if value.strip() else None


def _parse_row(row: list) -> PositionRecord:
    if len(row) < 2:
        raise ValueError(f"expected at least fen,eval_cp columns, got {row!r}")
    fen = row[0]
    eval_cp = _parse_optional_int(row[1])
    eval_mate = _parse_optional_int(row[2]) if len(row) > 2 else None
    ply = _parse_optional_int(row[3]) if len(row) > 3 else None
    return PositionRecord(
        fen=fen,
        label=PositionLabel(eval_cp=eval_cp, eval_mate=eval_mate),
        metadata=PositionMetadata(ply=ply),
    )


class TextDatasetProvider(DatasetProvider):
    """One `DatasetProvider` over a directory of normalized CSV shard files. Every
    `*.csv` file directly under `directory` is one shard -- no recursion, no other
    file types considered.
    """

    def __init__(self, directory: Path, identifier: str, source_ref: str) -> None:
        self._directory = Path(directory)
        self._identifier = identifier
        self._source_ref = source_ref

    def metadata(self) -> DatasetMetadata:
        return DatasetMetadata(
            identifier=self._identifier,
            stage="public",
            source_ref=self._source_ref,
        )

    def shards(self) -> Iterator[ShardRef]:
        for path in sorted(self._directory.glob("*.csv")):
            yield ShardRef(locator=str(path))

    def positions(self, shard: ShardRef) -> Iterator[PositionRecord]:
        with open(shard.locator, newline="", encoding="ascii") as f:
            for row in csv.reader(f):
                if not row:
                    continue
                yield _parse_row(row)
