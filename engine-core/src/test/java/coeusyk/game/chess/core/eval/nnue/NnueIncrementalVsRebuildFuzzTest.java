package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-004's named safety net: the incrementally-maintained NNUE accumulator must
 * exactly equal a from-scratch rebuild after every move, over random legal games
 * covering every move type (quiet, capture, en passant, castling, promotion,
 * promotion+capture, null move). A single mismatch here is the dominant NNUE bug
 * class (silent accumulator desync) — PRD §"Developer Tooling".
 *
 * <p>PR C-5 (issue #189): {@code GAMES}/{@code SEED} are overridable via system
 * properties — defaults unchanged (fixed seed, 40 games), so the default and
 * PR-blocking behavior is identical to before this change. The nightly workflow
 * overrides both ({@code -Dfuzz.seed=$(date +%s) -Dfuzz.games=<larger>}) for the
 * plan's "unfixed seed, larger game count" nightly-only variant (Task 4) — the seed
 * actually used is printed so a red nightly run is reproducible via
 * {@code -Dfuzz.seed=<logged value>}.
 */
class NnueIncrementalVsRebuildFuzzTest {

    private static final int GAMES = Integer.getInteger("fuzz.games", 40);
    private static final int PLIES_PER_GAME = 60;
    private static final long SEED = Long.getLong("fuzz.seed", 20260707L);

    /**
     * 8 is the historical width and never reaches a SIMD loop iteration on a 16-lane machine
     * ({@code loopBound(8) == 0}). 256 runs whole vector iterations; 264 adds a scalar tail
     * after them on 16- and 32-lane hardware (#269).
     */
    @ParameterizedTest(name = "width {0}")
    @ValueSource(ints = {8, 256, 264})
    void incrementalAccumulatorMatchesFullRebuildOverRandomLegalGames(int width) {
        System.out.println("[NnueFuzz] seed=" + SEED + " games=" + GAMES + " width=" + width
                + " vectorAvailable=" + VectorCapabilities.AVAILABLE
                + " lanes=" + VectorCapabilities.PREFERRED_SHORT_LANES);
        requireVectorWhenQualified(width);
        NnueNetwork network = TestNetworks.synthetic(width);
        NnueEvaluator incremental = new NnueEvaluator(network);
        NnueEvaluator rebuildOracle = new NnueEvaluator(network);
        Random random = new Random(SEED);

        for (int game = 0; game < GAMES; game++) {
            Board board = new Board();
            incremental.reset(board);
            int pliesPlayed = 0;

            for (int ply = 0; ply < PLIES_PER_GAME; ply++) {
                ArrayList<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
                if (legalMoves.isEmpty()) {
                    break; // checkmate/stalemate reached — start a fresh game
                }

                if (ply % 7 == 0) {
                    incremental.onMakeNull();
                    Board.NullMoveState nullState = board.makeNullMove();
                    assertAccumulatorMatchesRebuild(incremental, rebuildOracle, network, board,
                            "game " + game + " ply " + ply + " (null move)");
                    incremental.onUnmakeNull();
                    board.unmakeNullMove(nullState);
                }

                Move move = legalMoves.get(random.nextInt(legalMoves.size()));
                board.makeMove(move);
                incremental.onMake(board, move.pack(), board.lastCapturedPiece());
                pliesPlayed++;

                assertAccumulatorMatchesRebuild(incremental, rebuildOracle, network, board,
                        "game " + game + " ply " + ply + " move " + move.pack());
            }

            // Unwind the whole game, verifying onUnmake also stays exact at every step.
            for (int i = 0; i < pliesPlayed; i++) {
                incremental.onUnmake();
                board.unmakeMove();
                assertAccumulatorMatchesRebuild(incremental, rebuildOracle, network, board,
                        "game " + game + " unwind step " + i);
            }
        }
    }

    /**
     * In the vector-qualified Maven build ({@code nnue.requireVector}) a missing incubator module
     * is a failure, not a silent scalar run. Without the property the scalar fallback stays a
     * supported configuration and the test still runs, just without SIMD coverage.
     */
    private static void requireVectorWhenQualified(int width) {
        if (!Boolean.getBoolean("nnue.requireVector")) {
            return;
        }
        assertTrue(VectorCapabilities.AVAILABLE,
                "nnue.requireVector is set but jdk.incubator.vector did not resolve; "
                        + "the wide-width cases would silently exercise only the scalar path");
        if (width >= 256) {
            assertTrue(width >= VectorCapabilities.PREFERRED_SHORT_LANES && VectorCapabilities.PREFERRED_SHORT_LANES > 0,
                    "width " + width + " must cover at least one vector register so the SIMD loop body runs");
        }
    }

    private static void assertAccumulatorMatchesRebuild(NnueEvaluator incremental, NnueEvaluator rebuildOracle,
                                                          NnueNetwork network, Board board, String context) {
        rebuildOracle.reset(board);
        assertArrayEquals(rebuildOracle.currentAccumulator(Piece.White), incremental.currentAccumulator(Piece.White),
                "white accumulator diverged from full rebuild at " + context);
        assertArrayEquals(rebuildOracle.currentAccumulator(Piece.Black), incremental.currentAccumulator(Piece.Black),
                "black accumulator diverged from full rebuild at " + context);
        // rebuildOracle shares the evaluator's vector-or-scalar dispatch, so a vector bug would
        // appear on both sides and cancel. This oracle is plain scalar code, independent of it.
        assertArrayEquals(scalarRebuild(network, board, Piece.White), incremental.currentAccumulator(Piece.White),
                "white accumulator diverged from the scalar-only rebuild at " + context);
        assertArrayEquals(scalarRebuild(network, board, Piece.Black), incremental.currentAccumulator(Piece.Black),
                "black accumulator diverged from the scalar-only rebuild at " + context);
    }

    private static short[] scalarRebuild(NnueNetwork network, Board board, int perspectiveColor) {
        short[] acc = network.ftBiases().clone();
        short[] weights = network.ftWeights();
        FeatureExtractor.forEachPiece(board, (pieceColor, pieceType, square) ->
                NnueEvaluator.addFeatureScalar(acc,
                        FeatureExtractor.featureIndex(perspectiveColor, pieceColor, pieceType, square), weights));
        return acc;
    }
}
