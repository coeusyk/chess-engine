"""Minimal, test-only VSPR V1 byte encoder (DR-220-vspr-wire-format.md), used
to construct synthetic fixtures for #210 ingestion tests that need specific
values the canonical golden fixtures (`fixtures/vspr/`) don't cover -- a
second `runId`, a raw `gameId=0`, deliberately conflicting duplicate content,
and so on. Deliberately not part of `trainer.vspr` itself (that module's own
docstring: no encoder exists there since #211 found no demonstrated consumer
requirement for one) -- this is test fixture construction only, mirrored by
hand against the same spec `trainer.vspr`'s decoder already implements, not a
second production encoder.
"""

from __future__ import annotations

import struct
import zlib
from typing import List, Optional

DEPTH = 0
NODES = 1
TIME_MS = 2

BEST_MOVE = 0

WHITE_WIN = 0
BLACK_WIN = 1
DRAW = 2
UNRESOLVED = 3

CHECKMATE = 0
STALEMATE = 1
THREEFOLD_REPETITION = 2
FIFTY_MOVE_RULE = 3
INSUFFICIENT_MATERIAL = 4
ADJUDICATED_SCORE = 5
MOVE_CAP = 6
SEARCH_ABORT_OR_FAILURE = 7

PERSPECTIVE_WHITE = 0
PERSPECTIVE_SIDE_TO_MOVE_AT_SAMPLE = 1

CP = 0
MATE = 1


def _bounded_string(s: str, length_bits: int = 16) -> bytes:
    data = s.encode("utf-8")
    if length_bits == 8:
        return struct.pack(">B", len(data)) + data
    return struct.pack(">H", len(data)) + data


def build_header(
    run_id: bytes,
    *,
    format_version: int = 1,
    created_at_epoch_seconds: int = 1_700_000_000,
    generator_network_uuid: str = "test-net",
    generator_network_sha256: bytes = b"\x00" * 32,
    engine_build_id: str = "test-build",
    generator_network_path: str = "",
    max_plies: int = 500,
    search_budget_kind: int = DEPTH,
    search_budget_value: int = 6,
    reset_search_state_between_games: bool = True,
    candidates_persisted: bool = False,
) -> bytes:
    assert len(run_id) == 16
    assert len(generator_network_sha256) == 32
    out = bytearray()
    out += b"VSPR"
    out += struct.pack(">i", format_version)
    out += run_id
    out += struct.pack(">q", created_at_epoch_seconds)
    out += _bounded_string(generator_network_uuid, 16)
    out += generator_network_sha256
    out += _bounded_string(engine_build_id, 8)
    out += _bounded_string(generator_network_path, 16)
    out += struct.pack(">i", max_plies)
    out += struct.pack(">B", search_budget_kind)
    out += struct.pack(">q", search_budget_value)
    out += struct.pack(">B", 1 if reset_search_state_between_games else 0)
    out += struct.pack(">B", 1 if candidates_persisted else 0)
    out += struct.pack(">B", 0)  # adjudicationConfig absent
    out += struct.pack(">B", 0)  # diversityConfig absent
    return bytes(out)


def build_sample(
    ply: int,
    fen: str,
    *,
    eval_score_kind: int = CP,
    eval_score: int = 10,
    search_budget_kind: int = DEPTH,
    search_budget_value: int = 6,
) -> dict:
    return {
        "ply": ply,
        "fen": fen,
        "eval_score_kind": eval_score_kind,
        "eval_score": eval_score,
        "search_budget_kind": search_budget_kind,
        "search_budget_value": search_budget_value,
    }


def build_frame(
    game_id: int,
    *,
    game_outcome: int,
    termination_reason: int = ADJUDICATED_SCORE,
    outcome_perspective: int = PERSPECTIVE_WHITE,
    samples: Optional[List[dict]] = None,
    played_move_count: Optional[int] = None,
    game_seed: Optional[int] = None,
) -> bytes:
    samples = samples or []
    if played_move_count is None:
        played_move_count = (max((s["ply"] for s in samples), default=-1) + 1) or 1

    body = bytearray()
    body += struct.pack(">q", game_id)
    body += struct.pack(">B", 1) + struct.pack(">q", game_seed) if game_seed is not None else struct.pack(">B", 0)
    body += struct.pack(">B", game_outcome)
    body += struct.pack(">B", termination_reason)
    body += struct.pack(">B", outcome_perspective)
    body += struct.pack(">I", played_move_count)
    for _ in range(played_move_count):
        body += struct.pack(">H", 0x0000)  # move (flag nibble 0 = normal)
        body += struct.pack(">B", BEST_MOVE)  # selectionMechanismKind
        body += struct.pack(">B", 0)  # selectionSeed absent
    body += struct.pack(">I", len(samples))
    for s in samples:
        body += struct.pack(">I", s["ply"])
        fen_bytes = s["fen"].encode("ascii")
        body += struct.pack(">B", len(fen_bytes)) + fen_bytes
        body += struct.pack(">B", 1)  # onTrajectory
        body += struct.pack(">B", s["eval_score_kind"])
        body += struct.pack(">i", s["eval_score"])
        body += struct.pack(">B", s["search_budget_kind"])
        body += struct.pack(">q", s["search_budget_value"])
    # candidatesPersisted assumed False in every test fixture -- no candidate section.
    body = bytes(body)
    crc = zlib.crc32(body) & 0xFFFFFFFF
    return struct.pack(">I", len(body)) + body + struct.pack(">I", crc)


def build_vspr_bytes(run_id_hex: str, frames: List[bytes], **header_kwargs) -> bytes:
    run_id = bytes.fromhex(run_id_hex.zfill(32))
    return build_header(run_id, **header_kwargs) + b"".join(frames)
