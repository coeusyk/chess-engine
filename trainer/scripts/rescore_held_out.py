"""Rescore checkpoints on one held-out set against the original and the corrected Stage 1 labels (plan #329).

    python -m scripts.rescore_held_out --inventory INV.json --labels LABELS.json --root DIR \\
        --original-stage1 DIR --corrected-stage1 DIR --stage2-dir DIR --seed 42 --output OUT.json [--drop-duplicates]

Inference only: no training, no labelling, no export. Each shortlisted network (one checkpoint per deployed
identity in `INV.json`'s shortlist, the first of its labels that `LABELS.json` maps to a path) predicts the held-out
positions once, and those identical predictions are scored with the existing `evaluate_held_out` against two
reference label sets: the original (White-perspective) Stage 1 labels and the corrected (mover-relative) ones.

The held-out set is built exactly as `train_candidate_net.combine_and_split` builds it (Stage 1 records, then
Stage 2 records, optional `drop_duplicates`, `random.Random(seed).shuffle`, the first `HELD_OUT_FRACTION`), once,
from positions and indices, so the original and corrected records are the same ordered records and differ only in
their labels. That is checked, not assumed: the two datasets must have the same ordered FEN sequence, the held-out
records must agree in FEN, metadata and source, and the two prediction vectors must be identical.

Reported, with hashes and record identities: overall, per side to move, per source (`public` Stage 1 and
`sf-labeled` Stage 2) and per source and side, each with the number of records and of reference labels that
differ. Stage 2 labels are identical in both sets by construction, so only the `public` Black-to-move group is
expected to differ. Records hold base names only, never a local path, and the output file must not exist.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import sys
import warnings
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np
import torch

from scripts import train_candidate_net as tcn
from scripts.audit_checkpoint_quantization import file_sha256, load_checkpoint
from trainer.contracts import PositionRecord
from trainer.dataset.transform import position_key
from trainer.model.batching import encode_batch
from trainer.model.network import NnueNet
from trainer.validation.validator import evaluate_held_out


def _sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _label_text(record: PositionRecord) -> str:
    return f"{record.label.eval_cp}|{record.label.eval_mate}"


class HeldOut:
    """The same ordered held-out records under two reference label sets."""

    def __init__(self, original: List[PositionRecord], corrected: List[PositionRecord], sources: List[str],
                 source_index: List[int], seed: int, drop_duplicates: bool, total_records: int):
        self.original, self.corrected = original, corrected
        self.sources, self.source_index = sources, source_index
        self.seed, self.drop_duplicates, self.total_records = seed, drop_duplicates, total_records

    @property
    def fens(self) -> List[str]:
        return [r.fen for r in self.corrected]

    def identity(self) -> Dict[str, Any]:
        return {
            "seed": self.seed, "drop_duplicates": self.drop_duplicates, "combined_records": self.total_records,
            "held_out_records": len(self.corrected),
            "fen_order_sha256": _sha("\n".join(self.fens)),
            "record_identity_sha256": _sha("\n".join(f"{s}:{i}:{f}" for s, i, f in zip(self.sources, self.source_index, self.fens))),
            "original_labels_sha256": _sha("\n".join(_label_text(r) for r in self.original)),
            "corrected_labels_sha256": _sha("\n".join(_label_text(r) for r in self.corrected)),
        }


def _side(fen: str) -> str:
    return fen.split()[1]


def build_held_out(original_stage1: Path, corrected_stage1: Path, stage2_dir: Path, seed: int,
                   drop_duplicates: bool = False) -> HeldOut:
    def stage1(directory: Path) -> List[PositionRecord]:
        provider = tcn.TextDatasetProvider(directory=directory, identifier="stage1", source_ref=directory.name)
        return tcn._load_all(provider)

    records_original, records_corrected = stage1(original_stage1), stage1(corrected_stage1)
    if [r.fen for r in records_original] != [r.fen for r in records_corrected]:
        raise ValueError("the original and corrected Stage 1 directories do not have the same ordered FEN sequence")
    stage2 = tcn._load_all(tcn.StockfishLabeledProvider(directory=stage2_dir, identifier="stage2", source_ref=stage2_dir.name))

    all_original = records_original + stage2
    all_corrected = records_corrected + stage2
    sources = ["public"] * len(records_original) + ["sf-labeled"] * len(stage2)
    source_index = list(range(len(records_original))) + list(range(len(stage2)))

    order = list(range(len(all_corrected)))
    if drop_duplicates:  # `_drop_duplicate_positions` keeps the first entry for each position key
        seen: set = set()
        kept = []
        for i in order:
            key = position_key(all_corrected[i].fen)
            if key not in seen:
                seen.add(key)
                kept.append(i)
        order = kept
    total = len(order)
    random.Random(seed).shuffle(order)  # the same permutation `combine_and_split` applies to its list
    held = order[: int(total * tcn.HELD_OUT_FRACTION)]

    original = [all_original[i] for i in held]
    corrected = [all_corrected[i] for i in held]
    for a, b in zip(original, corrected):
        if a.fen != b.fen or a.metadata != b.metadata:
            raise ValueError("the original and corrected held-out records differ in more than their labels")
    return HeldOut(original, corrected, [sources[i] for i in held], [source_index[i] for i in held], seed,
                   drop_duplicates, total)


def load_model(checkpoint: Dict[str, Any]) -> Tuple[NnueNet, float]:
    config = checkpoint["config"]
    model = NnueNet(config["hidden_width"], config["qa"], config["qb"], config["output_scale"])
    missing, unexpected = model.load_state_dict(checkpoint["model_state_dict"], strict=False)
    if missing:
        raise ValueError(f"checkpoint is missing primary parameters: {missing}")
    if any(not key.startswith("wdl_head") for key in unexpected):  # a P5 net's auxiliary head is not used here
        raise ValueError(f"unexpected non-auxiliary parameters in checkpoint: {unexpected}")
    model.eval()
    return model, float(config["k"])


def predict(model: NnueNet, records: Sequence[PositionRecord]) -> np.ndarray:
    batch = encode_batch(list(records))
    with torch.no_grad():
        return model(batch.us_indices, batch.us_offsets, batch.them_indices, batch.them_offsets).numpy().astype(np.float32)


def _score(model: NnueNet, k: float, records: List[PositionRecord]) -> Dict[str, Any]:
    if not records:
        return {"records": 0}
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        try:
            report = evaluate_held_out(model, records, k)
        except ValueError as error:
            return {"records": len(records), "error": str(error)}
    skipped = sum("skipped" in str(w.message) for w in caught)
    return {"records": len(records), "scored": report.position_count, "wdl_only_skipped": skipped > 0,
            "correlation": report.label_correlation, "loss": report.held_out_loss}


def groups(held: HeldOut) -> Dict[str, List[int]]:
    sides = [_side(f) for f in held.fens]
    out: Dict[str, List[int]] = {"overall": list(range(len(sides)))}
    for side, name in (("w", "white_to_move"), ("b", "black_to_move")):
        out[name] = [i for i, s in enumerate(sides) if s == side]
    for source in ("public", "sf-labeled"):
        out[source] = [i for i, s in enumerate(held.sources) if s == source]
        for side, name in (("w", "white_to_move"), ("b", "black_to_move")):
            out[f"{source}/{name}"] = [i for i in out[source] if sides[i] == side]
    return out


def rescore_network(model: NnueNet, k: float, held: HeldOut) -> Dict[str, Any]:
    predictions_original, predictions_corrected = predict(model, held.original), predict(model, held.corrected)
    if not np.array_equal(predictions_original, predictions_corrected):
        raise ValueError("predictions differ between the two label sets, so they are not scoring the same network output")
    result: Dict[str, Any] = {"predictions_sha256": hashlib.sha256(predictions_original.tobytes()).hexdigest(), "groups": {}}
    for name, indices in groups(held).items():
        differing = sum(_label_text(held.original[i]) != _label_text(held.corrected[i]) for i in indices)
        result["groups"][name] = {
            "labels_differing": differing,
            "original_labels": _score(model, k, [held.original[i] for i in indices]),
            "corrected_labels": _score(model, k, [held.corrected[i] for i in indices]),
        }
    return result


def select_checkpoints(inventory: Dict[str, Any], labels: Dict[str, str], root: Path) -> List[Dict[str, Any]]:
    chosen = []
    for network in inventory["shortlist"]["networks"]:
        label = next((l for l in network["labels"] if l in labels), None)
        if label is None:
            raise ValueError(f"none of the labels {network['labels']} has a path in the labels file")
        chosen.append({"label": label, "labels": network["labels"], "path": Path(root) / labels[label],
                       "network_content_sha256": network["network_content_sha256"]})
    return chosen


def dataset_hashes(directory: Path) -> Dict[str, Any]:
    manifest = directory / "manifest.json"
    return {"directory": directory.name,
            "manifest_sha256": file_sha256(manifest) if manifest.is_file() else None,
            "shards_sha256": {p.name: file_sha256(p) for p in sorted(list(directory.glob("*.csv")) + list(directory.glob("*.bin")))}}


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--original-stage1", type=Path, required=True)
    parser.add_argument("--corrected-stage1", type=Path, required=True)
    parser.add_argument("--stage2-dir", type=Path, required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--drop-duplicates", action="store_true")
    parser.add_argument("--trust-checkpoint", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.output.exists():
        raise SystemExit(f"{args.output.name} already exists; refusing to overwrite")

    inventory = json.loads(args.inventory.read_text(encoding="utf-8"))
    labels = json.loads(args.labels.read_text(encoding="utf-8"))
    chosen = select_checkpoints(inventory, labels, args.root)
    held = build_held_out(args.original_stage1, args.corrected_stage1, args.stage2_dir, args.seed, args.drop_duplicates)

    networks = []
    for entry in chosen:
        model, k = load_model(load_checkpoint(entry["path"], args.trust_checkpoint))
        networks.append({"label": entry["label"], "labels": entry["labels"],
                         "network_content_sha256": entry["network_content_sha256"],
                         "checkpoint_file": entry["path"].name, "checkpoint_sha256": file_sha256(entry["path"]), "k": k,
                         **rescore_network(model, k, held)})
    report = {"held_out": held.identity(),
              "datasets": {"original_stage1": dataset_hashes(args.original_stage1),
                           "corrected_stage1": dataset_hashes(args.corrected_stage1),
                           "stage2": dataset_hashes(args.stage2_dir)},
              "networks": networks,
              "scope": "inference only on the held-out records; no strength claim"}
    text = json.dumps(report, indent=2, sort_keys=True) + "\n"
    args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
