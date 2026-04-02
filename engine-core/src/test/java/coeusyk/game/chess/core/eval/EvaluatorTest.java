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
        // Equal material + PSTs are symmetric, so the only offset is the TEMPO bonus
        // for the side to move (+15 for White at the start position).
        assertEquals(Evaluator.DEFAULT_CONFIG.tempo(), score, "Starting position should evaluate to TEMPO cp (side-to-move bonus only)");
    }

    @Test
    void scoreIsFromSideToMovePerspective() {
        // White has extra knight (black missing b8 knight)
        Board whiteAdvantage = new Board("r1bqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1");
        int blackToMove = evaluator.evaluate(whiteAdvantage);
        assertTrue(blackToMove < 0, "Black to move should see negative score when white has extra knight");

        // Position with equal material, White to move — eval equals TEMPO (side-to-move bonus).
        Board extraPawn = new Board("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        int equalMaterial = evaluator.evaluate(extraPawn);
        assertEquals(Evaluator.DEFAULT_CONFIG.tempo(), equalMaterial, "Equal material should evaluate to TEMPO cp (side-to-move bonus)");
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
        // Starting position is materially/positionally symmetric; eval = TEMPO (side-to-move bonus).
        assertEquals(Evaluator.DEFAULT_CONFIG.tempo(), whiteScore);
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
        // MG_KNIGHT row 4 (32-39), col 4 = 15 (Texel V2-tuned 2026-04-XX)
        assertEquals(15, PieceSquareTables.mg(2, 36));
        // EG_KNIGHT row 4 (32-39), col 4 = 10 (Texel V2-tuned 2026-04-XX)
        assertEquals(10, PieceSquareTables.eg(2, 36));
        // MG_PAWN row 4 (32-39), col 4 = 12 (unchanged)
        assertEquals(12, PieceSquareTables.mg(1, 36));
    }

    @Test
    void mgAndEgMaterialValuesAreCorrect() {
        assertEquals(100, Evaluator.mgMaterialValue(1));   // Pawn   (Texel-tuned 2026-04-01)
        assertEquals(391, Evaluator.mgMaterialValue(2));  // Knight
        assertEquals(428, Evaluator.mgMaterialValue(3));  // Bishop (Texel V2)
        assertEquals(558, Evaluator.mgMaterialValue(4));  // Rook (Texel V2)
        assertEquals(1200, Evaluator.mgMaterialValue(5)); // Queen

        assertEquals(89, Evaluator.egMaterialValue(1));   // Pawn   (Texel V2)
        assertEquals(287, Evaluator.egMaterialValue(2));  // Knight
        assertEquals(311, Evaluator.egMaterialValue(3));  // Bishop (Texel V2)
        assertEquals(555, Evaluator.egMaterialValue(4));  // Rook (Texel V2)
        assertEquals(1040, Evaluator.egMaterialValue(5)); // Queen (Texel V2)
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
        // Equal material, symmetric → eval = TEMPO (side-to-move bonus only)
        assertEquals(Evaluator.DEFAULT_CONFIG.tempo(), endgameScore);

        // Starting position: phase = 24 → pure middlegame score
        Board startPos = new Board();
        int startScore = evaluator.evaluate(startPos);
        assertEquals(Evaluator.DEFAULT_CONFIG.tempo(), startScore);
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

    @Test
    void mobilityRewardsActivePieces() {
        // A knight in the center (e4) with many moves should score better than a corner knight (a1)
        // Both positions: kings + one white knight only (pure EG since phase=0+1=1)
        Board activeKnight = new Board("4k3/8/8/8/4N3/8/8/4K3 w - - 0 1");
        Board trappedKnight = new Board("4k3/8/8/8/8/8/8/N3K3 w - - 0 1");

        int activeScore = evaluator.evaluate(activeKnight);
        int trappedScore = evaluator.evaluate(trappedKnight);
        assertTrue(activeScore > trappedScore,
                "Central knight should have higher mobility score than corner knight");
    }

    @Test
    void mobilityPenalizesRestrictedRook() {
        // Both positions: equal material (K+R+2P vs K+2P), only rook placement differs
        // Hemmed rook: R on a1 blocked by own pawns on a2, b2
        Board hemmedRook = new Board("4k3/pp6/8/8/8/8/PP6/R3K3 w - - 0 1");
        // Active rook: R on d4 (open board), pawns on a2, b2
        Board activeRook = new Board("4k3/pp6/8/8/3R4/8/PP6/4K3 w - - 0 1");

        int hemmedScore     = evaluator.evaluate(hemmedRook);
        int activeRookScore = evaluator.evaluate(activeRook);
        assertTrue(activeRookScore > hemmedScore,
                "Open rook should score better than hemmed-in rook");
    }

    @Test
    void mobilityEvalSymmetry() {
        // Same position mirrored — mobility should be symmetric
        String whiteActive = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
        String blackActive = "rnbqkbnr/pppp1ppp/8/4p3/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

        int evalWhite = evaluator.evaluate(new Board(whiteActive));
        int evalBlack = evaluator.evaluate(new Board(blackActive));
        assertEquals(evalWhite, evalBlack,
                "Mirrored positions should give symmetric mobility scores");
    }

    @Test
    void enemyPawnAttacksReduceMobility() {
        // Knight on e4 with no enemy pawns → high mobility
        Board noPawnControl = new Board("4k3/8/8/8/4N3/8/8/4K3 w - - 0 1");
        // Knight on e4 with black pawns on d5 and f5 controlling e4-adjacent squares
        Board pawnControl = new Board("4k3/8/8/3p1p2/4N3/8/8/4K3 w - - 0 1");

        int noPawnScore = evaluator.evaluate(noPawnControl);
        int withPawnScore = evaluator.evaluate(pawnControl);
        // The pawn-controlled position subtracts material (opponent has pawns)
        // but the mobility of the knight is also reduced, so score should differ
        // White should be relatively worse when opponent pawns control knight's squares
        assertTrue(noPawnScore > withPawnScore,
                "Enemy pawns controlling squares should reduce knight mobility advantage");
    }

    // --- Pawn structure tests ---

    @Test
    void passedPawnBonusScalesByRank() {
        // e7 = sq 12 (row 1, rank 7): bonusIndex 6 → MG 100, EG 165
        // e3 = sq 44 (row 5, rank 3): bonusIndex 2 → MG 10, EG 20
        long e7 = 1L << 12;
        long e3 = 1L << 44;
        int[] scoreHigh = PawnStructure.evaluate(e7, 0L);
        int[] scoreLow = PawnStructure.evaluate(e3, 0L);
        assertTrue(scoreHigh[0] > scoreLow[0],
                "Rank 7 passed pawn should have higher MG bonus than rank 3");
        assertTrue(scoreHigh[1] > scoreLow[1],
                "Rank 7 passed pawn should have higher EG bonus than rank 3");
    }

    @Test
    void isolatedPawnPenaltyFires() {
        // Both setups: 2 white pawns at same rank (row 4), no enemy pawns → both passed equally
        // Isolated: pawns on a4 (sq 32) and h4 (sq 39) — no adjacent file neighbors
        // Together: pawns on e4 (sq 36) and f4 (sq 37) — adjacent to each other
        long isolated = (1L << 32) | (1L << 39);
        long together = (1L << 36) | (1L << 37);

        int[] scoreIso = PawnStructure.evaluate(isolated, 0L);
        int[] scoreTog = PawnStructure.evaluate(together, 0L);

        assertTrue(scoreTog[0] > scoreIso[0],
                "Isolated pawns should have lower MG score");
        assertTrue(scoreTog[1] > scoreIso[1],
                "Isolated pawns should have lower EG score");
    }

    @Test
    void doubledPawnPenaltyFires() {
        // Doubled: e4 (sq 36) + e2 (sq 52) — same file
        // Spread: e4 (sq 36) + c2 (sq 50) — different files
        // Both have same passed bonuses (same ranks), both fully isolated
        long doubled = (1L << 36) | (1L << 52);
        long spread = (1L << 36) | (1L << 50);

        int[] scoreDoubled = PawnStructure.evaluate(doubled, 0L);
        int[] scoreSpread = PawnStructure.evaluate(spread, 0L);

        assertTrue(scoreSpread[0] >= scoreDoubled[0],
                "Doubled pawns MG: spread >= doubled (DOUBLED_MG=0, Texel-tuned)");
        assertTrue(scoreSpread[1] > scoreDoubled[1],
                "Doubled pawns should score lower in EG");
    }

    @Test
    void pawnStructureEvalSymmetry() {
        // White pawn on e5, black to move — mirror: black pawn on e4, white to move
        // Kings on e1/e8 in both (symmetric)
        String whitePassedUp = "4k3/8/8/4P3/8/8/8/4K3 b - - 0 1";
        String blackPassedUp = "4k3/8/8/8/4p3/8/8/4K3 w - - 0 1";

        int evalWhite = evaluator.evaluate(new Board(whitePassedUp));
        int evalBlack = evaluator.evaluate(new Board(blackPassedUp));

        assertEquals(evalWhite, evalBlack,
                "Mirrored pawn structure positions should produce symmetric eval");
    }

    // --- King safety tests ---

    @Test
    void pawnShieldBonusFires() {
        // White king g1 with shield pawns f2,g2,h2 vs no pawns at all
        Board withShield = new Board("4k3/8/8/8/8/8/5PPP/6K1 w - - 0 1");
        Board noShield = new Board("4k3/8/8/8/8/8/8/6K1 w - - 0 1");

        int safetyWith = KingSafety.evaluate(withShield);
        int safetyWithout = KingSafety.evaluate(noShield);

        assertTrue(safetyWith > safetyWithout,
                "King with pawn shield should have better safety score");
    }

    @Test
    void openFilePenaltyNearKing() {
        // Both kings castled g1/g8. White missing g-pawn (half-open), black full shield.
        Board halfOpen = new Board("6k1/5ppp/8/8/8/8/5P1P/6K1 w - - 0 1");
        Board closed = new Board("6k1/5ppp/8/8/8/8/5PPP/6K1 w - - 0 1");

        int safetyHalfOpen = KingSafety.evaluate(halfOpen);
        int safetyClosed = KingSafety.evaluate(closed);

        assertTrue(safetyClosed > safetyHalfOpen,
                "Open file near king should reduce safety");
    }

    @Test
    void attackerWeightReducesSafety() {
        // Black queen on f3 attacks white king zone on g1
        Board queenAttacks = new Board("6k1/5ppp/8/8/8/5q2/5PPP/6K1 w - - 0 1");
        // No queen — no attacker penalty
        Board noAttacker = new Board("6k1/5ppp/8/8/8/8/5PPP/6K1 w - - 0 1");

        int safetyAttacked = KingSafety.evaluate(queenAttacks);
        int safetyClean = KingSafety.evaluate(noAttacker);

        assertTrue(safetyClean > safetyAttacked,
                "Enemy queen attacking king zone should reduce safety");
    }

    @Test
    void kingSafetyEvalSymmetry() {
        // White king g1 with shield, black queen attacks — black to move
        String whiteDefending = "4k3/8/8/8/8/5q2/5PPP/6K1 b - - 0 1";
        // Mirror: black king g8 with shield, white queen attacks — white to move
        String blackDefending = "6k1/5ppp/5Q2/8/8/8/8/4K3 w - - 0 1";

        int evalA = evaluator.evaluate(new Board(whiteDefending));
        int evalB = evaluator.evaluate(new Board(blackDefending));

        assertEquals(evalA, evalB,
                "King safety should be symmetric for mirrored positions");
    }

    // --- Mop-up evaluation tests ---

    @Test
    void mopUpFiresInEndgameWithAdvantage() {
        // KQ vs K: phase = 4, material diff >> 400
        Board kqVsK = new Board("4k3/8/8/8/8/8/8/4KQ2 w - - 0 1");
        int phase = evaluator.computePhase(kqVsK);
        assertTrue(phase <= 8);
        int mopUp = MopUp.evaluate(kqVsK, phase);
        assertTrue(mopUp > 0, "Mop-up should give positive bonus for white queen advantage");
    }

    @Test
    void mopUpDoesNotFireInMiddlegame() {
        // White has extra queen but full piece complement (phase > 8)
        Board noBlackQueen = new Board("rnb1kbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        int phase = evaluator.computePhase(noBlackQueen);
        assertTrue(phase > 8, "Phase should be middlegame");
        int mopUp = MopUp.evaluate(noBlackQueen, phase);
        assertEquals(0, mopUp, "Mop-up should not fire when phase > 8");
    }

    @Test
    void mopUpRewardsEnemyKingOnEdge() {
        // KQ vs K, black king on corner a1 vs center e5
        Board cornerKing = new Board("8/8/8/8/8/8/8/k3KQ2 w - - 0 1");
        Board centerKing = new Board("8/8/8/4k3/8/8/8/4KQ2 w - - 0 1");
        int p1 = evaluator.computePhase(cornerKing);
        int p2 = evaluator.computePhase(centerKing);
        assertTrue(MopUp.evaluate(cornerKing, p1) > MopUp.evaluate(centerKing, p2),
                "Enemy king on corner should give more mop-up bonus");
    }

    @Test
    void mopUpRewardsKingProximity() {
        // White king adjacent to black king vs far away
        Board close = new Board("8/8/8/4k3/4K3/8/8/7Q w - - 0 1");
        Board far = new Board("8/8/8/4k3/8/8/8/K6Q w - - 0 1");
        int p1 = evaluator.computePhase(close);
        int p2 = evaluator.computePhase(far);
        assertTrue(MopUp.evaluate(close, p1) > MopUp.evaluate(far, p2),
                "Winning king near enemy should give higher mop-up bonus");
    }

    @Test
    void mopUpEvalSymmetry() {
        // White has KQ, black has K — black to move
        String whiteWins = "4k3/8/8/8/8/8/8/3QK3 b - - 0 1";
        // Mirror: black has kq, white has K — white to move
        String blackWins = "3qk3/8/8/8/8/8/8/4K3 w - - 0 1";

        int evalA = evaluator.evaluate(new Board(whiteWins));
        int evalB = evaluator.evaluate(new Board(blackWins));

        assertEquals(evalA, evalB,
                "Mop-up evaluation should be symmetric for mirrored positions");
    }
}
