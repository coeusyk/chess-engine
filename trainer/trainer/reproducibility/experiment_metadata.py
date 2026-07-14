"""Run-identification metadata (NNUE_TRAINER_ARCHITECTURE.md Section 10.1) -- the
lighter of two reproducibility tiers PyTorch itself documents: "identify this run"
(seed, code version, config) vs. "exact mid-training resume" (full RNG state dumps).
Vex only needs the first -- Invariant 7 already disclaims bit-exact GPU
reproducibility, so RNG-state checkpoint fields would be dead weight (research note
2026-07-14, idea #4). This is deliberately lighter than Section 9's provenance
manifest (Exporter's job, D-6) -- this module captures what a *training run* needs to
be identified; Exporter assembles the full manifest at export time from a checkpoint
produced using this metadata, not the other way around.
"""

from __future__ import annotations

import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict


def _trainer_commit() -> str:
    """The full git commit hash of the working tree, resolved via `git` itself (which
    walks up to the containing repository from any subdirectory) -- no repo-root
    detection of our own needed.
    """
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=Path(__file__).resolve().parent,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


@dataclass(frozen=True)
class ExperimentMetadata:
    """Identifies one training run -- not a full provenance manifest (Section 9,
    Exporter's job at export time), just enough to answer "what produced this
    checkpoint" without asking anyone: the seed, the trainer code version, when it
    started, and the resolved training configuration.
    """

    seed: int
    trainer_commit: str
    started_at_epoch_seconds: int
    config: Dict[str, Any]


def capture(seed: int, config: Dict[str, Any]) -> ExperimentMetadata:
    """Captures `ExperimentMetadata` for a run starting now, with the given seed and
    resolved config.
    """
    return ExperimentMetadata(
        seed=seed,
        trainer_commit=_trainer_commit(),
        started_at_epoch_seconds=int(time.time()),
        config=config,
    )
