package coeusyk.game.chess.core.eval;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvaluatorTest {

    private final Evaluator evaluator = new Evaluator();

    @Test
    void startingPositionEvaluatesToZero() {
        Board board = new Board();
        int score = evaluator.evaluate(board);
        assertEquals(0, score, "Starting position should evaluate to 0 (equal material)");
    }

    @Test
    void scoreIsFromSideToMovePerspective() {
        // White has extra knight (black missing b8 knight)
        Board whiteAdvantage = new Board("r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1");
        int blackToMove = evaluator.evaluate(whiteAdvantage);
        assertTrue(blackToMove < 0, "Black to move should see negative score when white has extra knight");

        // Position with white having extra pawn
        Board extraPawn = new Board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        int equalMaterial = evaluator.evaluate(extraPawn);
        assertEquals(0, equalMaterial, "Equal material should evaluate to 0");
    }

    @Test
    void materialAdvantageDetectedCorrectly() {
        // White has an extra queen (no black queen)
        Board board = new Board("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        int score = evaluator.evaluate(board);
        assertTrue(score > 0, "White to move with extra queen should be positive");
        // Tapered eval blends MG/EG — score won't reach pure MG queen value
        // but should exceed the EG queen value (936)
        assertTrue(score >= Evaluator.egMaterialValue(5),
                "Score should be at least one queen EG value");
    }

    @Test
    void evalSymmetryForStartPosition() {
        Board board = new Board();
        int whiteScore = evaluator.evaluate(board);
        // Starting position is symmetric, score should be 0 from either side
        assertEquals(0, whiteScore);
    }

    @Test
    void evalSymmetryMirroredPositions() {
        // White has extra pawn on e4
        String whitePawnUp = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
        // Mirror: black has extra pawn on e5
        String blackPawnUp = "rnbqkbnr/pppp1ppp/8/4p3/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

        Board whiteUp = new Board(whitePawnUp);
        Board blackUp = new Board(blackPawnUp);

        int evalWhiteUp = evaluator.evaluate(whiteUp);
        int evalBlackUp = evaluator.evaluate(blackUp);

        // eval(pos) == -eval(mirror(pos))
        // White pawn up, black to move => negative from black's perspective
        // Black pawn up, white to move => negative from white's perspective
        assertEquals(evalWhiteUp, evalBlackUp,
                "Mirrored positions should produce equal scores from the side-to-move perspective");
    }

    @Test
    void evalSymmetryForMultiplePieceImbalances() {
        // White missing a knight
        String whiteDown = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/R1BQKBNR w KQkq - 0 1";
        // Black missing a knight (mirror)
        String blackDown = "r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1";

        Board boardA = new Board(whiteDown);
        Board boardB = new Board(blackDown);

        int evalA = evaluator.evaluate(boardA);
        int evalB = evaluator.evaluate(boardB);

        assertEquals(evalA, evalB,
                "eval(white knight down, white to move) == eval(black knight down, black to move)");
        assertTrue(evalA < 0, "Side to move missing a knight should see negative score");
    }

    @Test
    void pstAffectsEvaluation() {
        // Place a white knight on e4 (square 28) vs a1 (square 0).
        // Central knight should score higher due to PST bonus.
        // FEN: only kings + one white knight on e4
        Board centralKnight = new Board("4k3/8/8/8/4N3/8/8/4K3 w - - 0 1");
        // White knight on a1
        Board rimKnight = new Board("4k3/8/8/8/8/8/8/N3K3 w - - 0 1");

        int centralScore = evaluator.evaluate(centralKnight);
        int rimScore = evaluator.evaluate(rimKnight);
        assertTrue(centralScore > rimScore,
                "Central knight (e4) should score higher than rim knight (a1) due to PST bonus");
    }

    @Test
    void pstTableLookupCorrect() {
        // Tables stored in display order: a8=0, h1=63
        // Board also uses a8=0, so white PST lookup is direct (no mirror).
        // White knight on e4 → board sq 36 → table index 36
        // MG_KNIGHT row 4 (32-39), index 4 = 28
        assertEquals(28, PieceSquareTables.mg(2, 36));
        // EG_KNIGHT row 4, index 4 = 16
        assertEquals(16, PieceSquareTables.eg(2, 36));
        // MG_PAWN row 4, index 4 = 17
        assertEquals(17, PieceSquareTables.mg(1, 36));
    }

    @Test
    void mgAndEgMaterialValuesAreCorrect() {
        assertEquals(82, Evaluator.mgMaterialValue(1));   // Pawn
        assertEquals(337, Evaluator.mgMaterialValue(2));  // Knight
        assertEquals(365, Evaluator.mgMaterialValue(3));  // Bishop
        assertEquals(477, Evaluator.mgMaterialValue(4));  // Rook
        assertEquals(1025, Evaluator.mgMaterialValue(5)); // Queen

        assertEquals(94, Evaluator.egMaterialValue(1));   // Pawn
        assertEquals(281, Evaluator.egMaterialValue(2));  // Knight
        assertEquals(297, Evaluator.egMaterialValue(3));  // Bishop
        assertEquals(512, Evaluator.egMaterialValue(4));  // Rook
        assertEquals(936, Evaluator.egMaterialValue(5));  // Queen
    }

    @Test
    void phaseIsMaxAtStartPosition() {
        Board board = new Board();
        // 4 knights (4*1) + 4 bishops (4*1) + 4 rooks (4*2) + 2 queens (2*4) = 24
        assertEquals(24, evaluator.computePhase(board));
    }

    @Test
    void phaseIsZeroWithOnlyKingsAndPawns() {
        Board board = new Board("4k3/pppppppp/8/8/8/8/PPPPPPPP/4K3 w - - 0 1");
        assertEquals(0, evaluator.computePhase(board));
    }

    @Test
    void phaseClampedToTwentyFour() {
        // Starting position already has phase 24 (max) — no way to exceed with normal material
        Board board = new Board();
        assertTrue(evaluator.computePhase(board) <= 24);
    }

    @Test
    void taperedEvalInterpolatesCorrectly() {
        // Kings + pawns only: phase = 0 → pure endgame score
        Board endgame = new Board("4k3/pppppppp/8/8/8/8/PPPPPPPP/4K3 w - - 0 1");
        int endgameScore = evaluator.evaluate(endgame);
        // Equal material, symmetric → should be 0
        assertEquals(0, endgameScore);

        // Starting position: phase = 24 → pure middlegame score
        Board startPos = new Board();
        int startScore = evaluator.evaluate(startPos);
        assertEquals(0, startScore);
    }

    @Test
    void kingPstBehaviorChangesWithPhase() {
        // In the endgame, a centralized king should score higher than a corner king.
        // In the middlegame, the king should prefer safety (corner/castled position).
        // EG king PST: center squares have high values, corners low
        // MG king PST: center squares have very low values, castled positions higher

        // King-only + pawns endgame (phase = 0, pure EG)
        Board centralKingEg = new Board("8/pppppppp/8/8/4K3/8/PPPPPPPP/8 w - - 0 1");
        Board cornerKingEg  = new Board("8/pppppppp/8/8/8/8/PPPPPPPP/K7 w - - 0 1");

        int centralEg = evaluator.evaluate(centralKingEg);
        int cornerEg  = evaluator.evaluate(cornerKingEg);
        assertTrue(centralEg > cornerEg,
                "In endgame, centralized king (e4) should score higher than corner king (a1)");

        // Full piece middlegame (phase = 24, pure MG)
        Board centralKingMg = new Board("rnbqkbnr/pppppppp/8/8/4K3/8/PPPPPPPP/RNBQ1BNR w kq - 0 1");
        Board safeKingMg    = new Board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");

        int centralMg = evaluator.evaluate(centralKingMg);
        int safeMg    = evaluator.evaluate(safeKingMg);
        assertTrue(safeMg > centralMg,
                "In middlegame, safe king (e1) should score higher than centralized king (e4)");
    }
}
