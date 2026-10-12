"""Stage 2 DatasetProvider (issue #199, ADR-007): reads shards already written by the
labeling driver (`trainer/scripts/stockfish_label.py`). This module never runs
Stockfish, never spawns a subprocess, and never imports the labeling driver -- it is
a thin reader, structurally identical in spirit to `TextDatasetProvider` (Stage 1):
"how did this shard's data get produced" is out of scope for a `DatasetProvider`, by
design (Invariant 1 isolation -- this file imports only `trainer.contracts` and
`trainer.dataset.mmap_shard`, nothing that knows about labeling, UCI, or Stockfish).
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

from trainer.contracts import DatasetMetadata, DatasetProvider, PositionRecord, ShardRef
from trainer.dataset.mmap_shard import read_shard


class StockfishLabeledProvider(DatasetProvider):
    """One `DatasetProvider` over a directory of `.bin` mmap shards produced by the
    Stage 2 labeling driver. Every `*.bin` file directly under `directory` is one
    shard -- no recursion, matching `TextDatasetProvider`'s own enumeration
    convention.
    """

    def __init__(self, directory: Path, identifier: str, source_ref: str) -> None:
        self._directory = Path(directory)
        self._identifier = identifier
        self._source_ref = source_ref

    def metadata(self) -> DatasetMetadata:
        return DatasetMetadata(
            identifier=self._identifier,
            stage="sf-labeled",
            source_ref=self._source_ref,
        )

    def shards(self) -> Iterator[ShardRef]:
        for path in sorted(self._directory.glob("*.bin")):
            yield ShardRef(locator=str(path))

    def positions(self, shard: ShardRef) -> Iterator[PositionRecord]:
        yield from read_shard(shard)
