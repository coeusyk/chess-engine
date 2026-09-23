# Dev Entries - Phase 20

---

### [2026-09-23] Phase 20: Lazy SMP qualification preregistration

**Built:**

- Started `phase/20-smp-qualification` from `origin/develop` at `85b201a` (the merge of PR #245, Phase 19 closure). Confirmed that `git diff 6afc523 HEAD -- engine-core/src/main engine-uci/src/main` is empty, so the production search source is still the Phase 18 frozen reference.
- Wrote `docs/architecture/research/phase20-smp-qualification-preregistration.md`. It is a plan only. No code, instrumentation, benchmarks, games or SPRT runs were produced.
- Before writing it I read the Lazy SMP path end to end: `UciApplication.handleGo` and `runSearch`, the `smpExecutor` setup, `Searcher.iterativeDeepening` (the start-depth overload used by helpers), `Searcher.setSharedTranspositionTable`, `TranspositionTable` in full, `BenchRunner.run`, and the existing `Threads=2` tests in `UciApplicationIntegrationTest`. The preregistration's architecture description and ownership matrix come from that reading, not from the Phase 7 description of the feature.

**Decisions Made:**

- Treated the historical "1T beats 2T" story as weak evidence rather than a finding to overturn. What the repository actually holds is one 34-game Phase 7 SPRT at 5+0.05 (-152 +/- 120, H0), a Phase 13 "2T NPS >= 1T NPS" bench that was left as TBD, and a Phase 13 SMP SPRT killed at H0 with no numbers logged. All of it predates the packed TT, the P16-1 lock-free fix and the Phase 18 repairs.
- Recorded several source facts that change how SMP has to be measured:
  - Result selection is main-thread only, and helper results are discarded.
  - UCI `nodes` counts the main thread only, so any past "2T NPS" read from UCI output excluded helper work.
  - `BenchRunner` can only run 1T, so every SMP measurement has to drive the real `runSearch` path.
  - Helpers are never joined, and their exceptions are swallowed silently.
  - The only diversification is start-depth staggering (`(i % 2 == 1) ? (i/2) + 2 : 1`).
  - Every searcher, helpers included, resets the shared TT stats when it starts.
- Ordered the stages so that nothing gets timed before its contaminants are ruled out. Stage 0 checks harness validity against the P18-5 node counts. Stage 1 runs lifecycle and correctness gates. Stage 2 sets an environment/JVM ceiling from independent searchers, both as separate processes and in one JVM. Only then comes Stage 3, the fixed-depth 1T/2T/4T time-to-depth measurement, followed by the conditional TT-capacity (Stage 4) and overlap/diversification (Stage 5) stages. The fixed-time stage runs only if Stage 1 passes and Stage 3 shows speedup outside noise.
- Did not invent numeric thresholds. The noise floor comes from the 1T run-to-run spread measured in the same session. The heap size, larger Hash value, movetime and clock states, affinity policy and extension budget are explicitly marked unresolved.
- Kept repairs, SMP redesign, stagger tuning, NNUE under SMP, more than 4 threads and any strength testing out of scope. Found defects go to a separately preregistered repair slice.
- Noted that `tools/sprt.ps1` applies `option.Threads=$EngineThreads` to both engines, so an eventual 2T vs 1T SPRT needs per-engine thread plumbing that doesn't exist yet.

**Broke / Fixed:**

- Nothing. Documentation only.

**Measurements:**

- None taken. The only numbers quoted in the preregistration are existing references: P18-5's 24,780,049 main nodes at depth 13 and its 1.70% CV (WSL2, descriptive only), and the Phase 7 SPRT result.

**Next:**

- Review the three high-risk assumptions in section 16 of the preregistration before execution: harness waiting on helpers that production never joins, native-Windows availability and thread placement, and whether main-thread time to depth at 16 MB is the right mechanism metric.
- Open the Phase 20 tracking issue(s) once the preregistration is reviewed.
