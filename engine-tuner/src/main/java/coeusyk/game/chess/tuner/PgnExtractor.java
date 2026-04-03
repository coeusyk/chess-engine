package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.notation.SanConverter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private static final Pattern HEADER_PATTERN = Pattern.compile("^\\[([A-Za-z]+)\\s+\"(.*)\"\\]$");
    private static final Pattern MOVE_NUMBER    = Pattern.compile("\\d+\\.+");
    private static final Pattern ANNOTATION     = Pattern.compile("\\$\\d+");
    private static final Pattern RESULT_TOKEN   = Pattern.compile("(1-0|0-1|1/2-1/2|\\*)$");

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
}
