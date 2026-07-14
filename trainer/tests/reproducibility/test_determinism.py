import torch

from trainer.reproducibility.determinism import configure_deterministic_execution


def teardown_function():
    # Reset global torch state so this test file doesn't leak determinism settings
    # into other test modules run in the same process.
    torch.use_deterministic_algorithms(False)
    torch.backends.cudnn.deterministic = False
    torch.backends.cudnn.benchmark = False


def test_disabled_leaves_deterministic_algorithms_off():
    configure_deterministic_execution(False)
    assert torch.are_deterministic_algorithms_enabled() is False


def test_enabled_turns_on_deterministic_algorithms():
    configure_deterministic_execution(True)
    assert torch.are_deterministic_algorithms_enabled() is True
    assert torch.backends.cudnn.deterministic is True
    assert torch.backends.cudnn.benchmark is False
