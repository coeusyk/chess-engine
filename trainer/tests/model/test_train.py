from pathlib import Path

import pytest
import torch

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.text_provider import TextDatasetProvider
from trainer.model.network import derive_weight_clip_bounds
from trainer.model.train import TrainingConfig, load_config, texel_sigmoid, train

FIXTURE_DIR = Path(__file__).parent.parent / "fixtures"

TINY_CONFIG = TrainingConfig(
    hidden_width=4,
    qa=127,
    qb=64,
    output_scale=400,
    k=1.0,
    learning_rate=0.01,
    seed=42,
    steps=3,
    batch_size=2,
)


def _records():
    provider = TextDatasetProvider(directory=FIXTURE_DIR, identifier="x", source_ref="x")
    shard = next(iter(provider.shards()))
    return list(provider.positions(shard))


def test_texel_sigmoid_matches_kfinder_java_formula():
    # sigma(0) = 0.5 regardless of K -- the same fixed point KFinder.java's own
    # sigmoid(eval=0, k) has.
    assert texel_sigmoid(torch.tensor(0.0), k=1.0).item() == 0.5


def test_train_produces_a_loadable_checkpoint(tmp_path):
    checkpoint_path = tmp_path / "checkpoint.pt"
    result = train(TINY_CONFIG, _records(), checkpoint_path)

    assert checkpoint_path.exists()
    assert len(result["losses"]) == TINY_CONFIG.steps

    checkpoint = torch.load(checkpoint_path, weights_only=False)
    assert set(checkpoint.keys()) == {
        "model_state_dict",
        "optimizer_state_dict",
        "config",
        "experiment_metadata",
        "final_loss",
    }
    assert checkpoint["config"]["hidden_width"] == 4
    assert checkpoint["experiment_metadata"]["seed"] == 42


def test_train_is_reproducible_given_the_same_seed(tmp_path):
    train(TINY_CONFIG, _records(), tmp_path / "a.pt")
    train(TINY_CONFIG, _records(), tmp_path / "b.pt")

    checkpoint_a = torch.load(tmp_path / "a.pt", weights_only=False)
    checkpoint_b = torch.load(tmp_path / "b.pt", weights_only=False)

    for key in checkpoint_a["model_state_dict"]:
        assert torch.equal(checkpoint_a["model_state_dict"][key], checkpoint_b["model_state_dict"][key])
    assert checkpoint_a["final_loss"] == checkpoint_b["final_loss"]


def test_train_enforces_weight_clipping_bound(tmp_path):
    train(TINY_CONFIG, _records(), tmp_path / "checkpoint.pt")
    checkpoint = torch.load(tmp_path / "checkpoint.pt", weights_only=False)

    bias_clip, weight_clip = derive_weight_clip_bounds(TINY_CONFIG.qa)
    ft_weight = checkpoint["model_state_dict"]["ft.weight"]
    ft_bias = checkpoint["model_state_dict"]["ft_bias"]

    assert torch.all(ft_weight.abs() <= weight_clip + 1e-4)
    assert torch.all(ft_bias.abs() <= bias_clip + 1e-4)


def test_train_rejects_wdl_only_labels_loudly(tmp_path):
    # No DatasetProvider reachable from D-1/D-2/D-3 ever populates wdl (Stage 1 text
    # data has none) -- this pins that train() fails loudly rather than silently
    # treating the PRD's eval/WDL blend as satisfied when it isn't.
    wdl_only_record = PositionRecord(
        fen="4k3/8/8/8/8/8/8/4K3 w - - 0 1",
        label=PositionLabel(wdl=1.0),
        metadata=PositionMetadata(),
    )
    with pytest.raises(ValueError, match="WDL-only labels"):
        train(TINY_CONFIG, [wdl_only_record], tmp_path / "checkpoint.pt")


def test_load_config_reads_all_fields_from_json(tmp_path):
    config_path = tmp_path / "config.json"
    config_path.write_text(
        '{"hidden_width": 4, "qa": 127, "qb": 64, "output_scale": 400, "k": 1.0, '
        '"learning_rate": 0.01, "seed": 42, "steps": 3, "batch_size": 2}'
    )
    config = load_config(config_path)
    assert config == TINY_CONFIG
