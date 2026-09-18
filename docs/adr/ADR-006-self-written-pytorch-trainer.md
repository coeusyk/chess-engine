# ADR-006: Self-written PyTorch trainer vs. pure-Java trainer vs. existing framework

Date: 2026-07-13  Status: accepted

## Context

`docs/NNUE_PRD.md` names this as a Phase D decision from the start (Appendix B:
"ADR-006: Self-written PyTorch trainer vs. pure-Java trainer vs. existing framework
(bullet) — Phase D"). The decision was actually made earlier, during the PRD's own
grilling session (2026-07-07, Appendix A item 6: "self-written PyTorch, engine-agnostic
export; pure-Java trainer rejected (compute infeasible), bullet rejected (black box)"),
and has governed every Phase A–C design choice since (the `.nnue` format, the
evaluator's pure-inference contract) without ever being written as a standalone,
citable record. This ADR closes that gap, ahead of Phase D's first PR (D-1, issue
#192) actually creating the `trainer/` directory this decision produces.

## Problem

What should build and train the NNUE network's weights: a self-written PyTorch
pipeline, a pure-Java trainer (avoiding a second language entirely), or an existing
open-source NNUE training framework (e.g. the tooling behind Stockfish-lineage nets,
commonly "bullet")?

## Alternatives considered

### 1. Self-written PyTorch pipeline (chosen)
A dedicated Python/PyTorch project (`trainer/`, per ADR-010) implementing every stage
— dataset ingestion, feature encoding, model, quantization, export — from scratch,
producing `.nnue` files the Java engine loads via `NnueNetwork.load()` and nothing
else.

### 2. Pure-Java trainer
Keep the entire project single-language: implement training (forward/backward passes,
optimization) directly in Java, inside or alongside `engine-core`/`engine-tuner`.
Rejected: **compute infeasible**. Training a network at the PRD's target scale
(50–100M positions, PRD §"Trainer Requirements") needs a tensor/autograd framework
with GPU support and years of numerical-stability engineering behind it; hand-writing
that in Java is a multi-year undertaking orthogonal to this project's actual goal
(learning NNUE, not reimplementing PyTorch), and the resulting training loop would
almost certainly be slower and less correct than a framework that already exists.

### 3. Existing NNUE training framework (e.g. "bullet")
Adopt an established, purpose-built NNUE trainer already used by other engines.
Rejected: **black box**. The PRD's maintainer persona (§2) states the project's
explicit goal is to understand every layer by building it — "deliverables are judged
on comprehensibility and documentation as much as strength." Adopting an existing
trainer would produce working `.nnue` files faster, but defeats the stated purpose of
this phase: the point is not merely a strong net, it is understanding how a strong net
is produced.

## Tradeoffs

Option 1 accepts a real, non-trivial engineering cost — writing and validating an
entire training pipeline (`DatasetProvider` through `Exporter`, per
`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §2) — in exchange for full
comprehension of every stage and freedom to shape the pipeline around this project's
own conventions (the `.nnue` format, provenance requirements, staged data plan per
ADR-007). Option 2 would have avoided a second language and toolchain entirely, at a
cost this project cannot actually pay (framework-grade tensor/autograd
infrastructure). Option 3 would have been fastest to a working net, at the direct cost
of the project's stated learning goal — a strong net produced by someone else's code
teaches nothing about how NNUE training actually works.

## Decision

Option 1: a self-written PyTorch training pipeline, structured as a separate
subsystem (`trainer/`, per ADR-010) with an engine-agnostic export contract — the
`.nnue` file and nothing else crosses from trainer to engine
(`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §1, §15 Invariant 8). PyTorch itself
(the tensor/autograd/optimizer framework) is adopted as infrastructure — writing an
autograd engine from scratch would repeat option 2's infeasibility for no comprehension
benefit specific to NNUE; the value of "understanding every layer" applies to the NNUE
pipeline's own stages (feature encoding, quantization, export), not to reimplementing
general-purpose tensor calculus PyTorch already solves correctly.

## Consequences

Easier: full visibility and control over every trainer-side decision — dataset
staging, loss calibration (KFinder-scale matching, per PRD §"Trainer Requirements"),
quantization behavior — none of it hidden inside a third-party framework's
conventions. Harder: the project now spans two languages and toolchains, a
maintenance cost the PRD's own risk table (§5) already names as **Low** severity,
mitigated by "hard contract boundary (.nnue format only); trainer fully documented and
seeded." Every trainer-side bug is this project's own to fix — there is no upstream
maintainer to file an issue against.

## Revisit Conditions

- If training compute or maintenance burden ever becomes genuinely prohibitive for a
  single maintainer (PRD §2 persona), revisit whether an existing framework's dataset
  or quantization tooling could be adopted for *specific* stages without abandoning the
  self-written model/training core — a partial, targeted change, not a wholesale
  reversal of this decision.
- Not revisited merely because an existing framework produces stronger nets faster —
  that tradeoff was already accepted here in favor of comprehension (PRD §2 persona).

## Supporting Evidence

`docs/NNUE_PRD.md` Appendix A item 6 (original grilling-session decision); §2 US-4
("As the maintainer, I want a self-written PyTorch training pipeline so that every
stage from data to weights is understood... Java engine has zero dependency on the
training framework; the `.nnue` format is the only contract"); §5 Risks table ("Two-language
project raises maintenance burden" — Low, mitigated). `docs/architecture/NNUE_TRAINER_ARCHITECTURE.md`
§1 restates the zero-dependency boundary as non-negotiable ahead of any trainer code
landing.

## Open Questions

None blocking Phase D's start. The pipeline's internal stage boundaries are
`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md`'s own concern (§2 onward), not this
ADR's — this ADR settles *that* PyTorch is self-written, not the pipeline's internal
shape.
