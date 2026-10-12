"""Loads the versioned Feature Specification (`docs/architecture/feature-spec/v1.json`,
NNUE_TRAINER_ARCHITECTURE.md Section 4.1) that FeatureEncoder derives its behavior
from -- the single source of truth for feature count, piece/color/square ordering,
and the index formula's rules, shared with Java's FeatureExtractor (which is verified
against the same file by FeatureSpecConformanceTest.java, not by parsing it at
runtime -- see Section 4.1's "Honest scoping of 'derive'").
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Tuple


def repo_root(start: Path) -> Path:
    """Walks upward from `start` to the nearest ancestor containing `.git` -- the
    shared path-resolution strategy both the spec and the parity corpus fixture use
    (NNUE_TRAINER_ARCHITECTURE.md Section 4.1 "Path resolution"), avoiding the
    working-directory fragility of hardcoded relative-segment counts.
    """
    for candidate in (start, *start.parents):
        if (candidate / ".git").exists():
            return candidate
    raise FileNotFoundError(f"no .git ancestor found starting from {start}")


@dataclass(frozen=True)
class FeatureSpec:
    spec_version: int
    feature_set_id: str
    piece_types: Tuple[str, ...]
    colors: Tuple[str, ...]
    squares: int
    features_per_perspective: int


def load_feature_spec() -> FeatureSpec:
    spec_path = repo_root(Path(__file__).resolve()) / "docs" / "architecture" / "feature-spec" / "v1.json"
    with open(spec_path, "r", encoding="utf-8") as f:
        raw = json.load(f)
    return FeatureSpec(
        spec_version=raw["spec_version"],
        feature_set_id=raw["feature_set_id"],
        piece_types=tuple(raw["piece_types"]),
        colors=tuple(raw["colors"]),
        squares=raw["squares"],
        features_per_perspective=raw["features_per_perspective"],
    )
