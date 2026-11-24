"""Audit one training checkpoint: how far is its quantized network from its float32 one?

Issue #265 asks for the float32-versus-quantized error of every surviving real checkpoint before
any scale-contract decision. This is the entry point for that measurement. It composes the
existing pieces and adds no arithmetic of its own: `checkpoint_to_canonical` (checkpoint loader),
`compute_quantization_parity` (production `quantize()` plus the bit-exact `NnueEvaluator` mirror)
and `compute_scale_utilization`.

    python -m scripts.audit_checkpoint_quantization CHECKPOINT.pt [--fens FILE ...] [--output OUT.json]

Report only by default: with no `--max-*` or `--min-*` option the exit status is 0 whatever the
numbers are, because no release threshold has been calibrated against a real checkpoint. Giving
any bound turns it into a gate (exit status 1 on a violation).

What the numbers describe: the checkpoint on the FEN set that was passed, identified by
`parity.fen_corpus_sha256`. The default set is the committed `bench/nnue-corpus` (393 positions),
which is not the checkpoint's training or held-out distribution. A real held-out set (`--fens`)
gives the figure that matters for strength.

Provenance written to the output: the checkpoint file's basename and SHA-256, the trainer commit
and seed recorded inside the checkpoint when present, and the FEN source names. No absolute path
is recorded.

This reads a checkpoint and a list of FENs. It does not train, label, export or modify anything.
`main()` runs only when this file is executed as a script; importing it has no effect.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence

from trainer.encoding.feature_spec import repo_root
from trainer.export.canonical import checkpoint_to_canonical
from trainer.validation.quantization_parity import (
    ParityThresholds,
    compute_quantization_parity,
    threshold_violations,
)
from trainer.validation.scale_utilization import compute_scale_utilization

DEFAULT_CORPUS_FILES = ("tactical", "quiet", "opening", "middlegame", "endgame", "random-legal")


def read_fens(path: Path) -> List[str]:
    """First four fields of every non-blank, non-comment line (the repo's FEN/EPD convention)."""
    fens = []
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            fens.append(" ".join(line.split()[:4]))
    return fens


def default_corpus() -> tuple[List[str], List[str]]:
    corpus_dir = repo_root(Path(__file__).resolve()) / "bench" / "nnue-corpus"
    names = [f"bench/nnue-corpus/{name}.epd" for name in DEFAULT_CORPUS_FILES]
    fens: List[str] = []
    for name in DEFAULT_CORPUS_FILES:
        fens.extend(read_fens(corpus_dir / f"{name}.epd"))
    return fens, names


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def audit_checkpoint(checkpoint_path: Path, fens: Sequence[str], fen_sources: Sequence[str],
                     thresholds: Optional[ParityThresholds] = None) -> Dict[str, Any]:
    """Returns the full audit record (JSON-safe). `violations` is empty when no bound is set."""
    import torch

    checkpoint = torch.load(checkpoint_path, weights_only=False)
    canonical = checkpoint_to_canonical(checkpoint)
    parity, _ = compute_quantization_parity(canonical, fens)
    utilization = compute_scale_utilization(canonical, fens)
    violations = threshold_violations(parity, thresholds) if thresholds is not None else []

    recorded = checkpoint.get("experiment_metadata") or {}
    return {
        "checkpoint_file": checkpoint_path.name,
        "checkpoint_sha256": file_sha256(checkpoint_path),
        "trainer_commit": recorded.get("trainer_commit"),
        "training_seed": recorded.get("seed"),
        "fen_sources": list(fen_sources),
        "parity": parity.to_manifest_dict(),
        "scale_utilization": utilization.to_manifest_dict(),
        "thresholds": None if thresholds is None else vars(thresholds),
        "violations": violations,
        "scope": "measured on the listed FEN set only; the default set is not the checkpoint's "
                 "training or held-out distribution",
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("--fens", type=Path, nargs="+", default=None,
                        help="FEN/EPD files to evaluate (default: the committed bench/nnue-corpus).")
    parser.add_argument("--output", type=Path, default=None, help="Also write the record as JSON here.")
    parser.add_argument("--max-mae-cp", type=float, default=None)
    parser.add_argument("--max-rmse-cp", type=float, default=None)
    parser.add_argument("--max-abs-cp", type=float, default=None)
    parser.add_argument("--min-pearson", type=float, default=None)
    args = parser.parse_args(argv)

    if args.fens:
        fens = [fen for path in args.fens for fen in read_fens(path)]
        sources = [path.name for path in args.fens]
    else:
        fens, sources = default_corpus()
    if not fens:
        parser.error("no FENs to evaluate")

    bounds = (args.max_mae_cp, args.max_rmse_cp, args.max_abs_cp, args.min_pearson)
    thresholds = None if all(b is None for b in bounds) else ParityThresholds(
        max_mae_cp=args.max_mae_cp, max_rmse_cp=args.max_rmse_cp, max_abs_cp=args.max_abs_cp,
        min_pearson_float_quantized=args.min_pearson)

    record = audit_checkpoint(args.checkpoint, fens, sources, thresholds)
    text = json.dumps(record, indent=2, sort_keys=True)
    print(text)
    if args.output is not None:
        args.output.write_text(text + "\n")
    return 1 if record["violations"] else 0


if __name__ == "__main__":
    sys.exit(main())
