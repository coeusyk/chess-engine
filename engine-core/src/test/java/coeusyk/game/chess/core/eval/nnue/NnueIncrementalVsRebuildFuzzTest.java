package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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

    @Test
    void incrementalAccumulatorMatchesFullRebuildOverRandomLegalGames() {
        System.out.println("[NnueFuzz] seed=" + SEED + " games=" + GAMES);
        NnueNetwork network = TestNetworks.synthetic(8);
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
                    assertAccumulatorMatchesRebuild(incremental, rebuildOracle, board,
                            "game " + game + " ply " + ply + " (null move)");
                    incremental.onUnmakeNull();
                    board.unmakeNullMove(nullState);
                }

                Move move = legalMoves.get(random.nextInt(legalMoves.size()));
                board.makeMove(move);
                incremental.onMake(board, move.pack(), board.lastCapturedPiece());
                pliesPlayed++;

                assertAccumulatorMatchesRebuild(incremental, rebuildOracle, board,
                        "game " + game + " ply " + ply + " move " + move.pack());
            }

            // Unwind the whole game, verifying onUnmake also stays exact at every step.
            for (int i = 0; i < pliesPlayed; i++) {
                incremental.onUnmake();
                board.unmakeMove();
                assertAccumulatorMatchesRebuild(incremental, rebuildOracle, board,
                        "game " + game + " unwind step " + i);
            }
        }
    }

    private static void assertAccumulatorMatchesRebuild(NnueEvaluator incremental, NnueEvaluator rebuildOracle,
                                                          Board board, String context) {
        rebuildOracle.reset(board);
        assertArrayEquals(rebuildOracle.currentAccumulator(Piece.White), incremental.currentAccumulator(Piece.White),
                "white accumulator diverged from full rebuild at " + context);
        assertArrayEquals(rebuildOracle.currentAccumulator(Piece.Black), incremental.currentAccumulator(Piece.Black),
                "black accumulator diverged from full rebuild at " + context);
    }
}
