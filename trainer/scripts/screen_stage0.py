"""Stage 0 screening checks of one exported network (#265, plan #329): no games, no training.

    python -m scripts.screen_stage0 NET.nnue --output OUT.json [--engine-jar JAR] [--java CMD]

Hard gates (each pass or fail; `not run` is never a pass):
  java_python_parity   the integer score of the real strict engine (`position fen` + the UCI `eval` command,
                       the `int16 score` line) equals the Python bit-exact reference on every position of the
                       committed 393-position corpus and the controlled positions, difference exactly 0.
                       Needs `--engine-jar`. #265 established bit-exactness on committed fixtures, so there is
                       no tolerance.
  controlled_orientation  the correct sign on every controlled position (`orientation_positions.epd`), see
                       `trainer/validation/orientation.py`. Scored with the Python reference, which the parity
                       gate ties to the engine.
Diagnostics (measurements, no threshold): corpus material sign agreement per side to move, the gap between the
sides, correlations and the mirror difference. They are written next to the gates and are not a verdict.

The network is read back from the file with `read_nnue`, so what is screened is what would be loaded. Records
hold the file's base name only. The engine runs with `StrictNnue=true`, so a network it cannot load ends the
run instead of being scored by Classical.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence

from scripts.audit_checkpoint_quantization import default_corpus, file_sha256
from trainer.encoding.feature_spec import repo_root
from trainer.export.nnue_reader import read_nnue
from trainer.quantization.inference import eval_quantized_fens
from trainer.validation.orientation import (
    controlled_orientation,
    corpus_diagnostics,
    load_controlled_positions,
    mirror_fen,
)

Scorer = Callable[[Sequence[str]], List[int]]


def _parse_eval_score(output: str) -> int:
    path = repo_root(Path(__file__).resolve()) / "tools" / "nnue_preflight.py"
    spec = importlib.util.spec_from_file_location("nnue_preflight_for_stage0", path)
    if spec is None or spec.loader is None:
        raise SystemExit("tools/nnue_preflight.py is missing: the engine parity check needs its eval-score parser")
    module = importlib.util.module_from_spec(spec)
    sys.modules.setdefault("nnue_preflight_for_stage0", module)
    spec.loader.exec_module(module)
    return module.parse_eval_score(output)


def engine_fen(fen: str) -> str:
    """The engine's `position fen` needs the move clocks; the corpus files carry four fields. The clocks do not
    enter an NNUE evaluation. Without them the engine kept its previous position and every score was the start
    position's, which the parity gate reported as hundreds of mismatches."""
    fields = fen.split()
    return " ".join(fields[:4] + (["0", "1"] if len(fields) == 4 else fields[4:]))


class EngineScorer:
    """Scores FENs with the real engine: `position fen F`, `eval`, the `int16 score` line. One process, strict."""

    def __init__(self, jar: Path, net: Path, java: str = "java", timeout: float = 60.0):
        command = [java, "--add-modules", "jdk.incubator.vector", "-jar", str(Path(jar).resolve())]
        self.timeout = timeout
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        text=True, bufsize=1)
        self.lines: "queue.Queue[Optional[str]]" = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        for option in (("EvalType", "NNUE"), ("EvalFile", str(Path(net).resolve())), ("StrictNnue", "true")):
            self._send(f"setoption name {option[0]} value {option[1]}")
        self._send("isready")
        self._until(lambda line: line == "readyok", "readyok")

    def _read(self) -> None:
        assert self.process.stdout is not None
        for line in self.process.stdout:
            self.lines.put(line.rstrip("\r\n"))
        self.lines.put(None)

    def _send(self, text: str) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write(text + "\n")
        self.process.stdin.flush()

    def _until(self, done: Callable[[str], bool], what: str) -> List[str]:
        collected: List[str] = []
        deadline = time.monotonic() + self.timeout
        while True:
            try:
                line = self.lines.get(timeout=max(0.01, deadline - time.monotonic()))
            except queue.Empty:
                raise RuntimeError(f"engine timed out waiting for {what}")
            if line is None:
                raise RuntimeError(f"engine exited before {what}: " + "; ".join(l for l in collected if "strict" in l))
            collected.append(line)
            if done(line):
                return collected

    def __call__(self, fens: Sequence[str]) -> List[int]:
        scores = []
        for fen in fens:
            self._send(f"position fen {engine_fen(fen)}")
            self._send("eval")
            lines = self._until(lambda l: "float32 oracle score" in l, f"the eval of {fen}")
            scores.append(_parse_eval_score("\n".join(lines)))
        return scores

    def close(self) -> None:
        try:
            self._send("quit")
            self.process.wait(timeout=2)
        except Exception:
            pass
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait()
        for stream in (self.process.stdin, self.process.stdout):
            try:
                if stream is not None:
                    stream.close()
            except OSError:
                pass


def stage0(net_path: Path, *, engine_scorer: Optional[Scorer] = None, fens: Optional[Sequence[str]] = None,
           controlled: Optional[Sequence] = None) -> Dict[str, Any]:
    net_path = Path(net_path)
    network, uuid = read_nnue(net_path)
    if fens is None:
        fens, _ = default_corpus()
    fens = list(fens)
    controlled = list(load_controlled_positions() if controlled is None else controlled)
    controlled_fens = [fen for fen, _ in controlled]

    python_scores = [int(s) for s in eval_quantized_fens(network, fens + controlled_fens)]
    corpus_scores, controlled_scores = python_scores[:len(fens)], python_scores[len(fens):]
    mirror_scores = [int(s) for s in eval_quantized_fens(network, [mirror_fen(f) for f in fens])]

    gates: Dict[str, Any] = {}
    orientation = controlled_orientation(controlled_scores, controlled)
    gates["controlled_orientation"] = "pass" if orientation.passed else "failed"

    parity: Dict[str, Any] = {"status": "not run"}
    if engine_scorer is not None:
        engine_scores = list(engine_scorer(fens + controlled_fens))
        mismatches = [{"fen": f, "java": j, "python": p}
                      for f, j, p in zip(fens + controlled_fens, engine_scores, python_scores) if j != p]
        parity = {"status": "pass" if len(engine_scores) == len(python_scores) and not mismatches else "failed",
                  "positions": len(python_scores), "mismatches": len(mismatches), "first_mismatches": mismatches[:5],
                  "max_abs_difference": max((abs(m["java"] - m["python"]) for m in mismatches), default=0)}
    gates["java_python_parity"] = parity["status"]

    return {
        "nnue_file": net_path.name,
        "nnue_sha256": file_sha256(net_path),
        "uuid": uuid,
        "hard_gates": gates,
        "java_python_parity": parity,
        "controlled_orientation": {"checked": orientation.checked, "failures": orientation.failures},
        "diagnostics": corpus_diagnostics(corpus_scores, fens, mirror_scores),
        "scope": "hard gates and measurements on the committed corpus and the controlled positions only; "
                 "no play-strength claim",
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("net", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--engine-jar", type=Path, default=None)
    parser.add_argument("--java", default="java")
    args = parser.parse_args(argv)
    if args.output.exists():
        raise SystemExit(f"{args.output.name} already exists; refusing to overwrite")

    scorer = EngineScorer(args.engine_jar, args.net, args.java) if args.engine_jar else None
    try:
        report = stage0(args.net, engine_scorer=scorer)
    finally:
        if scorer is not None:
            scorer.close()
    text = json.dumps(report, indent=2, sort_keys=True) + "\n"
    args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    failed = [name for name, status in report["hard_gates"].items() if status == "failed"]
    if failed:
        print("hard gate failed: " + ", ".join(failed), file=sys.stderr)
        return 1
    if report["hard_gates"]["java_python_parity"] == "not run":
        print("note: the engine parity gate did not run (no --engine-jar); no Java parity is claimed", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
