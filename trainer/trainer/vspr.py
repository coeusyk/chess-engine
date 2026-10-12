"""Minimal, strict Python decoder for the VSPR wire format, exactly as specified by
`docs/architecture/research/DR-220-vspr-wire-format.md` (V1, frozen). This module decodes bytes
into the semantic record types below and nothing else -- no shard writing, no split logic, no
manifests, no training-label blending, no sampling, no generator policy. Stage-3 ingestion
(#210) is a separate, not-yet-built consumer of this module, not something this module does
itself. There is deliberately no encoder here: #211 has no demonstrated consumer requirement for
one (its own task text: "Python need not encode unless there is a demonstrated consumer
requirement" -- the Java side already proves round-trip fidelity against the canonical fixtures).

Every bound below is cited from DR-220 section 7 and enforced explicitly, even though Python's
arbitrary-precision integers cannot overflow the way a naive Java cast could -- the task's own
instruction is not to rely on runtime behavior as validation, so every length/count field is
checked against its documented maximum before it is used to size a read, exactly mirroring the
Java codec's own checks (VsprCodec.java, engine-core).
"""

from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass
from enum import IntEnum
from typing import BinaryIO, List, Optional, Union

# ---- DR-220 section 4: magic/version ----
_MAGIC = b"VSPR"
_SUPPORTED_FORMAT_VERSION = 1

# ---- DR-220 section 7: bounds (decoder-allocation safety ceilings, not engine semantics) ----
_MAX_PLAYED_MOVE_COUNT = 65_536
_MAX_CANDIDATE_COUNT = 218
_MAX_PV_LENGTH = 128
_MAX_MECHANISM_NAME_BYTES = 32
_MAX_FEN_BYTES = 100
_MAX_NETWORK_UUID_BYTES = 128
_NETWORK_SHA256_BYTES = 32
_MAX_ENGINE_BUILD_ID_BYTES = 64
_MAX_NETWORK_PATH_BYTES = 512
_MAX_OPAQUE_PAYLOAD_BYTES = 4096
_MAX_FRAME_LENGTH = 64 * 1024 * 1024


class VsprFormatError(Exception):
    """A VSPR stream is malformed, truncated, or violates a DR-220 bound or validity rule --
    distinct from a plain OSError/IOError propagating from the underlying stream (a real I/O
    failure), so a caller (e.g. a future #210 ingester) can branch on `reason` without parsing
    message text.
    """

    def __init__(self, reason: str, message: str) -> None:
        super().__init__(message)
        self.reason = reason


# Reason constants -- match VsprCodec.VsprFormatException.Reason's names exactly (engine-core),
# so a cross-language bug report can cite one vocabulary.
BAD_MAGIC = "BAD_MAGIC"
UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
TRUNCATED = "TRUNCATED"
CRC_MISMATCH = "CRC_MISMATCH"
BOUND_VIOLATION = "BOUND_VIOLATION"
INVALID_ENUM = "INVALID_ENUM"
INVALID_PAIRING = "INVALID_PAIRING"
STRUCTURAL = "STRUCTURAL"


class GameOutcome(IntEnum):
    """Wire values are this enum's declared order (DR-220 section 6) -- never reorder."""
    WHITE_WIN = 0
    BLACK_WIN = 1
    DRAW = 2
    UNRESOLVED = 3


class TerminationReason(IntEnum):
    CHECKMATE = 0
    STALEMATE = 1
    THREEFOLD_REPETITION = 2
    FIFTY_MOVE_RULE = 3
    INSUFFICIENT_MATERIAL = 4
    ADJUDICATED_SCORE = 5
    MOVE_CAP = 6
    SEARCH_ABORT_OR_FAILURE = 7


class OutcomePerspective(IntEnum):
    WHITE = 0
    SIDE_TO_MOVE_AT_SAMPLE = 1


class ScoreKind(IntEnum):
    CP = 0
    MATE = 1


class SearchBudgetKind(IntEnum):
    DEPTH = 0
    NODES = 1
    TIME_MS = 2


class SelectionMechanismKind(IntEnum):
    BEST_MOVE = 0
    NAMED = 1


_VALID_PAIRINGS = {
    TerminationReason.CHECKMATE: {GameOutcome.WHITE_WIN, GameOutcome.BLACK_WIN},
    TerminationReason.STALEMATE: {GameOutcome.DRAW},
    TerminationReason.THREEFOLD_REPETITION: {GameOutcome.DRAW},
    TerminationReason.FIFTY_MOVE_RULE: {GameOutcome.DRAW},
    TerminationReason.INSUFFICIENT_MATERIAL: {GameOutcome.DRAW},
    TerminationReason.ADJUDICATED_SCORE: {
        GameOutcome.WHITE_WIN, GameOutcome.BLACK_WIN, GameOutcome.DRAW,
    },
    TerminationReason.MOVE_CAP: {GameOutcome.UNRESOLVED},
    TerminationReason.SEARCH_ABORT_OR_FAILURE: {GameOutcome.UNRESOLVED},
}


@dataclass(frozen=True)
class OpaqueConfig:
    schema_id: int
    payload: bytes


@dataclass(frozen=True)
class VsprHeader:
    format_version: int
    run_id: bytes
    created_at_epoch_seconds: int
    generator_network_uuid: str
    generator_network_sha256: bytes
    engine_build_id: str
    generator_network_path: str
    max_plies: int
    search_budget_kind: SearchBudgetKind
    search_budget_value: int
    reset_search_state_between_games: bool
    candidates_persisted: bool
    adjudication_config: Optional[OpaqueConfig]
    diversity_config: Optional[OpaqueConfig]


@dataclass(frozen=True)
class PlayedMoveDecision:
    move: int
    selection_mechanism_kind: SelectionMechanismKind
    mechanism_name: Optional[str]
    selection_seed: Optional[int]


@dataclass(frozen=True)
class TrainingSample:
    ply: int
    fen: str
    on_trajectory: bool
    eval_score_kind: ScoreKind
    eval_score: int
    search_budget_kind: SearchBudgetKind
    search_budget_value: int


@dataclass(frozen=True)
class SearchCandidate:
    move: int
    rank: int
    score_kind: ScoreKind
    score: int
    pv: List[int]
    complete: bool


@dataclass(frozen=True)
class CandidateSet:
    ply: int
    depth: int
    candidates: List[SearchCandidate]


@dataclass(frozen=True)
class GameFrame:
    game_id: int
    game_seed: Optional[int]
    game_outcome: GameOutcome
    termination_reason: TerminationReason
    outcome_perspective: OutcomePerspective
    played_moves: List[PlayedMoveDecision]
    samples: List[TrainingSample]
    candidate_sets: List[CandidateSet]


@dataclass(frozen=True)
class VsprFile:
    header: VsprHeader
    frames: List[GameFrame]


# =====================================================================================
# Byte-level primitives
# =====================================================================================


def _read_exact(stream: BinaryIO, n: int, what: str) -> bytes:
    data = stream.read(n)
    if len(data) != n:
        raise VsprFormatError(TRUNCATED, f"truncated while reading {what}")
    return data


def _read_u8(stream: BinaryIO, what: str) -> int:
    return _read_exact(stream, 1, what)[0]


def _read_u16(stream: BinaryIO, what: str) -> int:
    return struct.unpack(">H", _read_exact(stream, 2, what))[0]


def _read_u32(stream: BinaryIO, what: str) -> int:
    return struct.unpack(">I", _read_exact(stream, 4, what))[0]


def _read_i32(stream: BinaryIO, what: str) -> int:
    return struct.unpack(">i", _read_exact(stream, 4, what))[0]


def _read_i64(stream: BinaryIO, what: str) -> int:
    return struct.unpack(">q", _read_exact(stream, 8, what))[0]


def _read_bool(stream: BinaryIO, what: str) -> bool:
    b = _read_u8(stream, what)
    if b not in (0, 1):
        raise VsprFormatError(INVALID_ENUM, f"invalid bool byte {b} for {what} (must be 0 or 1)")
    return b == 1


def _read_enum(stream: BinaryIO, enum_type, what: str):
    value = _read_u8(stream, what)
    try:
        return enum_type(value)
    except ValueError:
        raise VsprFormatError(INVALID_ENUM, f"invalid {enum_type.__name__} tag {value}") from None


def _read_bounded_string(stream: BinaryIO, max_bytes: int, what: str, *, ascii_only: bool = False,
                          length_bits: int = 16) -> str:
    length = _read_u16(stream, f"{what} length") if length_bits == 16 \
        else _read_u8(stream, f"{what} length")
    if length > max_bytes:
        raise VsprFormatError(BOUND_VIOLATION,
                               f"{what} length {length} exceeds max {max_bytes}")
    data = _read_exact(stream, length, what)
    if ascii_only:
        for b in data:
            if b >= 0x80:
                raise VsprFormatError(STRUCTURAL, f"{what} contains a non-ASCII byte")
        return data.decode("ascii")
    return data.decode("utf-8")


def _validate_move_flag(packed_move: int, what: str) -> None:
    flag = (packed_move >> 12) & 0xF
    if flag > 8:
        raise VsprFormatError(INVALID_ENUM,
                               f"invalid packed Move flag nibble {flag} in {what} (must be 0-8)")


def _validate_pairing(outcome: GameOutcome, reason: TerminationReason) -> None:
    if outcome not in _VALID_PAIRINGS[reason]:
        raise VsprFormatError(
            INVALID_PAIRING,
            f"invalid (gameOutcome={outcome.name}, terminationReason={reason.name}) pairing "
            "(DR-220 section 6)",
        )


def _read_optional_i64(stream: BinaryIO, what: str) -> Optional[int]:
    return _read_i64(stream, what) if _read_bool(stream, f"{what} presence flag") else None


def _read_optional_opaque_config(stream: BinaryIO, what: str) -> Optional[OpaqueConfig]:
    if not _read_bool(stream, f"{what} presence flag"):
        return None
    schema_id = _read_u8(stream, f"{what}.schemaId")
    length = _read_u16(stream, f"{what}.payload length")
    if length > _MAX_OPAQUE_PAYLOAD_BYTES:
        raise VsprFormatError(BOUND_VIOLATION,
                               f"{what}.payload length {length} exceeds max "
                               f"{_MAX_OPAQUE_PAYLOAD_BYTES}")
    payload = _read_exact(stream, length, f"{what}.payload")
    return OpaqueConfig(schema_id, payload)


# =====================================================================================
# Header
# =====================================================================================


def read_header(stream: BinaryIO) -> VsprHeader:
    """Reads the fixed, 120-byte V1 header. Raises on bad magic, unsupported version, or a
    stream too short to contain a complete header -- there is no valid file identity to
    recover a partial read from (DR-220 section 9)."""
    magic = _read_exact(stream, len(_MAGIC), "magic")
    if magic != _MAGIC:
        raise VsprFormatError(BAD_MAGIC, "not a VSPR stream (bad magic bytes)")
    format_version = _read_i32(stream, "formatVersion")
    if format_version != _SUPPORTED_FORMAT_VERSION:
        raise VsprFormatError(
            UNSUPPORTED_VERSION,
            f"unsupported VSPR formatVersion {format_version} "
            f"(this decoder only supports {_SUPPORTED_FORMAT_VERSION})",
        )
    run_id = _read_exact(stream, 16, "runId")
    created_at_epoch_seconds = _read_i64(stream, "createdAtEpochSeconds")
    generator_network_uuid = _read_bounded_string(stream, _MAX_NETWORK_UUID_BYTES,
                                                   "generatorNetworkUuid")
    generator_network_sha256 = _read_exact(stream, _NETWORK_SHA256_BYTES,
                                            "generatorNetworkSha256")
    engine_build_id = _read_bounded_string(stream, _MAX_ENGINE_BUILD_ID_BYTES, "engineBuildId",
                                            length_bits=8)
    generator_network_path = _read_bounded_string(stream, _MAX_NETWORK_PATH_BYTES,
                                                   "generatorNetworkPath")
    max_plies = _read_i32(stream, "maxPlies")
    search_budget_kind = _read_enum(stream, SearchBudgetKind, "searchBudgetKind")
    search_budget_value = _read_i64(stream, "searchBudgetValue")
    reset_search_state_between_games = _read_bool(stream, "resetSearchStateBetweenGames")
    candidates_persisted = _read_bool(stream, "candidatesPersisted")
    adjudication_config = _read_optional_opaque_config(stream, "adjudicationConfig")
    diversity_config = _read_optional_opaque_config(stream, "diversityConfig")
    return VsprHeader(
        format_version=format_version,
        run_id=run_id,
        created_at_epoch_seconds=created_at_epoch_seconds,
        generator_network_uuid=generator_network_uuid,
        generator_network_sha256=generator_network_sha256,
        engine_build_id=engine_build_id,
        generator_network_path=generator_network_path,
        max_plies=max_plies,
        search_budget_kind=search_budget_kind,
        search_budget_value=search_budget_value,
        reset_search_state_between_games=reset_search_state_between_games,
        candidates_persisted=candidates_persisted,
        adjudication_config=adjudication_config,
        diversity_config=diversity_config,
    )


# =====================================================================================
# Frames
# =====================================================================================


def read_frame(stream: BinaryIO, candidates_persisted: bool) -> Optional[GameFrame]:
    """Reads exactly one GameFrame. Returns None only when the stream is cleanly exhausted
    before any byte of the next frame's frameLength field is read -- the sole condition meaning
    "no more games." Any other premature end-of-stream is a truncated frame and raises
    VsprFormatError(TRUNCATED, ...): a file whose only content is one incomplete frame must
    never decode as a valid, zero-game file (DR-220 section 9/11). This function performs no
    resume/recovery of any kind -- that is a separate, explicitly-invoked writer concern DR-220
    deliberately keeps out of normal decoding.
    """
    first = stream.read(1)
    if first == b"":
        return None
    rest = stream.read(3)
    if len(rest) != 3:
        raise VsprFormatError(TRUNCATED, "truncated frameLength field")
    frame_length = struct.unpack(">I", first + rest)[0]
    if frame_length > _MAX_FRAME_LENGTH:
        raise VsprFormatError(BOUND_VIOLATION,
                               f"frameLength {frame_length} exceeds max {_MAX_FRAME_LENGTH}")
    body = _read_exact(stream, frame_length, "frame body")
    declared_crc = struct.unpack(">I", _read_exact(stream, 4, "frame CRC-32"))[0]
    computed_crc = zlib.crc32(body) & 0xFFFFFFFF
    if computed_crc != declared_crc:
        raise VsprFormatError(
            CRC_MISMATCH,
            f"frame CRC-32 mismatch: declared {declared_crc}, computed {computed_crc}",
        )
    return _decode_frame_body(body, candidates_persisted)


def _decode_frame_body(body: bytes, candidates_persisted: bool) -> GameFrame:
    import io
    stream = io.BytesIO(body)

    game_id = _read_i64(stream, "gameId")
    game_seed = _read_optional_i64(stream, "gameSeed")
    outcome = _read_enum(stream, GameOutcome, "gameOutcome")
    reason = _read_enum(stream, TerminationReason, "terminationReason")
    perspective = _read_enum(stream, OutcomePerspective, "outcomePerspective")
    _validate_pairing(outcome, reason)

    played_move_count = _read_u32(stream, "playedMoveCount")
    if played_move_count > _MAX_PLAYED_MOVE_COUNT:
        raise VsprFormatError(BOUND_VIOLATION,
                               f"playedMoveCount {played_move_count} exceeds max "
                               f"{_MAX_PLAYED_MOVE_COUNT}")
    played_moves = [_read_played_move_decision(stream) for _ in range(played_move_count)]

    sample_count = _read_u32(stream, "sampleCount")
    if sample_count > played_move_count:
        raise VsprFormatError(BOUND_VIOLATION,
                               f"sampleCount {sample_count} exceeds playedMoveCount "
                               f"{played_move_count}")
    samples = []
    for _ in range(sample_count):
        sample = _read_training_sample(stream)
        if sample.ply >= played_move_count:
            raise VsprFormatError(STRUCTURAL,
                                   f"sample ply {sample.ply} >= playedMoveCount "
                                   f"{played_move_count}")
        samples.append(sample)

    candidate_sets: List[CandidateSet] = []
    if candidates_persisted:
        candidate_set_count = _read_u32(stream, "candidateSetCount")
        if candidate_set_count > played_move_count:
            raise VsprFormatError(BOUND_VIOLATION,
                                   f"candidateSetCount {candidate_set_count} exceeds "
                                   f"playedMoveCount {played_move_count}")
        for _ in range(candidate_set_count):
            cset = _read_candidate_set(stream)
            if cset.ply >= played_move_count:
                raise VsprFormatError(STRUCTURAL,
                                       f"candidate set ply {cset.ply} >= playedMoveCount "
                                       f"{played_move_count}")
            candidate_sets.append(cset)

    trailing = stream.read()
    if trailing:
        raise VsprFormatError(STRUCTURAL,
                               f"frame body has {len(trailing)} trailing byte(s) beyond its "
                               "declared structure")

    return GameFrame(game_id, game_seed, outcome, reason, perspective, played_moves, samples,
                      candidate_sets)


def _read_played_move_decision(stream: BinaryIO) -> PlayedMoveDecision:
    move = _read_u16(stream, "move")
    _validate_move_flag(move, "PlayedMoveDecision.move")
    kind = _read_enum(stream, SelectionMechanismKind, "selectionMechanismKind")
    mechanism_name = None
    if kind == SelectionMechanismKind.NAMED:
        mechanism_name = _read_bounded_string(stream, _MAX_MECHANISM_NAME_BYTES,
                                               "mechanismName", length_bits=8)
    selection_seed = _read_optional_i64(stream, "selectionSeed")
    return PlayedMoveDecision(move, kind, mechanism_name, selection_seed)


def _read_training_sample(stream: BinaryIO) -> TrainingSample:
    ply = _read_u32(stream, "ply")
    fen = _read_bounded_string(stream, _MAX_FEN_BYTES, "fen", ascii_only=True, length_bits=8)
    on_trajectory = _read_bool(stream, "onTrajectory")
    score_kind = _read_enum(stream, ScoreKind, "evalScoreKind")
    score = _read_i32(stream, "evalScore")
    budget_kind = _read_enum(stream, SearchBudgetKind, "searchBudgetKind")
    budget_value = _read_i64(stream, "searchBudgetValue")
    return TrainingSample(ply, fen, on_trajectory, score_kind, score, budget_kind, budget_value)


def _read_candidate_set(stream: BinaryIO) -> CandidateSet:
    ply = _read_u32(stream, "ply")
    depth = _read_i32(stream, "depth")
    candidate_count = _read_u16(stream, "candidateCount")
    if candidate_count > _MAX_CANDIDATE_COUNT:
        raise VsprFormatError(BOUND_VIOLATION,
                               f"candidateCount {candidate_count} exceeds max "
                               f"{_MAX_CANDIDATE_COUNT}")
    candidates = [_read_search_candidate(stream) for _ in range(candidate_count)]
    return CandidateSet(ply, depth, candidates)


def _read_search_candidate(stream: BinaryIO) -> SearchCandidate:
    move = _read_u16(stream, "SearchCandidate.move")
    _validate_move_flag(move, "SearchCandidate.move")
    rank = _read_u16(stream, "rank")
    score_kind = _read_enum(stream, ScoreKind, "scoreKind")
    score = _read_i32(stream, "score")
    pv_length = _read_u16(stream, "pvLength")
    if pv_length > _MAX_PV_LENGTH:
        raise VsprFormatError(BOUND_VIOLATION,
                               f"pvLength {pv_length} exceeds max {_MAX_PV_LENGTH}")
    pv = []
    for _ in range(pv_length):
        pv_move = _read_u16(stream, "pv move")
        _validate_move_flag(pv_move, "SearchCandidate.pv")
        pv.append(pv_move)
    complete = _read_bool(stream, "complete")
    return SearchCandidate(move, rank, score_kind, score, pv, complete)


# =====================================================================================
# Public entry point
# =====================================================================================


def read(source: Union[BinaryIO, bytes]) -> VsprFile:
    """Decodes a full VSPR stream: the header, then every frame until clean end-of-file."""
    stream: BinaryIO = __import__("io").BytesIO(source) if isinstance(source, bytes) else source
    header = read_header(stream)
    frames: List[GameFrame] = []
    while True:
        frame = read_frame(stream, header.candidates_persisted)
        if frame is None:
            break
        frames.append(frame)
    return VsprFile(header, frames)
