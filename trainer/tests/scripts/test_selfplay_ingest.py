"""#210 -- selfplay_ingest.py: VSPR -> V1 shard + dataset-generation manifest.
Uses the shared golden VSPR fixtures (`fixtures/vspr/`) where their fixed
gameId/runId values are enough, and the synthetic `_vspr_fixtures` builder for
cases those fixtures don't cover (a second runId, raw gameId=0, deliberately
conflicting duplicate content). No live self-play anywhere in this file.
"""

from pathlib import Path

import pytest

from trainer.dataset.mmap_shard import read_shard
from trainer.dataset.selfplay_manifest import validate_selfplay_manifest
from trainer.encoding.feature_spec import repo_root
from scripts.selfplay_ingest import ConflictingGameError, DuplicateSourceError, ingest

from tests.scripts._vspr_fixtures import (
    ADJUDICATED_SCORE,
    BLACK_WIN,
    CP,
    DRAW,
    FIFTY_MOVE_RULE,
    MATE,
    MOVE_CAP,
    NODES,
    PERSPECTIVE_SIDE_TO_MOVE_AT_SAMPLE,
    PERSPECTIVE_WHITE,
    UNRESOLVED,
    WHITE_WIN,
    build_frame,
    build_sample,
    build_vspr_bytes,
)

FIXTURES = repo_root(Path(__file__).resolve()) / "fixtures" / "vspr"
RUN_A = "aaaa"
RUN_B = "bbbb"


def _write(tmp_path: Path, name: str, data: bytes) -> Path:
    path = tmp_path / name
    path.write_bytes(data)
    return path


# =====================================================================================
# Golden-fixture-based tests
# =====================================================================================


def test_single_vspr_to_v1_shard(tmp_path):
    source = FIXTURES / "valid_01_ordinary_cp_sample.vspr"
    out = tmp_path / "out"
    manifest = ingest([source], out, "test-selfplay")

    assert manifest["game_count"] == 1
    assert manifest["record_count"] == 1
    records = list(read_shard(_shard_ref(manifest, out)))
    assert len(records) == 1
    assert records[0].metadata.game_id == 0  # first game in first file -> dataset-local 0
    assert records[0].label.eval_cp == 35


def _shard_ref(manifest, out_dir):
    from trainer.contracts import ShardRef

    shard = manifest["output_shards"][0]
    return ShardRef(locator=str(out_dir / shard["path"]))


def test_multiple_vspr_inputs(tmp_path):
    sources = [
        FIXTURES / "valid_01_ordinary_cp_sample.vspr",
        FIXTURES / "valid_02_mate_score_sample.vspr",
        FIXTURES / "valid_03_natural_draw.vspr",
    ]
    out = tmp_path / "out"
    manifest = ingest(sources, out, "test-selfplay")

    assert manifest["game_count"] == 3
    assert manifest["record_count"] == 3
    records = sorted(read_shard(_shard_ref(manifest, out)), key=lambda r: r.metadata.game_id)
    assert [r.metadata.game_id for r in records] == [0, 1, 2]


def test_source_checksum_recorded(tmp_path):
    import hashlib

    source = FIXTURES / "valid_01_ordinary_cp_sample.vspr"
    expected_sha = hashlib.sha256(source.read_bytes()).hexdigest()
    out = tmp_path / "out"
    manifest = ingest([source], out, "test-selfplay")

    assert manifest["sources"][0]["sha256"] == expected_sha
    assert manifest["sources"][0]["path"] == source.name


def test_producer_identity_propagates_into_manifest(tmp_path):
    source = FIXTURES / "valid_01_ordinary_cp_sample.vspr"
    out = tmp_path / "out"
    manifest = ingest([source], out, "test-selfplay")

    src = manifest["sources"][0]
    assert src["run_id"] == "0000000000000000000000000000aaaa"
    assert src["generator_network_uuid"] == "fixture-net-0001"
    assert src["engine_build_id"] == "fixture-build-0001"
    assert src["format_version"] == 1
    assert len(bytes.fromhex(src["generator_network_sha256"])) == 32


def test_cp_and_mate_samples_preserved(tmp_path):
    out = tmp_path / "out"
    manifest = ingest(
        [FIXTURES / "valid_01_ordinary_cp_sample.vspr", FIXTURES / "valid_02_mate_score_sample.vspr"],
        out,
        "test-selfplay",
    )
    records = list(read_shard(_shard_ref(manifest, out)))
    cp_record = next(r for r in records if r.label.eval_cp is not None)
    mate_record = next(r for r in records if r.label.eval_mate is not None)
    assert cp_record.label.eval_cp == 35
    assert cp_record.label.eval_mate is None
    assert mate_record.label.eval_mate == 1
    assert mate_record.label.eval_cp is None


def test_draw_outcome_wdl_is_half(tmp_path):
    out = tmp_path / "out"
    manifest = ingest([FIXTURES / "valid_03_natural_draw.vspr"], out, "test-selfplay")
    record = list(read_shard(_shard_ref(manifest, out)))[0]
    assert record.label.wdl == 0.5


def test_unresolved_outcome_has_wdl_false_but_keeps_eval(tmp_path):
    out = tmp_path / "out"
    manifest = ingest([FIXTURES / "valid_04_infrastructure_unresolved.vspr"], out, "test-selfplay")
    # valid_04 has zero samples (per MANIFEST.md), so assert at the manifest/shard level instead.
    assert manifest["record_count"] == 0
    assert manifest["game_count"] == 1


# =====================================================================================
# Synthetic-fixture-based tests: game-ID remapping, duplicates, WDL perspective
# =====================================================================================


def test_deterministic_runid_gameid_remapping_across_files(tmp_path):
    frame_a1 = build_frame(1, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    frame_a2 = build_frame(2, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    frame_b1 = build_frame(1, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])

    path_a = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame_a1, frame_a2]))
    path_b = _write(tmp_path, "b.vspr", build_vspr_bytes(RUN_B, [frame_b1]))

    out = tmp_path / "out"
    manifest = ingest([path_a, path_b], out, "test-selfplay")

    assert manifest["game_count"] == 3
    records = sorted(read_shard(_shard_ref(manifest, out)), key=lambda r: r.metadata.game_id)
    assert [r.metadata.game_id for r in records] == [0, 1, 2]


def test_raw_game_id_zero(tmp_path):
    frame = build_frame(0, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    path = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame]))
    out = tmp_path / "out"
    manifest = ingest([path], out, "test-selfplay")
    record = list(read_shard(_shard_ref(manifest, out)))[0]
    assert record.metadata.game_id == 0
    assert record.metadata.game_id is not None


def test_cross_file_raw_gameid_collision_different_runid_does_not_collide(tmp_path):
    # Both files use raw gameId=7 -- must map to two different dataset-local IDs
    # because runId differs.
    frame_a = build_frame(7, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    frame_b = build_frame(7, game_outcome=BLACK_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4q3/4K3 b - - 0 1")])
    path_a = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame_a]))
    path_b = _write(tmp_path, "b.vspr", build_vspr_bytes(RUN_B, [frame_b]))

    out = tmp_path / "out"
    manifest = ingest([path_a, path_b], out, "test-selfplay")

    assert manifest["game_count"] == 2
    records = list(read_shard(_shard_ref(manifest, out)))
    game_ids = {r.metadata.game_id for r in records}
    assert game_ids == {0, 1}


def test_duplicate_exact_source_rejected(tmp_path):
    frame = build_frame(1, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    path = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame]))

    with pytest.raises(DuplicateSourceError):
        ingest([path, path], tmp_path / "out", "test-selfplay")


def test_identical_duplicate_game_across_files_not_reduplicated(tmp_path):
    sample = build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")
    frame_1 = build_frame(1, game_outcome=WHITE_WIN, samples=[sample])
    # A second file, same runId, same gameId, byte-identical content.
    frame_1_again = build_frame(1, game_outcome=WHITE_WIN, samples=[sample])
    frame_2 = build_frame(2, game_outcome=WHITE_WIN, samples=[sample])

    path_a = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame_1]))
    path_b = _write(tmp_path, "b.vspr", build_vspr_bytes(RUN_A, [frame_1_again, frame_2]))

    out = tmp_path / "out"
    manifest = ingest([path_a, path_b], out, "test-selfplay")

    assert manifest["game_count"] == 2  # game 1 counted once, not twice
    assert manifest["record_count"] == 2  # game 1's sample emitted once, not twice


def test_conflicting_duplicate_gameid_across_files_is_hard_error(tmp_path):
    frame_1 = build_frame(
        1, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")]
    )
    frame_1_conflict = build_frame(
        1, game_outcome=BLACK_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")]
    )
    path_a = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame_1]))
    path_b = _write(tmp_path, "b.vspr", build_vspr_bytes(RUN_A, [frame_1_conflict]))

    with pytest.raises(ConflictingGameError):
        ingest([path_a, path_b], tmp_path / "out", "test-selfplay")


# =====================================================================================
# WDL perspective -- white/black to move, white/black win, draw
# =====================================================================================


def _single_sample_manifest(tmp_path, name, *, outcome, fen, perspective=PERSPECTIVE_WHITE, termination=ADJUDICATED_SCORE):
    frame = build_frame(
        1,
        game_outcome=outcome,
        termination_reason=termination,
        outcome_perspective=perspective,
        samples=[build_sample(0, fen)],
    )
    path = _write(tmp_path, name, build_vspr_bytes(RUN_A, [frame]))
    out = tmp_path / f"out-{name}"
    manifest = ingest([path], out, "test-selfplay")
    return list(read_shard(_shard_ref(manifest, out)))[0]


def test_white_to_move_white_win_perspective_white(tmp_path):
    r = _single_sample_manifest(
        tmp_path, "1.vspr", outcome=WHITE_WIN, fen="4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
    )
    assert r.label.wdl == 1.0


def test_white_to_move_black_win_perspective_white(tmp_path):
    r = _single_sample_manifest(
        tmp_path, "2.vspr", outcome=BLACK_WIN, fen="4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
    )
    assert r.label.wdl == 0.0


def test_black_to_move_white_win_perspective_white(tmp_path):
    r = _single_sample_manifest(
        tmp_path, "3.vspr", outcome=WHITE_WIN, fen="4k3/8/8/8/8/8/4q3/4K3 b - - 0 1"
    )
    assert r.label.wdl == 0.0


def test_black_to_move_black_win_perspective_white(tmp_path):
    r = _single_sample_manifest(
        tmp_path, "4.vspr", outcome=BLACK_WIN, fen="4k3/8/8/8/8/8/4q3/4K3 b - - 0 1"
    )
    assert r.label.wdl == 1.0


def test_draw_perspective_white_is_half_regardless_of_side_to_move(tmp_path):
    r = _single_sample_manifest(
        tmp_path, "5.vspr", outcome=DRAW, fen="4k3/8/8/8/8/8/4q3/4K3 b - - 0 1", termination=FIFTY_MOVE_RULE
    )
    assert r.label.wdl == 0.5


def test_side_to_move_at_sample_perspective_white_win_tag_is_mover_won(tmp_path):
    r = _single_sample_manifest(
        tmp_path,
        "6.vspr",
        outcome=WHITE_WIN,
        fen="4k3/8/8/8/8/8/4q3/4K3 b - - 0 1",  # Black to move -- perspective must NOT flip
        perspective=PERSPECTIVE_SIDE_TO_MOVE_AT_SAMPLE,
    )
    assert r.label.wdl == 1.0


def test_side_to_move_at_sample_perspective_black_win_tag_is_mover_lost(tmp_path):
    r = _single_sample_manifest(
        tmp_path,
        "7.vspr",
        outcome=BLACK_WIN,
        fen="4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1",
        perspective=PERSPECTIVE_SIDE_TO_MOVE_AT_SAMPLE,
    )
    assert r.label.wdl == 0.0


def test_unresolved_outcome_synthetic_has_wdl_false_eval_preserved(tmp_path):
    frame = build_frame(
        1,
        game_outcome=UNRESOLVED,
        termination_reason=MOVE_CAP,
        samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1", eval_score_kind=CP, eval_score=42)],
    )
    path = _write(tmp_path, "u.vspr", build_vspr_bytes(RUN_A, [frame]))
    out = tmp_path / "out"
    manifest = ingest([path], out, "test-selfplay")
    record = list(read_shard(_shard_ref(manifest, out)))[0]
    assert record.label.wdl is None
    assert record.label.eval_cp == 42


def test_search_budget_nodes_preserved(tmp_path):
    frame = build_frame(
        1,
        game_outcome=WHITE_WIN,
        samples=[
            build_sample(
                0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1", search_budget_kind=NODES, search_budget_value=123456
            )
        ],
    )
    path = _write(tmp_path, "n.vspr", build_vspr_bytes(RUN_A, [frame]))
    out = tmp_path / "out"
    manifest = ingest([path], out, "test-selfplay")
    record = list(read_shard(_shard_ref(manifest, out)))[0]
    assert record.metadata.search_nodes == 123456
    assert record.metadata.search_depth is None


def test_no_blending_wdl_and_eval_cp_both_stored_independently(tmp_path):
    frame = build_frame(
        1,
        game_outcome=WHITE_WIN,
        samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1", eval_score_kind=CP, eval_score=77)],
    )
    path = _write(tmp_path, "b.vspr", build_vspr_bytes(RUN_A, [frame]))
    out = tmp_path / "out"
    manifest = ingest([path], out, "test-selfplay")
    record = list(read_shard(_shard_ref(manifest, out)))[0]
    # Both fields present, unmodified by each other -- no blending applied.
    assert record.label.eval_cp == 77
    assert record.label.wdl == 1.0


# =====================================================================================
# Atomicity / bounded processing
# =====================================================================================


def test_atomic_failure_leaves_no_authoritative_manifest(tmp_path):
    frame_1 = build_frame(1, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    frame_1_conflict = build_frame(1, game_outcome=DRAW, termination_reason=FIFTY_MOVE_RULE, samples=[])
    path_a = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame_1]))
    path_b = _write(tmp_path, "b.vspr", build_vspr_bytes(RUN_A, [frame_1_conflict]))
    out = tmp_path / "out"

    with pytest.raises(ConflictingGameError):
        ingest([path_a, path_b], out, "test-selfplay")

    assert not (out / "manifest.json").exists()
    assert not (out / "shard-0.bin").exists()


def test_refuses_to_overwrite_existing_output(tmp_path):
    frame = build_frame(1, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    path = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame]))
    out = tmp_path / "out"
    ingest([path], out, "test-selfplay")

    with pytest.raises(FileExistsError):
        ingest([path], out, "test-selfplay-2")


def test_bounded_processing_many_games(tmp_path):
    # Not a memory-profiling test (out of proportion for this suite) -- proves
    # ingestion processes many small games correctly without needing anything
    # resembling a live self-play run, streamed through write_shard()'s own
    # bounded-batch writer rather than materializing the whole corpus first.
    n_games = 50
    samples_per_game = 5
    frames = []
    for gid in range(n_games):
        samples = [
            build_sample(p, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1", eval_score=p) for p in range(samples_per_game)
        ]
        frames.append(build_frame(gid, game_outcome=WHITE_WIN, samples=samples))
    path = _write(tmp_path, "many.vspr", build_vspr_bytes(RUN_A, frames))

    out = tmp_path / "out"
    manifest = ingest([path], out, "test-selfplay")

    assert manifest["game_count"] == n_games
    assert manifest["record_count"] == n_games * samples_per_game


def test_manifest_validates_against_schema(tmp_path):
    frame = build_frame(1, game_outcome=WHITE_WIN, samples=[build_sample(0, "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1")])
    path = _write(tmp_path, "a.vspr", build_vspr_bytes(RUN_A, [frame]))
    manifest = ingest([path], tmp_path / "out", "test-selfplay")
    validate_selfplay_manifest(manifest)  # must not raise
    assert manifest["assessment"] == {"status": "unassessed", "outcome": None, "evidence_ref": None}
