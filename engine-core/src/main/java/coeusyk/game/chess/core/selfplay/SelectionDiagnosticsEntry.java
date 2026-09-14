package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.selfplay.vspr.ScoreKind;

/**
 * Per-selection quality-cost diagnostics (#222 section 8) -- generation-time-only, never written
 * to VSPR (DR-220 §5's field list is not extended for this). {@link GameLoop} emits one of these
 * per ply to an optional sink; {@link SelfPlayCli} is the only current consumer, writing a local
 * CSV report alongside (not inside) the VSPR output when a stochastic selector is in use.
 */
public record SelectionDiagnosticsEntry(
        long gameId,
        int ply,
        int candidateCount,
        int chosenRank,
        ScoreKind rank1Kind,
        int rank1Value,
        ScoreKind chosenKind,
        int chosenValue,
        Integer cpLossFromRank1,
        Double selectionWeight,
        Double selectionProbability) {
}
