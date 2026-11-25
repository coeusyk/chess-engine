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
from trainer.quantization.inference import _feature_lists, _wrap_int16, eval_quantized_fens

FIXTURES = [
    ("d6-export-fixture.nnue", "fixture-d6-golden-evals.csv", 2),
    ("parity-w32-fixture.nnue", "fixture-w32-golden-evals.csv", 32),
    ("parity-w64-qa1016-fixture.nnue", "fixture-w64-qa1016-golden-evals.csv", 64),
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


def test_the_qa1016_fixture_reaches_the_cases_the_qa127_ones_cannot():
    # The comparison above is within one scale: Python equals Java on these bytes. Nothing here says the
    # scores equal those of a QA 127 network, they do not have to. This guards the fixture's purpose: it must
    # be a QA 1016 / QB 128 network that clips activations at both ends and reaches negative truncation.
    network, _ = read_nnue(FIXTURE_DIR / "parity-w64-qa1016-fixture.nnue")
    assert network.qa == 1016 and network.qb == 128

    above = below = 0
    for fen in corpus_fens():
        for features in _feature_lists(fen):
            accumulator = _wrap_int16(
                network.ft_biases.astype(np.int64) + network.ft_weights[list(features)].astype(np.int64).sum(axis=0))
            above += int(np.count_nonzero(accumulator > network.qa))
            below += int(np.count_nonzero(accumulator < 0))
    assert above > 0, "no accumulator value exceeds qa, the upper clamp is never reached"
    assert below > 0, "no accumulator value is below zero, the lower clamp is never reached"

    _, scores = golden_rows("fixture-w64-qa1016-golden-evals.csv")
    scores = np.array(scores)
    assert scores.min() < -1000 and scores.max() > 1000
    assert np.count_nonzero(scores < 0) > 100


def test_the_wide_fixture_exercises_the_cases_the_narrow_one_cannot():
    # Guards the fixture's purpose: if it is ever replaced by something that no longer reaches both
    # clamp ends and negative truncation, the parity test above would quietly weaken.
    _, scores = golden_rows("fixture-w32-golden-evals.csv")
    scores = np.array(scores)
    assert scores.min() < -1000 and scores.max() > 1000
    assert np.count_nonzero(scores < 0) > 100
