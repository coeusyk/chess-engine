package coeusyk.game.chess.core.selfplay;

/**
 * The minimum generation decision/run artifact DR-E12-stage3-generator-selection.md section 12
 * requires, written once per run as a small JSON file adjacent to the VSPR output -- no existing
 * artifact in this repository owns these fields together (the VSPR header carries the network/
 * engine/config identity but not the eligibility-smoke evidence or selection-kind metadata; the
 * future #210-ingested dataset manifest carries dataset assessment, which this record explicitly
 * does not -- DR-E12 section 12: "Do not encode dataset assessment here. That occurs after #210
 * ingestion."). Hand-serialized (no JSON library dependency in {@code engine-core}, matching this
 * module's existing no-Spring/no-HTTP-dependency convention) since the shape is small and fixed.
 */
public record DecisionRecord(
        String selectionKind,
        String networkUuid,
        String networkSha256,
        String engineBuildId,
        String searchBudgetKind,
        long searchBudgetValue,
        int maxPlies,
        long seed,
        int maxGames,
        Integer maxPositions,
        boolean eligibilitySmokePassed,
        String eligibilityEvidence,
        int gamesAttempted,
        int gamesCompleted,
        int hardFailures,
        int unresolvedInfrastructureTerminations,
        String outputVsprPath,
        String outputVsprSha256,
        String vsprRunIdHex) {

    /** Minimal, dependency-free JSON serialization -- every field above is a plain string, number,
     * or boolean, so no general-purpose escaping/nesting machinery is needed. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        appendString(sb, "selectionKind", selectionKind, true);
        appendString(sb, "networkUuid", networkUuid, true);
        appendString(sb, "networkSha256", networkSha256, true);
        appendString(sb, "engineBuildId", engineBuildId, true);
        appendString(sb, "searchBudgetKind", searchBudgetKind, true);
        appendNumber(sb, "searchBudgetValue", searchBudgetValue, true);
        appendNumber(sb, "maxPlies", maxPlies, true);
        appendNumber(sb, "seed", seed, true);
        appendNumber(sb, "maxGames", maxGames, true);
        if (maxPositions != null) {
            appendNumber(sb, "maxPositions", maxPositions, true);
        } else {
            sb.append("  \"maxPositions\": null,\n");
        }
        appendBoolean(sb, "eligibilitySmokePassed", eligibilitySmokePassed, true);
        appendString(sb, "eligibilityEvidence", eligibilityEvidence, true);
        appendNumber(sb, "gamesAttempted", gamesAttempted, true);
        appendNumber(sb, "gamesCompleted", gamesCompleted, true);
        appendNumber(sb, "hardFailures", hardFailures, true);
        appendNumber(sb, "unresolvedInfrastructureTerminations", unresolvedInfrastructureTerminations, true);
        appendString(sb, "outputVsprPath", outputVsprPath, true);
        appendString(sb, "outputVsprSha256", outputVsprSha256, true);
        appendString(sb, "vsprRunIdHex", vsprRunIdHex, false);
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String key, String value, boolean comma) {
        sb.append("  \"").append(key).append("\": ");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(value)).append('"');
        }
        sb.append(comma ? ",\n" : "\n");
    }

    private static void appendNumber(StringBuilder sb, String key, long value, boolean comma) {
        sb.append("  \"").append(key).append("\": ").append(value).append(comma ? ",\n" : "\n");
    }

    private static void appendBoolean(StringBuilder sb, String key, boolean value, boolean comma) {
        sb.append("  \"").append(key).append("\": ").append(value).append(comma ? ",\n" : "\n");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
