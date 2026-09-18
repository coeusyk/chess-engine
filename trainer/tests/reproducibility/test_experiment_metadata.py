import re
import time

from trainer.reproducibility.experiment_metadata import capture


def test_capture_populates_seed_and_config_verbatim():
    config = {"hidden_width": 256, "lr": 0.001}
    metadata = capture(seed=42, config=config)
    assert metadata.seed == 42
    assert metadata.config == config


def test_capture_resolves_a_real_git_commit():
    metadata = capture(seed=1, config={})
    assert re.fullmatch(r"[0-9a-f]{40}", metadata.trainer_commit)


def test_capture_timestamps_close_to_now():
    before = int(time.time())
    metadata = capture(seed=1, config={})
    after = int(time.time())
    assert before <= metadata.started_at_epoch_seconds <= after
