# Phase 17 closure: PVS experiment

Date: 2026-09-20
Issues: [#229](https://github.com/coeusyk/chess-engine/issues/229), [#230](https://github.com/coeusyk/chess-engine/issues/230)

## Final disposition

Phase 17 is a completed experiment. The repaired PVS candidate passed the
mechanism, throughput, and correctness gates, but it did not meet the frozen
strength criterion. It is not promoted and must not be merged into `develop`.

The experimental lineage remains on `phase/17-pvs-experiment` for audit and
future diagnosis. This closure does not change PVS/search behavior and does
not authorize another SPRT.

## Frozen comparison

- Candidate: `f9b152ca4f45e8e8aa5a48092b03416aba79b230`
- Baseline: `ebe513eabd50e853a4e24a0260c64b41a5a4b224`
- Gate 4 host: RENEGADE, AMD Ryzen 7 7700X
- Gate 4 settings: Threads=1, Hash=16 MB, TC=5+0.05, concurrency=6,
  `-repeat`, Cute Chess 1.5.1
- Opening corpus: `tools/noob_3moves.epd`, SHA-256
  `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`

## Gate results

| Gate | Result | Evidence |
|---|---|---|
| 1. Mechanism | PASS | Main nodes `73,089,246 -> 40,878,283` (`-44.07%`). |
| 2. Throughput | PASS | Median fixed-depth elapsed `218,267 ms -> 120,860 ms` (`-44.63%`). |
| 3. Correctness | PASS | Full relevant Maven suite passed; the PV-node propagation defect found during the experiment was repaired and covered. |
| 4. Strength | VALID, H0 accepted | NEW `0 wins / 13 losses / 1 draw` over 14 scored games; 5 additional games were cancelled after the boundary. LLR `-3.10`, bounds `[-2.94,+2.94]`. |

For this frozen SPRT, H0 acceptance means the evidence favored `elo0=0` over
`elo1=+50`. It does not prove a negative Elo, a `-572` Elo result, or any
particular regression magnitude. The candidate therefore failed the project's
promotion requirement of a demonstrated `+50` Elo gain, while the Gate 4 run
itself remains valid.

## Evidence locations

Generated evidence is intentionally not copied into Git. The authoritative
paths recorded by the run are:

- Run evidence directory:
  `tools/results/p17-4/20260920-103500/`
- Environment and identity evidence:
  `05-environment.json`, including `candidate_frozen_commit`,
  `candidate_actual_commit`, `candidate_jar_sha256`,
  `baseline_frozen_commit`, `baseline_actual_commit`, and
  `baseline_jar_sha256`.
- Copied authoritative match artifacts:
  `06a-sprt-authoritative.log` and `06b-sprt-authoritative.pgn`.
- Original authoritative artifacts:
  `tools/results/sprt_phase17-pvs_20260920_160513.log` and
  `tools/results/sprt_phase17-pvs_20260920_160513.pgn`.
- The copied artifacts were independently hash-checked against the originals;
  the JAR hashes were independently checked against the JAR files in the run
  directory. These generated files are not tracked here.

The full preregistration and validity audit remain in
[`phase17-p17-4-strength-preregistration.md`](phase17-p17-4-strength-preregistration.md).

## Commit classification

The table classifies diffs, not commit subjects alone.

| Commit | Classification | Cherry-pick disposition |
|---|---|---|
| `25d3453` | Mixed: PVS production/search behavior in `Searcher`/`SearchResult`, PVS tests, and experiment docs. | Do not cherry-pick. |
| `2949db9` | Phase-17 experiment evidence/docs only. | Archival content only; do not copy generated artifacts. |
| `debe3b1` | Phase-17-specific native throughput runner plus evidence entry. | Do not salvage as generic infrastructure. |
| `5fdd08b` | Phase-17-specific artifact ignore rules. | Do not copy wholesale. |
| `c11b1ca` | Phase-17 throughput evidence/docs only. | Archival content only. |
| `f9b152c` | Mixed: PVS production repair, PVS tests, regression-test updates, and evidence docs. | Do not cherry-pick. |
| `b50d72d` | Phase-17 evidence wording/docs only. | Archival content only. |
| `e6afbb1` | Phase-17 throughput rerun evidence/docs only. | Archival content only. |
| `f1b40a2` | Mixed: reusable nightly-SPRT semantics plus the Phase-17 runner/preregistration. | Do not cherry-pick wholesale; reimplement the generic workflow text. |
| `8797512` | Phase-17-specific Gate 4 concurrency/preregistration and runner changes. | Do not salvage. |
| `a5d5c92` | Mixed: Phase-17 runner integrity changes plus generic nightly Cute Chess pin. | Do not cherry-pick wholesale; reimplement the pin if retained. |
| `b2c82b6` | Phase-17-specific frozen-commit worktree runner changes. | Do not salvage. |
| `46d1e12` | Phase-17-specific runner cleanup for the known untracked directory. | Do not salvage. |
| `9794886` | Mixed: final Phase-17 evidence/docs plus Phase-17 runner post-run recovery changes. | Do not cherry-pick wholesale. |

`25d3453` and `f9b152c` are the production-code boundary. `f1b40a2`,
`a5d5c92`, and `9794886` are also mixed despite containing useful generic or
archival material. No mixed commit is a salvage unit.

## Salvage plan from clean `develop`

Create a new branch from `origin/develop` and manually reimplement only these
items:

### Reusable generic infrastructure

1. In `.github/workflows/nightly-sprt.yml`, preserve precise SPRT wording:
   H1 means evidence favors `elo1=+50`; H0 means evidence favors `elo0=0`
   under the test and does not establish a regression. Keep the workflow's
   policy failure for an H0 result as an unmet `+50` merge bar, not as a
   confirmed regression.
2. Retain Cute Chess `v1.5.1` only in the generic nightly workflow, with the
   existing version verification. This is independent of PVS and should be
   reviewed as a current dependency pin before merge.
3. Add optional exact artifact parameters to `tools/sprt.ps1`:
   `-LogPath <path>` and `-PgnPath <path>`. If both are supplied, write the
   authoritative log and PGN exactly there. If neither is supplied, preserve
   the existing `tools/results/sprt_${Tag}_${TS}.log/.pgn` defaults. Require
   both or neither so a caller cannot create a half-specified contract.
4. Update the generic runner documentation with the new contract. No caller
   should select an authoritative artifact by newest matching filename.

The artifact API is deliberately an interface cleanup, not another Phase 17
hardening cycle. It needs static or unit-level validation only; no match is
required.

### Experiment-specific archival evidence

Preserve this record and the separate diagnostic preregistration on the
salvage branch. Preserve commit SHAs, evidence paths, known hashes, and links
to #229/#230. Do not copy the PVS source, PVS-specific tests,
`tools/p17-4-sprt.ps1`, generated PGNs, generated JARs, or Phase-17-only
worktree orchestration.

The salvage branch must be inspected against clean `develop` before commit:

- no `engine-core/src/main` or `engine-uci/src/main` search behavior changes;
- no PVS implementation or PVS-dependent test files;
- no mixed Phase-17 commit cherry-picked;
- legacy `sprt.ps1` output behavior unchanged when explicit paths are omitted.

## Next phase

The bounded explanatory diagnostic is specified separately in
[`phase18-pvs-diagnostic-preregistration.md`](phase18-pvs-diagnostic-preregistration.md).
It is not a rescue attempt, a new strength gate, or a retroactive change to
the Phase 17 decision.
