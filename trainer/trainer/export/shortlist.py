"""The deterministic screening shortlist (#329, plan Slice 3 Task 7).

A pure function of an inventory (label, `network_content_sha256`) and the committed group file
`shortlist_groups.json`. It reads no measured quality: held-out correlation, quantization error, orientation
figures and any other field a record may carry are ignored, so choosing the shortlist cannot depend on how a
network scored. That also fixes the order of work: the shortlist exists before anything is rescored.

Rules (the same text is in the group file):
* the unit of selection is the group (the anchor, a baseline, or a paired comparison). A group is kept whole
  or dropped whole, so a control and its treatment are never split across the cap;
* groups are considered in file order. One network per distinct `network_content_sha256`: a group any of
  whose networks duplicates one already selected is dropped as a duplicate of the earlier group (the earliest
  group in the fixed order represents the network), and so is a group whose own members duplicate each other;
* the cap counts distinct networks. A group that would exceed it is skipped whole and the next group is
  considered, so the tie-break at the cap is the fixed order and nothing else;
* a group with a member that has no inventory record is dropped as missing.

The result lists every dropped group and why, so an exclusion is never silent.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence

GROUPS_FILE = Path(__file__).with_name("shortlist_groups.json")


@dataclass(frozen=True)
class ShortlistResult:
    selected: List[Dict[str, Any]] = field(default_factory=list)
    excluded: List[Dict[str, Any]] = field(default_factory=list)
    network_count: int = 0

    def labels(self) -> List[str]:
        return [label for group in self.selected for label in group["members"]]


def load_groups(path: Path = GROUPS_FILE) -> Dict[str, Any]:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def select(records: Sequence[Mapping[str, Any]], groups: Optional[Mapping[str, Any]] = None) -> ShortlistResult:
    groups = load_groups() if groups is None else groups
    cap = int(groups["cap"])
    by_label: Dict[str, Mapping[str, Any]] = {}
    for record in records:
        label = record["label"]
        if label in by_label:
            raise ValueError(f"label {label!r} appears more than once in the inventory; the result would depend on its order")
        by_label[label] = record

    selected: List[Dict[str, Any]] = []
    excluded: List[Dict[str, Any]] = []
    chosen: Dict[str, str] = {}  # network_content_sha256 -> id of the group that represents it
    for group in groups["groups"]:
        members: List[str] = list(group["members"])
        missing = [m for m in members if m not in by_label]
        if missing:
            excluded.append({"group": group["id"], "reason": "missing", "detail": missing})
            continue
        hashes = [by_label[m]["network_content_sha256"] for m in members]
        earlier = next((chosen[h] for h in hashes if h in chosen), None)
        if earlier is not None:
            excluded.append({"group": group["id"], "reason": "duplicate-of", "detail": earlier})
            continue
        if len(set(hashes)) != len(hashes):
            excluded.append({"group": group["id"], "reason": "duplicate-within-group", "detail": members})
            continue
        if len(chosen) + len(hashes) > cap:
            excluded.append({"group": group["id"], "reason": "cap", "detail": cap})
            continue
        for h in hashes:
            chosen[h] = group["id"]
        selected.append({"group": group["id"], "kind": group["kind"], "members": members,
                         "network_content_sha256": hashes})
    return ShortlistResult(selected=selected, excluded=excluded, network_count=len(chosen))
