package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.selfplay.vspr.SearchBudgetKind;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Everything a Stage-3 generation run must have explicit, operator-supplied values for
 * (DR-E12-stage3-generator-selection.md section 4/10/17: "explicit exact generator identity,"
 * "no implicit latest-checkpoint selection," "mandatory, finite pilot budget"). There is
 * deliberately no builder or partial-construction path with defaulted identity/budget fields --
 * every field below is required at construction, validated in the compact constructor, and a
 * missing or invalid value fails closed before any network loads or any game plays.
 */
public record GeneratorConfig(
        Path networkPath,
        String expectedNetworkSha256,
        String expectedNetworkUuid,
        String engineBuildId,
        SearchBudgetKind searchBudgetKind,
        long searchBudgetValue,
        int maxPlies,
        int maxGames,
        Integer maxPositions,
        long seed,
        Path outputVsprPath,
        Path outputDecisionRecordPath) {

    // DR-E9 section 4.3's own traced worst-case single-search push (MAX_PLY-1 + MAX_CHECK_EXTENSIONS
    // + MAX_Q_DEPTH = 127 + 16 + 6 = 149, rounded up) -- a defensive runtime margin, not a claim
    // that this figure is exhaustively proven against every possible search path (aspiration-window
    // re-searches, singular extensions, etc. were not individually traced). #221 task instruction:
    // do not encode a derived bound as "mathematically proven safe" -- this is a conservative
    // guard checked at runtime before every root search (GameLoop), never relied on as a substitute
    // for ArrayIndexOutOfBoundsException-as-control-flow.
    public static final int SEARCH_OCCUPANCY_MARGIN = 150;

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public GeneratorConfig {
        if (networkPath == null) {
            throw new IllegalArgumentException("networkPath is required");
        }
        if (expectedNetworkSha256 == null || !SHA256_HEX.matcher(expectedNetworkSha256).matches()) {
            throw new IllegalArgumentException(
                    "expectedNetworkSha256 must be a 64-character lowercase hex SHA-256 digest");
        }
        if (expectedNetworkUuid == null || expectedNetworkUuid.isBlank()) {
            throw new IllegalArgumentException("expectedNetworkUuid is required");
        }
        if (engineBuildId == null || engineBuildId.isBlank()) {
            throw new IllegalArgumentException("engineBuildId is required");
        }
        if (searchBudgetKind == null) {
            throw new IllegalArgumentException("searchBudgetKind is required");
        }
        if (searchBudgetValue <= 0) {
            throw new IllegalArgumentException("searchBudgetValue must be positive");
        }
        if (maxPlies <= 0) {
            throw new IllegalArgumentException("maxPlies must be positive");
        }
        // Fail closed at construction, not at the first dangerous search call: a config whose
        // own maxPlies already leaves no room for SEARCH_OCCUPANCY_MARGIN below
        // Board.UNMAKE_POOL_SIZE is rejected before any game is attempted (section 5's guard,
        // enforced twice -- once here structurally, once per-search defensively in GameLoop).
        if (maxPlies + SEARCH_OCCUPANCY_MARGIN >= Board.UNMAKE_POOL_SIZE) {
            throw new IllegalArgumentException(
                    "maxPlies (" + maxPlies + ") + SEARCH_OCCUPANCY_MARGIN (" + SEARCH_OCCUPANCY_MARGIN
                            + ") must stay below Board.UNMAKE_POOL_SIZE (" + Board.UNMAKE_POOL_SIZE + ")");
        }
        // Mandatory, finite pilot budget (#221 section 9 / DR-E12 section 10) -- no unbounded
        // generation path exists at all, not merely "unbounded is discouraged."
        if (maxGames <= 0) {
            throw new IllegalArgumentException("maxGames must be positive (a bounded pilot is mandatory)");
        }
        if (maxPositions != null && maxPositions <= 0) {
            throw new IllegalArgumentException("maxPositions, if given, must be positive");
        }
        if (outputVsprPath == null) {
            throw new IllegalArgumentException("outputVsprPath is required");
        }
        if (outputDecisionRecordPath == null) {
            throw new IllegalArgumentException("outputDecisionRecordPath is required");
        }
    }
}
