package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses position+outcome datasets in three formats:
 *
 * Format 1 — FEN with bracketed result:
 *   rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1 [1.0]
 *
 * Format 2 — EPD with c9 result annotation:
 *   rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2 c9 "1-0";
 *
 * Format 3 — EPD with c0 result annotation (quiet-labeled.epd / Ethereal format):
 *   rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2 c0 "1/2-1/2";
 *
 * Lines that cannot be parsed are silently skipped.
 */
public final class PositionLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PositionLoader.class);

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
            LOG.info(String.format("[PositionLoader] Skipped %,d unparseable lines", skipped));
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
                return parseFormat2(line, "c9");
            }
            // Format 3: contains c0 keyword (quiet-labeled.epd / Ethereal)
            if (line.contains("c0")) {
                return parseFormat2(line, "c0");
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
        TunerPosition pos = parseFen(fenPart);
        if (pos == null) return null;

        return new LabelledPosition(pos, outcome);
    }

    private static LabelledPosition parseFormat2(String line, String marker) {
        // Strip trailing semicolon
        String stripped = line.endsWith(";") ? line.substring(0, line.length() - 1).strip() : line;

        int markerIdx = stripped.indexOf(marker);
        if (markerIdx < 0) return null;

        String fenPart    = stripped.substring(0, markerIdx).strip();
        String resultPart = stripped.substring(markerIdx + 2).strip();

        // Result is quoted: "1-0", "0-1", "1/2-1/2"
        resultPart = resultPart.replace("\"", "").strip();
        double outcome = parseOutcome(resultPart);
        if (outcome < 0) return null;

        TunerPosition pos = parseFen(fenPart);
        if (pos == null) return null;

        return new LabelledPosition(pos, outcome);
    }

    /**
     * Converts FEN or EPD position to a compact {@link TunerPosition}.
     * Temporarily creates a full Board to extract bitboards, then discards it.
     * Appends "0 1" if halfmove/fullmove counters are absent (EPD format).
     */
    private static TunerPosition parseFen(String fen) {
        String[] parts = fen.split("\\s+");
        // EPD has 4 mandatory fields (position, color, castling, ep); FEN has 6
        String fullFen = parts.length >= 6 ? fen : fen + " 0 1";
        try {
            Board board = new Board(fullFen);
            return TunerPosition.from(board, fullFen);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Loads up to {@code maxPositions} labelled positions from a Stockfish-annotated
     * Texel corpus CSV generated by {@code tools/generate_texel_corpus.ps1}.
     *
     * <p>Expected CSV format (header row required):
     * <pre>
     *   fen,sf_cp,game_result
     *   "rnbqkbnr/... w KQkq -",47,0.5
     * </pre>
     *
     * <p>The {@code sf_cp} column is a raw Stockfish centipawn evaluation from White's
     * perspective. It is converted in-place to a pseudo-WDL outcome via
     * {@code sigmoid(sf_cp / K_SF)} where {@code K_SF = 340.0} (Stockfish
     * NormalizeToPawnValue=328 + 3.6% Vex-scale correction). The {@code game_result}
     * column is present in the file but not used by the tuner. Lines with unparseable
     * FENs or cp values are silently skipped.
     *
     * @param csvPath      path to the CSV file
     * @param maxPositions maximum number of positions to load
     * @return parsed positions (size &le; maxPositions)
     * @throws IOException if the file cannot be read
     */
    public static List<LabelledPosition> loadCsv(Path csvPath, int maxPositions) throws IOException {
        List<LabelledPosition> result  = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // Skip header row (starts with "fen" after stripping optional BOM/quotes)
                if (firstLine) {
                    firstLine = false;
                    if (line.replaceAll("^\"|\"$", "").toLowerCase().startsWith("fen")) continue;
                }
                LabelledPosition lp = tryParseCsvLine(line);
                if (lp != null) {
                    result.add(lp);
                    if (result.size() >= maxPositions) break;
                } else {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            LOG.info(String.format("[PositionLoader] CSV: skipped %,d unparseable lines", skipped));
        }
        return result;
    }

    /**
     * K scaling factor for Stockfish cp → pseudo-WDL conversion.
     * Derived from Stockfish NormalizeToPawnValue=328 with a 3.6% empirical
     * correction for Vex's centipawn scale. Do not change without a K-fit audit.
     */
    private static final double K_SF = 340.0;

    /**
     * Parses one CSV row: {@code "fen",sf_cp,game_result}
     * Converts the raw Stockfish centipawn value to a pseudo-WDL outcome via
     * {@code sigmoid(sf_cp / K_SF)}. Returns {@code null} if the row is malformed.
     */
    private static LabelledPosition tryParseCsvLine(String line) {
        try {
            // Handle optional quoted FEN field: "fen content",47,1.0
            String fenPart;
            String rest;
            if (line.startsWith("\"")) {
                int closeQuote = line.indexOf('"', 1);
                if (closeQuote < 0) return null;
                fenPart = line.substring(1, closeQuote);
                rest    = line.substring(closeQuote + 1).stripLeading();
                if (rest.startsWith(",")) rest = rest.substring(1);
            } else {
                int comma = line.indexOf(',');
                if (comma < 0) return null;
                fenPart = line.substring(0, comma).strip();
                rest    = line.substring(comma + 1);
            }
            // Next field: sf_cp (raw Stockfish centipawn evaluation)
            int comma2 = rest.indexOf(',');
            String cpStr = comma2 >= 0 ? rest.substring(0, comma2).strip() : rest.strip();
            double sfCp = Double.parseDouble(cpStr);
            if (!Double.isFinite(sfCp)) return null;
            // Convert to pseudo-WDL via sigmoid(sf_cp / K_SF)
            double pseudoWdl = 1.0 / (1.0 + Math.exp(-sfCp / K_SF));

            TunerPosition pos = parseFen(fenPart);
            if (pos == null) return null;
            return new LabelledPosition(pos, pseudoWdl);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Loads up to {@code maxPositions} labelled positions from a quiet-labeled.epd-compatible
     * annotated EPD file (Issue #140).
     *
     * <p>Supported annotation keywords: {@code c0} (Ethereal/quiet-labeled.epd), {@code c9},
     * and bracketed format {@code [result]}.
     *
     * <p>Two filters are applied:
     * <ol>
     *   <li>Positions where the active side is in check are skipped.</li>
     *   <li>Positions with material count &gt; 32 are skipped (opening-book noise filter).</li>
     * </ol>
     *
     * @param file         path to the annotated EPD file
     * @param maxPositions maximum number of positions to load
     * @return parsed positions (size &le; maxPositions)
     * @throws IOException if the file cannot be read
     */
    public static List<LabelledPosition> loadEpd(Path file, int maxPositions) throws IOException {
        List<LabelledPosition> result = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                LabelledPosition lp = tryParseEpdLine(line);
                if (lp != null) {
                    result.add(lp);
                    if (result.size() >= maxPositions) break;
                } else {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            LOG.info(String.format("[PositionLoader] EPD: skipped %,d filtered/unparseable lines", skipped));
        }
        return result;
    }

    /**
     * Parses a single annotated EPD line with in-check and material-count filters.
     * Handles c0 (Ethereal), c9, and bracketed result formats.
     * Returns {@code null} if the line is unparseable or filtered out.
     */
    private static LabelledPosition tryParseEpdLine(String line) {
        try {
            String fenPart;
            double outcome;

            if (line.contains("[")) {
                // Bracketed format: <FEN> [result]
                int open  = line.lastIndexOf('[');
                int close = line.lastIndexOf(']');
                if (open < 0 || close < open) return null;
                outcome = parseOutcome(line.substring(open + 1, close).strip());
                if (outcome < 0) return null;
                fenPart = line.substring(0, open).strip();
            } else {
                // c0 or c9 annotation format
                String stripped = line.endsWith(";") ? line.substring(0, line.length() - 1).strip() : line;
                int markerIdx;
                int c0Idx = stripped.indexOf("c0");
                int c9Idx = stripped.indexOf("c9");
                if (c0Idx >= 0 && (c9Idx < 0 || c0Idx <= c9Idx)) {
                    markerIdx = c0Idx;
                } else if (c9Idx >= 0) {
                    markerIdx = c9Idx;
                } else {
                    return null;
                }
                fenPart = stripped.substring(0, markerIdx).strip();
                String resultPart = stripped.substring(markerIdx + 2).strip().replace("\"", "").strip();
                outcome = parseOutcome(resultPart);
                if (outcome < 0) return null;
            }

            // Build full FEN (EPD has 4 fields, FEN has 6)
            String[] parts  = fenPart.split("\\s+");
            String   fullFen = parts.length >= 6 ? fenPart : fenPart + " 0 1";

            Board board = new Board(fullFen);

            // Filter 1: skip positions where the active side is in check
            if (board.isActiveColorInCheck()) return null;

            // Filter 2: skip positions with material count > 32 (opening-book noise)
            TunerPosition pos = TunerPosition.from(board, fullFen);
            int materialCount = Long.bitCount(pos.getWhiteOccupancy() | pos.getBlackOccupancy());
            if (materialCount > 32) return null;

            return new LabelledPosition(pos, outcome);
        } catch (Exception ignored) {
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

    /**
     * Loads up to {@code maxPositions} positions from an EPD file, then augments the
     * corpus with a color-flipped copy of every position, eliminating color bias.
     *
     * <p>For each loaded position with outcome {@code o}, a color-flipped copy is added
     * with outcome {@code 1.0 - o} (exchanging the White/Black perspective). The
     * resulting list interleaves original and flipped entries.
     *
     * @param file         EPD dataset path
     * @param maxPositions maximum number of base positions to load (list will be ≤ 2 × this)
     * @return list of size ≤ 2 × maxPositions
     * @throws IOException if the file cannot be read
     */
    public static List<LabelledPosition> loadBalanced(Path file, int maxPositions) throws IOException {
        List<LabelledPosition> base = loadEpd(file, maxPositions);
        List<LabelledPosition> result = new ArrayList<>(base.size() * 2);
        for (LabelledPosition lp : base) {
            result.add(lp);
            String flippedFen = colorFlipFen(lp.pos().fen());
            if (flippedFen == null) continue;
            TunerPosition flipped = parseFen(flippedFen);
            if (flipped != null) {
                result.add(new LabelledPosition(flipped, 1.0 - lp.outcome()));
            }
        }
        LOG.info("[PositionLoader] loadBalanced: {} base + {} flipped = {} total",
                 base.size(), result.size() - base.size(), result.size());
        return result;
    }

    /**
     * Returns the color-flipped FEN of the given position (mirrors the board vertically,
     * swaps piece colors, toggles the side to move, and adjusts castling/en-passant).
     * Returns {@code null} if the FEN is malformed.
     */
    private static String colorFlipFen(String fen) {
        try {
            String[] parts = fen.split(" ");
            if (parts.length < 4) return null;
            String placement = parts[0];
            String color     = parts[1];
            String castling  = parts[2];
            String ep        = parts[3];
            String rest      = parts.length > 4 ? parts[4] + " " + parts[5] : "0 1";

            // Reverse rank order and swap piece case
            String[] ranks = placement.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = ranks.length - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append('/');
                for (char c : ranks[i].toCharArray()) {
                    if      (Character.isUpperCase(c)) sb.append(Character.toLowerCase(c));
                    else if (Character.isLowerCase(c)) sb.append(Character.toUpperCase(c));
                    else                               sb.append(c);
                }
            }

            // Toggle active color
            String newColor = color.equals("w") ? "b" : "w";

            // Swap castling rights (K↔k, Q↔q)
            StringBuilder cr = new StringBuilder();
            for (char c : castling.toCharArray()) {
                if      (c == 'K') cr.append('k');
                else if (c == 'k') cr.append('K');
                else if (c == 'Q') cr.append('q');
                else if (c == 'q') cr.append('Q');
                else               cr.append(c);
            }

            // Mirror en-passant rank (3↔6)
            String newEp = ep;
            if (!ep.equals("-") && ep.length() == 2) {
                char rank = ep.charAt(1);
                if      (rank == '3') newEp = "" + ep.charAt(0) + '6';
                else if (rank == '6') newEp = "" + ep.charAt(0) + '3';
            }

            return sb + " " + newColor + " " + cr + " " + newEp + " " + rest;
        } catch (Exception e) {
            return null;
        }
    }

}
