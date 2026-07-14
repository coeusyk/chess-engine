"""Opt-in fully-deterministic PyTorch execution (NNUE_TRAINER_ARCHITECTURE.md Section
10.1). Not the default for real training runs -- research note 2026-07-14, idea #5:
`torch.use_deterministic_algorithms(True)` raises at runtime for any op with no
deterministic kernel, and disabling `cudnn.benchmark` costs real throughput. Useful
for small/CI-scale runs (Section 12's tiny fixed-seed export-parity check) where that
cost is negligible and exact repeatability matters more than speed.
"""

from __future__ import annotations

import torch


def configure_deterministic_execution(enabled: bool) -> None:
    """Toggles full determinism. `enabled=False` (the default for real training) makes
    no changes -- cuDNN autotuning and non-deterministic kernels stay on for
    throughput. `enabled=True` trades that throughput for exact repeatability given the
    same seed, code, and hardware.
    """
    if not enabled:
        return
    torch.use_deterministic_algorithms(True)
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False
