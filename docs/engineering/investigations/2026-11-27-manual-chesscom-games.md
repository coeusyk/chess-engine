# Eight manual games with Vex against Chess.com bots

Vex Black drew the 2500 game by threefold repetition on 63.Kh2. Vex had started reporting mate from a winning position and then played several moves in milliseconds without closing out the game. In the 2500 White game, Vex started overvaluing its position before the decisive error at move 48.

The games used the Classical demonstration JAR built at `3496281`, SHA-256 `bcb6e395aea532d675a16309dc5f12fb1d8441af9839cfea2d0df47fa988ed7e`. The saved Cute Chess profile suggests Threads=1, Hash=64 and Contempt=50. The manual games have no original UCI transcript, so those options could not be verified. The [eight PGNs](2026-11-27-manual-games/games) retain every half-move and engine comment. Player labels were anonymized and exact wall-clock timestamps removed. The original files and Claude's scripts, logs, transcripts and diagnostic JAR are in the local ignored archive `tools/output/manual-games-2026-11-27/claude-evidence.tar.gz`, SHA-256 `512b9d7721d7643ce3d4fd39ae9ba2f0772e4450ac01df0f48c137ffd4f3dc22`. All archived files were compared byte for byte with their sources. No match was rerun to produce the counts below.

## Mate conversion with a warm table

Claude replayed all 93 half-moves through 47.gxh5 and had Vex convert as Black at 3 s against Stockfish defending at 150 ms. Stockfish ran with Threads=4 and Hash=256. The harness stopped when a draw could be claimed, including a threefold claim with the next move. The original game reached the third occurrence on the board.

| Arm at ply 93 | Threefold draws | Vex checkmates |
| --- | ---: | ---: |
| Demo JAR, persistent table | 8 | 3 |
| Diagnostic JAR, persistent table, guard off | 3 | 2 |
| Demo JAR, table cleared each turn or fresh process | 0 | 12 |
| Diagnostic JAR, persistent table, mate-range cutoffs disabled | 0 | 5 |

The 11/16 combined control runs include GUI-profile runs and Threads=8, Hash=256 runs. The 12 cleared/fresh conversions took 9 to 13 Vex moves. The five guarded runs took 9 to 10. A separate start after ply 97 mated once with each table policy. The [per-run results](2026-11-27-manual-games/conversion-results.csv) preserve these distinctions. In `conv93_persistent` the first Vex search took 3,017 ms, then the subsequent seven took 3 to 12 ms. The mate distance fell to 6 and then alternated between 6 and 7 as the bishop reversed between e3 and g1.

The source already adjudicates repetition at the probed node. A cutoff can skip a descendant that would draw under the current game history. The table key does not encode that history, and iterative deepening stops once the root receives a mate-range score. The intervention skipped all applicable mate-range TT returns after score conversion and left move ordering intact. It establishes that the cutoff path matters in these replays. The logs do not identify the entry that first hid a descendant repetition or distinguish stale score reuse from other tree changes caused by the guard. A short PV is consistent with a table cutoff and can also accompany a correct mate. See [the #287 plan](../../superpowers/plans/2026-11-27-mate-tt-repetition.md).

### Fixed-depth trace of the cutoff and the early stop

I replayed the original Black game at one thread, Hash=64, Contempt=50, maximum depth 13. The script searches each actual Black prefix starting at ply 93, keeps one table and then advances the PGN by the moves that were played. This controls the history and isolates the search decisions from timed Stockfish play. The diagnostic build was based on `0301ded`.

1. At root ply 105, after `53.f6+`, Vex examined `53...Kxf6`. Generation 7 stored key `4d4b309b56cd9ad3` as EXACT at effective depth 13 with node-relative score `-99986` and TT move `g3h3` (packed `3054`). White had no prior occurrence of the position in the reversible window because Black had just captured.
2. At root ply 109, after `55.Kg3`, Vex examined `55...Kf6`. The placement, rights and side to move gave it the same key, now with halfmove clock 4 and one earlier occurrence. The local repetition check passed. At iteration depth 2, ply 1 and effective depth 1, the entry returned `-99985` in both the null window `[-996,-995]` and the re-search window `[-1000000,-995]`. The root reported mate in eight and stopped with a one-move PV.
3. Suppressing that key at the traced probe kept the hash move and the rest of the table. The switch is scoped to the target root and the trace filter, which includes ply 1 and, through iteration depth 5, plies 2 to 4. At depth 2 the immediate replacement result for that child was a `-973` lower bound. At depth 4 the searched line `e6f6 g3h3 c6d7 h3g3 d7c6` reached the same key at ply 5 and the repetition guard adjudicated it. This was occurrence three with the PGN prefix. The FEN-only control also adjudicated the line, as a cycle inside the search. The line uses a voluntary Black bishop retreat, so it establishes a skipped adjudication without proving that the position after `55...Kf6` cannot be won.

| Arm at root ply 109 | Returned move | Depth | Score | PV moves |
| --- | --- | ---: | ---: | ---: |
| Full history, warm table | Kf6 | 2 | M8 | 1 |
| Warm table, target root reconstructed from FEN | Kf6 | 2 | M8 | 1 |
| Full history, suppress the key at the traced probe | Kf6 | 4 | M8 | 3 |
| Full history, keep cutoffs and bypass the mate stop | Kf5 | 13 | M6 | 11 |
| Full history, suppress the one key and bypass the stop | Kf5 | 13 | M6 | 11 |
| Full history, cold table at target | Ke5 | 10 | M6 | 11 |

With just the early stop bypassed, Kf6 and M8 persisted through depth 5 and Kf5 took over at depth 6. The second exact entry I tested, key `e0679513e43cac91` on ply 121, was immediately replaced by a mate coming from a deeper cutoff when bypassed. A key-specific patch would leave other paths to the same shallow stop.

I also forced the played `62...Kf6` at root ply 123. With the complete prefix, White's `63.Kh2` was adjudicated as a draw, returning the contempt score `-50` from the Black root at depth 13. With the same table and target reconstructed from FEN, that exact reply remained in a mate PV and the search stopped at depth 4 on M10. The unrestricted deterministic replay chooses Bd4 over the played Kf6 on that final Black turn. The exact ply-109 entry is established for this controlled replay. The original live search had no node trace, and no reverse case of a cached repetition score suppressing mate was established here.

Reproduce on a disposable checkout of `0301ded` with `git apply --unidiff-zero /path/to/trace-mate-tt.patch`, then package as below. With the working directory set to the fixture folder, run:

```sh
python3 trace_mate_tt.py /path/to/diagnostic.jar games/2500_black.pgn --target 109 --to-ply 109
python3 trace_mate_tt.py /path/to/diagnostic.jar games/2500_black.pgn --target 109 --to-ply 109 --key 4d4b309b56cd9ad3
python3 trace_mate_tt.py /path/to/diagnostic.jar games/2500_black.pgn --target 109 --to-ply 109 --ignore-stop
python3 trace_mate_tt.py /path/to/diagnostic.jar games/2500_black.pgn --target 109 --to-ply 109 --key 4d4b309b56cd9ad3 --ignore-stop
python3 trace_mate_tt.py /path/to/diagnostic.jar games/2500_black.pgn --target 123 --to-ply 123 --force g7f6
```

Add `--fen-only` for the no-history target control and `--from-ply 109 --cold` for the cold ply-109 control. Capture stderr separately from the JSONL on stdout. The raw results are in the ignored `mate-tt-trace/` directory beside the archive.

## Ponderhit timing

In a separate Cute Chess run, `go ponder movetime 2000` was sent at 2448 ms, `ponderhit` at 4451 ms and `bestmove g1f3 ponder b8c6` at 4452 ms. The next hit-to-bestmove interval was also 1 ms. Baseline Vex replaces the fixed budget with a clock allocation using zero clocks. In the manual PGNs, 90 of 411 Vex moves are annotated as 0.001 s. The missing engine annotations on some moves and exact timing of individual manual ponder hits remain unverified. A packaged-JAR probe at 700 ms measured 2 ms on the demo and 670 ms with the correction in [PR #335](https://github.com/coeusyk/chess-engine/pull/335).

## White endgame

At move 48, the fresh Vex search chose Kf1 at depth 20 with 0 cp. In the recovered Stockfish run, Nxc7 scored -106 cp and the position after Kf1 and ...Rd5 had a best White line at -372 cp. The later 40-second Vex searches at moves 49, 52 and 59 scored -56, -153 and -437 cp against Stockfish's -395, -496 and -753 cp. The [root samples](2026-11-27-manual-games/white-root-search.log.txt) and [long searches](2026-11-27-manual-games/white-long-search.log.txt) are excerpts from Claude's session record. These are search scores from finite searches and the engines use different score scales.

### Controlled comparisons

I used the demo JAR's `eval` breakdown and Stockfish 18 at depth 18, one thread, 64 MB and a cleared hash per position. The Stockfish binary hash is `65c1e4dade6102e4f8219be7d24181d25e7e1b6f039a20b3ae49488623ba61e5`. The full breakdowns are in `eval-term-probe.jsonl` beside the local archive. The synthetic move-52 comparisons preserve material and change a piece's square or the side to move. The two move-48 continuations are actual legal moves. Scores are White-relative centipawns. In the term columns, EG is the untapered endgame contribution and MG is the untapered middlegame contribution.

| Position | Vex static | Stockfish search | Passed pawns EG | Mobility EG | King safety MG |
| --- | ---: | ---: | ---: | ---: | ---: |
| Before move 48 | +50 | -92 | +129 | +13 | +15 |
| After 48.Kf1 | +40 | -251 | +129 | +15 | +86 |
| After 48.Nxc7 | +348 | -67 | +129 | +6 | +15 |
| Before move 52 | +9 | -295 | +129 | +13 | +92 |
| 52, White f2 to g2 | -63 | -36 | +70 | +11 | +79 |
| 52, Black f3 to f4 | +55 | 0 | +129 | +11 | +92 |
| 52, Black Rd3 to b3 | +3 | -260 | +129 | +11 | +92 |
| 52, White Kg1 to h2 | +23 | -308 | +129 | +17 | +99 |
| 52, Black to move | -25 | -379 | +129 | +13 | +92 |
| 52, Black to move, White f2 to g2 | -97 | -379 | +70 | +11 | +79 |

- **Advanced pawns and king restriction.** When the f3 pawn is moved back to f4, White can play gxf4 and Kg2 also becomes legal. Stockfish's score improves by 295 cp and Vex's static score improves by 46 cp. The passed-pawn term is unchanged. Vex counts passers using enemy-pawn masks and rank. That misses the value of a blocked f3 pawn that is held in place by e4 and denies the White king g2. Moving the king itself from g1 to h2 changes Vex by +14 and Stockfish by -13. At phase 6/24 the existing king-safety term contributes only a quarter of its MG value, and its EG contribution is zero. The pawn retreat changes both a tactical capture and the king's access, so it cannot price either feature alone.
- **Promotion threats.** Moving the f2 blocker to g2 drops the net passed-pawn term by 59 EG cp. This opens a lane for e4 to qualify as passed, while g2 still lies in f3's passed-pawn mask. White can now play gxf3. With Black to move the edit also makes ...f2+ and ...fxg2 legal, yet Stockfish chooses ...Rxa3 and returns -379 in both positions. The blocker and the side to move matter more than a bonus based on promotion distance alone.
- **Piece activity and the White passer.** Relocating the Black rook changes mobility by only -2 EG cp and leaves the rook-file term at -50 MG and 0 EG. Vex's total moves 6 cp against White and Stockfish's score moves 35 cp in White's favor. The original position gives White +129 EG cp in net passed pawns against -146 EG cp of material and piece-square terms, before other bonuses. The c6 passer and the knight on a6 are part of the compensation that must be priced against Black's activity. The large static gain after Nxc7 includes the captured bishop before ...Kxc7, so it is unsuitable as a direct measure of passer strength.

The largest measured gap in the controlled edits comes from the advanced f3 pawn and the responses it forces. The rook and king relocations alone give smaller shifts. This identifies evaluation blind spots in the position family without attributing the original search decision to a single weight. No evaluation code, weights or training data changed.

## Replay

Use JDK 21, Stockfish 18 and Python with `chess==1.11.2`. From the directory containing the scripts:

```sh
python3 probe_ponderhit.py /path/to/vex-3496281.jar
python3 probe_eval.py /path/to/vex-3496281.jar /path/to/stockfish
python3 convert.py /path/to/vex-3496281.jar /path/to/stockfish persistent
python3 convert.py /path/to/vex-3496281.jar /path/to/stockfish cleared
python3 convert.py /path/to/vex-3496281.jar /path/to/stockfish fresh
python3 convert.py /path/to/vex-3496281.jar /path/to/stockfish persistent --threads 1 --hash 64 --contempt 50
python3 convert.py /path/to/diagnostic.jar /path/to/stockfish persistent --skip-mate-tt
```

The diagnostic JAR can be rebuilt in a disposable checkout of `3496281` with `git apply skip-mate-tt.patch` (give the full path to the patch) and `mvn -B -pl engine-core,engine-uci -am package -DskipTests`. For an otherwise identical control, omit `--skip-mate-tt`. The runner logs every played move and checks legality. It supplies the entire board history to the UCI client on each turn. Timed paths can diverge between runs, especially with multiple threads. The original runner remains in the local archive for byte-for-byte recovery.
