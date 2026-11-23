"""Issue #265 acceptance: Python quantized inference equals the engine's integer inference, with
zero centipawn difference, on the committed `.nnue` fixtures and the 393-FEN corpus.

The Java side of the same comparison is `NnueFixtureGoldenEvalTest`: it checks the live Java
`NnueEvaluator` against the very same golden CSV files read here, so Python equals Java by
transitivity, and a change on either side fails one of the two CI jobs.
"""

import numpy as np
import pytest

from tests.quantization._parity_data import FIXTURE_DIR, corpus_fens, golden_rows
from trainer.export.nnue_reader import read_nnue
from trainer.quantization.inference import eval_quantized_fens

FIXTURES = [
    ("d6-export-fixture.nnue", "fixture-d6-golden-evals.csv", 2),
    ("parity-w32-fixture.nnue", "fixture-w32-golden-evals.csv", 32),
]


@pytest.mark.parametrize("nnue_file,golden_csv,hidden_width", FIXTURES)
def test_python_quantized_inference_matches_java_exactly(nnue_file, golden_csv, hidden_width):
    network, _ = read_nnue(FIXTURE_DIR / nnue_file)
    assert network.hidden_width == hidden_width
    fens, java_scores = golden_rows(golden_csv)

    assert fens == corpus_fens(), "golden FEN order must be the corpus order"
    assert len(fens) == 393

    python_scores = eval_quantized_fens(network, fens)
    difference = np.abs(python_scores - np.array(java_scores, dtype=np.int64))
    mismatches = [(fens[i], int(python_scores[i]), java_scores[i]) for i in np.nonzero(difference)[0]]
    assert not mismatches, f"{len(mismatches)} positions differ from Java, first: {mismatches[:3]}"


def test_the_wide_fixture_exercises_the_cases_the_narrow_one_cannot():
    # Guards the fixture's purpose: if it is ever replaced by something that no longer reaches both
    # clamp ends and negative truncation, the parity test above would quietly weaken.
    _, scores = golden_rows("fixture-w32-golden-evals.csv")
    scores = np.array(scores)
    assert scores.min() < -1000 and scores.max() > 1000
    assert np.count_nonzero(scores < 0) > 100
