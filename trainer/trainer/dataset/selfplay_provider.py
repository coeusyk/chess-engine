"""Stage 3 DatasetProvider (#210): reads V1 shards already produced by
`scripts/selfplay_ingest.py`. Structurally identical in spirit to
`StockfishLabeledProvider` (Stage 2) and `TextDatasetProvider` (Stage 1): this
module never decodes VSPR, never runs ingestion, and never makes an
assessment/promotion decision -- it is a thin reader over already-ingested
shards, matching Invariant 1 isolation (this file imports only
`trainer.contracts` and `trainer.dataset.mmap_shard`, nothing that knows about
VSPR, self-play generation, or the ingestion manifest's assessment model).

Deliberately does not perform train/held-out splitting itself -- the
`DatasetProvider` ABC has no such requirement, and self-play data specifically
needs a grouped-by-game split (`trainer.dataset.split.split_by_game`), which
is the caller's job, not this provider's (#210 section 10/11 scope boundary).
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

from trainer.contracts import DatasetMetadata, DatasetProvider, PositionRecord, ShardRef
from trainer.dataset.mmap_shard import read_shard


class SelfPlayProvider(DatasetProvider):
    """One `DatasetProvider` over a directory of V1 mmap shards produced by
    `selfplay_ingest.py`. Every `*.bin` file directly under `directory` is one
    shard -- no recursion, matching the existing providers' own enumeration
    convention. Does not read the ingestion manifest itself (provenance and
    assessment state live there, not in `DatasetMetadata` -- a caller wanting
    that reads `<directory>/manifest.json` directly via
    `trainer.dataset.selfplay_manifest`).
    """

    def __init__(self, directory: Path, identifier: str, source_ref: str) -> None:
        self._directory = Path(directory)
        self._identifier = identifier
        self._source_ref = source_ref

    def metadata(self) -> DatasetMetadata:
        return DatasetMetadata(
            identifier=self._identifier,
            stage="self-play",
            source_ref=self._source_ref,
        )

    def shards(self) -> Iterator[ShardRef]:
        for path in sorted(self._directory.glob("*.bin")):
            yield ShardRef(locator=str(path))

    def positions(self, shard: ShardRef) -> Iterator[PositionRecord]:
        yield from read_shard(shard)
