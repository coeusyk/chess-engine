# Phase 4C: candidate reranking + WDL data-availability audit

**Date**: 2026-07-21. **Status**: Complete. **Type: investigation, not a trained-model experiment**
— no production code was modified, no model was trained, no dataset was regenerated. This document
is the roadmap's own "treat completed experiments as Bayesian evidence, don't auto-continue on the
original ordering" checkpoint, per this task's explicit instruction, plus the specific audit its
own conclusion required.

**Decision summary**: (1) The accumulated P4II/P4III evidence supports a mechanistic prediction
that **down-ranks Huber/log-cosh as originally scoped** (§45, "applied atop the incumbent
objective") — the same `σ'(p,K)` chain-rule factor that neutralized both closed Lever-B
interventions applies to any loss reshaping that stays inside the sigmoid/probability space, for
exact mathematical reasons stated below, not merely by analogy. (2) **WDL blend is now the
highest-expected-value remaining candidate**, untouched by that mechanism — but this audit finds
its previously-estimated "Medium-high, four prerequisites" engineering cost (§43) was based on an
untested assumption of data scarcity that turns out to be false: the real source data is **100%
populated**, and the existing training corpus can very likely be backfilled with WDL via a pure
data join, with **zero Stockfish re-execution**. (3) Neither candidate is implemented this turn —
this document updates the roadmap's ranking and cost estimates; implementation is explicitly
deferred pending review, per this task's stop condition.

## 1. Why this audit, not automatic continuation (per this task's instruction)

§45's original decision matrix ranked Huber/log-cosh (priority 3) immediately after the Lever-B
pair (K retrain, hybrid mate) and ahead of WDL blend (priority 4). That ranking was written
*before* any Lever-B experiment ran. Two Lever-B experiments have since closed
(`phase4-p4ii-mate-weight.md`, `phase4-p4iii-mate-target.md`), both **not promotable**, both via
the same underlying mechanism (`measurement-model.md` §10). Per this task's explicit instruction
("treat completed experiments as Bayesian evidence... do not automatically continue because a
candidate is next in the original roadmap"), this section checks whether that mechanism generalizes
to the next-ranked candidate before spending any implementation effort on it.

## 2. The mechanistic reranking argument: Huber/log-cosh atop the incumbent

**The demonstrated mechanism** (`measurement-model.md` §10, derived from P4III's own
`MATE_BASE_CP`/`MATE_FLOOR_CP` saturation-formula work): under `K=2.773456`'s calibrated sigmoid,
`σ'(p,K)` — the derivative governing how much gradient a prediction `p` receives — decays to
near-zero once `p` is more than roughly 1,000–1,200cp from zero. This is a property of the
*prediction-side* transform alone, independent of what the target is or how the outer loss is
shaped.

**§45 scopes the Huber/log-cosh candidate as "applied atop the incumbent objective"** — i.e., in
sigmoid/probability space: `L = Huber(σ(p,K) − σ(t,K))`, not `L = Huber(p − t)` in raw cp-space
(that combination is a structurally different, two-variable candidate — §5 below explains why it
is not substituted in here). Under this scoping, by the chain rule:

```
dL/dp = Huber'(σ(p,K) − σ(t,K)) · σ'(p,K)
```

**`σ'(p,K)` is exactly the same multiplicative factor the incumbent MSE loss already has**
(`dL/dp = 2(σ(p,K)−σ(t,K))·σ'(p,K)`, §41.0). Reshaping the *outer* loss from squared-error to
Huber changes `Huber'(...)` — a bounded quantity — but does nothing to the `σ'(p,K)` factor, which
is what actually vanishes for saturated predictions. **No choice of outer loss shape, applied in
this space, can restore gradient where `σ'(p,K)` is already near zero** — this is not a new
empirical finding, it is the same algebra P4III's own derivation already used, now applied one
step further down the candidate list instead of stopping at the mate-specific case it was
originally computed for.

**A second, independent argument reaches the same conclusion on the bulk of the corpus**: within
Huber's `δ`-radius, `Huber(r) = 0.5r²` — **identical to MSE**. Since correlation's decisive metric
(cp-only, per `measurement-model.md` §7 condition 4) is dominated by the majority population —
near-zero and moderate-magnitude records, exactly where residuals are smallest and most likely to
sit inside any reasonably-chosen `δ` — Huber changes **nothing** for the population the promotion
rule actually requires improvement in. It only differs from MSE on the tail, where `σ'(p,K)` has
already suppressed the gradient regardless.

**Revised expected-gain estimate**: §45's original "Medium-high" (citing §32.6's magnitude-bucketed
error as motivation) is revised down to **Low-medium on the primary metric (cp-only correlation)**
— the same class of expectation §38.1/§44 already correctly assigned to the closed Lever-A
candidates (K, target transform, label normalization), for a structurally identical reason (a
transform of the loss/target that cannot move a *retrained* model's correlation because the
saturation mechanism governing gradient magnitude is untouched by it). This is a downward revision
of *expected value*, not a claim of proof — Huber/log-cosh remains cheap, low-risk, and would still
be informative if run (a confirmed null closes the "loss-shape-in-probability-space" question
definitively, `measurement-model.md`'s "null results are informative" convention, reused from
§26.4/P4I's replication). **Not run this turn, per the stop condition** — this section revises its
priority, it does not execute it.

**Scope note, stated so this isn't over-read**: this argument applies specifically to Huber/log-cosh
*as scoped in §45* (atop the incumbent, sigmoid-space residual). It says nothing about Huber/
log-cosh applied in **raw cp-space** (no sigmoid at all) — that combination would escape the
`σ'(p,K)` factor entirely, because there is no sigmoid in the chain rule. It is not substituted in
as "the real Huber candidate" here because it is a **different, two-independent-variable candidate**
(remove the sigmoid *and* change the loss shape — §41.1 combined with §42), which this project's
one-variable-per-experiment discipline (§26.1, reused throughout P4I–P4III) does not permit folding
into a single Phase 4 experiment without a declared, reasoned grouping. Flagged here as a real,
separate future candidate — not ranked or run this turn.

## 3. Graphify discovery (mandatory, investigation-scoped)

`graphify . --update --code-only` (main checkout, not a fresh worktree — no `--code-only` LLM
extraction available; this is broader than P4I–P4III's own trainer-only scans since it covers the
full repo): 4,177 nodes / 7,907 edges / 530 communities, 81 code files re-extracted (314 unchanged,
2 deleted — pre-existing drift from prior sessions, not caused by this investigation).
`graphify query "WDL wdl PositionLabel dataset generation shard stockfish_label self-play"` traced
the candidate module set — see §5–§6 for what each module's actual, directly-read content
established, not merely what the graph named.

**No production code was modified this turn.** Every finding below comes from direct file reads,
`grep`, one real Stockfish-binary invocation, and read-only Python calls against `read_shard()` —
none of it a code change. A second Graphify pass is therefore not separately reported: `git status`
confirms zero tracked source files changed by this investigation (§9).

## 4. Stage 1 (Lichess cloud-eval) — re-confirmed, no WDL-adjacent signal

Directly re-read `trainer/scripts/acquire_stage1_lichess.py`: the only fields ever extracted from
Lichess's cloud-eval API response are `pv.get("cp")` / `pv.get("mate")` (lines 87–90) — a cloud
engine's point evaluation, structurally incapable of carrying a game-outcome signal. This
re-confirms §40.2's finding directly against the current source rather than citing it uncritically.
**Any WDL blend is Stage-2-only (or a future Stage-3-only) intervention** — unchanged from §40.2.

## 5. Stage 2 (Stockfish-labeled `quiet-labeled.epd`) — the substantive finding

### 5.1 The raw source file is present locally and 100% populated

`data/quiet-labeled.epd` exists on disk (725,000 lines). Direct inspection:

```
grep -c 'c9 ' data/quiet-labeled.epd   ->  725000   (100% of lines)
grep -o 'c9 "[^"]*"' ... | sort | uniq -c
   272601  c9 "1-0"
   253615  c9 "0-1"
   198784  c9 "1/2-1/2"
```

**This resolves §38.4's flagged unknown definitively, in the favorable direction**: `c9` is not
sparsely populated — it is populated on every line, at a roughly even three-way outcome split.

### 5.2 The `_read_fens()` parsing gap is real but empirically harmless to what's already shipped

§40.2 flagged, but did not test, whether `_read_fens()` passing the raw, `c9`-annotated line
verbatim to Stockfish's `position fen` command could corrupt existing labels. Directly verified
this session against the real local Stockfish binary (`/home/coeusyk/.local/bin/stockfish`,
`Stockfish 18` — the same binary/version the actual Stage 2 run used, per its manifest):

```
position fen <clean 4-field FEN>              -> Zobrist key F2CCBC277031E100
position fen <same FEN> c9 "1/2-1/2";          -> Zobrist key F2CCBC277031E100   (identical)
```

Stockfish's UCI parser silently ignores the trailing opcode. Separately, the trainer's own
feature encoder (`trainer/model/batching.py::_side_to_move`, `trainer/encoding/feature_encoder.py
::active_feature_indices`) reads only FEN fields 0–1 (piece placement, side to move) — both
positioned *before* where the `c9` annotation would appear — confirmed by direct code read, not
inferred. (Note: `python-chess`'s own `chess.Board()` constructor *does* reject the dirty string,
`ValueError: invalid half-move clock in fen`, tested directly — but the trainer's pipeline does not
use `chess.Board()` anywhere in this path, so that rejection is moot for what's actually shipped.)

**Conclusion: the already-shipped Stage 2 corpus is not corrupted by this gap.**

### 5.3 The existing production shard was already built from a pre-cleaned input, and can be backfilled with zero re-labeling

Directly inspected `trainer/outputs/datasets/stage2-quiet-sf/shard-0.bin` — the exact shard
`STAGE2_DIR` in every Phase 4 script (`phase4_p4i_k_sweep.py` line 60, reused by P4II/P4III)
points at, i.e. the corpus every closed Phase 4 experiment actually trained and evaluated against.
20,000 records; **zero** contain a `c9` substring in the stored `fen` field — the actual production
run used a different, already-stripped input file, not `data/quiet-labeled.epd` directly (git
history shows `_read_fens()` has never changed since its introducing commit, so the input file,
not the code, must have been pre-cleaned; which exact file was not determined — the manifest does
not record it, and this is not a blocking gap for what follows).

**This raised the obvious follow-up: can the existing shard's WDL be recovered by joining back
against the raw EPD file on FEN, without re-running Stockfish at all?** Tested directly:

```
Unique FENs in data/quiet-labeled.epd:                     724,127  (of 725,000 lines)
FENs with conflicting outcomes across duplicates:              248  (0.03% of unique FENs)
Existing Stage 2 shard records (20,000) matched by exact-FEN join:  20,000 / 20,000  (100%)
```

**Every record in the existing, already-trained-on Stage 2 corpus can be backfilled with a
side-to-move-relative WDL value via a pure text join against a file already present on disk, with
zero Stockfish invocations.** The 248 conflicting-outcome duplicate FENs (0.03%) are a negligible
label-noise source, consistent in magnitude with this roadmap's other small-fraction data-quality
findings (§32.4/Experiment 3A's 75/35,933 = 0.2% sentinel records) — droppable or majority-voted,
not a blocking concern.

## 6. Stage 3 (self-play) — confirmed unimplemented; one false lead investigated and ruled out

`docs/architecture/research/DR-E1-self-play-data-generation.md` states its own status plainly:
"Research only... No code... Phase E implementation" has not begun. Confirmed no
`SelfPlayProvider` or equivalent exists (`grep`, `DatasetProvider` subclasses: only
`TextDatasetProvider` and `StockfishLabeledProvider`).

**A promising-looking false lead, investigated and ruled out rather than silently ignored**
(this project's evidence-discipline convention): `data/wdl-selfplay.epd` exists locally (100,000
lines, full 6-field FENs with `c0 "<result>"` outcome annotations — structurally exactly what
Stage 3 self-play data would look like). Traced its provenance via `data/corpus_gen_run.log` and
`tools/generate_texel_corpus.ps1`: this is output from the **`engine-tuner` Texel-tuning pipeline**
(classical/HCE evaluation weight tuning, Phase 13 era, PGN dated 2026-04-06), a structurally
separate subsystem from the NNUE trainer's `DatasetProvider`/`PositionLabel`/shard architecture —
`generate_texel_corpus.ps1`'s own docstring confirms "the self-play PGN extraction pipeline has
been removed (#140)." **Not usable for RQ-4 without building an entirely new adapter**, and even
then it would be self-play from an old, comparatively weak version of Vex — not representative of
what a modern-strength self-play corpus would look like, the exact data-quality concern DR-E1
itself raises about early-network self-play data (DR-E1 §1 point 4). This file does not change
Stage 3's status from "unimplemented."

## 7. Revised WDL-blend engineering-cost estimate

§43's original table rated this "Medium-high (§40.2's four concrete prerequisites: contract field
exists but unpopulated, shard format extension, EPD parser extension, blend logic)". This audit
narrows that:

| Prerequisite (§43's original list) | Original estimate | This audit's finding |
|---|---|---|
| `PositionLabel.wdl` contract field | None (exists) | Unchanged — zero work |
| Data population / availability | Unknown, feared sparse | **100% populated** (§5.1) — not a blocker at all |
| EPD parser (`_read_fens`) extension | Required before any real WDL data reaches the pipeline | **Not required for the existing corpus** — a one-off join script backfills it without touching the labeling driver (§5.3). Still needed for *future* Stage 2 labeling runs, but that is decoupled from testing RQ-4 on the existing 20,000-record corpus. |
| `SHARD_DTYPE` format extension | Required | **Still required** — confirmed by direct read of `mmap_shard.py`; no shortcut found. Real work: a new field, encode/decode changes, a manifest schema-version consideration (§9.2 of the architecture doc), its own tests. |
| Blend logic (`target_cp()`/`texel_sigmoid()` call sites) | Required | **Still required and not yet designed as a concrete `TrainingConfig` field** — DR-E1 §1 point 3 already specifies the exact formula (`wdl_value = λ·sigmoid(cp/K) + (1−λ)·game_result`), reducing this to an implementation task, not an open design question. |
| Mirror-symmetry/regression suite re-run (CLAUDE.md §4) | Required | Unchanged — still required, training-target contract change. |

**Net revision**: from "Medium-high, four prerequisites, one of them a genuine unknown" to
**Medium — two concrete, bounded engineering tasks (shard-format extension; blend-loss
implementation), zero data-acquisition risk, zero re-labeling cost for testing against the existing
corpus.** This is a real reduction in implementation risk, not just a data-availability footnote.

## 8. Reranking conclusion

| Candidate | §45 original priority | §45 original expected gain | This audit's revision |
|---|---|---|---|
| Huber/log-cosh (atop incumbent) | 3 | Medium-high | **Revised down to Low-medium** on the primary metric — mechanistically predicted to hit the same `σ'(p,K)` wall as both closed Lever-B experiments (§2). Still cheap/low-risk; would still be an informative null if run. |
| WDL blend | 4 | Unknown, precedent-only | **Revised up** — remains the only candidate whose mechanism (target-source substitution) is untouched by the demonstrated saturation wall; its implementation cost is now Medium, not Medium-high, and its data-availability risk (the main uncertainty behind its old "Unknown" rating) is resolved: 100% populated, backfillable without re-labeling (§5, §7). |

**Ranking changes**: WDL blend is now the higher-expected-value remaining candidate. Per this
task's instruction, this is recorded here as the roadmap update; **implementation is not begun
this turn** (stop condition). The next Phase 4 experiment, when undertaken, should be scoped as:
(1) extend `SHARD_DTYPE` to store `wdl` (with tests), (2) write the one-off FEN-join backfill
script for the existing Stage 2 corpus (§5.3), (3) implement DR-E1's own already-specified λ-blend
formula as a new `TrainingConfig` field (mirroring `mate_weight`/`mate_target_distance_aware`'s own
safe-default, opt-in pattern), (4) re-run CLAUDE.md §4's mirror-symmetry/regression suite. This is
larger than a single train.py-only change (P4I–P4III's pattern) and should be scoped as its own
reviewed unit, not assumed to fit the same one-sitting shape.

## 9. What this does and does not establish (scope-capped)

- **Does not** prove Huber/log-cosh would produce a null result if run — it is a mechanistic
  prediction from an exact chain-rule argument extending an already-twice-confirmed mechanism, not
  a third empirical replication. A future review could still choose to run it as a cheap,
  informative-either-way confirmation.
- **Does not** commit to WDL blend as this roadmap's next experiment — that decision is explicitly
  left to review, per this task's stop condition. This document establishes what the ranking and
  cost estimate should be *if* WDL is chosen next, not that it must be.
- **Does** establish, directly and empirically (not by inference from old documentation), that: the
  real Stage 2 source data is fully populated; the already-shipped training corpus is not corrupted
  by the known parser gap; that corpus can be backfilled with WDL without any Stockfish
  re-execution; and Stage 3 self-play data does not secretly already exist for the NNUE trainer
  (the one promising-looking file on disk belongs to an unrelated, older subsystem).
- **Zero production code changed.** `git status` after this investigation shows no modification to
  any file under `trainer/trainer/`, `trainer/scripts/` (beyond this document and the README/
  index update), or `engine-core`/`engine-uci`. Confirmed directly, not assumed.

## 10. Learning log entry

```
## Investigation ID: P4C-audit-001-wdl-reranking
Hypothesis:             The demonstrated sigma'(p,K)-saturation mechanism (P4II+P4III,
                         measurement-model.md SS10) generalizes to down-rank Huber/log-cosh
                         (SS45's "atop the incumbent" scoping) and, by elimination, up-ranks
                         WDL blend as the remaining candidate whose mechanism is untouched by it.
Method:                  Direct chain-rule derivation (not a training run) for the Huber
                         argument; direct file/data inspection, one real Stockfish-binary
                         invocation, and a read-only FEN-join test (not a training run) for the
                         WDL data-availability question.
Observed outcome:        Huber-atop-incumbent's expected gain on the primary metric (cp-only
                         correlation) revised down to Low-medium via two independent chain-rule/
                         delta-radius arguments (SS2). WDL blend's data-availability risk --
                         SS45's original "Unknown, precedent-only" rating's main driver --
                         resolved: source data 100% populated (SS5.1), existing corpus
                         backfillable via FEN-join with zero re-labeling (SS5.3, 20,000/20,000
                         match rate, 0.03% conflict rate), engineering-cost estimate narrowed
                         from Medium-high to Medium (SS7). Stage 3 confirmed still unimplemented;
                         one plausible-looking existing data file (wdl-selfplay.epd) investigated
                         and ruled out as belonging to an unrelated subsystem (SS6).
Decision:                Ranking updated (SS8). No implementation begun this turn, per this
                         task's explicit stop condition -- awaiting review before choosing and
                         scoping the next Phase 4 experiment.
Next action:             Do not begin WDL blend or Huber/log-cosh implementation automatically.
                         If WDL is chosen next, it should be scoped as its own reviewed unit
                         (SS8's four-step outline), not assumed to fit a single train.py-only
                         sitting the way P4I-P4III did.
Artifacts:               This document only -- no trainer/outputs/ artifacts, no code changes.
```
