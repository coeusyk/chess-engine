package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.syzygy.NoOpSyzygyProber;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baseline endgame test: verifies that pure search + eval (no Syzygy tablebases)
 * correctly handles fundamental theoretical endgames — KQK, KRK, and KBNK.
 *
 * <p>Each test:
 * <ol>
 *   <li>Asserts the starting position is evaluated as clearly winning (scoreCp &gt; 50 cp)
 *       after a depth-20 search with {@link NoOpSyzygyProber} explicitly injected.</li>
 *   <li>Simulates move-by-move play at depth {@value #SIM_DEPTH} for both sides until
 *       checkmate occurs or the ply limit is reached, asserting that the game terminates
 *       by checkmate and not by stalemate, threefold repetition, or the 50-move rule.</li>
 * </ol>
 *
 * <p>Diagnostic intent: if KQK or KRK fail, the problem lies in eval/search and adding
 * Syzygy probing will not fix it. If they pass and KBNK fails, Syzygy tablebases would
 * be needed for reliable KBNK conversion.
 */
class EndgameHandlingTest {

    /** Depth used for the initial winning-position assessment. */
    private static final int EVAL_DEPTH = 20;

    /** Depth used for each ply in the move-by-move simulation. */
    private static final int SIM_DEPTH = 10;

    /** Maximum half-moves (plies) allowed for KQK simulation (KQK ≤ 10 moves optimal). */
    private static final int MAX_PLIES_KQK = 60;

    /** Maximum half-moves allowed for KRK simulation (KRK ≤ 16 moves optimal). */
    private static final int MAX_PLIES_KRK = 80;

    /**
     * Maximum half-moves allowed for KBNK simulation (KBNK ≤ 33 moves optimal).
     * A generous ceiling is used because at {@value #SIM_DEPTH} the engine may not
     * always find the minimum-distance path.
     */
    private static final int MAX_PLIES_KBNK = 200;

    // -----------------------------------------------------------------------
    // Test positions
    // -----------------------------------------------------------------------

    /** KQK: White to move, White has K + Q, Black has lone K. */
    private static final String[] KQK_FENS = {
        "8/8/8/8/8/Q1K5/8/7k w - - 0 1",   // WKc3 WQa3 BKh1
        "4k3/8/8/8/8/4K3/8/3Q4 w - - 0 1",  // WKe3 WQd1 BKe8
        "8/k7/8/8/8/3K4/8/1Q6 w - - 0 1",   // WKd3 WQb1 BKa7
    };

    /** KRK: White to move, White has K + R, Black has lone K. */
    private static final String[] KRK_FENS = {
        "7k/8/8/8/8/4K3/8/3R4 w - - 0 1",  // WKe3 WRd1 BKh8
        "4k3/8/8/8/8/4K3/8/3R4 w - - 0 1", // WKe3 WRd1 BKe8
        "k7/8/8/8/4K3/8/8/1R6 w - - 0 1",  // WKe4 WRb1 BKa8
    };

    /**
     * KBNK: White to move, White has K + B + N, Black has lone K.
     * All three positions use a light-squared bishop; the correct mating corners
     * are h1 and a8.
     */
    private static final String[] KBNK_FENS = {
        "8/8/8/4k3/8/8/3KBN2/8 w - - 0 1",  // WKd2 WBe2 WNf2 BKe5
        "8/8/3k4/8/8/3K4/2BN4/8 w - - 0 1", // WKd3 WBc2 WNd2 BKd6
        "8/8/8/8/3k4/8/3K4/3BN3 w - - 0 1", // WKd2 WBd1 WNe1 BKd4
    };

    // -----------------------------------------------------------------------
    // Test methods
    // -----------------------------------------------------------------------

    @Test
    void kqkFindsCheckmateWithoutSyzygy() {
        runEndgameTest("KQK", KQK_FENS, MAX_PLIES_KQK);
    }

    @Test
    void krkFindsCheckmateWithoutSyzygy() {
        runEndgameTest("KRK", KRK_FENS, MAX_PLIES_KRK);
    }

    @Test
    void kbnkFindsCheckmateWithoutSyzygy() {
        runEndgameTest("KBNK", KBNK_FENS, MAX_PLIES_KBNK);
    }

    // -----------------------------------------------------------------------
    // Implementation
    // -----------------------------------------------------------------------

    private record PositionResult(
            int index,
            String fen,
            boolean mateFound,
            int plies,
            String failure
    ) {}

    private void runEndgameTest(String label, String[] fens, int maxPlies) {
        List<PositionResult> results = new ArrayList<>();
        int failCount = 0;

        for (int i = 0; i < fens.length; i++) {
            PositionResult result = simulateEndgame(i + 1, fens[i], maxPlies);
            results.add(result);
            if (!result.mateFound()) failCount++;
        }

        printSummaryTable(label, results);

        assertTrue(failCount == 0,
                label + ": " + failCount + "/" + fens.length
                        + " position(s) failed to find checkmate. See table above.");
    }

    private void printSummaryTable(String label, List<PositionResult> results) {
        System.out.printf(Locale.US, "%n%-12s | %-40s | %-6s | %-13s | %s%n",
                "Position", "FEN (truncated)", "Result", "Moves to mate", "Pass/Fail");
        System.out.println("-".repeat(92));
        for (PositionResult r : results) {
            String fenShort = r.fen().length() > 38 ? r.fen().substring(0, 38) : r.fen();
            String movesToMate = r.mateFound() ? String.valueOf((r.plies() + 1) / 2) : "N/A";
            String passFail = r.mateFound() ? "PASS" : "FAIL: " + r.failure();
            System.out.printf(Locale.US, "%-12s | %-40s | %-6s | %-13s | %s%n",
                    label + "-" + r.index(), fenShort,
                    r.mateFound() ? "MATE" : "---",
                    movesToMate, passFail);
        }
        System.out.println();
    }

    /**
     * Runs the two-phase assessment for a single position:
     * <ol>
     *   <li>Depth-{@value #EVAL_DEPTH} eval check — position must score &gt; 50 cp.</li>
     *   <li>Move-by-move simulation at depth-{@value #SIM_DEPTH} for both sides.</li>
     * </ol>
     */
    private PositionResult simulateEndgame(int index, String fen, int maxPlies) {

        // --- Phase 1: initial assessment ---
        // NoOpSyzygyProber is the default, but inject explicitly to document the intent.
        Searcher evalSearcher = new Searcher();
        evalSearcher.setTranspositionTableSizeMb(16);
        evalSearcher.setSyzygyProber(new NoOpSyzygyProber());
        Board evalBoard = new Board(fen);
        evalBoard.setSearchMode(true);
        SearchResult evalResult = evalSearcher.searchDepth(evalBoard, EVAL_DEPTH);

        if (evalResult.scoreCp() < 50) {
            return new PositionResult(index, fen, false, 0,
                    "Initial eval too low: " + evalResult.scoreCp()
                            + " cp (expected > 50 for a winning endgame)");
        }

        // --- Phase 2: move-by-move simulation ---
        Searcher simSearcher = new Searcher();
        simSearcher.setTranspositionTableSizeMb(16);
        simSearcher.setSyzygyProber(new NoOpSyzygyProber());

        Board board = new Board(fen);
        board.setSearchMode(true);

        for (int ply = 0; ply < maxPlies; ply++) {

            if (board.isCheckmate()) {
                return new PositionResult(index, fen, true, ply, null);
            }
            if (board.isStalemate()) {
                String side = board.getActiveColor() == Piece.White ? "White" : "Black";
                return new PositionResult(index, fen, false, ply,
                        "Stalemate on ply " + ply + " (" + side + " to move)");
            }
            if (board.isThreefoldRepetition()) {
                return new PositionResult(index, fen, false, ply,
                        "Threefold repetition on ply " + ply);
            }
            if (board.isFiftyMoveRuleDraw()) {
                return new PositionResult(index, fen, false, ply,
                        "50-move rule draw on ply " + ply
                                + " (halfmove clock=" + board.getHalfmoveClock() + ")");
            }

            ArrayList<Move> legal = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
            if (legal.isEmpty()) {
                return new PositionResult(index, fen, false, ply,
                        "No legal moves on ply " + ply + " (missed terminal condition?)");
            }

            SearchResult sr = simSearcher.searchDepth(board, SIM_DEPTH);
            Move best = sr.bestMove();
            if (best == null) {
                return new PositionResult(index, fen, false, ply,
                        "Null bestMove returned on ply " + ply);
            }
            board.makeMove(best);
        }

        return new PositionResult(index, fen, false, maxPlies,
                "No mate within " + (maxPlies / 2) + " full moves");
    }
}
