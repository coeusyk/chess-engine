#!/usr/bin/env python3
"""Generate a per-PR architectural change report from two graphify graph.json
snapshots. Reuses graphify's own output (graph.json) and its own dependency
(networkx) rather than re-deriving graph structure or centrality math.

Usage:
    python3 generate_report.py --before BEFORE.json --after AFTER.json \
        --pr-id C-2 --pr-title "Oracle Comparator & Eval Breakdown" \
        --out docs/architecture/graph-audits/C-2-oracle-comparator.md

"before" is normally the dated backup graphify's post-commit hook writes
just before rebuilding (graphify-out/<today>/graph.json); "after" is the
live graphify-out/graph.json once the hook has finished. Both files carry
a `built_at_commit` field, echoed in the report for traceability.

ponytail: no simple_cycles enumeration (can explode combinatorially on a
~2000-node graph) — strongly_connected_components is the cheap, sufficient
existence check for cycles. Community IDs are not stable across separate
graphify runs (Louvain numbering is arbitrary per-run), so "community
changes" are detected via cohort overlap (Jaccard), not raw ID equality.
"""
import argparse
import json
from collections import Counter, defaultdict

import networkx as nx


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def build_graph(data):
    g = nx.DiGraph()
    for n in data["nodes"]:
        g.add_node(n["id"], **n)
    for e in data["links"]:
        if e["source"] in g and e["target"] in g:
            g.add_edge(e["source"], e["target"], relation=e.get("relation", ""))
    return g


def module_of(node):
    src = node.get("source_file") or ""
    return src.split("/", 1)[0] if src else None


def label_of(g, node_id):
    return g.nodes[node_id].get("label", node_id) if node_id in g else node_id


def top_by_abs_delta(before_map, after_map, common_ids, n=10):
    deltas = [(nid, after_map.get(nid, 0) - before_map.get(nid, 0)) for nid in common_ids]
    deltas.sort(key=lambda t: abs(t[1]), reverse=True)
    return [d for d in deltas if d[1] != 0][:n]


def cycle_groups(g):
    return [c for c in nx.strongly_connected_components(g) if len(c) > 1]


def cross_module_pairs(g):
    pairs = Counter()
    for u, v in g.edges():
        mu, mv = module_of(g.nodes[u]), module_of(g.nodes[v])
        if mu and mv and mu != mv:
            pairs[(mu, mv)] += 1
    return pairs


def community_moves(before_g, after_g, common_ids, jaccard_threshold=0.5):
    before_cohorts = defaultdict(set)
    for nid in before_g.nodes:
        before_cohorts[before_g.nodes[nid].get("community")].add(nid)
    after_cohorts = defaultdict(set)
    for nid in after_g.nodes:
        after_cohorts[after_g.nodes[nid].get("community")].add(nid)

    moved = []
    for nid in common_ids:
        b_cohort = before_cohorts[before_g.nodes[nid].get("community")] & common_ids
        a_cohort = after_cohorts[after_g.nodes[nid].get("community")] & common_ids
        union = b_cohort | a_cohort
        if not union:
            continue
        jaccard = len(b_cohort & a_cohort) / len(union)
        if jaccard < jaccard_threshold:
            moved.append((nid, jaccard))
    moved.sort(key=lambda t: t[1])
    return moved


def modularity(g):
    undirected = g.to_undirected()
    partitions = defaultdict(set)
    for nid in undirected.nodes:
        partitions[undirected.nodes[nid].get("community")].add(nid)
    communities = [c for c in partitions.values() if c]
    if len(communities) < 2:
        return None
    try:
        return nx.algorithms.community.quality.modularity(undirected, communities)
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    ap.add_argument("--pr-id", required=True)
    ap.add_argument("--pr-title", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    before_data, after_data = load(args.before), load(args.after)
    before_g, after_g = build_graph(before_data), build_graph(after_data)

    before_ids, after_ids = set(before_g.nodes), set(after_g.nodes)
    common_ids = before_ids & after_ids
    added_ids = after_ids - before_ids
    removed_ids = before_ids - after_ids

    before_edges = {(u, v) for u, v in before_g.edges()}
    after_edges = {(u, v) for u, v in after_g.edges()}
    added_edges = after_edges - before_edges
    removed_edges = before_edges - after_edges

    before_degree = dict(before_g.degree())
    after_degree = dict(after_g.degree())
    degree_deltas = top_by_abs_delta(before_degree, after_degree, common_ids)

    before_bc = nx.betweenness_centrality(before_g) if before_g.number_of_nodes() > 1 else {}
    after_bc = nx.betweenness_centrality(after_g) if after_g.number_of_nodes() > 1 else {}
    bc_deltas = top_by_abs_delta(before_bc, after_bc, common_ids)

    new_bridge_candidates = sorted(
        ((nid, after_bc.get(nid, 0.0)) for nid in added_ids if after_bc.get(nid, 0.0) > 0.0),
        key=lambda t: t[1], reverse=True,
    )[:10]

    before_cycles = cycle_groups(before_g)
    after_cycles = cycle_groups(after_g)
    before_cycle_sets = [frozenset(c) for c in before_cycles]
    new_cycles = [c for c in after_cycles if frozenset(c) not in before_cycle_sets]

    before_cross = cross_module_pairs(before_g)
    after_cross = cross_module_pairs(after_g)
    cross_added = {k: v for k, v in after_cross.items() if k not in before_cross}
    cross_removed = {k: v for k, v in before_cross.items() if k not in after_cross}
    cross_changed = {
        k: (before_cross[k], after_cross[k])
        for k in before_cross.keys() & after_cross.keys()
        if before_cross[k] != after_cross[k]
    }

    moved = community_moves(before_g, after_g, common_ids)

    GOD_NODE_TOP_N = 15
    before_god = {nid for nid, _ in sorted(before_degree.items(), key=lambda t: t[1], reverse=True)[:GOD_NODE_TOP_N]}
    after_god = {nid for nid, _ in sorted(after_degree.items(), key=lambda t: t[1], reverse=True)[:GOD_NODE_TOP_N]}
    new_god_nodes = after_god - before_god - added_ids  # newly promoted existing nodes
    new_god_nodes_from_new = (after_god & added_ids)  # brand-new nodes straight into the god-node band

    mod_before, mod_after = modularity(before_g), modularity(after_g)
    if mod_before is None or mod_after is None:
        cohesion_verdict = "unmeasurable (fewer than 2 communities in one snapshot)"
    else:
        delta = mod_after - mod_before
        if abs(delta) < 0.01:
            cohesion_verdict = f"maintained (modularity {mod_before:.4f} -> {mod_after:.4f}, delta {delta:+.4f})"
        elif delta > 0:
            cohesion_verdict = f"increased (modularity {mod_before:.4f} -> {mod_after:.4f}, delta {delta:+.4f})"
        else:
            cohesion_verdict = f"decreased (modularity {mod_before:.4f} -> {mod_after:.4f}, delta {delta:+.4f})"

    lines = []
    lines.append(f"# Architecture Graph Audit — {args.pr_id}: {args.pr_title}")
    lines.append("")
    lines.append(f"- Before commit: `{before_data.get('built_at_commit', '?')}`")
    lines.append(f"- After commit: `{after_data.get('built_at_commit', '?')}`")
    lines.append(f"- Before: {len(before_ids)} nodes, {len(before_edges)} edges")
    lines.append(f"- After: {len(after_ids)} nodes, {len(after_edges)} edges")
    lines.append(f"- Node delta: +{len(added_ids)} / -{len(removed_ids)}")
    lines.append(f"- Edge delta: +{len(added_edges)} / -{len(removed_edges)}")
    lines.append("")

    lines.append("## Top 10 nodes by betweenness centrality change")
    if bc_deltas:
        for nid, d in bc_deltas:
            lines.append(f"- `{label_of(after_g, nid)}` ({nid}): {before_bc.get(nid,0):.4f} -> {after_bc.get(nid,0):.4f} ({d:+.4f})")
    else:
        lines.append("- None (no common node's centrality changed)")
    lines.append("")

    lines.append("## Top 10 nodes by degree change")
    if degree_deltas:
        for nid, d in degree_deltas:
            lines.append(f"- `{label_of(after_g, nid)}` ({nid}): {before_degree.get(nid,0)} -> {after_degree.get(nid,0)} ({d:+d})")
    else:
        lines.append("- None (no common node's degree changed)")
    lines.append("")

    lines.append("## Newly introduced architectural bridge-node candidates")
    lines.append("_New nodes ranked by betweenness centrality in the after-graph (>0). Heuristic, not a graphify-defined threshold._")
    if new_bridge_candidates:
        for nid, bc in new_bridge_candidates:
            lines.append(f"- `{label_of(after_g, nid)}` ({nid}): betweenness centrality {bc:.4f}")
    else:
        lines.append("- None")
    lines.append("")

    lines.append("## Newly introduced dependency cycles")
    lines.append("_Detected via strongly-connected-components (existence check, not full cycle enumeration)._")
    if new_cycles:
        for c in new_cycles:
            lines.append(f"- Cycle group ({len(c)} nodes): " + ", ".join(sorted(label_of(after_g, n) for n in c)))
    else:
        lines.append("- None")
    lines.append("")

    lines.append("## Cross-module dependency changes")
    if cross_added:
        lines.append("**New module pairs:**")
        for (mu, mv), c in sorted(cross_added.items()):
            lines.append(f"- {mu} -> {mv}: {c} edge(s)")
    if cross_removed:
        lines.append("**Removed module pairs:**")
        for (mu, mv), c in sorted(cross_removed.items()):
            lines.append(f"- {mu} -> {mv}: {c} edge(s)")
    if cross_changed:
        lines.append("**Changed edge counts:**")
        for (mu, mv), (b, a) in sorted(cross_changed.items()):
            lines.append(f"- {mu} -> {mv}: {b} -> {a} ({a-b:+d})")
    if not (cross_added or cross_removed or cross_changed):
        lines.append("- None")
    lines.append("")

    lines.append("## Community changes")
    lines.append("_Community IDs are not stable across separate graphify runs — flagged via cohort-overlap (Jaccard < 0.5) among nodes present in both snapshots, not raw ID equality._")
    noise_ratio = len(moved) / len(common_ids) if common_ids else 0
    if noise_ratio > 0.10:
        lines.append(
            f"- **{len(moved)} of {len(common_ids)} common nodes ({noise_ratio:.0%}) flagged — "
            "this volume is typical Louvain re-clustering instability under any graph perturbation, "
            "not evidence of real coupling change. Read this section by checking whether the specific "
            "files this PR touched appear below, not by the raw count.**"
        )
    if moved:
        for nid, j in moved[:15]:
            lines.append(f"- `{label_of(after_g, nid)}` ({nid}): cohort overlap {j:.2f}")
        if len(moved) > 15:
            lines.append(f"- ... and {len(moved) - 15} more")
    else:
        lines.append("- None")
    lines.append("")

    lines.append(f"## New God Nodes (top {GOD_NODE_TOP_N} by degree)")
    if new_god_nodes or new_god_nodes_from_new:
        for nid in sorted(new_god_nodes, key=lambda n: after_degree.get(n, 0), reverse=True):
            lines.append(f"- `{label_of(after_g, nid)}` ({nid}): degree {before_degree.get(nid,0)} -> {after_degree.get(nid,0)} (existing node promoted)")
        for nid in sorted(new_god_nodes_from_new, key=lambda n: after_degree.get(n, 0), reverse=True):
            lines.append(f"- `{label_of(after_g, nid)}` ({nid}): degree {after_degree.get(nid,0)} (brand-new node)")
    else:
        lines.append("- None")
    lines.append("")

    lines.append("## Architectural cohesion")
    lines.append(f"- {cohesion_verdict}")
    lines.append("")

    lines.append("## Narrative")
    lines.append("_Filled in by the engineer/agent reviewing this report — the script only computes the quantitative sections above._")
    lines.append("")

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
