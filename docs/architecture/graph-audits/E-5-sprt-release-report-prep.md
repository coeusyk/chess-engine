# Architecture Graph Audit — E-5: SPRT Release Gates + Release Report (prep)

- Before commit: `918bfc5c270232b4e584e11f9506f72f6cadcf39` (E-4 graph audit)
- After commit: `f388e2fca9b452348be53b9eae8421656337a2e1`
- After: 3461 nodes, 7277 edges (live rebuild, `graphify-out/graph.json`)

## Methodology note

This session's graphify hook rebuilds on every file save, not only on `git commit` —
across this PR's many intermediate edits (a script created then removed, three
regenerations of the release report) the dated backup directory
(`graphify-out/2026-07-15/`) was overwritten multiple times and no longer holds a
clean snapshot at the E-4 parent commit. A full before/after centrality/community
diff (the kind E-1 through E-4 ran) is therefore not reproducible from this session's
backups. What follows instead is verified directly: the Engine/Trainer split totals
below are compared against E-4's own committed audit
(`docs/architecture/graph-audits/E-4-nnue-mode-gauntlet-harness.md`, "Engine: 1585
nodes; Trainer: 494 nodes"), which remains the authoritative last-clean baseline, and
the Architectural Boundary Report is verified by reading the actual diff's import
graph directly rather than by tooled diff — the same fallback discipline this
project already used in `tools/nnue-gauntlet-e4.md`'s "Code-review note on evidence
strength" when a tool had a gap.

## Architecture Split

- **Engine**: 1585 nodes (current) vs. 1585 (E-4 baseline) — **unchanged**. Expected:
  no file in this PR lives under `engine-core`/`engine-uci`/`engine-tuner`/
  `chess-engine-api`.
- **Trainer**: 499 nodes (current) vs. 494 (E-4 baseline) — **+5**. Traced directly to
  `trainer/scripts/generate_release_report.py` (module + 3 functions +
  `ValidationReport`-adjacent identifiers graphify's Python parser picks up); this is
  the only trainer-tree file this PR adds.
- **Everything else** (`tools/sprt.ps1` extension, `tools/nnue-gauntlet-e4.md`,
  `nets/HISTORY.md`, `nets/dfffd3da-...-release-report.md`): `.ps1` is not a language
  graphify indexes (same finding E-4's own audit made for `tools/nnue-gauntlet.ps1`);
  `nets/` is confirmed **not indexed at all** (zero nodes under that prefix in the
  current graph — the manifests/reports there are data, not source graphify scans);
  `tools/nnue-gauntlet-e4.md`'s edit only appends a section to an already-indexed doc
  node, not a new file.

## Newly introduced architectural bridge-node candidates

None — the one new source-parsed file (`generate_release_report.py`) is a standalone
script under `trainer/scripts/`, imported by nothing and importing only
`argparse`/`json`/`re`/`pathlib` (stdlib) plus reading a manifest JSON at runtime, not
importing any `trainer/trainer/` package module.

## Cross-module dependency changes

None.

## Architectural Boundary Report

`production` = `src/main/*.java` outside `DEBUG_ONLY_MAIN_CLASSES`; `debug` =
`NnueOracle.java` (ADR-002).

### production -> debug dependencies
- No change.

### debug -> production dependencies
- No change.

### cross-module dependency changes
- No change (see above).

### Frozen boundary verdict
- **UNCHANGED** — no unapproved production -> debug edges introduced. Verified
  directly: `generate_release_report.py` has zero import relationship to
  `engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api`, and the `sprt.ps1`
  extension (`-NewOptions`/`-OldOptions`) only adds an optional PowerShell parameter
  that forwards to cutechess-cli's own `option.X=Y` mechanism — no change to any Java
  or Python production code path.

## Narrative

This PR's real content is documentation and tooling: a backward-compatible parameter
on an existing PowerShell script, a generated release-report Markdown file, a
cross-network history ledger, and retroactive reproducibility metadata on an existing
doc. The only source-parsed addition is one small, dependency-free Python script. The
Trainer Architecture split's `+5` node delta traces exactly to that script and nothing
else; the Engine split is confirmed unchanged at the same 1585-node count E-4's own
audit recorded. No bridge nodes, no new dependency cycles, no cross-module edges, and
the frozen production/debug boundary is unanimous "no change" — consistent with a PR
whose acceptance criteria are entirely about recording results and reproducibility,
not touching the evaluator, search, or trainer pipeline internals. The SPRT run and
the promotion decision itself remain outstanding (native-Windows-only, CLAUDE.md §5) —
this audit covers only the prep work committed so far.
