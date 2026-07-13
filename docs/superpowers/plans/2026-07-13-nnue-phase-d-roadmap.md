# NNUE Phase D — Training Pipeline Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement each PR task-by-task, one PR at a time. Do not implement more than one PR (D-N) per session without an explicit go-ahead — Phase C's own C-1..C-5 sequencing is the precedent for small, independently-reviewable PRs.

**Goal:** Sequence Phase D ("Training pipeline" per `docs/NNUE_PRD.md` §5 Phased Rollout) into small, independently mergeable PRs that together satisfy Phase D's exit criteria: *"First real net trains end-to-end reproducibly with a complete manifest; held-out correlation reported; eval scale verified against classical on a corpus."*

**Architecture:** Follows `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` (accepted 2026-07-13, post-grilling/architecture-review/agentic-eval) exactly — module boundaries (§2), `CanonicalNetwork` (§5), `trainer/` in-repo location (§16), and the 8 Trainer Architecture Invariants (§15) are binding on every PR below. No PR in this roadmap may violate Invariant 8 (engine/trainer runtime isolation) — none of them touch `engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api` production code.

**Tech Stack:** Python 3, PyTorch, numpy (trainer side — new); no new Java dependencies.

## Global Constraints

- No JNI, no embedded Python, no PyTorch runtime dependency in the Java engine (architecture doc §1, §15 Invariant 8) — every PR below is additive under `trainer/`, never touching engine-core/engine-uci/engine-tuner/chess-engine-api production code.
- Branch: stays on `phase/15-nnue` — CLAUDE.md §6, one branch per phase, no per-PR branches.
- `trainer/` layout follows the architecture doc §16 proposed structure exactly; do not invent a different layout mid-roadmap.
- Every PR that touches both `FeatureExtractor.java`'s fixture corpus and the Python `FeatureEncoder` must update both in the same PR (Invariant 2) — most PRs below touch only one side and don't trigger this.
- `CanonicalNetwork` is consumed only by `Quantizer`/`Exporter`, never a raw `state_dict` (Invariant 3) — binding from D-5 onward.
- CI path-scoping (architecture doc §12): once a trainer CI workflow exists (D-1 skeleton, D-7 full), it must be path-filtered (`paths: ['trainer/**']`) so Java-only PRs never pay Python/PyTorch setup cost.

---

## PR Sequence

| PR | Issue | Title | Depends on | Architecture doc §§ |
|---|---|---|---|---|
| D-1 | [#192](https://github.com/coeusyk/chess-engine/issues/192) | Trainer Scaffolding & ADR Extraction | none (Phase C frozen) | §16, Follow-up work |
| D-2 | [#193](https://github.com/coeusyk/chess-engine/issues/193) | DatasetProvider (Stage 1 text) + Transform + mmap shards | D-1 | §2, §3 |
| D-3 | [#194](https://github.com/coeusyk/chess-engine/issues/194) | FeatureEncoder + Java/Python Parity CI | D-1 | §4 |
| D-4 | [#195](https://github.com/coeusyk/chess-engine/issues/195) | PyTorch Model + Trainer (training loop) | D-1, D-2, D-3 | §2, §6 |
| D-5 | [#196](https://github.com/coeusyk/chess-engine/issues/196) | CanonicalNetwork + Quantizer | D-4 | §5, §7 |
| D-6 | [#197](https://github.com/coeusyk/chess-engine/issues/197) | Exporter + Provenance Manifest + Validator | D-5 | §8, §9, §11 |
| D-7 | [#198](https://github.com/coeusyk/chess-engine/issues/198) | Trainer Reproducibility CI + Path-Scoped Workflow | D-6 | §12 |
| D-8 | [#199](https://github.com/coeusyk/chess-engine/issues/199) | Stockfish Labeling Driver (Stage 2) | D-2 | PRD §2 US-5, §3 |

This ordering follows the architecture doc's own module dependency direction (§2's table order) and PRD §5's Phase D exit criteria — D-1 through D-7 are the minimum path to "a real net trains end-to-end reproducibly with a manifest"; D-8 (Stockfish labeling) is independent of D-4 through D-7 and could be reordered earlier if useful, but is listed last because Stage 1 (D-2's text dataset) is sufficient to prove the D-4..D-7 pipeline works before investing in a second data source.

**Explicitly out of scope for Phase D** (per PRD §5 Phased Rollout — these are Phase E): self-play data generation (Stage 3), gauntlets, SPRT runs, Vector API optimization.

---

## D-1: Trainer Scaffolding & ADR Extraction (full detail — this is the next PR to implement)

**Files:**
- Create: `trainer/README.md`, `trainer/pyproject.toml`, `trainer/trainer/__init__.py`, `trainer/trainer/dataset/__init__.py`, `trainer/trainer/encoding/__init__.py`, `trainer/trainer/model/__init__.py`, `trainer/trainer/export/__init__.py`, `trainer/trainer/quantization/__init__.py`, `trainer/trainer/validation/__init__.py`, `trainer/trainer/cli/__init__.py`, `trainer/tests/__init__.py`, `trainer/configs/.gitkeep`, `trainer/scripts/.gitkeep`
- Create: `trainer/.gitignore` (must ignore `outputs/` per architecture doc §16)
- Create: `docs/adr/ADR-006-self-written-pytorch-trainer.md`
- Create: `docs/adr/ADR-007-staged-training-data.md`
- Create: `docs/adr/ADR-010-trainer-repository-location.md` (the §16 micro-ADR; numbered 010 since 006-009 are already assigned/used per `docs/NNUE_PRD.md` Appendix B and the existing `docs/adr/` directory)
- Modify: `docs/architecture/graph-audits/generate_report.py` — none (already extended, C-5-adjacent commit, done)

**Interfaces:**
- Consumes: `docs/NNUE_PRD.md` Appendix A items 5–6 (source content for ADR-006/007), `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §16 (source content for ADR-010), the existing `docs/adr/ADR-002..009` files as the formatting template (Context/Problem/Alternatives/Tradeoffs/Decision/Consequences/Revisit Conditions).
- Produces: an importable (but empty) `trainer` Python package that later PRs fill in; three new committed ADRs that later PRs and reviews can cite by number instead of pointing back to PRD Appendix A prose.

- [ ] **Step 1: Create the `trainer/` directory skeleton**

```bash
mkdir -p trainer/trainer/{dataset,encoding,model,export,quantization,validation,cli}
mkdir -p trainer/tests trainer/configs trainer/scripts trainer/outputs
touch trainer/trainer/__init__.py trainer/trainer/dataset/__init__.py \
      trainer/trainer/encoding/__init__.py trainer/trainer/model/__init__.py \
      trainer/trainer/export/__init__.py trainer/trainer/quantization/__init__.py \
      trainer/trainer/validation/__init__.py trainer/trainer/cli/__init__.py \
      trainer/tests/__init__.py trainer/configs/.gitkeep trainer/scripts/.gitkeep
```

- [ ] **Step 2: Write `trainer/.gitignore`**

```
outputs/
__pycache__/
*.pyc
.venv/
```

- [ ] **Step 3: Write `trainer/pyproject.toml`**

```toml
[project]
name = "vex-trainer"
version = "0.1.0"
description = "Self-written PyTorch training pipeline for Vex's NNUE evaluator (docs/NNUE_PRD.md, docs/architecture/NNUE_TRAINER_ARCHITECTURE.md)"
requires-python = ">=3.11"
dependencies = [
    "torch>=2.0",
    "numpy>=1.24",
]

[project.optional-dependencies]
dev = ["pytest>=7.0"]

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"
```

- [ ] **Step 4: Write `trainer/README.md`**

```markdown
# Vex NNUE Trainer

Self-written PyTorch training pipeline producing real trained `.nnue` weight files
for the Vex chess engine's NNUE evaluator. See:

- `docs/NNUE_PRD.md` §3 — original requirements
- `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` — full architecture reference
  (module boundaries, Canonical Network IR, provenance, invariants)
- `docs/adr/ADR-006-self-written-pytorch-trainer.md`,
  `docs/adr/ADR-007-staged-training-data.md`,
  `docs/adr/ADR-010-trainer-repository-location.md`

The Java engine has zero dependency on this package. The `.nnue` file is the only
contract between the two (architecture doc §1, §15 Invariant 8).

## Status

Scaffolding only (PR D-1). No functional pipeline yet — see the architecture doc's
§2 module table and this repo's Phase D roadmap
(`docs/superpowers/plans/2026-07-13-nnue-phase-d-roadmap.md`) for what's next.
```

- [ ] **Step 5: Write `docs/adr/ADR-006-self-written-pytorch-trainer.md`**

Follow the exact section structure of `docs/adr/ADR-005-pure-nnue-runtime.md` (Context/
Problem/Alternatives/Tradeoffs/Decision/Consequences/Revisit Conditions/Supporting
Evidence/Open Questions). Content source: `docs/NNUE_PRD.md` Appendix A item 6
("self-written PyTorch, engine-agnostic export; pure-Java trainer rejected — compute
infeasible; bullet rejected — black box") and Appendix B's ADR-006 stub line. State
the decision as already made during the PRD's grilling session (2026-07-07) and this
ADR as the retroactive formal record — same pattern ADR-002/003/005 already used for
decisions made before their own ADR was written.

- [ ] **Step 6: Write `docs/adr/ADR-007-staged-training-data.md`**

Same template. Content source: PRD Appendix A item 5 (staged bootstrap: Stage 1
plain-text only, binpack out of scope; Stockfish labeling driver Stage 2; self-play
Stage 3; mixed-data retraining measured, not assumed superior) and PRD §2 US-5.

- [ ] **Step 7: Write `docs/adr/ADR-010-trainer-repository-location.md`**

Same template. Content source: `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §16
verbatim (already contains Context/Decision/Rationale/Rejected-alternative structure
— reformat into the ADR template's exact section headers, do not re-derive the
reasoning).

- [ ] **Step 8: Add a trainer CI skeleton workflow (no jobs yet, just the path filter)**

Create `.github/workflows/trainer-ci.yml`:

```yaml
name: Trainer CI

on:
  push:
    paths:
      - 'trainer/**'
  pull_request:
    paths:
      - 'trainer/**'

jobs:
  placeholder:
    runs-on: ubuntu-latest
    steps:
      - run: echo "Trainer CI skeleton — real jobs land in PR D-7 (trainer reproducibility CI)."
```

This exists now, in D-1, specifically so path-scoping (architecture doc §12) is
correct from the very first trainer commit — not retrofitted in D-7 after several
PRs have already been merged without it.

- [ ] **Step 9: Verify the Java build is unaffected**

```bash
mvn -pl engine-core,engine-uci,engine-tuner -am test
```

Expected: unchanged pass count from before this PR (this PR touches zero files under
`engine-core`/`engine-uci`/`engine-tuner`/`chess-engine-api`).

- [ ] **Step 10: Run graphify and generate a D-1 graph audit**

```bash
/home/coeusyk/.local/share/uv/tools/graphifyy/bin/python \
  docs/architecture/graph-audits/generate_report.py \
  --before <pre-D-1 snapshot> --after <post-D-1 snapshot> \
  --pr-id D-1 --pr-title "Trainer Scaffolding & ADR Extraction" \
  --out docs/architecture/graph-audits/D-1-trainer-scaffolding.md
```

Expected: the new "## Architecture Split" section's Trainer Architecture subsection
shows real node counts for the first time (Python files under `trainer/` are picked
up as `module_of() == "trainer"`, per the already-merged `architecture_group()`
classifier) — confirm this instead of assuming it; graphify's Python indexing
behavior for a brand-new empty-module tree hasn't been observed yet in this repo.

- [ ] **Step 11: Commit**

```bash
git add trainer/ docs/adr/ADR-006-self-written-pytorch-trainer.md \
        docs/adr/ADR-007-staged-training-data.md \
        docs/adr/ADR-010-trainer-repository-location.md \
        .github/workflows/trainer-ci.yml \
        docs/architecture/graph-audits/D-1-trainer-scaffolding.md
git commit -m "$(cat <<'EOF'
feat(trainer): scaffold trainer/ and extract ADR-006/007/010 (D-1)

Why: Phase D needs a place to land real trainer code and three decisions
(self-written PyTorch, staged training data, in-repo location) that
already exist as prose in docs/NNUE_PRD.md Appendix A and
NNUE_TRAINER_ARCHITECTURE.md §16, but not as standalone, citable ADRs.
What: trainer/ directory skeleton per the architecture doc's §16 layout
(empty modules only, no functional code); ADR-006/007/010 extracted
verbatim from their existing source decisions, not re-derived; a
path-filtered trainer-ci.yml skeleton so path-scoping is correct from
the first trainer commit, not retrofitted later.
Left out: no functional DatasetProvider/FeatureEncoder/model/etc — that's
D-2 onward per docs/superpowers/plans/2026-07-13-nnue-phase-d-roadmap.md.
Closes #192
Phase: 15 — nnue
EOF
)"
```

---

## D-2 through D-8 (scoped summaries — full task breakdown deferred to each PR's own planning pass, per this roadmap's own right-sizing: detailing D-8's bite-sized steps today, before D-1 has even landed and possibly changed assumptions, would go stale before it's used)

### D-2: DatasetProvider (Stage 1 text) + Transform + mmap shards

**Objective:** First functional trainer code — a `DatasetProvider` reading a public
plain-text labeled dataset (PRD §2 US-5 Stage 1; candidate sources named in PRD §5 Open
Questions #1, decided at this PR's start, not before), a composable `Transform` stage
(filtering/dedup/phase balancing), and an mmap binary shard writer (PRD §"Trainer
Requirements": "memory-mapped binary shards + numpy batching, specified up front").

**Scope:** `trainer/trainer/dataset/text_provider.py`, `trainer/trainer/dataset/transform.py`, shard format + writer, unit tests per component (architecture doc §11 validation strategy — this PR only needs component-level tests, not end-to-end).

**Explicitly out of scope:** Stockfish labeling (D-8), self-play (Phase E), `FeatureEncoder` (D-3 — this PR's `DatasetProvider` yields raw positions, not encoded features, per architecture doc §2's stage contract).

**Dependencies:** D-1 (needs the `trainer/` skeleton).

**Acceptance criteria:** `DatasetProvider` satisfies the iterator-of-`(position, label, metadata)` contract (architecture doc §3); Invariant 1 (DatasetProvider isolation) holds — no import of `Trainer`/`Quantizer`/`Exporter` internals; component tests pass.

### D-3: FeatureEncoder + Java/Python Parity CI

**Objective:** The Python mirror of `FeatureExtractor.java` (architecture doc §4), pinned against the existing `FeatureIndexParityTest.CORPUS` fixture, plus the CI job that fails on drift.

**Scope:** `trainer/trainer/encoding/feature_encoder.py` implementing the exact formula from architecture doc §4; a parity test consuming the same FEN corpus `FeatureIndexParityTest.java` already commits to (extend that Java file to export/share the corpus if needed, e.g. a small committed JSON fixture both languages read — decide the exact sharing mechanism at this PR's start, architecture doc §4 specifies the *what*, not the *file format*); wire the parity check into `trainer-ci.yml` (created empty in D-1).

**Dependencies:** D-1.

**Acceptance criteria:** Invariant 2 (Java/Python feature parity) has a real, running CI check for the first time, not just a documented intention; `FeatureEncoder` output matches `FeatureExtractor` output exactly on the shared corpus.

### D-4: PyTorch Model + Trainer (training loop)

**Objective:** The `nn.Module` (architecture doc §6: `(768→hiddenWidth)×2→1`, clipped ReLU, shared FT weights) and the `Trainer` stage (optimization loop, KFinder-calibrated loss per PRD §"Trainer Requirements", training-time overflow-safety weight clipping per architecture doc §7).

**Scope:** `trainer/trainer/model/network.py`, `trainer/trainer/model/train.py`, seeded/config-file-driven hyperparameters (architecture doc §10 reproducibility), checkpoint saving.

**Explicitly out of scope:** quantization-aware training (architecture doc §6 rejected alternative — only revisit per §17's stated condition); export (D-6).

**Dependencies:** D-1, D-2 (needs shards to train on), D-3 (needs encoded features).

**Acceptance criteria:** a tiny training run (few steps, fixed seed, per architecture doc §12's eventual CI shape) produces a checkpoint; weight clipping bound is derived from `qa`, documented in the training config, not hardcoded (architecture doc §7).

### D-5: CanonicalNetwork + Quantizer

**Objective:** Implement `CanonicalNetwork` exactly as specified in architecture doc §5 (including the `quantized: bool` field and `None`-until-export provenance fields from the post-architecture-review fix), `checkpoint_to_canonical()`, and `Quantizer` (architecture doc §7: per-tensor `qa`/`qb` scale application, round-to-nearest int16, clipping-boundary report).

**Scope:** `trainer/trainer/export/canonical.py` (the dataclass + converter), `trainer/trainer/quantization/quantizer.py`.

**Dependencies:** D-4 (needs a real checkpoint to convert).

**Acceptance criteria:** Invariant 3 (Canonical Network IR) holds — `Quantizer` never touches `state_dict`; Invariant 5 (quantization determinism) is directly tested — same input quantizes to byte-identical int16 arrays across two runs.

### D-6: Exporter + Provenance Manifest + Validator

**Objective:** `Exporter` (architecture doc §8: writes the frozen `.nnue` byte layout, assigns `network_uuid`/`trainer_commit`/`created_at_epoch_seconds` at this stage per the provenance-timing fix in §5), the sidecar `<uuid>.json` manifest (architecture doc §9), and `Validator` (held-out metrics, label correlation, eval-scale check vs. classical — architecture doc §11, the KFinder-calibrated hard requirement).

**Scope:** `trainer/trainer/export/exporter.py`, manifest schema + writer, `trainer/trainer/validation/validator.py`. Also: the round-trip test from architecture doc §14's failure-mode table (`CanonicalNetwork` → `.nnue` bytes → `NnueNetwork.load()` (Java) → assert equal) — this is the first PR that needs a small Java-side test fixture consuming a trainer-produced file, so confirm this doesn't violate Invariant 8 (it doesn't: a *test* reading a trainer-produced artifact is exactly the sanctioned `.nnue`-file integration boundary, not a production dependency).

**Dependencies:** D-5.

**Acceptance criteria:** issue #191 (`NnueNetwork.load()` not rejecting non-positive qa/qb) is either fixed by this point or the exporter's own export-time validation (assert `qa > 0`, `qb > 0`, architecture doc §8) is in place regardless — do not ship D-6 assuming #191 is fixed; a real exported `.nnue` file loads successfully in `NnueNetwork.load()`; a complete manifest is produced per PRD §4's schema (Invariant 6).

### D-7: Trainer Reproducibility CI + Path-Scoped Workflow

**Objective:** Fill in the `trainer-ci.yml` skeleton from D-1 with the real job: a miniature end-to-end training run (tiny dataset, few steps, fixed seed) that exports a `.nnue` + manifest and asserts bit-identical export across two runs (architecture doc §12, PRD §"Continuous Validation (CI)" item 5).

**Scope:** `.github/workflows/trainer-ci.yml` (fill in the placeholder job from D-1), a tiny fixed-seed dataset fixture for the CI run.

**Dependencies:** D-6 (needs the full pipeline to run end-to-end).

**Acceptance criteria:** Invariant 7 (reproducibility guarantees) has a real CI check for its byte-exact-export half; confirm (per D-1 Step 8's own uncertainty) that the path filter correctly excludes this job from Java-only PRs and vice versa — this is the point where that assumption first gets exercised for real, not just asserted in the architecture doc.

### D-8: Stockfish Labeling Driver (Stage 2)

**Objective:** PRD §2 US-5 Stage 2 — a local Stockfish UCI labeling driver (fixed nodes/depth, versioned engine, position filtering), reusing `PgnExtractor`/`PositionLoader` from `engine-tuner` where practical (PRD §4 Integration Points).

**Scope:** `trainer/scripts/stockfish_label.py` or similar; a labeling-run config format (architecture doc §9's provenance chain requires labeling runs be driven by committed config, output shards carrying the dataset identifier).

**Explicitly out of scope:** self-play (Stage 3, Phase E).

**Dependencies:** D-2 (extends the `DatasetProvider`/shard-format story to a second source).

**Acceptance criteria:** Invariant 1 (DatasetProvider isolation) holds — this is a new `DatasetProvider` implementation, not a modification to D-2's text provider; labeling runs are reproducible per architecture doc §10 (documented config, versioned engine).

---

## Notes for whoever picks up D-2

Before starting D-2, re-read `docs/NNUE_PRD.md` §5 "Open Questions" item 1 (which public dataset — Zurichess quiet set vs. Lichess evaluated positions, or another candidate) — this roadmap deliberately does not pre-decide it; the PRD itself says "decide at Phase D start," and D-1 is scaffolding, not a dataset decision. Resolve it at the start of D-2's own planning pass, not by inheriting an assumption from this roadmap.
