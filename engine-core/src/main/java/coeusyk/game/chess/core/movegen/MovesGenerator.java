package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.bitboard.MagicBitboards;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;

import java.util.*;


public class MovesGenerator {
    public static final int[] DirectionOffsets = { -8, 1, 8, -1, -9, -7, 9, 7 };
    public static int[][] SquaresToEdges = new int[64][8];  // Holds the number of squares to each edge from each square (for easier computation)

    static {
        ComputeMoveData();
    }
    
    // Knight move offsets and corresponding expected rank-distance for wrap-around validation.
    private static final int[] KNIGHT_OFFSETS      = { -10, -17, -15, -6, 10, 17, 15,  6 };
    private static final int[] KNIGHT_RANK_DISTANCE = {   1,   2,   2,  1,  1,  2,  2,  1 };
    
    private final Board board;

    private final ArrayList<Move> possibleMoves;

    public MovesGenerator(Board board) {
        this.board = board;
        this.possibleMoves = new ArrayList<>();
        generateMoves();
        makeMovesLegal(board.getActiveColor());
    }

    /**
     * Pool constructor: fills {@code dest} (pre-allocated, caller-owned) with legal
     * active-color moves instead of allocating a new list. The list is cleared before
     * use. Callers must NOT share the same list across concurrent threads.
     */
    public MovesGenerator(Board board, ArrayList<Move> dest) {
        this.board = board;
        this.possibleMoves = dest;
        dest.clear();
        generateMoves();
        makeMovesLegal(board.getActiveColor());
    }

    // Computes all possible moves from each square, and stores it in SquaresToEdges:
    private static void ComputeMoveData() {
        for (int rank = 0; rank < 8; rank++) {
            for (int file = 0; file < 8; file++) {
                int currentPos = (8 * rank) + file;

                int squaresToTop = rank;
                int squaresToBottom = 7 - rank;
                int squaresToLeft = file;
                int squaresToRight = 7 - file;

                SquaresToEdges[currentPos] = new int[]{
                        squaresToTop,
                        squaresToRight,
                        squaresToBottom,
                        squaresToLeft,
                        // Using min as the minimum from the two values gives the number of squares it will take to reach the edge:
                        Math.min(squaresToTop, squaresToLeft),  // Towards north-west
                        Math.min(squaresToTop, squaresToRight), // Towards north-east
                        Math.min(squaresToBottom, squaresToRight),  // Towards south-east
                        Math.min(squaresToBottom, squaresToLeft)  // Towards south-west
                };
            }
        }
    }

    // Generates all the possible moves of each piece:
    private void generateMoves() {
        possibleMoves.clear();  // Removing previous moves

        int activeColor = board.getActiveColor();
        for (int sq = 0; sq < 64; sq++) {
            int currentPiece = board.getPiece(sq);

            // Skip empty squares and pieces belonging to the inactive side.
            // This halves the loop body work and eliminates all opponent-move allocations.
            if (currentPiece == Piece.None || !Piece.isColor(currentPiece, activeColor)) continue;

            if (Piece.isSliding(currentPiece)) {
                generateSlidingMoves(sq, currentPiece);
            } else {
                switch (Piece.type(currentPiece)) {
                    case (Piece.Pawn) -> generatePawnMoves(sq, currentPiece);
                    case (Piece.Knight) -> generateKnightMoves(sq, currentPiece);
                    case (Piece.King) -> generateKingMoves(sq, currentPiece);
                }
            }
        }
    }

    // Returns if the king is in check, and if it is in a double check.
    // Uses O(1) magic-bitboard attack lookup — no move generation required.
    public boolean[] isKingInCheck(int kingSquare, int activeColor) {
        int inactiveColor = Piece.isWhite(activeColor) ? Piece.Black : Piece.White;
        boolean inCheck = board.isSquareAttackedBy(kingSquare, inactiveColor);
        return new boolean[] {inCheck, false};
    }

    public ArrayList<Move> getAllMoves() {
        return new ArrayList<>(possibleMoves);
    }

    public ArrayList<Move> getActiveMoves(int activeColor) {
        ArrayList<Move> colorSpecificMoves = new ArrayList<>();

        for (Move move : possibleMoves) {
            if (Piece.isColor(board.getPiece(move.startSquare), activeColor)) {
                colorSpecificMoves.add(move);
            }
        }

        return colorSpecificMoves;
    }

    private void makeMovesLegal(int activeColor) {
        int kingSq = board.getKingSquare(activeColor);
        boolean inCheck = board.isColorKingInCheck(activeColor);

        // When not in check, compute pinned pieces once using bitboard X-ray attacks.
        // A pinned piece is one that sits alone on the ray between the king and an enemy
        // sliding attacker. Moving a pinned piece (except along its pin ray) exposes the
        // king to check. For non-pinned, non-king, non-special moves the pseudo-legal
        // move is always legal — no make/unmake needed (fast path).
        long pinnedBB = (!inCheck) ? computePinnedPiecesBB(activeColor) : 0L;

        // possibleMoves already contains only active-color pseudo-legal moves (generateMoves
        // skips inactive-color pieces). Filter in-place using Iterator.remove() — eliminates
        // the two intermediate ArrayList copies that getAllMoves() + getActiveMoves() created.
        Iterator<Move> it = possibleMoves.iterator();
        while (it.hasNext()) {
            Move move = it.next();
            boolean isKingMove  = (move.startSquare == kingSq);
            boolean isSpecial   = "en-passant".equals(move.reaction)
                    || (move.reaction != null && move.reaction.startsWith("castle"));
            boolean isPinned    = (pinnedBB & (1L << move.startSquare)) != 0L;

            // Fast path: not-in-check, not a king/special move, piece not pinned.
            // Pseudo-legal move cannot expose the king → always legal, keep it.
            if (!inCheck && !isKingMove && !isSpecial && !isPinned) {
                continue;
            }

            // Slow path: verify via make/unmake.
            board.makeMove(move);
            if (board.isColorKingInCheck(activeColor)) {
                it.remove();
            }
            board.unmakeMove();
        }
    }

    /**
     * Returns a bitboard of all friendly pieces that are absolutely pinned to the king.
     * Uses magic-bitboard X-ray attacks to detect enemy sliding pieces (rooks, bishops,
     * queens) that have exactly one friendly blocker between them and the king.
     *
     * @param activeColor the side whose pieces to test for pin
     * @return bitboard where each set bit is a pinned friendly piece
     */
    private long computePinnedPiecesBB(int activeColor) {
        int kingSq = board.getKingSquare(activeColor);
        long friendlyBB, enemyRooks, enemyBishops, enemyQueens, enemyOccupied;

        if (Piece.isWhite(activeColor)) {
            friendlyBB   = board.getWhiteOccupancy();
            enemyRooks   = board.getBlackRooks();
            enemyBishops = board.getBlackBishops();
            enemyQueens  = board.getBlackQueens();
            enemyOccupied = board.getBlackOccupancy();
        } else {
            friendlyBB   = board.getBlackOccupancy();
            enemyRooks   = board.getWhiteRooks();
            enemyBishops = board.getWhiteBishops();
            enemyQueens  = board.getWhiteQueens();
            enemyOccupied = board.getWhiteOccupancy();
        }

        long pinnedBB = 0L;

        // Rook-type pins (same rank or file).
        // X-ray from king through friendly pieces: treat enemy-only board so sliders
        // won't stop at friendly blockers, reaching potential enemy pinners beyond them.
        long rookSliders = enemyRooks | enemyQueens;
        if (rookSliders != 0L) {
            long potentialPinners = MagicBitboards.getRookAttacks(kingSq, enemyOccupied) & rookSliders;
            while (potentialPinners != 0L) {
                int pinnerSq = Long.numberOfTrailingZeros(potentialPinners);
                // Squares strictly between king and pinner
                long between = getBetween(kingSq, pinnerSq);
                long friendlyInBetween = between & friendlyBB;
                if (Long.bitCount(friendlyInBetween) == 1) {
                    pinnedBB |= friendlyInBetween;
                }
                potentialPinners &= potentialPinners - 1L;
            }
        }

        // Bishop-type pins (same diagonal or anti-diagonal).
        long bishopSliders = enemyBishops | enemyQueens;
        if (bishopSliders != 0L) {
            long potentialPinners = MagicBitboards.getBishopAttacks(kingSq, enemyOccupied) & bishopSliders;
            while (potentialPinners != 0L) {
                int pinnerSq = Long.numberOfTrailingZeros(potentialPinners);
                long between = getBetween(kingSq, pinnerSq);
                long friendlyInBetween = between & friendlyBB;
                if (Long.bitCount(friendlyInBetween) == 1) {
                    pinnedBB |= friendlyInBetween;
                }
                potentialPinners &= potentialPinners - 1L;
            }
        }

        return pinnedBB;
    }

    /**
     * Returns a bitboard of squares strictly between sq1 and sq2 on the same ray
     * (rank, file, diagonal, or anti-diagonal). Returns 0 if they are not collinear.
     * Uses the intersection of the two truncated magic-bitboard attack rays.
     */
    private static long getBetween(int sq1, int sq2) {
        // Rook ray: both squares on same rank or file?
        long r1 = MagicBitboards.getRookAttacks(sq1, 1L << sq2);
        if ((r1 & (1L << sq2)) != 0L) {
            long r2 = MagicBitboards.getRookAttacks(sq2, 1L << sq1);
            return r1 & r2; // squares strictly between sq1 and sq2 on the rank/file
        }
        // Bishop ray: both squares on same diagonal or anti-diagonal?
        long b1 = MagicBitboards.getBishopAttacks(sq1, 1L << sq2);
        if ((b1 & (1L << sq2)) != 0L) {
            long b2 = MagicBitboards.getBishopAttacks(sq2, 1L << sq1);
            return b1 & b2; // squares strictly between sq1 and sq2 on the diagonal
        }
        return 0L; // not collinear
    }

    /**
     * Function to find the move, based on its startSquare and targetSquare
     * @param startSquare The starting square of the move
     * @param targetSquare The target square of the move
     * @return boolean
     */
    public Move findMove(int startSquare, int targetSquare) {
        for (Move move : possibleMoves) {
            if ((move.startSquare == startSquare) && (move.targetSquare == targetSquare)) {
                return move;
            }
        }

        return null;
    }

    /**
     * Function to get the possible moves of the piece at the specified square
     */
    public ArrayList<Move> getPieceMoves(int pieceSquare) {
        // Invalid argument handling:
        if (pieceSquare < 0 || pieceSquare > 63) {
            throw new IllegalArgumentException("expected a valid square index");
        } else if (board.getPiece(pieceSquare) == 0) {
            throw new IllegalArgumentException("expected a piece at square index");
        }

        ArrayList<Move> pieceMoves = new ArrayList<>();

        // Obtaining the moves:
        possibleMoves.forEach(move -> {
            if (move.startSquare == pieceSquare) {
                pieceMoves.add(move);
            }
        });

        return pieceMoves;
    }

    // For long range pieces - queen, rook, bishop (magic bitboard lookup):
    private void generateSlidingMoves(int startSquare, int currentPiece) {
        int pieceType = Piece.type(currentPiece);
        long occupied = board.getAllOccupancy();
        long friendlyOccupancy = Piece.isWhite(currentPiece)
                ? board.getWhiteOccupancy() : board.getBlackOccupancy();

        long attacks;
        if (pieceType == Piece.Bishop) {
            attacks = MagicBitboards.getBishopAttacks(startSquare, occupied);
        } else if (pieceType == Piece.Rook) {
            attacks = MagicBitboards.getRookAttacks(startSquare, occupied);
        } else {
            attacks = MagicBitboards.getQueenAttacks(startSquare, occupied);
        }

        attacks &= ~friendlyOccupancy;

        while (attacks != 0) {
            int targetSquare = Long.numberOfTrailingZeros(attacks);
            possibleMoves.add(new Move(startSquare, targetSquare));
            attacks &= attacks - 1;
        }
    }

    // For pawns:
    private void generatePawnMoves(int startSquare, int currentPawn) {
        int naturalOffset;
        int numOfSquaresToCheck = 1;
        boolean isWhitePawn = Piece.isWhite(currentPawn);

        int[] captureOffsets = new int[2];

        if (isWhitePawn) {
            naturalOffset = -8;
            if ((startSquare / 8) == 6) {
                numOfSquaresToCheck = 2;
            }

            captureOffsets[0] = -9; captureOffsets[1] = -7;
        } else {
            naturalOffset = 8;
            if ((startSquare / 8) == 1) {
                numOfSquaresToCheck = 2;
            }

            captureOffsets[0] = 7; captureOffsets[1] = 9;
        }

        // Natural moves:
        for (int i = 0; i < numOfSquaresToCheck; i++) {
            int targetSquare = startSquare + (naturalOffset * (i + 1));
            int targetPiece = board.getPiece(targetSquare);

            // Breaking if the path is blocked by any piece:
            if (Piece.type(targetPiece) != Piece.None) break;

            if (i == 0 && isPromotionRank(targetSquare, currentPawn)) {
                addPromotionMoves(startSquare, targetSquare);
            } else if (i == 0) {
                possibleMoves.add(new Move(startSquare, targetSquare));
            } else {
                possibleMoves.add(new Move(startSquare, targetSquare, "ep-target"));
            }
        }

        // Capturing moves:
        for (int offset : captureOffsets) {
            int targetSquare = startSquare + offset;
            if (targetSquare < 0 || targetSquare > 63) {
                continue;
            }

            if (Math.abs((startSquare % 8) - (targetSquare % 8)) != 1) {
                continue;
            }

            int targetPiece = board.getPiece(targetSquare);

            // En passant square check:
            if (targetSquare == board.getEpTargetSquare()) {
                int epPawnSquare = isWhitePawn
                        ? targetSquare + 8
                        : targetSquare - 8;

                // Obtaining the en-passant pawn (epPawn), and then adding the move only if the current pawn and the
                // epPawn are of opposite colors:
                int epPawn = board.getPiece(epPawnSquare);

                if (Piece.type(epPawn) == Piece.Pawn && !Piece.isColor(epPawn, currentPawn)) {
                    possibleMoves.add(new Move(startSquare, targetSquare, "en-passant"));
                }
            }

            // Opponent pawn on capture square check:
            else if ((Piece.color(targetPiece) != 0) && (!Piece.isColor(currentPawn, targetPiece))) {
                if (isPromotionRank(targetSquare, currentPawn)) {
                    addPromotionMoves(startSquare, targetSquare);
                } else {
                    possibleMoves.add(new Move(startSquare, targetSquare));
                }
            }
        }
    }

    private boolean isPromotionRank(int square, int pawn) {
        return (Piece.isWhite(pawn) && (square / 8) == 0)
                || (Piece.isBlack(pawn) && (square / 8) == 7);
    }

    private void addPromotionMoves(int startSquare, int targetSquare) {
        possibleMoves.add(new Move(startSquare, targetSquare, "promote-q"));
        possibleMoves.add(new Move(startSquare, targetSquare, "promote-r"));
        possibleMoves.add(new Move(startSquare, targetSquare, "promote-b"));
        possibleMoves.add(new Move(startSquare, targetSquare, "promote-n"));
    }

    // For knights:
    private void generateKnightMoves(int startSquare, int currentKnight) {
        // Adding the possible moves using precomputed static offset arrays:
        for (int i = 0; i < KNIGHT_OFFSETS.length; i++) {
            int targetSquare = startSquare + KNIGHT_OFFSETS[i];

            if (Math.abs((startSquare / 8) - (targetSquare / 8)) == KNIGHT_RANK_DISTANCE[i]) {
                if (targetSquare >= 0 && targetSquare <= 63) {
                    int targetPiece = board.getPiece(targetSquare);

                    if (!Piece.isColor(currentKnight, targetPiece)) {
                        possibleMoves.add(new Move(startSquare, targetSquare));
                    }
                }
            }
        }
    }

    // For kings:
    private void generateKingMoves(int startSquare, int currentKing) {
        // So that the king moves don't go through the board from one side to the other (depending on how far a king is from the edge):
        int[] kingSquaresToEdges = SquaresToEdges[startSquare];
        int opponentColor = Piece.isWhite(currentKing) ? Piece.Black : Piece.White;

        // Natural moves:
        for (int direction = 0; direction < DirectionOffsets.length; direction++) {
            if (kingSquaresToEdges[direction] > 0) {
                int targetSquare = startSquare + DirectionOffsets[direction];
                if (targetSquare < 0 || targetSquare > 63) continue;

                int targetPiece = board.getPiece(targetSquare);

                if (!Piece.isColor(currentKing, targetPiece)) {
                    possibleMoves.add(new Move(startSquare, targetSquare));
                }
            }
        }

        boolean[] castlingAvailability = board.getCastlingAvailability();

        // Castling availability specific to the selected king:
        boolean[] kingSpecificCA = (Piece.isWhite(currentKing)) ?
                Arrays.copyOfRange(castlingAvailability, 0, 2) :
                Arrays.copyOfRange(castlingAvailability, 2, castlingAvailability.length);

        boolean kingOnHomeSquare = (Piece.isWhite(currentKing) && startSquare == 60)
                || (Piece.isBlack(currentKing) && startSquare == 4);

        // For king side castling:
        if (kingOnHomeSquare && kingSpecificCA[0]) {
            int rookSquare = startSquare + 3;
            int throughSquare = startSquare + 1;
            int targetSquare = startSquare + 2;

            int expectedRook = Piece.isWhite(currentKing)
                    ? (Piece.White | Piece.Rook)
                    : (Piece.Black | Piece.Rook);

            if (board.getPiece(rookSquare) == expectedRook
                    && board.getPiece(throughSquare) == Piece.None
                    && board.getPiece(targetSquare) == Piece.None
                    && !isSquareAttacked(startSquare, opponentColor)
                    && !isSquareAttacked(throughSquare, opponentColor)
                    && !isSquareAttacked(targetSquare, opponentColor)) {
                possibleMoves.add(new Move(startSquare, targetSquare, "castle-k"));
            }
        }

        // For queen side castling:
        if (kingOnHomeSquare && kingSpecificCA[1]) {
            int rookSquare = startSquare - 4;
            int throughSquare = startSquare - 1;
            int targetSquare = startSquare - 2;
            int rookSideSquare = startSquare - 3;

            int expectedRook = Piece.isWhite(currentKing)
                    ? (Piece.White | Piece.Rook)
                    : (Piece.Black | Piece.Rook);

            if (board.getPiece(rookSquare) == expectedRook
                    && board.getPiece(throughSquare) == Piece.None
                    && board.getPiece(targetSquare) == Piece.None
                    && board.getPiece(rookSideSquare) == Piece.None
                    && !isSquareAttacked(startSquare, opponentColor)
                    && !isSquareAttacked(throughSquare, opponentColor)
                    && !isSquareAttacked(targetSquare, opponentColor)) {
                possibleMoves.add(new Move(startSquare, targetSquare, "castle-q"));
            }
        }
    }

    private boolean isSquareAttacked(int square, int attackerColor) {
        // Pawn attacks
        int[] pawnAttackSourceOffsets = Piece.isWhite(attackerColor)
                ? new int[] { 7, 9 }
                : new int[] { -7, -9 };

        for (int offset : pawnAttackSourceOffsets) {
            int sourceSquare = square + offset;
            if (sourceSquare < 0 || sourceSquare > 63) continue;

            if (Math.abs((sourceSquare % 8) - (square % 8)) != 1) continue;

            int piece = board.getPiece(sourceSquare);
            if (Piece.isColor(piece, attackerColor) && Piece.type(piece) == Piece.Pawn) {
                return true;
            }
        }

        // Knight attacks – use static arrays to avoid per-call allocation
        for (int i = 0; i < KNIGHT_OFFSETS.length; i++) {
            int sourceSquare = square + KNIGHT_OFFSETS[i];
            if (sourceSquare < 0 || sourceSquare > 63) continue;

            if (Math.abs((sourceSquare / 8) - (square / 8)) != KNIGHT_RANK_DISTANCE[i]) continue;

            int piece = board.getPiece(sourceSquare);
            if (Piece.isColor(piece, attackerColor) && Piece.type(piece) == Piece.Knight) {
                return true;
            }
        }

        // Sliding attacks (magic bitboard lookup)
        long occupied = board.getAllOccupancy();
        long rookQueenBB, bishopQueenBB;
        if (Piece.isWhite(attackerColor)) {
            rookQueenBB = board.getWhiteRooks() | board.getWhiteQueens();
            bishopQueenBB = board.getWhiteBishops() | board.getWhiteQueens();
        } else {
            rookQueenBB = board.getBlackRooks() | board.getBlackQueens();
            bishopQueenBB = board.getBlackBishops() | board.getBlackQueens();
        }
        if ((MagicBitboards.getRookAttacks(square, occupied) & rookQueenBB) != 0) return true;
        if ((MagicBitboards.getBishopAttacks(square, occupied) & bishopQueenBB) != 0) return true;

        // King attacks
        for (int direction = 0; direction < DirectionOffsets.length; direction++) {
            if (SquaresToEdges[square][direction] <= 0) continue;

            int sourceSquare = square + DirectionOffsets[direction];
            int piece = board.getPiece(sourceSquare);

            if (Piece.isColor(piece, attackerColor) && Piece.type(piece) == Piece.King) {
                return true;
            }
        }

        return false;
    }

    // ==================== Static packed-int move generation ====================
    // These methods generate directly into a caller-supplied int[] buffer without
    // allocating any Move objects. Used by the search hot path (Searcher.alphaBeta).

    /**
     * Generates all legal moves for the active side into {@code dest} and returns the count.
     * No Move objects are created; each element is a packed int (see {@link Move#of}).
     * The caller must supply a buffer of at least 256 ints (MAX_LEGAL_MOVES).
     */
    public static int generate(Board board, int[] dest) {
        int count = 0;
        int activeColor = board.getActiveColor();

        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getPiece(sq);
            if (piece == Piece.None || !Piece.isColor(piece, activeColor)) continue;

            if (Piece.isSliding(piece)) {
                count = genSliding(board, dest, count, sq, piece);
            } else {
                count = switch (Piece.type(piece)) {
                    case Piece.Pawn   -> genPawn(board, dest, count, sq, piece);
                    case Piece.Knight -> genKnight(board, dest, count, sq, piece);
                    case Piece.King   -> genKing(board, dest, count, sq, piece);
                    default           -> count;
                };
            }
        }

        return filterLegal(board, dest, count, activeColor);
    }

    /**
     * Generates only tactical moves (captures and quiet queen promotions) for the active side.
     * Used by the quiescence search (not-in-check path) to avoid generating quiet moves that
     * are immediately discarded. Returns the count of legal tactical moves.
     * The caller must supply a buffer of at least 64 ints.
     */
    public static int generateCaptures(Board board, int[] dest) {
        int count = 0;
        int activeColor = board.getActiveColor();
        long enemyBB = (activeColor == Piece.White) ? board.getBlackOccupancy() : board.getWhiteOccupancy();
        long occupied = board.getAllOccupancy();

        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getPiece(sq);
            if (piece == Piece.None || !Piece.isColor(piece, activeColor)) continue;

            if (Piece.isSliding(piece)) {
                int pt = Piece.type(piece);
                long attacks;
                if (pt == Piece.Bishop) {
                    attacks = MagicBitboards.getBishopAttacks(sq, occupied);
                } else if (pt == Piece.Rook) {
                    attacks = MagicBitboards.getRookAttacks(sq, occupied);
                } else {
                    attacks = MagicBitboards.getQueenAttacks(sq, occupied);
                }
                attacks &= enemyBB; // captures only — no quiet moves
                while (attacks != 0) {
                    int target = Long.numberOfTrailingZeros(attacks);
                    dest[count++] = Move.of(sq, target);
                    attacks &= attacks - 1;
                }
            } else {
                count = switch (Piece.type(piece)) {
                    case Piece.Pawn   -> genPawnTactical(board, dest, count, sq, piece, enemyBB);
                    case Piece.Knight -> genKnightCaptures(board, dest, count, sq, piece, enemyBB);
                    case Piece.King   -> genKingCaptures(board, dest, count, sq, piece, enemyBB);
                    default           -> count;
                };
            }
        }

        return filterLegal(board, dest, count, activeColor);
    }

    /** Pawn tactical moves: diagonal captures (incl. en passant), capture-promotions (queen only),
     *  and quiet pushes to the promotion rank (queen only). */
    private static int genPawnTactical(Board board, int[] dest, int count,
                                       int startSquare, int pawn, long enemyBB) {
        boolean isWhite = Piece.isWhite(pawn);
        int[] captureOffsets = isWhite ? new int[]{ -9, -7 } : new int[]{ 7, 9 };

        for (int offset : captureOffsets) {
            int target = startSquare + offset;
            if (target < 0 || target > 63) continue;
            if (Math.abs((startSquare % 8) - (target % 8)) != 1) continue;

            if (target == board.getEpTargetSquare()) {
                int epPawnSq = isWhite ? target + 8 : target - 8;
                int epPawn = board.getPiece(epPawnSq);
                if (Piece.type(epPawn) == Piece.Pawn && !Piece.isColor(epPawn, pawn)) {
                    dest[count++] = Move.of(startSquare, target, Move.FLAG_EN_PASSANT);
                }
            } else if ((enemyBB & (1L << target)) != 0) {
                if (isPromotionRankStatic(target, pawn)) {
                    dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_Q); // queen only
                } else {
                    dest[count++] = Move.of(startSquare, target);
                }
            }
        }

        // Quiet push to promotion rank → include as queen promo (not R/B/N; Q dominates in Q-search).
        int naturalOffset = isWhite ? -8 : 8;
        int pushTarget = startSquare + naturalOffset;
        if (pushTarget >= 0 && pushTarget < 64 && board.getPiece(pushTarget) == Piece.None
                && isPromotionRankStatic(pushTarget, pawn)) {
            dest[count++] = Move.of(startSquare, pushTarget, Move.FLAG_PROMO_Q);
        }

        return count;
    }

    /** Knight captures only — no quiet moves. */
    private static int genKnightCaptures(Board board, int[] dest, int count,
                                         int startSquare, int knight, long enemyBB) {
        for (int i = 0; i < KNIGHT_OFFSETS.length; i++) {
            int target = startSquare + KNIGHT_OFFSETS[i];
            if (Math.abs((startSquare / 8) - (target / 8)) != KNIGHT_RANK_DISTANCE[i]) continue;
            if (target < 0 || target > 63) continue;
            if ((enemyBB & (1L << target)) != 0) {
                dest[count++] = Move.of(startSquare, target);
            }
        }
        return count;
    }

    /** King captures only — no quiet moves, no castling. */
    private static int genKingCaptures(Board board, int[] dest, int count,
                                       int startSquare, int king, long enemyBB) {
        int[] edgeDists = SquaresToEdges[startSquare];
        for (int dir = 0; dir < DirectionOffsets.length; dir++) {
            if (edgeDists[dir] <= 0) continue;
            int target = startSquare + DirectionOffsets[dir];
            if (target < 0 || target > 63) continue;
            if ((enemyBB & (1L << target)) != 0) {
                dest[count++] = Move.of(startSquare, target);
            }
        }
        return count;
    }

    private static int genSliding(Board board, int[] dest, int count, int startSquare, int piece) {
        int pieceType = Piece.type(piece);
        long occupied = board.getAllOccupancy();
        long friendlyOccupancy = Piece.isWhite(piece)
                ? board.getWhiteOccupancy() : board.getBlackOccupancy();

        long attacks;
        if (pieceType == Piece.Bishop) {
            attacks = MagicBitboards.getBishopAttacks(startSquare, occupied);
        } else if (pieceType == Piece.Rook) {
            attacks = MagicBitboards.getRookAttacks(startSquare, occupied);
        } else {
            attacks = MagicBitboards.getQueenAttacks(startSquare, occupied);
        }

        attacks &= ~friendlyOccupancy;
        while (attacks != 0) {
            int target = Long.numberOfTrailingZeros(attacks);
            dest[count++] = Move.of(startSquare, target);
            attacks &= attacks - 1;
        }
        return count;
    }

    private static int genPawn(Board board, int[] dest, int count, int startSquare, int pawn) {
        boolean isWhite = Piece.isWhite(pawn);
        int naturalOffset = isWhite ? -8 : 8;
        int numSquares = isWhite ? ((startSquare / 8) == 6 ? 2 : 1)
                                 : ((startSquare / 8) == 1 ? 2 : 1);
        int[] captureOffsets = isWhite ? new int[]{ -9, -7 } : new int[]{ 7, 9 };

        // Natural (non-capturing) moves
        for (int i = 0; i < numSquares; i++) {
            int target = startSquare + naturalOffset * (i + 1);
            if (board.getPiece(target) != Piece.None) break;

            if (i == 0 && isPromotionRankStatic(target, pawn)) {
                // All four promotion moves
                dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_Q);
                dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_R);
                dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_B);
                dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_N);
            } else if (i == 0) {
                dest[count++] = Move.of(startSquare, target);
            } else {
                dest[count++] = Move.of(startSquare, target, Move.FLAG_EP_TARGET);
            }
        }

        // Capturing moves
        for (int offset : captureOffsets) {
            int target = startSquare + offset;
            if (target < 0 || target > 63) continue;
            if (Math.abs((startSquare % 8) - (target % 8)) != 1) continue;

            if (target == board.getEpTargetSquare()) {
                int epPawnSq = isWhite ? target + 8 : target - 8;
                int epPawn = board.getPiece(epPawnSq);
                if (Piece.type(epPawn) == Piece.Pawn && !Piece.isColor(epPawn, pawn)) {
                    dest[count++] = Move.of(startSquare, target, Move.FLAG_EN_PASSANT);
                }
            } else {
                int targetPiece = board.getPiece(target);
                if (targetPiece != Piece.None && !Piece.isColor(pawn, targetPiece)) {
                    if (isPromotionRankStatic(target, pawn)) {
                        dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_Q);
                        dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_R);
                        dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_B);
                        dest[count++] = Move.of(startSquare, target, Move.FLAG_PROMO_N);
                    } else {
                        dest[count++] = Move.of(startSquare, target);
                    }
                }
            }
        }
        return count;
    }

    private static boolean isPromotionRankStatic(int square, int pawn) {
        return (Piece.isWhite(pawn) && (square / 8) == 0)
                || (Piece.isBlack(pawn) && (square / 8) == 7);
    }

    private static int genKnight(Board board, int[] dest, int count, int startSquare, int knight) {
        for (int i = 0; i < KNIGHT_OFFSETS.length; i++) {
            int target = startSquare + KNIGHT_OFFSETS[i];
            if (Math.abs((startSquare / 8) - (target / 8)) != KNIGHT_RANK_DISTANCE[i]) continue;
            if (target < 0 || target > 63) continue;
            int tp = board.getPiece(target);
            if (!Piece.isColor(knight, tp)) {
                dest[count++] = Move.of(startSquare, target);
            }
        }
        return count;
    }

    private static int genKing(Board board, int[] dest, int count, int startSquare, int king) {
        int[] edgeDists = SquaresToEdges[startSquare];
        int opponentColor = Piece.isWhite(king) ? Piece.Black : Piece.White;

        // Natural king moves
        for (int dir = 0; dir < DirectionOffsets.length; dir++) {
            if (edgeDists[dir] <= 0) continue;
            int target = startSquare + DirectionOffsets[dir];
            if (target < 0 || target > 63) continue;
            int tp = board.getPiece(target);
            if (!Piece.isColor(king, tp)) {
                dest[count++] = Move.of(startSquare, target);
            }
        }

        // Castling
        boolean[] ca = board.getCastlingAvailability();
        boolean[] kingCA = Piece.isWhite(king)
                ? new boolean[]{ ca[0], ca[1] }
                : new boolean[]{ ca[2], ca[3] };
        boolean kingHome = (Piece.isWhite(king) && startSquare == 60)
                || (Piece.isBlack(king) && startSquare == 4);

        if (kingHome && kingCA[0]) {
            int rookSq = startSquare + 3;
            int throughSq = startSquare + 1;
            int targetSq = startSquare + 2;
            int expectedRook = Piece.isWhite(king) ? (Piece.White | Piece.Rook) : (Piece.Black | Piece.Rook);
            if (board.getPiece(rookSq) == expectedRook
                    && board.getPiece(throughSq) == Piece.None
                    && board.getPiece(targetSq) == Piece.None
                    && !board.isSquareAttackedBy(startSquare, opponentColor)
                    && !board.isSquareAttackedBy(throughSq, opponentColor)
                    && !board.isSquareAttackedBy(targetSq, opponentColor)) {
                dest[count++] = Move.of(startSquare, targetSq, Move.FLAG_CASTLING_K);
            }
        }

        if (kingHome && kingCA[1]) {
            int rookSq = startSquare - 4;
            int throughSq = startSquare - 1;
            int targetSq = startSquare - 2;
            int rookSideSq = startSquare - 3;
            int expectedRook = Piece.isWhite(king) ? (Piece.White | Piece.Rook) : (Piece.Black | Piece.Rook);
            if (board.getPiece(rookSq) == expectedRook
                    && board.getPiece(throughSq) == Piece.None
                    && board.getPiece(targetSq) == Piece.None
                    && board.getPiece(rookSideSq) == Piece.None
                    && !board.isSquareAttackedBy(startSquare, opponentColor)
                    && !board.isSquareAttackedBy(throughSq, opponentColor)
                    && !board.isSquareAttackedBy(targetSq, opponentColor)) {
                dest[count++] = Move.of(startSquare, targetSq, Move.FLAG_CASTLING_Q);
            }
        }

        return count;
    }

    /**
     * Filters the int[] move buffer in-place, retaining only legal moves.
     * Uses the same fast-path logic as {@link #makeMovesLegal}: only king moves,
     * special moves, and pinned-piece moves need full make/unmake verification.
     * Returns the new count of legal moves.
     */
    private static int filterLegal(Board board, int[] moves, int count, int activeColor) {
        int kingSq = board.getKingSquare(activeColor);
        boolean inCheck = board.isColorKingInCheck(activeColor);
        long pinnedBB = inCheck ? 0L : computePinnedPiecesBBStatic(board, activeColor);

        int legal = 0;
        for (int i = 0; i < count; i++) {
            int packed = moves[i];
            int from = Move.from(packed);
            int flag = Move.flag(packed);

            boolean isKingMove = (from == kingSq);
            boolean isSpecial = flag == Move.FLAG_EN_PASSANT
                    || flag == Move.FLAG_CASTLING_K
                    || flag == Move.FLAG_CASTLING_Q;
            boolean isPinned = (pinnedBB & (1L << from)) != 0L;

            if (!inCheck && !isKingMove && !isSpecial && !isPinned) {
                moves[legal++] = packed;   // fast path: always legal
                continue;
            }

            // Slow path: verify via make/unmake
            board.makeMove(packed);
            if (!board.isColorKingInCheck(activeColor)) {
                moves[legal++] = packed;
            }
            board.unmakeMove();
        }
        return legal;
    }

    /** Static version of computePinnedPiecesBB; does not require a MovesGenerator instance. */
    private static long computePinnedPiecesBBStatic(Board board, int activeColor) {
        int kingSq = board.getKingSquare(activeColor);
        long friendlyBB, enemyRooks, enemyBishops, enemyQueens, enemyOccupied;

        if (Piece.isWhite(activeColor)) {
            friendlyBB   = board.getWhiteOccupancy();
            enemyRooks   = board.getBlackRooks();
            enemyBishops = board.getBlackBishops();
            enemyQueens  = board.getBlackQueens();
            enemyOccupied = board.getBlackOccupancy();
        } else {
            friendlyBB   = board.getBlackOccupancy();
            enemyRooks   = board.getWhiteRooks();
            enemyBishops = board.getWhiteBishops();
            enemyQueens  = board.getWhiteQueens();
            enemyOccupied = board.getWhiteOccupancy();
        }

        long pinnedBB = 0L;

        long rookSliders = enemyRooks | enemyQueens;
        if (rookSliders != 0L) {
            long potentialPinners = MagicBitboards.getRookAttacks(kingSq, enemyOccupied) & rookSliders;
            while (potentialPinners != 0L) {
                int pinnerSq = Long.numberOfTrailingZeros(potentialPinners);
                long between = getBetween(kingSq, pinnerSq);
                long friendlyInBetween = between & friendlyBB;
                if (Long.bitCount(friendlyInBetween) == 1) pinnedBB |= friendlyInBetween;
                potentialPinners &= potentialPinners - 1L;
            }
        }

        long bishopSliders = enemyBishops | enemyQueens;
        if (bishopSliders != 0L) {
            long potentialPinners = MagicBitboards.getBishopAttacks(kingSq, enemyOccupied) & bishopSliders;
            while (potentialPinners != 0L) {
                int pinnerSq = Long.numberOfTrailingZeros(potentialPinners);
                long between = getBetween(kingSq, pinnerSq);
                long friendlyInBetween = between & friendlyBB;
                if (Long.bitCount(friendlyInBetween) == 1) pinnedBB |= friendlyInBetween;
                potentialPinners &= potentialPinners - 1L;
            }
        }

        return pinnedBB;
    }
}