package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.movegen.MovesGenerator;

/**
 * Captures-only quiescence search for the tuner.
 *
 * <p>Used to resolve hanging-piece noise in training positions: instead of
 * trusting the static eval on a position where a free capture is available,
 * we play out all captures to a quiet state before labelling.
 *
 * <p>This is a simplified version of the engine's main qsearch — no delta
 * pruning, no SEE filter, no check evasions. The dataset is expected to
 * contain only quiet-labelled positions, so most will exit at depth 0.
 *
 * <p>Score is always from <b>White's perspective</b>.
 */
final class TunerQuiescence {

    /** Maximum depth of the capture chain. */
    static final int MAX_DEPTH = 4;

    /** Reusable move buffer per depth (not thread-safe — each thread needs its own). */
    private final int[][] moveBuffers;

    TunerQuiescence() {
        moveBuffers = new int[MAX_DEPTH][];
        for (int i = 0; i < MAX_DEPTH; i++) {
            moveBuffers[i] = new int[256];
        }
    }

    /**
     * Runs captures-only qsearch from the given position and returns the
     * score from White's perspective.
     *
     * @param board  position (will be modified via make/unmake but restored)
     * @param params tuner parameter array
     * @return centipawn score from White's perspective
     */
    int search(Board board, double[] params) {
        int stm = board.getActiveColor();
        int score = quiesce(board, params, -999999, 999999, 0);
        // quiesce returns score from side-to-move perspective; convert to White
        return stm == coeusyk.game.chess.core.models.Piece.White ? score : -score;
    }

    /**
     * Alpha-beta qsearch over captures only, from the side-to-move perspective.
     */
    private int quiesce(Board board, double[] params, int alpha, int beta, int depth) {
        // Static eval from White's perspective; flip for STM
        int whiteEval = TunerEvaluator.evaluateStatic(board, params);
        int stm = board.getActiveColor();
        int standPat = stm == coeusyk.game.chess.core.models.Piece.White ? whiteEval : -whiteEval;

        if (depth >= MAX_DEPTH) {
            return standPat;
        }

        if (standPat >= beta) {
            return standPat;
        }
        if (standPat > alpha) {
            alpha = standPat;
        }

        int[] moves = moveBuffers[depth];
        int count = MovesGenerator.generateCaptures(board, moves);

        if (count == 0) {
            return standPat;
        }

        int bestScore = standPat;
        for (int i = 0; i < count; i++) {
            board.makeMove(moves[i]);
            int score = -quiesce(board, params, -beta, -alpha, depth + 1);
            board.unmakeMove();

            if (score > bestScore) {
                bestScore = score;
            }
            if (score > alpha) {
                alpha = score;
            }
            if (alpha >= beta) {
                break;
            }
        }

        return bestScore;
    }
}
