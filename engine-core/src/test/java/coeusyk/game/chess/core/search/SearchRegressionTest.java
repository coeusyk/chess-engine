package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("regression")
class SearchRegressionTest {

    private static final int DEFAULT_DEPTH = 8;

    // ── Tactical positions (10) ──────────────────────────────────────
    // All are mate-in-1 or an undefended free piece — engine finds instantly.

    // T1: Back-rank mate — White Rook delivers Re8# (8 pieces).
    // Correct: e1e8
    static final String T1_FEN = "6k1/5ppp/8/8/8/8/5PPP/4R1K1 w - - 0 1";

    // T2: Back-rank mate — Rd8# traps the enemy king (3 pieces).
    // WK(g6) covers g7/h7/f7; Rd8 covers entire rank 8. Correct: d1d8
    static final String T2_FEN = "7k/8/6K1/8/8/8/8/3R4 w - - 0 1";

    // T3: King outpost — Kf6 sets up unstoppable Rh8# (3 pieces).
    // Rh8 is only a check (BK can take the rook); Kf6 followed by Rh8# is forced mate. Correct: g6f6
    static final String T3_FEN = "6k1/8/6KR/8/8/8/8/8 w - - 0 1";

    // T4: Rook corridor mate — Rb1# (3 pieces).
    // WK(g3) covers g2/h2; Rb1 covers entire rank 1. Correct: b2b1
    static final String T4_FEN = "8/8/8/8/8/6K1/1R6/7k w - - 0 1";

    // T5: Pawn promotes to queen with check — a8=Q# (3 pieces).
    // WK(c1) covers b1/b2; Qa8 covers file-a. Correct: a7a8q
    static final String T5_FEN = "8/P7/8/8/8/8/8/k1K5 w - - 0 1";

    // T6: Free rook capture — Qxd2 wins an undefended rook (4 pieces).
    // Correct: d1d2
    static final String T6_FEN = "8/8/5k2/8/8/8/3r4/K2Q4 w - - 0 1";

    // T7: Free bishop capture — Bxc4 wins an undefended bishop (4 pieces).
    // WB on f1 captures Bc4 diagonally via f1-e2-d3-c4. Correct: f1c4
    static final String T7_FEN = "8/8/4k3/8/2b5/8/8/4KB2 w - - 0 1";

    // T8: Free rook capture — Rxe3 wins an undefended rook (4 pieces).
    // Correct: e1e3
    static final String T8_FEN = "8/8/4k3/8/8/4r3/8/4R1K1 w - - 0 1";

    // T9: Free knight capture — Nxf4 wins an undefended knight (4 pieces).
    // Correct: d3f4
    static final String T9_FEN = "8/8/4k3/8/5n2/3N4/8/4K3 w - - 0 1";

    // T10: Free bishop capture — Rxd3 wins an undefended bishop (4 pieces).
    // Correct: d1d3
    static final String T10_FEN = "8/8/4k3/8/8/3b4/8/3RK3 w - - 0 1";

    // ── Positional positions (10) ────────────────────────────────────
    // All are K+P vs K endgames (3–5 pieces) — engine determines best plan.

    // P1: Passed pawn — White pushes the f-passer toward promotion (3 pieces).
    static final String P1_FEN = "8/5k2/8/5P2/8/8/8/4K3 w - - 0 1";

    // P2: K+P vs K — White king and pawn vs lone king (4 pieces).
    static final String P2_FEN = "8/8/2k5/8/2KP4/8/8/8 w - - 0 1";

    // P3: K+P vs K with black out of square — White wins (4 pieces).
    static final String P3_FEN = "8/8/8/8/4k3/8/3PK3/8 w - - 0 1";

    // P4: K+P vs K — king escorts pawn from the front (4 pieces).
    static final String P4_FEN = "8/8/3k4/8/3PK3/8/8/8 w - - 0 1";

    // P5: Two connected passed pawns vs king — push the c/b pawns (5 pieces).
    static final String P5_FEN = "8/8/3k4/8/1PP5/8/8/2K5 w - - 0 1";

    // P6: K+2P vs K — two pawns escort plan (4 pieces).
    static final String P6_FEN = "8/8/4k3/8/4KP2/8/8/8 w - - 0 1";

    // P7: White passed pawn nearly promoting vs lone king (4 pieces).
    static final String P7_FEN = "8/3P4/8/8/8/8/8/3K1k2 w - - 0 1";

    // P8: Two kingside pawns vs king — advance correctly to avoid stalemate (5 pieces).
    static final String P8_FEN = "8/8/8/8/6k1/8/6PP/6K1 w - - 0 1";

    // P9: King centralization with pawn — advance plan (4 pieces).
    static final String P9_FEN = "8/8/3k4/8/8/3KP3/8/8 w - - 0 1";

    // P10: K+P vs K — break opposition with king sidestep (4 pieces).
    static final String P10_FEN = "8/8/8/4k3/4P3/4K3/8/8 w - - 0 1";

    // ── Endgame positions (10) ───────────────────────────────────────
    // All are 3–6 piece theoretical endings — fast at depth 8.

    // E1: KQ vs K — queen and king drive lone king to the edge (3 pieces).
    static final String E1_FEN = "4k3/8/8/8/8/8/8/4KQ2 w - - 0 1";

    // E2: KR vs K — rook cuts off the enemy king, box it in (3 pieces).
    static final String E2_FEN = "4k3/8/8/8/8/8/8/4KR2 w - - 0 1";

    // E3: KRP vs K — rook + passed pawn vs lone king, easy win (5 pieces).
    static final String E3_FEN = "8/8/5k2/8/5P2/8/8/5RK1 w - - 0 1";

    // E4: K+P opposition — White uses opposition to escort pawn (4 pieces).
    static final String E4_FEN = "8/8/4k3/8/4K3/4P3/8/8 w - - 0 1";

    // E5: Rook behind passed pawn — engine advances king or pawn (5 pieces).
    static final String E5_FEN = "8/4k3/8/4P3/8/8/R7/4K3 w - - 0 1";

    // E6: Two connected passed pawns vs distant king (5 pieces) — advance pawns.
    static final String E6_FEN = "8/8/8/5k2/1PP5/8/2K5/8 w - - 0 1";

    // E7: KBN vs K — drive king toward a corner (4 pieces).
    static final String E7_FEN = "8/8/8/4k3/8/8/1K1NB3/8 w - - 0 1";

    // E8: Rook vs pawn race — both sides have a pawn; rook side wins (6 pieces).
    static final String E8_FEN = "7k/p7/8/8/8/8/7P/6RK w - - 0 1";

    // E9: King + pawn advance with support (4 pieces).
    static final String E9_FEN = "8/8/8/3k4/3P4/3K4/8/8 w - - 0 1";

    // E10: Philidor position — Black defends with third-rank rook check (5 pieces, Black to move).
    static final String E10_FEN = "8/8/5KPk/8/8/8/r7/5R2 b - - 0 1";

    // ── Stability test: assert every position matches its captured bestmove ──

    @ParameterizedTest(name = "{0}")
    @MethodSource("regressionPositions")
    @Tag("regression")
    void bestMoveIsStable(String name, String fen, String expectedMove) {
        Board board = new Board(fen);
        SearchResult result = new Searcher().searchDepth(board, DEFAULT_DEPTH);
        assertEquals(expectedMove, toUci(result.bestMove()),
                "Regression: bestmove changed for " + name + " | " + fen);
    }

    static Stream<Arguments> regressionPositions() {
        return Stream.of(
            // Tactical
            Arguments.of("T1",  T1_FEN,  "e1e8"),
            Arguments.of("T2",  T2_FEN,  "d1d8"),
            Arguments.of("T3",  T3_FEN,  "g6f6"),
            Arguments.of("T4",  T4_FEN,  "b2b1"),
            Arguments.of("T5",  T5_FEN,  "a7a8q"),
            Arguments.of("T6",  T6_FEN,  "d1d2"),
            Arguments.of("T7",  T7_FEN,  "f1c4"),
            Arguments.of("T8",  T8_FEN,  "e1e3"),
            Arguments.of("T9",  T9_FEN,  "d3f4"),
            Arguments.of("T10", T10_FEN, "d1d3"),
            // Positional
            Arguments.of("P1",  P1_FEN,  "e1e2"),
            Arguments.of("P2",  P2_FEN,  "d4d5"),
            Arguments.of("P3",  P3_FEN,  "d2d3"),
            Arguments.of("P4",  P4_FEN,  "d4d5"),
            Arguments.of("P5",  P5_FEN,  "c1d2"),
            Arguments.of("P6",  P6_FEN,  "f4f5"),
            Arguments.of("P7",  P7_FEN,  "d7d8q"),
            Arguments.of("P8",  P8_FEN,  "g1f2"),
            Arguments.of("P9",  P9_FEN,  "e3e4"),
            Arguments.of("P10", P10_FEN, "e3f3"),
            // Endgame
            Arguments.of("E1",  E1_FEN,  "f1f6"),
            Arguments.of("E2",  E2_FEN,  "f1f6"),
            Arguments.of("E3",  E3_FEN,  "f4f5"),
            Arguments.of("E4",  E4_FEN,  "e4f4"),
            Arguments.of("E5",  E5_FEN,  "a2e2"),
            Arguments.of("E6",  E6_FEN,  "b4b5"),
            Arguments.of("E7",  E7_FEN,  "b2c3"),
            Arguments.of("E8",  E8_FEN,  "g1g5"),
            Arguments.of("E9",  E9_FEN,  "d3e3"),
            Arguments.of("E10", E10_FEN, "a2a6")
        );
    }

    // ── Discovery: run this to capture bestmoves at depth DEFAULT_DEPTH ──

    @Test
    void discoverBestMoves() {
        String[] names = {
            "T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8", "T9", "T10",
            "P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10",
            "E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10"
        };
        String[] fens = {
            T1_FEN, T2_FEN, T3_FEN, T4_FEN, T5_FEN, T6_FEN, T7_FEN, T8_FEN, T9_FEN, T10_FEN,
            P1_FEN, P2_FEN, P3_FEN, P4_FEN, P5_FEN, P6_FEN, P7_FEN, P8_FEN, P9_FEN, P10_FEN,
            E1_FEN, E2_FEN, E3_FEN, E4_FEN, E5_FEN, E6_FEN, E7_FEN, E8_FEN, E9_FEN, E10_FEN
        };

        System.out.println("=== REGRESSION BESTMOVE DISCOVERY (depth " + DEFAULT_DEPTH + ") ===");
        for (int i = 0; i < fens.length; i++) {
            try {
                Board board = new Board(fens[i]);
                Searcher searcher = new Searcher();
                long start = System.currentTimeMillis();
                SearchResult result = searcher.searchDepth(board, DEFAULT_DEPTH);
                long elapsed = System.currentTimeMillis() - start;
                String uci = result.bestMove() != null ? toUci(result.bestMove()) : "null";
                System.out.printf("RESULT %s | bestmove=%s | score=%d | time=%dms%n",
                        names[i], uci, result.scoreCp(), elapsed);
            } catch (Exception e) {
                System.out.printf("ERROR  %s | %s%n", names[i], e.getMessage());
            }
        }
        System.out.println("=== END DISCOVERY ===");
    }

    // ── Helper ──────────────────────────────────────────────────────

    private static String toUci(Move move) {
        int startFile = move.startSquare % 8;
        int startRank = 8 - (move.startSquare / 8);
        int targetFile = move.targetSquare % 8;
        int targetRank = 8 - (move.targetSquare / 8);
        String uci = "" + (char) ('a' + startFile) + startRank
                + (char) ('a' + targetFile) + targetRank;
        if ("promote-q".equals(move.reaction)) uci += "q";
        else if ("promote-r".equals(move.reaction)) uci += "r";
        else if ("promote-b".equals(move.reaction)) uci += "b";
        else if ("promote-n".equals(move.reaction)) uci += "n";
        return uci;
    }
}
