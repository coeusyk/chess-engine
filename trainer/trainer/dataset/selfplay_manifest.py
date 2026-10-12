"""Stage 3 self-play dataset-generation manifest schema (#210). Distinct from
both `dataset/manifest_schema.py` (Stage 1/2's minimal cross-stage provenance
fields -- `dataset_identifier`/`stage`/`trainer_commit`/`created_at_epoch_seconds`
only, too narrow for self-play's richer per-source/per-game provenance and
assessment needs) and `export/manifest_schema.py` (a different artifact
entirely: network-export manifests describing a *trained network*, not
dataset-generation provenance -- reusing it here would be the same category
error #202 already found when `dataset/manifest_schema.py` was written).

Replaces the original `generator_validated: bool` idea (#210's own rewritten
scope): a boolean cannot distinguish "rejected" from "approved" from "never
assessed," which is exactly the failure mode where a later process could
select a rejected generator's output believing it was cleared. `assessment`
below is a real three-state model instead. This manifest records assessment
*state* only -- it does not enforce generation/release policy (that is #212's
future job); `SelfPlayProvider` and `selfplay_ingest.py` must never refuse
data merely because `assessment.outcome == "rejected"`.
"""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any, Dict, List

MANIFEST_VERSION = 1

_ASSESSMENT_STATUSES = frozenset({"unassessed", "assessed"})
_ASSESSMENT_OUTCOMES = frozenset({"approved", "rejected"})
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

_REQUIRED_SOURCE_FIELDS: Dict[str, Any] = {
    "path": str,
    "sha256": str,
    "run_id": str,
    "generator_network_uuid": str,
    "generator_network_sha256": str,
    "engine_build_id": str,
    "format_version": int,
}

_REQUIRED_SHARD_FIELDS: Dict[str, Any] = {
    "path": str,
    "sha256": str,
    "record_count": int,
}


def _check_type(errors: List[str], where: str, value: Any, expected_type: type) -> bool:
    if isinstance(value, bool) or not isinstance(value, expected_type):
        errors.append(f"{where} has type {type(value).__name__}, expected {expected_type.__name__}")
        return False
    return True


def _check_sha256(errors: List[str], where: str, value: Any) -> None:
    if not isinstance(value, str) or not _SHA256_RE.match(value):
        errors.append(f"{where} is not a valid lowercase 64-hex-character SHA-256 digest: {value!r}")


def validate_selfplay_manifest(manifest: Dict[str, Any]) -> None:
    """Raises `ValueError` listing every violation found (not just the first).
    Pure schema/invariant validation -- does not touch the filesystem, does
    not recompute checksums against real files (see
    `verify_manifest_against_shards` for that). Passing this check means the
    manifest's own shape and internal invariants hold, not that it describes
    real, still-present shard files.
    """
    errors: List[str] = []

    version = manifest.get("manifest_version")
    if version != MANIFEST_VERSION:
        errors.append(f"unknown manifest_version {version!r} (expected {MANIFEST_VERSION})")

    if "dataset_identifier" not in manifest:
        errors.append("missing required field 'dataset_identifier'")
    elif not _check_type(errors, "'dataset_identifier'", manifest["dataset_identifier"], str):
        pass

    if "created_at_epoch_seconds" not in manifest:
        errors.append("missing required field 'created_at_epoch_seconds'")
    else:
        _check_type(errors, "'created_at_epoch_seconds'", manifest["created_at_epoch_seconds"], int)

    for field in ("game_count", "record_count"):
        if field not in manifest:
            errors.append(f"missing required field '{field}'")
        else:
            _check_type(errors, f"'{field}'", manifest[field], int)

    _validate_output_shards(errors, manifest.get("output_shards"))
    _validate_sources(errors, manifest.get("sources"))
    _validate_game_id_mapping(errors, manifest.get("game_id_mapping"))
    _validate_assessment(errors, manifest.get("assessment"))

    if errors:
        raise ValueError("self-play manifest schema violations: " + "; ".join(errors))


def _validate_output_shards(errors: List[str], shards: Any) -> None:
    if shards is None:
        errors.append("missing required field 'output_shards'")
        return
    if not isinstance(shards, list) or not shards:
        errors.append("'output_shards' must be a non-empty list")
        return
    for i, shard in enumerate(shards):
        if not isinstance(shard, dict):
            errors.append(f"output_shards[{i}] must be an object")
            continue
        for field, expected_type in _REQUIRED_SHARD_FIELDS.items():
            if field not in shard:
                errors.append(f"output_shards[{i}] missing required field '{field}'")
            else:
                _check_type(errors, f"output_shards[{i}].{field}", shard[field], expected_type)
        if "sha256" in shard:
            _check_sha256(errors, f"output_shards[{i}].sha256", shard["sha256"])


def _validate_sources(errors: List[str], sources: Any) -> None:
    if sources is None:
        errors.append("missing required field 'sources'")
        return
    if not isinstance(sources, list) or not sources:
        errors.append("'sources' must be a non-empty list")
        return
    seen_sha256: Dict[str, int] = {}
    for i, source in enumerate(sources):
        if not isinstance(source, dict):
            errors.append(f"sources[{i}] must be an object")
            continue
        for field, expected_type in _REQUIRED_SOURCE_FIELDS.items():
            if field not in source:
                errors.append(f"sources[{i}] missing required field '{field}'")
            else:
                _check_type(errors, f"sources[{i}].{field}", source[field], expected_type)
        sha = source.get("sha256")
        if sha is not None:
            _check_sha256(errors, f"sources[{i}].sha256", sha)
            if isinstance(sha, str):
                if sha in seen_sha256:
                    errors.append(
                        f"sources[{i}] duplicates sources[{seen_sha256[sha]}]'s sha256 {sha} -- "
                        "the same source artifact must not be listed twice"
                    )
                else:
                    seen_sha256[sha] = i


def _validate_game_id_mapping(errors: List[str], mapping: Any) -> None:
    if mapping is None:
        errors.append("missing required field 'game_id_mapping'")
        return
    if not isinstance(mapping, dict):
        errors.append("'game_id_mapping' must be an object")
        return
    if "rule" not in mapping or not isinstance(mapping["rule"], str):
        errors.append("'game_id_mapping.rule' must be a string")
    ranges = mapping.get("ranges")
    if not isinstance(ranges, list):
        errors.append("'game_id_mapping.ranges' must be a list")
        return
    required = {"run_id": str, "raw_game_id_start": int, "raw_game_id_end": int, "dataset_local_start": int}
    prev_local_end = -1
    for i, r in enumerate(ranges):
        if not isinstance(r, dict):
            errors.append(f"game_id_mapping.ranges[{i}] must be an object")
            continue
        for field, expected_type in required.items():
            if field not in r:
                errors.append(f"game_id_mapping.ranges[{i}] missing required field '{field}'")
            else:
                _check_type(errors, f"game_id_mapping.ranges[{i}].{field}", r[field], expected_type)
        if all(f in r for f in required):
            span = r["raw_game_id_end"] - r["raw_game_id_start"]
            if span < 0:
                errors.append(f"game_id_mapping.ranges[{i}] has raw_game_id_end < raw_game_id_start")
                continue
            local_start = r["dataset_local_start"]
            if local_start <= prev_local_end:
                errors.append(
                    f"game_id_mapping.ranges[{i}].dataset_local_start {local_start} does not "
                    f"continue monotonically after the previous range's end {prev_local_end}"
                )
            prev_local_end = local_start + span


def _validate_assessment(errors: List[str], assessment: Any) -> None:
    if assessment is None:
        errors.append("missing required field 'assessment'")
        return
    if not isinstance(assessment, dict):
        errors.append("'assessment' must be an object")
        return

    status = assessment.get("status")
    outcome = assessment.get("outcome")
    evidence_ref = assessment.get("evidence_ref")

    if status not in _ASSESSMENT_STATUSES:
        errors.append(f"'assessment.status' must be one of {sorted(_ASSESSMENT_STATUSES)}, got {status!r}")
        return

    if status == "unassessed":
        if outcome is not None:
            errors.append("'assessment.outcome' must be null when status is 'unassessed'")
        if evidence_ref is not None:
            errors.append("'assessment.evidence_ref' must be null when status is 'unassessed'")
    else:  # status == "assessed"
        if outcome not in _ASSESSMENT_OUTCOMES:
            errors.append(
                f"'assessment.outcome' must be one of {sorted(_ASSESSMENT_OUTCOMES)} when status "
                f"is 'assessed', got {outcome!r}"
            )
        if evidence_ref is None:
            errors.append("'assessment.evidence_ref' is required when status is 'assessed'")
        elif not isinstance(evidence_ref, str):
            errors.append("'assessment.evidence_ref' must be a string when status is 'assessed'")


def verify_manifest_against_shards(manifest: Dict[str, Any], base_dir: Path) -> None:
    """Beyond `validate_selfplay_manifest`'s pure schema check: recomputes the
    SHA-256 of every `output_shards[i].path` (resolved relative to `base_dir`)
    and compares it against the manifest's recorded value. Raises `ValueError`
    on any mismatch or missing file. Separate from schema validation because
    it touches the filesystem and is O(shard size), not O(manifest size).
    """
    validate_selfplay_manifest(manifest)
    base_dir = Path(base_dir)
    errors: List[str] = []
    for i, shard in enumerate(manifest["output_shards"]):
        shard_path = base_dir / shard["path"]
        if not shard_path.exists():
            errors.append(f"output_shards[{i}]: {shard_path} does not exist")
            continue
        h = hashlib.sha256()
        with open(shard_path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        actual = h.hexdigest()
        if actual != shard["sha256"]:
            errors.append(
                f"output_shards[{i}] ({shard_path}): sha256 mismatch -- manifest says "
                f"{shard['sha256']}, actual file is {actual}"
            )
    if errors:
        raise ValueError("self-play manifest shard checksum violations: " + "; ".join(errors))
