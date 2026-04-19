package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.notation.SanConverter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts a PGN file into a list of labelled positions suitable for Texel tuning.
 *
 * <p>For each game, positions after the first {@code skipPlies} half-moves are extracted
 * (to skip opening theory) and labelled with the game result (1.0/0.5/0.0 from white's POV).
 *
 * <p>Only positions where the side to move is NOT in check are included, making the dataset
 * more "quiet". In-check positions skew evaluation because tactical sequences dominate.
 */
public final class PgnExtractor {

    private static final Pattern MOVE_NUMBER    = Pattern.compile("\\d+\\.+");
    private static final Pattern ANNOTATION     = Pattern.compile("\\$\\d+");

    private PgnExtractor() {}

    /**
     * Parses a PGN file and extracts labelled positions.
     *
     * @param pgnPath   path to the PGN file
     * @param skipPlies number of half-moves to skip at the start of each game (skip opening)
     * @return list of {@link LabelledPosition} records ready for tuning
     */
    public static List<LabelledPosition> extract(Path pgnPath, int skipPlies) throws IOException {
        List<LabelledPosition> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(pgnPath);

        List<String> headerLines = new ArrayList<>();
        StringBuilder moveText   = new StringBuilder();
        boolean inMoveSection    = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (inMoveSection) {
                    // blank line after move text — game is complete
                    processGame(headerLines, moveText.toString(), skipPlies, result);
                    headerLines.clear();
                    moveText.setLength(0);
                    inMoveSection = false;
                }
                continue;
            }
            if (line.startsWith("[")) {
                if (inMoveSection) {
                    // new game starting before blank line separator
                    processGame(headerLines, moveText.toString(), skipPlies, result);
                    headerLines.clear();
                    moveText.setLength(0);
                    inMoveSection = false;
                }
                headerLines.add(line);
            } else {
                inMoveSection = true;
                moveText.append(line).append(' ');
            }
        }

        // flush last game if file doesn't end with a blank line
        if (inMoveSection && moveText.length() > 0) {
            processGame(headerLines, moveText.toString(), skipPlies, result);
        }

        return result;
    }

    // -----------------------------------------------------------------------

    private static void processGame(List<String> headers, String moveText,
                                    int skipPlies, List<LabelledPosition> out) {
        String result = extractHeader(headers, "Result");
        String fen    = extractHeader(headers, "FEN");

        if (result == null || result.equals("*")) {
            return; // unfinished game — skip
        }

        double outcome = resultToOutcome(result);
        List<String> tokens = tokenizeMoves(moveText);
        if (tokens.isEmpty()) {
            return;
        }

        Board board = (fen != null) ? new Board(fen) : new Board();
        int ply = 0;

        for (String token : tokens) {
            if (token.isEmpty()) continue;

            Move move = SanConverter.fromSan(token, board);
            if (move == null) {
                // Couldn't parse move — abort this game gracefully
                return;
            }

            if (ply >= skipPlies) {
                // Extract position BEFORE the move (pre-move FEN)
                // Only include quiet (non-check) positions
                if (!board.isActiveColorInCheck()) {
                    String positionFen = board.toFen();
                    out.add(new LabelledPosition(TunerPosition.from(board, positionFen), outcome));
                }
            }

            board.makeMove(move);
            ply++;
        }
    }

    private static String extractHeader(List<String> headers, String tag) {
        String prefix = "[" + tag + " \"";
        for (String h : headers) {
            if (h.startsWith(prefix) && h.endsWith("\"]")) {
                return h.substring(prefix.length(), h.length() - 2);
            }
        }
        return null;
    }

    private static double resultToOutcome(String result) {
        return switch (result) {
            case "1-0"     -> 1.0;
            case "0-1"     -> 0.0;
            case "1/2-1/2" -> 0.5;
            default        -> 0.5;
        };
    }

    /**
     * Strips move numbers, NAGs, result tokens and comments from PGN move text,
     * returning a flat list of SAN tokens.
     */
    private static List<String> tokenizeMoves(String moveText) {
        // Strip {...} comments
        String text = moveText.replaceAll("\\{[^}]*\\}", " ");
        // Strip (...) variants
        text = stripVariants(text);
        // Strip NAGs ($1 etc.)
        text = ANNOTATION.matcher(text).replaceAll(" ");
        // Strip move numbers (1. 1... etc.)
        text = MOVE_NUMBER.matcher(text).replaceAll(" ");
        // Strip annotation glyphs
        text = text.replaceAll("[?!]+", "");
        // Strip check/mate symbols are kept — SanConverter.normalizeSan handles them

        List<String> tokens = new ArrayList<>();
        for (String tok : text.split("\\s+")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            // Skip result markers
            if (t.equals("1-0") || t.equals("0-1") || t.equals("1/2-1/2") || t.equals("*")) continue;
            tokens.add(t);
        }
        return tokens;
    }

    /** Strips parenthesised variation blocks (not nested). */
    private static String stripVariants(String text) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '(')      { depth++; }
            else if (c == ')') { depth = Math.max(0, depth - 1); }
            else if (depth == 0) { sb.append(c); }
        }
        return sb.toString();
    }

    /**
     * CLI entry point: extracts WDL-labelled positions from PGN files and writes EPD.
     *
     * <p>Usage: {@code java -cp <tuner.jar> coeusyk.game.chess.tuner.PgnExtractor
     *   <pgnDir> <output.epd> [maxPositions] [skipPlies] [maxPieces]}
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: PgnExtractor <pgnDir> <output.epd> [maxPositions] [skipPlies] [maxPieces]");
            System.exit(1);
        }

        Path pgnDir      = Path.of(args[0]);
        Path output      = Path.of(args[1]);
        int maxPositions  = args.length >= 3 ? Integer.parseInt(args[2]) : 100_000;
        int skipPlies     = args.length >= 4 ? Integer.parseInt(args[3]) : 20;
        int maxPieces     = args.length >= 5 ? Integer.parseInt(args[4]) : 28;

        List<Path> pgnFiles = Files.list(pgnDir)
                .filter(p -> p.toString().endsWith(".pgn"))
                .sorted()
                .toList();

        System.out.printf("[PgnExtractor] PGN files: %d in %s%n", pgnFiles.size(), pgnDir);
        System.out.printf("[PgnExtractor] Max positions: %d, skipPlies: %d, maxPieces: %d%n",
                maxPositions, skipPlies, maxPieces);

        List<LabelledPosition> all = new ArrayList<>();
        for (Path pgn : pgnFiles) {
            System.out.printf("[PgnExtractor] Processing: %s%n", pgn.getFileName());
            List<LabelledPosition> batch = extract(pgn, skipPlies);
            // Filter: piece count <= maxPieces
            for (LabelledPosition lp : batch) {
                String fen = lp.pos().fen();
                int pieces = countPieces(fen);
                if (pieces <= maxPieces) {
                    all.add(lp);
                }
            }
            System.out.printf("[PgnExtractor]   extracted %d, total %d%n", batch.size(), all.size());
            if (all.size() >= maxPositions) break;
        }

        // Shuffle and cap
        Collections.shuffle(all);
        if (all.size() > maxPositions) {
            all = all.subList(0, maxPositions);
        }

        // Write EPD with c0 annotation
        try (BufferedWriter w = Files.newBufferedWriter(output)) {
            for (LabelledPosition lp : all) {
                String result = outcomeToResult(lp.outcome());
                w.write(lp.pos().fen() + " c0 \"" + result + "\";");
                w.newLine();
            }
        }

        System.out.printf("[PgnExtractor] Wrote %d positions to %s%n", all.size(), output);
    }

    private static int countPieces(String fen) {
        String board = fen.split(" ")[0];
        int count = 0;
        for (char c : board.toCharArray()) {
            if (Character.isLetter(c)) count++;
        }
        return count;
    }

    private static String outcomeToResult(double outcome) {
        if (outcome == 1.0) return "1-0";
        if (outcome == 0.0) return "0-1";
        return "1/2-1/2";
    }
}
