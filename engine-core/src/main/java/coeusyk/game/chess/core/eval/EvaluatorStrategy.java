package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;

/**
 * Decouples {@code Searcher} from any specific evaluation implementation
 * (classical handcrafted eval, NNUE, or future strategies).
 *
 * <p>The lifecycle hooks let an implementation maintain incremental state
 * (e.g. an NNUE accumulator) across the search tree. They are called at
 * every make/unmake site in {@code Searcher} — including quiescence and
 * null-move search — so that no path can leave incremental state stale.
 * All hooks default to no-ops: {@link ClassicalEvaluator} has no incremental
 * state and is unaffected by them.
 *
 * <p>Implementation-specific configuration/reporting (e.g. classical's pawn
 * hash table) is deliberately kept off this interface — it is not a general
 * evaluator capability, and a no-op default here would return a fabricated
 * answer (e.g. a fake 0.0 hit rate) for strategies with no such concept.
 * Callers needing that access a concrete type directly (see
 * {@code Searcher}'s pawn-hash passthroughs).
 */
public interface EvaluatorStrategy {

    /** Returns the static evaluation of {@code board} from the side-to-move's perspective. */
    int evaluate(Board board);

    /** Rebuilds any incremental state from scratch. Called on new position / root change. */
    default void reset(Board board) {
    }

    /**
     * Called immediately after {@code board.makeMove(move)}. {@code move} is the packed
     * move int; {@code capturedPiece} is {@code board.lastCapturedPiece()} at the same
     * call site ({@link coeusyk.game.chess.core.models.Piece#None} if the move wasn't a
     * capture) — the one piece of information not otherwise derivable from {@code move}
     * plus post-move {@code board} state (en passant, promotion, and castling are all
     * fully derivable from {@code move} alone).
     */
    default void onMake(Board board, int move, int capturedPiece) {
    }

    /** Called immediately after {@code board.unmakeMove()} undoes the corresponding move. */
    default void onUnmake() {
    }

    /** Called immediately after {@code board.makeNullMove()}. */
    default void onMakeNull() {
    }

    /** Called immediately after {@code board.unmakeNullMove(state)}. */
    default void onUnmakeNull() {
    }
}
