"""Dataset-level provenance manifest schema (issue #202 acceptance criterion:
"Both datasets' provenance manifests exist and validate against ... `validate_manifest()`").

`trainer/trainer/export/manifest_schema.py::validate_manifest()` validates the
*`.nnue` export* manifest (network identity, quantization, training config) --
fields like `network_uuid`/`hidden_width`/`nnue_sha256` describe a trained network,
which does not exist yet at the dataset-acquisition/labeling stage this module
covers. Reusing that function here would be a category error, not a stricter check
(architecture-review finding during E-2 planning). This module is the dataset-level
counterpart: the minimum provenance every `DatasetProvider`-facing manifest carries,
regardless of stage.

Deliberately minimal and format-agnostic, matching `export/manifest_schema.py`'s own
convention (hand-rolled, no `jsonschema` dependency -- the shape is small and flat):
only the fields already common to every manifest this project writes today --
`stockfish_label.py`'s Stage 2 manifest (unmodified, per issue #202's Explicit
Non-Scope) and this issue's new Stage 1 acquisition manifest both already carry
`dataset_identifier`/`stage`/`trainer_commit`/`created_at_epoch_seconds` verbatim.
A stage-specific manifest is free to carry additional fields (e.g. `engine_uci_id`,
`source_url`) this validator does not require and does not reject -- the same
additive-field posture `NNUE_TRAINER_ARCHITECTURE.md` Section 9.2 already documents
for the export manifest.
"""

from __future__ import annotations

from typing import Any, Dict, List

from trainer.contracts import VALID_STAGES

_REQUIRED_FIELDS: Dict[str, Any] = {
    "dataset_identifier": str,
    "stage": str,
    "trainer_commit": str,
    "created_at_epoch_seconds": int,
}


def validate_dataset_manifest(manifest: Dict[str, Any]) -> None:
    """Raises `ValueError` listing every violation found (not just the first) if
    `manifest` is missing one of the required provenance fields above, or has the
    wrong type/value for one. Passing this check does not mean the manifest is
    complete for its specific stage (e.g. a Stage 2 manifest missing `engine_uci_id`
    still passes here) -- it means the minimum cross-stage provenance contract holds.
    """
    errors: List[str] = []

    for field, expected_type in _REQUIRED_FIELDS.items():
        if field not in manifest:
            errors.append(f"missing required field {field!r}")
            continue
        value = manifest[field]
        if isinstance(value, bool) or not isinstance(value, expected_type):
            errors.append(f"field {field!r} has type {type(value).__name__}, expected {expected_type}")

    stage = manifest.get("stage")
    if isinstance(stage, str) and stage not in VALID_STAGES:
        errors.append(f"field 'stage' has value {stage!r}, expected one of {sorted(VALID_STAGES)}")

    if errors:
        raise ValueError("dataset manifest schema violations: " + "; ".join(errors))
