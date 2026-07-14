from pathlib import Path

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.mmap_shard import write_shard
from trainer.dataset.stockfish_provider import StockfishLabeledProvider


def _write_fixture_shard(directory: Path, name: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    records = [
        PositionRecord(
            fen="rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            label=PositionLabel(eval_cp=25),
            metadata=PositionMetadata(search_depth=12, search_nodes=500000),
        ),
        PositionRecord(
            fen="6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1",
            label=PositionLabel(eval_mate=1),
            metadata=PositionMetadata(search_depth=8, search_nodes=1000),
        ),
    ]
    write_shard(records, directory / name)


def test_metadata_reports_sf_labeled_stage(tmp_path):
    provider = StockfishLabeledProvider(directory=tmp_path, identifier="sf-run-1", source_ref="stockfish16-nodes8000")
    metadata = provider.metadata()
    assert metadata.identifier == "sf-run-1"
    assert metadata.stage == "sf-labeled"
    assert metadata.source_ref == "stockfish16-nodes8000"


def test_shards_enumerates_bin_files(tmp_path):
    _write_fixture_shard(tmp_path, "shard-0.bin")
    _write_fixture_shard(tmp_path, "shard-1.bin")
    provider = StockfishLabeledProvider(directory=tmp_path, identifier="x", source_ref="x")

    shards = list(provider.shards())
    assert len(shards) == 2
    assert {Path(s.locator).name for s in shards} == {"shard-0.bin", "shard-1.bin"}


def test_positions_streams_records_with_search_metadata(tmp_path):
    _write_fixture_shard(tmp_path, "shard-0.bin")
    provider = StockfishLabeledProvider(directory=tmp_path, identifier="x", source_ref="x")
    shard = next(iter(provider.shards()))

    records = list(provider.positions(shard))
    assert len(records) == 2
    assert records[0].label.eval_cp == 25
    assert records[0].metadata.search_depth == 12
    assert records[0].metadata.search_nodes == 500000
    assert records[1].label.eval_mate == 1


def test_provider_does_not_import_labeling_driver_or_other_providers():
    # Invariant 1 (DatasetProvider isolation): this module must not import
    # trainer.dataset.text_provider, scripts.stockfish_label, or Trainer/Quantizer/
    # Exporter internals. Checked directly against the module's own source rather
    # than trusting the docstring's claim.
    import inspect

    import trainer.dataset.stockfish_provider as module

    source = inspect.getsource(module)
    assert "text_provider" not in source
    assert "scripts.stockfish_label" not in source
    assert "trainer.model" not in source
    assert "trainer.export" not in source
    assert "trainer.quantization" not in source
