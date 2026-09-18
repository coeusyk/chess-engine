package coeusyk.game.chess.core.selfplay.vspr;

/**
 * The fixed, 120-byte V1 VSPR file header (DR-220 section 4) -- run-level {@code GameConfig}
 * plus producer identity. {@code runId} and {@code generatorNetworkSha256} are raw wire bytes;
 * callers comparing them must use {@link java.util.Arrays#equals(byte[], byte[])}, not this
 * record's generated {@code equals} (array fields compare by reference, per Java record
 * semantics, not by content).
 */
public record VsprHeader(
        int formatVersion,
        byte[] runId,
        long createdAtEpochSeconds,
        String generatorNetworkUuid,
        byte[] generatorNetworkSha256,
        String engineBuildId,
        String generatorNetworkPath,
        int maxPlies,
        SearchBudgetKind searchBudgetKind,
        long searchBudgetValue,
        boolean resetSearchStateBetweenGames,
        boolean candidatesPersisted,
        OpaqueConfig adjudicationConfig,
        OpaqueConfig diversityConfig) {
}
