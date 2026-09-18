package coeusyk.game.chess.core.eval.nnue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR C-3 (issue #187): {@code NnueDebug} mode's sampled incremental-vs-rebuild
 * assertion (gated behind the {@code debugMode} constructor flag) must fire on
 * injected corruption within a bounded number of {@code onMake} calls, log the
 * divergence rather than throw, and do nothing at all when {@code debugMode} is
 * {@code false} — proving the zero-overhead-when-disabled requirement at the
 * behavioral level (no log output), not just by inspection of the gating code.
 */
class NnueEvaluatorDebugModeTest {

    private static final int SEED = 20260713;
    // > DEBUG_ASSERTION_SAMPLE_PERIOD (256) so at least one sample is guaranteed
    // regardless of how the corrupted-then-restarted self-play games land.
    private static final int TOTAL_ON_MAKE_CALLS = 300;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger nnueEvaluatorLogger;

    @BeforeEach
    void attachLogAppender() {
        nnueEvaluatorLogger = (Logger) LoggerFactory.getLogger(NnueEvaluator.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        nnueEvaluatorLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        nnueEvaluatorLogger.detachAppender(logAppender);
    }

    @Test
    void assertionFiresOnInjectedCorruptionWithinBoundedMoveCountAndNeverThrows() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network, true);

        assertDoesNotThrow(() -> driveOnMakeCallsWithPersistentCorruption(evaluator, TOTAL_ON_MAKE_CALLS),
                "the debug assertion must never let an exception propagate out of onMake");

        boolean desyncLogged = logAppender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("NNUE accumulator desync detected"));
        assertTrue(desyncLogged,
                "sampled rebuild assertion should have logged a desync warning within "
                        + TOTAL_ON_MAKE_CALLS + " onMake calls");
    }

    @Test
    void debugModeDisabledNeverLogsEvenWithCorruption() {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network, false);

        assertDoesNotThrow(() -> driveOnMakeCallsWithPersistentCorruption(evaluator, TOTAL_ON_MAKE_CALLS));

        assertTrue(logAppender.list.isEmpty(), "debugMode=false must never log a debug-assertion warning");
    }

    /**
     * Plays random legal self-play games (same restart-on-empty-legal-moves pattern as
     * {@link NnueIncrementalVsRebuildFuzzTest}) until {@code totalOnMakeCalls} real
     * {@code onMake} invocations have happened, re-injecting corruption after every
     * {@link NnueEvaluator#reset} (reset rebuilds from scratch and wipes it) so the
     * live accumulator stays corrupted regardless of how many game restarts occur —
     * {@code onMakeCount} lives on the evaluator instance and isn't reset by
     * {@code reset()}, so it keeps marching toward the sample period across restarts.
     */
    private static void driveOnMakeCallsWithPersistentCorruption(NnueEvaluator evaluator, int totalOnMakeCalls) {
        Random random = new Random(SEED);
        int onMakeCalls = 0;
        while (onMakeCalls < totalOnMakeCalls) {
            Board board = new Board();
            evaluator.reset(board);
            evaluator.corruptForTest(Piece.White, 0, (short) 9);

            while (onMakeCalls < totalOnMakeCalls) {
                ArrayList<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
                if (legalMoves.isEmpty()) {
                    break; // checkmate/stalemate reached — restart with a fresh game + fresh corruption
                }
                Move move = legalMoves.get(random.nextInt(legalMoves.size()));
                board.makeMove(move);
                evaluator.onMake(board, move.pack(), board.lastCapturedPiece());
                onMakeCalls++;
            }
        }
    }
}
