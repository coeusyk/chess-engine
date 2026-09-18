from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord

from scripts.phase5_rq5_label_noise_floor import (
    _extract_fen,
    compute_spread,
    fixed_subsample,
    magnitude_bucket,
    summarize,
)


def _rec(fen: str, eval_cp=None, eval_mate=None) -> PositionRecord:
    return PositionRecord(fen=fen, label=PositionLabel(eval_cp=eval_cp, eval_mate=eval_mate),
                           metadata=PositionMetadata())


def test_extract_fen_strips_c9_opcode():
    line = 'rnb1kbnr/pp1pppp1/7p/2q5/5P2/N1P1P3/P2P2PP/R1BQKBNR w KQkq - c9 "1/2-1/2";'
    assert _extract_fen(line) == "rnb1kbnr/pp1pppp1/7p/2q5/5P2/N1P1P3/P2P2PP/R1BQKBNR w KQkq -"


def test_extract_fen_handles_blank_line():
    assert _extract_fen("\n") is None


def test_fixed_subsample_is_deterministic_and_evenly_spaced():
    population = [f"fen{i}" for i in range(20000)]
    sample = fixed_subsample(population, size=1000, stride=20)
    assert len(sample) == 1000
    assert sample[0] == "fen0"
    assert sample[1] == "fen20"
    assert sample == fixed_subsample(population, size=1000, stride=20)


def test_fixed_subsample_rejects_undersized_population():
    try:
        fixed_subsample(["only-one"], size=1000, stride=20)
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_magnitude_bucket_boundaries():
    assert magnitude_bucket(0) == "near_zero"
    assert magnitude_bucket(24.9) == "near_zero"
    assert magnitude_bucket(25) == "moderate"
    assert magnitude_bucket(199.9) == "moderate"
    assert magnitude_bucket(200) == "large"
    assert magnitude_bucket(800) == "extreme"
    assert magnitude_bucket(50000) == "extreme"


def test_compute_spread_diffs_matched_cp_pairs():
    low = [_rec("a", eval_cp=10), _rec("b", eval_cp=100)]
    high = [_rec("a", eval_cp=15), _rec("b", eval_cp=70)]
    result = compute_spread(low, high)
    assert result.matched_cp_pairs == 2
    assert result.cp_spread == [5, 30]
    assert result.mode_mismatch_count == 0


def test_compute_spread_separates_mode_mismatch_from_cp_spread():
    low = [_rec("a", eval_cp=10), _rec("b", eval_mate=3)]
    high = [_rec("a", eval_cp=15), _rec("b", eval_cp=900)]
    result = compute_spread(low, high)
    assert result.matched_cp_pairs == 1
    assert result.mode_mismatch_count == 1
    assert result.both_mate_count == 0


def test_compute_spread_tracks_both_mate_ply_diff_separately_from_cp():
    low = [_rec("a", eval_mate=3)]
    high = [_rec("a", eval_mate=5)]
    result = compute_spread(low, high)
    assert result.both_mate_count == 1
    assert result.mate_ply_spread == [2]
    assert result.cp_spread == []


def test_compute_spread_rejects_length_mismatch():
    try:
        compute_spread([_rec("a", eval_cp=1)], [])
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_summarize_verdict_thresholds():
    low = [_rec(str(i), eval_cp=0) for i in range(5)]
    high = [_rec(str(i), eval_cp=30) for i in range(5)]  # spread = 30 >= 25 -> material
    result = compute_spread(low, high)
    summary = summarize(result, low)
    assert summary["cp_spread"]["median"] == 30
    assert summary["verdict"] == "material"


def test_summarize_verdict_negligible():
    low = [_rec(str(i), eval_cp=0) for i in range(5)]
    high = [_rec(str(i), eval_cp=5) for i in range(5)]  # spread = 5 < 10 -> negligible
    result = compute_spread(low, high)
    summary = summarize(result, low)
    assert summary["verdict"] == "negligible"
