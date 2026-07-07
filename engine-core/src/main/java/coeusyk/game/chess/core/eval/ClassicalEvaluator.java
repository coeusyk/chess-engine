package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;

/**
 * Wraps the handcrafted {@link Evaluator} as an {@link EvaluatorStrategy}.
 * Has no incremental per-ply state, so all lifecycle hooks stay no-op
 * (inherited from the interface).
 */
public class ClassicalEvaluator implements EvaluatorStrategy {

    private final Evaluator evaluator = new Evaluator();

    @Override
    public int evaluate(Board board) {
        return evaluator.evaluate(board);
    }

    /** Resizes the pawn hash table. See {@link Evaluator#setPawnHashSizeMb}. */
    public void setPawnHashSizeMb(int mb) {
        evaluator.setPawnHashSizeMb(mb);
    }

    /** Enables pawn-hash statistics tracking. Resets counters. */
    public void enablePawnHashStats() {
        evaluator.enablePawnHashStats();
    }

    /** Returns the pawn-hash hit rate [0.0, 1.0] since stats were last enabled. */
    public double getPawnHashHitRate() {
        return evaluator.getPawnHashHitRate();
    }
}
