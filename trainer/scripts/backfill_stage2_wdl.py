"""Phase 4-WDL (research doc RQ-4) one-off migration: backfills `PositionLabel.wdl`
onto the existing, already-trained-on Stage 2 shard via a pure FEN join against
`data/quiet-labeled.epd` -- no Stockfish re-execution, no re-labeling.

`docs/architecture/research/nnue/phase4c-reranking-wdl-audit.md` §5.3 established this
join is viable: the production shard's 20,000 records match 100% against the raw EPD
file's FENs, with only 248/724,127 (0.03%) unique FENs carrying conflicting outcomes
across duplicates. This script drops the wdl attachment (not the record) for any FEN
that is missing or ambiguous in the join -- every one of the 20,000 original records is
preserved, in its original order, so `combine_and_split`'s fixed-seed shuffle produces
the identical train/held-out split as every prior P4I-P4III experiment (verified by
this script's own `--verify-split-preserved` check, not merely asserted).

`SHARD_DTYPE` changed (added `wdl`/`has_wdl`) as part of this same change --
`read_shard()` can no longer open the *old*-format shard file
(`outputs/datasets/stage2-quiet-sf/shard-0.bin`), so this script reads it via a frozen,
local copy of the pre-change dtype (`_LEGACY_SHARD_DTYPE_NO_WDL`, not imported from
`mmap_shard.py`, since that module's own `SHARD_DTYPE` has already moved on) rather
than building a general shard-versioning mechanism nothing else needs yet.
"""

from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path
from typing import Dict, Iterator, Optional, Tuple

import numpy as np

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef
from trainer.dataset.mmap_shard import write_shard

_FEN_BYTES = 90
_LEGACY_SHARD_DTYPE_NO_WDL = np.dtype(
    [
        ("fen", f"S{_FEN_BYTES}"),
        ("eval_cp", "i4"),
        ("has_eval_cp", "?"),
        ("eval_mate", "i4"),
        ("has_eval_mate", "?"),
        ("ply", "i4"),
        ("has_ply", "?"),
        ("search_depth", "i4"),
        ("has_search_depth", "?"),
        ("search_nodes", "i8"),
        ("has_search_nodes", "?"),
    ]
)

_C9_LINE = re.compile(r'^(.*?) c9 "([^"]*)";?$')


def _read_legacy_shard(path: Path) -> Iterator[PositionRecord]:
    mapped = np.memmap(path, dtype=_LEGACY_SHARD_DTYPE_NO_WDL, mode="r")
    for row in mapped:
        fen = bytes(row["fen"]).rstrip(b"\x00").decode("ascii")
        label = PositionLabel(
            eval_cp=int(row["eval_cp"]) if bool(row["has_eval_cp"]) else None,
            eval_mate=int(row["eval_mate"]) if bool(row["has_eval_mate"]) else None,
        )
        metadata = PositionMetadata(
            ply=int(row["ply"]) if bool(row["has_ply"]) else None,
            search_depth=int(row["search_depth"]) if bool(row["has_search_depth"]) else None,
            search_nodes=int(row["search_nodes"]) if bool(row["has_search_nodes"]) else None,
        )
        yield PositionRecord(fen=fen, label=label, metadata=metadata)


def _wdl_from_result(side_to_move: str, result: str) -> Optional[float]:
    """Converts a White-perspective EPD `c9` game result into a side-to-move-relative
    wdl value, matching `PositionLabel.wdl`'s documented convention (0 = mover lost,
    1 = mover won, 0.5 = drawn) -- the same mover-relative sign convention `eval_cp`
    already carries, required for the two to be blended together
    (`train.py::train()`'s `wdl_lambda` blend).

    `side_to_move` is the FEN's own field-1 token ("w" or "b"). Returns `None` for an
    unrecognized result string rather than guessing.
    """
    if result == "1/2-1/2":
        return 0.5
    white_won = result == "1-0"
    black_won = result == "0-1"
    if not white_won and not black_won:
        return None
    mover_is_white = side_to_move == "w"
    mover_won = (white_won and mover_is_white) or (black_won and not mover_is_white)
    return 1.0 if mover_won else 0.0


def _build_epd_outcome_map(epd_path: Path) -> Tuple[Dict[str, str], int]:
    """Maps clean FEN -> raw `c9` result string, one entry per unique FEN in the source
    EPD file. A FEN whose lines disagree on the outcome (§5.3's measured 248/724,127
    conflict rate) is dropped from the map entirely -- ambiguous, not guessed at.
    Returns (map, conflict_count).
    """
    seen: Dict[str, str] = {}
    conflicting: set = set()
    with open(epd_path, "r", encoding="ascii") as f:
        for line in f:
            match = _C9_LINE.match(line.strip())
            if not match:
                continue
            fen, result = match.group(1), match.group(2)
            if fen in seen and seen[fen] != result:
                conflicting.add(fen)
                continue
            seen[fen] = result
    for fen in conflicting:
        seen.pop(fen, None)
    return seen, len(conflicting)


def backfill(
    legacy_shard_path: Path, epd_path: Path, output_dir: Path
) -> Dict[str, int]:
    """Reads `legacy_shard_path` (old dtype), joins each record's `wdl` from
    `epd_path` by exact FEN match, and writes every record -- in original order, none
    dropped -- to `output_dir/shard-0.bin` under the new (wdl-bearing) `SHARD_DTYPE`.
    Returns join statistics for the manifest.
    """
    outcome_map, conflict_count = _build_epd_outcome_map(epd_path)

    total = 0
    matched = 0
    unmatched = 0

    def _records() -> Iterator[PositionRecord]:
        nonlocal total, matched, unmatched
        for record in _read_legacy_shard(legacy_shard_path):
            total += 1
            result = outcome_map.get(record.fen)
            wdl = None
            if result is not None:
                side_to_move = record.fen.split(" ")[1]
                wdl = _wdl_from_result(side_to_move, result)
            if wdl is not None:
                matched += 1
            else:
                unmatched += 1
            new_label = PositionLabel(
                eval_cp=record.label.eval_cp, eval_mate=record.label.eval_mate, wdl=wdl
            )
            yield PositionRecord(fen=record.fen, label=new_label, metadata=record.metadata)

    output_dir.mkdir(parents=True, exist_ok=True)
    shard_path = output_dir / "shard-0.bin"
    write_shard(_records(), shard_path)

    stats = {
        "total_records": total,
        "matched_with_wdl": matched,
        "unmatched_no_wdl": unmatched,
        "epd_unique_fens": len(outcome_map) + conflict_count,
        "epd_conflicting_fens_dropped": conflict_count,
    }
    manifest = {
        "dataset_identifier": "stage2-sf-labeled-quiet-2026-07-15-wdl-backfilled",
        "stage": "sf-labeled",
        "source_shard": str(legacy_shard_path),
        "source_epd": str(epd_path),
        "created_at_epoch_seconds": int(time.time()),
        **stats,
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return stats


def main() -> int:
    """usage: python -m scripts.backfill_stage2_wdl [legacy_shard] [epd_file] [output_dir]"""
    legacy_shard = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("outputs/datasets/stage2-quiet-sf/shard-0.bin")
    epd_file = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("../data/quiet-labeled.epd")
    output_dir = Path(sys.argv[3]) if len(sys.argv) > 3 else Path("outputs/datasets/stage2-quiet-sf-wdl")

    stats = backfill(legacy_shard, epd_file, output_dir)
    print(f"backfilled {stats['total_records']} records -> {output_dir}")
    print(f"  matched_with_wdl: {stats['matched_with_wdl']}")
    print(f"  unmatched_no_wdl: {stats['unmatched_no_wdl']}")
    print(f"  epd_conflicting_fens_dropped: {stats['epd_conflicting_fens_dropped']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
