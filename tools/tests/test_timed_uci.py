import importlib.util
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "timed_uci", Path(__file__).resolve().parents[1] / "measure-307" / "timed_uci.py")
timed_uci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(timed_uci)


def test_epd_lines_become_fens_and_comments_are_skipped():
    assert timed_uci.epd_to_fen("# a comment") is None
    assert timed_uci.epd_to_fen("") is None
    assert timed_uci.epd_to_fen("4k3/8/8/8/8/8/8/4K3 w - - bm Kd2;") == "4k3/8/8/8/8/8/8/4K3 w - - 0 1"


def test_every_committed_corpus_file_yields_positions():
    corpus = Path(__file__).resolve().parents[2] / "bench" / "nnue-corpus"
    fens = timed_uci.load_positions(corpus, 3)
    assert len(fens) == 3 * len(timed_uci.CORPUS_FILES)
    assert all(len(f.split()) == 6 for f in fens)


def test_summary_reports_overshoot_and_speed():
    rows = [{"fen": "x", "wall_ms": 100.0 + o, "overshoot_ms": float(o), "nodes": 50_000} for o in (1, 2, 3, 4, 40)]
    s = timed_uci.summarize(rows, 100)
    assert s["searches"] == 5
    assert s["overshoot_ms_median"] == 3
    assert s["overshoot_ms_max"] == 40
    assert 400_000 < s["nodes_per_second_mean"] < 500_000
