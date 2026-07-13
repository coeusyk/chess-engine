# ADR-010: Trainer repository location — `trainer/` in-repo, not a separate repository

Date: 2026-07-13  Status: accepted

## Context

`docs/NNUE_PRD.md` left this explicitly open ("Trainer location: separate repo vs.
`trainer/` top-level directory — TBD at implementation," §2 US-4 and §5 Open Questions
item 4) — unlike ADR-006 and ADR-007, whose substance was already settled in the
original grilling session, this decision was never made until Phase D planning itself,
via a dedicated `/grilling` round on 2026-07-13, recorded in full in
`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §16. This ADR is the standalone,
citable record of that decision, ahead of PR D-1 (issue #192) creating the `trainer/`
directory it produces.

## Problem

Should the trainer live in a separate git repository, or as a `trainer/` top-level
directory inside this repository, alongside `engine-core`/`engine-uci`/`engine-tuner`/
`chess-engine-api`?

## Alternatives considered

### 1. `trainer/` directory in this repository (chosen)
Same repository, same commit history, own Python package (`trainer/trainer/`, `uv`-managed),
isolated from Java build tooling by not being declared in root `pom.xml`'s `<modules>`
list.

### 2. Separate repository
A dedicated `vex-trainer` (or similarly named) repository, independently versioned,
referenced from this repository only by a pinned commit hash in `.nnue` provenance
manifests.

## Tradeoffs

**In favor of option 1 (chosen):**
- The trainer and engine share several versioned contracts — the feature specification,
  the `.nnue` binary format, `architectureId`, `featureSetId`, the quantization format,
  and provenance metadata (`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §18
  Contract Matrix). One repository allows atomic evolution of these contracts: a
  `.nnue` format version bump is one PR touching both the Python exporter and the Java
  loader together, not a coordinated two-repo release.
- One repository preserves end-to-end provenance: a single commit hash covers the
  trainer, exporter, engine, documentation, benchmarks, and CI configuration that
  produced and validated a given network (§9) — this aligns with the project's
  existing architecture-audit workflow (graphify), which already assumes one graph
  covering the whole repository.
- This repo already models mixed-concern module isolation successfully
  (`engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api`/`tools`, per CLAUDE.md
  §1) — `trainer/` extends that same pattern rather than introducing a new one.
- The project has a single maintainer (PRD §2 persona) building this to understand
  every layer personally — the classic justifications for a separate repo
  (independent release cadence, different teams, different access-control boundaries)
  don't apply at this project's current scale.

**In favor of option 2 (rejected):**
- Cleaner dependency isolation — zero PyTorch/CUDA footprint in this repository at all,
  no risk of a Java-only clone pulling in unrelated Python tooling.
- Independent CI/release cadence, unconstrained by the engine's own build.
- Costs: cross-repo commit-pinning for provenance instead of a single hash; two-repo
  coordination for any `.nnue` format change; loses single-graphify-report visibility
  into the trainer (`docs/architecture/graph-audits/generate_report.py`'s Engine/Trainer
  split section only works cleanly against one graph.json covering both trees).

The PRD's own risk table (§5) already notes "two-language project raises maintenance
burden" as a **Low**-severity risk, mitigated by "hard contract boundary (.nnue format
only); trainer fully documented and seeded" — that mitigation holds equally well
whether the two languages share a repository or not, so it does not favor either
option and is not counted as a tiebreaker.

## Decision

Option 1: the trainer lives at `trainer/`, a top-level directory in this repository.

## Consequences

Easier: atomic contract changes, single-commit provenance, one graphify graph. Harder:
CI must be deliberately path-scoped (`.github/workflows/trainer-ci.yml`, `paths:
['trainer/**']`, landed with PR D-1) so Java-only PRs never pay Python/PyTorch setup
cost, and trainer-only PRs never trigger unrelated Java CI — an ongoing discipline this
repository must maintain, not a one-time setup. Being in the same repository is
**not** permission for the engine to import trainer code or vice versa — the sanctioned
integration boundary remains exactly the exported `.nnue` artifact plus the documented
feature specification (`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §15 Invariant
8), unchanged by this decision.

## Revisit Conditions

- Only if the project ever gains additional maintainers with a genuine need for
  independent release cadence or access-control separation — not preemptively, and not
  merely because CI cost grows (that is a path-scoping problem, not a
  repository-topology problem).

## Supporting Evidence

`docs/NNUE_PRD.md` §2 US-4, §5 Open Questions item 4 (the open question this ADR
closes); `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §16 (full grilled rationale,
reproduced here in ADR form) and §18 Contract Matrix (the shared-contract list this
decision's rationale depends on); CLAUDE.md §1 (existing module list this decision
extends).

## Open Questions

None blocking Phase D. Re-confirmed only under the Revisit Conditions above.
