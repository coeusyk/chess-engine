# #262 SEE correction: non-regression SPRT result and adjudication audit

## Result

The preregistered non-regression SPRT stopped at its upper boundary. Candidate was b26569f plus tooling-only commits (25a3889), baseline was 52fee7c, and the hypotheses were H0 = -5 Elo against H1 = 0 Elo with alpha = beta = 0.05 (see `docs/architecture/research/see-262-nonregression-protocol.md`). Cute Chess logged `llr 2.96, lbound -2.94, ubound 2.94 - H1 was accepted`. I recomputed the LLR from the W/D/L counts with a trinomial model at the observed draw rate and got 2.96 as well.

5,713 scored games: candidate 1,969 wins, 1,872 losses, 1,872 draws, score 0.5085, +5.9 Elo. My own 95% interval from the empirical per-game variance is -1.5 to +13.3. Cute Chess prints +/- 7.4. The 5 other games in the log and PGN were cancelled in flight when the SPRT stopped and were never scored.

What this means: the data favour "no worse than -5 Elo" over "5 Elo worse". It does not show an improvement, and the interval still includes zero.

Pairing: 2,856 complete opening pairs (5,712 games) plus one scored game whose partner was cancelled. NEW as White went 952-968-937 and as Black 1,017-904-935. Candidate pair results were 670 win-and-loss, 634 win-and-draw, 610 draw-and-loss, 332 won both, 296 lost both, 314 drew both.

## Reconciling the game count

The first count I reported during the run was wrong. The termination categories I had listed summed to 1,935 and I wrote 1,535, then computed percentages from that. It was a transcription error and not a parser bug, but it made the draw rate look like 41% when it was 33%. The final reconciliation:

- Log: 5,718 unique `Finished game` lines, no duplicates. PGN: 5,718 games. Compared by game number they agree on White, Black and Result in every case. The PGN is written in game-number order, so comparing in finish order shows false mismatches.
- 5 games are `No result` in the log and `unterminated` in the PGN. They are not in the score.
- Cute Chess's own `Score of` line (1969 - 1872 - 1872, 5713) matches the log-derived tally.
- Termination: 3,664 resign-adjudicated wins (1,880 candidate, 1,784 baseline), 177 checkmates (89 and 88), and 1,872 draws: 1,438 repetition, 208 adjudication, 167 insufficient material, 53 fifty-move rule, 6 stalemate.
- No crashes, forfeits, illegal moves or timeouts in the log. About 1,000 early-run moves, and 307 resign-adjudicated games at their sixth-previous loser move, carry no eval comment. I did not find out why.

## Resign rule

The frozen arguments were `-resign movecount=5 score=400` with no `twosided`. The installed cutechess-cli 1.5.1 help says `twosided` defaults to false, so only the losing engine's score counts. I checked this against the PGN evaluation comments: in all 3,664 resign-adjudicated games the loser's last 5 own moves are at or below -4.00, with no run of 6 or more where a sixth move was visible. The winner also reported at least +4.00 on its last 5 moves in 2,229 of them, so the rule really was one-sided.

## Independent check of the adjudications

`tools/adjudication_audit.py` draws a seeded sample (seed 262) of 25 resign-adjudicated games from each of 8 groups: candidate win or loss, candidate colour, and whether the winner's own last 5 evaluations were also at least +400 (`both_engines`) or not (`loser_only`). It replays each game with python-chess, then evaluates the final position with Stockfish 18 at depth 24, 4 threads, no tablebases. A position is `supported` if Stockfish gives the loser -300 cp or worse, `uncertain` between -300 and -150, and `potentially_incorrect` above -150. A depth-limited score is not a proven result, and 62 of the 200 sampled final positions have 7 pieces or fewer where a tablebase would have been the right check.

| Group | Pool | Sampled | Supported | Uncertain | Potentially incorrect |
|---|---|---|---|---|---|
| candidate loss, NEW Black, both engines | 516 | 25 | 24 | 0 | 1 |
| candidate loss, NEW Black, loser only | 344 | 25 | 25 | 0 | 0 |
| candidate loss, NEW White, both engines | 558 | 25 | 25 | 0 | 0 |
| candidate loss, NEW White, loser only | 366 | 25 | 24 | 0 | 1 |
| candidate win, NEW Black, both engines | 578 | 25 | 24 | 0 | 1 |
| candidate win, NEW Black, loser only | 393 | 25 | 24 | 0 | 1 |
| candidate win, NEW White, both engines | 577 | 25 | 24 | 0 | 1 |
| candidate win, NEW White, loser only | 332 | 25 | 24 | 0 | 1 |
| Sample total | 3,664 | 200 | 194 | 0 | 6 |

6 of 200 is 3.0%, Wilson 95% interval 1.4% to 6.4%. The groups have different pool sizes and the sample is equal-allocation, so this is a statement about these 200 games, not an estimate for the whole match. Candidate wins account for 4 of the 6 (4 of 100 sampled), candidate losses for 2 (2 of 100). `both_engines` has 3 and `loser_only` has 3, so a winner that also reported +4.00 did not protect against a wrong call.

All six were re-searched to depth 40 and for 45 seconds with 8 threads. No final position turned out to favour the adjudicated loser; they are all roughly equal or drawn:

| Game (PGN start, India time) | Position | Engines said (loser) | Stockfish depth 24 / 40 / 45 s (loser) |
|---|---|---|---|
| 2146 (13:06:51) | K+N+N v K | -6.6 to -7.6 | -25 / 0 / -6 |
| 2529 (13:21:40) | K+B+B v K+B | -4.1 to -4.6 | -20 / 0 / 0 |
| 2785 (13:31:49) | B+2P v B+P, pawns on g6 and h7 | -5.7 to -6.3 | -71 / -42 / -78 |
| 3962 (14:17:57) | B+P v P | -4.2 to -6.0 | -91 / -107 / -104 |
| 4321 (14:31:57) | R v R+B | -4.0 to -4.1 | -39 / 0 / 0 |
| 4410 (14:35:30) | 16 pieces, queen and knights | -4.8 to -6.8 | 0 / 0 / 0 |

These are mostly drawn endings where a piece of extra material has no winning plan (two knights cannot force mate, R v R+B is a known draw, and so on), plus one 16-piece position I did not look into beyond Stockfish's verdict. Both engines share the same classical evaluation, which scores the extra material highly, so having both agree is not independent evidence. That is also why the `both_engines` stratum did no better than `loser_only`.

Whether these adjudications changed a game result cannot be known from this sample, because the games were stopped before they were played out. They are the case where a draw is counted as a win for the engine that is ahead on material. Because the engines are the same program apart from SEE, I expect this to add the same noise to both arms and not a bias toward one. I did not test that, and I did not use the sample to reinterpret the SPRT.

## Process notes

- The relative `-LogPath` and `-PgnPath` in the first set of commands were resolved against the .NET process directory, so the log and PGN were written under the user profile instead of the run folder. The run was unaffected. `sprt.ps1` now resolves them against the PowerShell location (4557869), and the files were copied into the run folder with SHA-256 verification.
- A first parser in the audit script mis-attributed moves that had no eval comment and showed 29 false "rule violations". It was fixed and all 3,664 games pass.
- The script initially aborted on the 5 unterminated games. It now skips them.
