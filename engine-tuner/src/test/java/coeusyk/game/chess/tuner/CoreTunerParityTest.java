package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.eval.Evaluator;
import coeusyk.game.chess.core.eval.MopUp;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity between the production evaluator and the tuner's copy of it (#274). The production
 * {@link Evaluator} is the reference. Both are compared as static scores from White's point of view with
 * the tuner running on the parameters extracted from the live evaluator. Positions are fixed and seeded,
 * so a failure names the same FEN on every run.
 */
class CoreTunerParityTest {
    static final String[] HAND_PICKED = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            // Pawns on the a and h files, adjacent-file pairs for the connected-pawn count (#213).
            "4k3/8/8/8/8/P6P/PP4PP/4K3 w - - 0 1",
            "4k3/pp4pp/p6p/8/8/8/8/4K3 b - - 0 1",
            "4k3/8/8/8/P6p/P6p/8/4K3 w - - 0 1",
            // Kings on opposite flanks.
            "6k1/5ppp/8/8/8/8/PPP5/1K4R1 w - - 0 1",
            "1k4r1/ppp5/8/8/8/8/5PPP/6K1 b - - 0 1",
            "r4rk1/ppp2ppp/8/8/8/8/PPP2PPP/2KR3R w - - 0 1",
            // Rook behind a passed pawn, on and off the pawn's file.
            "3rk3/8/8/4P3/8/8/8/4RK2 w - - 0 1",
            "3rk3/8/8/3P4/8/8/8/4RK2 w - - 0 1",
            "4k3/4r3/8/8/4p3/8/8/3RK3 b - - 0 1",
    };

    static List<String> seededPositions(int count, long seed) {
        Random rnd = new Random(seed);
        List<String> out = new ArrayList<>();
        while (out.size() < count) {
            Board b = new Board();
            int plies = 8 + rnd.nextInt(110);
            boolean ok = true;
            for (int i = 0; i < plies; i++) {
                List<Move> ms = new ArrayList<>(new MovesGenerator(b).getActiveMoves(b.getActiveColor()));
                if (ms.isEmpty() || b.isFiftyMoveRuleDraw()) { ok = false; break; }
                b.makeMove(ms.get(rnd.nextInt(ms.size())));
            }
            if (ok && !b.isActiveColorInCheck()) out.add(b.toFen());
        }
        return out;
    }

    static int coreWhitePov(Evaluator evaluator, String fen) {
        Board board = new Board(fen);
        int score = evaluator.evaluate(board);
        return Piece.isWhite(board.getActiveColor()) ? score : -score;
    }

    static int tunerWhitePov(double[] params, String fen) {
        return TunerEvaluator.evaluateStatic(new Board(fen), params);
    }



    /** Game phase as the evaluator computes it: knight and bishop 1, rook 2, queen 4, capped at 24. */
    static int phaseOf(Board b) {
        int phase = Long.bitCount(b.getWhiteKnights() | b.getBlackKnights() | b.getWhiteBishops() | b.getBlackBishops())
                + 2 * Long.bitCount(b.getWhiteRooks() | b.getBlackRooks())
                + 4 * Long.bitCount(b.getWhiteQueens() | b.getBlackQueens());
        return Math.min(24, phase);
    }

    /** The tuner leaves mop-up out on purpose (see the TunerEvaluator header), so those positions are not comparable. */
    static boolean mopUpActive(Board b) {
        return MopUp.evaluate(b, phaseOf(b)) != 0;
    }

    /** Random legal placements of the listed piece letters (white lower-case counts mirrored for black). */
    static List<String> placements(String pieces, int count, long seed) {
        Random rnd = new Random(seed);
        List<String> out = new ArrayList<>();
        int guard = 0;
        while (out.size() < count && guard++ < count * 200) {
            char[] grid = new char[64];
            java.util.Arrays.fill(grid, '.');
            put(grid, rnd, 'K'); put(grid, rnd, 'k');
            for (char c : pieces.toCharArray()) {
                int n = 1 + rnd.nextInt(c == 'p' ? 6 : 2);
                for (int i = 0; i < n; i++) { put(grid, rnd, c == 'p' ? 'P' : Character.toUpperCase(c)); put(grid, rnd, c == 'p' ? 'p' : c); }
            }
            StringBuilder fen = new StringBuilder();
            for (int r = 0; r < 8; r++) {
                int empty = 0;
                for (int f = 0; f < 8; f++) {
                    char c = grid[r * 8 + f];
                    if (c == '.') empty++; else { if (empty > 0) { fen.append(empty); empty = 0; } fen.append(c); }
                }
                if (empty > 0) fen.append(empty);
                if (r < 7) fen.append('/');
            }
            fen.append(rnd.nextBoolean() ? " w" : " b").append(" - - 0 1");
            String f = fen.toString();
            if (Board.isLegalFen(f)) out.add(f);
        }
        return out;
    }

    private static void put(char[] grid, Random rnd, char piece) {
        for (int tries = 0; tries < 50; tries++) {
            int sq = rnd.nextInt(64);
            boolean pawn = piece == 'P' || piece == 'p';
            if (pawn && (sq < 8 || sq >= 56)) continue;
            if (grid[sq] == '.') { grid[sq] = piece; return; }
        }
    }

    /** Compares both evaluators on every position and fails with the first differing FEN and the count. */
    private static void assertParity(List<String> fens, String label) {
        double[] params = EvalParams.extractFromCurrentEval();
        Evaluator evaluator = new Evaluator();
        int compared = 0, differing = 0;
        String first = null;
        for (String fen : fens) {
            if (mopUpActive(new Board(fen))) continue;
            compared++;
            int core = coreWhitePov(evaluator, fen);
            int tuner = tunerWhitePov(params, fen);
            if (core != tuner) {
                differing++;
                if (first == null) first = "core=" + core + " tuner=" + tuner + " " + fen;
            }
        }
        assertTrue(compared >= fens.size() * 3 / 4, label + ": too many positions skipped for mop-up (" + compared + "/" + fens.size() + ")");
        assertEquals(0, differing, label + ": " + differing + " of " + compared + " positions differ, first: " + first);
    }

    @Test
    void handPickedPositionsMatchTheProductionEvaluator() {
        assertParity(List.of(HAND_PICKED), "hand picked");
    }

    @Test
    void seededGamePositionsMatchTheProductionEvaluator() {
        assertParity(seededPositions(150, 274), "seeded games");
    }

    @Test
    void randomPlacementsOfEachPieceKindMatchTheProductionEvaluator() {
        String[] kinds = {"", "p", "n", "b", "r", "q", "pr", "pb", "pn", "pnbrq"};
        for (String kind : kinds) {
            assertParity(placements(kind, 60, 274), "placements '" + kind + "'");
        }
    }

    @Test
    void oppositeFlankKingsWithAnAttackerMatchTheProductionEvaluator() {
        assertParity(List.of(
                "6k1/5ppp/8/8/8/8/PPP5/1K4R1 w - - 0 1",
                "r4rk1/ppp2ppp/8/8/8/8/PPP2PPP/2KR3R w - - 0 1",
                "2kr3r/ppp2ppp/8/8/8/8/PPP2PPP/R4RK1 b - - 0 1",
                "r1bq1rk1/ppp2ppp/2n5/3p4/3P4/2N5/PPP2PPP/R1BQ1RK1 w - - 0 1"), "opposite flanks");
    }

    @Test
    void connectedPawnsOnTheEdgeFilesDoNotWrapAround() {
        assertParity(List.of(
                "4k3/8/8/8/8/P6P/PP4PP/4K3 w - - 0 1",
                "4k3/pp4pp/p6p/8/8/8/8/4K3 b - - 0 1",
                "4k3/8/8/8/P6p/P6p/8/4K3 w - - 0 1",
                "4k3/8/8/8/7P/P7/8/4K3 w - - 0 1"), "edge file pawns");
    }

    @Test
    void theBaselineParametersComeFromTheProductionEvaluator() {
        double[] p = EvalParams.extractFromCurrentEval();
        var cfg = Evaluator.DEFAULT_CONFIG;
        assertEquals(cfg.bishopPairMg(), p[EvalParams.IDX_BISHOP_PAIR_MG]);
        assertEquals(cfg.rookBehindPasserEg(), p[EvalParams.IDX_ROOK_BEHIND_PASSER_EG]);
        assertEquals(coeusyk.game.chess.core.eval.EvalParams.TEMPO, p[EvalParams.IDX_TEMPO]);
        assertEquals(coeusyk.game.chess.core.eval.EvalParams.HANGING_PENALTY, p[EvalParams.IDX_HANGING_PENALTY]);
        assertEquals(coeusyk.game.chess.core.eval.EvalParams.PASSED_PAWN_RANK_BONUS_EG[6], p[EvalParams.IDX_PASSED_EG_START + 5]);
    }
}
