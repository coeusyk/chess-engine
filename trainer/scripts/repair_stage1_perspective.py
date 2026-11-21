"""Audit and version historical Stage 1 Lichess shards (issue #263).

Every Stage 1 shard acquired before issue #263 was fixed copied Lichess's
White-perspective `cp`/`mate` verbatim, while the trainer's label contract is
mover-relative. This tool gives a safe, deterministic, **non-destructive** way to
(1) measure the damage and (2) produce a corrected, versioned copy.

    python -m scripts.repair_stage1_perspective audit  <shard_dir> [--json OUT.json]
    python -m scripts.repair_stage1_perspective repair <shard_dir> <new_dir> <new_dataset_identifier> \
        --confirm-white-perspective

Safety properties (each covered by tests/scripts/test_repair_stage1_perspective.py):

* The input directory is only ever opened for reading; originals are never modified,
  renamed or deleted.
* `repair` refuses to write unless `<new_dir>` does not exist, is not the input, and is
  not inside or above the input directory. Files are created exclusively.
* `repair` refuses without `--confirm-white-perspective`: the caller asserts the shards
  came from the pre-#263 Lichess adapter. It also refuses manifests that already carry
  `label_perspective: mover-relative` (a second flip would re-corrupt the labels),
  manifests that are not Stage 1 (`stage != "public"`), and unreadable/ill-formed rows.
* Only Black-to-move rows change (`cp` and `mate` negated); White-to-move rows and any
  extra columns (e.g. `ply`) are preserved verbatim.
* Output is deterministic: shards are processed in sorted order, the audit report has
  sorted keys and fixed rounding, and the new manifest carries no wall-clock timestamp
  (`created_at_epoch_seconds` is inherited from the input manifest unless overridden).
* The new manifest records `repaired_from` (input identifier, manifest and per-shard
  SHA-256) so the repaired dataset is traceable to the untouched original.

Scope limit: this operates on Stage 1 CSV shard directories (`stage1.csv` +
`manifest.json`, as written by `acquire_stage1_lichess.py`). Derived artifacts that
mix sources (mmap shards built from Stage 1 + Stage 2, trained checkpoints) carry no
per-row source tag and cannot be repaired in place; they must be rebuilt from a
repaired Stage 1 directory.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional

from trainer.dataset.lichess_perspective import (
    LABEL_PERSPECTIVE_FIELD,
    MOVER_RELATIVE,
    side_to_move,
    to_mover_relative,
)

_MANIFEST_NAME = "manifest.json"
_REPORT_NAME = "repair-report.json"
_TOOL = "scripts/repair_stage1_perspective.py"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _shards(directory: Path) -> List[Path]:
    # Same discovery rule as TextDatasetProvider.shards(): *.csv directly under dir.
    return sorted(directory.glob("*.csv"))


def _opt_int(value: str) -> Optional[int]:
    return int(value) if value.strip() else None


def _read_manifest(directory: Path) -> Dict[str, Any]:
    path = directory / _MANIFEST_NAME
    if not path.is_file():
        raise ValueError(f"{directory}: missing {_MANIFEST_NAME}; refusing to treat as a Stage 1 dataset")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError(f"{path}: manifest must be a JSON object")
    return manifest


class _SideStats:
    def __init__(self) -> None:
        self.rows = 0
        self.cp_count = self.cp_sum = self.cp_pos = self.cp_neg = self.cp_zero = 0
        self.mate_count = self.mate_pos = self.mate_neg = self.mate_zero = 0

    def add(self, cp: Optional[int], mate: Optional[int]) -> None:
        self.rows += 1
        if cp is not None:
            self.cp_count += 1
            self.cp_sum += cp
            if cp > 0:
                self.cp_pos += 1
            elif cp < 0:
                self.cp_neg += 1
            else:
                self.cp_zero += 1
        if mate is not None:
            self.mate_count += 1
            if mate > 0:
                self.mate_pos += 1
            elif mate < 0:
                self.mate_neg += 1
            else:
                self.mate_zero += 1

    def as_dict(self) -> Dict[str, Any]:
        mean = round(self.cp_sum / self.cp_count, 6) if self.cp_count else None
        return {
            "rows": self.rows,
            "cp": {"count": self.cp_count, "mean": mean, "positive": self.cp_pos,
                   "negative": self.cp_neg, "zero": self.cp_zero},
            "mate": {"count": self.mate_count, "positive": self.mate_pos,
                     "negative": self.mate_neg, "zero": self.mate_zero},
        }


def _iter_rows(shard: Path):
    with open(shard, newline="", encoding="ascii") as f:
        for line_no, row in enumerate(csv.reader(f), start=1):
            if not row:
                continue
            if len(row) < 2:
                raise ValueError(f"{shard}:{line_no}: expected at least fen,eval_cp columns, got {row!r}")
            try:
                cp = _opt_int(row[1])
                mate = _opt_int(row[2]) if len(row) > 2 else None
                side_to_move(row[0])
            except ValueError as exc:
                raise ValueError(f"{shard}:{line_no}: {exc}") from exc
            yield row, cp, mate


def audit(directory: Path) -> Dict[str, Any]:
    """Read-only, deterministic per-side label statistics. Measures only what is in
    `directory`; it never infers or extrapolates counts for data it cannot see.

    How to read it: mover-relative labels from a balanced position source are roughly
    symmetric for both sides to move; White-perspective labels contaminated into a
    mover-relative contract show a Black-to-move cp/mate distribution that is the
    sign-mirror of what the same positions should have. The report states facts; the
    decision to repair is the operator's (`--confirm-white-perspective`).
    """
    directory = Path(directory)
    manifest = _read_manifest(directory)
    shards = _shards(directory)
    if not shards:
        raise ValueError(f"{directory}: no *.csv shards found")

    stats = {"w": _SideStats(), "b": _SideStats()}
    shard_report: Dict[str, Any] = {}
    for shard in shards:
        before = {s: stats[s].rows for s in stats}
        for row, cp, mate in _iter_rows(shard):
            stats[side_to_move(row[0])].add(cp, mate)
        shard_report[shard.name] = {
            "sha256": _sha256(shard),
            "rows_white_to_move": stats["w"].rows - before["w"],
            "rows_black_to_move": stats["b"].rows - before["b"],
        }

    return {
        "dataset_identifier": manifest.get("dataset_identifier"),
        "manifest_stage": manifest.get("stage"),
        "manifest_sha256": _sha256(directory / _MANIFEST_NAME),
        "label_perspective_marker": manifest.get(LABEL_PERSPECTIVE_FIELD),
        "total_rows": stats["w"].rows + stats["b"].rows,
        "white_to_move": stats["w"].as_dict(),
        "black_to_move": stats["b"].as_dict(),
        "shards": shard_report,
    }


def _is_within(child: Path, parent: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def repair(
    input_dir: Path,
    output_dir: Path,
    dataset_identifier: str,
    *,
    confirm_white_perspective: bool,
    created_at_epoch_seconds: Optional[int] = None,
) -> Dict[str, Any]:
    """Writes a corrected copy of `input_dir` into the new directory `output_dir`.
    Returns the repair report (also written to `output_dir/repair-report.json`).
    """
    input_dir = Path(input_dir).resolve()
    output_dir = Path(output_dir).resolve()

    if not confirm_white_perspective:
        raise ValueError(
            "refusing to repair without confirm_white_perspective=True "
            "(--confirm-white-perspective): the caller must assert these shards were produced by "
            "the pre-#263 Lichess adapter, whose labels are White-perspective"
        )
    if output_dir == input_dir or _is_within(output_dir, input_dir) or _is_within(input_dir, output_dir):
        raise ValueError(f"output_dir {output_dir} must be a new directory disjoint from input_dir {input_dir}")
    if output_dir.exists():
        raise ValueError(f"output_dir {output_dir} already exists; refusing to overwrite or merge")
    if not dataset_identifier:
        raise ValueError("dataset_identifier must be non-empty")

    manifest = _read_manifest(input_dir)
    if manifest.get(LABEL_PERSPECTIVE_FIELD) == MOVER_RELATIVE:
        raise ValueError(
            f"{input_dir}: manifest already declares {LABEL_PERSPECTIVE_FIELD}={MOVER_RELATIVE!r}; "
            "flipping again would corrupt the labels"
        )
    if manifest.get("stage") != "public":
        raise ValueError(
            f"{input_dir}: manifest stage is {manifest.get('stage')!r}, expected 'public' (Stage 1); "
            "Stage 2/3 labels are already mover-relative and must not be flipped"
        )
    if dataset_identifier == manifest.get("dataset_identifier"):
        raise ValueError("dataset_identifier must differ from the input dataset's identifier (versioned copy)")
    if created_at_epoch_seconds is None and "created_at_epoch_seconds" not in manifest:
        raise ValueError(f"{input_dir}: manifest missing required field 'created_at_epoch_seconds'")

    shards = _shards(input_dir)
    if not shards:
        raise ValueError(f"{input_dir}: no *.csv shards found")

    # Validate every row before creating anything on disk.
    before = audit(input_dir)

    output_dir.mkdir(parents=True, exist_ok=False)
    try:
        flipped_rows = cp_flipped = mate_flipped = 0
        shard_sha: Dict[str, str] = {}
        for shard in shards:
            out_path = output_dir / shard.name
            with open(shard, "rb") as raw_in:
                first_line = raw_in.readline()
            lineterm = "\r\n" if first_line.endswith(b"\r\n") else "\n"
            with open(out_path, "x", newline="", encoding="ascii") as out:
                writer = csv.writer(out, lineterminator=lineterm)
                for row, cp, mate in _iter_rows(shard):
                    if side_to_move(row[0]) == "b":
                        new_cp, new_mate = to_mover_relative(row[0], cp, mate)
                        row = list(row)
                        if cp is not None:
                            row[1] = str(new_cp)
                            cp_flipped += 1
                        if mate is not None:
                            row[2] = str(new_mate)
                            mate_flipped += 1
                        flipped_rows += 1
                    writer.writerow(row)
            shard_sha[shard.name] = _sha256(out_path)

        from trainer.reproducibility.experiment_metadata import _trainer_commit

        new_manifest = dict(manifest)
        new_manifest.update({
            "dataset_identifier": dataset_identifier,
            LABEL_PERSPECTIVE_FIELD: MOVER_RELATIVE,
            "trainer_commit": _trainer_commit(),
            "created_at_epoch_seconds": (
                created_at_epoch_seconds if created_at_epoch_seconds is not None
                else manifest["created_at_epoch_seconds"]
            ),
            "shard_sha256": shard_sha,
            "repaired_from": {
                "dataset_identifier": manifest.get("dataset_identifier"),
                "manifest_sha256": before["manifest_sha256"],
                "shard_sha256": {name: info["sha256"] for name, info in before["shards"].items()},
                "tool": _TOOL,
                "rows_black_to_move_flipped": flipped_rows,
            },
        })
        # `output_sha256` in acquire-written manifests is the hash of the single stage1.csv.
        if list(shard_sha) == ["stage1.csv"]:
            new_manifest["output_sha256"] = shard_sha["stage1.csv"]
        else:
            new_manifest.pop("output_sha256", None)
        (output_dir / _MANIFEST_NAME).write_text(
            json.dumps(new_manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

        report = {
            "before": before,
            "after": audit(output_dir),
            "rows_black_to_move_flipped": flipped_rows,
            "cp_values_negated": cp_flipped,
            "mate_values_negated": mate_flipped,
        }
        (output_dir / _REPORT_NAME).write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return report
    except BaseException:
        # Only ever removes the directory this call just created (guaranteed not to
        # have existed above) -- never anything under input_dir.
        shutil.rmtree(output_dir, ignore_errors=True)
        raise


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_audit = sub.add_parser("audit", help="read-only per-side-to-move label statistics")
    p_audit.add_argument("shard_dir", type=Path)
    p_audit.add_argument("--json", type=Path, default=None, help="also write the report to this new file")

    p_repair = sub.add_parser("repair", help="write a corrected, versioned copy into a NEW directory")
    p_repair.add_argument("shard_dir", type=Path)
    p_repair.add_argument("new_dir", type=Path)
    p_repair.add_argument("dataset_identifier")
    p_repair.add_argument("--confirm-white-perspective", action="store_true",
                          help="assert the input was produced by the pre-#263 Lichess adapter")

    args = parser.parse_args(argv)
    if args.command == "audit":
        report = audit(args.shard_dir)
        text = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.json is not None:
            if args.json.exists():
                raise SystemExit(f"{args.json} already exists; refusing to overwrite")
            args.json.write_text(text, encoding="utf-8")
        print(text, end="")
        return 0

    report = repair(args.shard_dir, args.new_dir, args.dataset_identifier,
                    confirm_white_perspective=args.confirm_white_perspective)
    print(f"repaired {report['rows_black_to_move_flipped']} Black-to-move rows -> {args.new_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
