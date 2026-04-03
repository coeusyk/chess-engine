package coeusyk.game.chess.uci.syzygy;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.syzygy.WDLResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that verify {@link OnlineSyzygyProber} returns the correct
 * WDL for trivially winning / drawn tablebase positions via the Lichess API.
 *
 * <p>These tests require internet access and are therefore disabled in CI.
 * Run manually to validate that Syzygy probing is wired up correctly.
 */
@Disabled("Requires internet: queries Lichess tablebase API (http://tablebase.lichess.ovh)")
class SyzygyProbingIntegrationTest {

    private static final OnlineSyzygyProber PROBER = new OnlineSyzygyProber(true);

    // -----------------------------------------------------------------------
    // Availability
    // -----------------------------------------------------------------------

    @Test
    void onlineProbersIsAvailableReturnsTrue() {
        assertTrue(PROBER.isAvailable(), "OnlineSyzygyProber should always report available");
    }

    // -----------------------------------------------------------------------
    // WDL probe — winning positions
    // -----------------------------------------------------------------------

    /**
     * KQK — trivially winning for White. White Ka2, Qb2, Black Kh1 (White to move).
     * FEN: 8/8/8/8/8/8/KQ6/7k w - - 0 1
     * Expected: WDL = WIN (tablebase category "win").
     */
    @Test
    void kqkIsWin() {
        Board board = new Board("8/8/8/8/8/8/KQ6/7k w - - 0 1");
        WDLResult result = PROBER.probeWDL(board);
        assertTrue(result.valid(), "WDL probe should return a valid result for KQK position");
        assertEquals(WDLResult.WDL.WIN, result.wdl(), "KQK should be a WIN for the side with the queen");
    }

    /**
     * KRK — winning for White. White Ka2, Rb2, Black Kh1 (White to move).
     * Same piece arrangement as the working KQK test but with rook instead of queen.
     * FEN: 8/8/8/8/8/8/KR6/7k w - - 0 1
     * Expected: WDL = WIN.
     */
    @Test
    void krkIsWin() {
        Board board = new Board("8/8/8/8/8/8/KR6/7k w - - 0 1");
        WDLResult result = PROBER.probeWDL(board);
        assertTrue(result.valid(), "WDL probe should return a valid result for KRK position");
        assertEquals(WDLResult.WDL.WIN, result.wdl(), "KRK should be a WIN for the side with the rook");
    }

    // -----------------------------------------------------------------------
    // WDL probe — drawn positions
    // -----------------------------------------------------------------------

    /**
     * KK — bare kings, trivially drawn.
     * FEN: 8/8/8/8/8/8/1K6/7k w - - 0 1
     * Expected: WDL = DRAW.
     */
    @Test
    void kkIsDraw() {
        Board board = new Board("8/8/8/8/8/8/1K6/7k w - - 0 1");
        WDLResult result = PROBER.probeWDL(board);
        assertTrue(result.valid(), "WDL probe should return a valid result for KK position");
        assertEquals(WDLResult.WDL.DRAW, result.wdl(), "KK (bare kings) should be a DRAW");
    }

    /**
     * KBK — king and bishop vs king, drawn (bishop cannot force mate).
     * White Ka2, Bb2, Black Kh1 (White to move).
     * Same piece arrangement as the working KQK test but with bishop instead of queen.
     * FEN: 8/8/8/8/8/8/KB6/7k w - - 0 1
     * Expected: WDL = DRAW.
     */
    @Test
    void kbkIsDraw() {
        Board board = new Board("8/8/8/8/8/8/KB6/7k w - - 0 1");
        WDLResult result = PROBER.probeWDL(board);
        assertTrue(result.valid(), "WDL probe should return a valid result for KBK position");
        assertEquals(WDLResult.WDL.DRAW, result.wdl(), "KBK should be a DRAW (insufficient material)");
    }

    // -----------------------------------------------------------------------
    // WDL probe — losing position (Black to move in losing position)
    // -----------------------------------------------------------------------

    /**
     * KQK — same material but Black to move (Black Kh1, Black to move facing KQ).
     * FEN: 8/8/8/8/8/8/KQ6/7k b - - 0 1
     * Expected: WDL = LOSS (for the side to move — Black).
     */
    @Test
    void kqkBlackToMoveIsLoss() {
        Board board = new Board("8/8/8/8/8/8/KQ6/7k b - - 0 1");
        WDLResult result = PROBER.probeWDL(board);
        assertTrue(result.valid(), "WDL probe should return a valid result for KQK (Black to move)");
        assertEquals(WDLResult.WDL.LOSS, result.wdl(), "KQK with Black to move should be LOSS for Black");
    }

    // -----------------------------------------------------------------------
    // Piece limit guard
    // -----------------------------------------------------------------------

    @Test
    void defaultPieceLimitIsFive() {
        assertEquals(5, PROBER.getPieceLimit(), "Default piece limit for OnlineSyzygyProber should be 5");
    }
}
