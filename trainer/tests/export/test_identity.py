"""Content identity of networks (#265, plan #329): the hashes see every inference-relevant parameter and nothing
else, so two artifacts are the same network exactly when their hashes are equal."""

import dataclasses

import numpy as np
import torch

from trainer.export.canonical import checkpoint_to_canonical
from trainer.export.identity import IDENTITY_SCHEMA, network_content_sha256, tensor_sha256
from trainer.model.network import NnueNet
from trainer.model.train import TrainingConfig
from trainer.quantization.quantizer import quantize

HIDDEN = 8


def _config(**override):
    base = dict(hidden_width=HIDDEN, qa=127, qb=64, output_scale=400, k=2.773456, learning_rate=0.01, seed=42, steps=1, batch_size=2)
    base.update(override)
    return dataclasses.asdict(TrainingConfig(**base))


def checkpoint(seed=0, metadata=None, **config):
    torch.manual_seed(seed)
    model = NnueNet(HIDDEN, config.get("qa", 127), config.get("qb", 64), config.get("output_scale", 400))
    return {"model_state_dict": model.state_dict(), "config": _config(**config),
            "experiment_metadata": metadata or {"seed": seed, "trainer_commit": "abc", "started_at_epoch_seconds": 1, "config": {}}}


def test_the_same_network_saved_with_different_metadata_has_the_same_identity():
    a = checkpoint(seed=3, metadata={"seed": 3, "trainer_commit": "one", "started_at_epoch_seconds": 1, "config": {}})
    b = checkpoint(seed=3, metadata={"seed": 99, "trainer_commit": "two", "started_at_epoch_seconds": 2, "config": {"x": 1}})
    assert tensor_sha256(checkpoint_to_canonical(a)) == tensor_sha256(checkpoint_to_canonical(b))
    assert network_content_sha256(quantize(checkpoint_to_canonical(a))) == network_content_sha256(quantize(checkpoint_to_canonical(b)))


def test_one_changed_weight_changes_both_identities():
    canonical = checkpoint_to_canonical(checkpoint(seed=1))
    ft = canonical.ft_weights.copy()
    ft[5, 2] += 0.75
    changed = dataclasses.replace(canonical, ft_weights=ft)
    assert tensor_sha256(changed) != tensor_sha256(canonical)
    assert network_content_sha256(quantize(changed)) != network_content_sha256(quantize(canonical))


def test_networks_that_differ_only_in_a_scalar_parameter_have_different_identities():
    canonical = checkpoint_to_canonical(checkpoint(seed=1))
    quantized = quantize(canonical)
    for field, value in (("qa", 255), ("qb", 128), ("output_scale", 300), ("feature_set_id", 7), ("architecture_id", 9)):
        assert tensor_sha256(dataclasses.replace(canonical, **{field: value})) != tensor_sha256(canonical), field
        assert network_content_sha256(dataclasses.replace(quantized, **{field: value})) != network_content_sha256(quantized), field
    assert network_content_sha256(dataclasses.replace(quantized, output_bias=quantized.output_bias + 1)) != network_content_sha256(quantized)


def test_swapping_the_us_and_them_output_rows_changes_the_identity():
    quantized = quantize(checkpoint_to_canonical(checkpoint(seed=1)))
    rng = np.random.default_rng(0)
    weights = rng.integers(-50, 50, size=quantized.output_weights.shape).astype(np.int16)
    network = dataclasses.replace(quantized, output_weights=weights)
    swapped = dataclasses.replace(quantized, output_weights=np.stack([weights[1], weights[0]]))

    assert network_content_sha256(swapped) != network_content_sha256(network)


def test_arrays_with_equal_bytes_but_different_shapes_do_not_collide():
    from trainer.export.identity import _update_array
    import hashlib
    flat, square = hashlib.sha256(), hashlib.sha256()
    data = np.arange(8, dtype=np.int16)
    _update_array(flat, "a", data, "<i2")
    _update_array(square, "a", data.reshape(2, 4), "<i2")
    assert flat.hexdigest() != square.hexdigest()


def test_identities_are_stable_hex_digests_and_carry_a_schema_version():
    canonical = checkpoint_to_canonical(checkpoint(seed=1))
    assert IDENTITY_SCHEMA == 1
    assert len(tensor_sha256(canonical)) == 64 and len(network_content_sha256(quantize(canonical))) == 64
    assert tensor_sha256(canonical) == tensor_sha256(checkpoint_to_canonical(checkpoint(seed=1)))
    assert tensor_sha256(canonical) != network_content_sha256(quantize(canonical))
