# #260 fixed-count paired characterization: proposal

2026-11-25. This is a proposal for the owner to approve or change. Nothing is launched and nothing here authorizes a launch. It describes a fixed-count native comparison of the merged #258 tree against its first parent, where the question is how large the strength difference is, not whether a stopping rule fires. It also lists where that conflicts with the preparation already on disk, and what 4,000 games can and cannot show.

## What is being compared

The frozen builds from the prepared set (`tools/results/vex-followup/20261124T1102Z/history-260/` on the native Windows checkout, not in git):

| | Commit | Archive SHA-256 | JAR SHA-256 |
|---|---|---|---|
| base | `61a37127776cbe3d81349bf115d5f00aefdf63a6` | `6b323c02...b154a` | `93d746ff...a8143b` |
| candidate | `c5042536f06eb279e053309d74c7c9e2c6f87910` (merge tree of #258) | `3eb816d2...b6b6` | `b13fe7ba...b4fe0` |

(full hashes are in that folder's `provenance.json`). Everything else stays as prepared: native Windows, JDK 21.0.10 (Zulu), cutechess-cli 1.5.1 (SHA-256 `8889f958...5d7e`), `sprt.ps1` with SHA-256 `68247ad1...2213`, the paired EPD book `noob_3moves.epd` (150,932 positions, SHA-256 `2011193b...3347`), 5+0.05, one thread, Hash 16, PawnHashSize 1, Classical evaluator, 6 concurrent games, `-repeat`, `order=random plies=4`, no opening book in the engines, no tablebases.

The reason to measure at timed play is that #258 changes more than repetition handling. It makes the game age that `TimeManager` and the book gate see come from the FEN fullmove counter, so the time divisor now moves with the game instead of staying at 40, and the candidate spends its clock differently. That can move strength in either direction. This is why an estimate is the useful output here.

## The design

- 4,000 games, so 2,000 opening pairs (`-repeat` plays each opening with both colours).
- No stopping rule is part of the design. The result is the pooled pair-level estimate and its 95 percent interval, computed offline from the PGN.
- The Cute Chess `-sprt` option cannot be removed without changing `sprt.ps1`, which would change the runner hash the frozen wrapper checks. So the existing runner is kept and its SPRT is made inert: `Alpha = Beta = 0.001`, bounds of plus or minus 6.91. Cute Chess's LLR is not used for any conclusion. A run that did stop on a bound would be reported as it is, and that would only happen for a candidate far worse than anything expected (a true difference near -30 Elo gives an expected LLR of about -7 at 2,000 pairs).
- Analysis, fixed in advance: pair scores over complete pairs among the first 4,000 games, mean, standard error, Elo with a 95 percent interval, and the draw rate. Reported next to it, for diagnosis only: colour split, time losses, adjudication counts, mean game length, and a count of games that ended abandoned, disconnected or unterminated.
- No decision threshold is attached. What the number means for #260 is decided after it exists, and that is the owner's call.

## Expected statistical precision

The variance comes from the #296 run, the only recent native run with the same book, time control, hash and thread count: 5,379 clean pairs, mean pair score 0.49605, variance 0.0799 (standard deviation 0.283), draw rate 33.5 percent. One point of score is about 695 Elo near an even match. The candidate here differs from #296's, so the real variance could be somewhat different. A 20 percent higher variance would widen every interval below by about 10 percent.

| Games | Pairs | SE (Elo) | 95% interval half-width (Elo) |
|---|---|---|---|
| 2,000 | 1,000 | 6.2 | 12.2 |
| **4,000** | **2,000** | **4.4** | **8.6** |
| 6,000 | 3,000 | 3.6 | 7.0 |
| 10,000 | 5,000 | 2.8 | 5.4 |
| 26,450 | 13,225 | 1.7 | 3.3 |

What 4,000 games can do: estimate the difference to within about plus or minus 8.6 Elo at 95 percent, and detect a true difference of about 12 Elo or more with 80 percent power (two-sided, 5 percent). That separates a large change from none. It cannot separate small ones.

What it cannot do: establish non-inferiority at the prepared margin of -5 Elo. With the one-sided test at 5 percent, the power to show the lower bound above -5 is about 31 percent even if the true difference is exactly 0, 23 percent at -1 and 17 percent at -2. Reaching 90 percent power at a true difference of 0 needs a standard error of about 1.7 Elo, which is about 13,200 pairs, or 26,450 games. That is close to the prepared 30,000 cap and about 17 hours at the speed #296 ran (about 1,530 games an hour). So a 4,000-game run answers a different question from the prepared one, and a non-inferiority conclusion would still need the long run.

Time: at that speed 4,000 games take about 2.6 hours.

## Conflicts with the existing preparation

1. **Hypothesis.** The prepared manifest is a non-inferiority SPRT, Elo0 -5 and Elo1 0, alpha = beta = 0.05, and the #260 issue asks for a "controlled native SPRT". A fixed-count run has no hypothesis and no stopping rule. The issue text and the preparation note would have to be amended, with the owner's approval, to say the first measurement is an estimate.
2. **Cap and alpha and beta.** 30,000 becomes 4,000 and 0.05 becomes 0.001. Both change the Cute Chess arguments, so `prepared-arguments.json` (hash `08da4d42...`) no longer applies. The new run needs its own prepared manifest in a new run folder. `history-260/` stays exactly as it is and is not edited.
3. **Claims.** The issue says not to claim Elo gains before an SPRT completes. A fixed-count run cannot complete an SPRT, so it supports no gain claim and no non-inferiority claim, only the estimate and its interval.
4. **Preparation note.** The existing note says a future launch needs separate approval for the hypothesis, the cap and the frozen manifest. All three are different in this proposal, so all three need that approval.
5. **Pooling.** The 5,379 clean #296 pairs are for a different candidate and cannot be pooled into this estimate.
6. **Opening order.** Unseeded, as in #296. Nothing in `sprt.ps1` passes `-srand`. For a 2.6 hour run I do not think it is worth changing the runner hash to add one: the openings actually played are in the PGN, so the data can be audited even though the order cannot be replayed. A seeded order is the opt-in change in #319, and it is only needed if an interrupted run ever has to be continued.
7. **Host.** The native Windows machine is the same physical host as the WSL2 environment where the #307 and #313 measurements are running. A native match during those measurements would disturb both, so the launch has to wait until they finish.
8. **Rules in `CLAUDE.md`.** SPRTs run on native Windows only. When a launch is approved, the exact `sprt.ps1` command is given and stops there. It is not run from here.

## Interruption

The #296 run was lost to a host power off, and the launcher reported it as finished (#319). The launcher is not fixed. So for this run the pre-registered rule is: if it ends before 4,000 games for any reason, it is recorded as interrupted and inconclusive and is not resumed. After it ends, before anything is read from it, the PGN is checked for abandoned, disconnected and unterminated games and the game count is compared with 4,000.

## What would be needed to launch

1. The owner approves the hypothesis change (estimation, no stopping rule), the 4,000 cap, the 0.001 alpha and beta, and the analysis plan above, and amends the #260 issue text.
2. A new run is prepared from the frozen builds with `-PrepareOnly`, its hashes are compared with the table above and recorded, and the prepared manifest is approved.
3. The #307 and #313 measurements are finished and the host is idle.
4. Explicit launch authorization. None has been given.
