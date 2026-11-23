"""Shared builders for the quantization-parity tests (issue #265): the committed 393-FEN corpus,
the committed Java golden outputs, and seeded synthetic float32 networks. Not a `test_*.py`
module, so pytest never collects it directly.
"""

from __future__ import annotations

from pathlib import Path
from typing import List, Tuple

import numpy as np

from trainer.encoding.feature_spec import repo_root
from trainer.export.canonical import ARCHITECTURE_ID, FEATURE_SET_ID, FEATURES_PER_PERSPECTIVE, CanonicalNetwork

REPO = repo_root(Path(__file__).resolve())
CORPUS_DIR = REPO / "bench" / "nnue-corpus"
FIXTURE_DIR = REPO / "engine-core" / "src" / "test" / "resources" / "nnue"
CORPUS_FILES = ("tactical", "quiet", "opening", "middlegame", "endgame", "random-legal")


def corpus_fens() -> List[str]:
    """All corpus FENs (4-field), in the order the Java tools write them."""
    fens: List[str] = []
    for name in CORPUS_FILES:
        for line in (CORPUS_DIR / f"{name}.epd").read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                fens.append(" ".join(line.split()[:4]))
    return fens


def golden_rows(csv_name: str) -> Tuple[List[str], List[int]]:
    """`(fens, java_int16_scores)` from a `bench/nnue-corpus/fixture-*-golden-evals.csv`."""
    lines = (CORPUS_DIR / csv_name).read_text().splitlines()
    assert lines[1] == "fen,eval", f"unexpected header in {csv_name}"
    rows = [line.rsplit(",", 1) for line in lines[2:]]
    return [r[0] for r in rows], [int(r[1]) for r in rows]


def synthetic_float_network(seed: int, ft_sigma: float, out_sigma: float, hidden_width: int = 32,
                            bias_mean: float = 30.0, bias_sigma: float = 25.0,
                            output_bias: float = -123.4) -> CanonicalNetwork:
    """A seeded random float32 network in the existing QA/QB-native units. `ft_sigma` and
    `out_sigma` are the weight standard deviations; the interesting contrast is a net whose
    feature-transformer weights use the int16 range (ft_sigma ~ 16) versus one whose FT weights
    are O(1), the regime issue #265 describes for PyTorch's default `EmbeddingBag` init.
    """
    rng = np.random.default_rng(seed)
    return CanonicalNetwork(
        hidden_width=hidden_width,
        ft_weights=rng.normal(0, ft_sigma, (FEATURES_PER_PERSPECTIVE, hidden_width)).astype(np.float32),
        ft_biases=rng.normal(bias_mean, bias_sigma, hidden_width).astype(np.float32),
        output_weights=rng.normal(0, out_sigma, (2, hidden_width)).astype(np.float32),
        output_bias=output_bias,
        qa=127, qb=64, output_scale=400,
        architecture_id=ARCHITECTURE_ID, feature_set_id=FEATURE_SET_ID,
    )


def well_scaled_network() -> CanonicalNetwork:
    """FT weights span the int16 range, output weights give a realistic centipawn spread
    (score standard deviation about 190 cp on the corpus)."""
    return synthetic_float_network(seed=1, ft_sigma=16, out_sigma=12)


def poorly_scaled_network() -> CanonicalNetwork:
    """The regime issue #265 describes: O(1) FT weights (rounding step 1.0 is a large fraction
    of the weight) with large output weights absorbing the scale, so the score spread is similar
    to `well_scaled_network()` but rounding the FT weights distorts it badly."""
    return synthetic_float_network(seed=1, ft_sigma=1, out_sigma=150)
