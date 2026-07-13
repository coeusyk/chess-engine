"""The DatasetProvider contract (docs/architecture/NNUE_TRAINER_ARCHITECTURE.md §3,
§15 Invariant 1). Interfaces and value objects only — no loading logic. Every
concrete provider (Stage 1 text, Stage 2 Stockfish-labeled, Stage 3 self-play) depends
on this module; this module depends on nothing under trainer.dataset,
trainer.encoding, trainer.model, trainer.export, trainer.quantization, or
trainer.validation, by construction (Invariant 1).

Three concerns are kept separate, matching the PRD's own iterator contract
("Yield raw labeled positions from one source... iterator of (position, label,
metadata)") split across shard granularity:

- DatasetMetadata   -- describes the dataset as a whole (identity, provenance).
- ShardRef          -- names one shard without reading it (enumeration).
- PositionRecord     -- one labeled position, read from within a shard (iteration).

None of these three types, nor DatasetProvider itself, reference a concrete storage
format (EPD, CSV, mmap, JSON) anywhere in this module -- format is a concrete
provider's concern, not the contract's.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Iterator, Optional


@dataclass(frozen=True)
class DatasetMetadata:
    """Dataset-level identity and provenance (PRD Section "End-to-End
    Reproducibility", item 1: "each dataset has an identifier, an acquisition or
    generation script, and recorded provenance").

    `stage` is one of "public", "sf-labeled", "self-play" -- the three sources this
    contract must serve (PRD Section 2 US-5) -- but this type does not otherwise vary
    its shape per stage; stage-specific detail belongs in `source_ref`, not in new
    fields on this dataclass.
    """

    identifier: str
    stage: str
    source_ref: str
    position_count: Optional[int] = None


@dataclass(frozen=True)
class ShardRef:
    """A reference to one shard of a dataset -- enough to identify and later open it,
    nothing more. Naming a shard is separate from reading its positions
    (`DatasetProvider.positions`) so a caller can enumerate a dataset's shape (for
    parallel/resumable iteration) without paying the cost of opening every shard.

    `locator` is an opaque, provider-defined string (a file path, a URL, a shard
    index encoded as text -- whatever the concrete provider needs to open this shard
    later). The contract does not interpret it.
    """

    locator: str
    position_count: Optional[int] = None


@dataclass(frozen=True)
class PositionLabel:
    """A position's training target. At least one of `eval_cp` / `eval_mate` / `wdl`
    is populated; which ones depend on the source stage, and no field is assumed to
    be present -- callers must None-check before use, never assume a specific field
    is set just because another one is.

    `eval_cp`, `eval_mate`, and `wdl` are all relative to the side to move (positive /
    higher = good for the player about to move), matching this engine's own internal
    negamax convention (`Searcher`'s contempt handling: "the side to move has a
    material+PST advantage... returns +cp"). This is the same sign convention `fen`
    itself carries in its side-to-move field -- consistent by construction, not by
    coincidence. `wdl` is a scalar in `[0.0, 1.0]` (0 = the mover lost, 1 = the mover
    won, 0.5 = drawn), not an absolute White-perspective outcome.

    - Stage 1 (public text datasets): typically `eval_cp` (or `eval_mate` for forced
      mates) only.
    - Stage 2 (Stockfish labeling): `eval_cp` / `eval_mate` from a fixed-depth search.
    - Stage 3 (self-play): typically `wdl` (game outcome) only, `eval_cp` optional if
      the search score was also recorded.

    Blending these into one training target (PRD's lambda-weighted sigmoid blend) is
    the future `Labeler` stage's job, not this contract's -- `DatasetProvider` yields
    whatever the source actually recorded, unblended.
    """

    eval_cp: Optional[int] = None
    eval_mate: Optional[int] = None
    wdl: Optional[float] = None

    def __post_init__(self) -> None:
        if self.eval_cp is None and self.eval_mate is None and self.wdl is None:
            raise ValueError("PositionLabel requires at least one of eval_cp, eval_mate, wdl")


@dataclass(frozen=True)
class PositionMetadata:
    """Per-position metadata (the PRD iterator contract's third element). Every field
    is optional -- populated only when the source actually provides it; a Stage 1
    text dataset may leave every field unset, while Stage 2/3 sources populate more.

    Deliberately does not repeat `DatasetMetadata.stage` here -- a single provider
    represents one source (PRD: "raw labeled positions from one source"), so stage is
    uniform across every position it yields; the dataset-level field is the one
    source of truth, not duplicated per-record.
    """

    ply: Optional[int] = None
    game_id: Optional[str] = None
    search_depth: Optional[int] = None
    search_nodes: Optional[int] = None


@dataclass(frozen=True)
class PositionRecord:
    """One labeled position: a FEN string, its label, and its metadata -- the PRD's
    "(position, label, metadata)" tuple, named. `fen` is a plain FEN string, not a
    parsed board -- parsing into features is `FeatureEncoder`'s job (architecture doc
    Section 4), a later pipeline stage this contract does not depend on.
    """

    fen: str
    label: PositionLabel
    metadata: PositionMetadata


class DatasetProvider(ABC):
    """One data source, per docs/architecture/NNUE_TRAINER_ARCHITECTURE.md Section 3.
    Every concrete provider (Stage 1 text, Stage 2 Stockfish-labeled, Stage 3
    self-play) implements exactly this contract and nothing more -- no shared base
    beyond this class, no provider importing another provider (Invariant 1).

    ABC (not typing.Protocol): a provider missing one of the three methods below must
    fail at construction time (`TypeError`), not at first use -- matching this
    project's established preference for structural, construction-time guarantees
    over ones only caught by convention or a type checker (ADR-002/003/004's shared
    theme of eliminating silent-failure classes by construction). No Python
    type-checker runs in this project's CI yet, so Protocol's static-only guarantee
    would not actually be enforced anywhere; ABC's runtime enforcement is real today.
    """

    @abstractmethod
    def metadata(self) -> DatasetMetadata:
        """Describe this dataset as a whole. Cheap -- must not read shard contents."""
        raise NotImplementedError

    @abstractmethod
    def shards(self) -> Iterator[ShardRef]:
        """Enumerate this dataset's shards. Cheap -- must not read position data;
        a provider backed by a single file may yield exactly one ShardRef.
        """
        raise NotImplementedError

    @abstractmethod
    def positions(self, shard: ShardRef) -> Iterator[PositionRecord]:
        """Stream labeled positions from one shard. Must not load the full shard
        into memory at once (docs/architecture/NNUE_TRAINER_ARCHITECTURE.md Section 3:
        streaming, not a loaded list -- the 50-100M position scale this contract is
        sized for).
        """
        raise NotImplementedError
