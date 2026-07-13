package coeusyk.game.chess.core.eval.nnue;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * PR C-4 (issue #188): one-time generator for the ply/piece-count-bucketed benchmark
 * corpus categories under {@code bench/nnue-corpus/}. Reuses the same fixed-seed
 * random-legal self-play machinery as {@link NnueIncrementalVsRebuildFuzzTest}.
 *
 * <p>Excluded from the standard test suite and from CI — the plan requires the
 * generator to be re-runnable on demand but its <em>output</em> to be committed once
 * and never regenerated automatically (Task 3, "Reproducibility"). Run manually with:
 * <pre>
 *   mvn -pl engine-core test -Dgroups=corpus-generation -Dcorpus.generate=true
 * </pre>
 */
@Tag("corpus-generation")
class NnueCorpusGenerator {

    private static final int SEED = 20260713;
    // Endgame generation uses its own seed and its own (capture-biased) pass — see below
    // — so it must not perturb the unbiased RNG stream that opening/middlegame/random-legal
    // share with NnueIncrementalVsRebuildFuzzTest's plain random-legal-game machinery.
    private static final int ENDGAME_SEED = SEED + 1;
    private static final int GAMES = 60;
    private static final int MAX_PLIES = 150;
    private static final int PER_CATEGORY = 60;
    private static final int ENDGAME_PIECE_THRESHOLD = 12;
    // Pure random-legal play rarely trades material down far enough to reach sparse
    // endgame boards within a bounded ply count (captures are one option among many
    // each ply). Biasing move selection toward captures accelerates convergence to
    // sparse positions without making the games deterministic. This bias is deliberately
    // confined to the endgame pass only — the "random-legal" category's whole point is to
    // be the plan's unbiased reuse of NnueIncrementalVsRebuildFuzzTest's machinery verbatim.
    private static final double CAPTURE_BIAS = 0.35;

    private static final Path CORPUS_DIR = Path.of("..", "bench", "nnue-corpus");
    private static final Path DRAW_FAILURES_EPD =
            Path.of("src", "test", "resources", "regression", "draw_failures.epd");

    // A single @Test method: JUnit 5 does not guarantee method execution order within a
    // class, and generateGoldenEvals() depends on the category files existing on disk.
    @Test
    void generateCorpus() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("corpus.generate"),
                "corpus generation only runs when explicitly requested via -Dcorpus.generate=true");

        generateCategoryFiles();
        generateGoldenEvals();
    }

    private void generateCategoryFiles() throws IOException {
        Set<String> openingFens = new LinkedHashSet<>();
        Set<String> middlegameFens = new LinkedHashSet<>();
        Set<String> randomLegalFens = new LinkedHashSet<>();
        Random random = new Random(SEED);

        // Unbiased pass — plain random-legal move selection, matching
        // NnueIncrementalVsRebuildFuzzTest's machinery exactly.
        for (int game = 0; game < GAMES; game++) {
            Board board = new Board();
            for (int ply = 1; ply <= MAX_PLIES; ply++) {
                List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
                if (legalMoves.isEmpty()) {
                    break; // checkmate/stalemate reached — start a fresh game
                }
                Move move = legalMoves.get(random.nextInt(legalMoves.size()));
                board.makeMove(move);
                String fen = fourFieldFen(board);

                if (ply <= 15 && openingFens.size() < PER_CATEGORY) {
                    openingFens.add(fen);
                } else if (ply > 15 && ply <= 40 && middlegameFens.size() < PER_CATEGORY) {
                    middlegameFens.add(fen);
                }
                if (randomLegalFens.size() < PER_CATEGORY && random.nextInt(MAX_PLIES) == 0) {
                    randomLegalFens.add(fen);
                }
            }
        }

        Set<String> endgameFens = new LinkedHashSet<>();
        Random endgameRandom = new Random(ENDGAME_SEED);

        // Capture-biased pass — deliberate deviation, confined to this category only
        // (see CAPTURE_BIAS javadoc above), to reach sparse material within MAX_PLIES.
        for (int game = 0; game < GAMES && endgameFens.size() < PER_CATEGORY; game++) {
            Board board = new Board();
            for (int ply = 1; ply <= MAX_PLIES; ply++) {
                List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
                if (legalMoves.isEmpty()) {
                    break; // checkmate/stalemate reached — start a fresh game
                }
                Move move = pickMove(board, legalMoves, endgameRandom);
                board.makeMove(move);
                String fen = fourFieldFen(board);

                if (pieceCount(fen) <= ENDGAME_PIECE_THRESHOLD && endgameFens.size() < PER_CATEGORY) {
                    endgameFens.add(fen);
                }
            }
        }

        endgameFens.addAll(readFensFromEpd(DRAW_FAILURES_EPD));

        writeCategory("opening.epd",
                "Opening (ply 1-15) — fixed-seed (" + SEED + ") random-legal self-play.", openingFens);
        writeCategory("middlegame.epd",
                "Middlegame (ply 16-40) — fixed-seed (" + SEED + ") random-legal self-play.", middlegameFens);
        writeCategory("endgame.epd",
                "Endgame (<= " + ENDGAME_PIECE_THRESHOLD + " pieces) — self-play tail + curated draw_failures.epd.",
                endgameFens);
        writeCategory("random-legal.epd",
                "Fixed-seed (" + SEED + ") random-legal — uniformly sampled across full-length self-play games.",
                randomLegalFens);
    }

    private void generateGoldenEvals() throws IOException {
        NnueNetwork network = TestNetworks.synthetic(8);
        NnueEvaluator evaluator = new NnueEvaluator(network);

        List<String> lines = new ArrayList<>();
        lines.add("fen,eval");
        for (String category : NnueCorpusCategories.FILE_NAMES) {
            for (String fen : readFensFromEpd(CORPUS_DIR.resolve(category))) {
                Board board = NnueCorpusCategories.toBoard(fen);
                evaluator.reset(board);
                short eval = (short) evaluator.evaluate(board);
                lines.add(fen + "," + eval);
            }
        }
        Files.write(CORPUS_DIR.resolve("golden-evals.csv"), lines);
    }

    private static void writeCategory(String fileName, String description, Set<String> fens) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# " + description);
        lines.add("# Regenerate: mvn -pl engine-core test -Dgroups=corpus-generation -Dcorpus.generate=true");
        lines.add("# Format: <fen-4-fields> id \"...\";");
        int i = 0;
        for (String fen : fens) {
            lines.add(fen + " id \"" + fileName.replace(".epd", "") + "-" + (i++) + "\";");
        }
        Files.write(CORPUS_DIR.resolve(fileName), lines);
    }

    /** Repo-wide corpus convention is a 4-field FEN — {@link Board#toFen()} includes the clocks too. */
    private static String fourFieldFen(Board board) {
        String[] tokens = board.toFen().split("\\s+");
        String fen = String.join(" ", tokens[0], tokens[1], tokens[2], tokens[3]);
        return repairTruncatedLastRank(fen);
    }

    /**
     * {@link Board#toFen()} never flushes a trailing run of empty squares on the last
     * rank (no rank-transition follows it to trigger the flush) — the affected squares
     * are genuinely empty so this repo's own {@code Board(String)} round-trips it
     * correctly regardless, but a committed corpus file should hold standards-valid FEN,
     * not lean on that internal coincidence. Repairs it externally rather than editing
     * {@code Board.java}, which is out of C-4's scope (no production behavior changes).
     */
    private static String repairTruncatedLastRank(String fen) {
        String[] fields = fen.split(" ");
        String[] rawRanks = fields[0].split("/");
        // A wholly-empty last rank never emits any segment at all (no piece to trigger
        // the piece-branch flush either) — 7 segments instead of 8, not just a short one.
        String[] ranks;
        if (rawRanks.length == 7) {
            ranks = Arrays.copyOf(rawRanks, 8);
            ranks[7] = "8";
        } else {
            ranks = rawRanks;
        }
        String lastRank = ranks[ranks.length - 1];
        int squares = 0;
        for (char c : lastRank.toCharArray()) {
            squares += Character.isDigit(c) ? (c - '0') : 1;
        }
        if (squares < 8) {
            ranks[ranks.length - 1] = lastRank + (8 - squares);
        }
        fields[0] = String.join("/", ranks);
        return String.join(" ", fields);
    }

    private static Move pickMove(Board board, List<Move> legalMoves, Random random) {
        if (random.nextDouble() < CAPTURE_BIAS) {
            List<Move> captures = new ArrayList<>();
            for (Move candidate : legalMoves) {
                if (board.getPiece(candidate.targetSquare) != Piece.None) {
                    captures.add(candidate);
                }
            }
            if (!captures.isEmpty()) {
                return captures.get(random.nextInt(captures.size()));
            }
        }
        return legalMoves.get(random.nextInt(legalMoves.size()));
    }

    private static List<String> readFensFromEpd(Path path) throws IOException {
        List<String> fens = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] tokens = trimmed.split("\\s+");
            fens.add(String.join(" ", tokens[0], tokens[1], tokens[2], tokens[3]));
        }
        return fens;
    }

    private static int pieceCount(String fen) {
        String placement = fen.split(" ")[0];
        int count = 0;
        for (int i = 0; i < placement.length(); i++) {
            if (Character.isLetter(placement.charAt(i))) {
                count++;
            }
        }
        return count;
    }
}
