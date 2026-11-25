# #260 fixed-count paired characterization

2026-11-25. Approved by the owner as a fixed-count characterization (see the last section); nothing is launched and nothing here authorizes a launch. It describes a fixed-count native comparison of the merged #258 tree against its first parent, where the question is how large the strength difference is, not whether a stopping rule fires. It also lists where that conflicts with the preparation already on disk, and what 4,000 games can and cannot show.

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
- The invocation is an actual fixed-count one, with no SPRT of any kind: the Cute Chess arguments are the ones `sprt.ps1` builds, minus `-sprt`, plus `-games 4000` and an explicit `-srand`. Loosening alpha and beta would not have done this, because `-sprt` with any alpha and beta still stops a match when a bound is crossed, which would make the game count depend on the results. The historical `sprt.ps1`, its SHA-256 and everything under `history-260/` stay exactly as they are. `sprt.ps1` has no mode without `-sprt`, so the new invocation comes from a new, separate launcher, written under the #319 plan.
- The opening order is seeded and the seed is part of the design: `-srand 260258`. It is recorded in the prepared manifest and in the run provenance, so the sequence of openings can be replayed and the run can be audited against it.
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
2. **Cap, alpha and beta, seed.** 30,000 becomes 4,000, `-sprt` and with it alpha and beta are gone, and `-srand 260258` is added. All of that changes the Cute Chess arguments, so `prepared-arguments.json` (hash `08da4d42...`) no longer applies. The new run needs its own prepared manifest in a new run folder, produced by the new launcher in its prepare-only mode and compared with the argument list in the next section. `history-260/` stays exactly as it is and is not edited.
3. **Claims.** The issue says not to claim Elo gains before an SPRT completes. A fixed-count run cannot complete an SPRT, so it supports no gain claim and no non-inferiority claim, only the estimate and its interval.
4. **Preparation note.** The existing note says a future launch needs separate approval for the hypothesis, the cap and the frozen manifest. All three are different in this proposal, so all three need that approval.
5. **Pooling.** The 5,379 clean #296 pairs are for a different candidate and cannot be pooled into this estimate.
6. **Opening order.** #296 used an unseeded order and nothing in `sprt.ps1` passes `-srand`. This run is seeded (`-srand 260258`), which is a difference from the historical protocol and is deliberate. The seed comes from the new launcher and is recorded in the manifest.
7. **Host.** The native Windows machine is the same physical host as the WSL2 environment where the #307 and #313 measurements are running. A native match during those measurements would disturb both, so the launch has to wait until they finish.
8. **Rules in `CLAUDE.md`.** Native matches run on native Windows only. When a launch is approved, the exact command of the new launcher is given and stops there. It is not run from here.

## The invocation

Same arguments as `sprt.ps1` builds for the historical preparation, with three differences: `-sprt` is removed, `-games` is 4000, and `-srand 260258` is added. In order (paths are inside the new run folder, written relative here):

```
-engine name=NEW cmd=java arg=--add-modules arg=jdk.incubator.vector arg=-jar arg=<cand.jar> proto=uci option.Threads=1
        option.Hash=16 option.PawnHashSize=1 option.EvalType=Classical option.MultiPV=1 option.Contempt=0
        option.OwnBook=false option.SyzygyOnline=false option.SyzygyPath=
-engine name=OLD cmd=java arg=--add-modules arg=jdk.incubator.vector arg=-jar arg=<base.jar> proto=uci option.Threads=1
        (the same options)
-each tc=5+0.05 -games 4000 -repeat -recover -resign movecount=5 score=400 -draw movenumber=40 movecount=8 score=10
-concurrency 6 -ratinginterval 10 -pgnout <match.pgn>
-openings file=<noob_3moves.epd> format=epd order=random plies=4 -srand 260258
```

The launcher is `tools/fixed_count_match.py`. It builds this list from the same function as `sprt_linux.py` and removes the `-sprt` group, so `-srand` comes last. Its prepare-only mode writes the argument manifest and a provenance record with the SHA-256 of both JARs, the book, the launcher and the Cute Chess executable, the game count and the seed. Launching with `--expected-provenance-path` refuses to start unless the arguments and all of those hashes equal the prepared record.

`-recover` is kept so the engine settings and the game flow are the same as in the historical run. The consequence is that Cute Chess finishes a match after engines it cannot restart, so completion is decided from the log and PGN by the new launcher's own validation and not from the exit code (#319).

## Interruption

The #296 run was lost to a host power off, and the launcher reported it as finished (#319). The new launcher validates the log and PGN after the run, and for this run the pre-registered rule is: if it ends before 4,000 games for any reason, or any game ended abandoned, disconnected or unterminated, it is recorded as interrupted and inconclusive and is not resumed. The result is not read until that validation has passed.

## Status and what is still needed to launch

The owner approved the 4,000 game fixed-count characterization on 2026-11-25 (an estimate, not a non-inferiority experiment). That covers the design above, the seed and the analysis plan. Still to do before a launch:

1. The new launcher and its post-run validation, under the #319 plan. It is not written yet, so the prepared manifest cannot exist yet.
2. The #260 issue wording is amended (done together with this note).
3. A new run folder is prepared from the frozen builds with the launcher's prepare-only mode. The jar, book, runner and toolchain hashes are compared with the table above and recorded, and the argument list is compared with the one above.
4. The #307 and #313 measurements are finished and the host-idle checks pass.
5. Explicit launch authorization. None has been given.
