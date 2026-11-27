"""Verify a repaired Stage 1 directory against its original (issue #263), and show that the repair did not move
any position between the training and the held-out set.

    python -m scripts.verify_stage1_repair <original_dir> <repaired_dir>
        [--stage2-dir DIR --seed N [--expect-held-out-sha256 H]]

`repair_stage1_perspective.py` does the repair. This script only reads, and checks the properties that repair
promises, so that anyone can re-run the proof instead of trusting the tool's own report:

* source-record identity: the same positions, the same number of rows in every shard, in the same order (the
  FEN column of each shard, and of the shards concatenated, hashes to the same value). Rows are compared
  shard by shard, so a row moved from one shard to another, or dropped from the end of one, fails;
* every White-to-move row is byte-identical, every Black-to-move row differs only by `cp` and `mate`
  negated (absent values stay absent), and extra columns and row lengths are untouched;
* the original shards still hash to what the repaired manifest's `repaired_from` block records, that block
  names the original manifest and identifier, and its flipped-row count equals the Black-to-move row count;
* the repaired manifest carries `label_perspective: mover-relative` and the production guard accepts it, while
  the guard still refuses the original;
* the repaired manifest's own `shard_sha256` (and `output_sha256` for a single `stage1.csv`) equal the hashes of
  the repaired files, and the line terminator style of each shard is unchanged (the CSV fields are compared
  parsed, so this is what catches a CRLF to LF conversion).

Two claims are kept apart on purpose. **Repair invariance** (`split_membership_invariance`) says the repair
moved no position between the training and held-out sets: the shuffle and split depend only on the order and
length of the combined position list, never on labels, so it must hold for any faithful repair. **Reproducing a
historical held-out set** (comparing `held_out_order_sha256` with a value recorded years earlier) also depends
on Stage 2 and on code history and can legitimately fail without the repair being wrong. The first is a gate,
the second is reported.

The split recipe here repeats three lines of `combine_and_split` (shuffle the tagged union with the seed, take
the first `HELD_OUT_FRACTION`). `tests/scripts/test_verify_stage1_repair.py` compares it with the production
function on a repaired directory so any drift between them fails a test.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Dict, Iterator, List, Optional, Tuple

from scripts import train_candidate_net as tcn
from scripts.repair_stage1_perspective import _iter_rows, _shards
from trainer.dataset.lichess_perspective import (
    LABEL_PERSPECTIVE_FIELD,
    MOVER_RELATIVE,
    UnsafeStage1PerspectiveError,
    require_mover_relative_stage1,
    side_to_move,
    to_mover_relative,
)

_MANIFEST = "manifest.json"
_END = object()


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_bytes(Path(path).read_bytes())


def _read_manifest(directory: Path) -> Dict[str, Any]:
    return json.loads((Path(directory) / _MANIFEST).read_text(encoding="utf-8"))


def _paired_rows(original: Path, repaired: Path) -> Iterator[Tuple[str, Any, Any]]:
    """`(shard name, original row, repaired row)` in order, shard by shard; `_END` pads the shorter side."""
    for shard_original, shard_repaired in zip(_shards(original), _shards(repaired)):
        left, right = _iter_rows(shard_original), _iter_rows(shard_repaired)
        while True:
            a, b = next(left, _END), next(right, _END)
            if a is _END and b is _END:
                break
            yield shard_original.name, a, b


def _line_terminator(path: Path) -> bytes:
    with open(path, "rb") as handle:
        first = handle.readline()
    return b"\r\n" if first.endswith(b"\r\n") else b"\n"


def verify(original: Path, repaired: Path) -> Dict[str, Any]:
    original, repaired = Path(original), Path(repaired)
    original_shards, repaired_shards = _shards(original), _shards(repaired)
    shard_names_equal = [p.name for p in original_shards] == [p.name for p in repaired_shards]

    rows_original = rows_repaired = black_rows = 0
    white_identical = black_negated = extra_identical = True
    fens_original: Dict[str, List[str]] = {}
    fens_repaired: Dict[str, List[str]] = {}
    if shard_names_equal:
        for name, pair_a, pair_b in _paired_rows(original, repaired):
            if pair_a is not _END:
                rows_original += 1
                fens_original.setdefault(name, []).append(pair_a[0][0])
            if pair_b is not _END:
                rows_repaired += 1
                fens_repaired.setdefault(name, []).append(pair_b[0][0])
            if pair_a is _END or pair_b is _END:
                continue  # unmatched tail rows are counted and their FENs recorded; the per-shard comparison below fails
            (row_a, cp_a, mate_a), (row_b, cp_b, mate_b) = pair_a, pair_b
            if len(row_a) != len(row_b) or row_a[3:] != row_b[3:]:
                extra_identical = False
            if row_a[0] != row_b[0]:
                continue
            if side_to_move(row_a[0]) == "w":
                if row_a != row_b:
                    white_identical = False
            else:
                black_rows += 1
                if (cp_b, mate_b) != to_mover_relative(row_a[0], cp_a, mate_a):
                    black_negated = False
    original_fens = [f for name in sorted(fens_original) for f in fens_original[name]]
    repaired_fens = [f for name in sorted(fens_repaired) for f in fens_repaired[name]]
    per_shard_identical = shard_names_equal and fens_original == fens_repaired
    terminators_preserved = shard_names_equal and all(
        _line_terminator(a) == _line_terminator(b) for a, b in zip(original_shards, repaired_shards))

    manifest_original, manifest_repaired = _read_manifest(original), _read_manifest(repaired)
    provenance = manifest_repaired.get("repaired_from") or {}
    original_sha = {p.name: _sha256_file(p) for p in original_shards}
    try:
        require_mover_relative_stage1(repaired)
        accepted = True
    except UnsafeStage1PerspectiveError:
        accepted = False
    try:
        require_mover_relative_stage1(original)
        original_refused = False
    except UnsafeStage1PerspectiveError:
        original_refused = True

    fen_hash = lambda fens: _sha256_bytes("\n".join(fens).encode())
    repaired_sha = {p.name: _sha256_file(p) for p in repaired_shards}
    own_hashes_match = manifest_repaired.get("shard_sha256") == repaired_sha and (
        list(repaired_sha) != ["stage1.csv"] or manifest_repaired.get("output_sha256") == repaired_sha["stage1.csv"])
    report = {
        "original_identifier": manifest_original.get("dataset_identifier"),
        "repaired_identifier": manifest_repaired.get("dataset_identifier"),
        "shard_names_equal": shard_names_equal,
        "rows_original": rows_original,
        "rows_repaired": rows_repaired,
        "black_to_move_rows": black_rows,
        "rows_equal": shard_names_equal and rows_original == rows_repaired
        and all(len(fens_original.get(n, [])) == len(fens_repaired.get(n, [])) for n in fens_original),
        "source_record_identity": per_shard_identical or (shard_names_equal and Counter(original_fens) == Counter(repaired_fens)
                                                          and fens_original.keys() == fens_repaired.keys()
                                                          and all(Counter(fens_original[n]) == Counter(fens_repaired[n]) for n in fens_original)),
        "row_order_identical": per_shard_identical,
        "fen_column_sha256_original": fen_hash(original_fens),
        "fen_column_sha256_repaired": fen_hash(repaired_fens),
        "white_rows_byte_identical": white_identical,
        "black_rows_exactly_negated": black_negated,
        "extra_columns_identical": extra_identical,
        "line_terminators_preserved": terminators_preserved,
        "repaired_manifest_hashes_match_files": own_hashes_match,
        "original_sha256_unchanged": bool(provenance) and provenance.get("shard_sha256") == original_sha,
        "repaired_from_matches_original": bool(provenance)
        and provenance.get("manifest_sha256") == _sha256_file(original / _MANIFEST)
        and provenance.get("dataset_identifier") == manifest_original.get("dataset_identifier")
        and provenance.get("rows_black_to_move_flipped") == black_rows,
        "identifier_differs": manifest_repaired.get("dataset_identifier") != manifest_original.get("dataset_identifier"),
        "marker_is_mover_relative": manifest_repaired.get(LABEL_PERSPECTIVE_FIELD) == MOVER_RELATIVE,
        "accepted_by_require_mover_relative": accepted,
        "original_refused_by_require_mover_relative": original_refused,
    }
    checks = [k for k, v in report.items() if isinstance(v, bool)]
    report["all_checks_pass"] = all(report[k] for k in checks)
    return report


def split_membership(stage1_dir: Path, stage2_dir: Path, seed: int, drop_duplicates: bool = False) -> Dict[str, Any]:
    """The ordered held-out and training FENs `combine_and_split` would produce for these directories, without
    reading a label and without the legacy-label guard (which a legacy original must be allowed to skip so
    that it can be compared). Providers are the production ones. `drop_duplicates=False` is the path that frozen
    checkpoints and the historical P5 hash used; `train_candidate_net.main` trains with `drop_duplicates=True`,
    so pass it to see the split a new training run would get."""
    stage1 = tcn.TextDatasetProvider(directory=stage1_dir, identifier="stage1-lichess-evals-2026-07-15", source_ref=str(stage1_dir))
    stage2 = tcn.StockfishLabeledProvider(directory=stage2_dir, identifier="stage2-sf-labeled-quiet-2026-07-15", source_ref=str(stage2_dir))
    tagged = [(r, stage1.metadata().identifier, "public") for r in tcn._load_all(stage1)]
    tagged += [(r, stage2.metadata().identifier, "sf-labeled") for r in tcn._load_all(stage2)]
    if drop_duplicates:
        tagged = tcn._drop_duplicate_positions(tagged)
    random.Random(seed).shuffle(tagged)
    held_out_count = int(len(tagged) * tcn.HELD_OUT_FRACTION)
    held_out = [r.fen for r, _, _ in tagged[:held_out_count]]
    training = [r.fen for r, _, _ in tagged[held_out_count:]]
    return {
        "held_out_fens": held_out,
        "training_fens": training,
        "held_out_count": len(held_out),
        "training_count": len(training),
        "held_out_order_sha256": _sha256_bytes("\n".join(held_out).encode()),
        "training_order_sha256": _sha256_bytes("\n".join(training).encode()),
    }


def split_membership_invariance(original_stage1: Path, repaired_stage1: Path, stage2_dir: Path, seed: int,
                                drop_duplicates: bool = False) -> Dict[str, Any]:
    before = split_membership(original_stage1, stage2_dir, seed, drop_duplicates)
    after = split_membership(repaired_stage1, stage2_dir, seed, drop_duplicates)
    same_held_out = before["held_out_fens"] == after["held_out_fens"]
    same_training = before["training_fens"] == after["training_fens"]
    return {
        "seed": seed,
        "drop_duplicates": drop_duplicates,
        "split_membership_invariant": same_held_out and same_training,
        "held_out_count": after["held_out_count"],
        "training_count": after["training_count"],
        "held_out_order_sha256": after["held_out_order_sha256"],
        "training_order_sha256": after["training_order_sha256"],
        "held_out_order_sha256_original": before["held_out_order_sha256"],
    }


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("original_dir", type=Path)
    parser.add_argument("repaired_dir", type=Path)
    parser.add_argument("--stage2-dir", type=Path, default=None)
    parser.add_argument("--seed", type=int, default=None)
    parser.add_argument("--drop-duplicates", action="store_true",
                        help="the split `train_candidate_net.main` would train on (default: the historical path)")
    parser.add_argument("--expect-held-out-sha256", default=None,
                        help="a recorded historical held-out hash; a mismatch is reported, it does not fail the repair")
    args = parser.parse_args(argv)

    try:
        report: Dict[str, Any] = {"repair": verify(args.original_dir, args.repaired_dir)}
    except (OSError, ValueError) as error:
        print(f"cannot verify: {error}", file=sys.stderr)
        return 1
    ok = report["repair"]["all_checks_pass"]
    if args.stage2_dir is not None:
        if args.seed is None:
            parser.error("--stage2-dir needs --seed")
        report["split"] = split_membership_invariance(args.original_dir, args.repaired_dir, args.stage2_dir, args.seed,
                                                      args.drop_duplicates)
        ok = ok and report["split"]["split_membership_invariant"]
        if args.expect_held_out_sha256:
            report["split"]["historical_held_out_reproduced"] = (
                report["split"]["held_out_order_sha256"] == args.expect_held_out_sha256)
            report["split"]["historical_held_out_expected_sha256"] = args.expect_held_out_sha256
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
