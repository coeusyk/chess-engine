"""Provenance manifest schema (NNUE_TRAINER_ARCHITECTURE.md Section 9, PRD
"Provenance"'s sidecar field list) -- Trainer owns this shape per the Contract Matrix
(Section 18). No `jsonschema` dependency: the manifest shape is small, flat, and fixed
in advance (it is exactly `exporter.py::_write_manifest`'s own field set), so a hand-
rolled check follows this codebase's existing convention for shape validation
(`canonical.py::_validate_shapes`, `contracts/dataset.py`'s construction-time checks)
rather than adding a new dependency for a few lines of key/type checking.
"""

from __future__ import annotations

from typing import Any, Dict, List

# name -> expected Python type(s) after `json.loads` (bool excluded from int checks
# deliberately -- Python's `bool` is an `int` subclass, and no manifest field is
# boolean, so allowing it would let a stray `True`/`False` value pass silently).
_REQUIRED_FIELDS: Dict[str, Any] = {
    "format_version": int,
    "architecture_id": int,
    "feature_set_id": int,
    "hidden_width": int,
    "quant_version": int,
    "network_uuid": str,
    "trainer_version": str,
    "trainer_commit": str,
    "created_at_epoch_seconds": int,
    "dataset_composition": list,
    "training_config": dict,
    "training_seed": int,
    "label_engine_version": (str, type(None)),
    "nnue_sha256": str,
}

_DATASET_COMPOSITION_ENTRY_FIELDS: Dict[str, Any] = {
    "identifier": str,
    "stage": str,
    "proportion": (int, float),
}


def validate_manifest(manifest: Dict[str, Any]) -> None:
    """Raises `ValueError` listing every violation found (not just the first) if
    `manifest` does not match the sidecar schema `_write_manifest` produces. A manifest
    that round-trips through `json.loads` and passes this check is guaranteed to have
    every field PRD "Provenance" requires, with the right shape -- not that its values
    are semantically correct (e.g. `nnue_sha256` actually matching the sibling `.nnue`
    file is a separate, stronger check `export()`'s own tests already cover).
    """
    errors: List[str] = []

    for field, expected_type in _REQUIRED_FIELDS.items():
        if field not in manifest:
            errors.append(f"missing required field {field!r}")
            continue
        value = manifest[field]
        if isinstance(value, bool) or not isinstance(value, expected_type):
            errors.append(f"field {field!r} has type {type(value).__name__}, expected {expected_type}")

    dataset_composition = manifest.get("dataset_composition")
    if isinstance(dataset_composition, list):
        for i, entry in enumerate(dataset_composition):
            if not isinstance(entry, dict):
                errors.append(f"dataset_composition[{i}] is not an object")
                continue
            for field, expected_type in _DATASET_COMPOSITION_ENTRY_FIELDS.items():
                if field not in entry:
                    errors.append(f"dataset_composition[{i}] missing {field!r}")
                    continue
                value = entry[field]
                if isinstance(value, bool) or not isinstance(value, expected_type):
                    errors.append(
                        f"dataset_composition[{i}].{field!r} has type {type(value).__name__}, "
                        f"expected {expected_type}"
                    )

    if errors:
        raise ValueError("manifest schema violations: " + "; ".join(errors))
