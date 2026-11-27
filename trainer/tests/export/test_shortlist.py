"""The deterministic screening shortlist (#329): a pure function of the inventory and the committed group file."""

import copy
import json
import random

from trainer.export.shortlist import GROUPS_FILE, load_groups, select

GROUPS = {"candidate_cap": 3, "groups": [
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
    result = select(full(), GROUPS)  # candidate cap 3: base + pair1 = 3, pair2 would make 5; the anchor is outside the cap
    assert result.labels() == ["A", "B", "C-ctl", "C-trt"]
    assert {"group": "pair2", "reason": "cap", "detail": 3} in result.excluded
    assert {"group": "late", "reason": "cap", "detail": 3} in result.excluded
    assert result.network_count == 4


def test_the_cap_tie_break_ignores_every_measured_quality_field():
    low = [rec(x, held_out_correlation=0.1, mae=50.0) for x in ["A", "B", "C-ctl", "C-trt", "D-ctl", "D-trt", "E"]]
    high = [rec(x, held_out_correlation=0.99, mae=0.1) if x.startswith("D") else rec(x, held_out_correlation=0.0, mae=999.0)
            for x in ["A", "B", "C-ctl", "C-trt", "D-ctl", "D-trt", "E"]]
    assert select(low, GROUPS).labels() == select(high, GROUPS).labels() == ["A", "B", "C-ctl", "C-trt"]


def test_a_baseline_identical_to_an_earlier_network_is_kept_at_no_cost_with_its_provenance():
    records = full()
    records[6] = rec("E", net="net-B")  # E is the same network as B
    groups = copy.deepcopy(GROUPS)
    groups["candidate_cap"] = 10
    result = select(records, groups)
    late = next(g for g in result.selected if g["group"] == "late")
    assert late["new_networks"] == [] and late["reused"] == {"net-B": "base"}
    assert result.network_count == 6
    shared = next(n for n in result.networks if n["network_content_sha256"] == "net-B")
    assert shared["first_group"] == "base" and shared["labels"] == ["B", "E"]


def test_a_comparison_may_reuse_an_identical_arm_and_adds_only_its_distinct_counterpart():
    records = full()
    records[2] = rec("C-ctl", net="net-B")  # the control of pair1 is the baseline B again
    groups = copy.deepcopy(GROUPS)
    groups["candidate_cap"] = 10
    result = select(records, groups)
    pair = next(g for g in result.selected if g["group"] == "pair1")
    assert pair["members"] == ["C-ctl", "C-trt"], "the comparison keeps both arms"
    assert pair["network_content_sha256"] == ["net-B", "net-C-trt"]
    assert pair["new_networks"] == ["net-C-trt"] and pair["reused"] == {"net-B": "base"}
    assert result.network_count == 6  # A, B, C-trt, D-ctl, D-trt, E


def test_a_shared_arm_costs_one_cap_slot_not_two():
    records = full()
    records[2] = rec("C-ctl", net="net-B")
    # candidate cap 3: B, then pair1 reuses B and adds C-trt (2), pair2 would add two and exceed, E fits (3)
    result = select(records, GROUPS)
    assert [g["group"] for g in result.selected] == ["anchor", "base", "pair1", "late"]
    assert {"group": "pair2", "reason": "cap", "detail": 3} in result.excluded
    assert result.network_count == 4


def test_a_fully_duplicate_comparison_is_kept_at_no_cost_and_does_not_use_the_cap():
    records = full()
    records[4], records[5] = rec("D-ctl", net="net-B"), rec("D-trt", net="net-C-trt")  # pair2 = {B, C-trt}, both selected
    groups = copy.deepcopy(GROUPS)
    groups["candidate_cap"] = 3
    result = select(records, groups)  # A, B, C-ctl, C-trt = 4 networks; pair2 adds none
    pair2 = next(g for g in result.selected if g["group"] == "pair2")
    assert pair2["new_networks"] == [] and pair2["reused"] == {"net-B": "base", "net-C-trt": "pair1"}
    assert pair2["members"] == ["D-ctl", "D-trt"] and pair2["identical_arms"] is False
    assert result.network_count == 4


def test_a_pair_whose_two_arms_are_identical_is_kept_flagged_and_counted_once():
    records = full()
    records[3] = rec("C-trt", net="net-C-ctl")  # both arms identical
    groups = copy.deepcopy(GROUPS)
    groups["candidate_cap"] = 10
    result = select(records, groups)
    pair = next(g for g in result.selected if g["group"] == "pair1")
    assert pair["identical_arms"] is True and pair["new_networks"] == ["net-C-ctl"]
    assert result.network_count == 6  # A, B, C, D-ctl, D-trt, E


def test_the_cap_counts_identities_not_checkpoints_and_never_splits_a_comparison():
    records = [rec(x) for x in ["A", "B", "C-ctl", "C-trt", "D-ctl", "D-trt", "E"]]
    groups = copy.deepcopy(GROUPS)
    groups["candidate_cap"] = 4  # B, pair1 = 3 candidates; pair2 needs two more (5), so it is skipped whole; E fits as the 4th
    result = select(records, groups)
    assert [g["group"] for g in result.selected] == ["anchor", "base", "pair1", "late"]
    assert not any(label.startswith("D-") for label in result.labels())
    assert result.network_count == 5


def test_checkpoints_that_are_in_no_group_still_appear_as_labels_of_their_network():
    records = full() + [rec("EXTRA-copy-of-A", net="net-A", checkpoint_sha256="ff" * 32)]
    result = select(records, GROUPS)
    anchor = result.networks[0]
    assert anchor["labels"] == ["A", "EXTRA-copy-of-A"] and anchor["checkpoint_sha256"] == ["ff" * 32]


def test_a_missing_member_drops_the_whole_group_and_is_reported():
    records = [r for r in full() if r["label"] != "C-trt"]
    result = select(records, GROUPS)
    assert {"group": "pair1", "reason": "missing", "detail": ["C-trt"]} in result.excluded
    assert "C-ctl" not in result.labels()


def test_the_committed_group_file_is_well_formed_and_its_priority_is_written_down():
    groups = load_groups()
    ids = [g["id"] for g in groups["groups"]]
    assert groups["candidate_cap"] == 10 and "cap" not in groups and len(ids) == len(set(ids))
    assert [g["kind"] for g in groups["groups"]].count("anchor") == 1, "the budget is 10 candidates plus ONE anchor"
    labels = [m for g in groups["groups"] for m in g["members"]]
    assert len(labels) == len(set(labels)), "a checkpoint label may appear in one group only"
    assert all(len(g["members"]) == 2 for g in groups["groups"] if g["kind"] == "pair")
    assert all(g["tier"] in "ABCD" and g["why"] for g in groups["groups"]) and groups["priority"]
    tiers = [g["tier"] for g in groups["groups"]]
    assert tiers == sorted(tiers), "the file order must follow the tiers"
    assert json.loads(GROUPS_FILE.read_text())["groups"][0]["id"] == "anchor-e3"


def test_committed_order_with_all_distinct_networks_takes_the_tiers_in_order_and_cuts_inside_tier_c():
    groups = load_groups()
    labels = [m for g in groups["groups"] for m in g["members"]]
    result = select([rec(l) for l in labels] + [rec("M1-s42-final", net="net-P1-G04")] , groups)
    assert [g["group"] for g in result.selected] == ["anchor-e3", "baseline-p1-g04", "baseline-p4iv-001", "pair-p5-selected",
                                                     "pair-e16-seed42", "pair-e15", "baseline-m1-seed43", "baseline-m1-seed44"]
    assert result.candidate_count == 10 and result.network_count == 11, "10 candidates plus the anchor, the maximum"
    assert {"group": "baseline-p3a-001", "reason": "cap", "detail": 10} in result.excluded


def test_with_p1_g04_and_m1_seed42_identical_the_networks_record_both_labels_and_count_once():
    groups = load_groups()
    labels = [m for g in groups["groups"] for m in g["members"]]
    records = [rec(l) for l in labels] + [rec("M1-s42-final", net="net-P1-G04")]
    result = select(records, groups)
    p1 = next(n for n in result.networks if n["network_content_sha256"] == "net-P1-G04")
    assert p1["labels"] == ["M1-s42-final", "P1-G04"] and p1["first_group"] == "baseline-p1-g04"


def test_a_repeated_label_is_an_error_not_an_order_dependent_choice():
    import pytest
    records = full()
    records.append(rec(records[0]["label"], net="f" * 64))
    with pytest.raises(ValueError, match="more than once"):
        select(records)


def test_the_anchor_is_outside_the_candidate_cap_and_the_total_never_exceeds_cap_plus_one():
    groups = copy.deepcopy(GROUPS)
    groups["candidate_cap"] = 2
    result = select(full(), groups)  # anchor A (free), B (1), pair1 needs two (3) -> skipped, E (2)
    assert [g["group"] for g in result.selected] == ["anchor", "base", "late"]
    assert result.candidate_count == 2 and result.network_count == 3


def test_a_candidate_arm_identical_to_the_anchor_network_is_reused_at_no_candidate_cost():
    records = full()
    records[2] = rec("C-ctl", net="net-A")  # the control of pair1 is the anchor network
    groups = copy.deepcopy(GROUPS)
    groups["candidate_cap"] = 2
    result = select(records, groups)  # B (1), pair1 reuses A and adds C-trt (2), pair2 would exceed
    pair = next(g for g in result.selected if g["group"] == "pair1")
    assert pair["reused"] == {"net-A": "anchor"} and pair["new_networks"] == ["net-C-trt"]
    assert result.candidate_count == 2 and result.network_count == 3
