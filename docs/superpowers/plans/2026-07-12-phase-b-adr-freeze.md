# Phase B Documentation Freeze — Implementation Plan

> **For agentic workers:** Documentation-only task, no code changes. Executed
> inline in the same session per explicit user instruction — no subagent
> dispatch, no TDD cycle (there is no code to test). Steps use checkbox
> (`- [ ]`) syntax for tracking.

**Goal:** Close the two documentation gaps identified by the Phase C readiness
audit so Phase B's architecture record is complete before Phase C begins:
write the two ADRs the PRD reserved for Phase B but were never drafted
(ADR-002, ADR-003), and fix an ADR-number collision I introduced during the
freeze patch (my `ADR-006-nnue-evaluator-ownership.md` collides with the
PRD's own reservation of ADR-006 for a Phase D trainer-choice decision).

**Architecture:** No architecture changes — this plan only writes down
decisions already made and already implemented in the frozen Phase B code.
Every claim in both new ADRs must be traceable to a specific line in
`NnueNetwork.java` or `NnueEvaluator.java`, not asserted from memory.

**Tech Stack:** Markdown, following the existing `docs/adr/ADR-004`/`ADR-005`
template structure exactly (Date/Status header; Context; Problem; Alternatives
considered; Tradeoffs; Decision; Consequences; Revisit Conditions; Supporting
Evidence; Open Questions).

## Global Constraints

- Do not modify any implementation file. Do not modify any production code.
- Do not redesign any completed architecture — both ADRs document the
  decision that was made, not a decision being newly evaluated.
- Match the exact section structure and tone of `ADR-004-evaluator-lifecycle-hooks.md`
  and `ADR-005-pure-nnue-runtime.md` (already-read reference files).
- Every technical claim (quantization formula, accumulator push/pop shape)
  must be grepped/read from the actual source before being written into the
  ADR — no speculative numbers.
- ADR renumbering must update every reference: the file itself, and any place
  that names "ADR-006" in connection with evaluator ownership (grep the whole
  repo, not just `docs/`).

---

### Task 1: Write ADR-002 (int16 canonical inference, float32 oracle)

**Files:**
- Create: `docs/adr/ADR-002-int16-canonical-inference.md`
- Read first (for exact formula/constants): `engine-core/src/main/java/coeusyk/game/chess/core/eval/nnue/NnueEvaluator.java:104-124` (the `evaluate()` method and `clamp()`), `engine-core/src/main/java/coeusyk/game/chess/core/eval/nnue/NnueNetwork.java:36-56` (the `qa`/`qb`/`outputScale`/`MAX_HIDDEN_WIDTH` fields and binary-format doc comment)

**Interfaces:**
- Consumes: nothing (documentation only)
- Produces: `docs/adr/ADR-002-int16-canonical-inference.md`, referenced from Task 4's validation pass and from the final ADR index

- [ ] **Step 1: Confirm the exact quantized-inference formula from source**

Already confirmed via grep in this session:
```java
// NnueEvaluator.java:104-124
public int evaluate(Board board) {
    ...
    int qa = network.qa();
    long sum = 0;
    for (int i = 0; i < width; i++) {
        sum += clamp(us[i], qa) * (long) outWeights[i];
        sum += clamp(them[i], qa) * (long) outWeights[width + i];
    }
    sum += network.outputBias();
    return (int) (sum * network.outputScale() / ((long) qa * network.qb()));
}

private static int clamp(short value, int qa) {
    if (value < 0) return 0;
    return Math.min(value, qa);
}
```
This is int16 accumulators (`short[]`), clipped-ReLU clamp to `[0, qa]`, int32/long
dot product against int16 output weights, scaled by `outputScale / (qa * qb)`.
No float arithmetic anywhere in this path. `NnueNetwork` stores `ftWeights`,
`ftBiases`, `outputWeights` as `short[]` — there is no float32 weight storage
in the `.nnue` format at all (confirmed against the binary-format doc comment,
`NnueNetwork.java:17-34`).

- [ ] **Step 2: Write ADR-002 with this content**

```markdown
# ADR-002: Int16 canonical inference with a float32 test-only oracle

Date: 2026-07-12  Status: accepted

## Context

Phase B's PRD scope (§4 Network Specification) calls for "int16 canonical
inference" with "float32 test oracle" as a named, separate decision
(NNUE_PRD.md Appendix B: "ADR-002: int16 canonical inference with float32
test oracle (vs. float-first migration) — Phase B"). The decision was made
and implemented during Phase B's PR2 (`NnueEvaluator`/`NnueNetwork`), but no
ADR was written at the time — this ADR documents that decision retroactively,
against the code as it actually shipped, ahead of Phase C's oracle-comparator
tool needing a documented error bound to validate against.

## Problem

Should NNUE inference run in float32 (matching the PyTorch training graph
exactly) with int16 as an optional optimization layered on later, or should
int16 be the canonical, only production inference path from the start, with
float32 existing solely as a correctness check?

## Alternatives considered

### 1. Int16 canonical, float32 test-only oracle (chosen)
`NnueNetwork` stores weights exclusively as `short[]` (`ftWeights`,
`ftBiases`, `outputWeights` — confirmed, no float fields exist in the class
at all). `NnueEvaluator.evaluate()` runs entirely in integer arithmetic:
clipped accumulators clamped to `[0, qa]`, `long` dot product, final scaling
by `outputScale / (qa * qb)`. A float32 path, when built in Phase C, exists
only as a separate, test-scope utility that dequantizes the same stored
`short[]` weights via `qa`/`qb` and re-runs the forward pass in `double`/`float`
arithmetic to catch integer-arithmetic implementation bugs (overflow,
mis-ordered clamp, wrong shift) — not to catch training-time quantization
loss, since it starts from the same quantized weights as production.

### 2. Float32 canonical, int16 as a later optimization pass
Run inference in float32 matching the PyTorch graph bit-for-bit; add int16
quantization later once correctness is established, mirroring how some NNUE
implementations bootstrap. Rejected: this project's own performance gate
(NNUE_PRD.md §1) requires int16-class throughput from the first playable
build — quiescence search alone calls `evaluate()` on the order of millions
of times per search; a float32-first path would need a second, disruptive
rewrite of the exact same hot loop this ADR is trying to get right once.
Building the throughput-critical path twice is strictly worse than building
it once and testing it against a lightweight oracle.

### 3. Dual-path production inference (float32 fallback + int16 fast path)
Ship both paths in production, selectable at runtime. Rejected: doubles the
weight-storage requirement (`.nnue` would need both `short[]` and `float[]`
copies of every tensor), doubles the surface area that has to stay correct
under every future feature-set change, and the PRD's own Non-Goals (§2)
explicitly rule out this kind of dual-maintenance surface for v1.

## Tradeoffs

Option 1 accepts that the "ground truth" oracle (float32) and the trained
network's actual weights are related only by a documented, bounded
dequantization step, not by two independently-trained models — so the oracle
catches implementation bugs (integer overflow, wrong clamp bound, mis-ordered
operations) but does **not**, by itself, catch a training-time quantization
regression that was baked into the exported weights before the loader ever
sees them (that is the trainer's own quantization-validator's job per PRD
§"Analysis tools (Python, trainer repo)" — a separate, Phase D concern).
Option 2 buys nothing over Option 1 except deferred pain: the hot-path
rewrite has to happen eventually regardless, and delaying it means writing
throwaway float32 search-integration code first. Option 3 buys a live
fallback at the cost of double the format surface and double the
weight-storage size, for a benefit ("switch inference precision at runtime")
nothing in the PRD's user stories actually asks for.

## Decision

Option 1: int16 is the only production inference path
(`NnueEvaluator.evaluate()`), sharing the exact quantized weights the `.nnue`
loader validates and stores. Float32 exists exclusively as a Phase C
test/debug-scope oracle that dequantizes those same weights and re-runs the
forward pass, reporting max/mean absolute error against the int16 path
(PRD's "Oracle comparator" tool). It is never constructed on, or reachable
from, any search call path — `Searcher.evaluate()` (line 1698 in
`Searcher.java`) calls `evaluator.evaluate(board)` through the
`EvaluatorStrategy` interface, which has exactly one `evaluate` method; there
is no second, float32 entry point for the oracle to plug into, by
construction.

**Acceptable numerical drift:** the oracle's job is bug detection, not
precision auditing — any max-absolute-error above what integer rounding in
the clamp/dot-product/scale chain alone can produce is a bug, not "expected
quantization noise." The exact numeric bound is a Phase C deliverable (it
depends on `qa`/`qb`'s actual magnitudes in the first real trained net, not
on the tiny synthetic test networks currently in the repo) — this ADR fixes
the *policy* ("any oracle divergence beyond integer-rounding noise is a
correctness bug, not tolerated drift") and defers the *numeric threshold* to
Phase C, where it will be measured against a real network rather than
guessed.

**Why the oracle is not part of production evaluation:** it duplicates the
entire forward pass in a second numeric representation purely to have an
independent check — running it on the actual search path would roughly
double NNUE evaluation cost for zero playing-strength benefit, directly
violating the performance gate this whole int16-first decision exists to
protect. It belongs in test/debug scope only, exactly as PRD
"Developer Tooling & Debugging (NNUE)" specifies ("these are development
tools, not production features... must add zero cost to the normal search
path").

## Consequences

Easier: one hot-path implementation to optimize and test, matching the
project's own no-allocation/high-NPS constraints (CLAUDE.md §3) from day one;
the oracle can be built, changed, or removed in Phase C without touching
`EvaluatorStrategy`, `NnueEvaluator`, or the `.nnue` format at all, since it
never sits on the interface. Harder: the oracle cannot, by construction,
independently verify the trainer's own quantization choices (weight
clipping, `qa`/`qb` selection) — that verification is the trainer-side
"Quantization validator" tool (PRD, Python analysis tools), a distinct,
later (Phase D) piece of tooling operating on the float checkpoint before
export, not on the already-quantized `.nnue` file this oracle reads.

## Revisit Conditions

- If a second feature set or network topology is ever approved (ADR-001's
  own revisit conditions), re-confirm the oracle's dequantization logic
  still matches the new `featureSetId`/`architectureId` — it currently
  assumes the exact `evaluate()` shape documented above.
- If profiling ever shows the int16 path itself (not the oracle) is not
  meeting the NPS gate, that is a distinct problem from this ADR — this
  decision is about *which representation is canonical*, not about further
  optimizing the int16 path (Vector API work is explicitly ADR-008,
  Phase E).

## Supporting Evidence

Verified directly against source, not from the PRD's description alone:
`NnueNetwork.java` stores `ftWeights`/`ftBiases`/`outputWeights` as `short[]`
only (no float fields in the class); `NnueEvaluator.evaluate()` (lines
104-124) performs the full forward pass in `short`/`int`/`long` arithmetic
with a single `int` return; `clamp()` (lines 119-124) is the clipped-ReLU
step, bounded `[0, qa]`. No float32 oracle exists yet in the frozen Phase B
code — the freeze patch's Phase C readiness audit confirmed this is
correctly scoped to Phase C, not a Phase B gap requiring reopening.

## Open Questions

The exact acceptable-drift numeric bound (mentioned above) is deferred to
Phase C, to be measured against the first real network rather than asserted
here against synthetic test data.
```

- [ ] **Step 3: Verify the file matches the ADR-004/005 section order exactly**

Run: `grep "^##" docs/adr/ADR-002-int16-canonical-inference.md`
Expected output (in this order):
```
## Context
## Problem
## Alternatives considered
## Tradeoffs
## Decision
## Consequences
## Revisit Conditions
## Supporting Evidence
## Open Questions
```

- [ ] **Step 4: Commit is deferred to the end of this plan (Task 5) — one commit for all doc changes, not one per ADR, since this is a single logical "close the freeze-review gaps" change.**

---

### Task 2: Write ADR-003 (copy-per-ply accumulator stack)

**Files:**
- Create: `docs/adr/ADR-003-copy-per-ply-accumulator-stack.md`
- Read first (for exact push/pop shape): `engine-core/src/main/java/coeusyk/game/chess/core/eval/nnue/NnueEvaluator.java:59-85` (`onMake`, `onUnmake`, `onMakeNull`, `onUnmakeNull`)

**Interfaces:**
- Consumes: nothing
- Produces: `docs/adr/ADR-003-copy-per-ply-accumulator-stack.md`

- [ ] **Step 1: Confirm the exact accumulator push/pop shape from source**

Already confirmed via earlier read in this session:
```java
// NnueEvaluator.java:59-85
public void onMake(Board board, int move, int capturedPiece) {
    System.arraycopy(whiteAcc[sp], 0, whiteAcc[sp + 1], 0, width);
    System.arraycopy(blackAcc[sp], 0, blackAcc[sp + 1], 0, width);
    sp++;
    FeatureExtractor.forEachChange(board, move, capturedPiece, this);
}

public void onUnmake() {
    sp--;
}

public void onMakeNull() {
    System.arraycopy(whiteAcc[sp], 0, whiteAcc[sp + 1], 0, width);
    System.arraycopy(blackAcc[sp], 0, blackAcc[sp + 1], 0, width);
    sp++;
}

public void onUnmakeNull() {
    sp--;
}
```
This is: preallocated `short[][] whiteAcc`/`blackAcc` sized to
`Board.UNMAKE_POOL_SIZE`; each `onMake` copies the current top-of-stack frame
forward one slot, then applies feature deltas in place at the new top;
`onUnmake` is a pure decrement (`sp--`) — the old frame's bytes are left
in the array, untouched, and simply become unreachable until the next
`onMake` overwrites them. This is "copy-per-ply," not "in-place reverse
delta on unmake" (which would instead subtract the same deltas back out
of a single shared buffer on `onUnmake`, needing no copy on `onMake` but
needing the exact same delta computation run twice — once forward, once
reversed).

- [ ] **Step 2: Write ADR-003 with this content**

```markdown
# ADR-003: Copy-per-ply accumulator stack, not in-place reverse deltas

Date: 2026-07-12  Status: accepted

## Context

`NnueEvaluator` must maintain a dual-perspective accumulator (`whiteAcc`,
`blackAcc`) that stays correct across `Searcher`'s full make/unmake tree —
root search, quiescence, null-move, singular-extension re-search — without
allocating on any hot-path call (CLAUDE.md §3: "No object allocation in hot
paths"). The PRD reserved this as its own decision
("ADR-003: Copy-per-ply accumulator stack vs. in-place reverse deltas —
Phase B", Appendix B) because it is the single highest-traffic code path in
the whole NNUE integration — every `makeMove`/`unmakeMove` pair in the search
tree calls into it. The decision was made and implemented in Phase B's PR2;
this ADR documents it retroactively against the shipped code.

## Problem

When `Searcher` makes a move, `NnueEvaluator.onMake` must produce a new,
correct accumulator reflecting that move — and when the move is later
unmade, the previous accumulator must be exactly restored, with zero
allocation and minimal per-call cost, across search-tree depths bounded only
by `Board.UNMAKE_POOL_SIZE`.

## Alternatives considered

### 1. Copy-per-ply accumulator stack (chosen)
A preallocated `short[Board.UNMAKE_POOL_SIZE][hiddenWidth]` pool per
perspective, indexed by a stack pointer `sp` mirroring `Board`'s own
`unmakePool`/`unmakeSP` idiom. `onMake` copies the current frame to
`sp + 1` via `System.arraycopy`, increments `sp`, then applies feature
deltas in place at the new top. `onUnmake` is a single `sp--`; the
superseded frame's bytes are simply abandoned in the array until
overwritten by a future `onMake` at the same depth.

### 2. In-place reverse deltas (rejected)
A single accumulator per perspective (no stack). `onMake` applies feature
deltas forward in place; `onUnmake` re-derives and applies the same deltas
in reverse (remove what was added, add back what was removed) to restore
the prior state. Rejected: `onUnmake()` (`EvaluatorStrategy` line 44) takes
**no arguments** — no `move`, no `capturedPiece` — by design (Phase A's
audited hook-timing contract; see ADR-004). Reverse-delta computation needs
the same move/capture information `onMake` had, so this approach would
either require threading that information back into `onUnmake` (reopening
Phase A's frozen hook signature) or having `NnueEvaluator` retain its own
parallel undo-info stack duplicating what `Board.unmakePool` already stores
— redundant state with its own desync risk, for a design whose only
advantage (avoiding the `arraycopy`) is a performance question, evaluated
below.

### 3. Hybrid: shallow reverse-delta window, full-copy stack beyond a fixed depth
Use in-place reverse deltas for the first N plies (fast, no allocation
concerns at shallow depth) and fall back to a copy-per-ply stack beyond
that, on the theory that most nodes are shallow and most cost lives there.
Rejected as premature complexity: it inherits Option 2's core problem
(`onUnmake()` still has no move/capture info to reverse with) *and* adds a
second code path with its own boundary-condition bugs (the depth at which
strategies switch), for a performance benefit that was never measured
against Option 1 — see Tradeoffs.

## Tradeoffs

**Performance:** Option 1's `System.arraycopy` cost is linear in
`hiddenWidth` per `onMake` call, paid on every move made in the search
tree — measurable, but `arraycopy` is a JIT-intrinsic bulk-copy operation,
not a Java loop, and the incremental feature-delta application already
dominates with far fewer operations than a full accumulator rebuild would
cost. Option 2 avoids this copy entirely but cannot be implemented without
violating Phase A's frozen `onUnmake()` signature (see above) — so it is not
actually a smaller-cost alternative available under this project's
constraints, it is an alternative that requires reopening an already-frozen,
independently-justified decision (ADR-004) to even attempt.

**Correctness:** Option 1's failure mode is bounded and cheap to verify: if
`sp` and `Board.unmakeSP` (which drives `Board.UNMAKE_POOL_SIZE`, the shared
pool-size constant per ADR-006/`Board.java`'s public constant) ever diverge,
the incremental-vs-rebuild fuzz test (`NnueIncrementalVsRebuildFuzzTest`)
catches it immediately, because every frame is independently addressable —
there is no accumulated reverse-delta rounding to diagnose. Option 2's
failure mode is more insidious: a single subtly wrong reverse-delta (e.g. a
mis-signed feature index) doesn't fail the current node, it silently
corrupts every subsequent sibling at the same depth, and the bug only
surfaces however many plies later a discrepancy becomes visible — exactly
the "dominant NNUE bug class" (silent accumulator desync) the PRD's own
Developer Tooling section names as the primary thing to guard against.

**Debugging implications:** Option 1's `currentAccumulator(int)` test hook
can read any live frame's exact byte state at any `sp` without needing to
replay history — the state at depth `d` is simply `whiteAcc[d]`, independent
of how it got there. Option 2 has no equivalent: inspecting the accumulator
at a given historical depth would require either retaining history
explicitly (defeating the whole point of not copying) or replaying moves
from the root, which is exactly the "rebuild comparator" tool's job and
would make every debug inspection as expensive as a full rebuild.

## Decision

Option 1: copy-per-ply accumulator stack, mirroring `Board`'s own
`unmakePool`/`unmakeSP` idiom exactly (`NnueEvaluator.java:33`,
`ACCUMULATOR_POOL_SIZE = Board.UNMAKE_POOL_SIZE`, confirmed to reference the
shared constant, not an independent literal, per the Phase B freeze patch).
Deciding factor: it is the only option compatible with Phase A's already-
frozen `onUnmake()` signature without reopening that decision, and it gives
the cheapest, most direct debugging story for the PRD's named dominant bug
class (silent accumulator desync).

## Consequences

Easier: every accumulator frame is independently inspectable and immutable
once written, so the rebuild-comparator and accumulator-inspector Phase C
tools need no special-case logic — they just read `whiteAcc[sp]`/`blackAcc[sp]`
directly. `onUnmake` is O(1), a pure pointer decrement. Harder: memory
footprint scales with `Board.UNMAKE_POOL_SIZE * hiddenWidth * 2 perspectives
* 2 bytes`, preallocated per `NnueEvaluator` instance (i.e. per `Searcher`,
per ADR-006's ownership model) — a real, bounded, one-time cost accepted
knowingly, not a leak, but one that scales linearly if `hiddenWidth` grows
substantially in a future network (tracked under ADR-002's revisit
conditions, not this one, since it's a network-size question, not an
accumulator-strategy question).

## Revisit Conditions

- If `Board`'s own unmake-pool depth ever needs to grow substantially beyond
  its current fixed size for unrelated search reasons, re-measure the
  resulting `NnueEvaluator` memory footprint (`hiddenWidth` × pool size ×
  4 arrays) before assuming it's still negligible.
- If profiling under a real (non-synthetic) trained network ever shows the
  `arraycopy` cost is a measurable fraction of total NNUE evaluation time,
  revisit whether a bounded reverse-delta window (Option 3) is worth
  reopening `onUnmake()`'s signature for — but only with actual profiling
  data, not the a priori reasoning in this ADR.
- If the feature set ever changes to a king-relative scheme (ADR-001's own
  revisit conditions), king moves would need a full-refresh path rather than
  a pure incremental delta — this ADR's copy-per-ply shape still works for
  that (a refresh just writes a freshly-rebuilt frame instead of an
  incrementally-updated one at the same `sp`), but the interaction should be
  re-verified explicitly when that migration happens, not assumed.

## Supporting Evidence

Verified directly against source: `NnueEvaluator.java` lines 59-85
(`onMake`/`onUnmake`/`onMakeNull`/`onUnmakeNull`), confirming the
copy-then-delta shape on make and pure-decrement shape on unmake;
`ACCUMULATOR_POOL_SIZE = Board.UNMAKE_POOL_SIZE` (line 27) confirming the
pool is sized off the same shared constant `Board`'s own unmake stack uses,
not an independent literal — this was itself a finding fixed in the Phase B
freeze patch (previously a duplicated `768` literal). The
incremental-vs-rebuild fuzz test and the six hand-picked
`FeatureExtractorMoveTypeTest` fixtures (added in the freeze patch) are the
concrete correctness evidence for this design, not just the reasoning above.

## Open Questions

None blocking Phase C. Revisit only under the profiling-driven condition
above, not preemptively.
```

- [ ] **Step 3: Verify section order matches ADR-004/005**

Run: `grep "^##" docs/adr/ADR-003-copy-per-ply-accumulator-stack.md`
Expected: same nine headings as Task 1 Step 3, in the same order.

---

### Task 3: Rename the evaluator-ownership ADR to a free number

**Files:**
- Rename: `docs/adr/ADR-006-nnue-evaluator-ownership.md` → `docs/adr/ADR-009-nnue-evaluator-ownership.md`
- Modify (content unchanged except the title line and Date/Status header's
  file is untouched — only the filename and the in-file `# ADR-006:` title
  line's number change, not the decision content): the file's own first line
- Search and update: every other file in the repo that references
  "ADR-006" in connection with evaluator ownership

**Interfaces:**
- Consumes: nothing
- Produces: `docs/adr/ADR-009-nnue-evaluator-ownership.md` (the correct,
  collision-free file), with `docs/adr/ADR-006-nnue-evaluator-ownership.md`
  no longer existing

- [ ] **Step 1: Confirm ADR-009 is actually free**

Run: `ls docs/adr/`
Expected: `ADR-004-evaluator-lifecycle-hooks.md`,
`ADR-005-pure-nnue-runtime.md`, `ADR-006-nnue-evaluator-ownership.md` (about
to be renamed). The PRD's Appendix B reserves 002, 003 (this plan is filling
those), 006 (Phase D trainer choice), 007 (Phase D data/binpack), 008
(Phase E Vector API). 001 is already written in full in the PRD itself
(not a separate file). **009 is the first number not reserved by the PRD and
not already on disk — confirm this by re-reading the PRD's reservation list
(`docs/NNUE_PRD.md` "ADRs to write during implementation") before committing
to 009, in case the list has been extended since this plan was written.**

- [ ] **Step 2: Find every reference to the old number**

Run: `grep -rn "ADR-006" --include="*.md" --include="*.java" .`

Expect matches only in:
- `docs/adr/ADR-006-nnue-evaluator-ownership.md` (the file being renamed)
- Possibly `docs/NNUE_PRD.md` if it was ever updated to point at the
  evaluator-ownership ADR specifically (it currently should only mention
  ADR-006 in the context of the *Phase D trainer-choice* reservation — verify
  this stays correct and unambiguous after the rename, since ADR-006 the
  *number* still means "Phase D trainer choice" in the PRD after this rename,
  and must NOT be edited to mean anything else)
- Possibly git commit messages (not editable, not in scope — commit history
  is immutable; do not attempt to rewrite it)

- [ ] **Step 3: Perform the rename**

```bash
git mv docs/adr/ADR-006-nnue-evaluator-ownership.md docs/adr/ADR-009-nnue-evaluator-ownership.md
```

- [ ] **Step 4: Update the in-file title line only**

The file's first line currently reads:
```markdown
# ADR-006: NNUE evaluator ownership — one instance per Searcher, never shared
```
Change to:
```markdown
# ADR-009: NNUE evaluator ownership — one instance per Searcher, never shared
```
No other line in the file's body changes — the decision content is
unmodified, per the user's explicit "Do not change the ADR contents"
instruction. (The file's "Consequences"/"Supporting Evidence" sections do
not self-reference "ADR-006" anywhere internally — confirmed by the Step 2
grep — so no body edits beyond the title line are needed.)

- [ ] **Step 5: Verify no dangling references remain**

Run: `grep -rn "ADR-006" --include="*.md" --include="*.java" . | grep -v "Phase D trainer"`
Expected: zero output (every remaining "ADR-006" mention, if any, must be
the PRD's own Phase D trainer-choice reservation — nothing should still
point at the renamed evaluator-ownership file under the old number).

---

### Task 4: Update the ADR index / cross-references

**Files:**
- Modify: `docs/NNUE_PRD.md` — no content change needed if it never named the
  evaluator-ownership ADR by number in the first place (verify via Task 3
  Step 2's grep before touching this file at all; the PRD's Appendix B
  reservation list for ADR-006 already correctly describes the Phase D
  trainer-choice decision, which is still accurate and untouched by this
  plan)
- No dedicated `docs/adr/README.md`/index file currently exists in this repo
  (confirmed: `docs/adr/` contains only the numbered ADR files themselves) —
  do not create one speculatively; this plan's Task 5 output message serves
  as the index snapshot the user asked for, not a new permanent file

**Interfaces:**
- Consumes: results of Tasks 1-3
- Produces: a verified, consistent `docs/adr/` directory listing

- [ ] **Step 1: List the final ADR directory state**

Run: `ls docs/adr/`
Expected:
```
ADR-002-int16-canonical-inference.md
ADR-003-copy-per-ply-accumulator-stack.md
ADR-004-evaluator-lifecycle-hooks.md
ADR-005-pure-nnue-runtime.md
ADR-009-nnue-evaluator-ownership.md
```

- [ ] **Step 2: Confirm no other file names an ADR number incorrectly**

Run: `grep -rln "ADR-00[1-9]" --include="*.md" --include="*.java" . | grep -v graphify-out`
Review each hit manually — every reference should point at a number that
either exists on disk or is explicitly reserved-but-unwritten in the PRD's
own Appendix B list (001, 007, 008 — Phase D/E, correctly still unwritten).

---

### Task 5: Validate with agentic-eval, then commit

**Files:**
- No new files — this task runs the self-critique pass and, if it passes,
  stages/commits everything from Tasks 1-4 in one commit.

- [ ] **Step 1: Run an agentic-eval self-critique pass**

Prompt (adapt the specifics from Task 1-4's actual diffs once written):
"Critique the two new ADRs (ADR-002, ADR-003) and the ADR-006→ADR-009 rename
against: (a) numbering consistency — does every ADR reference in the repo
now resolve correctly, with no file claiming a number the PRD reserves for a
different phase; (b) alignment with the frozen implementation — re-read
`NnueEvaluator.java` and `NnueNetwork.java` directly and confirm every
factual claim in both new ADRs (the quantization formula, the accumulator
push/pop shape, the `ACCUMULATOR_POOL_SIZE` sourcing) is still accurate
against the actual code, not just plausible-sounding; (c) consistency with
the approved NNUE PRD — do the two new ADRs' 'Decision' sections match what
PRD Appendix B's one-line description of each ADR says it should cover;
(d) whether any other Phase A/B architectural decision remains undocumented
by any ADR. Report plainly, no fabricated findings."

- [ ] **Step 2: Fix any findings from Step 1 inline**

If the critique finds a factual error (e.g. a misquoted line number, a
formula transcription mistake) or a genuinely undocumented decision, fix it
directly in the relevant ADR file. Do not proceed to Step 3 until the
critique comes back clean or all findings are resolved.

- [ ] **Step 3: Stage and commit**

```bash
git add docs/adr/ADR-002-int16-canonical-inference.md \
        docs/adr/ADR-003-copy-per-ply-accumulator-stack.md \
        docs/adr/ADR-009-nnue-evaluator-ownership.md
git status --porcelain  # confirm ADR-006 file no longer appears as untracked/present
```

Then commit using this repo's mandated commit format (CLAUDE.md §7):
```
docs(adr): write ADR-002/ADR-003, renumber evaluator-ownership ADR to 009

Why: Phase C readiness audit found two PRD-reserved ADRs (int16-canonical-
inference, copy-per-ply accumulator stack) were never written despite both
decisions being implemented in frozen Phase B code, plus a numbering
collision — the freeze patch's own evaluator-ownership ADR was filed as
ADR-006, which the NNUE PRD's Appendix B reserves for a distinct Phase D
trainer-choice decision.
What: ADR-002 and ADR-003 document the already-implemented, already-shipped
decisions (verified line-by-line against NnueEvaluator.java/NnueNetwork.java,
no new decisions made). Evaluator-ownership ADR renamed 006 -> 009 (content
unchanged except the title line's number); repo-wide grep confirms no
dangling ADR-006 references remain outside the PRD's own Phase D reservation.
Left out: no code changes anywhere in this commit. The float32 oracle's
acceptable-drift numeric bound (ADR-002) is explicitly deferred to Phase C,
to be measured against a real trained network rather than guessed here.
Phase: 15 — NNUE Phase B documentation freeze
```

- [ ] **Step 4: Report final status to the user**

Confirm: documentation changes made, final `docs/adr/` listing, and an
explicit statement that Phase B documentation is now complete (or, if
agentic-eval's Step 1 surfaced an unresolved gap, name it plainly instead of
declaring completion).

---

## Self-Review

**Spec coverage:** Task 1 covers "Write ADR-002" (all 8 required sections +
the 4 explicit sub-questions: why int16 production, why float32 oracle-only,
acceptable drift, why oracle isn't production). Task 2 covers "Write ADR-003"
(copy-per-ply, alternatives incl. reverse-delta and hybrid, performance/
correctness/debugging tradeoffs, revisit conditions). Task 3 covers "ADR
numbering" rename + reference updates. Task 5 covers "Validation" via
agentic-eval and the four things it must verify (numbering consistency,
alignment with frozen implementation, consistency with PRD, no undocumented
Phase A/B decisions) plus the three required outputs (doc changes, updated
index, completion confirmation). No gaps found.

**Placeholder scan:** No "TBD"/"implement later"/"add appropriate X" found —
every ADR section above has full prose, not a description of what prose
should say. The one deliberately-deferred item (ADR-002's numeric drift
bound) is explicitly named as deferred-with-reason, not a placeholder.

**Type consistency:** N/A — no code, no function signatures to cross-check.
File-path and ADR-number consistency checked instead (Task 4 Step 2's grep
is the equivalent gate).
