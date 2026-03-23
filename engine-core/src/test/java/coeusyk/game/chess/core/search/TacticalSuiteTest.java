package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalSuiteTest {
    private static final Pattern BM_PATTERN = Pattern.compile("\\bbm\\s+([^;]+)");

    @Test
    void solvesAtLeastConfiguredPercentageOfTacticalSuite() throws IOException {
        Assumptions.assumeTrue(
                Boolean.getBoolean("tactical.enabled"),
                "Set -Dtactical.enabled=true to run tactical suite benchmark"
        );

        String suiteFile = System.getProperty("tactical.suite.file", "").trim();
        int expectedPositions = Integer.getInteger("tactical.expected.positions", 50);
        double minPassRate = Double.parseDouble(System.getProperty("tactical.min.pass.rate", "0.80"));

        int depth = Integer.getInteger("tactical.depth", 0);
        long movetimeMs = Long.getLong("tactical.movetime.ms", 2000L);
        if (depth <= 0 && movetimeMs <= 0) {
            throw new IllegalArgumentException("Configure tactical.depth > 0 or tactical.movetime.ms > 0");
        }

        List<TacticalPosition> positions = loadSuite(suiteFile);
        assertFalse(
            positions.isEmpty(),
            "No tactical positions were loaded. Populate engine-core/src/test/resources/tactical/mate_2_3_50.epd " +
                "or pass -Dtactical.suite.file=<absolute-path-to-epd>."
        );
        assertTrue(
                positions.size() >= expectedPositions,
                "Loaded " + positions.size() + " positions but expected at least " + expectedPositions
        );

        Searcher searcher = new Searcher(true);
        int solved = 0;

        for (int i = 0; i < positions.size(); i++) {
            TacticalPosition position = positions.get(i);
            Board board = new Board(position.fen());
            SearchResult result;

            if (depth > 0) {
                result = searcher.iterativeDeepening(board, depth, () -> false, () -> false, null);
            } else {
                TimeManager manager = new TimeManager();
                manager.configureMovetime(movetimeMs);
                result = searcher.searchWithTimeManager(board, 127, manager, () -> false, null);
            }

            String bestMoveUci = toUci(result.bestMove());
            boolean isSolved = bestMoveUci != null && position.bestMovesUci().contains(bestMoveUci);
            if (isSolved) {
                solved++;
            } else {
                System.out.printf(
                        Locale.ROOT,
                        "MISS #%d id=%s expected=%s got=%s%n",
                        i + 1,
                        position.id(),
                        position.bestMovesUci(),
                        bestMoveUci
                );
            }
        }

        double passRate = (double) solved / (double) positions.size();
        System.out.printf(
                Locale.ROOT,
                "Tactical suite result: solved=%d total=%d passRate=%.2f%%%n",
                solved,
                positions.size(),
                passRate * 100.0
        );

        assertTrue(
                passRate >= minPassRate,
                "Expected pass rate >= " + (minPassRate * 100.0) + "% but got " + (passRate * 100.0) + "%"
        );
    }

    private List<TacticalPosition> loadSuite(String suiteFile) throws IOException {
        if (!suiteFile.isBlank()) {
            try (BufferedReader reader = Files.newBufferedReader(Path.of(suiteFile), StandardCharsets.UTF_8)) {
                return parseSuiteLines(reader.lines().toList());
            }
        }

        try (InputStream stream = getClass().getResourceAsStream("/tactical/mate_2_3_50.epd")) {
            if (stream == null) {
                throw new IllegalStateException("Missing classpath resource /tactical/mate_2_3_50.epd");
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return parseSuiteLines(reader.lines().toList());
            }
        }
    }

    private List<TacticalPosition> parseSuiteLines(List<String> lines) {
        List<TacticalPosition> positions = new ArrayList<>();

        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String raw = lines.get(lineNumber);
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] tokens = line.split("\\s+");
            if (tokens.length < 5) {
                throw new IllegalArgumentException("Invalid tactical line " + (lineNumber + 1) + ": " + line);
            }

            String fen = String.join(" ", tokens[0], tokens[1], tokens[2], tokens[3], "0", "1");
            String operations = String.join(" ", java.util.Arrays.copyOfRange(tokens, 4, tokens.length));

            Matcher bmMatcher = BM_PATTERN.matcher(operations);
            if (!bmMatcher.find()) {
                throw new IllegalArgumentException("Missing bm operation at line " + (lineNumber + 1));
            }

            Set<String> bestMoves = new HashSet<>();
            for (String move : bmMatcher.group(1).trim().split("\\s+")) {
                String normalized = normalizeUci(move);
                if (!normalized.isEmpty()) {
                    bestMoves.add(normalized);
                }
            }
            if (bestMoves.isEmpty()) {
                throw new IllegalArgumentException("Empty bm move list at line " + (lineNumber + 1));
            }

            String id = "line-" + (lineNumber + 1);
            Matcher idMatcher = Pattern.compile("\\bid\\s+\"([^\"]+)\"").matcher(operations);
            if (idMatcher.find()) {
                id = idMatcher.group(1);
            }

            positions.add(new TacticalPosition(fen, Set.copyOf(bestMoves), id));
        }

        return positions;
    }

    private String toUci(Move move) {
        if (move == null) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        builder.append(squareToUci(move.startSquare));
        builder.append(squareToUci(move.targetSquare));

        if ("promote-q".equals(move.reaction)) builder.append('q');
        if ("promote-r".equals(move.reaction)) builder.append('r');
        if ("promote-b".equals(move.reaction)) builder.append('b');
        if ("promote-n".equals(move.reaction)) builder.append('n');

        return builder.toString();
    }

    private String squareToUci(int square) {
        int file = square % 8;
        int rank = 8 - (square / 8);
        char fileChar = (char) ('a' + file);
        return "" + fileChar + rank;
    }

    private String normalizeUci(String move) {
        String normalized = move.trim().toLowerCase(Locale.ROOT).replace(";", "");
        if (normalized.endsWith("+") || normalized.endsWith("#")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record TacticalPosition(String fen, Set<String> bestMovesUci, String id) {
        TacticalPosition {
            Objects.requireNonNull(fen);
            Objects.requireNonNull(bestMovesUci);
            Objects.requireNonNull(id);
        }
    }
}