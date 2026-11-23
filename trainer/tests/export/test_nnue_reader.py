import numpy as np
import pytest

from tests.export._fixtures import metadata, quantized_network
from trainer.export.exporter import export
from trainer.export.nnue_reader import read_nnue


def test_reader_is_the_exact_inverse_of_the_exporter(tmp_path):
    original = quantized_network()
    result = export(original, metadata(), [], tmp_path)

    loaded, network_uuid = read_nnue(result.nnue_path)

    assert network_uuid == result.network_uuid
    for field in ("hidden_width", "output_bias", "qa", "qb", "output_scale", "architecture_id", "feature_set_id"):
        assert getattr(loaded, field) == getattr(original, field)
    for field in ("ft_weights", "ft_biases", "output_weights"):
        np.testing.assert_array_equal(getattr(loaded, field), getattr(original, field))


def test_reader_rejects_bad_magic_truncation_and_trailing_bytes(tmp_path):
    good = export(quantized_network(), metadata(), [], tmp_path).nnue_path.read_bytes()
    for name, data, message in (
        ("magic", b"XXXX" + good[4:], "bad magic"),
        ("short", good[:-5], "truncated"),
        ("long", good + b"\0", "trailing"),
    ):
        path = tmp_path / f"{name}.nnue"
        path.write_bytes(data)
        with pytest.raises(ValueError, match=message):
            read_nnue(path)
