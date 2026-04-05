package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.syzygy.DTZResult;
import coeusyk.game.chess.core.syzygy.NoOpSyzygyProber;
import coeusyk.game.chess.core.syzygy.SyzygyProber;
import coeusyk.game.chess.core.syzygy.WDLResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the Syzygy probe integration in {@link Searcher} behaves correctly:
 *
 * <ul>
 *   <li>The {@link NoOpSyzygyProber} reports unavailable so search is not affected.</li>
 *   <li>A stub prober returning LOSS causes the search to score the position at TB_LOSS_SCORE.</li>
 *   <li>A stub prober returning WIN causes the search to score the position at TB_WIN_SCORE.</li>
 *   <li>The in-check guard bypasses the WDL probe when the side to move is in check.</li>
 * </ul>
 *
 * Verified FEN positions (hand-checked against Lichess tablebase API):
 * <ul>
 *   <li>8/8/8/8/8/8/KQ6/7k w - - 0 1 → WDL = WIN  (KQK, White to move)</li>
 *   <li>8/8/8/8/8/8/KR6/7k w - - 0 1 → WDL = WIN  (KRK, White to move)</li>
 *   <li>8/8/8/8/8/8/1K6/7k w - - 0 1 → WDL = DRAW (KK,  White to move)</li>
 *   <li>8/8/8/8/8/8/KQ6/7k b - - 0 1 → WDL = LOSS (KQK, Black to move)</li>
 * </ul>
 */
class SyzygySearchTest {

    // TB_WIN_SCORE  = MATE_SCORE - 2 * MAX_PLY = 100_000 - 256 = 99_744
    // TB_LOSS_SCORE = -(MATE_SCORE - 2 * MAX_PLY) = -99_744
    private static final int TB_WIN_SCORE  =  99_744;
    private static final int TB_LOSS_SCORE = -99_744;

    // -----------------------------------------------------------------------
    // NoOpSyzygyProber availability
    // -----------------------------------------------------------------------

    @Test
    void noOpProberIsUnavailable() {
        assertFalse(new NoOpSyzygyProber().isAvailable(),
                "NoOpSyzygyProber must report unavailable so default search is untouched");
    }

    @Test
    void noOpProberReturnsInvalidWdl() {
        Board board = new Board("8/8/8/8/8/8/KQ6/7k w - - 0 1");
        assertFalse(new NoOpSyzygyProber().probeWDL(board).valid(),
                "NoOpSyzygyProber must return INVALID WDL result");
    }

    @Test
    void noOpProberReturnsInvalidDtz() {
        Board board = new Board("8/8/8/8/8/8/KQ6/7k w - - 0 1");
        assertFalse(new NoOpSyzygyProber().probeDTZ(board).valid(),
                "NoOpSyzygyProber must return INVALID DTZ result");
    }

    // -----------------------------------------------------------------------
    // WDL probe scoring — tablebase-lost position
    // -----------------------------------------------------------------------

    /**
     * KQK position with Black to move — tablebase loss for the side to move.
     * FEN: 8/8/8/8/8/8/KQ6/7k b - - 0 1 → expected WDL = LOSS for Black.
     *
     * The stub prober is colour-aware: it returns LOSS when Black is to move and WIN
     * when White is to move.  This correctly simulates the tablebase truth for this
     * position in a negamax tree — at depth ≥ 2 the probe fires on the child nodes
     * (White to move) and returns WIN for White, which negamax translates to a loss
     * score (−99 744) for Black at the root.
     */
    @Test
    void tbLostPositionScoresBelowMinusTenThousandAtDepthTwo() {
        // KQK, Black to move — this is a tablebase loss for Black
        Board board = new Board("8/8/8/8/8/8/KQ6/7k b - - 0 1");

        Searcher searcher = new Searcher();
        // Colour-aware stub: LOSS for Black-to-move, WIN for White-to-move.
        // AlwaysLossProber is intentionally NOT used here: it returns LOSS for every
        // node, so White-to-move child nodes report LOSS for White — negamax flips
        // that to +99 744 for Black, reversing the intended semantics.
        searcher.setSyzygyProber(new LossForBlackToMoveProber());
        // syzygyProbeDepth defaults to 1 so probe fires at depth ≥ 1

        SearchResult result = searcher.searchDepth(board, 2);

        assertTrue(result.scoreCp() <= -10_000,
                "TB-lost position must score ≤ −10 000 when Syzygy is enabled; got: " + result.scoreCp());
    }

    /**
     * KQK position with White to move — tablebase win for the side to move.
     * With a stub prober that always returns WIN, the search at depth 2 must
     * return a score ≥ +10 000.
     */
    @Test
    void tbWinPositionScoresAbovePlusTenThousandAtDepthTwo() {
        // KQK, White to move — tablebase win for White
        Board board = new Board("8/8/8/8/8/8/KQ6/7k w - - 0 1");

        Searcher searcher = new Searcher();
        searcher.setSyzygyProber(new AlwaysWinProber());

        SearchResult result = searcher.searchDepth(board, 2);

        assertTrue(result.scoreCp() >= 10_000,
                "TB-win position must score ≥ +10 000 when Syzygy is enabled; got: " + result.scoreCp());
    }

    // -----------------------------------------------------------------------
    // In-check guard: probe must not fire when side to move is in check
    // -----------------------------------------------------------------------

    /**
     * A 3-piece position where the side to move (White) is in check.
     * FEN: 4k3/8/8/8/8/8/8/r3K3 w - - 0 1 — White king on e1, Black rook on a1 (check).
     * The stub prober always returns LOSS, but the in-check guard must prevent the probe,
     * so the engine will search normally and find a non-losing move (king evades the check).
     * The score must therefore NOT be TB_LOSS_SCORE.
     */
    @Test
    void wdlProbeBypassedWhenSideToMoveIsInCheck() {
        // White is in check from Black's rook — Syzygy probe must be skipped
        Board board = new Board("4k3/8/8/8/8/8/8/r3K3 w - - 0 1");

        Searcher searcher = new Searcher();
        searcher.setSyzygyProber(new AlwaysLossProber());

        SearchResult result = searcher.searchDepth(board, 3);

        // The probe is skipped because White is in check; the engine finds a legal move
        // to escape check, so the score should not be TB_LOSS_SCORE.
        assertNotEquals(TB_LOSS_SCORE, result.scoreCp(),
                "WDL probe must be skipped when side to move is in check");
    }

    // -----------------------------------------------------------------------
    // Graceful degradation: NoOp prober produces identical search to baseline
    // -----------------------------------------------------------------------

    /**
     * With the default NoOp prober the search result for a tactical position must
     * be identical to a search performed with Syzygy explicitly disabled.
     */
    @Test
    void searchUnchangedWithNoOpProber() {
        String fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/2N5/PPPP1PPP/R1BQK1NR b KQkq - 2 3";
        Board boardA = new Board(fen);
        Board boardB = new Board(fen);

        Searcher withNoOp = new Searcher();
        // default NoOpSyzygyProber already in place — no extra setup needed

        Searcher withExplicitNoOp = new Searcher();
        withExplicitNoOp.setSyzygyProber(new NoOpSyzygyProber());

        SearchResult a = withNoOp.searchDepth(boardA, 4);
        SearchResult b = withExplicitNoOp.searchDepth(boardB, 4);

        assertEquals(a.scoreCp(), b.scoreCp(),
                "Search score must be identical with default vs explicit NoOp prober");
    }

    // -----------------------------------------------------------------------
    // #126: KBN vs K — Syzygy probe verification
    // -----------------------------------------------------------------------

    /**
     * Confirms KBN vs K (4 pieces) is within the default 5-piece tablebase limit so the
     * WDL probe fires at the root when Syzygy is active.
     *
     * <p>FEN: {@code 8/8/8/8/8/8/8/KBN1k3 w - - 0 1}
     * White: Ka1, Bb1, Nc1 | Black: ke1 — 4 pieces total.
     * The probe infrastructure is verified via {@code AlwaysWinProber}; actual Syzygy
     * files are not required for this test.</p>
     */
    @Test
    void kbnVsKPieceCountWithinTablebases() {
        Board kbnVsK = new Board("8/8/8/8/8/8/8/KBN1k3 w - - 0 1");
        long occupancy = kbnVsK.getAllOccupancy();
        int pieceCount = Long.bitCount(occupancy);
        assertTrue(pieceCount <= new coeusyk.game.chess.core.syzygy.NoOpSyzygyProber().getPieceLimit(),
                "KBN vs K (4 pieces) must be within the default Syzygy piece limit, got " + pieceCount);
    }

    /**
     * Simulates Syzygy returning WDL=WIN for the KBN vs K position.
     * The engine must propagate the tablebase win score (≥ +10 000 cp) to the root.
     *
     * <p>This tests the WDL-in-alphaBeta plumbing for 4-piece positions. The
     * {@code AlwaysWinProber} acts as a stand-in for a real 4-piece Syzygy probe
     * returning WIN for White to move.</p>
     */
    @Test
    void kbnVsKReturnsWinScoreWithSyzygyEnabled() {
        Board kbnVsK = new Board("8/8/8/8/8/8/8/KBN1k3 w - - 0 1");
        Searcher searcher = new Searcher();
        searcher.setSyzygyProber(new AlwaysWinProber());

        SearchResult result = searcher.searchDepth(kbnVsK, 2);

        assertTrue(result.scoreCp() >= 10_000,
                "KBN vs K with Syzygy enabled must return TB win score (>= 10 000), got: " + result.scoreCp());
    }

    /**
     * Without Syzygy (NoOpSyzygyProber), the engine uses classical eval for KBN vs K.
     * The score should be positive (White has Bishop + Knight advantage) but NOT a
     * guaranteed win within typical search depths — KBN vs K requires ~33 moves of
     * optimal play, beyond the horizon of classical search.
     *
     * <p>Documents the expected behaviour at CCRL when Syzygy files are not installed:
     * the engine will have a positive score but is unlikely to convert the endgame.</p>
     */
    @Test
    void kbnVsKWithoutSyzygyReturnsPositiveButNotTablebasisScore() {
        Board kbnVsK = new Board("8/8/8/8/8/8/8/KBN1k3 w - - 0 1");
        Searcher searcher = new Searcher();
        // Default NoOpSyzygyProber — no Syzygy files available

        SearchResult result = searcher.searchDepth(kbnVsK, 6);

        // Classical eval should return positive score (White has B+N material advantage)
        // but must NOT reach TB_WIN_SCORE (that would mean Syzygy incorrectly fired)
        assertTrue(result.scoreCp() > 0,
                "KBN vs K without Syzygy should return positive material score, got: " + result.scoreCp());
        assertTrue(result.scoreCp() < 10_000,
                "KBN vs K without Syzygy must not return a tablebase win score — probe must be inactive");
    }

    // -----------------------------------------------------------------------
    // Stub probers
    // -----------------------------------------------------------------------

    /** Always returns WDL = LOSS (simulates a tablebase-lost position for the side to move). */
    private static class AlwaysLossProber implements SyzygyProber {
        @Override public WDLResult probeWDL(Board board) { return WDLResult.loss(); }
        @Override public DTZResult probeDTZ(Board board) { return DTZResult.INVALID; }
        @Override public boolean isAvailable() { return true; }
    }

    /** Always returns WDL = WIN (simulates a tablebase-won position for the side to move). */
    private static class AlwaysWinProber implements SyzygyProber {
        @Override public WDLResult probeWDL(Board board) { return WDLResult.win(); }
        @Override public DTZResult probeDTZ(Board board) { return DTZResult.INVALID; }
        @Override public boolean isAvailable() { return true; }
    }

    /**
     * Colour-aware stub that correctly models "Black is in a tablebase-lost position":
     * returns LOSS when Black is to move and WIN when White is to move.
     *
     * <p>In a negamax tree the WDL probe fires on <em>child</em> nodes (after the root
     * side has moved).  For a Black-to-move root, child nodes are White-to-move.  A
     * real tablebase would return WIN for White there; this stub replicates that so the
     * negamax negation correctly propagates −TB_WIN_SCORE (= TB_LOSS_SCORE) back to
     * Black at the root.</p>
     */
    private static class LossForBlackToMoveProber implements SyzygyProber {
        @Override
        public WDLResult probeWDL(Board board) {
            return board.getActiveColor() == Piece.White ? WDLResult.win() : WDLResult.loss();
        }
        @Override public DTZResult probeDTZ(Board board) { return DTZResult.INVALID; }
        @Override public boolean isAvailable() { return true; }
    }
}
