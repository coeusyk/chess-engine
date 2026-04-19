package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.notation.SanConverter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Search regression suite — self-consistency and EPD-based validation.
 *
 * <p>Replaces per-position Stockfish-agreement checks (hardcoded expected moves
 * that need manual updates every time the evaluation changes) with:
 * <ol>
 *   <li><b>Depth stability</b> — best-move agreement between D={@value #LOW_DEPTH}
 *       and D={@value #HIGH_DEPTH} across all WAC positions. Flip rate must not
 *       exceed the baseline threshold (default {@value #DEFAULT_FLIP_MAX}).</li>
 *   <li><b>WAC pass rate</b> — fraction of WAC EPD positions where the engine's
 *       best move at D={@value #PASS_DEPTH} matches the expected SAN move. Must
 *       equal or exceed the baseline pass rate (default {@value #DEFAULT_PASS_MIN}).</li>
 *   <li><b>SEE blunder gate</b> — for every capture the engine plays on a WAC
 *       position, the static exchange evaluation must be ≥
 *       {@value #SEE_BLUNDER_THRESHOLD} centipawns (not clearly losing material).</li>
 * </ol>
 *
 * <h3>Running</h3>
 * <pre>
 *   mvn test -pl engine-core -Psearch-regression
 * </pre>
 *
 * <h3>Updating the baseline</h3>
 * <pre>
 *   mvn test -pl engine-core -Psearch-regression -Dupdate-baseline=true
 * </pre>
 * This writes the current pass rate into
 * {@code engine-core/src/test/resources/regression/search_regression_baseline.properties}.
 *
 * <p>This test class is excluded from the normal build (see the {@code search-regression}
 * Maven profile in engine-core/pom.xml) and is only executed when that profile is active.
 */
@Tag("search-regression")
class SearchRegressionSuite {

    // ── Search depths ────────────────────────────────────────────────────────
    /** Shallow depth for the stability "before" side. */
    private static final int LOW_DEPTH  = 5;
    /** Deep depth for the stability "after" side (= LOW_DEPTH + 4). */
    private static final int HIGH_DEPTH = 9;
    /** Depth used for the WAC pass-rate and SEE blunder tests. */
    private static final int PASS_DEPTH = 7;

    // ── Thresholds ───────────────────────────────────────────────────────────
    /** Maximum allowed depth-flip rate when no baseline property exists. */
    private static final double DEFAULT_FLIP_MAX   = 0.35;
    /** Minimum WAC pass rate when no baseline property exists. */
    private static final double DEFAULT_PASS_MIN   = 0.80;
    /** SEE score below this is considered a blunder. */
    private static final int    SEE_BLUNDER_THRESHOLD = -100; // centipawns

    // ── Classpath resources ──────────────────────────────────────────────────
    private static final String WAC_RESOURCE      = "/regression/wac.epd";
    private static final String BASELINE_RESOURCE = "/regression/search_regression_baseline.properties";

    // ── Baseline property keys ───────────────────────────────────────────────
    private static final String KEY_PASS_RATE = "wac.pass.rate";
    private static final String KEY_FLIP_MAX  = "wac.flip.rate.max";

    // ────────────────────────────────────────────────────────────────────────
    // Test 1 — Depth stability
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the engine's best move does not flip significantly when
     * search depth increases from {@value #LOW_DEPTH} to {@value #HIGH_DEPTH}.
     *
     * <p>A "flip" is when the chosen best move changes between depths.
     * Tactical positions in the WAC suite should be highly stable: the winning
     * move found at depth 5 rarely changes at depth 9.
     */
    @Test
    void depthStabilityBelowFlipThreshold() throws IOException {
        List<EpdPosition> positions = loadWacPositions();
        assertFalse(positions.isEmpty(), "WAC EPD file must contain at least one position");

        Properties baseline = loadBaseline();
        double maxFlipRate = Double.parseDouble(
                baseline.getProperty(KEY_FLIP_MAX, String.valueOf(DEFAULT_FLIP_MAX)));

        Searcher searcher = new Searcher();
        int flips = 0;
        int evaluated = 0;

        for (EpdPosition pos : positions) {
            String lowMove  = toUci(searcher.searchDepth(new Board(pos.fen()), LOW_DEPTH).bestMove());
            String highMove = toUci(searcher.searchDepth(new Board(pos.fen()), HIGH_DEPTH).bestMove());

            if (lowMove == null || highMove == null) {
                continue; // no legal moves — endgame terminal; skip
            }
            evaluated++;
            if (!lowMove.equals(highMove)) {
                flips++;
                System.out.printf(Locale.ROOT,
                        "[stability] FLIP %s: D%d=%s  D%d=%s%n",
                        pos.id(), LOW_DEPTH, lowMove, HIGH_DEPTH, highMove);
            }
        }

        double flipRate = evaluated > 0 ? (double) flips / evaluated : 0.0;
        System.out.printf(Locale.ROOT,
                "[stability] flips=%d evaluated=%d flipRate=%.1f%%  threshold=%.0f%%%n",
                flips, evaluated, flipRate * 100.0, maxFlipRate * 100.0);

        assertTrue(flipRate <= maxFlipRate,
                String.format(Locale.ROOT,
                        "Depth-stability flip rate %.1f%% exceeds allowed %.0f%% (flips=%d/%d).",
                        flipRate * 100.0, maxFlipRate * 100.0, flips, evaluated));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 2 — WAC pass rate
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Checks that the engine solves at least the baseline percentage of WAC EPD
     * positions at D={@value #PASS_DEPTH}.
     *
     * <p>Expected best moves are stored in SAN notation in the EPD file.
     * This test converts each SAN bm to UCI via {@link SanConverter#fromSan} in
     * the context of the position's board, then compares with the engine's output.
     * This eliminates fragile per-position expected-move hardcoding.
     *
     * <p>Run with {@code -Dupdate-baseline=true} to record the current pass rate
     * as the new baseline instead of asserting against the existing one.
     */
    @Test
    void wacPassRateAboveBaseline() throws IOException {
        List<EpdPosition> positions = loadWacPositions();
        assertFalse(positions.isEmpty(), "WAC EPD file must contain at least one position");

        Properties baseline = loadBaseline();
        double minPassRate = Double.parseDouble(
                baseline.getProperty(KEY_PASS_RATE, String.valueOf(DEFAULT_PASS_MIN)));

        Searcher searcher = new Searcher();
        int passed = 0;
        int total  = 0;

        for (EpdPosition pos : positions) {
            Board board = new Board(pos.fen());

            // Convert SAN bm list to UCI using the board position context
            Set<String> expectedUci = new HashSet<>();
            for (String san : pos.bestMoveSans()) {
                Move m = SanConverter.fromSan(san, board);
                if (m != null) {
                    expectedUci.add(toUci(m));
                }
            }
            if (expectedUci.isEmpty()) {
                System.out.printf(Locale.ROOT,
                        "[wac] WARNING: bm SAN could not be resolved for %s — skipping%n",
                        pos.id());
                continue; // unresolvable bm: skip without penalising pass rate
            }
            total++;

            String engineMove = toUci(searcher.searchDepth(new Board(pos.fen()), PASS_DEPTH).bestMove());
            boolean correct   = engineMove != null && expectedUci.contains(engineMove);
            if (correct) {
                passed++;
            } else {
                System.out.printf(Locale.ROOT,
                        "[wac] MISS %s: expected=%s  got=%s%n",
                        pos.id(), expectedUci, engineMove);
            }
        }

        if (total == 0) {
            assertTrue(false, "No WAC positions could be evaluated (all bm fields unresolvable).");
            return;
        }

        double passRate = (double) passed / total;
        System.out.printf(Locale.ROOT,
                "[wac] passed=%d total=%d passRate=%.1f%%  baseline=%.1f%%%n",
                passed, total, passRate * 100.0, minPassRate * 100.0);

        if (Boolean.getBoolean("update-baseline")) {
            updateBaseline(baseline, KEY_PASS_RATE, String.format(Locale.ROOT, "%.4f", passRate));
            System.out.printf(Locale.ROOT,
                    "[wac] Baseline updated: %s=%.4f%n", KEY_PASS_RATE, passRate);
            return; // do not assert when updating
        }

        assertTrue(passRate >= minPassRate,
                String.format(Locale.ROOT,
                        "WAC pass rate %.1f%% is below baseline %.1f%% (passed=%d/%d).",
                        passRate * 100.0, minPassRate * 100.0, passed, total));
    }

    // ────────────────────────────────────────────────────────────────────────
    // Test 3 — SEE blunder gate
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Verifies that the engine does not choose a clearly losing capture on any
     * WAC position.
     *
     * <p>For every capture move the engine plays at D={@value #PASS_DEPTH},
     * the static exchange evaluation (SEE) must be ≥
     * {@value #SEE_BLUNDER_THRESHOLD} centipawns. A negative SEE score far
     * below zero indicates the engine is walking into a known material loss —
     * a search regression symptom.
     */
    @Test
    void engineDoesNotBlunderMaterialOnWacPositions() throws IOException {
        List<EpdPosition> positions = loadWacPositions();
        assertFalse(positions.isEmpty(), "WAC EPD file must contain at least one position");

        Searcher searcher = new Searcher();
        StaticExchangeEvaluator see = new StaticExchangeEvaluator();
        List<String> blunders = new ArrayList<>();

        for (EpdPosition pos : positions) {
            Board board = new Board(pos.fen());
            Move best = searcher.searchDepth(new Board(pos.fen()), PASS_DEPTH).bestMove();
            if (best == null) {
                continue;
            }

            // Only evaluate SEE for captures (quiet moves have undefined SEE)
            boolean isCapture = board.getPiece(best.targetSquare) != Piece.None
                    || "en-passant".equals(best.reaction);
            if (!isCapture) {
                continue;
            }

            int seeScore = see.evaluate(board, best);
            if (seeScore < SEE_BLUNDER_THRESHOLD) {
                blunders.add(String.format(Locale.ROOT,
                        "%s: engineMove=%s SEE=%d", pos.id(), toUci(best), seeScore));
            }
        }

        assertTrue(blunders.isEmpty(),
                "Engine played SEE-blunder capture(s) on WAC positions:\n  "
                        + String.join("\n  ", blunders));
    }

    // ────────────────────────────────────────────────────────────────────────
    // EPD loading
    // ────────────────────────────────────────────────────────────────────────

    private List<EpdPosition> loadWacPositions() throws IOException {
        URL url = getClass().getResource(WAC_RESOURCE);
        assertNotNull(url, "WAC EPD resource not found on classpath: " + WAC_RESOURCE);

        try (InputStream in = url.openStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parseEpd(reader.lines().toList());
        }
    }

    /**
     * Parses lines from a 4-field EPD file (position + opcodes).
     * Expected opcodes: {@code bm <san>[...];} and optionally {@code id "<label>"; }
     *
     * <p>Lines beginning with {@code #} or blank lines are skipped.
     */
    private List<EpdPosition> parseEpd(List<String> lines) {
        Pattern bmPattern = Pattern.compile("\\bbm\\s+([^;]+)");
        Pattern idPattern = Pattern.compile("\\bid\\s+\"([^\"]+)\"");

        List<EpdPosition> result = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] tokens = line.split("\\s+");
            if (tokens.length < 5) {
                continue; // need at least 4 FEN fields + at least one opcode token
            }

            // 4-field FEN → 6-field by appending "0 1" for halfmove/fullmove clocks
            String fen = tokens[0] + " " + tokens[1] + " " + tokens[2] + " " + tokens[3] + " 0 1";
            String opcodes = String.join(" ", Arrays.copyOfRange(tokens, 4, tokens.length));

            Matcher bmMatcher = bmPattern.matcher(opcodes);
            if (!bmMatcher.find()) {
                continue;
            }
            List<String> bestMoveSans = new ArrayList<>();
            for (String token : bmMatcher.group(1).trim().split("\\s+")) {
                String s = token.replace(";", "").trim();
                if (!s.isEmpty()) {
                    bestMoveSans.add(s);
                }
            }
            if (bestMoveSans.isEmpty()) {
                continue;
            }

            String id = "epd-line-" + (i + 1);
            Matcher idMatcher = idPattern.matcher(opcodes);
            if (idMatcher.find()) {
                id = idMatcher.group(1);
            }

            result.add(new EpdPosition(fen, Set.copyOf(bestMoveSans), id));
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Baseline I/O
    // ────────────────────────────────────────────────────────────────────────

    private Properties loadBaseline() {
        Properties props = new Properties();
        URL url = getClass().getResource(BASELINE_RESOURCE);
        if (url == null) {
            System.out.println("[baseline] No baseline file found — using defaults.");
            return props;
        }
        try (InputStream in = url.openStream()) {
            props.load(in);
        } catch (IOException e) {
            System.out.println("[baseline] WARNING: could not load baseline: " + e.getMessage());
        }
        return props;
    }

    /**
     * Writes a single key/value pair to the baseline properties file in the
     * source tree. Maven working directory during tests is the module root
     * ({@code engine-core/}); the relative path resolves correctly in standard
     * Maven layouts.
     */
    private void updateBaseline(Properties existing, String key, String value) {
        existing.setProperty(key, value);
        Path target = Path.of("src/test/resources/regression/search_regression_baseline.properties");
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            existing.store(writer, "Search regression baseline — auto-updated by -Dupdate-baseline=true");
            System.out.println("[baseline] Written to: " + target.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("[baseline] WARNING: could not write baseline: " + e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private static String toUci(Move move) {
        if (move == null) {
            return null;
        }
        int sf = move.startSquare % 8;
        int sr = 8 - (move.startSquare / 8);
        int tf = move.targetSquare % 8;
        int tr = 8 - (move.targetSquare / 8);
        String uci = "" + (char) ('a' + sf) + sr + (char) ('a' + tf) + tr;
        if ("promote-q".equals(move.reaction)) uci += "q";
        else if ("promote-r".equals(move.reaction)) uci += "r";
        else if ("promote-b".equals(move.reaction)) uci += "b";
        else if ("promote-n".equals(move.reaction)) uci += "n";
        return uci;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Data model
    // ────────────────────────────────────────────────────────────────────────

    /**
     * An EPD position parsed from the WAC file.
     *
     * @param fen          6-field FEN string
     * @param bestMoveSans set of accepted best moves in SAN notation
     * @param id           position identifier (from the EPD {@code id} opcode)
     */
    private record EpdPosition(String fen, Set<String> bestMoveSans, String id) {}
}
