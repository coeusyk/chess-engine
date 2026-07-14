import dataclasses
import pickle
from pathlib import Path

import numpy as np
import pytest
import torch

from trainer.contracts import PositionLabel, PositionMetadata, PositionRecord
from trainer.dataset.text_provider import TextDatasetProvider
from trainer.export.canonical import (
    ARCHITECTURE_ID,
    FEATURE_SET_ID,
    CanonicalNetwork,
    QuantizedCanonicalNetwork,
    checkpoint_to_canonical,
)
from trainer.export.canonical import FEATURES_PER_PERSPECTIVE
from trainer.model.train import TrainingConfig, train
from trainer.quantization.quantizer import quantize

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


def _sample_network(hidden_width: int = 4) -> CanonicalNetwork:
    rng = np.random.default_rng(0)
    return CanonicalNetwork(
        hidden_width=hidden_width,
        ft_weights=rng.uniform(-50, 50, size=(FEATURES_PER_PERSPECTIVE, hidden_width)).astype(np.float32),
        ft_biases=rng.uniform(-10, 10, size=hidden_width).astype(np.float32),
        output_weights=rng.uniform(-50, 50, size=(2, hidden_width)).astype(np.float32),
        output_bias=1.5,
        qa=127,
        qb=64,
        output_scale=400,
        architecture_id=ARCHITECTURE_ID,
        feature_set_id=FEATURE_SET_ID,
    )


def test_canonical_network_rejects_mismatched_ft_weights_shape():
    with pytest.raises(ValueError, match="ft_weights shape"):
        CanonicalNetwork(
            hidden_width=4,
            ft_weights=np.zeros((FEATURES_PER_PERSPECTIVE, 3), dtype=np.float32),
            ft_biases=np.zeros(4, dtype=np.float32),
            output_weights=np.zeros((2, 4), dtype=np.float32),
            output_bias=0.0,
            qa=127,
            qb=64,
            output_scale=400,
            architecture_id=ARCHITECTURE_ID,
            feature_set_id=FEATURE_SET_ID,
        )


def test_canonical_network_is_frozen_against_attribute_reassignment():
    network = _sample_network()
    with pytest.raises(dataclasses.FrozenInstanceError):
        network.hidden_width = 8


def test_quantized_canonical_network_is_frozen_against_attribute_reassignment():
    quantized = quantize(_sample_network())
    with pytest.raises(dataclasses.FrozenInstanceError):
        quantized.hidden_width = 8


def test_canonical_network_arrays_are_not_writeable_in_place():
    network = _sample_network()
    with pytest.raises(ValueError, match="read-only"):
        network.ft_weights[0, 0] = 999.0


def test_canonical_network_copies_arrays_so_the_source_cannot_alias_it():
    source = np.zeros((FEATURES_PER_PERSPECTIVE, 4), dtype=np.float32)
    network = CanonicalNetwork(
        hidden_width=4,
        ft_weights=source,
        ft_biases=np.zeros(4, dtype=np.float32),
        output_weights=np.zeros((2, 4), dtype=np.float32),
        output_bias=0.0,
        qa=127,
        qb=64,
        output_scale=400,
        architecture_id=ARCHITECTURE_ID,
        feature_set_id=FEATURE_SET_ID,
    )
    source[0, 0] = 999.0
    assert network.ft_weights[0, 0] == 0.0


def test_canonical_network_copies_a_live_torch_tensor_numpy_view():
    tensor = torch.zeros(FEATURES_PER_PERSPECTIVE, 4)
    view = tensor.detach().numpy()
    network = CanonicalNetwork(
        hidden_width=4,
        ft_weights=view,
        ft_biases=np.zeros(4, dtype=np.float32),
        output_weights=np.zeros((2, 4), dtype=np.float32),
        output_bias=0.0,
        qa=127,
        qb=64,
        output_scale=400,
        architecture_id=ARCHITECTURE_ID,
        feature_set_id=FEATURE_SET_ID,
    )
    tensor.fill_(999.0)
    assert network.ft_weights[0, 0] == 0.0


def test_checkpoint_to_canonical_extracts_matching_shapes_and_values(tmp_path):
    checkpoint_path = tmp_path / "checkpoint.pt"
    train(TINY_CONFIG, _records(), checkpoint_path)
    checkpoint = torch.load(checkpoint_path, weights_only=False)

    network = checkpoint_to_canonical(checkpoint)

    state_dict = checkpoint["model_state_dict"]
    assert network.hidden_width == TINY_CONFIG.hidden_width
    assert network.ft_weights.shape == (FEATURES_PER_PERSPECTIVE, TINY_CONFIG.hidden_width)
    np.testing.assert_array_equal(network.ft_weights, state_dict["ft.weight"].numpy())
    np.testing.assert_array_equal(network.ft_biases, state_dict["ft_bias"].numpy())

    output_weight = state_dict["output_layer.weight"].numpy()
    np.testing.assert_array_equal(network.output_weights[0], output_weight[0, : TINY_CONFIG.hidden_width])
    np.testing.assert_array_equal(network.output_weights[1], output_weight[0, TINY_CONFIG.hidden_width :])
    assert network.output_bias == pytest.approx(float(state_dict["output_layer.bias"].numpy()[0]))

    assert network.qa == int(TINY_CONFIG.qa)
    assert network.qb == int(TINY_CONFIG.qb)
    assert network.output_scale == int(TINY_CONFIG.output_scale)
    assert network.architecture_id == ARCHITECTURE_ID
    assert network.feature_set_id == FEATURE_SET_ID


def test_checkpoint_to_canonical_does_not_require_torch_import_at_module_level():
    import sys

    assert "trainer.export.canonical" in sys.modules
    # The module itself must not have pulled torch in as a side effect of import --
    # only checkpoint_to_canonical()'s own local import does that, and this test file
    # already imported torch directly above, so this only proves canonical.py's import
    # doesn't *require* torch to already be present -- see module docstring.
    import trainer.export.canonical as canonical_module

    assert "torch" not in vars(canonical_module)


def test_canonical_network_round_trips_through_pickle_with_no_torch_dependency():
    # architecture doc Section 5 Tradeoffs: "a CanonicalNetwork round-trips... with no
    # torch import required at all" -- pickle (stdlib) is sufficient for this; no
    # bespoke serialization format is needed, and none is built here (that's Exporter's
    # .nnue-specific job, D-6, explicitly out of scope for D-5).
    network = _sample_network()
    restored = pickle.loads(pickle.dumps(network))

    np.testing.assert_array_equal(network.ft_weights, restored.ft_weights)
    np.testing.assert_array_equal(network.ft_biases, restored.ft_biases)
    np.testing.assert_array_equal(network.output_weights, restored.output_weights)
    assert restored.output_bias == network.output_bias
    assert restored.hidden_width == network.hidden_width
    assert restored.ft_weights.flags.writeable is False
    with pytest.raises(dataclasses.FrozenInstanceError):
        restored.hidden_width = 8


def test_quantized_canonical_network_round_trips_through_pickle():
    quantized = quantize(_sample_network())
    restored: QuantizedCanonicalNetwork = pickle.loads(pickle.dumps(quantized))

    np.testing.assert_array_equal(quantized.ft_weights, restored.ft_weights)
    assert restored.ft_weights.dtype == np.int16
    assert restored.output_bias == quantized.output_bias
    assert restored.ft_weights.flags.writeable is False
