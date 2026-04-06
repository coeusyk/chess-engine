package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.eval.MopUp;
import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for issue #125: KQK / KRK repetition-draw fix.
 *
 * <p>Root causes diagnosed:
 * <ol>
 *   <li>MopUp gradient too weak — max 112 cp (10/4 multipliers) gives only ~14 cp per
 *       king-step improvement, insufficient to prevent cycling in a 1 040 cp material
 *       sea. Fixed: multipliers raised to 20/8 (max 224 cp → ~18 cp per step).</li>
 *   <li>Draw contempt missing — repetition and 50-move draws returned score 0 even
 *       when one side had a clear material advantage, making the winning side
 *       indifferent between a draw and a 1 040 cp advantage. Fixed: draw score is now
 *       {@code contemptScore(board)} which returns {@code -DRAW_CONTEMPT} for the
 *       winning side and {@code +DRAW_CONTEMPT} for the losing side.</li>
 * </ol>
 *
 * <p>Acceptance criteria verified here:
 * <ul>
 *   <li>contemptScore() is negative (-20) when the side to move has material advantage
 *       &gt; 300 cp — KQK (White to move) and KRK (White to move).</li>
 *   <li>contemptScore() is 0 for balanced positions (KK).</li>
 *   <li>contemptScore() is positive (+20) for the losing side (KQK, Black to move).</li>
 *   <li>searchDepth() returns a positive score (not 0) from KQK and KRK positions,
 *       confirming the engine does not immediately accept a draw.</li>
 *   <li>MopUp bonus for a corner king is ≥ 120 cp (was ≤ 60 before fix).</li>
 * </ul>
 */
class EndgameDrawAvoidanceTest {

    private final Searcher searcher = new Searcher();

    // KQK: 1 queen × weight 4 = phase 4; fully endgame.
    // KRK: 1 rook  × weight 2 = phase 2; fully endgame.
    // Both are well below the MopUp PHASE_THRESHOLD of 8.
    private static final int KQK_PHASE = 4;
    private static final int KRK_PHASE = 2;

    // ── contemptScore() unit tests ────────────────────────────────────────────

    /**
     * KQK, White to move. materialAdv (White's perspective) = +1040 > 300.
     * Winning side must receive negative contempt → hates the draw.
     */
    @Test
    void contemptScoreNegativeWhenWinningKQK() {
        Board kqk = new Board("4k3/8/8/8/8/8/8/4KQ2 w - - 0 1");
        assertEquals(-Searcher.DRAW_CONTEMPT, searcher.contemptScore(kqk),
                "KQK White to move: winning side should return -DRAW_CONTEMPT");
    }

    /**
     * KRK, White to move. materialAdv = +555 > 300.
     * Winning side must receive negative contempt.
     */
    @Test
    void contemptScoreNegativeWhenWinningKRK() {
        Board krk = new Board("4k3/8/8/8/8/8/8/4KR2 w - - 0 1");
        assertEquals(-Searcher.DRAW_CONTEMPT, searcher.contemptScore(krk),
                "KRK White to move: winning side should return -DRAW_CONTEMPT");
    }

    /**
     * KQK, Black to move. materialAdv (Black's perspective) = -1040 < -300.
     * Losing side must receive positive contempt → prefers the draw.
     */
    @Test
    void contemptScorePositiveForLosingSideKQK() {
        // Black has only a King; from Black's perspective materialAdv = 0 - 1040 = -1040
        Board kqkBlackToMove = new Board("4k3/8/8/8/8/8/8/4KQ2 b - - 0 1");
        assertEquals(Searcher.DRAW_CONTEMPT, searcher.contemptScore(kqkBlackToMove),
                "KQK Black to move: losing side should return +DRAW_CONTEMPT");
    }

    /**
     * KK position: both sides have material advantage = 0. Contempt must be 0.
     */
    @Test
    void contemptScoreZeroForBalancedKK() {
        Board kk = new Board("4k3/8/8/8/8/8/8/4K3 w - - 0 1");
        assertEquals(0, searcher.contemptScore(kk),
                "KK (balanced) should return contempt score of 0");
    }

    /**
     * Position with roughly equal minor-piece material (KNK vs K+bishop): inside the
     * ±300 cp threshold, contempt must return 0.
     */
    @Test
    void contemptScoreZeroNearBalancedMinorPieces() {
        // White: K + N (300), Black: K + B (300) → diff = 0 → contempt = 0
        Board knkb = new Board("4kb2/8/8/8/8/8/8/4KN2 w - - 0 1");
        assertEquals(0, searcher.contemptScore(knkb),
                "KNK vs KB (balanced minor pieces) should return contempt score of 0");
    }

    // ── MopUp weight validation ────────────────────────────────────────────────

    /**
     * After raising multipliers to 20/8, a corner king should generate an edge bonus
     * of at least 120 cp (CMD[corner] = 6 → 6 × 20 = 120).
     * This ensures the engine has a stronger gradient to push the losing king to the edge.
     */
    @Test
    void mopUpBonusIsHighForCornerKing() {
        // Black king on a8 (square 0, CMD = 6 → maximum edge distance)
        // With new multiplier: edge bonus = 6 × 20 = 120. Proximity is additive.
        Board kqkCorner = new Board("k7/8/8/8/8/8/8/4KQ2 w - - 0 1");
        int mopUp = MopUp.evaluate(kqkCorner, KQK_PHASE);
        assertTrue(mopUp >= 120,
                "MopUp with corner king (CMD=6 × 20 = 120 edge bonus) should be >= 120 cp, got " + mopUp);
    }

    /**
     * Corner king (CMD=6) must produce strictly higher mopup than edge king (CMD=3)
     * at the same phase. This verifies the doubled edge multiplier preserves ordering.
     */
    @Test
    void mopUpCornerKingExceedsEdgeKing() {
        Board corner = new Board("k7/8/8/8/8/8/8/4KQ2 w - - 0 1"); // a8, CMD=6
        Board edge   = new Board("4k3/8/8/8/8/8/8/4KQ2 w - - 0 1"); // e8, CMD=3
        assertTrue(MopUp.evaluate(corner, KQK_PHASE) > MopUp.evaluate(edge, KQK_PHASE),
                "Corner king should produce larger MopUp bonus than center-edge king");
    }

    // ── Search sanity: engine must not return draw score from KQK / KRK ─────────

    /**
     * From KQK at depth 4, the engine must return a score reflecting material
     * advantage (> 500 cp). A return of 0 would indicate the engine is treating this
     * as a draw — a regression that the contempt fix must prevent.
     */
    @Test
    void kqkSearchReturnsPositiveScore() {
        Board kqk = new Board("4k3/8/8/8/8/8/8/4KQ2 w - - 0 1");
        SearchResult result = searcher.searchDepth(kqk, 4);
        assertTrue(result.scoreCp() > 500,
                "KQK at depth 4 should return material advantage > 500 cp, got " + result.scoreCp());
    }

    /**
     * From KRK at depth 4, the engine must return a score reflecting rook advantage
     * (> 400 cp). Same reasoning as the KQK case above.
     */
    @Test
    void krkSearchReturnsPositiveScore() {
        Board krk = new Board("4k3/8/8/8/8/8/8/4KR2 w - - 0 1");
        SearchResult result = searcher.searchDepth(krk, 4);
        assertTrue(result.scoreCp() > 400,
                "KRK at depth 4 should return material advantage > 400 cp, got " + result.scoreCp());
    }

    /**
     * KQK with 50-move counter at 40 (halfmoveClock = 40). The engine must still
     * find a positive evaluation — contempt on the 50-move draw ensures it applies
     * urgency rather than accepting the impending draw as neutral.
     */
    @Test
    void kqkHighFiftyMoveClockReturnsPositiveScore() {
        Board kqk50 = new Board("8/8/4k3/8/8/8/4KQ2/8 w - - 40 1");
        SearchResult result = searcher.searchDepth(kqk50, 4);
        assertTrue(result.scoreCp() > 0,
                "KQK with 50-move counter at 40 should return positive score, got " + result.scoreCp());
    }

    /**
     * Regression test for reopened issue #125.
     * FEN is from an actual self-play game that drew by 3-fold repetition despite Black
     * winning by force (Stockfish: mate in 29).  The original bug excluded pawns from the
     * contemptScore() material count, making the advantage appear as exactly 300 cp (strict
     * threshold check 300 &gt; 300 returned false) instead of the true 400 cp.
     *
     * <p>Black: rook (555) + knight (300) + pawn e6 (100) = 955 cp
     * <p>White: rook (555) cp
     * <p>materialAdv for Black to move = 400 &gt;= 300 → should return -DRAW_CONTEMPT
     */
    @Test
    void contemptScoreCountsPawnsBlackWinning() {
        // Black to move: rook + knight + pawn vs rook — pawn must be counted
        Board pos = new Board("8/R7/4p3/8/2r1K3/5nk1/8/8 b - - 1 74");
        assertEquals(-Searcher.DRAW_CONTEMPT, searcher.contemptScore(pos),
                "Black to move with rook+knight+pawn advantage: pawn must be counted, contempt must fire");
    }
}
