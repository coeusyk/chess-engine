package coeusyk.game.chess.core.selfplay.vspr;

import java.util.OptionalLong;

/**
 * DR-220 section 5: the move {@code GameLoop} actually played at one ply. {@code move} is the
 * engine's own packed 16-bit encoding ({@code Move.of()}/{@code Move.from()}/{@code Move.to()}/
 * {@code Move.flag()}) -- deliberately not a {@code Move} object, so this codec never depends on
 * {@code engine-core}'s search/board types (DR-220 owns the wire contract, not search internals).
 */
public record PlayedMoveDecision(
        int move,
        SelectionMechanismKind selectionMechanismKind,
        String mechanismName,
        OptionalLong selectionSeed) {
}
