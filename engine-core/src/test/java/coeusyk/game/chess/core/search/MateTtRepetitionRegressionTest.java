package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression fixtures and negative controls for #287: mate-range TT cutoffs must be
 * rejected while preserving TT hash-move ordering, ordinary centipawn cutoffs, and
 * fast genuine mates.
 */
class MateTtRepetitionRegressionTest {

    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int MATE_RANGE_MIN = MATE_SCORE - MAX_PLY;

    // Complete 125-ply move list from docs/engineering/investigations/2026-11-27-manual-games/games/2500_black.pgn.
    private static final List<String> GAME_2500_BLACK_MOVES = List.of(
            "g1f3", "d7d5", "g2g3", "c7c5", "f1g2", "b8c6", "e1g1", "e7e5",
            "d2d3", "g8f6", "b1c3", "f8e7", "c1g5", "d5d4", "g5f6", "e7f6",
            "c3e4", "f6e7", "c2c3", "e8g8", "c3d4", "c5d4", "a1c1", "d8b6",
            "d1c2", "c8e6", "e4c5", "c6b4", "c5a4", "b6b5", "c2c7", "e7d8",
            "c7e5", "b5a4", "f3d4", "b4d3", "b2b3", "d3e5", "b3a4", "e6a2",
            "c1c2", "a2c4", "g2b7", "a8b8", "b7g2", "b8b4", "f1c1", "c4d3",
            "c2c5", "b4d4", "c5e5", "d3a6", "e2e3", "d4a4", "g2f3", "a4a2",
            "f3e4", "d8b6", "e4c6", "f8c8", "e5e8", "c8e8", "c6e8", "a2d2",
            "e8a4", "g7g6", "a4c6", "a6d3", "c6d5", "d3c2", "d5b7", "a7a5",
            "b7c6", "d2d1", "c1d1", "c2d1", "h2h4", "a5a4", "g1g2", "d1b3",
            "c6a4", "b3a4", "g2h3", "h7h5", "g3g4", "a4d7", "f2f3", "b6e3",
            "h3g3", "d7c6", "f3f4", "c6e4", "g4h5", "g6h5", "f4f5", "g8g7",
            "g3h2", "e4c6", "h2g3", "c6e4", "g3h2", "e4c6", "h2g3", "e3d4",
            "f5f6", "g7f6", "g3f4", "f6e6", "f4g3", "e6f6", "g3f4", "f6e6",
            "f4g3", "d4e3", "g3h3", "e6f6", "h3h2", "f6g7", "h2g3", "g7f6",
            "g3h2", "f6g7", "h2g3"
    );

    private static Move legal(Board board, String uci) {
        int from = square(uci.charAt(0), uci.charAt(1));
        int to = square(uci.charAt(2), uci.charAt(3));
        return new MovesGenerator(board).getActiveMoves(board.getActiveColor()).stream()
                .filter(m -> m.startSquare == from && m.targetSquare == to)
                .findFirst()
                .orElseThrow(() -> new AssertionError("illegal move " + uci));
    }

    private static String toUci(Move move) {
        return "" + (char) ('a' + move.startSquare % 8) + (char) ('8' - move.startSquare / 8)
                + (char) ('a' + move.targetSquare % 8) + (char) ('8' - move.targetSquare / 8);
    }

    private static int square(char file, char rank) {
        return (8 - (rank - '0')) * 8 + file - 'a';
    }

    private static Searcher newConfiguredSearcher(TranspositionTable table) {
        Searcher searcher = new Searcher();
        searcher.setSharedTranspositionTable(table);
        searcher.setContempt(50);
        return searcher;
    }

    private static Searcher forcedSearcher(TranspositionTable table, Move move, int contempt) {
        Searcher searcher = new Searcher();
        searcher.setSharedTranspositionTable(table);
        searcher.setContempt(contempt);
        searcher.setSearchMoves(List.of(move));
        return searcher;
    }

    private static Board replayThroughPly(int targetPly) {
        Board board = new Board();
        for (int i = 0; i < targetPly; i++) {
            board.makeMove(legal(board, GAME_2500_BLACK_MOVES.get(i)));
        }
        return board;
    }

    private static TranspositionTable warmTableAcrossBlackTurns(int fromPly, int toPlyExclusive) {
        TranspositionTable table = new TranspositionTable(64);
        Board board = new Board();
        for (int ply = 0; ply < toPlyExclusive; ply++) {
            if (ply >= fromPly && ply % 2 == 1) {
                table.incrementGeneration();
                newConfiguredSearcher(table).iterativeDeepening(board, 13);
            }
            board.makeMove(legal(board, GAME_2500_BLACK_MOVES.get(ply)));
        }
        return table;
    }

    @Test
    void ply109WithWarmTableAndFullHistoryFindsKe5MateInsteadOfPrematureKf6() {
        TranspositionTable warmTable = warmTableAcrossBlackTurns(93, 109);
        Board ply109 = replayThroughPly(109);

        warmTable.incrementGeneration();
        SearchResult warmResult = newConfiguredSearcher(warmTable).iterativeDeepening(ply109, 13);
        assertEquals("e6e5", toUci(warmResult.bestMove()),
                "ply 109 must not reuse the ply-105 cached mate to play e6f6");
        assertEquals(MATE_SCORE - 11, warmResult.scoreCp());
        assertEquals(10, warmResult.depthReached());
        assertEquals(11, warmResult.principalVariation().size());

        // Same-history warm repeat retains the winning line.
        warmTable.incrementGeneration();
        SearchResult repeatResult = newConfiguredSearcher(warmTable).iterativeDeepening(replayThroughPly(109), 13);
        assertEquals("e6e5", toUci(repeatResult.bestMove()));
        assertEquals(MATE_SCORE - 11, repeatResult.scoreCp());

        // Same-history cold control agrees on the winning move and mate distance.
        SearchResult coldResult = newConfiguredSearcher(new TranspositionTable(64))
                .iterativeDeepening(replayThroughPly(109), 13);
        assertEquals("e6e5", toUci(coldResult.bestMove()));
        assertEquals(MATE_SCORE - 11, coldResult.scoreCp());
        assertEquals(10, coldResult.depthReached());
        assertEquals(11, coldResult.principalVariation().size());
    }

    @Test
    void ply123ForcedKf6AdjudicatesRepetitionWithFullHistoryAndMatesFromFenOnly() {
        TranspositionTable fullWarmTable = warmTableAcrossBlackTurns(93, 123);
        Board fullHistory123 = replayThroughPly(123);
        Move forcedKf6 = legal(fullHistory123, "g7f6");

        fullWarmTable.incrementGeneration();
        SearchResult fullHistoryResult = forcedSearcher(fullWarmTable, forcedKf6, 50)
                .iterativeDeepening(fullHistory123, 13);
        assertEquals("g7f6", toUci(fullHistoryResult.bestMove()));
        assertEquals(-50, fullHistoryResult.scoreCp());
        assertEquals(13, fullHistoryResult.depthReached());

        // With no pre-root history and an identical warm TT, the forced line is recomputed rather
        // than stopping at depth 4 on a cached M10 cutoff.
        TranspositionTable fenWarmTable = warmTableAcrossBlackTurns(93, 123);
        Board fenOnly123 = new Board(replayThroughPly(123).toFen());
        Move fenKf6 = legal(fenOnly123, "g7f6");
        fenWarmTable.incrementGeneration();
        SearchResult fenOnlyResult = forcedSearcher(fenWarmTable, fenKf6, 50)
                .iterativeDeepening(fenOnly123, 13);
        assertEquals("g7f6", toUci(fenOnlyResult.bestMove()));
        assertEquals(MATE_SCORE - 15, fenOnlyResult.scoreCp());
        assertEquals(13, fenOnlyResult.depthReached());
    }

    @Test
    void signedMateRangeTtSeedsDoNotProduceFalseCutoffsAcrossBounds() {
        for (int signed : new int[]{-99_997, 99_997}) {
            for (TTBound bound : TTBound.values()) {
                Board root = new Board();
                Move e2e4 = legal(root, "e2e4");
                TranspositionTable table = new TranspositionTable(1);
                root.makeMove(e2e4);
                table.store(root.getZobristHash(), legal(root, "e7e5").pack(), 8, signed, bound);
                root.unmakeMove();

                SearchResult result = forcedSearcher(table, e2e4, 50).iterativeDeepening(root, 2);
                assertEquals(e2e4.pack(), result.bestMove().pack());
                assertTrue(Math.abs(result.scoreCp()) < MATE_RANGE_MIN,
                        "mate seed " + signed + " (" + bound + ") leaked into root score " + result.scoreCp());
            }
        }
    }

    @Test
    void mateRangeTtMoveStillGuidesInteriorMoveOrderingWhenScoreCutoffIsRejected() {
        // Force one root move (b7b6) into a child at ply=1 where White has mate-in-1 (a1a8#) among
        // ~20 legal moves. Seed the ply=1 child with a deep EXACT mate score so alphaBeta invokes
        // applyTtBound and rejects the score cutoff, then compare nodes with the mating TT move
        // (a1a8) against a non-mating TT move (a1b1).
        String fen = "6k1/1p3ppp/8/8/8/8/5PPP/R5K1 b - - 0 1";
        Board setup = new Board(fen);
        Move forcedBlackMove = legal(setup, "b7b6");
        setup.makeMove(forcedBlackMove);
        long childHash = setup.getZobristHash();
        Move matingWhiteReply = legal(setup, "a1a8");
        Move nonMatingWhiteReply = legal(setup, "a1b1");

        TranspositionTable withWinningTtMove = new TranspositionTable(1);
        withWinningTtMove.store(childHash, matingWhiteReply.pack(), 8, 99_999, TTBound.EXACT);

        TranspositionTable withWrongTtMove = new TranspositionTable(1);
        withWrongTtMove.store(childHash, nonMatingWhiteReply.pack(), 8, 99_999, TTBound.EXACT);

        Board goodBoard = new Board(fen);
        SearchResult goodOrderResult = forcedSearcher(withWinningTtMove, legal(goodBoard, "b7b6"), 50)
                .searchDepth(goodBoard, 4);

        Board badBoard = new Board(fen);
        SearchResult badOrderResult = forcedSearcher(withWrongTtMove, legal(badBoard, "b7b6"), 50)
                .searchDepth(badBoard, 4);

        assertEquals(-99_998, goodOrderResult.scoreCp());
        assertEquals(-99_998, badOrderResult.scoreCp());
        assertTrue(goodOrderResult.principalVariation().size() >= 2,
                "rejected interior mate cutoff must recompute the PV");
        assertTrue(goodOrderResult.nodesVisited() < badOrderResult.nodesVisited(),
                "interior TT hash move must still improve move ordering when its mate score cutoff is rejected: "
                        + goodOrderResult.nodesVisited() + " vs " + badOrderResult.nodesVisited());
    }

    @Test
    void genuineFastMateAndValidCachedMateAndNegativeMateAndDrawControls() {
        // Genuine mate-in-1 still completes at depth 1.
        SearchResult mateInOne = new Searcher().iterativeDeepening(
                new Board("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1"), 6);
        assertEquals(99_999, mateInOne.scoreCp());
        assertEquals(1, mateInOne.depthReached());

        // Valid cached mate-in-2 is recomputed with its complete PV rather than a truncated 1-move PV.
        Board root = new Board("7Q/8/2K5/8/k7/8/8/8 w - - 0 1");
        Move queen = legal(root, "h8b2");
        root.makeMove(queen);
        Move king = legal(root, "a4a5");
        SearchResult losing = forcedSearcher(new TranspositionTable(1), king, 50).iterativeDeepening(root, 6);
        assertEquals(-99_998, losing.scoreCp());

        TranspositionTable warm = new TranspositionTable(1);
        warm.store(root.getZobristHash(), king.pack(), 6, losing.scoreCp(), TTBound.EXACT);
        root.unmakeMove();

        SearchResult cold = forcedSearcher(new TranspositionTable(1), queen, 50).iterativeDeepening(root, 6);
        SearchResult cached = forcedSearcher(warm, queen, 50).iterativeDeepening(root, 6);
        assertEquals(99_997, cold.scoreCp());
        assertEquals(cold.scoreCp(), cached.scoreCp());
        assertTrue(cached.principalVariation().size() >= 3,
                "cached mate-in-2 should recompute full PV, got " + cached.principalVariation().size());

        // Direct third-occurrence draw control with contempt 0.
        Board draws = new Board();
        for (String move : List.of("g1f3", "g8f6", "f3g1", "f6g8", "g1f3", "g8f6", "f3g1")) {
            draws.makeMove(legal(draws, move));
        }
        SearchResult drawResult = forcedSearcher(new TranspositionTable(1), legal(draws, "f6g8"), 0)
                .iterativeDeepening(draws, 4);
        assertEquals(0, drawResult.scoreCp());
    }
}
