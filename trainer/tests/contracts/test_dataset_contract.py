import pytest

from trainer.contracts import (
    DatasetMetadata,
    DatasetProvider,
    PositionLabel,
    PositionMetadata,
    PositionRecord,
    ShardRef,
)


def test_position_label_requires_at_least_one_field():
    with pytest.raises(ValueError):
        PositionLabel()


def test_position_label_accepts_eval_cp_only():
    label = PositionLabel(eval_cp=25)
    assert label.eval_cp == 25
    assert label.eval_mate is None
    assert label.wdl is None


def test_position_label_accepts_wdl_only():
    label = PositionLabel(wdl=0.5)
    assert label.wdl == 0.5


def test_dataset_provider_cannot_be_instantiated_directly():
    with pytest.raises(TypeError):
        DatasetProvider()


def test_dataset_provider_requires_all_three_methods():
    class Incomplete(DatasetProvider):
        def metadata(self) -> DatasetMetadata:
            return DatasetMetadata(identifier="x", stage="public", source_ref="x")

    with pytest.raises(TypeError):
        Incomplete()


def test_dataset_provider_full_implementation_is_constructible():
    class Complete(DatasetProvider):
        def metadata(self) -> DatasetMetadata:
            return DatasetMetadata(identifier="x", stage="public", source_ref="x")

        def shards(self):
            yield ShardRef(locator="shard-0")

        def positions(self, shard: ShardRef):
            yield PositionRecord(
                fen="startpos",
                label=PositionLabel(eval_cp=0),
                metadata=PositionMetadata(),
            )

    provider = Complete()
    assert provider.metadata().identifier == "x"
    assert list(provider.shards())[0].locator == "shard-0"
    positions = list(provider.positions(ShardRef(locator="shard-0")))
    assert len(positions) == 1
    assert positions[0].fen == "startpos"


def test_position_metadata_has_no_stage_field():
    # Architecture-review fix: stage lives only on DatasetMetadata, never duplicated
    # per-position.
    assert not hasattr(PositionMetadata(), "source_stage")
    assert not hasattr(PositionMetadata(), "stage")
