from pathlib import Path

import pytest

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset import read_shard, write_shard
from trainer.dataset.text_provider import TextDatasetProvider

FIXTURE_DIR = Path(__file__).parent.parent / "fixtures"


def _fixture_records() -> list:
    provider = TextDatasetProvider(directory=FIXTURE_DIR, identifier="x", source_ref="x")
    shard = next(iter(provider.shards()))
    return list(provider.positions(shard))


def test_round_trip_preserves_every_field(tmp_path):
    records = _fixture_records()
    shard_path = tmp_path / "shard-0.bin"

    shard_ref = write_shard(records, shard_path)
    assert shard_ref.position_count == len(records)

    round_tripped = list(read_shard(shard_ref))
    assert len(round_tripped) == len(records)
    for original, restored in zip(records, round_tripped):
        assert restored.fen == original.fen
        assert restored.label.eval_cp == original.label.eval_cp
        assert restored.label.eval_mate == original.label.eval_mate
        assert restored.metadata.ply == original.metadata.ply


def test_read_shard_returns_a_generator_not_a_list(tmp_path):
    # read_shard must return a generator backed by np.memmap, not a materialized
    # in-memory list -- asserting the streaming contract itself.
    records = _fixture_records()
    shard_ref = write_shard(records, tmp_path / "shard-stream.bin")
    result = read_shard(shard_ref)
    assert hasattr(result, "__next__"), "read_shard() must be a generator/iterator, not a list"


def test_absent_optional_fields_round_trip_as_none(tmp_path):
    record = PositionRecord(
        fen="4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1",
        label=PositionLabel(eval_mate=3),
        metadata=PositionMetadata(),
    )
    shard_path = tmp_path / "shard-1.bin"
    shard_ref = write_shard([record], shard_path)

    restored = list(read_shard(shard_ref))[0]
    assert restored.label.eval_cp is None
    assert restored.label.eval_mate == 3
    assert restored.metadata.ply is None


def test_oversized_fen_is_rejected(tmp_path):
    record = PositionRecord(
        fen="x" * 200,
        label=PositionLabel(eval_cp=0),
        metadata=PositionMetadata(),
    )
    with pytest.raises(ValueError):
        write_shard([record], tmp_path / "shard-2.bin")


def test_wdl_only_label_is_rejected_loudly_not_silently_dropped(tmp_path):
    # Standards-review finding: SHARD_DTYPE has no wdl field yet -- write_shard must
    # fail at write time, not silently drop the label and fail confusingly later at
    # read time.
    record = PositionRecord(
        fen="4k3/8/8/8/8/8/8/4K3 w - - 0 1",
        label=PositionLabel(wdl=1.0),
        metadata=PositionMetadata(),
    )
    with pytest.raises(ValueError, match="wdl"):
        write_shard([record], tmp_path / "shard-3.bin")


def test_write_shard_streams_across_multiple_internal_batches(tmp_path):
    # Exercises the _WRITE_BATCH_SIZE boundary directly rather than trusting the
    # docstring -- write more than one batch's worth and confirm every record still
    # round-trips correctly.
    from trainer.dataset.mmap_shard import _WRITE_BATCH_SIZE

    base_fen = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
    n = _WRITE_BATCH_SIZE + 5
    records = [
        PositionRecord(fen=base_fen, label=PositionLabel(eval_cp=i), metadata=PositionMetadata())
        for i in range(n)
    ]
    shard_ref = write_shard(records, tmp_path / "shard-4.bin")
    assert shard_ref.position_count == n

    restored = list(read_shard(shard_ref))
    assert len(restored) == n
    assert [r.label.eval_cp for r in restored] == list(range(n))
