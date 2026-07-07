---
name: adr-review
description: Generate an Architecture Decision Record (ADR) from a design decision. Use when the user says "write an ADR", "record this decision", "document why we chose X", or wants a decision captured with alternatives and revisit conditions. Documentation only — never recommends implementation details.
---

# ADR Review

Turn a design decision into an Architecture Decision Record. The input is a decision (made or pending); the output is the record below — nothing else.

**Scope rules:**
- Document the decision; **never recommend implementation details** (no code sketches, class names, file layouts, or how-to steps).
- Rejected alternatives get the same depth as the accepted one — a future reader must be able to see *why* each lost, not just that it did. An ADR whose alternatives section is thinner than its decision section is incomplete.
- If the decision, its alternatives, or its drivers are unclear from the input, ask before writing — don't invent context.
- Record honestly: if evidence is weak (no benchmark, no SPRT, gut call), say so under Supporting Evidence rather than dressing it up.

Number and title it `ADR-NNN: <decision as a short assertive phrase>` (check `docs/adr/` or wherever prior ADRs live for the next number; if none exist, start at 001 and note the convention is new). Write it to a file there and return the path.

## Output format (always, in this order)

```markdown
# ADR-NNN: <title>
Date: <date>  Status: proposed | accepted | superseded by ADR-MMM

## Context
The forces at play: constraints, prior decisions, project phase. Facts, not advocacy.

## Problem
The single question this decision answers, in one or two sentences.

## Alternatives
One subsection per option **including the chosen one**, each with: what it is,
what it does well, what it costs. Equal rigor for rejected options.

## Tradeoffs
The comparison across options — what dimension each wins and loses on.

## Decision
Which alternative was chosen, stated plainly, with the deciding factor(s).

## Consequences
What becomes easier, what becomes harder, what is now committed to —
including the negative consequences of the chosen option.

## Revisit Conditions
Concrete triggers that should reopen this decision (a measurement crossing a
threshold, a dependency changing, a phase goal shifting). "Never" is not an answer.

## Supporting Evidence
Benchmarks, SPRT results, issue links, references — or an honest "none; judgment call".

## Open Questions
What remains unsettled that this ADR deliberately does not decide.
```

Keep each section tight; the record's value is retrievability, not length.
