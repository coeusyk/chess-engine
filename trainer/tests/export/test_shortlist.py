"""The deterministic screening shortlist (#329): a pure function of the inventory and the committed group file."""

import copy
import json
import random

from trainer.export.shortlist import GROUPS_FILE, load_groups, select

GROUPS = {"cap": 4, "groups": [
    {"id": "anchor", "kind": "anchor", "members": ["A"]},
    {"id": "base", "kind": "baseline", "members": ["B"]},
    {"id": "pair1", "kind": "pair", "members": ["C-ctl", "C-trt"]},
    {"id": "pair2", "kind": "pair", "members": ["D-ctl", "D-trt"]},
    {"id": "late", "kind": "baseline", "members": ["E"]},
]}


def rec(label, net=None, **extra):
    return {"label": label, "network_content_sha256": net or f"net-{label}", **extra}


def full():
    return [rec(x) for x in ["A", "B", "C-ctl", "C-trt", "D-ctl", "D-trt", "E"]]


def test_the_result_does_not_depend_on_the_order_of_the_inventory():
    records = full()
    baseline = select(records, GROUPS)
    for seed in range(5):
        shuffled = records[:]
        random.Random(seed).shuffle(shuffled)
        assert select(shuffled, GROUPS) == baseline


def test_a_pair_is_kept_whole_or_dropped_whole_at_the_cap():
    result = select(full(), GROUPS)  # cap 4: anchor + base + pair1 = 4, pair2 would make 6
    assert result.labels() == ["A", "B", "C-ctl", "C-trt"]
    assert {"group": "pair2", "reason": "cap", "detail": 4} in result.excluded
    assert {"group": "late", "reason": "cap", "detail": 4} in result.excluded
    assert result.network_count == 4


def test_the_cap_tie_break_ignores_every_measured_quality_field():
    low = [rec(x, held_out_correlation=0.1, mae=50.0) for x in ["A", "B", "C-ctl", "C-trt", "D-ctl", "D-trt", "E"]]
    high = [rec(x, held_out_correlation=0.99, mae=0.1) if x.startswith("D") else rec(x, held_out_correlation=0.0, mae=999.0)
            for x in ["A", "B", "C-ctl", "C-trt", "D-ctl", "D-trt", "E"]]
    assert select(low, GROUPS).labels() == select(high, GROUPS).labels() == ["A", "B", "C-ctl", "C-trt"]


def test_a_group_whose_network_duplicates_an_earlier_one_is_dropped_as_a_duplicate_of_the_earlier_group():
    records = full()
    records[6] = rec("E", net="net-B")  # E is the same network as B
    groups = copy.deepcopy(GROUPS)
    groups["cap"] = 10
    result = select(records, groups)
    assert {"group": "late", "reason": "duplicate-of", "detail": "base"} in result.excluded
    assert "E" not in result.labels()


def test_a_pair_with_a_duplicated_member_is_dropped_not_half_kept():
    records = full()
    records[3] = rec("C-trt", net="net-C-ctl")  # both arms identical
    groups = copy.deepcopy(GROUPS)
    groups["cap"] = 10
    result = select(records, groups)
    assert {"group": "pair1", "reason": "duplicate-within-group", "detail": ["C-ctl", "C-trt"]} in result.excluded
    assert not any(l.startswith("C-") for l in result.labels())


def test_a_missing_member_drops_the_whole_group_and_is_reported():
    records = [r for r in full() if r["label"] != "C-trt"]
    result = select(records, GROUPS)
    assert {"group": "pair1", "reason": "missing", "detail": ["C-trt"]} in result.excluded
    assert "C-ctl" not in result.labels()


def test_the_committed_group_file_is_well_formed_and_the_p1_g04_duplicate_is_resolved_by_order():
    groups = load_groups()
    ids = [g["id"] for g in groups["groups"]]
    assert groups["cap"] == 10 and len(ids) == len(set(ids))
    labels = [m for g in groups["groups"] for m in g["members"]]
    assert len(labels) == len(set(labels)), "a checkpoint label may appear in one group only"
    assert all(len(g["members"]) == 2 for g in groups["groups"] if g["kind"] == "pair")
    # M-1 seed 42 is bit-identical to P1-G04, so given equal hashes the later group is the duplicate.
    records = [rec(l) for l in labels]
    for r in records:
        if r["label"] == "M1-s42-final":
            r["network_content_sha256"] = "net-P1-G04"
    result = select(records, groups)
    assert {"group": "baseline-m1-seed42", "reason": "duplicate-of", "detail": "baseline-p1-g04"} in result.excluded
    assert result.network_count == 10
    assert [g["group"] for g in result.selected][:7] == ["anchor-e3", "baseline-p1-g04", "baseline-p3a-001",
                                                         "baseline-p4iv-001", "pair-p5-selected", "pair-e15", "pair-e16-seed42"]
    assert json.loads(GROUPS_FILE.read_text())["groups"][0]["id"] == "anchor-e3"


def test_a_repeated_label_is_an_error_not_an_order_dependent_choice():
    import pytest
    records = full()
    records.append(rec(records[0]["label"], net="f" * 64))
    with pytest.raises(ValueError, match="more than once"):
        select(records)
