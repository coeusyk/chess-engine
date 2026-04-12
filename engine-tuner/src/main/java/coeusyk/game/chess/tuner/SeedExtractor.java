package coeusyk.game.chess.tuner;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Extracts targeted seed EPD files from a large corpus by filtering positions
 * where specific eval feature groups are active.
 *
 * <p>Usage:
 * <pre>
 *   java -cp tuner.jar coeusyk.game.chess.tuner.SeedExtractor \
 *       &lt;corpus.epd&gt; &lt;outputDir&gt; [maxPerGroup]
 * </pre>
 *
 * Groups extracted:
 * <ul>
 *   <li>passed-pawn — positions with non-zero passed pawn features (idx 780–791)</li>
 *   <li>rook-7th — rook on 7th rank (idx 815–816)</li>
 *   <li>rook-open-file — rook on open file (idx 817–818)</li>
 *   <li>rook-semi-open — rook on semi-open file (idx 819–820)</li>
 *   <li>knight-outpost — knight outpost (idx 821–822)</li>
 *   <li>connected-pawn — connected pawn bonus (idx 823–824)</li>
 *   <li>backward-pawn — backward pawn penalty (idx 825–826)</li>
 *   <li>rook-behind-passer — rook behind passed pawn (idx 827–828)</li>
 *   <li>king-safety — at least one ATK piece in enemy king zone</li>
 * </ul>
 */
public final class SeedExtractor {

    private SeedExtractor() {}

    /** Feature group definition: name → set of target param indices. */
    private static final Map<String, int[]> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("passed_pawn", range(EvalParams.IDX_PASSED_MG_START, EvalParams.IDX_PASSED_EG_START + 6));
        GROUPS.put("rook_7th", new int[]{EvalParams.IDX_ROOK_7TH_MG, EvalParams.IDX_ROOK_7TH_EG});
        GROUPS.put("rook_open_file", new int[]{EvalParams.IDX_ROOK_OPEN_FILE_MG, EvalParams.IDX_ROOK_OPEN_FILE_EG});
        GROUPS.put("rook_semi_open", new int[]{EvalParams.IDX_ROOK_SEMI_OPEN_MG, EvalParams.IDX_ROOK_SEMI_OPEN_EG});
        GROUPS.put("knight_outpost", new int[]{EvalParams.IDX_KNIGHT_OUTPOST_MG, EvalParams.IDX_KNIGHT_OUTPOST_EG});
        GROUPS.put("connected_pawn", new int[]{EvalParams.IDX_CONNECTED_PAWN_MG, EvalParams.IDX_CONNECTED_PAWN_EG});
        GROUPS.put("backward_pawn", new int[]{EvalParams.IDX_BACKWARD_PAWN_MG, EvalParams.IDX_BACKWARD_PAWN_EG});
        GROUPS.put("rook_behind_passer", new int[]{EvalParams.IDX_ROOK_BEHIND_PASSER_MG, EvalParams.IDX_ROOK_BEHIND_PASSER_EG});
        // king-safety is handled separately via ATK attacker counts (not in sparse indices)
    }

    private static int[] range(int start, int endExclusive) {
        int[] r = new int[endExclusive - start];
        for (int i = 0; i < r.length; i++) r[i] = start + i;
        return r;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: SeedExtractor <corpus.epd> <outputDir> [maxPerGroup]");
            System.exit(1);
        }

        Path corpus = Paths.get(args[0]);
        Path outDir = Paths.get(args[1]);
        int maxPerGroup = args.length >= 3 ? Integer.parseInt(args[2]) : 5000;

        Files.createDirectories(outDir);

        System.out.printf("Loading corpus: %s%n", corpus);
        List<LabelledPosition> positions = PositionLoader.load(corpus);
        System.out.printf("Loaded %,d positions. Building features...%n", positions.size());

        // Read raw EPD lines for output (preserves original FEN + label format)
        List<String> rawLines = Files.readAllLines(corpus);

        // Build features
        List<PositionFeatures> features = PositionFeatures.buildList(positions);
        System.out.printf("Features built for %,d positions.%n", features.size());

        // Match raw lines to positions (PositionLoader may skip some lines)
        // Re-read and match by parsing — simpler: just rebuild the FEN from position
        // Actually, use rawLines indexed by the position order from loader.
        // PositionLoader reads sequentially, skipping unparseable lines. We need
        // a mapping from loaded position index back to the raw EPD line.
        // Simplest correct approach: reload lines and attempt parsing, keeping only the lines
        // that parsed successfully.
        List<String> parsedLines = new ArrayList<>(positions.size());
        for (String line : rawLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            // Check if this line looks like a valid EPD/FEN line
            if (trimmed.contains("/") && (trimmed.contains(" c0 ") || trimmed.contains(" c9 "))) {
                parsedLines.add(trimmed);
            }
        }
        // If counts don't match, fall back to generating FENs from positions
        boolean useParsedLines = (parsedLines.size() == positions.size());
        if (!useParsedLines) {
            System.out.printf("Warning: raw line count (%d) != position count (%d). Will regenerate FENs.%n",
                    parsedLines.size(), positions.size());
        }

        // Extract each group
        for (Map.Entry<String, int[]> entry : GROUPS.entrySet()) {
            String name = entry.getKey();
            int[] targetIndices = entry.getValue();
            Set<Integer> targetSet = new HashSet<>();
            for (int idx : targetIndices) targetSet.add(idx);

            List<Integer> matches = new ArrayList<>();
            for (int p = 0; p < features.size() && matches.size() < maxPerGroup; p++) {
                PositionFeatures pf = features.get(p);
                if (hasAnyIndex(pf, targetSet)) {
                    matches.add(p);
                }
            }

            Path outFile = outDir.resolve(name + "_seeds.epd");
            writeSeeds(outFile, matches, positions, parsedLines, useParsedLines);
            System.out.printf("%-25s → %,5d positions → %s%n", name, matches.size(), outFile);
        }

        // King safety group: check ATK attacker counts
        {
            List<Integer> matches = new ArrayList<>();
            for (int p = 0; p < features.size() && matches.size() < maxPerGroup; p++) {
                PositionFeatures pf = features.get(p);
                if (pf.wN > 0 || pf.wB > 0 || pf.wR > 0 || pf.wQ > 0 ||
                    pf.bN > 0 || pf.bB > 0 || pf.bR > 0 || pf.bQ > 0) {
                    matches.add(p);
                }
            }
            Path outFile = outDir.resolve("king_safety_seeds.epd");
            writeSeeds(outFile, matches, positions, parsedLines, useParsedLines);
            System.out.printf("%-25s → %,5d positions → %s%n", "king-safety", matches.size(), outFile);
        }

        System.out.println("Done.");
    }

    private static boolean hasAnyIndex(PositionFeatures pf, Set<Integer> targetIndices) {
        for (short idx : pf.indices) {
            if (targetIndices.contains((int) idx)) return true;
        }
        return false;
    }

    private static void writeSeeds(Path outFile, List<Integer> matchIndices,
                                   List<LabelledPosition> positions,
                                   List<String> parsedLines, boolean useParsedLines) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outFile.toFile()))) {
            for (int idx : matchIndices) {
                if (useParsedLines) {
                    bw.write(parsedLines.get(idx));
                } else {
                    LabelledPosition lp = positions.get(idx);
                    String resultStr;
                    if (lp.outcome() == 1.0) resultStr = "\"1-0\"";
                    else if (lp.outcome() == 0.0) resultStr = "\"0-1\"";
                    else resultStr = "\"1/2-1/2\"";
                    bw.write(lp.pos().fen() + " c0 " + resultStr);
                }
                bw.newLine();
            }
        }
    }
}
