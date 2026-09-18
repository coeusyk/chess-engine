package coeusyk.game.chess.core.tools;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.notation.SanConverter;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone CLI utility for issue #181 (SPRT PGN post-processing pipeline).
 * Replays every game in a cutechess-cli PGN file move-by-move using this
 * project's own, already-tested {@link SanConverter}/{@link Board}/movegen
 * stack, and emits a flat, tab-separated line per ply -- FEN before and after
 * each move, the move's own SAN (so a caller can check for captures/checks
 * without re-parsing chess rules), and cutechess-cli's own embedded
 * {score/depth time} annotation for that ply (context only -- never a
 * training label; see tools/sprt_pgn_pipeline.ps1, which is the only caller
 * of this class and which never treats these scores as an oracle).
 *
 * This exists because SAN move replay requires real chess-rules logic
 * (disambiguation, castling, en passant, promotion, check/mate detection).
 * Reusing SanConverter/Board here avoids re-implementing and re-testing that
 * logic in PowerShell for a tooling script.
 *
 * Usage: java -cp engine-uci-&lt;version&gt;.jar coeusyk.game.chess.core.tools.PgnReplayer &lt;pgn-file&gt;
 *
 * Output (stdout, tab-separated):
 *   GAME&lt;TAB&gt;index&lt;TAB&gt;white&lt;TAB&gt;black&lt;TAB&gt;result&lt;TAB&gt;startFen
 *   PLY&lt;TAB&gt;index&lt;TAB&gt;plyNum&lt;TAB&gt;san&lt;TAB&gt;fenBefore&lt;TAB&gt;fenAfter&lt;TAB&gt;isCapture&lt;TAB&gt;isCheck&lt;TAB&gt;vexScoreCp&lt;TAB&gt;vexDepth&lt;TAB&gt;vexTimeS
 * A game that fails to replay (illegal/unrecognised SAN, bad FEN) is skipped
 * entirely; a one-line reason is written to stderr as "SKIP&lt;TAB&gt;index&lt;TAB&gt;reason"
 * and replay continues with the next game -- required by #181's own
 * acceptance criteria ("runs on all .pgn files without crashing on malformed
 * games").
 */
public final class PgnReplayer {

    private static final Pattern GAME_SPLIT = Pattern.compile("(?=\\[Event )");
    private static final Pattern TAG_PAIR = Pattern.compile("\\[(\\w+)\\s+\"([^\"]*)\"]");
    // Anchored, used only to recognise a whole token as a move number (e.g. "31." or
    // "31...") -- never applied as a blind replaceAll over the movetext, because doing
    // so also matches decimal points inside {score/depth Xs} comments (e.g. "3.9s"
    // contains "3." and would be corrupted to "9s").
    private static final Pattern MOVE_NUMBER_TOKEN = Pattern.compile("^\\d+\\.(\\.\\.)?$");
    private static final Pattern MOVE_AND_COMMENT =
            Pattern.compile("(\\S+)(?:\\s*\\{([^}]*)})?");
    private static final Pattern SCORE_ANNOTATION =
            Pattern.compile("([+-]?[\\d.]+)/(\\d+)\\s+([\\d.]+)s");
    private static final String STANDARD_START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private PgnReplayer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: PgnReplayer <pgn-file>");
            System.exit(1);
        }
        String text = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String[] chunks = GAME_SPLIT.split(text);

        PrintStream out = System.out;
        int gameIndex = 0;
        for (String chunk : chunks) {
            if (chunk.trim().isEmpty()) {
                continue;
            }
            gameIndex++;
            try {
                replayGame(chunk, gameIndex, out);
            } catch (Exception e) {
                System.err.println("SKIP\t" + gameIndex + "\t" + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private static void replayGame(String chunk, int gameIndex, PrintStream out) {
        java.util.Map<String, String> tags = new java.util.HashMap<>();
        Matcher tagMatcher = TAG_PAIR.matcher(chunk);
        int movetextStart = 0;
        while (tagMatcher.find()) {
            tags.put(tagMatcher.group(1), tagMatcher.group(2));
            movetextStart = tagMatcher.end();
        }

        String white = tags.getOrDefault("White", "?");
        String black = tags.getOrDefault("Black", "?");
        String result = tags.getOrDefault("Result", "*");
        String startFen = tags.getOrDefault("FEN", STANDARD_START_FEN);

        String movetext = chunk.substring(movetextStart).trim();

        Board board = new Board(startFen);
        out.println("GAME\t" + gameIndex + "\t" + white + "\t" + black + "\t" + result + "\t" + startFen);

        Matcher moveMatcher = MOVE_AND_COMMENT.matcher(movetext);
        int ply = 0;
        while (moveMatcher.find()) {
            String token = moveMatcher.group(1);
            if (token.isEmpty() || MOVE_NUMBER_TOKEN.matcher(token).matches()) {
                continue;
            }
            if (isResultToken(token)) {
                break;
            }
            String comment = moveMatcher.group(2);

            String fenBefore = board.toFen();
            Move move = SanConverter.fromSan(token, board);
            if (move == null) {
                throw new IllegalStateException("unrecognised SAN '" + token + "' at ply " + (ply + 1));
            }
            board.makeMove(move);
            ply++;
            String fenAfter = board.toFen();

            boolean isCapture = token.contains("x");
            boolean isCheck = token.contains("+") || token.contains("#");

            String scoreCp = "";
            String depth = "";
            String timeS = "";
            if (comment != null) {
                Matcher scoreMatcher = SCORE_ANNOTATION.matcher(comment);
                if (scoreMatcher.find()) {
                    scoreCp = scoreMatcher.group(1);
                    depth = scoreMatcher.group(2);
                    timeS = scoreMatcher.group(3);
                }
            }

            out.println("PLY\t" + gameIndex + "\t" + ply + "\t" + token + "\t" + fenBefore + "\t" + fenAfter
                    + "\t" + isCapture + "\t" + isCheck + "\t" + scoreCp + "\t" + depth + "\t" + timeS);
        }
    }

    private static boolean isResultToken(String token) {
        return "1-0".equals(token) || "0-1".equals(token) || "1/2-1/2".equals(token) || "*".equals(token);
    }
}
