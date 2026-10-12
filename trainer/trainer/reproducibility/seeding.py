"""Single-owner RNG seeding for the trainer (NNUE_TRAINER_ARCHITECTURE.md Section 10.1
"Reproducibility Infrastructure"). One top-level seed threads through `random`,
`numpy`, and `torch` -- per-component seeds (data-shuffle vs. init vs. augmentation)
are a variance-isolation tool for sweep-scale research operations Vex has no
demonstrated need for yet (research note 2026-07-14, idea #2: YAGNI until Stage 3
self-play variance debugging proves otherwise).
"""

from __future__ import annotations

import random

import numpy as np
import torch


def seed_everything(seed: int) -> None:
    """Seeds `random`, `numpy.random`, and `torch` (CPU + CUDA) with one seed.
    Call once, at the start of any script that trains or evaluates.
    """
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def dataloader_generator(seed: int) -> torch.Generator:
    """A seeded `torch.Generator` for `DataLoader(generator=...)` -- keeps shuffle
    order reproducible independent of whatever global seeding happened earlier.
    """
    generator = torch.Generator()
    generator.manual_seed(seed)
    return generator


def worker_init_fn(worker_id: int) -> None:
    """Reseeds NumPy and `random` inside each `DataLoader` worker process.

    PyTorch's own multi-worker default only reseeds *its own* RNG per worker
    (`base_seed + worker_id`); `numpy`/`random` calls inside `Dataset.__getitem__`
    silently duplicate across workers otherwise (research note 2026-07-14, idea #3 --
    docs.pytorch.org/2.13/data.html, "seeds for other libraries may be duplicated upon
    initializing workers"). Pass as `DataLoader(worker_init_fn=worker_init_fn, ...)`.
    """
    worker_seed = torch.initial_seed() % 2**32
    np.random.seed(worker_seed)
    random.seed(worker_seed)
