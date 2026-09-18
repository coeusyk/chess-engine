"""Stage 2 Stockfish labeling driver (issue #199, ADR-007, PRD Section 2 US-5,
`docs/architecture/research/DR-D8-stockfish-labeling-driver.md`).

**Minimal v1 scope (grilled decision, 2026-07-14).** Sequential: one persistent,
single-threaded Stockfish UCI subprocess, driven synchronously, position after
position. No dynamic work-stealing scheduler, no worker pool, no manifest-based
crash-recovery, no circuit breaker -- all real, valuable ideas the Deep Research
report recommends (Sections 5-8, 12, 16), deferred because issue #199's actual
acceptance criteria do not require them: a new `DatasetProvider`, a reproducible
committed config, and shards carrying a dataset identifier. A future PR can add
parallelism/recovery without changing this module's public shape
(`StockfishLabelConfig`, `label_positions`) -- only its internals.

**Position source (grilled decision).** A plain FEN/EPD-line input file, one FEN per
line -- no PGN parsing, no Java<->Python bridge. "Reusing PositionLoader" (issue
#199) is satisfied at the format level (this driver's input is exactly the FEN shape
`PositionLoader.java` already parses, Format 1), not via a new cross-language
bridge that issue #199 doesn't actually require.

**Not-in-check pre-filtering is deliberately NOT implemented here.** The Deep
Research report (Section 10) recommends it as a cheap pre-search structural filter,
but no Python chess-logic library exists in this repository today --
`feature_encoder.py` parses piece placement only, never attack/check detection --
and adding one (e.g. `python-chess`) for a single filter is a real dependency
decision outside this PR's minimal scope. A position source already passed through
quiet/not-in-check extraction upstream satisfies this without the driver re-deriving
it. Recorded as a deferred gap, not a silent omission.

**Determinism.** Every worker (here: the one persistent subprocess) runs Stockfish
with `Threads=1`, non-configurable -- not exposed as a config knob that could
accidentally be changed, since every reproducibility/idempotence claim this driver
makes depends on it (DR-D8 Sections 10, 12, 14).
"""

from __future__ import annotations

import hashlib
import json
import queue
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Optional

from trainer.dataset.mmap_shard import write_shard
from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord, ShardRef
from trainer.export.exporter import _atomic_write
from trainer.reproducibility.experiment_metadata import _trainer_commit

_UCI_STARTUP_TIMEOUT_SECONDS = 10.0


class EngineTimeoutError(Exception):
    """Raised when a Stockfish subprocess does not respond within the configured
    per-position timeout -- caught by the caller, never silently swallowed."""


class EngineProtocolError(Exception):
    """Raised when a Stockfish subprocess produces output that cannot be parsed as
    a valid UCI response -- caught by the caller, never silently treated as a
    zero/default label."""


@dataclass(frozen=True)
class StockfishLabelConfig:
    """A labeling run's reproducibility contract (DR-D8 Section 11) -- everything
    needed to answer "what produced this label" without re-deriving it, and
    everything needed to re-run the exact same labeling policy later.

    Exactly one of `nodes`/`depth` must be set (DR-D8 Section 10/17: a node budget is
    the recommended default for reproducibility -- wall-clock time is inherently
    hardware-dependent, and even depth-based search can vary slightly by engine
    build/hardware under some internal accounting -- but issue #199 names both
    "fixed nodes/depth" as valid, so both are supported).
    """

    engine_path: Path
    nodes: Optional[int] = None
    depth: Optional[int] = None
    timeout_seconds: float = 30.0

    def __post_init__(self) -> None:
        if (self.nodes is None) == (self.depth is None):
            raise ValueError("StockfishLabelConfig requires exactly one of nodes or depth, not both or neither")
        if self.nodes is not None and self.nodes <= 0:
            raise ValueError(f"nodes must be positive, got {self.nodes}")
        if self.depth is not None and self.depth <= 0:
            raise ValueError(f"depth must be positive, got {self.depth}")
        if self.timeout_seconds <= 0:
            raise ValueError(f"timeout_seconds must be positive, got {self.timeout_seconds}")

    def go_command(self) -> str:
        if self.nodes is not None:
            return f"go nodes {self.nodes}"
        return f"go depth {self.depth}"


@dataclass(frozen=True)
class LabelResult:
    eval_cp: Optional[int]
    eval_mate: Optional[int]
    search_depth: Optional[int]
    search_nodes: Optional[int]


class UciEngine:
    """Drives one persistent Stockfish (or any UCI-speaking) subprocess. One engine
    instance owns its subprocess exclusively for its entire lifetime -- UCI is a
    synchronous, single-command-in-flight protocol (DR-D8 Section 9), so no two
    callers may share one instance concurrently.

    Reads are timeout-bounded via a background reader thread feeding a queue,
    since `Popen.stdout.readline()` has no native per-call timeout on a pipe
    (DR-D8 Section 8/9's documented gap in Python's stdlib process primitives).
    """

    def __init__(self, engine_path: Path) -> None:
        self._engine_path = engine_path
        self._process: Optional[subprocess.Popen] = None
        self._lines: "queue.Queue[str]" = queue.Queue()
        self._reader_thread: Optional[threading.Thread] = None
        self.uci_id_name: Optional[str] = None
        self.engine_binary_sha256: Optional[str] = None

    def start(self, startup_timeout_seconds: float = _UCI_STARTUP_TIMEOUT_SECONDS) -> None:
        """Spawns the subprocess and completes the UCI handshake. Self-cleaning on
        failure (code-review finding): if the handshake times out or errors after
        the subprocess has already been spawned, this kills that subprocess before
        propagating -- a caller that never gets a chance to call `close()` (because
        the exception came from `start()` itself) must not leak the child process.

        A `.py` engine path is invoked via the current Python interpreter rather
        than executed directly. This repo has `core.filemode=false` (checked
        directly: WSL2/cross-platform filesystem quirk, not this file's concern to
        second-guess), so git never preserves a committed test fixture script's
        executable bit -- a fresh checkout would otherwise silently fail to exec the
        test-scope stub UCI engine. A real Stockfish binary is unaffected: it is
        never a `.py` file and is invoked directly, as before.
        """
        self.engine_binary_sha256 = hashlib.sha256(self._engine_path.read_bytes()).hexdigest()
        command = ([sys.executable, str(self._engine_path)] if self._engine_path.suffix == ".py"
                   else [str(self._engine_path)])
        try:
            self._process = subprocess.Popen(
                command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                bufsize=1,
            )
            self._reader_thread = threading.Thread(target=self._read_loop, daemon=True)
            self._reader_thread.start()

            self._send("uci")
            self.uci_id_name = self._await(
                predicate=lambda line: line == "uciok",
                capture=lambda line: line[len("id name "):] if line.startswith("id name ") else None,
                timeout_seconds=startup_timeout_seconds,
            )
            self._send("setoption name Threads value 1")
            self._send("isready")
            self._await(predicate=lambda line: line == "readyok", capture=lambda line: None,
                         timeout_seconds=startup_timeout_seconds)
        except BaseException:
            self.close()
            raise

    def evaluate(self, fen: str, config: StockfishLabelConfig) -> LabelResult:
        self._send(f"position fen {fen}")
        self._send(config.go_command())

        last_score_cp: Optional[int] = None
        last_score_mate: Optional[int] = None
        last_depth: Optional[int] = None
        last_nodes: Optional[int] = None

        def capture_info(line: str) -> None:
            nonlocal last_score_cp, last_score_mate, last_depth, last_nodes
            tokens = line.split()
            if not tokens or tokens[0] != "info":
                return
            for i, token in enumerate(tokens):
                if token == "score" and i + 2 < len(tokens):
                    if tokens[i + 1] == "cp":
                        last_score_cp, last_score_mate = int(tokens[i + 2]), None
                    elif tokens[i + 1] == "mate":
                        last_score_mate, last_score_cp = int(tokens[i + 2]), None
                elif token == "depth" and i + 1 < len(tokens):
                    last_depth = int(tokens[i + 1])
                elif token == "nodes" and i + 1 < len(tokens):
                    last_nodes = int(tokens[i + 1])

        def on_line(line: str) -> Optional[bool]:
            capture_info(line)
            return True if line.startswith("bestmove") else None

        self._await(predicate=lambda line: line.startswith("bestmove"), capture=on_line,
                     timeout_seconds=config.timeout_seconds)

        if last_score_cp is None and last_score_mate is None:
            raise EngineProtocolError(f"no parseable score line before bestmove for fen={fen!r}")

        return LabelResult(eval_cp=last_score_cp, eval_mate=last_score_mate,
                            search_depth=last_depth, search_nodes=last_nodes)

    def close(self) -> None:
        if self._process is None:
            return
        try:
            self._send("quit")
            self._process.wait(timeout=5.0)
        except Exception:
            self._process.kill()
        finally:
            self._process = None

    def _send(self, command: str) -> None:
        assert self._process is not None and self._process.stdin is not None
        self._process.stdin.write(command + "\n")
        self._process.stdin.flush()

    def _read_loop(self) -> None:
        assert self._process is not None and self._process.stdout is not None
        for line in self._process.stdout:
            self._lines.put(line.rstrip("\n"))

    def _await(self, predicate, capture, timeout_seconds: float):
        deadline = time.monotonic() + timeout_seconds
        result = None
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise EngineTimeoutError(
                    f"no response within {timeout_seconds}s from {self._engine_path}"
                )
            try:
                line = self._lines.get(timeout=remaining)
            except queue.Empty:
                raise EngineTimeoutError(
                    f"no response within {timeout_seconds}s from {self._engine_path}"
                )
            captured = capture(line)
            if captured is not None:
                result = captured
            if predicate(line):
                return result


def _read_fens(input_path: Path) -> Iterator[str]:
    """Streams FEN lines one at a time -- never materializes the full input file
    in memory (DR-D8 Section 13's streaming discipline)."""
    with open(input_path, "r", encoding="ascii") as f:
        for line in f:
            fen = line.strip()
            if fen:
                yield fen


@dataclass(frozen=True)
class LabelingRunResult:
    shard: ShardRef
    labeled_count: int
    skipped_count: int
    manifest_path: Path


def label_positions(config: StockfishLabelConfig, input_path: Path, output_dir: Path,
                     dataset_identifier: str) -> LabelingRunResult:
    """Runs the full Reader -> Labeler -> Writer pipeline (DR-D8 Section 7, scaled to
    this PR's minimal sequential scope) over every FEN in `input_path`, writing one
    shard plus a provenance manifest into `output_dir`.

    A position that times out or produces unparseable engine output is logged and
    skipped, not fatal to the run -- this v1 has no circuit breaker (DR-D8 Section
    12's future-work item), so a persistently broken engine will skip every position
    rather than aborting; that gap is accepted for this PR's minimal scope and is not
    silently hidden (`skipped_count` surfaces it in the manifest).
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    # A one-element holder, not a bare local, because a timeout must replace the
    # engine outright (see _records below) -- the aborted search is still running
    # inside the old subprocess (UCI has no defined recovery for "a new position/go
    # arrives while the previous go's bestmove never came"), so reusing it would
    # silently attribute a later position's stale info/bestmove lines to the wrong
    # query. Confirmed empirically against real Stockfish before this fix: a second
    # evaluate() call on the same engine after a timeout hung for the full next
    # timeout window instead of returning a fresh result.
    engine_holder = [UciEngine(config.engine_path)]
    engine_holder[0].start()  # self-cleaning on failure -- see UciEngine.start()'s docstring
    # Captured once, up front -- invariant across any respawn triggered below, since
    # every respawned engine points at the same config.engine_path.
    engine_uci_id = engine_holder[0].uci_id_name
    engine_binary_sha256 = engine_holder[0].engine_binary_sha256

    labeled_count = 0
    skipped_count = 0

    def _records() -> Iterator[PositionRecord]:
        nonlocal labeled_count, skipped_count
        for fen in _read_fens(input_path):
            try:
                result = engine_holder[0].evaluate(fen, config)
            except EngineTimeoutError as exc:
                # Only a timeout leaves the subprocess in an unknown, possibly-still-
                # searching state that requires a full respawn (see the holder comment
                # above). A protocol error (below) means bestmove *did* arrive cleanly
                # -- the process is provably idle and safe to reuse; respawning it too
                # would be correct but wasteful (architecture-review finding).
                skipped_count += 1
                print(f"[stockfish_label] skipping {fen!r}: {exc}")
                engine_holder[0].close()
                engine_holder[0] = UciEngine(config.engine_path)
                engine_holder[0].start()
                continue
            except EngineProtocolError as exc:
                skipped_count += 1
                print(f"[stockfish_label] skipping {fen!r}: {exc}")
                continue
            labeled_count += 1
            yield PositionRecord(
                fen=fen,
                label=PositionLabel(eval_cp=result.eval_cp, eval_mate=result.eval_mate),
                metadata=PositionMetadata(search_depth=result.search_depth, search_nodes=result.search_nodes),
            )

    shard_path = output_dir / "shard-0.bin"
    try:
        shard = write_shard(_records(), shard_path)
    finally:
        engine_holder[0].close()

    manifest_path = output_dir / "manifest.json"
    manifest = {
        "dataset_identifier": dataset_identifier,
        "stage": "sf-labeled",
        "engine_path": str(config.engine_path),
        "engine_uci_id": engine_uci_id,
        "engine_binary_sha256": engine_binary_sha256,
        "search_policy": {"nodes": config.nodes, "depth": config.depth, "threads": 1},
        "timeout_seconds": config.timeout_seconds,
        "trainer_commit": _trainer_commit(),
        "created_at_epoch_seconds": int(time.time()),
        "labeled_count": labeled_count,
        "skipped_count": skipped_count,
    }
    _atomic_write(manifest_path, (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8"))

    return LabelingRunResult(shard=shard, labeled_count=labeled_count, skipped_count=skipped_count,
                              manifest_path=manifest_path)


def main() -> int:
    """CLI entry point: `python -m scripts.stockfish_label <config.json> <input.fen> <output_dir> <dataset_id>`."""
    if len(sys.argv) != 5:
        print("usage: stockfish_label.py <config.json> <input.fen> <output_dir> <dataset_id>")
        return 1
    config_path, input_path, output_dir, dataset_identifier = (Path(sys.argv[1]), Path(sys.argv[2]),
                                                                 Path(sys.argv[3]), sys.argv[4])
    with open(config_path, "r", encoding="utf-8") as f:
        raw = json.load(f)
    config = StockfishLabelConfig(engine_path=Path(raw["engine_path"]), nodes=raw.get("nodes"),
                                   depth=raw.get("depth"), timeout_seconds=raw.get("timeout_seconds", 30.0))
    result = label_positions(config, input_path, output_dir, dataset_identifier)
    print(f"labeled {result.labeled_count}, skipped {result.skipped_count} -> {result.shard.locator}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
