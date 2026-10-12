import random

import numpy as np
import torch

from trainer.reproducibility.seeding import dataloader_generator, seed_everything, worker_init_fn


def test_seed_everything_makes_random_reproducible():
    seed_everything(42)
    first = [random.random() for _ in range(5)]
    seed_everything(42)
    second = [random.random() for _ in range(5)]
    assert first == second


def test_seed_everything_makes_numpy_reproducible():
    seed_everything(42)
    first = np.random.rand(5)
    seed_everything(42)
    second = np.random.rand(5)
    assert np.array_equal(first, second)


def test_seed_everything_makes_torch_reproducible():
    seed_everything(42)
    first = torch.rand(5)
    seed_everything(42)
    second = torch.rand(5)
    assert torch.equal(first, second)


def test_seed_everything_different_seeds_diverge():
    seed_everything(1)
    a = torch.rand(5)
    seed_everything(2)
    b = torch.rand(5)
    assert not torch.equal(a, b)


def test_dataloader_generator_is_seeded_reproducibly():
    order_a = torch.randperm(10, generator=dataloader_generator(7))
    order_b = torch.randperm(10, generator=dataloader_generator(7))
    assert torch.equal(order_a, order_b)


def test_worker_init_fn_seeds_numpy_and_random_deterministically():
    torch.manual_seed(123)
    worker_init_fn(worker_id=0)
    first_np = np.random.rand(3)
    first_random = random.random()

    torch.manual_seed(123)
    worker_init_fn(worker_id=0)
    second_np = np.random.rand(3)
    second_random = random.random()

    assert np.array_equal(first_np, second_np)
    assert first_random == second_random
