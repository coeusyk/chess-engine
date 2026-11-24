# Does a repetition score in the transposition table change the search? (#287)

2026-11-24. #287 was split out of #259. The transposition table is keyed by the Zobrist hash, which says nothing about which earlier positions were on the path. `alphaBeta` returns a contempt-adjusted draw score for a repetition (`isRepetitionDraw`, `Searcher.java` just after the `shouldStopHard` check), and the stored score of an ancestor can carry that draw into a later visit where the repetition does not occur, or the reverse. #286 left it alone on purpose and said so in a comment next to the table probe. The issue asked for a deterministic reproduction first, a measurement of how often the result differs on repetition-heavy positions, and only then a decision between not storing scores whose subtree touched a repetition and leaving it.

## What I built

`TtRepetitionContaminationProbe` (engine-core tests, off unless `-Dprobe.run=true`). For each starting position it finds the first quiet four-ply cycle (two moves and their exact reversals that return to the same Zobrist hash) and plays it once, then plays the first move again. The start position has then occurred twice and the root position twice (counting itself), so a search from the root can reach positions that count as repetitions against that history. The same position without any history is built from the root FEN.

Each position gets six searches, all single-threaded and fixed depth:

- cold search of the position with history, and cold search of the position without history (the references);
- warm-up at depth minus two on the position without history, then search of the position with history ("history after plain warm-up"), and the reverse ("plain after history warm-up");
- two controls that warm up with the same context they are then searched in, history with history and plain with plain.

A result counts as different if the best move or the score differs from the cold reference. The controls matter. Any warm table changes the order and the cutoffs of the next search, so a warm search can differ from a cold one with no repetition involved at all. A repetition effect would show as the two cross-context columns diverging more often than the two same-context controls.

Positions: 16 hand-picked endgames and middlegames, plus seeded random playouts (Random seed 287, 20 to 119 plies, not in check, not drawn). Positions without a quiet cycle are skipped.

## Results

| Run | Positions | History after plain warm-up | Plain after history warm-up | Control, plain | Control, history |
|---|---:|---:|---:|---:|---:|
| Depth 8, contempt 0, 16 hand-picked | 16 | 0 | 0 | 0 | 0 |
| Depth 7, contempt 0, 1 MB table | 213 | 3 | 2 | 2 | 2 |
| Depth 8, contempt 100, 1 MB table | 134 | 6 | 5 | 5 | 6 |
| Depth 8, contempt 100, 16 MB table | 134 | 2 | 1 | 1 | 2 |

Contempt 100 was added so that a repetition scores something other than zero and an inherited draw would be easier to see. The 16 MB table was added because the default 1 MB table replaces entries quickly and could hide reuse.

In every run the cross-context columns diverge about as often as the controls, and the differences are small: mostly the same best move with a score that moves by a few to about 50 cp, in positions that are already decided. One position in the depth 7 run (`4q3/pr4r1/6n1/4p1kp/P3P3/3P4/2PN1R2/K1R5 b - - 3 58`) changed its best move after the plain warm-up, from `58>57` at -1031 cp to `51>34` at -996 cp, while its same-context control did not. That is a position lost by about ten pawns. It is one event among several that look like the same warm-table noise, and I did not try to attribute it to a repetition.

## What this does and does not show

It does not reproduce a case where a repetition-derived score in the table changes a result beyond what a differently warmed table changes anyway. I take that as evidence that the effect is rare or small at these depths, and not as proof that it is absent. Limits of the measurement:

- the history is one quiet four-ply cycle, so repetition decides few lines;
- depth 7 and 8, one thread, a warm-up of two plies shallower, and a table that lives for a single position pair, not a whole game;
- 134 to 213 positions per run and a handful of divergences, so a rate of a few percent cannot be excluded;
- no deterministic positive control exists. I did not find a position where the contaminated score is guaranteed to be reused. Building one means reaching the same node through two paths that differ in repetition status with the table entry surviving between them, which the search order makes fragile to construct by hand;
- the hypothesis that a warm-versus-cold divergence is evidence of contamination was wrong as a design: the first run without controls would have counted ordinary noise as contamination.

## Decision

I am not changing the search. The data does not support restricting stores or cutoffs for scores whose subtree touched a repetition: the fix would have to track that taint through every node and refuse some cutoffs, which costs nodes per second on every search, for an effect I could not measure above noise. The #286 position (accepted, table cleared on `ucinewgame`) stays. #287 stays open, because the issue asked for a reproduction and this is only a null result at modest scale.

What would change the decision: a position or a game record where a repetition-derived score is shown to decide a move, a larger measurement over real game histories (for example the archived Phase 21 PGNs replayed with the table kept across a game), or a deterministic construction of the two-path case. The harness takes `-Dprobe.depth`, `-Dprobe.random`, `-Dprobe.contempt`, `-Dprobe.cycles` and `-Dprobe.hash` for that.

Reproduce: `mvn -pl engine-core test -Dtest=TtRepetitionContaminationProbe -Dprobe.run=true -Dprobe.depth=8 -Dprobe.random=120 -Dprobe.contempt=100 -Dprobe.hash=16`.
