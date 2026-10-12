# ADR-007: Staged training data (public text → SF labeling → self-play); binpack exclusion

Date: 2026-07-13  Status: accepted

## Context

`docs/NNUE_PRD.md` Appendix B names this a Phase D decision ("ADR-007: Staged training
data (public text → SF labeling → self-play); binpack exclusion — Phase D"). Like
ADR-006, the substance was actually settled during the PRD's grilling session
(2026-07-07, Appendix A item 5) and amended once by adversarial review (binpack
explicitly excluded) before this ADR formally records it, ahead of Phase D's first PR.

## Problem

What data pipeline produces the labeled positions the trainer consumes, and in what
order should data sources be brought online — one big investment in the best possible
data source up front, or a staged plan that validates the pipeline cheaply first?

## Alternatives considered

### 1. Staged bootstrap: public text → Stockfish labeling → self-play (chosen)
Three sequential stages (PRD §2 US-5), each its own `DatasetProvider`
(`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §3):

- **Stage 1:** a public, plain-text labeled dataset (EPD/CSV with evals) — cheap,
  immediately available, validates the pipeline (encoding, training, quantization,
  export) end-to-end before any custom data generation exists.
- **Stage 2:** a local Stockfish UCI labeling driver (fixed nodes/depth, versioned
  engine, position filtering), reusing `PgnExtractor`/`PositionLoader` from
  `engine-tuner` where practical.
- **Stage 3:** self-play generation using the engine's own search, labels blended from
  search score and game outcome (λ configurable) — Phase E, once the pipeline and an
  early net already exist to generate from.

Retraining on mixed datasets is measured (SPRT/strength tests), never assumed superior
without evidence.

### 2. Binpack ingestion (a compact, widely-used NNUE training data format)
Rejected — **failed cost/benefit vs. text datasets** (PRD's own review-amendment
rationale, Appendix A closing paragraph). A binpack parser is nontrivial engineering
for a format this project would only use to reach data other engines already publish
in plain text; building a parser for it doesn't teach anything about NNUE training
itself, unlike the stages actually chosen.

### 3. Skip straight to self-play (no public/labeled bootstrap stage)
Rejected: self-play data quality depends on having *some* working net to generate
from — bootstrapping directly from self-play with no prior net is either impossible
(nothing to search with) or produces low-quality early data from a random or
near-random network, wasting compute before the pipeline itself is even validated.

## Tradeoffs

Staging accepts that Stage 1's data (a public dataset, not the engine's own play) may
produce a net with real, measurable weaknesses relative to what self-play would
eventually teach — accepted deliberately, because Stage 1's job is to validate the
*pipeline* (dataset → features → training → quantization → export → a loadable
`.nnue`) cheaply and quickly, not to produce the strongest possible net on the first
attempt. Skipping straight to the best eventual data source (self-play) would mean
debugging the entire pipeline for the first time against the most expensive, slowest
data source available — the wrong order for a self-written pipeline still being
validated for correctness.

## Decision

Option 1: staged data — Stage 1 (public plain-text dataset) is Phase D's actual
data source; Stage 2 (Stockfish labeling, PR D-8) extends the pipeline to a second,
project-controlled source within Phase D; Stage 3 (self-play) is explicitly deferred
to Phase E (`docs/superpowers/plans/2026-07-13-nnue-phase-d-roadmap.md`, "Explicitly
out of scope for Phase D"). Binpack parsing is out of scope entirely — every
`DatasetProvider` this project ever writes targets a plain-text or project-generated
format, never binpack.

## Consequences

Easier: the pipeline gets validated against the cheapest, most available data first;
each stage is an independent `DatasetProvider` (Invariant 1,
`docs/architecture/NNUE_TRAINER_ARCHITECTURE.md` §15) so adding Stage 2 or Stage 3
never requires touching Stage 1's code. Harder: an early net trained only on Stage 1
data may plateau below what a self-play-trained net eventually reaches — this is a
known, accepted, and explicitly not-yet-a-problem condition (PRD §5 Risks: "First
bootstrap net lands near parity → motivation/goalpost risk," mitigated by pre-committed
SPRT terms and an explicit expectation of iteration, not a first-net-must-win
requirement).

## Revisit Conditions

- If Stage 1 public data proves insufficient even to validate the pipeline (e.g.
  systematic labeling errors, insufficient position diversity), revisit the specific
  dataset choice — not this ADR's staging order, which remains sound regardless of
  which Stage 1 source is used.
- Binpack exclusion is revisited only if a specific, measured need (not general
  convenience) for binpack-formatted external data emerges and the cost/benefit
  calculus that rejected it here has genuinely changed — not preemptively.

## Supporting Evidence

`docs/NNUE_PRD.md` Appendix A item 5 and its review-amendment rationale ("binpack
parser failed cost/benefit vs. text datasets"); §2 US-5 (the three-stage plan,
verbatim); §5 Open Questions item 1 ("Which public text-format dataset for Stage 1... —
decide at Phase D start," left open by this ADR, resolved instead at PR D-2's own
start per `docs/superpowers/plans/2026-07-13-nnue-phase-d-roadmap.md`).

## Open Questions

Which specific public dataset (Zurichess quiet set, Lichess evaluated positions, or
another candidate) is Stage 1's actual source — deliberately left open by both the PRD
and this ADR, to be resolved at PR D-2's start, not asserted here without the
D-2 implementer's own evaluation of what's available.
