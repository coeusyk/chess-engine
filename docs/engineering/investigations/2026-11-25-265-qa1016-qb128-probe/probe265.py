"""Probe for #265: export one real checkpoint at two scales and dump the Python quantized scores.

Run it from trainer/ of a checkout of develop at be7cfff, in an environment that has Torch (the historical
trainer environment), with PYTHONPATH=. so the trainer's scripts package resolves:

    PYTHONPATH=. python probe265.py CHECKPOINT.pt OUT_DIR

This file is not in be7cfff. Retrieve it from the revision that holds the #265 note (develop after #317 merges,
research/265-qa1016-compat before), and see the Replay section of
docs/engineering/investigations/2026-11-25-265-qa1016-qb128-compatibility.md for the full procedure:
    git show <rev>:docs/engineering/investigations/2026-11-25-265-qa1016-qb128-probe/probe265.py > probe265.py

Writes OUT_DIR/<tag>.nnue and OUT_DIR/<tag>.scores.tsv (FEN, python int score) for each scale. It exits with an
error when the arguments are missing, the checkpoint is not the pinned one, the corpus does not have 393
positions, or an exported network breaks the loader's accumulator bound.
"""
import hashlib
import sys
import time
from pathlib import Path

import numpy as np

CHECKPOINT_SHA256 = "fef3ee900c5973016ca589fac2146f28eed48e7b9812eb6d78bbd2d9380b11bb"
EXPECTED_POSITIONS = 393

from scripts.analyze_scale_alternatives import rescale
from scripts.audit_checkpoint_quantization import default_corpus, load_checkpoint
from trainer.export.canonical import checkpoint_to_canonical
from trainer.export.exporter import _nnue_bytes
from trainer.quantization.inference import _feature_lists, eval_quantized_canonical, accumulator_bound_violations
from trainer.quantization.quantizer import quantize

if len(sys.argv) != 3:
    sys.exit("usage: PYTHONPATH=. python probe265.py CHECKPOINT.pt OUT_DIR")
checkpoint, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
if not checkpoint.is_file():
    sys.exit(f"checkpoint not found: {checkpoint}")
actual = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
if actual != CHECKPOINT_SHA256:
    sys.exit(f"checkpoint is not the pinned one: sha256 {actual}, expected {CHECKPOINT_SHA256}")
out_dir.mkdir(parents=True, exist_ok=True)
fens, _ = default_corpus()
if len(fens) != EXPECTED_POSITIONS:
    sys.exit(f"corpus has {len(fens)} positions, expected {EXPECTED_POSITIONS}")
base = checkpoint_to_canonical(load_checkpoint(checkpoint, False))
print("base qa/qb/width:", base.qa, base.qb, base.hidden_width, "positions:", len(fens))

for tag, (ft_factor, out_factor) in {"qa127_qb64": (1, 1), "qa1016_qb128": (8, 2)}.items():
    q = quantize(rescale(base, ft_factor, out_factor))
    problems = accumulator_bound_violations(q)
    blob = _nnue_bytes(q, "probe-" + tag, "probe", int(time.time()))
    (out_dir / (tag + ".nnue")).write_bytes(blob)
    lines = []
    for fen in fens:
        us, them = _feature_lists(fen)
        lines.append(fen + "\t" + str(eval_quantized_canonical(q, us, them)))
    (out_dir / (tag + ".scores.tsv")).write_text("\n".join(lines) + "\n")
    ftmax = int(np.abs(q.ft_weights.astype(np.int64)).max())
    print(tag, "qa", q.qa, "qb", q.qb, "ft max |w|", ftmax, "bias range", int(q.ft_biases.min()), int(q.ft_biases.max()),
          "out max |w|", int(np.abs(q.output_weights.astype(np.int64)).max()), "loader violations", len(problems))
    if problems:
        sys.exit(f"{tag}: the exported network breaks the loader's accumulator bound: {problems[:3]}")
