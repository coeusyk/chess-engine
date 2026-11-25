"""Scratch probe for #265: export one real checkpoint at two scales and dump the Python quantized scores.

Usage (from trainer/): python probe265.py CHECKPOINT.pt OUT_DIR
Writes OUT_DIR/<tag>.nnue and OUT_DIR/<tag>.scores.tsv (FEN, python int score) for each scale.
"""
import sys
import time
from pathlib import Path

import numpy as np

from scripts.analyze_scale_alternatives import rescale
from scripts.audit_checkpoint_quantization import default_corpus, load_checkpoint
from trainer.export.canonical import checkpoint_to_canonical
from trainer.export.exporter import _nnue_bytes
from trainer.quantization.inference import _feature_lists, eval_quantized_canonical, accumulator_bound_violations
from trainer.quantization.quantizer import quantize

checkpoint, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)
fens, _ = default_corpus()
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
