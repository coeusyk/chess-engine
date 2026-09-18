from pathlib import Path

from trainer.dataset import TextDatasetProvider

FIXTURE_DIR = Path(__file__).parent.parent / "fixtures"


def _make_provider() -> TextDatasetProvider:
    return TextDatasetProvider(
        directory=FIXTURE_DIR,
        identifier="stage1-fixture",
        source_ref="test-fixture",
    )


def test_metadata_is_cheap_and_correct():
    provider = _make_provider()
    metadata = provider.metadata()
    assert metadata.identifier == "stage1-fixture"
    assert metadata.stage == "public"


def test_shards_enumerates_csv_files():
    provider = _make_provider()
    shards = list(provider.shards())
    assert len(shards) == 1
    assert shards[0].locator.endswith("stage1_sample.csv")


def test_positions_streams_all_rows():
    provider = _make_provider()
    shard = next(iter(provider.shards()))
    positions = list(provider.positions(shard))
    assert len(positions) == 5


def test_positions_parses_eval_cp_row():
    provider = _make_provider()
    shard = next(iter(provider.shards()))
    positions = list(provider.positions(shard))
    startpos = positions[0]
    assert startpos.fen == "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    assert startpos.label.eval_cp == 20
    assert startpos.label.eval_mate is None
    assert startpos.metadata.ply == 0


def test_positions_parses_mate_row_with_no_eval_cp():
    provider = _make_provider()
    shard = next(iter(provider.shards()))
    positions = list(provider.positions(shard))
    mate_row = positions[-1]
    assert mate_row.label.eval_cp is None
    assert mate_row.label.eval_mate == 3


def test_positions_does_not_load_full_dataset_eagerly():
    # positions() must return a generator (an iterator), not a materialized list --
    # asserting the streaming contract (docs/architecture/NNUE_TRAINER_ARCHITECTURE.md
    # Section 3), not just its observed output.
    provider = _make_provider()
    shard = next(iter(provider.shards()))
    result = provider.positions(shard)
    assert hasattr(result, "__next__"), "positions() must be a generator/iterator, not a list"
