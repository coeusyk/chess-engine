"""#210 section 10/20 -- SelfPlayProvider: satisfies DatasetProvider, reads
already-ingested V1 shards, has no VSPR dependency at consumption time.
"""

import sys

from trainer.contracts import DatasetProvider, PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.mmap_shard import write_shard
from trainer.dataset.selfplay_provider import SelfPlayProvider

FEN = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"


def test_satisfies_dataset_provider_abc():
    assert issubclass(SelfPlayProvider, DatasetProvider)


def test_reads_ingested_v1_shard(tmp_path):
    record = PositionRecord(
        fen=FEN, label=PositionLabel(eval_cp=10, wdl=1.0), metadata=PositionMetadata(game_id=3)
    )
    write_shard([record], tmp_path / "shard-0.bin")

    provider = SelfPlayProvider(directory=tmp_path, identifier="sp-test", source_ref=str(tmp_path))
    assert provider.metadata().stage == "self-play"
    assert provider.metadata().identifier == "sp-test"

    shards = list(provider.shards())
    assert len(shards) == 1
    positions = list(provider.positions(shards[0]))
    assert len(positions) == 1
    assert positions[0].metadata.game_id == 3
    assert positions[0].label.wdl == 1.0


def test_enumerates_only_bin_files_no_recursion(tmp_path):
    write_shard(
        [PositionRecord(fen=FEN, label=PositionLabel(eval_cp=1), metadata=PositionMetadata())],
        tmp_path / "shard-0.bin",
    )
    (tmp_path / "manifest.json").write_text("{}")
    subdir = tmp_path / "nested"
    subdir.mkdir()
    write_shard(
        [PositionRecord(fen=FEN, label=PositionLabel(eval_cp=1), metadata=PositionMetadata())],
        subdir / "shard-1.bin",
    )

    provider = SelfPlayProvider(directory=tmp_path, identifier="sp-test", source_ref=str(tmp_path))
    shards = list(provider.shards())
    assert len(shards) == 1


def test_no_vspr_import_at_module_level():
    # SelfPlayProvider must decode nothing itself -- confirm the module that
    # defines it never imports trainer.vspr, matching Invariant 1 isolation.
    import trainer.dataset.selfplay_provider as mod

    assert "trainer.vspr" not in sys.modules or "vspr" not in mod.__dict__
    with open(mod.__file__, encoding="utf-8") as f:
        source = f.read()
    assert "import trainer.vspr" not in source
    assert "from trainer import vspr" not in source
    assert "from trainer.vspr" not in source
