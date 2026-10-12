"""Stage 3 self-play ingestion (#210): VSPR V1 files -> V1 mmap shard(s) +
dataset-generation manifest. Narrow responsibility, matching the layering
#210's own task text lays out:

    trainer.vspr        -- VSPR bytes -> semantic records (decode only, not duplicated here)
    selfplay_ingest      -- semantic records -> V1 shard + dataset manifest (this module)
    mmap_shard           -- V1 physical shard format (write_shard(), not duplicated here)
    SelfPlayProvider      -- consumes already-ingested shards (separate module, no VSPR import)

## Corrected scope: samples are authoritative, never resampled

#210's original scope text said ingestion may "sample positions" -- read *from
this module's own responsibility*, not as license to resample the played game
trajectory. Every VSPR `TrainingSample` is already the search's own sampling
decision, made once, by whatever policy `GameLoop` used to generate it
(DR-E9 section 6's key invariant: a sample's search label belongs to its exact
recorded FEN). This module decodes `TrainingSample` records and writes one
shard record per sample, unchanged:

    decode TrainingSample -> preserve its exact FEN/eval label/outcome provenance -> write one shard record

It does NOT, and must never:
  - create additional positions from `playedMoves`
  - drop or select samples under a new sampling policy of its own
  - rerun any sampling
  - infer a label from `GameRecord` independently of the sample that already carries one

Any future resampling policy is a separate, later research/transform concern -- not this
ingester's job, and not something a `Transform` stage (`trainer.dataset.transform`) should
silently be handed either without being designed for it explicitly.

## game_id remapping

VSPR's own `gameId` is unique only within one file's `runId` (DR-220 section 9: "one VSPR
file = one run"). This module's dataset-local `game_id` (per #207) is defined as a
deterministic function of `(runId, gameId)` pairs, assigned in strict input-file order,
first-occurrence order within each file: the first new `(runId, gameId)` seen gets the next
integer starting at 0; every later sample from that same game reuses it. Two different runs
whose raw `gameId` values happen to collide numerically never collide in the output, since the
key is the pair, not the bare `gameId`.

## Duplicate / replay semantics (fail-closed)

- The exact same source file passed twice in one `ingest()` call is rejected up front (SHA-256
  compared across every input before any decoding starts).
- The same `(runId, gameId)` appearing twice (e.g. across two input files) with byte-identical
  sample/outcome content is *not* re-emitted a second time -- the dataset-local ID already
  assigned to it is reused, and its second occurrence is silently skipped, not duplicated.
- The same `(runId, gameId)` appearing twice with *conflicting* content (different outcome,
  different samples) is a hard error -- this module refuses to silently pick one version.

## Resume: explicitly deferred, not half-implemented

A correct resume mode needs to reload a prior run's exact game-ID mapping state, verify the new
input set doesn't redefine an already-committed `(runId, gameId)` differently, and continue IDs
monotonically -- while `write_shard()`'s own atomicity model produces one complete shard file per
call, not an appendable one. Building that safely is a real subsystem, not a flag. Per #210's own
instruction not to ship a half-correct resume mode, this module supports only new, from-scratch
ingestion into a not-yet-existing output directory; `--resume` on the CLI fails immediately with
an explicit message rather than silently behaving like `new` mode.

## WDL perspective

`GameOutcome` is per-game (`whiteWin`/`blackWin`/`draw`/`unresolved`); `outcomePerspective`
states which convention it's recorded under (DR-220 section 6, DR-E9 section 9 -- deliberately
left open by both documents for #210 to decide explicitly):

  - `WHITE`: `gameOutcome` names the actual chess color that won. Converting to this shard
    format's mover-relative `PositionLabel.wdl` (1.0 = the sampled position's side to move won,
    0.0 = lost, 0.5 = drawn) requires reading the sample's own FEN side-to-move field and
    flipping when it's Black to move -- the same sign convention `backfill_stage2_wdl.py`
    already established for Stage 2 (`_wdl_from_result`), applied here identically for
    consistency across the codebase's one WDL convention.
  - `SIDE_TO_MOVE_AT_SAMPLE`: `gameOutcome`'s `whiteWin`/`blackWin` tags are already stated
    relative to whichever side is to move at each sample, so `whiteWin` means "the mover at this
    sample won" directly -- no FEN side-to-move lookup needed or correct to do here (reading the
    FEN in this branch would silently reapply the *other* perspective's logic to a value that's
    already mover-relative).

`unresolved` outcomes never produce a WDL target (`has_wdl=false`); a sample's own `eval_cp`/
`eval_mate` label is preserved regardless -- an infrastructure-terminated game still carries a
real search evaluation at each sample, only its final game result is unknown, and unknown must
never be silently written as drawn (0.5) or any other fabricated value.

## No blending

`eval_cp`, `eval_mate`, and `wdl` are stored as three independent, unblended fields exactly per
the existing `PositionLabel`/shard contract -- `wdl_lambda`, `K` scaling, `target_cp()`, and any
CP/mate/outcome interpolation are training-time concerns (`TrainingConfig`/#208), out of scope
here by design, not merely unimplemented.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path
from typing import Dict, Iterator, List, Optional, Tuple

from trainer import vspr
from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.mmap_shard import _read_header, write_shard
from trainer.dataset.selfplay_manifest import MANIFEST_VERSION, validate_selfplay_manifest


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class ConflictingGameError(ValueError):
    """The same (runId, gameId) was seen twice across the input files with
    different content -- refuses to silently pick either version."""


class DuplicateSourceError(ValueError):
    """Two input paths are byte-identical (same SHA-256)."""


def _frame_fingerprint(frame: "vspr.GameFrame") -> bytes:
    """A content fingerprint over exactly the fields this module actually
    reads out of a frame (outcome/termination/perspective + every sample's
    label-relevant fields) -- deliberately excludes `playedMoves`, which this
    module never touches (see module docstring: no positions are ever derived
    from played moves, so a change to them alone is not a conflict this
    ingester's output would even see).
    """
    parts = [str(int(frame.game_outcome)), str(int(frame.termination_reason)), str(int(frame.outcome_perspective))]
    for s in frame.samples:
        parts.append(
            f"{s.ply}|{s.fen}|{int(s.eval_score_kind)}|{s.eval_score}|"
            f"{int(s.search_budget_kind)}|{s.search_budget_value}"
        )
    return hashlib.sha256("\x00".join(parts).encode("utf-8")).digest()


class _GameIdRemapper:
    """Assigns dataset-local int64 game IDs to `(runId, gameId)` pairs in
    strict first-occurrence order, and builds the manifest's compact
    contiguous-range representation of that mapping as it goes -- one range
    per maximal run of consecutive `(same runId, raw gameId += 1)` pairs
    mapped to consecutive dataset-local IDs (the common case for a self-play
    generator that assigns `gameId` sequentially per run), falling back to a
    length-one range whenever that doesn't hold. Never stores a full frame in
    memory -- only a small fingerprint per distinct game.
    """

    def __init__(self) -> None:
        self._next_id = 0
        self._seen: Dict[Tuple[str, int], Tuple[int, bytes]] = {}
        self._ranges: List[dict] = []
        self._open_range: Optional[dict] = None

    @property
    def game_count(self) -> int:
        return len(self._seen)

    def assign(self, run_id_hex: str, frame: "vspr.GameFrame") -> Optional[int]:
        """Returns the dataset-local game_id for this frame, or `None` if this
        is an exact duplicate of an already-assigned game (caller must skip
        re-emitting its samples). Raises `ConflictingGameError` if the same
        `(runId, gameId)` was already assigned different content.
        """
        key = (run_id_hex, frame.game_id)
        fingerprint = _frame_fingerprint(frame)

        existing = self._seen.get(key)
        if existing is not None:
            existing_id, existing_fingerprint = existing
            if fingerprint != existing_fingerprint:
                raise ConflictingGameError(
                    f"conflicting duplicate content for (runId={run_id_hex}, gameId={frame.game_id}): "
                    "already ingested with different outcome/sample content -- refusing to pick "
                    "either version silently"
                )
            return None  # identical duplicate -- already counted, do not re-emit

        local_id = self._next_id
        self._next_id += 1
        self._seen[key] = (local_id, fingerprint)
        self._extend_range(run_id_hex, frame.game_id, local_id)
        return local_id

    def _extend_range(self, run_id_hex: str, raw_game_id: int, local_id: int) -> None:
        o = self._open_range
        if o is not None:
            span = o["raw_game_id_end"] - o["raw_game_id_start"]
            if (
                o["run_id"] == run_id_hex
                and o["raw_game_id_end"] + 1 == raw_game_id
                and o["dataset_local_start"] + span + 1 == local_id
            ):
                o["raw_game_id_end"] = raw_game_id
                return
            self._ranges.append(o)
        self._open_range = {
            "run_id": run_id_hex,
            "raw_game_id_start": raw_game_id,
            "raw_game_id_end": raw_game_id,
            "dataset_local_start": local_id,
        }

    def finalize_ranges(self) -> List[dict]:
        if self._open_range is not None:
            self._ranges.append(self._open_range)
            self._open_range = None
        return self._ranges


def _mover_wdl(frame: "vspr.GameFrame", side_to_move: str) -> Optional[float]:
    if frame.game_outcome == vspr.GameOutcome.UNRESOLVED:
        return None
    if frame.game_outcome == vspr.GameOutcome.DRAW:
        return 0.5
    if frame.outcome_perspective == vspr.OutcomePerspective.SIDE_TO_MOVE_AT_SAMPLE:
        # Already mover-relative by definition -- reading the FEN here would
        # incorrectly reapply the WHITE-perspective flip to a value that
        # doesn't use one.
        return 1.0 if frame.game_outcome == vspr.GameOutcome.WHITE_WIN else 0.0
    # outcome_perspective == WHITE: gameOutcome names the actual chess color.
    white_result = 1.0 if frame.game_outcome == vspr.GameOutcome.WHITE_WIN else 0.0
    return white_result if side_to_move == "w" else 1.0 - white_result


def _sample_to_position_record(
    sample: "vspr.TrainingSample", frame: "vspr.GameFrame", local_game_id: int
) -> PositionRecord:
    side_to_move = sample.fen.split(" ")[1]  # never inferred from ply parity
    eval_cp = sample.eval_score if sample.eval_score_kind == vspr.ScoreKind.CP else None
    eval_mate = sample.eval_score if sample.eval_score_kind == vspr.ScoreKind.MATE else None
    wdl = _mover_wdl(frame, side_to_move)

    search_depth = None
    search_nodes = None
    if sample.search_budget_kind == vspr.SearchBudgetKind.DEPTH:
        search_depth = sample.search_budget_value
    elif sample.search_budget_kind == vspr.SearchBudgetKind.NODES:
        search_nodes = sample.search_budget_value
    # TIME_MS has no corresponding shard field (PositionMetadata has no
    # time-budget field) -- dropped, not fabricated into the wrong unit.

    label = PositionLabel(eval_cp=eval_cp, eval_mate=eval_mate, wdl=wdl)
    metadata = PositionMetadata(
        ply=sample.ply, game_id=local_game_id, search_depth=search_depth, search_nodes=search_nodes
    )
    return PositionRecord(fen=sample.fen, label=label, metadata=metadata)


def ingest(vspr_paths: List[Path], output_dir: Path, dataset_identifier: str) -> dict:
    """Ingests one or more VSPR V1 files into a new V1 shard + manifest under
    `output_dir`. `output_dir` must not already contain `shard-0.bin` or
    `manifest.json` -- this function only ever creates a new dataset, never
    silently overwrites one (see module docstring: resume is deferred).
    Bounded streaming throughout: no full corpus of frames or samples is ever
    held in memory, only one frame at a time (via `vspr.read_frame`) and one
    `write_shard()` batch at a time (10,000 records, per `mmap_shard.py`).
    """
    vspr_paths = [Path(p) for p in vspr_paths]
    if not vspr_paths:
        raise ValueError("ingest() requires at least one VSPR input file")

    output_dir = Path(output_dir)
    manifest_path = output_dir / "manifest.json"
    shard_path = output_dir / "shard-0.bin"
    if manifest_path.exists() or shard_path.exists():
        raise FileExistsError(
            f"{output_dir} already has ingestion output -- refusing to overwrite an existing dataset"
        )

    source_shas = [_sha256_file(p) for p in vspr_paths]
    seen_shas: Dict[str, Path] = {}
    for path, sha in zip(vspr_paths, source_shas):
        if sha in seen_shas:
            raise DuplicateSourceError(
                f"duplicate source artifact: {path} is byte-identical to {seen_shas[sha]} "
                f"(sha256={sha}) -- pass each source file at most once per ingestion"
            )
        seen_shas[sha] = path

    remapper = _GameIdRemapper()
    sources_meta: List[dict] = []
    record_counter = {"n": 0}

    def records() -> Iterator[PositionRecord]:
        for path, sha in zip(vspr_paths, source_shas):
            with open(path, "rb") as f:
                header = vspr.read_header(f)
                run_id_hex = header.run_id.hex()
                sources_meta.append(
                    {
                        "path": path.name,
                        "sha256": sha,
                        "run_id": run_id_hex,
                        "generator_network_uuid": header.generator_network_uuid,
                        "generator_network_sha256": header.generator_network_sha256.hex(),
                        "engine_build_id": header.engine_build_id,
                        "format_version": header.format_version,
                    }
                )
                while True:
                    frame = vspr.read_frame(f, header.candidates_persisted)
                    if frame is None:
                        break
                    local_gid = remapper.assign(run_id_hex, frame)
                    if local_gid is None:
                        continue  # identical duplicate -- already ingested once, skip
                    for sample in frame.samples:
                        record_counter["n"] += 1
                        yield _sample_to_position_record(sample, frame, local_gid)

    output_dir.mkdir(parents=True, exist_ok=True)
    shard_ref = write_shard(records(), shard_path)

    if shard_ref.position_count != record_counter["n"]:
        raise AssertionError(  # pragma: no cover -- internal bookkeeping invariant
            f"ingested record count disagreement: write_shard reported {shard_ref.position_count}, "
            f"internal counter reported {record_counter['n']}"
        )

    # O(1) re-validation of the just-committed shard's own header, distinct
    # from trusting write_shard()'s return value alone.
    header_check = _read_header(shard_path)
    if header_check.record_count != shard_ref.position_count:
        raise AssertionError(  # pragma: no cover
            f"committed shard header record_count {header_check.record_count} != "
            f"write_shard() count {shard_ref.position_count}"
        )

    shard_sha = _sha256_file(shard_path)

    manifest = {
        "manifest_version": MANIFEST_VERSION,
        "dataset_identifier": dataset_identifier,
        "created_at_epoch_seconds": int(time.time()),
        "output_shards": [
            {"path": shard_path.name, "sha256": shard_sha, "record_count": shard_ref.position_count}
        ],
        "game_count": remapper.game_count,
        "record_count": shard_ref.position_count,
        "sources": sources_meta,
        "game_id_mapping": {
            "rule": "first-occurrence-in-file-order",
            "ranges": remapper.finalize_ranges(),
        },
        "assessment": {"status": "unassessed", "outcome": None, "evidence_ref": None},
    }
    validate_selfplay_manifest(manifest)

    tmp_manifest_path = output_dir / ".manifest.json.tmp"
    tmp_manifest_path.write_text(json.dumps(manifest, indent=2))
    os.replace(tmp_manifest_path, manifest_path)  # atomic, and committed last

    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vspr_files", nargs="+", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--dataset-identifier", required=True)
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Not implemented -- see this script's module docstring ('Resume: explicitly "
        "deferred, not half-implemented'). Fails immediately instead of silently behaving "
        "like a new ingestion.",
    )
    args = parser.parse_args()

    if args.resume:
        print(
            "selfplay_ingest.py: --resume is not implemented (deferred by design -- see module "
            "docstring); run without --resume into a fresh output directory instead.",
            file=sys.stderr,
        )
        return 2

    manifest = ingest(args.vspr_files, args.output_dir, args.dataset_identifier)
    print(
        f"ingested {manifest['game_count']} games, {manifest['record_count']} records "
        f"from {len(manifest['sources'])} source file(s) -> {args.output_dir}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
