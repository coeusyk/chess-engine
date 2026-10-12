---
name: architecture-review
description: Design review before implementation — coupling, abstraction, interfaces, module boundaries, extensibility, maintainability, testability, future experimentation. Use when the user says "review this design", "architecture review", or presents a proposal/PRD/plan before coding. Architecture only — never reviews code style or syntax.
---

# Architecture Review

Review the *design*, before implementation. Input is a proposal, PRD, plan, interface sketch, or a description of intended structure — plus existing code only as context for how the design fits.

**Scope rules:**
- Architecture only. Never comment on code style, naming conventions, formatting, or syntax — even if shown code.
- If given only code with no design question, ask what design decision is under review.
- Judge against this repo's constraints (CLAUDE.md): `engine-core` purity (no Spring/HTTP), no allocation in hot paths, module dependency direction (`engine-tuner` → `engine-core` only, etc.).

## Focus areas

Evaluate each that applies:
- **Coupling** — what knows about what; can components change independently; hidden shared state.
- **Abstraction** — is each abstraction earning its keep, or speculative? Wrong-level abstractions that leak.
- **Interfaces** — small surface, deep implementation; do the signatures force allocation or chatty calls in hot paths?
- **Module boundaries** — does responsibility land in the right module; does the dependency direction hold?
- **Extensibility** — the *asked-for* axis of change only; flag speculative flexibility as a weakness, not a strength.
- **Maintainability** — can someone reason about one piece without holding the whole design in their head.
- **Testability** — can each part be verified in isolation (perft, mirror symmetry, bench gates included); are seams where the tests need them?
- **Future experimentation** — for engine work: does the design allow A/B via UCI option + SPRT without restructuring?

## Method

1. Restate the design in 2–3 sentences to confirm understanding; surface any ambiguity as an open question rather than guessing.
2. Identify the one or two decisions the design actually hinges on; spend the review there, not on the periphery.
3. Sketch at least one credible alternative (including "do less / don't build it") and compare honestly.

## Output format (always, in this order)

```markdown
## Verdict
One sentence: approve / approve-with-changes / rethink — and why.

## Strengths
## Weaknesses
## Alternative designs
At least one, with a sentence on when it would be the better choice.
## Tradeoffs
What the recommended design gives up; what the alternatives give up.
## Risk assessment
Likelihood × blast radius of the main failure modes (perf regression, boundary erosion, migration dead-end).
## Recommendations
Concrete, ordered; smallest change that resolves each weakness.
## Open questions
Decisions the author must make that the review can't settle.
```

Keep it short: a verdict the author can act on beats an essay. If the design is fine, say so in one line per section and stop.
