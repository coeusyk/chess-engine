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


# Phase D prep (NNUE_TRAINER_ARCHITECTURE.md): split reports into Engine Architecture
# vs Trainer Architecture sections. "trainer" is a forward-declared prefix — the
# directory doesn't exist until Phase D lands, so every report until then reports the
# Trainer Architecture section empty, which is expected, not a bug.
ENGINE_MODULE_PREFIXES = {"engine-core", "engine-uci", "engine-tuner", "chess-engine-api"}
TRAINER_MODULE_PREFIXES = {"trainer"}


def architecture_group(node):
    """'engine' / 'trainer' / None (docs, CI config, graphify's own dev-entries, etc. —
    neither engine nor trainer, excluded from both split sections)."""
    mod = module_of(node)
    if mod in ENGINE_MODULE_PREFIXES:
        return "engine"
    if mod in TRAINER_MODULE_PREFIXES:
        return "trainer"
    return None


def group_summary_lines(title, before_group_ids, after_group_ids):
    """One-paragraph summary for an architecture-group split section: node/edge counts
    scoped to that group only. Detailed per-class/centrality analysis stays whole-graph
    (splitting betweenness centrality etc. per group would be misleading — centrality is
    a whole-graph measure), so this is deliberately just a scoping summary, not a
    duplicate of the sections below."""
    lines = [f"## {title}"]
    if not before_group_ids and not after_group_ids:
        lines.append("- No nodes in this group in either snapshot (expected until this "
                      "subsystem's source tree exists).")
        lines.append("")
        return lines
    added = after_group_ids - before_group_ids
    removed = before_group_ids - after_group_ids
    lines.append(f"- Before: {len(before_group_ids)} nodes; After: {len(after_group_ids)} nodes")
    lines.append(f"- Node delta: +{len(added)} / -{len(removed)}")
    lines.append("")
    return lines


def label_of(g, node_id):
    return g.nodes[node_id].get("label", node_id) if node_id in g else node_id


# Phase C: classes that live under src/main but are debug/oracle-only by design
# (ADR-002: NnueOracle's float32 path is test/debug-only, never part of the live
# int16 eval path). Extend this set as future debug-only src/main classes land
# (e.g. C-3's NnueDebug) — everything else under src/main is "production".
DEBUG_ONLY_MAIN_CLASSES = {"NnueOracle.java"}

# The one production -> debug file pair this boundary is known to allow, guarded
# at the bytecode level (method granularity: explainEval only) by
# OracleArchitecturalBoundaryTest. Keyed by file, not node label, since the same
# file pair can produce edges from several node granularities (class, method).
# Any other production -> debug edge is a regression.
APPROVED_PROD_TO_DEBUG_FILE_PAIRS = {("NnueEvaluator.java", "NnueOracle.java")}

# The five classes this Phase's ADRs (002/003/004/005/009) freeze the shape of —
# coupling drift here is the signal this report exists to catch.
FROZEN_BOUNDARY_CLASSES = ["EvaluatorStrategy", "Searcher", "NnueEvaluator", "FeatureExtractor", "NnueNetwork"]


def classify(node):
    """'production' / 'debug' for any node (file/class/method — graphify emits all three granularities
    sharing one source_file) belonging to a src/main/*.java file, else None (test/doc/concept nodes
    excluded — the boundary this report guards is production code reaching into debug-only code, not
    tests depending on production code, which is normal and not interesting to flag). Classifying by
    node label instead of source_file would miss almost all real coupling: call/reference edges connect
    class- and method-level nodes (e.g. `NnueOracle`, `.explainEval()`), not the file-level node itself."""
    src = node.get("source_file") or ""
    if not src.endswith(".java") or "/src/main/" not in src:
        return None
    filename = src.rsplit("/", 1)[-1]
    return "debug" if filename in DEBUG_ONLY_MAIN_CLASSES else "production"


def file_of(node):
    src = node.get("source_file") or ""
    return src.rsplit("/", 1)[-1] if src else None


def boundary_edges(g):
    """(production, debug) edge sets across every node granularity, as
    (from_file, from_label, to_file, to_label, relation) tuples."""
    prod_to_debug, debug_to_prod = set(), set()
    for u, v, data in g.edges(data=True):
        un, vn = g.nodes[u], g.nodes[v]
        cu, cv = classify(un), classify(vn)
        rel = data.get("relation", "")
        if cu == "production" and cv == "debug":
            prod_to_debug.add((file_of(un), label_of(g, u), file_of(vn), label_of(g, v), rel))
        elif cu == "debug" and cv == "production":
            debug_to_prod.add((file_of(un), label_of(g, u), file_of(vn), label_of(g, v), rel))
    return prod_to_debug, debug_to_prod


def find_class_node(g, class_name):
    """The class-construct node (label == "Searcher"), not the file node (label == "Searcher.java") —
    graphify's call/inherits/implements edges attach to the class node, per class_neighbors's docstring."""
    suffix = f"/{class_name}.java"
    for nid, data in g.nodes(data=True):
        if data.get("label") == class_name and (data.get("source_file") or "").endswith(suffix):
            return nid
    return None


def class_neighbors(g, nid):
    """(neighbor_label, relation, direction) triples for a class-file node's direct file-level edges."""
    if nid is None or nid not in g:
        return set()
    neighbors = set()
    for _, v, data in g.out_edges(nid, data=True):
        neighbors.add((label_of(g, v), data.get("relation", ""), "out"))
    for u, _, data in g.in_edges(nid, data=True):
        neighbors.add((label_of(g, u), data.get("relation", ""), "in"))
    return neighbors


def class_coupling_delta(before_g, after_g, class_name):
    before_neighbors = class_neighbors(before_g, find_class_node(before_g, class_name))
    after_neighbors = class_neighbors(after_g, find_class_node(after_g, class_name))
    return after_neighbors - before_neighbors, before_neighbors - after_neighbors


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

    before_engine_ids = {n for n in before_ids if architecture_group(before_g.nodes[n]) == "engine"}
    after_engine_ids = {n for n in after_ids if architecture_group(after_g.nodes[n]) == "engine"}
    before_trainer_ids = {n for n in before_ids if architecture_group(before_g.nodes[n]) == "trainer"}
    after_trainer_ids = {n for n in after_ids if architecture_group(after_g.nodes[n]) == "trainer"}

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

    lines.append("## Architecture Split")
    lines.append("_Scoping summary only — the detailed sections below (centrality, degree, "
                 "boundary report, etc.) remain whole-graph, since those measures are not "
                 "meaningful computed on a subgraph alone._")
    lines.append("")
    lines.extend(group_summary_lines("Engine Architecture", before_engine_ids, after_engine_ids))
    lines.extend(group_summary_lines("Trainer Architecture", before_trainer_ids, after_trainer_ids))

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

    # --- Architectural Boundary Report -----------------------------------
    before_p2d, before_d2p = boundary_edges(before_g)
    after_p2d, after_d2p = boundary_edges(after_g)
    new_p2d, removed_p2d = after_p2d - before_p2d, before_p2d - after_p2d
    new_d2p, removed_d2p = after_d2p - before_d2p, before_d2p - after_d2p
    unapproved_new_p2d = [e for e in new_p2d if (e[0], e[2]) not in APPROVED_PROD_TO_DEBUG_FILE_PAIRS]

    lines.append("## Architectural Boundary Report")
    lines.append(
        "_`production` = src/main/*.java outside `DEBUG_ONLY_MAIN_CLASSES`; `debug` = "
        f"{', '.join(sorted(DEBUG_ONLY_MAIN_CLASSES))} (ADR-002). Test files are excluded — "
        "test-to-production coupling is normal and not a boundary risk. Edges are reported at whichever "
        "node granularity graphify attaches them to (class/method), grouped by owning file._"
    )
    lines.append("")

    lines.append("### production -> debug dependencies")
    if new_p2d or removed_p2d:
        for uf, ul, vf, vl, rel in sorted(new_p2d):
            flag = "" if (uf, vf) in APPROVED_PROD_TO_DEBUG_FILE_PAIRS else " **UNAPPROVED**"
            lines.append(f"- + `{ul}` ({uf}) -> `{vl}` ({vf}) [{rel}]{flag}")
        for uf, ul, vf, vl, rel in sorted(removed_p2d):
            lines.append(f"- - `{ul}` ({uf}) -> `{vl}` ({vf}) [{rel}]")
    else:
        lines.append("- No change")
    lines.append("")

    lines.append("### debug -> production dependencies")
    if new_d2p or removed_d2p:
        for uf, ul, vf, vl, rel in sorted(new_d2p):
            lines.append(f"- + `{ul}` ({uf}) -> `{vl}` ({vf}) [{rel}]")
        for uf, ul, vf, vl, rel in sorted(removed_d2p):
            lines.append(f"- - `{ul}` ({uf}) -> `{vl}` ({vf}) [{rel}]")
    else:
        lines.append("- No change")
    lines.append("")

    lines.append("### cross-module dependency changes")
    lines.append("_See \"Cross-module dependency changes\" above._")
    lines.append("")

    for class_name in FROZEN_BOUNDARY_CLASSES:
        added, removed = class_coupling_delta(before_g, after_g, class_name)
        lines.append(f"### {class_name} coupling changes")
        if added or removed:
            for label, rel, direction in sorted(added):
                arrow = "->" if direction == "out" else "<-"
                lines.append(f"- + `{class_name}` {arrow} `{label}` ({rel})")
            for label, rel, direction in sorted(removed):
                arrow = "->" if direction == "out" else "<-"
                lines.append(f"- - `{class_name}` {arrow} `{label}` ({rel})")
        else:
            lines.append("- No change")
        lines.append("")

    lines.append("### Frozen boundary verdict")
    if unapproved_new_p2d:
        lines.append(
            f"- **CHANGED — {len(unapproved_new_p2d)} unapproved production -> debug "
            "edge(s) introduced, review required:**"
        )
        for uf, ul, vf, vl, rel in sorted(unapproved_new_p2d):
            lines.append(f"  - `{ul}` ({uf}) -> `{vl}` ({vf}) [{rel}]")
    else:
        lines.append("- UNCHANGED — no unapproved production -> debug edges introduced.")
    lines.append("")

    lines.append("## Narrative")
    lines.append("_Filled in by the engineer/agent reviewing this report — the script only computes the quantitative sections above._")
    lines.append("")

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
