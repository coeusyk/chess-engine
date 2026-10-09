# Phase 21 Stage 6 strength preregistration proposal

Date: 2026-10-09. **Status: proposed; no policy selected and no execution authorized.** Stages 0–5 passed; [Stage 5 evidence](phase21-stage5-native/README.md) is committed. The measured 12.623% elapsed reduction establishes throughput improvement, with no measured Phase 21 Elo or match throughput.

## Decision to make before any game

The current [project policy](../../sprt-guidelines.md) remains `[0,50]`, alpha = beta = 0.05, TC `5+0.05`. [Phase 21 sections 9–11](phase21-direction-and-preregistration.md) permit a separately accepted amendment after Stage 5. Choose one policy before game 1. This proposal does not amend the policy or authorize running multiple alternatives, changing bounds after results, or a rescue SPRT.

Let Δ denote candidate strength minus control strength under the common conditions below. H0 and H1 are the lower and upper endpoint hypotheses. Acceptance favors one endpoint over the other; it does not establish Δ equals that endpoint, prove Δ exceeds H1, or give a 95% posterior probability. Values inside the interval have no promised classification.

| Bounds | H0 accepted and promotion consequence | H1 accepted and promotion consequence | Nominal errors at the endpoints |
|---|---|---|---|
| Existing `[0,50]` | Favors 0 over +50. Reject promotion under the existing large-gain policy. Does not prove zero gain or regression; a smaller positive gain can fail. | Favors +50 over 0. Promote under the existing policy. This is not a lower confidence bound of +50. | α: about 5% accepting H1 at Δ=0. β: about 5% accepting H0 at Δ=+50. |
| Narrower gain `[0,10]` | Favors 0 over +10. Reject promotion under a positive-gain policy with a narrower target. Small positive effects can still fail. | Favors +10 over 0. Promote under the narrower gain policy; do not claim a demonstrated minimum +10 gain. | α: about 5% accepting H1 at Δ=0. β: about 5% accepting H0 at Δ=+10. |
| Non-inferiority `[-5,0]` | Favors −5 over 0. Reject promotion under a policy treating a 5-Elo loss as unacceptable. Does not establish a particular regression magnitude. | Favors 0 over −5. Promote a contract repair under a 5-Elo tolerance policy, even without positive-gain evidence. A small true loss can pass. | α: about 5% accepting H1 at Δ=−5. β: about 5% accepting H0 at Δ=0. |

Alpha concerns choosing the upper endpoint when the lower is true; beta concerns the reverse. These are nominal model-based rates, not guaranteed calibration for correlated games or probabilities a verdict is wrong. The cap adds an inconclusive outcome; 5% error targets do not guarantee 95% promotion by the cap. Use `BonferroniM=1` for the single selected comparison.

[Cute Chess 1.5.1's implementation](https://github.com/cutechess/cutechess/blob/v1.5.1/projects/lib/src/sprt.cpp) uses W/L/D counts, regularization and an estimated draw parameter. Its LLR boundaries are `ln(beta/(1-alpha))` and `ln((1-beta)/alpha)`, giving ±2.944439 at 0.05/0.05. [Its interface documents endpoint error interpretation](https://github.com/cutechess/cutechess/blob/v1.5.1/projects/lib/src/sprt.h). Paired openings are retained, but this implementation does not use a pentanomial pair-count likelihood.

## Estimated sample size and runtime, not engine measurements

For scale only, assume independent game scores, 50% draws near equality, variance `V=0.125`, and local logistic score slope `k=ln(10)/1600`. With interval width `w`, set `v=(k*w)^2/V` and `A=ln(19)`. A symmetric Brownian LLR approximation gives uncapped expected games `1.8*A/v` at either true endpoint and `A*A/v` at the true midpoint. This is a sequential approximation, not fixed-sample power or simulated games. [Fishtest's mathematics notes](https://github.com/official-stockfish/fishtest/wiki/Fishtest-mathematics) describe this resource-estimation approach; its normalized-Elo/pentanomial setup is different from this runner's model.

For wall time only, additionally assume 10–20 seconds per complete game and concurrency 6, so `hours = games * seconds_per_game / (6*3600)`. Neither that game duration nor the assumed draw rate has been measured for this comparison.

| Bounds | Expected games if true Δ is an endpoint | Expected games if true Δ is the midpoint | Estimated hours at an endpoint / midpoint |
|---|---:|---:|---|
| `[0,50]` | ~128 at 0 or +50 | ~209 at +25 | 0.06–0.12 / 0.10–0.19 |
| `[0,10]` | ~3,199 at 0 or +10 | ~5,233 at +5 | 1.48–2.96 / 2.42–4.85 |
| `[-5,0]` | ~12,795 at −5 or 0 | ~20,931 at −2.5 | 5.92–11.85 / 9.69–19.38 uncapped |

At corresponding endpoints, widths 10 and 5 cost approximately 25× and 100× relative to width 50. These are not runtime ratios at an arbitrary shared Δ. Large effects can stop sooner. Draw rate, pair covariance, fitted nuisance parameters and overhead can change the estimates; Stage 5's time reduction supplies none of these match measurements.

Propose the existing **20,000-game cap for all three alternatives**, with no minimum-game barrier. Reaching it without a boundary is **INCONCLUSIVE**, with no promotion. It corresponds to 9.26–18.52 hours under the assumed game-duration range. In particular, the non-inferiority midpoint expectation already exceeds this cap. Do not stop at an estimated game count or relabel an interruption as H0.

## Unchanged engines and common proposed match conditions

| Arm | Exact production commit | Recorded Stage 5 JAR SHA-256 |
|---|---|---|
| OLD/control | `d3a56ffadf0d9151a2b19fba4902a734a99bff96` | `8b31e3bd9a44523f94158b3719b1845845505e9a07af64e1dccf3ae3f77b0222` |
| NEW/candidate | `e90d3d4f90b244c46e8bbf75c33222bba7d58003` | `64c5efab060f6c8a4ba98d6162af39ab956f8c0c75d8521639d1af9bb5705553` |

Stage 5 cleanup removed those JARs. A rebuild can change ZIP bytes; these hashes remain provenance. After policy acceptance, build these exact commits in isolated native worktrees with Zulu 21.0.10+7-LTS/Maven 3.9.16. Verify clean sources, freeze new Stage 6 JAR hashes before games, and preserve the match binaries. Do not substitute the branch tip or Phase 17 candidate.

The following conditions are common to every bounds alternative and await acceptance with the chosen policy:

| Condition | Exact proposed value |
|---|---|
| Host | Native Windows PowerShell on `RENEGADE`, Ryzen 7 7700X; Balanced plan, full affinity, no competing CPU workload. Record OS build without pinning it. |
| Match tool | Cute Chess CLI **1.5.1**, record executable path, version and SHA-256 before launch. |
| JVM | Same native Java executable for both arms; `--add-modules jdk.incubator.vector -jar <frozen-arm-jar>`, as constructed by `sprt.ps1`. No inherited JVM option variables, JFR, agents or diagnostic overrides. The generic runner does not forward Stage 5's heap flags. |
| Time and parallelism | `TC=5+0.05`, `Concurrency=6`, `EngineThreads=1`; fixed throughout, following the historical Phase 17 host configuration. |
| Symmetric UCI options | `Hash=16`, `PawnHashSize=1`, `EvalType=Classical`, `MultiPV=1`, `Contempt=0`, `OwnBook=false`, `SyzygyOnline=false`, empty `SyzygyPath`. All other defaults unchanged. |
| Openings | `tools/noob_3moves.epd`, 150,932 positions, byte SHA-256 `2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347`; `format=epd order=random plies=4`, `-repeat` for swapped colors. Local and native copies currently match this hash. Archive the corpus before games; it is currently gitignored. |
| Adjudication | `-resign movecount=5 score=400`; `-draw movenumber=40 movecount=8 score=10`, unchanged from the existing runner. |
| Statistical settings | Explicit selected `Elo0/Elo1`, `Alpha=0.05`, `Beta=0.05`, `BonferroniM=1`, `MinGames=0`, `MaxGames=20000`. |
| Evidence | Unique native run directory, frozen manifests, complete authoritative log/PGN, final LLR/verdict, W/L/D and paired-opening/color audit. Preserve cancellations after a boundary separately from scored games. |

Use [sprt.ps1](../../../tools/sprt.ps1) with identical NEW/OLD options and explicit settings. Its defaults are `[0,10]`, `60+0.6`, concurrency 2 and UCI Hash 64 MB. It permits a missing opening file, does not enforce native identity or frozen commits/hashes, and `MinGames` is not a minimum-stop barrier. Verify prerequisites before launch. It exposes no opening-seed parameter; retain actual PGN opening order. This proposal changes no tooling.

Stop at a valid LLR boundary or the declared cap. Keep all scored games and log any crash, illegal move, timeout, forfeit or recovery. A setup/protocol fault requires a validity audit; an unfavorable score alone cannot invalidate the run. Do not silently restart, selectively discard games, change concurrency or choose new bounds afterward.

## Recommendation, pending the user's decision

I recommend **`[0,10]` with alpha = beta = 0.05**: retain a positive-gain policy, with a narrower target and longer workload. A genuine gain inside the interval can still fail. Choose `[-5,0]` only if tolerating a small possible strength loss for the contract repair is an explicit policy; throughput alone does not justify that tolerance.

[Phase 17](phase17-closure.md) validly accepted H0 after 0W/13L/1D despite larger throughput gains. [Phase 19](phase19-pvs-diagnostic-report.md) found a verification hole without proving it solely caused that result or authorizing a rescue test. Neither predicts Phase 21 Elo. The archived Phase 17 preregistration was inspected at commit `2087383dbef2ee345811f67919ae7b39058b67bb`, path `docs/architecture/research/phase17-p17-4-strength-preregistration.md`.

**Before execution:** the user chooses one policy and accepts common conditions/cap. Record any Phase 21 exception in `docs/sprt-guidelines.md` before games, then prepare the exact native command. Until acceptance, `[0,50]` remains governing, no alternative is selected, and Stage 6 is unstarted.
