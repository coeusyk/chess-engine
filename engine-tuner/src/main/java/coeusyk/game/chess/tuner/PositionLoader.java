package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses position+outcome datasets in two formats:
 *
 * Format 1 — FEN with bracketed result:
 *   rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1 [1.0]
 *
 * Format 2 — EPD with c9 result annotation:
 *   rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2 c9 "1-0";
 *
 * Lines that cannot be parsed are silently skipped.
 */
public final class PositionLoader {

    private PositionLoader() {}

    /**
     * Loads all positions from the file.
     */
    public static List<LabelledPosition> load(Path file) throws IOException {
        return load(file, Integer.MAX_VALUE);
    }

    /**
     * Loads up to {@code maxPositions} positions from the file.
     * Stops reading as soon as the cap is reached, avoiding OOM on large corpora.
     *
     * @param file         dataset path
     * @param maxPositions maximum number of positions to load
     * @return parsed positions (size ≤ maxPositions)
     */
    public static List<LabelledPosition> load(Path file, int maxPositions) throws IOException {
        List<LabelledPosition> result = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                LabelledPosition lp = tryParse(line);
                if (lp != null) {
                    result.add(lp);
                    if (result.size() >= maxPositions) break;
                } else {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            System.out.printf("[PositionLoader] Skipped %,d unparseable lines%n", skipped);
        }
        return result;
    }

    private static LabelledPosition tryParse(String line) {
        try {
            // Format 1: ends with [1.0], [0.5], or [0.0]
            if (line.contains("[")) {
                return parseFormat1(line);
            }
            // Format 2: contains c9 keyword and ends with ;
            if (line.contains("c9")) {
                return parseFormat2(line);
            }
        } catch (Exception ignored) {
            // Skip unparseable lines
        }
        return null;
    }

    private static LabelledPosition parseFormat1(String line) {
        int bracketOpen  = line.lastIndexOf('[');
        int bracketClose = line.lastIndexOf(']');
        if (bracketOpen < 0 || bracketClose < bracketOpen) return null;

        String resultStr = line.substring(bracketOpen + 1, bracketClose).strip();
        double outcome   = parseOutcome(resultStr);
        if (outcome < 0) return null;

        String fenPart = line.substring(0, bracketOpen).strip();
        Board board = parseFen(fenPart);
        if (board == null) return null;

        return new LabelledPosition(board, outcome);
    }

    private static LabelledPosition parseFormat2(String line) {
        // Strip trailing semicolon
        String stripped = line.endsWith(";") ? line.substring(0, line.length() - 1).strip() : line;

        // Find c9 annotation
        int c9Idx = stripped.indexOf("c9");
        if (c9Idx < 0) return null;

        String fenPart    = stripped.substring(0, c9Idx).strip();
        String resultPart = stripped.substring(c9Idx + 2).strip();

        // Result is quoted: "1-0", "0-1", "1/2-1/2"
        resultPart = resultPart.replace("\"", "").strip();
        double outcome = parseOutcome(resultPart);
        if (outcome < 0) return null;

        Board board = parseFen(fenPart);
        if (board == null) return null;

        return new LabelledPosition(board, outcome);
    }

    /**
     * Converts FEN or EPD position to Board.
     * Appends "0 1" if halfmove/fullmove counters are absent (EPD format).
     */
    private static Board parseFen(String fen) {
        String[] parts = fen.split("\\s+");
        // EPD has 4 mandatory fields (position, color, castling, ep); FEN has 6
        String fullFen = parts.length >= 6 ? fen : fen + " 0 1";
        try {
            return new Board(fullFen);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses an outcome string.
     *
     * @return 1.0 (white win), 0.5 (draw), 0.0 (black win), or -1 on failure.
     */
    private static double parseOutcome(String s) {
        return switch (s.strip()) {
            case "1.0", "1-0"       -> 1.0;
            case "0.5", "1/2-1/2"   -> 0.5;
            case "0.0", "0-1"       -> 0.0;
            default -> {
                try { yield Double.parseDouble(s); }
                catch (NumberFormatException e) { yield -1.0; }
            }
        };
    }
}
