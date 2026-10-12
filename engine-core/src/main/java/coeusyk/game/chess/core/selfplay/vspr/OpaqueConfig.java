package coeusyk.game.chess.core.selfplay.vspr;

/**
 * DR-220 section 4's {@code opaqueConfig}: a schema-tagged, length-bounded blob for whichever
 * future document defines {@code adjudicationConfig}/{@code diversityConfig}'s concrete field
 * layout. VSPR bounds-checks {@code payload}'s length only and never interprets its contents.
 */
public record OpaqueConfig(int schemaId, byte[] payload) {
}
