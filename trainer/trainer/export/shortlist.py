"""The deterministic screening shortlist (#329, plan Slice 3 Task 7).

A pure function of an inventory (label, `network_content_sha256`) and the committed group file
`shortlist_groups.json`. It reads no measured quality: held-out correlation, quantization error, orientation
figures and any other field a record may carry are ignored, so choosing the shortlist cannot depend on how a
network scored. That also fixes the order of work: the shortlist exists before anything is rescored.

Rules (the same text is in the group file):
* the unit of selection is the group (the anchor, a baseline, or a paired comparison). A group is kept whole
  or dropped whole, so a control and its treatment are never split across the cap;
* network EXECUTION is deduplicated, experimental RELATIONSHIPS are not. A network is identified by its
  `network_content_sha256`. A comparison one of whose arms is identical to a network already selected reuses that
  network and adds only its distinct counterpart. A comparison whose arms are all already selected is kept at no
  cost, and a comparison whose two arms are identical to each other is kept and flagged `identical_arms`. Every
  selected group lists which of its networks it deployed (`new_networks`) and which it reused and from which
  group (`reused`), so the arm relationship survives;
* the cap counts distinct deployed network identities, once each, whatever number of checkpoints or groups use
  them. A group that would take the count over the cap is skipped whole and the next group is considered, so
  the tie-break at the cap is the fixed order and nothing else. A group that adds no new network always fits;
* checkpoint-level provenance is kept: each deployed network lists every inventory label that has that identity
  (and their checkpoint hashes when the inventory carries them), including checkpoints that appear in no group;
* groups are considered in file order, and the order is the evidence-driven priority written next to each group
  in the file;
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
    networks: List[Dict[str, Any]] = field(default_factory=list)  # one entry per deployed identity, in deployment order

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

    labels_by_network: Dict[str, List[Mapping[str, Any]]] = {}
    for record in by_label.values():
        labels_by_network.setdefault(record["network_content_sha256"], []).append(record)

    selected: List[Dict[str, Any]] = []
    excluded: List[Dict[str, Any]] = []
    chosen: Dict[str, str] = {}  # network_content_sha256 -> id of the group that first deployed it
    for group in groups["groups"]:
        members: List[str] = list(group["members"])
        missing = [m for m in members if m not in by_label]
        if missing:
            excluded.append({"group": group["id"], "reason": "missing", "detail": missing})
            continue
        hashes = [by_label[m]["network_content_sha256"] for m in members]
        new = [h for h in dict.fromkeys(hashes) if h not in chosen]
        if len(chosen) + len(new) > cap:
            excluded.append({"group": group["id"], "reason": "cap", "detail": cap})
            continue
        reused = {h: chosen[h] for h in dict.fromkeys(hashes) if h in chosen}
        for h in new:
            chosen[h] = group["id"]
        selected.append({"group": group["id"], "kind": group["kind"], "members": members,
                         "network_content_sha256": hashes, "new_networks": new, "reused": reused,
                         "identical_arms": len(members) > 1 and len(set(hashes)) == 1})
    networks = [{"network_content_sha256": h, "first_group": first,
                 "labels": sorted(r["label"] for r in labels_by_network[h]),
                 "checkpoint_sha256": sorted({r["checkpoint_sha256"] for r in labels_by_network[h] if r.get("checkpoint_sha256")})}
                for h, first in chosen.items()]
    return ShortlistResult(selected=selected, excluded=excluded, network_count=len(chosen), networks=networks)
