package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.bitboard.AttackTables;
import coeusyk.game.chess.core.bitboard.MagicBitboards;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;

public class StaticExchangeEvaluator {
    private static final int[] PIECE_VALUES = {
            0,
            100,
            320,
            330,
            500,
            900,
            20_000
    };

    // Square convention: a8=0, h8=7, a1=56, h1=63.
    // FILE_A = column-0 bits (a-file), FILE_H = column-7 bits (h-file).
    private static final long FILE_A = 0x0101010101010101L;
    private static final long FILE_H = 0x8080808080808080L;

    // ==================== Public API (signatures unchanged) ====================

    public int evaluate(Board board, Move move) {
        if (!isCapture(board, move)) {
            return 0;
        }
        int capturedValue   = capturedPieceValue(board, move);
        int promotionDelta  = promotionDelta(move.reaction);
        board.makeMove(move);
        int lossByRecaptures = bestReplyGain(board, move.targetSquare, board.getActiveColor());
        board.unmakeMove();
        return capturedValue + promotionDelta - lossByRecaptures;
    }

    /** SEE for a packed-int move. Returns 0 for non-captures. */
    public int evaluate(Board board, int packedMove) {
        if (!isCapture(board, packedMove)) {
            return 0;
        }
        int capturedValue  = capturedPieceValue(board, packedMove);
        int promoDelta     = promotionDelta(Move.flag(packedMove));
        board.makeMove(packedMove);
        int lossByRecaptures = bestReplyGain(board, Move.to(packedMove), board.getActiveColor());
        board.unmakeMove();
        return capturedValue + promoDelta - lossByRecaptures;
    }

    public int evaluateSquareOccupation(Board board, int targetSquare) {
        int occupant = board.getPiece(targetSquare);
        if (occupant == Piece.None) {
            return 0;
        }
        int occupantValue    = PIECE_VALUES[Piece.type(occupant)];
        int lossByRecaptures = bestReplyGain(board, targetSquare, board.getActiveColor());
        return occupantValue - lossByRecaptures;
    }

    /**
     * Returns the expected material gain for {@code attackerColor} from starting a
     * capture sequence on {@code targetSquare}, assuming both sides play optimally.
     * Positive = attacker gains material (piece is hanging); 0 = not profitable for attacker.
     *
     * <p>Used by {@code Evaluator.hangingPenalty()} to detect genuinely hanging pieces:
     * a piece defended only by the king is correctly scored as not hanging when the king
     * recapture is the optimal response (SEE returns 0 for that exchange).
     */
    public int captureGainFor(Board board, int targetSquare, int attackerColor) {
        return bestReplyGain(board, targetSquare, attackerColor);
    }

    // ==================== Core SEE: zero-allocation bitboard LVA ====================

    /**
     * Returns the expected material gain for {@code sideToMove} from capturing
     * whatever is currently on {@code targetSq}. Zero heap allocations: uses
     * bitboard attack maps to detect the least-valuable attacker (LVA) at each
     * recursion level instead of generating a full move list.
     *
     * <p>X-ray re-attackers (e.g. a rook hidden behind the first capturer) are
     * revealed automatically because {@code makeMove} updates the occupancy
     * bitboards before the next recursive {@code bitboardLva} query.
     */
    private int bestReplyGain(Board board, int targetSq, int sideToMove) {
        int from = bitboardLva(board, targetSq, sideToMove);
        if (from < 0) {
            return 0;
        }

        int flag = recaptureFlag(board, from, targetSq);

        int capturedVal;
        if (flag == Move.FLAG_EN_PASSANT) {
            capturedVal = PIECE_VALUES[Piece.Pawn];
        } else {
            int occ = board.getPiece(targetSq);
            capturedVal = (occ != Piece.None) ? PIECE_VALUES[Piece.type(occ)] : 0;
        }
        int promoBonus = (flag == Move.FLAG_PROMO_Q)
                ? PIECE_VALUES[Piece.Queen] - PIECE_VALUES[Piece.Pawn] : 0;

        int packed = Move.of(from, targetSq, flag);
        board.makeMove(packed);
        int continuation = bestReplyGain(board, targetSq, board.getActiveColor());
        board.unmakeMove();

        return Math.max(0, capturedVal + promoBonus - continuation);
    }

    /**
     * Returns the from-square of the least-valuable piece of {@code sideToMove}
     * that attacks {@code toSq}, or -1 if no such piece exists.
     *
     * <p>Piece priority (lowest value first):
     * pawn → knight → bishop → rook → queen → king.
     *
     * <p>Uses precomputed {@link AttackTables} for knights/kings and
     * {@link MagicBitboards} for sliding pieces — no move-list allocation.
     *
     * <p>Pawn attacks in a8=0 convention:
     * <ul>
     *   <li>White pawn at sq attacks sq−9 (not file A) and sq−7 (not file H).</li>
     *   <li>Black pawn at sq attacks sq+7 (not file A) and sq+9 (not file H).</li>
     * </ul>
     * To find which pawns attack {@code toSq} we reverse this:
     * white: toSq+9 (not file H) and toSq+7 (not file A);
     * black: toSq−7 (not file H) and toSq−9 (not file A).
     */
    private int bitboardLva(Board board, int toSq, int sideToMove) {
        long occ      = board.getAllOccupancy();
        long sqBit    = 1L << toSq;
        long rookRays = MagicBitboards.getRookAttacks(toSq, occ);
        long diagRays = MagicBitboards.getBishopAttacks(toSq, occ);

        long sub;
        if (sideToMove == Piece.White) {
            // White pawns: attack toSq from squares toSq+9 (not file H) and toSq+7 (not file A).
            sub = ((sqBit & ~FILE_H) << 9 | (sqBit & ~FILE_A) << 7) & board.getWhitePawns();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = AttackTables.KNIGHT_ATTACKS[toSq] & board.getWhiteKnights();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = diagRays & board.getWhiteBishops();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = rookRays & board.getWhiteRooks();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = (rookRays | diagRays) & board.getWhiteQueens();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = AttackTables.KING_ATTACKS[toSq] & board.getWhiteKing();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);
        } else {
            // Black pawns: attack toSq from squares toSq−7 (not file H) and toSq−9 (not file A).
            sub = ((sqBit & ~FILE_H) >> 7 | (sqBit & ~FILE_A) >> 9) & board.getBlackPawns();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = AttackTables.KNIGHT_ATTACKS[toSq] & board.getBlackKnights();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = diagRays & board.getBlackBishops();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = rookRays & board.getBlackRooks();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = (rookRays | diagRays) & board.getBlackQueens();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);

            sub = AttackTables.KING_ATTACKS[toSq] & board.getBlackKing();
            if (sub != 0) return Long.numberOfTrailingZeros(sub);
        }
        return -1;
    }

    /**
     * Returns the packed-int flag needed to make the recapture from {@code from}
     * to {@code toSq}. Handles en-passant and capturing promotions (always queen).
     */
    private int recaptureFlag(Board board, int from, int toSq) {
        int piece = board.getPiece(from);
        if (Piece.type(piece) == Piece.Pawn) {
            if (toSq == board.getEpTargetSquare()) {
                return Move.FLAG_EN_PASSANT;
            }
            int toRank = toSq / 8;
            if (toRank == 0 || toRank == 7) {
                // Capturing promotion — assume queen for SEE (maximum value, optimal choice).
                return Move.FLAG_PROMO_Q;
            }
        }
        return Move.FLAG_NORMAL;
    }

    // ==================== Helpers ====================

    private int capturedPieceValue(Board board, Move move) {
        if ("en-passant".equals(move.reaction)) {
            return PIECE_VALUES[Piece.Pawn];
        }
        return PIECE_VALUES[Piece.type(board.getPiece(move.targetSquare))];
    }

    private int capturedPieceValue(Board board, int move) {
        if (Move.isEnPassant(move)) {
            return PIECE_VALUES[Piece.Pawn];
        }
        return PIECE_VALUES[Piece.type(board.getPiece(Move.to(move)))];
    }

    private int promotionDelta(String reaction) {
        if (reaction == null) {
            return 0;
        }
        return switch (reaction) {
            case "promote-q" -> PIECE_VALUES[Piece.Queen]  - PIECE_VALUES[Piece.Pawn];
            case "promote-r" -> PIECE_VALUES[Piece.Rook]   - PIECE_VALUES[Piece.Pawn];
            case "promote-b" -> PIECE_VALUES[Piece.Bishop] - PIECE_VALUES[Piece.Pawn];
            case "promote-n" -> PIECE_VALUES[Piece.Knight] - PIECE_VALUES[Piece.Pawn];
            default -> 0;
        };
    }

    private int promotionDelta(int flag) {
        return switch (flag) {
            case Move.FLAG_PROMO_Q -> PIECE_VALUES[Piece.Queen]  - PIECE_VALUES[Piece.Pawn];
            case Move.FLAG_PROMO_R -> PIECE_VALUES[Piece.Rook]   - PIECE_VALUES[Piece.Pawn];
            case Move.FLAG_PROMO_B -> PIECE_VALUES[Piece.Bishop] - PIECE_VALUES[Piece.Pawn];
            case Move.FLAG_PROMO_N -> PIECE_VALUES[Piece.Knight] - PIECE_VALUES[Piece.Pawn];
            default -> 0;
        };
    }

    private boolean isCapture(Board board, Move move) {
        return "en-passant".equals(move.reaction) || board.getPiece(move.targetSquare) != Piece.None;
    }

    private boolean isCapture(Board board, int move) {
        return Move.isEnPassant(move) || board.getPiece(Move.to(move)) != Piece.None;
    }
}
