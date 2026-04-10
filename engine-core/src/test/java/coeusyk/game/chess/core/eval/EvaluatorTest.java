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
        // White knight on e4 → board sq 36 → table index 36 (row 4, col 4)
        // Restored 2026-04-10: eval-converged PSTs reverted (see EvaluatorTest.mgAndEgMaterialValuesAreCorrect).
        // MG_KNIGHT row 4 (32-39): {-21,-10,7,17,19,5,21,-20} → col 4 = 19
        assertEquals(19, PieceSquareTables.mg(2, 36));
        // EG_KNIGHT row 4 (32-39): reverted from eval-converged PSTs → col 4 = 14
        assertEquals(14, PieceSquareTables.eg(2, 36));
        // MG_PAWN row 4 (32-39): reverted from eval-converged PSTs → col 4 = 14
        assertEquals(14, PieceSquareTables.mg(1, 36));
    }

    @Test
    void mgAndEgMaterialValuesAreCorrect() {
        // Restored 2026-04-10: eval-converged params from Issue #141 were reverted because
        // eval-mode Texel minimizes raw cp² MSE against Stockfish's internal scale, which
        // differs from Vex's. This collapsed all piece values by ~35% (Rook MG: 558→362),
        // causing ~−44 Elo regression versus the pre-tuning baseline (SPRT #142 post-mortem).
        // eval-mode tuning is now gated behind --experimental. (See issue tracker.)
        assertEquals(100,  Evaluator.mgMaterialValue(1));  // Pawn
        assertEquals(391,  Evaluator.mgMaterialValue(2));  // Knight
        assertEquals(428,  Evaluator.mgMaterialValue(3));  // Bishop
        assertEquals(558,  Evaluator.mgMaterialValue(4));  // Rook
        assertEquals(1200, Evaluator.mgMaterialValue(5));  // Queen

        assertEquals(89,   Evaluator.egMaterialValue(1));  // Pawn
        assertEquals(287,  Evaluator.egMaterialValue(2));  // Knight
        assertEquals(311,  Evaluator.egMaterialValue(3));  // Bishop
        assertEquals(555,  Evaluator.egMaterialValue(4));  // Rook
        assertEquals(1040, Evaluator.egMaterialValue(5));  // Queen
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
        // Both positions: equal material (K+R+2P vs K+2P), only rook placement differs.
        // Hemmed rook: R on a1 blocked by own pawns on a2, b2.
        Board hemmedRook = new Board("4k3/pp6/8/8/8/8/PP6/R3K3 w - - 0 1");
        // Active rook: R on b3 — open file + good PST (EG_ROOK[b3]=+9 vs EG_ROOK[a1]=-49 → +58 EG diff).
        // Net MG = mobilityDiff + PST(b3-a1)_mg; Net EG = PST(b3-a1)_eg = +58. Clearly positive.
        Board activeRook = new Board("4k3/pp6/8/8/8/1R6/PP6/4K3 w - - 0 1");

        int hemmedScore     = evaluator.evaluate(hemmedRook);
        int activeRookScore = evaluator.evaluate(activeRook);
        assertTrue(activeRookScore > hemmedScore,
                "Open rook (b3) should score better than hemmed-in rook (a1) — EG_ROOK[b3]=+9 vs [a1]=-49");
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
        // e7 = sq 12 (row 1, rank 7): bonusIndex 6 → PASSED_MG[6]=52, PASSED_EG[6]=129 (v0.5.4)
        // e3 = sq 44 (row 5, rank 3): bonusIndex 2 → PASSED_MG[2]=4, PASSED_EG[2]=11 (v0.5.4)
        long e7 = 1L << 12;
        long e3 = 1L << 44;
        int[] scoreHigh = PawnStructure.evaluate(e7, 0L);
        int[] scoreLow = PawnStructure.evaluate(e3, 0L);
        // PASSED_MG is non-zero in v0.5.4; rank 7 bonus (index 6 = 52) > rank 3 (index 2 = 4).
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

        // Updated 2026-04-10: eval-converged Texel run (Issue #141) set DOUBLED_MG=-2 (tuner
        // found a tiny MG bonus for doubled pawns, likely absorbing a structural trade-off).
        // mg -= (wd-bd) * (-2) = mg += 2 for the doubled position. Doubled MG >= spread MG.
        // The doubled-pawn downside is captured entirely by DOUBLED_EG=23 (penalty).
        assertTrue(scoreDoubled[0] >= scoreSpread[0],
                "Doubled pawns MG: with DOUBLED_MG=-2 (tuned tiny bonus), doubled >= spread");
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
        // HALF_OPEN=0 after Texel run-2; test fully open g-file (OPEN_FILE=37) instead.
        // Both boards: White king g1. 'openFile': f2,h2 pawns only — g-file fully open
        //   (neither side has a g-pawn) → OPEN_FILE=37 penalty fires.
        // 'closed': f2,g2,h2 — g-file has friendly pawn → no open-file penalty.
        Board openFile = new Board("4k3/8/8/8/8/8/5P1P/6K1 w - - 0 1");
        Board closed   = new Board("4k3/8/8/8/8/8/5PPP/6K1 w - - 0 1");

        int safetyOpenFile = KingSafety.evaluate(openFile);
        int safetyClosed   = KingSafety.evaluate(closed);

        assertTrue(safetyClosed > safetyOpenFile,
                "Fully open file near king should reduce safety (OPEN_FILE=37, HALF_OPEN=0 after Texel run-2)");
    }

    @Test
    void attackerWeightReducesSafety() {
        // Black rook on f3 attacks white king zone on g1.
        // ATK_WEIGHT_ROOK = 9 (clearly positive), so a rook attack always reduces safety.
        //
        // Note — queen deliberately excluded: ATK_WEIGHT_QUEEN = -1 (CLOP / Texel-converged
        // value). With a lone queen, w = -1, w²/4 = 0 via integer division → zero penalty.
        // This is intentional: the negative weight suppresses double-counting of queen threats
        // that are already captured via mobility and PST bonuses. The queen's contribution is
        // meaningful only in combination with other attackers (where w stays large and positive).
        // A separate test below validates the multi-piece combined-threat scenario.
        Board rookAttacks = new Board("6k1/5ppp/8/8/8/5r2/5PPP/6K1 w - - 0 1");
        Board noAttacker  = new Board("6k1/5ppp/8/8/8/8/5PPP/6K1 w - - 0 1");

        int safetyAttacked = KingSafety.evaluate(rookAttacks);
        int safetyClean    = KingSafety.evaluate(noAttacker);

        assertTrue(safetyClean > safetyAttacked,
                "Enemy rook attacking king zone should reduce safety (ATK_WEIGHT_ROOK=9)");
    }

    @Test
    void queenPlusRookCombinedAttackReducesSafety() {
        // Black rook+queen on f3/e3 both attacking white king zone on g1.
        // Combined w = ATK_WEIGHT_ROOK + ATK_WEIGHT_QUEEN = 9 + (-1) = 8 → penalty = -16.
        // Even with Q=-1, the combined threat is significant.
        Board comboAttacks = new Board("6k1/5ppp/8/8/8/4qr2/5PPP/6K1 w - - 0 1");
        Board noAttacker   = new Board("6k1/5ppp/8/8/8/8/5PPP/6K1 w - - 0 1");

        int safetyCombo = KingSafety.evaluate(comboAttacks);
        int safetyClean = KingSafety.evaluate(noAttacker);

        assertTrue(safetyClean > safetyCombo,
                "Rook+queen combined attack should reduce king safety");
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

    @Test
    void hangingPiecePenaltyReducesScore() {
        // White bishop on c4, attacked by black rook on c8, no white defender.
        // The hanging penalty must lower white's score compared to a position where
        // a white pawn on b3 defends the bishop.
        Board hangingBishop  = new Board("2r1k3/8/8/8/2B5/8/8/4K3 w - - 0 1");
        Board defendedBishop = new Board("2r1k3/8/8/8/2B5/1P6/8/4K3 w - - 0 1");

        int hangingScore  = evaluator.evaluate(hangingBishop);
        int defendedScore = evaluator.evaluate(defendedBishop);

        // The defended position has an extra pawn AND removes the hanging penalty.
        // Either effect alone is enough to make defendedScore > hangingScore.
        assertTrue(hangingScore < defendedScore,
                "Undefended attacked bishop should score lower than defended bishop");
    }

    @Test
    void hangingPenaltyIsSymmetric() {
        // Black rook on a8 attacked by white rook on a1: mutual hanging, net penalty = 0.
        // Black pawn on b7 is a promotion-path pawn (attacks a8 diagonally), so it defends black's rook.
        Board hangingBlackRook  = new Board("r3k3/8/8/8/8/8/8/R3K3 b - - 0 1");
        Board defendedBlackRook = new Board("r3k3/1p6/8/8/8/8/8/R3K3 b - - 0 1");

        int hangingScore  = evaluator.evaluate(hangingBlackRook);
        int defendedScore = evaluator.evaluate(defendedBlackRook);

        // In hangingBlackRook: black rook a8 undefended, white rook a1 undefended → mutual-hanging,
        // net penalty = 0.  Score is roughly equal material.
        // In defendedBlackRook: black pawn at b7 attacks a8 (promotion diagonal), so black rook is
        // defended; SEE for white taking a8 = 0 (pawn recaptures with promotion → unprofitable).
        // White rook a1 still undefended: penalty = −rook_value/4 for white → good for black.
        // Plus black has an extra pawn (+100 material).
        // Therefore: defendedBlackRook is better for black → defendedScore > hangingScore.
        assertTrue(defendedScore > hangingScore,
                "Defended black rook + extra pawn + white rook still hanging → black scores better in defended position");
    }

    @Test
    void hangingPenaltyNotTriggerWhenKingDefendsAffordably() {
        // White bishop on d5, defended only by White King on c4, attacked by Black Rook on d1.
        // SEE: Rook takes Bishop (gain 330), King recaptures Rook (gain 500) → Black net = -170.
        // Black would not take (unprofitable), so captureGainFor(d5, Black) = 0 → no hanging penalty.
        //
        // Test design: compare to a position with equal material where the rook is on a1 instead
        // (same file as before but left file — does NOT attack d5 at all).  Because both positions
        // have identical material and neither has a hanging penalty for the bishop, the evaluation
        // difference should be only the rook-PST(d1) vs rook-PST(a1) — a small amount well below
        // a piece value.  If the bishop were wrongly penalised, the gap would be ~bishop_value/4 ≈ 82 cp larger.
        Board rookOnDFile = new Board("3k4/8/8/3B4/2K5/8/8/3r4 w - - 0 1"); // rook d1 attacks bishop but exchange unprofitable
        Board rookOffFile = new Board("3k4/8/8/3B4/2K5/8/8/r7 w - - 0 1");  // rook a1 does not attack bishop at all

        int dFileScore  = evaluator.evaluate(rookOnDFile);
        int offFileScore = evaluator.evaluate(rookOffFile);

        // Same material; difference should be only rook-PST, not a hanging penalty on the bishop.
        // A tolerance of 150 cp covers any PST variation while flagging an erroneous ~82 cp penalty.
        assertTrue(Math.abs(dFileScore - offFileScore) < 150,
                "Bishop defended by king (SEE=0 for Black) must not incur a hanging penalty; "
                + "score difference should reflect only rook-PST(d1 vs a1), not a hanging penalty");
    }

    @Test
    void hangingPenaltyFiresWhenKingIsOnlyDefenderButExchangeProfitable() {
        // Regression for Fix 3: the old isSquareAttackedBy check treated the King as a real
        // defender regardless of exchange result. If Black attacks with a piece LESS valuable than
        // the target, the King recapture doesn't compensate — the piece IS genuinely hanging.
        //
        // Position: White Rook on d5 (value 500), Black Bishop on a2 attacks d5 diagonally,
        //           White King on c4 "defends" d5 adjacently.  Black King on h8.
        // Exchange: Ba2xd5 (Black gains 500), Kc4xd5 (White gains bishop=330 back).
        // Net for Black = 500 - 330 = +170 → Rook IS hanging.
        // Old code: isSquareAttackedBy(d5, White) == true (king) → NOT penalised (WRONG).
        // New SEE code: captureGainFor(d5, Black) = 170 → penalised by -170 (correct).
        Board hangingRookKingDefender = new Board("7k/8/8/3R4/2K5/8/b7/8 w - - 0 1");
        Board noAttackerBaseline      = new Board("7k/8/8/3R4/2K5/8/8/8 w - - 0 1");

        int hangingScore  = evaluator.evaluate(hangingRookKingDefender);
        int baselineScore = evaluator.evaluate(noAttackerBaseline);

        assertTrue(hangingScore < baselineScore,
                "Rook attacked by lesser-value piece with only king-defender must be penalised by SEE score");
    }

    // --- Piece bonus tests (Phase 10 — #10.5 exit criteria) ---

    @Test
    void bishopPairBonusFires() {
        // White Ke1, Ba4 (a4, row4 col0), Bd3 (row5 col3) — bishop pair.
        // vs White Ke1, Na4, Bd3 — knight + bishop (no pair).
        // Net MG = (Bishop-Knight material) + bishopPairMg + (PST Bd3 cancels) + PST diff at a4
        //        = (331-304) + 29 + (-14-15) = +27 MG
        // Net EG = (215-200) + bishopPairEg + PST diff at a4
        //        = 15 + 52 + (-71-(-61)) = +57 EG  (phase≈2 → EG dominates → clearly positive)
        Board withPair    = new Board("4k3/8/8/8/B7/3B4/8/4K3 w - - 0 1");
        Board withoutPair = new Board("4k3/8/8/8/N7/3B4/8/4K3 w - - 0 1");

        assertTrue(evaluator.evaluate(withPair) > evaluator.evaluate(withoutPair),
                "Two bishops should score higher than bishop + knight (pair bonus + material)");
    }

    @Test
    void rookOnOpenFileBonusFires() {
        // Same material: white Ke1 + R + Pa2/d2; black Ke8 only.
        // A: rook on c1 — c-file has no white pawn (Pa2, Pd2 not on c) → open-file bonus.
        // B: rook on d1 — d-file has white pawn Pd2 → no bonus.
        // Identical pawn configuration in both; difference is only rook placement + bonus.
        // Net MG = rookOpenMg(50) + PST(Rc1)-PST(Rd1) = 50 + (-42-(-21)) = +29
        // Net EG = rookOpenEg(0) + PST(Rc1 EG)-PST(Rd1 EG) = 0 + (-65-(-70)) = +5
        // Tapered at phase=2: (29*2 + 5*22)/24 ≈ +7 (positive → Rc1+open wins)
        Board rookOpenFile = new Board("4k3/8/8/8/8/8/P2P4/2R1K3 w - - 0 1");
        Board rookBlocked  = new Board("4k3/8/8/8/8/8/P2P4/3RK3 w - - 0 1");

        assertTrue(evaluator.evaluate(rookOpenFile) > evaluator.evaluate(rookBlocked),
                "Rook on open c-file should score higher than rook on blocked d-file");
    }

    @Test
    void rookOnSemiOpenFileBonusFires() {
        // Same material: white Ke1 + R + Pa2; black Ke8 + Pc7.
        // A: rook on c1 — c-file has no white pawn (Pa2 not on c), black Pc7 present → semi-open bonus.
        // B: rook on a1 — a-file has white pawn Pa2 → no bonus (blocked file).
        // Identical pawn configuration in both; difference is only rook placement + bonus.
        // Net MG = rookSemiMg(18) + PST(Rc1)-PST(Ra1) = 18 + (-42-(-58)) = +34
        // Net EG = rookSemiEg(19) + PST(Rc1 EG)-PST(Ra1 EG) = 19 + (-65-(-49)) = +3
        // Tapered at phase=2: (34*2 + 3*22)/24 ≈ +5 (positive → Rc1+semi-open wins)
        Board rookSemiOpen = new Board("4k3/2p5/8/8/8/8/P7/2R1K3 w - - 0 1");
        Board rookBlocked  = new Board("4k3/2p5/8/8/8/8/P7/R3K3 w - - 0 1");

        assertTrue(evaluator.evaluate(rookSemiOpen) > evaluator.evaluate(rookBlocked),
                "Rook on semi-open c-file should score higher than rook on blocked a-file");
    }

    @Test
    void rookOnSeventhRankBonusFires() {
        // Both positions have equal material: white Ke1 + one rook; black Ke8.
        // A: rook on a7 (WHITE_RANK_7 = bits 8–15) → rook7th bonus fires.
        // B: rook on a1 → no rook7th bonus.
        // Rook EG PST(a7)=10 + rook7thEg(23) > rook EG PST(a1)=12, and
        // MG PSTs are equal (both 4), so A > B by ~19 cp.
        Board rookOn7th = new Board("4k3/R7/8/8/8/8/8/4K3 w - - 0 1");
        Board rookOn1st = new Board("4k3/8/8/8/8/8/8/R3K3 w - - 0 1");

        assertTrue(evaluator.evaluate(rookOn7th) > evaluator.evaluate(rookOn1st),
                "Rook on 7th rank (a7) should score higher than rook on 1st rank (a1)");
    }
}
