# Phase 27. Search history and table contamination

### [2026-11-24] Repetition scores in the transposition table (#287)

**Built:** An opt-in measurement harness, `TtRepetitionContaminationProbe`, and an investigation write-up. The harness compares cold and warm table searches of a position with and without a repetition history, with same-context controls for plain warm-up noise.

**Decisions Made:** The search is not changed. The cross-context divergences match the controls in all four runs (for example 6 and 5 against 5 and 6 at depth 8 with contempt 100), so I have no measured effect to justify refusing cutoffs for scores whose subtree touched a repetition. #287 stays open as a null result at modest scale.

**Broke / Fixed:** My first design had no controls and would have counted ordinary warm-table differences as contamination. Adding the same-context columns removed that.

**Measurements:** 16 hand-picked positions at depth 8 gave 0, 0, 0, 0. 213 positions at depth 7 gave 3, 2 against controls 2, 2. 134 positions at depth 8 with contempt 100 gave 6, 5 against 5, 6 with a 1 MB table and 2, 1 against 1, 2 with a 16 MB table.
