"""Two-tier test strategy (grilled decision, 2026-07-14): unit tests below drive
`UciEngine`/`label_positions` against a tiny test-only stub UCI process
(`tests/fixtures/stub_uci_engine.py`) -- no real Stockfish needed, always runs in
CI. Golden-fixture and two-run-determinism tests against a *real* Stockfish binary
are marked `skipif` and auto-skip when none is found -- they run here and on any
dev machine with Stockfish installed, but never block CI (trainer-ci.yml does not
install one, matching this PR's grilled decision not to add that infra cost).
"""

import json
import os
import shutil
from pathlib import Path

import pytest

from scripts.stockfish_label import (
    EngineProtocolError,
    EngineTimeoutError,
    StockfishLabelConfig,
    UciEngine,
    label_positions,
)
from trainer.dataset.mmap_shard import read_shard

STUB_ENGINE = Path(__file__).parent.parent / "fixtures" / "stub_uci_engine.py"

STARTPOS = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"


def _real_stockfish_path() -> "str | None":
    return shutil.which("stockfish")


requires_real_stockfish = pytest.mark.skipif(
    _real_stockfish_path() is None, reason="no stockfish binary found on PATH"
)


# --- StockfishLabelConfig validation -----------------------------------------

def test_config_requires_exactly_one_of_nodes_or_depth():
    with pytest.raises(ValueError, match="exactly one"):
        StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=None, depth=None)
    with pytest.raises(ValueError, match="exactly one"):
        StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, depth=5)


def test_config_rejects_non_positive_nodes():
    with pytest.raises(ValueError, match="nodes must be positive"):
        StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=0)


def test_config_rejects_non_positive_timeout():
    with pytest.raises(ValueError, match="timeout_seconds must be positive"):
        StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=0)


def test_go_command_uses_nodes_when_set():
    config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100)
    assert config.go_command() == "go nodes 100"


def test_go_command_uses_depth_when_set():
    config = StockfishLabelConfig(engine_path=STUB_ENGINE, depth=8)
    assert config.go_command() == "go depth 8"


# --- UciEngine against the stub -----------------------------------------------

def test_uci_engine_captures_id_name_and_binary_hash():
    engine = UciEngine(STUB_ENGINE)
    try:
        engine.start()
        assert engine.uci_id_name == "StubEngine"
        assert engine.engine_binary_sha256 is not None
        assert len(engine.engine_binary_sha256) == 64  # sha256 hex digest length
    finally:
        engine.close()


def test_uci_engine_evaluates_a_position():
    engine = UciEngine(STUB_ENGINE)
    try:
        engine.start()
        config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=2.0)
        result = engine.evaluate(STARTPOS, config)
        assert result.eval_cp is not None
        assert result.eval_mate is None
        assert result.search_nodes == 123
    finally:
        engine.close()


def test_uci_engine_raises_on_timeout_and_survives_a_respawn():
    engine = UciEngine(STUB_ENGINE)
    try:
        engine.start()
        hang_config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=0.3)
        with pytest.raises(EngineTimeoutError):
            engine.evaluate("HANG", hang_config)
    finally:
        engine.close()

    # A fresh UciEngine against the same stub still works -- confirms the timeout
    # path doesn't leave the *stub process* itself unusable (label_positions is
    # responsible for the respawn-on-timeout policy, tested separately below).
    engine2 = UciEngine(STUB_ENGINE)
    try:
        engine2.start()
        config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=2.0)
        result = engine2.evaluate(STARTPOS, config)
        assert result.eval_cp is not None
    finally:
        engine2.close()


def test_start_kills_the_subprocess_if_the_handshake_times_out(monkeypatch):
    # code-review finding: start() must not leak a spawned-but-never-handshaken
    # subprocess if the uci/uciok handshake itself times out.
    monkeypatch.setenv("STUB_HANG_ON_UCI", "1")
    engine = UciEngine(STUB_ENGINE)
    with pytest.raises(EngineTimeoutError):
        engine.start(startup_timeout_seconds=0.3)

    # The subprocess must have been killed, not left running -- close() (called
    # internally by the failed start()) sets _process back to None only after a
    # successful wait/kill, so a non-None _process here would mean the leak wasn't
    # actually fixed.
    assert engine._process is None


# --- label_positions end-to-end against the stub ------------------------------

def test_label_positions_writes_shard_and_manifest(tmp_path):
    input_path = tmp_path / "positions.fen"
    input_path.write_text(f"{STARTPOS}\n4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1\n")
    config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=2.0)

    result = label_positions(config, input_path, tmp_path / "out", dataset_identifier="test-ds")

    assert result.labeled_count == 2
    assert result.skipped_count == 0
    assert Path(result.shard.locator).exists()
    assert result.manifest_path.exists()

    records = list(read_shard(result.shard))
    assert len(records) == 2
    assert records[0].fen == STARTPOS
    assert records[0].metadata.search_depth is not None
    assert records[0].metadata.search_nodes == 123


def test_label_positions_manifest_carries_provenance(tmp_path):
    input_path = tmp_path / "positions.fen"
    input_path.write_text(f"{STARTPOS}\n")
    config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=2.0)

    result = label_positions(config, input_path, tmp_path / "out", dataset_identifier="test-ds")

    manifest = json.loads(result.manifest_path.read_text())
    assert manifest["dataset_identifier"] == "test-ds"
    assert manifest["stage"] == "sf-labeled"
    assert manifest["engine_uci_id"] == "StubEngine"
    assert manifest["engine_binary_sha256"] is not None
    assert manifest["search_policy"] == {"nodes": 100, "depth": None, "threads": 1}
    assert manifest["labeled_count"] == 1
    assert manifest["skipped_count"] == 0
    assert manifest["trainer_commit"]


def test_label_positions_skips_hanging_positions_and_continues(tmp_path):
    # A HANG position times out and must be skipped -- not fatal to the run, and
    # the position after it must still be labeled (proves the respawn-on-timeout
    # recovery in label_positions actually restores a working engine).
    input_path = tmp_path / "positions.fen"
    input_path.write_text(f"HANG\n{STARTPOS}\n")
    config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=0.3)

    result = label_positions(config, input_path, tmp_path / "out", dataset_identifier="test-ds")

    assert result.skipped_count == 1
    assert result.labeled_count == 1
    records = list(read_shard(result.shard))
    assert len(records) == 1
    assert records[0].fen == STARTPOS


def test_label_positions_skips_protocol_error_positions_and_continues(tmp_path):
    # architecture-review finding: a protocol error (bestmove with no score line)
    # is functionally handled the same way as a timeout from label_positions's
    # perspective (skip and continue) -- but must not require a respawn to do so
    # correctly. This test proves the functional behavior (skip + continue
    # labeling); the respawn-avoidance itself is verified by code inspection
    # (label_positions's separate except EngineTimeoutError / except
    # EngineProtocolError branches -- only the former respawns).
    input_path = tmp_path / "positions.fen"
    input_path.write_text(f"NOSCORE\n{STARTPOS}\n")
    config = StockfishLabelConfig(engine_path=STUB_ENGINE, nodes=100, timeout_seconds=2.0)

    result = label_positions(config, input_path, tmp_path / "out", dataset_identifier="test-ds")

    assert result.skipped_count == 1
    assert result.labeled_count == 1
    records = list(read_shard(result.shard))
    assert len(records) == 1
    assert records[0].fen == STARTPOS


# --- Real Stockfish: golden fixture + two-run determinism ---------------------

@requires_real_stockfish
def test_golden_fixture_against_real_stockfish(tmp_path):
    """A tiny fixed corpus, fixed engine, fixed node budget -- catches silent
    behavioral drift in the UCI-driving logic itself (not Stockfish's own eval,
    which is out of this project's control). If this test's expected values ever
    need updating because a locally installed Stockfish version differs, that's
    expected -- the test pins *this driver's parsing*, not a specific engine build.
    """
    input_path = tmp_path / "positions.fen"
    input_path.write_text("4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1\n6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1\n")
    config = StockfishLabelConfig(engine_path=Path(_real_stockfish_path()), nodes=5000, timeout_seconds=15.0)

    result = label_positions(config, input_path, tmp_path / "out", dataset_identifier="golden")

    records = list(read_shard(result.shard))
    assert len(records) == 2
    # KQ vs K is a large, decisive winning advantage for White.
    assert records[0].label.eval_cp is not None
    assert records[0].label.eval_cp > 500
    # A back-rank mate-in-1 must be reported as a mate score, not a centipawn one.
    assert records[1].label.eval_mate == 1
    assert records[1].label.eval_cp is None


@requires_real_stockfish
def test_two_runs_produce_identical_labels(tmp_path):
    """Reproducibility (DR-D8 Sections 10, 12, 14): same engine, same Threads=1,
    same fixed node budget, same input -> identical labels across two independent
    runs. This is the load-bearing guarantee every provenance/idempotence claim in
    the Deep Research report depends on -- verified directly, not assumed.
    """
    input_path = tmp_path / "positions.fen"
    input_path.write_text(f"{STARTPOS}\n4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1\n")
    config = StockfishLabelConfig(engine_path=Path(_real_stockfish_path()), nodes=8000, timeout_seconds=15.0)

    result_a = label_positions(config, input_path, tmp_path / "run_a", dataset_identifier="det-test")
    result_b = label_positions(config, input_path, tmp_path / "run_b", dataset_identifier="det-test")

    records_a = list(read_shard(result_a.shard))
    records_b = list(read_shard(result_b.shard))
    assert len(records_a) == len(records_b) == 2
    for a, b in zip(records_a, records_b):
        assert a.fen == b.fen
        assert a.label.eval_cp == b.label.eval_cp
        assert a.label.eval_mate == b.label.eval_mate
        assert a.metadata.search_nodes == b.metadata.search_nodes
