# Stage-3 generator selection and promotion gates (E-12 design record)

**Status:** decided policy, no implementation. Downstream of `DR-E9-stage3-game-search-label-contract.md` (game/search/label semantics), `DR-220-vspr-wire-format.md` (the provenance fields this policy keys off), and issue #210 (the dataset-generation manifest and assessment model this policy assigns responsibility to, without redefining). This document answers one question only: *which network/build may generate Stage-3 self-play data, under what checks, and how is that decision distinguished from whether a network ships.* No GameLoop, no generator/CLI implementation, no self-play run, no training, no SPRT execution happens in this document.

Every claim about current project evidence below is verified against `docs/architecture/research/nnue/README.md`, `docs/architecture/research/nnue/measurement-model.md`, and `docs/architecture/research/nnue/phase5-p5-auxhead-rms.md` on `phase/15-nnue`, not against issue prose.

## 1. Decision

Adopt a two-gate model for Stage-3 self-play (§3), with an explicit, bounded generator-eligibility check (§4) that is deliberately lighter than release promotion (§9), a mandatory bounded pilot before any larger generation (§5, §10), a dataset-quality assessment distinct from generator eligibility (§5, §8), and an explicit bootstrap-generator decision (§6) rather than an implicit "use whatever is newest" default, which this document explicitly rejects (§3).

## 2. Context

Issue #212 originally proposed adopting "unconditionally use the latest candidate network for self-play" as settled policy, citing AlphaGo Zero/AlphaZero's gating history and lc0's production split as precedent. That precedent is real but external — evidence that ungated generation works somewhere, not evidence it is safe for this project specifically. Two facts about this project's own state make the unconditional-latest rule actively wrong to adopt now, not merely unproven:

- **Vex has no completed Stage-3 generate-to-retrain cycle yet.** There is no track record here to appeal to.
- **The most recent trained candidates are known not-promotable on their own merits**, not merely "not yet proven." Per `measurement-model.md` §7 (the permanent promotion rule) and `nnue/README.md`'s status table: P4II, P4III, P4IV, P5-WDLALT, P5-AUXHEAD-001, and P5-AUXHEAD-RMS-001 all failed promotion — several (P5-AUXHEAD-001 in particular) with actively regressed RMSE and calibration, not just a flat correlation. "Newest" and "best" have been different networks throughout Phase 4 and Phase 5's entire history so far. Adopting "always newest" now would mean generating a Stage-3 training corpus from a network already known, by this project's own evidence, not to be an improvement — with no check in between.

The two-gate *separation itself* (generator eligibility vs. release promotion) is sound and independent of that history: requiring a full promotion-grade SPRT before any exploratory generation is unnecessary overhead, and #210 already gives generation output somewhere principled to go (a dataset-generation manifest with its own assessment state, distinct from a network's promotion state). What #212's original text got wrong was conflating "no promotion gate before generation" with "no gate at all." This document keeps the former, rejects the latter, and defines the actual eligibility gate.

## 3. The two-gate principle

**Generator eligibility ≠ release promotion.** Two different questions, two different decision records, two different owners of evidence:

| | Generator eligibility | Release promotion |
|---|---|---|
| Question | May this exact network/build produce a bounded Stage-3 research corpus? | Is this candidate strong enough and safe enough to ship? |
| Evidence required | Identity, legality, protocol conformance, a passed bounded pilot (§4) | Full promotion rule, `measurement-model.md` §7 — pooled correlation, RMSE, calibration, majority-population correlation, all four conditions |
| Failure consequence | Generation blocked or paused for that exact identity | Network stays unshipped; nothing about generation is affected |
| Owner concept | `GeneratorEligibilityDecision` (§4, §12) | Existing SPRT/promotion process (§9), unchanged |

No mandatory release SPRT is required before exploratory generation — that much of the original #212 proposal holds and is restated here as settled. But **"no mandatory release SPRT" is not "unconditional latest-candidate generation."** This document explicitly rejects "always use the newest checkpoint/network automatically" as a bootstrap or iterative rule (§6, §7), unless a future revision of this ADR is written against new evidence that changes that judgment.

## 4. Generator eligibility

A generator candidate is identified by exactly the identity fields #210's manifest and VSPR's header already carry (`DR-220-vspr-wire-format.md` §6, `trainer/trainer/dataset/selfplay_manifest.py`'s `sources[]` schema) — this document defines no new identity scheme:

- **logical network identity** — an experiment/checkpoint label (e.g. `P1-G04`), carried as VSPR's `generatorNetworkUuid` (a free-form bounded string per the wire format, not required to be an RFC-4122 UUID)
- **exact network artifact SHA-256** — VSPR's `generatorNetworkSha256`
- **exact engine build ID** — VSPR's `engineBuildId` (the git commit SHA the engine binary was built from, per `DR-220` §6)
- **search/generation configuration identity** — `GameConfig` (search budget, move cap, adjudication/diversity config), recorded in the decision record (§12), not itself a new wire field

Eligibility checks are bounded and purpose-appropriate — they establish "safe/sane enough to generate bounded research data," **not** "stronger than the current release evaluator" and **not** "release-worthy." Four categories, all required before a pilot begins:

**IDENTITY** — the four fields above are known and recorded before generation starts; a candidate with an unrecorded or ambiguous identity is ineligible by construction (it cannot produce a valid VSPR header at all).

**ENGINE/LEGALITY** — the generator loads successfully; search completes on a fixed set of deterministic smoke positions; every returned move is legal in the position it was returned for; board/search state remains internally consistent across the smoke run (no exception, no assertion failure); no non-finite (`NaN`/`Inf`) or otherwise corrupted search scores.

**PROTOCOL** — the VSPR output the candidate's engine build actually produces passes the strict, existing codec (`trainer.vspr`, verified against the golden fixtures per #211 — no independent reinterpretation); every provenance field the file carries matches the generator identity selected for this run, not some other build or network.

**BOUNDED PILOT** — the candidate first generates a capped pilot corpus (§10), the pilot is ingested through #210's existing pipeline unmodified, and the resulting dataset manifest's assessment begins at `unassessed` (#210's own default) — eligibility does not itself set an assessment outcome; that is §5/§8's separate job.

Eligibility explicitly does **not** require: a promotion-grade SPRT, proof the candidate is stronger than the current release evaluator, or a full production-scale generation run. A network can be eligible to generate and simultaneously known, by the same project's own evidence, not to be a promotion candidate — these are not in tension (§9).

## 5. Bounded pilot and dataset assessment

Once IDENTITY/ENGINE-LEGALITY/PROTOCOL pass, the candidate generates one **bounded pilot** (budget defined in §10), which is ingested through #210 exactly as any other VSPR input. The resulting dataset-generation manifest's `assessment` then moves from `unassessed` to `assessed + approved` or `assessed + rejected` (#210's existing three-state model — this document assigns it meaning for Stage-3 pilots, it does not redefine its shape).

**"Approved" means the generated data is usable research-corpus quality — nothing about the generating network's promotion status.** Checks whose semantics already follow directly from #209/#220/#210, with no new numeric machinery invented here:

- decoder/ingestion succeeded end to end (§210's own strict, fail-closed decode/ingest path)
- zero illegal or structurally invalid records survived ingestion (VSPR's own decode-time legality/bound checks, `trainer.vspr`)
- provenance is internally consistent (recorded generator identity matches what every sample in the corpus actually carries)
- observed sample/game counts match what the pilot budget declared (§10) within whatever tolerance the pilot's own preregistration states
- the unresolved-outcome (infrastructure-failure) rate does not exceed a **preregistered** tolerance for that specific run
- score/label fields are structurally valid (VSPR's own tagged-union cp/mate encoding, decode-time bound checks)
- game outcome/termination-reason pairs are valid (DR-220 §6's own pairing table, enforced at decode time — nothing new to check here)
- no game-ID leakage or collision (#210's `(runId, gameId)` remapping and conflict detection, exercised automatically by ingestion)

**On numeric/statistical corpus-quality thresholds (e.g. "no catastrophic degeneration in obvious corpus statistics"): this document deliberately does not invent fixed numbers.** No prior Stage-3 pilot has run; there is no distributional baseline yet to set a threshold against, and a threshold chosen without one would be exactly the kind of unsupported arbitrary value this task's own instructions forbid. Where a numeric tolerance is needed (unresolved-rate ceiling, expected sample-count band, any future degeneracy check), it **must be preregistered per pilot/run**, before that pilot runs, as part of the generation decision record (§12) — supplied by whoever owns that generation cycle's decision, with its rationale recorded alongside the number. A pilot with no preregistered tolerance for a given check cannot use that check to justify `approved`; it is not automatically `rejected` either — it means that specific check was not evaluated, and the decision record must say so rather than silently passing.

## 6. Bootstrap generator

Stage 3 has no prior generate-then-retrain lineage — there is no earlier Stage-3-trained network to bootstrap from, only Vex's existing supervised-training lineage (Phase 1 through Phase 5). This document does not adopt "latest candidate" as an implicit bootstrap rule (§3).

**Correction (post-#221 review): `P1-G04` and `P3A-001` are two different checkpoint artifacts, not one network under two names.** An earlier version of this section wrote "P1-G04, embodied by its P3A-001 checkpoint," carrying forward a conflation that also appears in `phase5-p5-auxhead-multitask.md`'s own summary table — but the two files have distinct, independently verified SHA-256 hashes, recorded side by side in `phase5-p5-auxhead-rms.md` §2:

| Logical identity | Exact artifact path | SHA-256 |
|---|---|---|
| `P1-G04` | `outputs/phase1/P1-G04/checkpoints/step-015999.pt` (the selected step, per `phase5-p5-auxhead-rms.md`'s own correction distinguishing it from `final.pt`) | `acd24d68bc21d34e09ad71f651e7f0c5055adb32f90b0cae9480ea3343036cd8` |
| `P3A-001` | `outputs/phase3/P3A-001/checkpoints/step-016999.pt` | `c2c33d1dd7af5345d8772aa0761e79d5b9bc8bff7aff409b946078684c08d0ef` |

`P3A-001` is Experiment 3A's retrain of P1-G04's exact training schedule on a sentinel-filtered corpus (`2026-07-19-nnue-improvement-analysis.md` §35) — statistically indistinguishable from P1-G04 on held-out correlation (+0.0002, an order of magnitude below the noise floor), but a genuinely separate trained artifact with its own weights and its own hash, not the same file.

**Current project evidence supports naming an explicit bootstrap network now: `P3A-001`**, exact artifact `outputs/phase3/P3A-001/checkpoints/step-016999.pt`, `sha256 c2c33d1dd7af5345d8772aa0761e79d5b9bc8bff7aff409b946078684c08d0ef`. Rationale, from current evidence, not novelty:

- **It is the artifact this project's own later experiments actually treat as the operational baseline, not P1-G04.** `phase5-p5-auxhead-rms.md` §2 is explicit: P5-AUXHEAD-RMS-001's mechanism and primary gating "compare only the matched control, the treatment, and the P3A-001 baseline" — and states P1-G04's own checkpoint hash was "recorded for display-only continuity (**never used for gating**)." Whatever earlier files (including this document's own prior draft) said about "P1-G04 via P3A-001," the actual operational precedent in this codebase is P3A-001 by itself.
- **It is the only network anywhere in the roadmap's current lineage with reference/production status**, not merely offline-not-yet-rejected. Every later trained candidate this project has evaluated — P4II, P4III, P4IV, P5-WDLALT, P5-AUXHEAD-001, P5-AUXHEAD-RMS-001 — failed the permanent promotion rule (`measurement-model.md` §7); several regressed RMSE/calibration outright, not merely a flat correlation. Choosing any of those over P3A-001 as a bootstrap would be choosing a network already known, by this project's own accumulated evidence, to be worse.
- **It has been reused as the fixed gating baseline across multiple independent Phase 4/5 experiments with no noted numerical or stability defect.** That is real evidence toward the ENGINE/LEGALITY category's numeric-soundness concern (no NaN/corrupted weights) — but it is evidence from forward-pass evaluation during training-report generation, **not** from being searched against inside a self-play `GameLoop`. This document states that gap explicitly rather than papering over it: **P3A-001's extensive reuse as a training baseline does not by itself satisfy the ENGINE/LEGALITY smoke checks (§4)** — legal-move generation and search-completion behavior under actual self-play search has never been exercised for this checkpoint, because no GameLoop exists yet to exercise it with. The bootstrap decision here is "P3A-001 is the correct network to *point* the first eligibility check at," not "P3A-001 is already eligible" — the full §4 eligibility sequence, including its own ENGINE/LEGALITY smoke run, is still required before any bootstrap pilot generates a single game.

**What this document does not decide**: the exact engine build (git commit) the bootstrap pilot runs under, and the exact `GameConfig` (search budget, move cap, adjudication policy) it uses, are not fixed here — those depend on the generator implementation issue this ADR exports a contract for (§17) and on whatever engine-core commit exists when that implementation actually runs. Recording them is the generation decision record's job (§12), not this document's.

If a future reviewer judges P3A-001 insufficient (e.g. the ENGINE/LEGALITY smoke run fails, or a materially better-justified candidate emerges before implementation), that is an explicit owner decision to override this section — not a silent default to whatever is newest.

## 7. Iterative generator selection (post-bootstrap)

No single universal rule ("always latest," "always current release model") governs which generator a later generation cycle uses. Each generation cycle requires an explicit **selection record** (part of §12's decision record), stating:

- candidate generator identity (§4's four identity fields)
- predecessor generator identity, if any (the generator this cycle's candidate is meant to supersede or compare against)
- reason selected
- prior offline metrics or experiment reference, if the candidate was trained (not merely configured) — e.g. a `measurement-model.md` §7 promotion-rule evaluation, even if that evaluation's outcome was "not promotable"
- prior generation assessment for this exact identity, if it has generated before (§5/§8)
- selection kind: **bootstrap**, **exploratory**, **continuation**, or **comparison/control**

**A newly trained candidate may be selected for a bounded exploratory pilot without first passing promotion SPRT**, provided its eligibility checks (§4) pass — this is the direct consequence of §3's two-gate separation, restated concretely. **A candidate with a known severe correctness, numerical, or data-quality defect must not generate production research data merely because it is newest** — that is a hard-failure category (§11), independent of how recently it was trained.

**Offline strength regression alone is not automatically a correctness defect.** A network that fails the promotion rule (§9) on correlation/RMSE/calibration grounds can still pass every IDENTITY/ENGINE-LEGALITY/PROTOCOL check and generate structurally valid, usable research data — strength and correctness are orthogonal axes here (§11 makes this the load-bearing distinction between STRENGTH FAILURE and the other two categories). Selecting such a network for an exploratory or comparison/control pilot is a legitimate use of this policy, not an exception to it.

## 8. Dataset assessment vs. generator eligibility (scope precision)

`GeneratorEligibilityDecision` (a decision about one exact network/build/config, made before or at the start of a generation cycle, §4/§12) and `DatasetAssessment` (#210's manifest assessment, a result for one generated-and-ingested dataset, made after generation, §5) are separate records with separate lifetimes. Neither substitutes for the other:

- **One generator identity can produce multiple datasets**, under different `GameConfig`s or at different times, each with its own independent `DatasetAssessment`. An approved dataset from one run does not universally certify every future run from that same generator identity — a later run under a different search budget, a different move-cap policy, or after an unrelated infrastructure change is a new dataset requiring its own assessment.
- **A rejected dataset does not mean the generating network can never be used again**, unless the rejection's own evidence demonstrates an *intrinsic* generator defect (e.g. an ENGINE/LEGALITY hard failure, §11) rather than a run-specific cause (e.g. an infrastructure crash unrelated to the network itself, or a config mismatch). The rejection's evidence_ref (#210's manifest field) is what a later reviewer reads to tell these apart — this document does not add a second field for it, since #210's existing `evidence_ref` string already carries exactly this kind of free-text justification.

## 9. Release promotion — unchanged

This document changes none of the following, and states so explicitly per its own scope:

- offline model-promotion metrics (`measurement-model.md` §6/§7, unmodified)
- release-candidate selection criteria
- SPRT process or boundaries (native-Windows-only execution, per `CLAUDE.md` §6, unmodified)
- shipped-evaluator selection

**Generator eligibility does not imply promotion. Dataset approval does not imply promotion.** A generator may be legitimately useful for producing research data — including data that later trains a genuinely stronger candidate — and the generator itself may still never ship. These are independent facts about independent artifacts (the generating network, and whatever future network its data helps train), and this document's two-gate separation (§3) exists specifically so that conflating them is a category error going forward, not an ambiguity left open.

## 10. Pilot budget

No fixed game count is adopted here — no existing project evidence (no completed Stage-3 pilot, no prior generate-retrain cycle) supports pinning one number now, and inventing one would be exactly the unsupported arbitrary threshold this task's instructions forbid.

Instead, pilot budget is **mandatory, finite, per-run configuration**, recorded in the generation decision record (§12) before generation starts:

- max games (required)
- and/or max positions/samples (required if a per-position cap is more meaningful than a per-game one for that run's purpose)
- search/node/depth budget inherited from that run's `GameConfig` (not a separate pilot-specific field — the same budget every sample in the run already carries, per DR-220 §6)

**The generator implementation must refuse to run an unbounded pilot** — this is an implementation-contract requirement (§15), not merely a recommendation. **Production-scale generation, if ever introduced, requires an approved pilot assessment (§5) for that exact generator identity and configuration first** — this document does not otherwise define what "production-scale" means, since no production generation exists yet to define it against; that is deferred to whichever future task first proposes it, against whatever pilot evidence exists by then.

## 11. Failure taxonomy

Three categories, deliberately never conflated:

**HARD CORRECTNESS FAILURE** — generator identity mismatch; network hash mismatch; engine build mismatch; an illegal move returned by search; a board/search invariant failure (e.g. the `UNMAKE_POOL_SIZE` bound from `DR-E9` §2 exceeded, an assertion failure); a non-finite search result; a VSPR serialization/validation failure on the generator's own output; repeated infrastructure crashes beyond that run's preregistered tolerance; a manifest/provenance mismatch. **Consequence: the generator is ineligible for continuation under that exact identity/config until the defect is fixed and eligibility (§4) is re-established from scratch** — a hard-correctness failure is not something a later pilot for the same identity can simply retry past.

**QUALITY ASSESSMENT FAILURE** — the generator passed eligibility and ran without a hard correctness failure, but the resulting dataset fails one of §5's assessment checks (e.g. unresolved-rate exceeded its preregistered tolerance, provenance inconsistency inside otherwise-valid data). **Consequence: that dataset is rejected (#210's manifest assessment); the generator itself may or may not need investigation or a different configuration** — §8 governs whether this implicates the generator identity broadly or just that one run.

**STRENGTH FAILURE** — the generating network fails the release promotion rule (`measurement-model.md` §7) or otherwise measures offline-weaker than some reference. **Consequence: relevant to promotion (§9) only. Not, by itself, a generation-eligibility or dataset-quality concern** (§7's explicit distinction) — a network can be simultaneously a STRENGTH FAILURE and fully eligible to generate valid research data.

These three categories are independent axes, not a severity ladder: a candidate can fail one, two, all three, or none, in any combination, and each failure type routes to its own consequence without inferring the others.

## 12. Provenance / decision record

Each generation run's minimum decision artifact, recorded once per run (not duplicated into every downstream artifact — VSPR/#210's existing provenance fields already carry the per-sample and per-dataset copies this needs):

- exact generator network identity (§4's four fields)
- exact engine build (git commit)
- search/generation configuration (`GameConfig`)
- RNG/run seed
- pilot vs. production designation
- generation budget (§10)
- eligibility decision (pass/fail per §4 category, with evidence for each)
- evidence references (smoke-test results, any preregistered numeric tolerances used in §5's assessment, prior offline metrics if applicable per §7)
- resulting VSPR file identity (`runId`, per DR-220 §9 — one VSPR file is one run, by that document's own design)
- resulting dataset manifest identity (#210's `dataset_identifier`)
- dataset assessment result (#210's `assessment` block, once the pilot is ingested and assessed)

This document defines responsibility for what must be recorded and where each piece already has a natural home (VSPR header fields, #210's manifest, this record's own eligibility/selection fields) — it does not create a new generic workflow database, and does not require duplicating VSPR/manifest fields into a third artifact when those two already carry them.

## 13. Consequences

**Easier**: a network known not to be promotable (the common case throughout this project's history so far) can still be used productively for Stage-3 exploratory or comparison-control generation, without that use being confused for a promotion signal in either direction. A future generator implementation has an unambiguous, bounded contract (§15) to build against rather than inventing eligibility/pilot/assessment semantics itself. Failure investigation has a taxonomy (§11) that routes a given failure to the right owner and the right remediation, instead of one undifferentiated "something went wrong" bucket.

**Harder**: every generation run now carries more required bookkeeping (§12) than "just run it" would — an explicit cost accepted here because #210's ingestion path already makes almost all of it free (provenance fields it already records) and the alternative (ungated, unrecorded generation) is exactly the risk §2 identifies. A reviewer must actively distinguish HARD CORRECTNESS from QUALITY ASSESSMENT from STRENGTH failures rather than reaching for one umbrella "validation failed" verdict — a discipline cost, not a mechanical one, since nothing in the codebase enforces the distinction structurally (this document is policy, not code).

## 14. Rejected alternatives

**"Always use the latest candidate, unconditionally."** The original #212 proposal. Rejected per §2/§3: the cited precedent is external, not evidence for this project's current state, and this project's own most recent candidates are known not-promotable — adopting this now would mean generating a corpus from a network already known worse than the reference.

**"No policy until a full generate-retrain cycle exists to learn from."** Considered and rejected: this defers exactly the ungated-generation risk §2 identifies, with no interim safeguard, and blocks #210's ingestion path (already built and closed) from having any principled first consumer.

**A single universal post-bootstrap selection rule ("always current release model," "always the most recent eligible candidate").** Rejected per §7: different generation cycles legitimately want different selections (bootstrap, exploratory, continuation, comparison/control), and forcing one rule either blocks legitimate exploratory use or silently permits generation from a network nobody actually chose deliberately.

**Fixed numeric pilot-size and corpus-quality thresholds, decided now.** Rejected per §5/§10: no completed Stage-3 pilot exists yet to derive a defensible number from; a number invented without evidence is indistinguishable from an arbitrary one, which this task's own instructions explicitly forbid. Preregistration per pilot is the adopted alternative.

**Folding generator eligibility into #210's dataset assessment as one combined record.** Rejected per §8: eligibility is a per-identity, pre-generation decision; assessment is a per-dataset, post-generation result. Collapsing them would make it impossible to express "this generator is eligible but this particular run's data was rejected for an unrelated infrastructure reason" — exactly the distinction §16's scenario review below depends on.

## 15. Open decisions

- The exact engine build (git commit) and `GameConfig` the bootstrap pilot (§6) runs under — deferred to the generator implementation issue this document exports a contract for (§16), since no such implementation exists yet to pin a build against.
- Numeric preregistered tolerances for any given pilot's unresolved-rate ceiling, expected sample-count band, or corpus-statistics degeneracy checks (§5) — owned per-run by whoever runs that generation cycle, not fixed by this document.
- Whether a future revision of this ADR, informed by an actual completed pilot's evidence, should relax any part of §4's eligibility bar or §7's per-cycle selection-record requirement. Not decided now; explicitly left for evidence this project does not yet have.
- Whether P3A-001 should remain the bootstrap generator once its own ENGINE/LEGALITY smoke run (§4) is actually executed for the first time — that run may surface a defect this document's training-only reuse evidence could not have caught (§6's explicit caveat).

## 16. Adversarial policy review

Each scenario below must resolve to an unambiguous decision category under §3/§11. None require new policy beyond what's already stated above; this section exists to show that explicitly, not to add rules.

| Scenario | Category | Resolution |
|---|---|---|
| Newest model is weaker offline but structurally valid | STRENGTH FAILURE only | Eligible to generate (§4/§7) if it passes IDENTITY/ENGINE-LEGALITY/PROTOCOL; not promotable (§9), unaffected by generation use |
| Newest model produces illegal moves | HARD CORRECTNESS FAILURE | Ineligible under §4's ENGINE/LEGALITY check; blocked before any pilot runs |
| Generator passes pilot once, a later run crashes | Per-run QUALITY ASSESSMENT or HARD CORRECTNESS, not automatic | §8: the later run's own dataset is assessed independently; whether the generator identity itself is implicated depends on the crash's cause (infrastructure vs. intrinsic defect), per §8's rejected-dataset rule |
| Same network under a different engine build | New identity for §4 purposes | `engineBuildId` is one of the four identity fields (§4); a build change requires re-running eligibility, since PROTOCOL/ENGINE-LEGALITY are properties of the build, not just the network weights |
| Same network under a different search budget | New generation-cycle selection record (§7), same network identity | Eligibility (§4, identity-level) does not need re-establishing for the network itself if build is unchanged; the pilot/assessment (§5) and decision record (§12) are still per-run, since `GameConfig` is part of what a dataset's quality checks (e.g. expected sample counts) depend on |
| Pilot dataset rejected for quality | QUALITY ASSESSMENT FAILURE | Per §8/§11: dataset rejected; generator eligibility for a *future* run is unaffected unless the rejection's evidence shows an intrinsic generator defect |
| Candidate never passed release SPRT | Not a generation concern | §3/§9: release SPRT is not a precondition for generation eligibility at all |
| Candidate passed release SPRT but produces malformed VSPR under a new engine build | HARD CORRECTNESS FAILURE (PROTOCOL) for that build | §11: blocks generation under that build regardless of the network's promotion status — promotion and PROTOCOL conformance are unrelated axes |
| Bootstrap has no predecessor | Expected, not an error | §7: `predecessor generator identity` is explicitly "if any" in the selection record; the bootstrap cycle (§6) has none by definition |
| Two candidate generators both appear usable | Both may be eligible simultaneously | §4/§7 impose no exclusivity; each gets its own selection record and, if both generate, independent dataset assessments |
| Generator dataset approved but the resulting trained child regresses | Not a generation-policy failure | §9: dataset approval is a data-quality judgment, not a claim about what a model later trained on it will achieve; the child's own promotion evaluation (§9, unchanged) governs whether it ships |
| Generator dataset rejected because of infrastructure failure, not model quality | QUALITY ASSESSMENT FAILURE, generator not implicated | §8's rejected-dataset rule: rejection evidence pointing at infrastructure (not an intrinsic generator defect) does not block that generator identity's future eligibility |

## 17. Implementation contract for the future generator/CLI issue

Exported here for whichever future issue implements the bounded Stage-3 generator/CLI (not implemented in this document, and not started by it). That implementation must eventually provide:

- **Explicit exact generator identity** — the four §4 fields, supplied by the operator invoking the tool, never inferred or defaulted from "whatever checkpoint is newest on disk."
- **Bounded pilot mode** — a mandatory, finite generation budget (§10: max games and/or max positions) that the tool refuses to omit; no unbounded-run code path.
- **Fail-closed legality/numeric checks** — the ENGINE/LEGALITY smoke checks (§4) run and must pass before any game contributing to the pilot corpus is generated for real; a non-finite score, an illegal move, or a board/search invariant failure aborts the run per §11's HARD CORRECTNESS category, not a partial/best-effort continuation.
- **VSPR output per #220** — via the existing wire format and, on the decode/verification side, the existing `trainer.vspr` codec; no independent reinterpretation of the byte layout, matching the same discipline #210's ingester already follows.
- **Decision/provenance hooks required by this document** — the tool must be able to emit (or accept as input, for the eligibility/selection portions decided by a human operator beforehand) everything §12's decision record requires, in a form #210's ingestion path can carry through to the dataset manifest without inventing a second parallel provenance scheme.
- **No implicit "latest checkpoint" selection** — direct implementation consequence of §3/§6/§7's rejection of that rule.
- **No promotion decision logic** — the tool generates and records; it does not decide whether anything it produces, or any network it used, should ship. That stays entirely within the existing, unchanged release process (§9).

This is a contract for a future implementation issue to satisfy, not a specification of that issue's internal design — the generator/CLI issue itself will make its own implementation choices within these bounds.
