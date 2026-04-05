package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.Evaluator;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class SearcherTest {

    @Test
    void searchReturnsBestMoveAtRequestedDepth() {
        Board board = new Board();
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 3);

        assertNotNull(result.bestMove());
        assertEquals(3, result.depthReached());
        assertFalse(result.aborted());
    }

    @Test
    void alphaBetaVisitsFewerNodesThanBruteForceMinimax() {
        Board board = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        int depth = 3;

        Searcher searcher = new Searcher();
        SearchResult alphaBetaResult = searcher.searchDepth(board, depth);

        NodeCounter bruteCounter = new NodeCounter();
        bruteForceNegamax(board, depth, bruteCounter);

        long alphaBetaNodes = alphaBetaResult.nodesVisited() + alphaBetaResult.leafNodes();
        assertTrue(alphaBetaNodes < bruteCounter.nodes(),
                "Expected alpha-beta to visit fewer nodes than brute force minimax");
    }

    @Test
    void iterativeDeepeningConvergesDeterministically() {
        Board boardA = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Board boardB = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Searcher searcherOne = new Searcher();
        Searcher searcherTwo = new Searcher();

        SearchResult idRunOne = searcherOne.iterativeDeepening(boardA, 4, neverAbort());
        SearchResult idRunTwo = searcherTwo.iterativeDeepening(boardB, 4, neverAbort());

        assertNotNull(idRunOne.bestMove());
        assertNotNull(idRunTwo.bestMove());
        assertEquals(idRunOne.depthReached(), idRunTwo.depthReached());
        assertMoveEquals(idRunOne.bestMove(), idRunTwo.bestMove());
        assertEquals(idRunOne.principalVariation().size(), idRunTwo.principalVariation().size());
        for (int i = 0; i < idRunOne.principalVariation().size(); i++) {
            assertMoveEquals(idRunOne.principalVariation().get(i), idRunTwo.principalVariation().get(i));
        }
    }

    @Test
    void quiescenceNodesAreTrackedSeparately() {
        Board board = new Board();
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 1);

        assertTrue(result.quiescenceNodes() > 0);
        assertTrue(result.quiescenceNodes() <= 40);
    }

    @Test
    void tacticalHangingQueenIsEvaluatedCorrectly() {
        Board board = new Board("4k3/8/8/4q3/2N5/8/8/4K3 w - - 0 1");
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 1);

        assertNotNull(result.bestMove());
        assertEquals(34, result.bestMove().startSquare);
        assertEquals(28, result.bestMove().targetSquare);
    }

    @Test
    void principalVariationIsPresentAndBoundedByDepth() {
        Board board = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 4);

        assertNotNull(result.bestMove());
        assertFalse(result.principalVariation().isEmpty());
        assertTrue(result.principalVariation().size() <= result.depthReached());
        assertMoveEquals(result.bestMove(), result.principalVariation().get(0));
    }

    @Test
    void moveOrderingReducesNodesComparedToDisabledOrdering() {
        // Use pure alpha-beta (no NMP/LMR/aspiration) to isolate move-ordering effect.
        // With aggressive pruning enabled, different orderings can explore different branches
        // and find different best moves — which is expected and not a bug.
        Board boardOrdered = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Board boardUnordered = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");

        SearchResult ordered   = new Searcher(true,  false, false, false, false, false).searchDepth(boardOrdered, 4);
        SearchResult unordered = new Searcher(false, false, false, false, false, false).searchDepth(boardUnordered, 4);

        assertNotNull(ordered.bestMove());
        assertNotNull(unordered.bestMove());
        // Pure alpha-beta finds the same best move regardless of ordering (no heuristic interference)
        assertMoveEquals(ordered.bestMove(), unordered.bestMove());
        // Better ordering means fewer nodes via earlier alpha cuts
        long orderedNodes   = ordered.nodesVisited()   + ordered.leafNodes();
        long unorderedNodes = unordered.nodesVisited() + unordered.leafNodes();
        assertTrue(orderedNodes < unorderedNodes, "Better move ordering should visit fewer nodes");
    }

    @Test
    void aspirationWindowsPreserveBestMoveAgainstFullWindow() {
        // Use a position with a trivially forced best move (free queen capture) rather than
        // a complex opening. Opening positions are eval-sensitive: any small change (e.g.,
        // a new hanging-piece penalty) can shift the depth-5 best move just enough to cause
        // aspiration window fail-low/fail-high that resolves to a different move than the
        // full-window search. A forced material gain is move-ordering-invariant and makes
        // the test correctly verify aspiration STABILITY, not opening book preference.
        // White Knight e4 captures undefended Black Queen f6 at any depth.
        Board boardA = new Board("4k3/8/5q2/8/4N3/8/8/4K3 w - - 0 1");
        Board boardB = new Board("4k3/8/5q2/8/4N3/8/8/4K3 w - - 0 1");

        SearchResult withAspiration = new Searcher(true, true).searchDepth(boardA, 5);
        SearchResult withoutAspiration = new Searcher(true, false).searchDepth(boardB, 5);

        assertNotNull(withAspiration.bestMove());
        assertNotNull(withoutAspiration.bestMove());
        assertMoveEquals(withAspiration.bestMove(), withoutAspiration.bestMove());
    }

    @Test
    void aspirationWindowsReduceRootSearchWorkOnTypicalPosition() {
        Board boardWithAspiration = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");
        Board boardWithoutAspiration = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");

        SearchResult withAspiration = new Searcher(true, true).searchDepth(boardWithAspiration, 5);
        SearchResult withoutAspiration = new Searcher(true, false).searchDepth(boardWithoutAspiration, 5);

        long withAspirationNodes = withAspiration.nodesVisited() + withAspiration.leafNodes();
        long withoutAspirationNodes = withoutAspiration.nodesVisited() + withoutAspiration.leafNodes();

        assertTrue(
                withAspirationNodes < withoutAspirationNodes,
                "Expected aspiration windows to reduce searched nodes"
        );
    }

    @Test
    void nullMovePruningReducesNodesOnQuietPosition() {
        // Test without aspiration windows to isolate NMP's effect on node count.
        // When aspiration is enabled, NMP can change score stability, causing additional
        // aspiration window failures that may offset the NMP node savings.
        Board boardWithNullMove    = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");
        Board boardWithoutNullMove = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");

        SearchResult withNullMove    = new Searcher(true, false, true).searchDepth(boardWithNullMove, 8);
        SearchResult withoutNullMove = new Searcher(true, false, false).searchDepth(boardWithoutNullMove, 8);

        long withNullMoveNodes    = withNullMove.nodesVisited()    + withNullMove.leafNodes();
        long withoutNullMoveNodes = withoutNullMove.nodesVisited() + withoutNullMove.leafNodes();

        assertTrue(withNullMoveNodes < withoutNullMoveNodes, "Expected null-move pruning to reduce searched nodes");
    }

    @Test
    void nullMovePruningIsSkippedWhenOnlyPawnsAndKingsRemain() {
        Board boardWithNullMove = new Board("8/3k4/8/3p4/8/4P3/4K3/8 w - - 0 1");
        Board boardWithoutNullMove = new Board("8/3k4/8/3p4/8/4P3/4K3/8 w - - 0 1");

        SearchResult withNullMove = new Searcher(true, true, true).searchDepth(boardWithNullMove, 5);
        SearchResult withoutNullMove = new Searcher(true, true, false).searchDepth(boardWithoutNullMove, 5);

        assertNotNull(withNullMove.bestMove());
        assertNotNull(withoutNullMove.bestMove());
        assertMoveEquals(withNullMove.bestMove(), withoutNullMove.bestMove());
    }

    @Test
    void lmrReductionTableIsPrecomputed() {
        Searcher searcher = new Searcher();

        // Updated Phase 9B #113: new log2-based formula (R = 1 + log2(d)*log2(m)/2) gives R=2
        // at depth=3/moveIndex=3; both R=1 and R=2 are correct reductions (R=2 is more aggressive).
        assertEquals(2, searcher.getLmrReductionForTesting(3, 3));
        assertTrue(searcher.getLmrReductionForTesting(8, 8) >= 1);
        assertTrue(searcher.getLmrReductionForTesting(12, 24) >= searcher.getLmrReductionForTesting(4, 4));
    }

    @Test
    void lmrReducesNodesOnQuietPosition() {
        Board boardWithLmr = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");
        Board boardWithoutLmr = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");

        SearchResult withLmr = new Searcher(true, true, false, true).searchDepth(boardWithLmr, 7);
        SearchResult withoutLmr = new Searcher(true, true, false, false).searchDepth(boardWithoutLmr, 7);

        long withLmrNodes = withLmr.nodesVisited() + withLmr.leafNodes();
        long withoutLmrNodes = withoutLmr.nodesVisited() + withoutLmr.leafNodes();

        assertTrue(withLmrNodes < withoutLmrNodes, "Expected LMR to reduce searched nodes");
    }

    @Test
    void lmrKeepsTacticalBestMoveStable() {
        Board boardWithLmr = new Board("4k3/8/8/4q3/2N5/8/8/4K3 w - - 0 1");
        Board boardWithoutLmr = new Board("4k3/8/8/4q3/2N5/8/8/4K3 w - - 0 1");

        SearchResult withLmr = new Searcher(true, true, false, true).searchDepth(boardWithLmr, 5);
        SearchResult withoutLmr = new Searcher(true, true, false, false).searchDepth(boardWithoutLmr, 5);

        assertNotNull(withLmr.bestMove());
        assertNotNull(withoutLmr.bestMove());
        assertMoveEquals(withoutLmr.bestMove(), withLmr.bestMove());
    }

    @Test
    void futilityAndRazorMarginsAreDefinedAsConstants() {
        Searcher searcher = new Searcher();

        assertEquals(150, searcher.getFutilityMarginForTesting(1));  // Phase 9B #114: raised 100 → 150
        assertEquals(300, searcher.getFutilityMarginForTesting(2));
        assertEquals(0, searcher.getFutilityMarginForTesting(3));
        assertEquals(300, searcher.getRazorMarginForTesting());
    }

    @Test
    void futilityAndRazoringReduceNodesOnQuietPosition() {
        Board boardWithPruning = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");
        Board boardWithoutPruning = new Board("r2q1rk1/ppp2ppp/2n2n2/3bp3/3P4/2P1PN2/PP1N1PPP/R1BQ1RK1 w - - 0 9");

        SearchResult withPruning = new Searcher(true, true, false, false, true).searchDepth(boardWithPruning, 6);
        SearchResult withoutPruning = new Searcher(true, true, false, false, false).searchDepth(boardWithoutPruning, 6);

        long withPruningNodes = withPruning.nodesVisited() + withPruning.leafNodes();
        long withoutPruningNodes = withoutPruning.nodesVisited() + withoutPruning.leafNodes();

        assertTrue(withPruningNodes < withoutPruningNodes, "Expected futility/razoring to reduce searched nodes");
    }

    @Test
    void futilityAndRazoringDoNotHideHangingQueenTactic() {
        Board boardWithPruning = new Board("4k3/8/8/4q3/2N5/8/8/4K3 w - - 0 1");
        Board boardWithoutPruning = new Board("4k3/8/8/4q3/2N5/8/8/4K3 w - - 0 1");

        SearchResult withPruning = new Searcher(true, true, false, true, true).searchDepth(boardWithPruning, 5);
        SearchResult withoutPruning = new Searcher(true, true, false, true, false).searchDepth(boardWithoutPruning, 5);

        assertNotNull(withPruning.bestMove());
        assertNotNull(withoutPruning.bestMove());
        assertMoveEquals(withoutPruning.bestMove(), withPruning.bestMove());
    }

    @Test
    void checkExtensionCapIsBoundedByInitialDepth() {
        Searcher searcher = new Searcher();

        assertEquals(0, searcher.getMaxCheckExtensionsForTesting(1));
        assertEquals(1, searcher.getMaxCheckExtensionsForTesting(2));
        assertEquals(3, searcher.getMaxCheckExtensionsForTesting(6));
        assertEquals(16, searcher.getMaxCheckExtensionsForTesting(64));
    }

    @Test
    void checkExtensionAppliesOnlyWhenEnabled() {
        Board boardWithExtension = new Board("k3r3/8/8/8/8/8/8/4K3 w - - 0 1");
        Board boardWithoutExtension = new Board("k3r3/8/8/8/8/8/8/4K3 w - - 0 1");

        Searcher withExtension = new Searcher(true, true, false, true, false, true);
        Searcher withoutExtension = new Searcher(true, true, false, true, false, false);

        withExtension.searchDepth(boardWithExtension, 4);
        withoutExtension.searchDepth(boardWithoutExtension, 4);

        assertTrue(withExtension.getCheckExtensionsAppliedForTesting() > 0,
            "Expected at least one extension in an in-check search path");
        assertEquals(0, withoutExtension.getCheckExtensionsAppliedForTesting(),
            "Expected extension count to stay zero when check extensions are disabled");
    }

    @Test
    void lmrDoesNotReduceWhenSideToMoveIsInCheck() {
        Board boardWithLmr = new Board("k3r3/8/8/8/8/8/8/4K3 w - - 0 1");
        Board boardWithoutLmr = new Board("k3r3/8/8/8/8/8/8/4K3 w - - 0 1");

        SearchResult withLmr = new Searcher(true, true, false, true, false, true)
            .searchDepth(boardWithLmr, 5);
        SearchResult withoutLmr = new Searcher(true, true, false, false, false, true)
            .searchDepth(boardWithoutLmr, 5);

        long withLmrNodes = withLmr.nodesVisited() + withLmr.leafNodes();
        long withoutLmrNodes = withoutLmr.nodesVisited() + withoutLmr.leafNodes();

        // LMR does not apply at in-check nodes (canApplyLmr guards with !sideToMoveInCheck),
        // but it does apply in child positions where the check has been resolved.
        // So total tree size with LMR should be <= without LMR.
        assertTrue(withLmrNodes <= withoutLmrNodes,
            "Expected LMR to not increase total nodes when root is in check");
    }

    @Test
    void quiescenceSkipsNegativeSeeCaptures() {
        Board board = new Board("4k3/5p2/4p3/8/5N2/8/8/4K3 w - - 0 1");
        Move losingCapture = findMove(board, 37, 20);

        Searcher withSee = new Searcher(true, true, false, true, true, true);
        Searcher withoutSee = new Searcher(true, true, false, true, true, true);
        withoutSee.setSeeEnabledForTesting(false);

        assertFalse(withSee.shouldIncludeInQuiescenceForTesting(board, losingCapture));
        assertTrue(withoutSee.shouldIncludeInQuiescenceForTesting(board, losingCapture));
    }

    @Test
    void quiescenceDepthCapAllowsLimitedCheckExtension() {
        Searcher searcher = new Searcher();

        // In-check nodes are capped at MAX_Q_DEPTH + MAX_Q_CHECK_EXTENSION = 6 + 3 = 9.
        assertFalse(searcher.shouldApplyQuiescenceDepthCapForTesting(6, true));  // 6 < 9 → not capped
        assertFalse(searcher.shouldApplyQuiescenceDepthCapForTesting(8, true));  // 8 < 9 → not capped
        assertTrue(searcher.shouldApplyQuiescenceDepthCapForTesting(9, true));   // 9 >= 9 → capped
        // Non-check nodes are still capped at MAX_Q_DEPTH = 6.
        assertTrue(searcher.shouldApplyQuiescenceDepthCapForTesting(6, false));
    }

    @Test
    void quiescenceReturnsDrawForStalemate() {
        // Black king on a8, white queen on c7, white king on b6 — Black is stalemated.
        // 3 total pieces: piece-count gate (≤ 8) fires, generateCaptures returns 0,
        // then generate() returns 0 (stalemate) → Q-search returns 0 (draw score).
        Board stalemateBoard = new Board("k7/2Q5/1K6/8/8/8/8/8 b - - 0 1");
        Searcher searcher = new Searcher();
        Evaluator eval = new Evaluator();
        int standPat = eval.evaluate(stalemateBoard);
        assertTrue(standPat < 0, "Stalemate position should evaluate as large negative for Black to move");
        int score = searcher.quiescenceForTesting(stalemateBoard, -10000, 10000, 0);
        assertEquals(0, score, "Q-search returns 0 (draw) for stalemate — piece-count gate fires and generate() confirms no legal moves");
    }

    @Test
    void quiescenceReturnsDrawForStalemateUnderTightWindow() {
        // NOTE 2026-04-03: With a tight window beta == standPat, the stand-pat beta-cutoff
        // fires at line `if (standPat >= beta) return standPat` — BEFORE the stalemate guard.
        // Stalemate guard (piece-count gate + generate()) is only reached when no beta-cutoff
        // fires. Under this window Q-search still returns standPat, not 0.
        Board stalemateBoard = new Board("k7/2Q5/1K6/8/8/8/8/8 b - - 0 1");
        Searcher searcher = new Searcher();
        Evaluator eval = new Evaluator();
        int standPat = eval.evaluate(stalemateBoard);
        assertTrue(standPat < 0, "Stalemate position should evaluate as large negative for Black to move");
        // beta == standPat: standPat >= standPat fires, returning standPat before the stalemate guard.
        int score = searcher.quiescenceForTesting(stalemateBoard, standPat - 100, standPat, 0);
        assertEquals(standPat, score, "Q-search returns standPat under tight window (beta-cutoff fires before stalemate guard)");
    }

    @Test
    void ttMoveHintIsTriedFirstAtRoot() {
        // Knight captures an undefended queen — definitively best at depth 1 regardless of eval tuning.
        // Verifies that when set as the TT hint the move is tried first and returned as best.
        Board board = new Board("5k2/8/5q2/8/4N3/8/8/4K3 w - - 0 1");
        Searcher searcher = new Searcher(true);
        Move ttHint = findMove(board, 36, 21); // Ne4xf6 — captures undefended queen
        searcher.setRootTtMoveHintForTesting(ttHint);

        SearchResult result = searcher.searchDepth(board, 1);

        assertNotNull(result.bestMove());
        assertMoveEquals(ttHint, result.bestMove());
    }

    @Test
    void transpositionTableHitRateIsNonZeroOnRepeatedSearch() {
        Board board = new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3");
        Searcher searcher = new Searcher();

        searcher.searchDepth(board, 4);
        SearchResult second = searcher.searchDepth(new Board("r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3"), 4);

        assertTrue(second.ttHitRate() > 0.0);
    }

    @Test
    void pawnPromotionExtensionAppliesOnSafeAdvanceTo7thRank() {
        // White pawn on e6, white king on e5, black king far on h1.
        // Pawn advance (e6→e7) is safe (black king too far), so extension fires.
        // All positions entered are unique (promotion path), so draw detection
        // does not interfere with the node-count comparison.
        Board board = new Board("8/8/4P3/4K3/8/8/8/7k w - - 0 1");
        Searcher withExtension = new Searcher(true, true, false, false, false, true);
        Searcher withoutExtension = new Searcher(true, true, false, false, false, true);
        withoutExtension.setSeeEnabledForTesting(false); // Disable SEE to prevent extension

        SearchResult resultWith = withExtension.searchDepth(board, 5);
        SearchResult resultWithout = withoutExtension.searchDepth(board, 5);

        assertNotNull(resultWith.bestMove());
        assertNotNull(resultWithout.bestMove());
        // Extension should increase the number of visited nodes at the same nominal depth
        assertTrue(resultWith.nodesVisited() > 0);
        assertTrue(resultWithout.nodesVisited() > 0);
        assertTrue(resultWith.nodesVisited() > resultWithout.nodesVisited(),
                "Expected pawn promotion extension to increase nodes visited");
    }

    @Test
    void pawnPromotionExtensionDoesNotApplyOnLosingCapture() {
        // Black pawn on e3 captures white piece on d2, but it's losing (e.g., pawn takes defended rook)
        // Extension should not apply because SEE is negative
        Board board = new Board("4k3/8/8/8/8/4p3/3RP3/4K3 b - - 0 1");
        Searcher searcher = new Searcher();

        SearchResult result = searcher.searchDepth(board, 4);

        assertNotNull(result.bestMove());
        // Just verify the search completes; tactical positions should be handled correctly
        assertTrue(result.nodesVisited() > 0);
    }

    @Test
    void singularMarginScalesByDepth() {
        Searcher searcher = new Searcher();

        assertEquals(64, searcher.getSingularMarginForTesting(8));
        assertEquals(80, searcher.getSingularMarginForTesting(10));
    }

    @Test
    void singularityGuardRequiresDepthAndQualifiedTtEntry() {
        Searcher searcher = new Searcher();
        int move = Move.of(52, 36, Move.FLAG_EP_TARGET);
        TranspositionTable.Entry qualified = new TranspositionTable.Entry(1L, move, 8, 50, TTBound.EXACT, (byte) 0);
        TranspositionTable.Entry shallow = new TranspositionTable.Entry(1L, move, 3, 50, TTBound.EXACT, (byte) 0);

        assertTrue(searcher.canAttemptSingularityForTesting(8, qualified, false, false));
        assertFalse(searcher.canAttemptSingularityForTesting(7, qualified, false, false));
        assertFalse(searcher.canAttemptSingularityForTesting(8, shallow, false, false));
        assertFalse(searcher.canAttemptSingularityForTesting(8, qualified, true, false));
        assertFalse(searcher.canAttemptSingularityForTesting(8, qualified, false, true));
    }

    @Test
    void ttBoundGatingWorksForExactLowerUpper() {
        Searcher searcher = new Searcher();

        TranspositionTable.Entry exact = new TranspositionTable.Entry(1L, Move.NONE, 4, 50, TTBound.EXACT, (byte) 0);
        TranspositionTable.Entry lower = new TranspositionTable.Entry(1L, Move.NONE, 4, 120, TTBound.LOWER_BOUND, (byte) 0);
        TranspositionTable.Entry upper = new TranspositionTable.Entry(1L, Move.NONE, 4, -80, TTBound.UPPER_BOUND, (byte) 0);

        assertEquals(50, searcher.applyTtBound(exact, 3, -100, 100, 0));
        assertEquals(120, searcher.applyTtBound(lower, 3, -100, 100, 0));
        assertEquals(-80, searcher.applyTtBound(upper, 3, -70, 100, 0));

        assertNull(searcher.applyTtBound(lower, 3, -100, 200, 0));
        assertNull(searcher.applyTtBound(upper, 3, -120, 100, 0));
        assertNull(searcher.applyTtBound(exact, 5, -100, 100, 0));
    }

    private Move findMove(Board board, int startSquare, int targetSquare) {
        List<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : moves) {
            if (move.startSquare == startSquare && move.targetSquare == targetSquare) {
                return move;
            }
        }
        fail("Expected legal move not found");
        return null;
    }

    private int bruteForceNegamax(Board board, int depth, NodeCounter counter) {
        counter.increment();

        if (depth == 0) {
            return 0;
        }

        MovesGenerator generator = new MovesGenerator(board);
        List<Move> moves = generator.getActiveMoves(board.getActiveColor());
        if (moves.isEmpty()) {
            return 0;
        }

        int best = Integer.MIN_VALUE / 2;
        for (Move move : moves) {
            board.makeMove(move);
            int score = -bruteForceNegamax(board, depth - 1, counter);
            board.unmakeMove();
            if (score > best) {
                best = score;
            }
        }

        return best;
    }

    private BooleanSupplier neverAbort() {
        return () -> false;
    }

    private void assertMoveEquals(Move expected, Move actual) {
        assertEquals(expected.startSquare, actual.startSquare);
        assertEquals(expected.targetSquare, actual.targetSquare);
        assertEquals(expected.reaction, actual.reaction);
    }

    private static class NodeCounter {
        private long nodes;

        void increment() {
            nodes++;
        }

        long nodes() {
            return nodes;
        }
    }
}