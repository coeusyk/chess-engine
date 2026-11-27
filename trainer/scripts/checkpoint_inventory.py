"""Inventory of training checkpoints by content, with the deterministic screening shortlist (#265, plan #329).

    python -m scripts.checkpoint_inventory --input LABELS.json --root DIR --output OUT.json [--shortlist]
        [--trust-checkpoint]
    python -m scripts.checkpoint_inventory CKPT.pt [CKPT.pt ...] --output OUT.json

`LABELS.json` maps a label (for example `P1-G04`, the names in `trainer/export/shortlist_groups.json`) to a path
relative to `--root`, in the order `duplicate_of` should be resolved (the shortlist resolves duplicates by its own group order, so the two can name different representatives of one network). The record for each checkpoint holds its base
name, the file SHA-256, `tensor_sha256` (the float network), `network_content_sha256` (the int16 network the
engine would load), the trainer commit and seed when the checkpoint records them, the inference-relevant
parameters, and `duplicate_of` (the label of the first earlier record with the same network). File hashes
cannot find duplicates: two checkpoints with equal tensors but different metadata differ in file hash, and
`export()` gives a new uuid and timestamp on every call. The identity functions are in
`trainer/export/identity.py`.

Records never hold a local path. Checkpoints load with `weights_only=True`; `--trust-checkpoint` allows full
unpickling (it runs code in the file, so only for files you produced) and each record says whether it was
needed. With `--shortlist` the output also carries the shortlist, which is a function of the inventory and the
committed group file only.

Nothing is written next to the checkpoints, and nothing is trained, exported or labelled.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

from scripts.audit_checkpoint_quantization import file_sha256, load_checkpoint
from trainer.export.canonical import checkpoint_to_canonical
from trainer.export.identity import IDENTITY_SCHEMA, network_content_sha256, tensor_sha256
from trainer.export.shortlist import select
from trainer.quantization.quantizer import quantize


def inventory_record(label: str, path: Path, trust_checkpoint: bool) -> Dict[str, Any]:
    needed_trust = False
    try:
        checkpoint = load_checkpoint(path, False)
    except SystemExit:
        if not trust_checkpoint:
            raise
        checkpoint = load_checkpoint(path, True)
        needed_trust = True
    canonical = checkpoint_to_canonical(checkpoint)
    recorded = checkpoint.get("experiment_metadata") or {}
    return {
        "label": label,
        "checkpoint_file": Path(path).name,
        "checkpoint_sha256": file_sha256(path),
        "tensor_sha256": tensor_sha256(canonical),
        "network_content_sha256": network_content_sha256(quantize(canonical)),
        "trainer_commit": recorded.get("trainer_commit"),
        "training_seed": recorded.get("seed"),
        "hidden_width": canonical.hidden_width,
        "qa": canonical.qa,
        "qb": canonical.qb,
        "output_scale": canonical.output_scale,
        "architecture_id": canonical.architecture_id,
        "feature_set_id": canonical.feature_set_id,
        "loaded_with_trust": needed_trust,
    }


def build_inventory(entries: Sequence[Tuple[str, Path]], trust_checkpoint: bool = False) -> List[Dict[str, Any]]:
    records: List[Dict[str, Any]] = []
    first_by_network: Dict[str, str] = {}
    seen_labels = set()
    for label, path in entries:
        if label in seen_labels:
            raise ValueError(f"label {label!r} is used twice (give same-named files in different directories distinct labels with --input)")
        seen_labels.add(label)
        record = inventory_record(label, path, trust_checkpoint)
        record["duplicate_of"] = first_by_network.get(record["network_content_sha256"])
        first_by_network.setdefault(record["network_content_sha256"], label)
        records.append(record)
    return records


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("checkpoints", nargs="*", type=Path)
    parser.add_argument("--input", type=Path, default=None, help="JSON object: label -> path relative to --root.")
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--shortlist", action="store_true")
    parser.add_argument("--trust-checkpoint", action="store_true")
    args = parser.parse_args(argv)

    if args.input is not None:
        def no_duplicate_keys(pairs):
            keys = [k for k, _ in pairs]
            if len(keys) != len(set(keys)):
                raise ValueError("LABELS.json repeats a label")
            return dict(pairs)

        labels = json.loads(args.input.read_text(encoding="utf-8"), object_pairs_hook=no_duplicate_keys)
        entries = [(label, args.root / relative) for label, relative in labels.items()]
    else:
        entries = [(path.name, path) for path in args.checkpoints]
    if not entries:
        parser.error("no checkpoints given")

    records = build_inventory(entries, args.trust_checkpoint)
    output: Dict[str, Any] = {"identity_schema": IDENTITY_SCHEMA, "records": records}
    if args.shortlist:
        result = select(records)
        output["shortlist"] = {"selected": result.selected, "excluded": result.excluded,
                               "network_count": result.network_count}
    text = json.dumps(output, indent=2, sort_keys=True)
    if args.output is not None:
        if args.output.exists():
            raise SystemExit(f"{args.output.name} already exists; refusing to overwrite")
        args.output.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
