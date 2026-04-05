package coeusyk.game.chess.core.models;

import coeusyk.game.chess.core.bitboard.AttackTables;
import coeusyk.game.chess.core.bitboard.BitboardPosition;
import coeusyk.game.chess.core.bitboard.MagicBitboards;
import coeusyk.game.chess.core.bitboard.ZobristHash;
import coeusyk.game.chess.core.eval.PieceSquareTables;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.util.*;


public class Board {
    public record NullMoveState(int previousActiveColor, int previousEpTargetSquare, int previousHalfmoveClock,
                                int previousFullMoves, long previousZobristHash) {
    }

    // Inner class to store move undo information for efficient unmake without FEN parsing
    private static class UnmakeInfo {
        int capturedPiece;              // Piece captured on target square (Piece.None if none)
        int capturedEPPiece;            // For en passant: piece captured on EP square
        int previousEpTargetSquare;     // En passant target square before the move
        final boolean[] previousCastlingRights = new boolean[4]; // pre-allocated, filled via arraycopy
        int previousHalfmoveClock;      // Halfmove clock before the move
        int previousFullMoves;          // Full move counter before the move
        int packedMove;                 // The move encoded as a packed int (Move.of)
        int previousMgScore;            // Incremental material+PST MG score before the move
        int previousEgScore;            // Incremental material+PST EG score before the move

        /** Pool constructor — fields are set via set() before first use. */
        UnmakeInfo() {}

        void set(int packed, int capturedPiece, int capturedEPPiece, int prevEP,
                 boolean[] prevCastling, int prevHalf, int prevFull, int prevMg, int prevEg) {
            this.packedMove = packed;
            this.capturedPiece = capturedPiece;
            this.capturedEPPiece = capturedEPPiece;
            this.previousEpTargetSquare = prevEP;
            System.arraycopy(prevCastling, 0, this.previousCastlingRights, 0, 4);
            this.previousHalfmoveClock = prevHalf;
            this.previousFullMoves = prevFull;
            this.previousMgScore = prevMg;
            this.previousEgScore = prevEg;
        }
    }

    /** Valid move reactions. Checked as a Set to avoid per-call ArrayList allocation. */
    private static final Set<String> VALID_REACTIONS = Set.of(
        "castle-k", "castle-q", "en-passant", "ep-target",
        "promote-q", "promote-r", "promote-b", "promote-n"
    );

    /**
     * Maximum depth of the unmake stack: max search ply (128) + max game length (512)
     * plus a safety margin. Pooled to avoid per-node GC pressure.
     */
    private static final int UNMAKE_POOL_SIZE = 768;

    /**
     * When {@code true}, {@link #makeMove} and {@link #unmakeMove} skip recording
     * {@link #movesPlayed} and {@link #boardStates} (expensive FEN generation).
     * Set this before handing the board to the search and restore afterward.
     */
    private boolean searchMode = false;

    public void setSearchMode(boolean enabled) {
        this.searchMode = enabled;
    }

    // Material values mirroring Evaluator.MG_MATERIAL / EG_MATERIAL — keep in sync.
    // Indexed by Piece type constants (Piece.Pawn=1 .. Piece.King=6).
    private static final int[] INC_MG_MATERIAL = { 0, 100, 391, 428,  558, 1200, 0 };
    private static final int[] INC_EG_MATERIAL = { 0,  89, 287, 311,  555, 1040, 0 };

    // Incrementally maintained material+PST score (white minus black, before tapering).
    // Updated in makeMove, restored in unmakeMove, initialised in recomputeIncrementalScores().
    private int incMgScore = 0;
    private int incEgScore = 0;

    // Bitboard representation (12 piece types: 6 per color)
    private long whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing;
    private long blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing;
    
    // Occupancy masks
    private long whiteOccupancy, blackOccupancy, allOccupancy;

    // Attacked-squares bitboards: all squares attacked by each side.
    // Computed lazily on first access after a make/unmake — never during the hot make/unmake call.
    // Eliminates repeated isSquareAttackedBy() calls in hangingPenalty() (~56/eval → bitboard ops).
    private long attackedByWhite, attackedByBlack;
    private boolean attackedSquaresValid = false;

    static final String STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
//    static final String STARTING_FEN = "8/8/5k2/8/p7/P1B5/4K3/8 b - - 0 1";

    private static final long FILE_A = 0x0101010101010101L;
    private static final long FILE_H = 0x8080808080808080L;

    // Board Attributes:
    private int activeColor = Piece.White;  // The color to move

    private final boolean[] castlingAvailability = { false, false, false, false };  // First two for white king, other for black king
//    private final int[] castledRookSquares = { 5, 3, 61, 59 };  // Even indices for king side, odd indices for queen side

    private int epTargetSquare = -1;  // The current target square for En Passant
    private int halfmoveClock;  // This takes care of enforcing the 50-move rule (resets after captures and pawn moves)
    private int fullMoves = 1;  // Starts at 1 and updates after black's move
    
    // Zobrist hash for the current position
    private long zobristHash;

    // Moves made until current position:
    public ArrayList<Move> movesPlayed = new ArrayList<>();
    public ArrayList<String> boardStates = new ArrayList<>();  // Storing the states of the board
    // Zobrist hash history — long[] stack to avoid autoboxing overhead of ArrayList<Long>.
    // Size matches UNMAKE_POOL_SIZE: one entry per make, cleared on initWithFEN.
    private final long[] zobristStack = new long[UNMAKE_POOL_SIZE];
    private int zobristSP = 0;

    // Pre-allocated pool of UnmakeInfo objects — reused per make/unmake to avoid GC pressure.
    // unmakeSP is the next-free index (stack pointer). Pool entries are filled via set().
    private final UnmakeInfo[] unmakePool = new UnmakeInfo[UNMAKE_POOL_SIZE];
    private int unmakeSP = 0;

    public Board() {
        initUnmakePool();
        initWithFEN(STARTING_FEN);

        if (!boardStates.contains(STARTING_FEN)) {
            boardStates.add(STARTING_FEN);
        }
    }

    public Board(String fenString) {
        initUnmakePool();
        initWithFEN(fenString);

        if (!boardStates.contains(fenString)) {
            boardStates.add(fenString);
        }
    }

    private void initUnmakePool() {
        for (int i = 0; i < UNMAKE_POOL_SIZE; i++) {
            unmakePool[i] = new UnmakeInfo();
        }
    }

    // Initializing the board with a FEN string:
    private void initWithFEN(String fenString) {
        // Clearing all bitboards
        whitePawns = whiteKnights = whiteBishops = whiteRooks = whiteQueens = whiteKing = 0L;
        blackPawns = blackKnights = blackBishops = blackRooks = blackQueens = blackKing = 0L;
        whiteOccupancy = blackOccupancy = allOccupancy = 0L;
        zobristSP = 0;

        String[] fenFields = fenString.split(" ");

        if (fenFields.length != 6) {
            throw new IllegalArgumentException("expected appropriate FEN String (\"<position> <color-to-move> <castling-availability> <en-passant-target-square> <halfmove-clock> <full-move>\")");
        }

        String piecePositions = String.join("", fenFields[0].split("/"));
        String[] boardInformation = Arrays.copyOfRange(fenFields, 1, fenFields.length);

        // Double brace initialisation:
        Map<Character, Integer> pieceChars = new HashMap<>() {{
           put('p', Piece.Pawn); put('n', Piece.Knight);
           put('b', Piece.Bishop); put('r', Piece.Rook);
           put('q', Piece.Queen); put('k', Piece.King);
        }};

        Map<Character, Integer> castlingChars = new HashMap<>() {{
            put('K', 0); put('Q', 1);
            put('k', 2); put('q', 3);
        }};

        // Initializing the position:
        int index = 0;

        for (char ch : piecePositions.toCharArray()) {
            int value = Piece.Black;

            if (Character.isDigit(ch)) {
                index += Character.getNumericValue(ch);
            } else {
                if (Character.isUpperCase(ch)) {
                    ch = Character.toLowerCase(ch);
                    value = Piece.White;
                }

                int pieceType = pieceChars.get(ch);
                long bit = 1L << index;
                
                // Set piece in appropriate bitboard
                if (value == Piece.White) {
                    switch (pieceType) {
                        case Piece.Pawn -> whitePawns |= bit;
                        case Piece.Knight -> whiteKnights |= bit;
                        case Piece.Bishop -> whiteBishops |= bit;
                        case Piece.Rook -> whiteRooks |= bit;
                        case Piece.Queen -> whiteQueens |= bit;
                        case Piece.King -> whiteKing |= bit;
                    }
                } else {
                    switch (pieceType) {
                        case Piece.Pawn -> blackPawns |= bit;
                        case Piece.Knight -> blackKnights |= bit;
                        case Piece.Bishop -> blackBishops |= bit;
                        case Piece.Rook -> blackRooks |= bit;
                        case Piece.Queen -> blackQueens |= bit;
                        case Piece.King -> blackKing |= bit;
                    }
                }
                
                index++;
            }
        }

        // Recompute occupancy masks
        recomputeOccupancies();

        // Initialising the active color:
        if (boardInformation[0].equals("b")){
            activeColor = Piece.Black;
        } else if (boardInformation[0].equals("w")) {
            activeColor = Piece.White;
        } else {
            throw new IllegalArgumentException(
                    String.format("expected appropriate active color ('w' or 'b'); got '%s'", boardInformation[0])
            );
        }

        // Initializing the castling availability:
        if (!boardInformation[1].equals("-")) {
            String validCAChars = "KQkq";

            for (int i = 0; i < boardInformation[1].length(); i++) {
                if (validCAChars.indexOf(boardInformation[1].charAt(i)) != -1) {
                    castlingAvailability[castlingChars.get(boardInformation[1].charAt(i))] = true;
                } else {
                    throw new IllegalArgumentException(
                            String.format("expected appropriate castling availability ('K', 'Q', 'k', and 'q'); " +
                                    "got '%s'", boardInformation[1].charAt(i))
                    );
                }
            }
        }

        // Initializing the En Passant Target Square:
        if (!boardInformation[2].equals("-")) {
            checkEPTargetSquare(boardInformation[2]);

            int file = boardInformation[2].charAt(0) - 'a';
            int rank = Character.getNumericValue(boardInformation[2].charAt(1));
            epTargetSquare = (8 - rank) * 8 + file;
        }

        // Initializing the Halfmove Clock:
        halfmoveClock = Integer.parseInt(boardInformation[3]);

        // Initializing the Full Moves:
        fullMoves = Integer.parseInt(boardInformation[4]);

        // Adding the current FEN to the board states (only if it doesn't exist beforehand):
        if (!boardStates.contains(fenString)) {
            boardStates.add(fenString);
        }
        
        // Compute Zobrist hash for the initial position
        zobristHash = recomputeZobristHash();
        zobristStack[zobristSP++] = zobristHash;

        // Initialize incremental material+PST scores from the FEN position
        recomputeIncrementalScores();
        // Attacked-squares are computed lazily on first access; no init call needed.
    }

    // Function for obtaining the FEN string of the current position (for storing states of the board):
    private String getCurrentFEN() {
        StringBuilder fenString = new StringBuilder();

        Map<Integer, Character> pieceChars = new HashMap<>() {{
           put(Piece.Pawn, 'p'); put(Piece.Knight, 'n');
           put(Piece.Bishop, 'b'); put(Piece.Rook, 'r');
           put(Piece.Queen, 'q'); put(Piece.King, 'k');
        }};

        Map<Integer, String> castlingChars = new HashMap<>() {{
            put(0, "K"); put(1, "Q");
            put(2, "k"); put(3, "q");
        }};

        // Obtaining the current position of the board in a FEN format:
        int currentRank = 1;
        int rankEmptySquares = 0;  // The number of consecutive squares which are empty in one rank

        for (int index = 0; index < 64; index++) {
            if ((index / 8) + 1 > currentRank) {
                currentRank++;

                if (rankEmptySquares > 0) {
                    fenString.append(rankEmptySquares);
                    rankEmptySquares = 0;
                }

                fenString.append("/");
            }

            int piece = getPiece(index);
            if (piece == Piece.None) {
                rankEmptySquares++;
            } else {
                if (rankEmptySquares > 0) {
                    fenString.append(rankEmptySquares);
                    rankEmptySquares = 0;
                }

                int pieceType = Piece.type(piece);
                char pieceChar = pieceChars.get(pieceType);

                if (Piece.isWhite(piece)) {
                    fenString.append(Character.toUpperCase(pieceChar));
                } else {
                    fenString.append(pieceChar);
                }
            }
        }

        fenString.append(" ");

        // Adding the active color field:
        fenString.append((Piece.isBlack(activeColor)) ? "b" : "w");
        fenString.append(" ");

        // Adding the castling availability field:
        boolean castlingExists = false;

        for (int index = 0; index < castlingAvailability.length; index++) {
            if (castlingAvailability[index]) {
                fenString.append(castlingChars.get(index));
                castlingExists = true;
            }
        }

        if (!castlingExists) fenString.append("-");

        fenString.append(" ");

        // Adding the En Passant Target Square:
        if (epTargetSquare == -1) {
            fenString.append("-");
        } else {
            fenString.append(getChessSquare(epTargetSquare));
        }

        fenString.append(" ");

        // Adding HalfMove Clock field:
        fenString.append(halfmoveClock);
        fenString.append(" ");

        // Adding Full Moves field:
        fenString.append(fullMoves);

        return new String(fenString);
    }

    private void checkEPTargetSquare(String epts) {
        if (epts.length() != 2) {
            throw new IllegalArgumentException("expected a valid square (length 2 : <file><rank>); got string of length " + epts.length());
        }

        char firstChar = epts.charAt(0);
        char secondChar = epts.charAt(1);

        if (!Character.isAlphabetic(firstChar)) {
            throw new IllegalArgumentException("expected a valid file (a to h)");
        } else if (!Character.isLowerCase(firstChar)) {
            throw new IllegalArgumentException("expected a valid file (lowercase)");
        }

        if (!Character.isDigit(secondChar)) {
            throw new IllegalArgumentException("expected a valid rank (digits)");
        }
    }

    // ==================== Bitboard Helper Methods ====================
    
    private void recomputeOccupancies() {
        whiteOccupancy = whitePawns | whiteKnights | whiteBishops | whiteRooks | whiteQueens | whiteKing;
        blackOccupancy = blackPawns | blackKnights | blackBishops | blackRooks | blackQueens | blackKing;
        allOccupancy = whiteOccupancy | blackOccupancy;
    }

    /**
     * Computes attacked-squares bitboards from scratch using current occupancy.
     * Called lazily by getAttackedByWhite()/getAttackedByBlack() — only when evaluate()
     * actually needs them, not on every make/unmake.
     */
    private void recomputeAttackedSquares() {
        long occ = allOccupancy;

        // White attacks
        long wa = 0L;
        long temp = whiteRooks | whiteQueens;
        while (temp != 0) { int sq = Long.numberOfTrailingZeros(temp); wa |= MagicBitboards.getRookAttacks(sq, occ);   temp &= temp - 1; }
        temp = whiteBishops | whiteQueens;
        while (temp != 0) { int sq = Long.numberOfTrailingZeros(temp); wa |= MagicBitboards.getBishopAttacks(sq, occ); temp &= temp - 1; }
        temp = whiteKnights;
        while (temp != 0) { int sq = Long.numberOfTrailingZeros(temp); wa |= AttackTables.KNIGHT_ATTACKS[sq];          temp &= temp - 1; }
        if (whiteKing != 0L) wa |= AttackTables.KING_ATTACKS[Long.numberOfTrailingZeros(whiteKing)];
        wa |= ((whitePawns & ~FILE_A) >> 9) | ((whitePawns & ~FILE_H) >> 7);
        attackedByWhite = wa;

        // Black attacks
        long ba = 0L;
        temp = blackRooks | blackQueens;
        while (temp != 0) { int sq = Long.numberOfTrailingZeros(temp); ba |= MagicBitboards.getRookAttacks(sq, occ);   temp &= temp - 1; }
        temp = blackBishops | blackQueens;
        while (temp != 0) { int sq = Long.numberOfTrailingZeros(temp); ba |= MagicBitboards.getBishopAttacks(sq, occ); temp &= temp - 1; }
        temp = blackKnights;
        while (temp != 0) { int sq = Long.numberOfTrailingZeros(temp); ba |= AttackTables.KNIGHT_ATTACKS[sq];          temp &= temp - 1; }
        if (blackKing != 0L) ba |= AttackTables.KING_ATTACKS[Long.numberOfTrailingZeros(blackKing)];
        ba |= ((blackPawns & ~FILE_A) << 7) | ((blackPawns & ~FILE_H) << 9);
        attackedByBlack = ba;
        attackedSquaresValid = true;
    }

    public long getAttackedByWhite() {
        if (!attackedSquaresValid) recomputeAttackedSquares();
        return attackedByWhite;
    }
    public long getAttackedByBlack() {
        if (!attackedSquaresValid) recomputeAttackedSquares();
        return attackedByBlack;
    }

    /**
     * Adjusts the incremental material+PST scores when a piece is added (delta=+1) or
     * removed (delta=-1) from the given square. White pieces contribute positively;
     * black pieces contribute negatively (scores are from White's perspective).
     */
    private void incAdjust(int piece, int square, int delta) {
        int type = Piece.type(piece);
        boolean white = Piece.isWhite(piece);
        int pstSq = white ? square : (square ^ 56);
        int mg = INC_MG_MATERIAL[type] + PieceSquareTables.mg(type, pstSq);
        int eg = INC_EG_MATERIAL[type] + PieceSquareTables.eg(type, pstSq);
        if (white) {
            incMgScore += delta * mg;
            incEgScore += delta * eg;
        } else {
            incMgScore -= delta * mg;
            incEgScore -= delta * eg;
        }
    }

    /** Recomputes incMgScore/incEgScore from scratch by iterating all 12 bitboards. */
    private void recomputeIncrementalScores() {
        incMgScore = 0;
        incEgScore = 0;
        accumBitboard(whitePawns,   Piece.White | Piece.Pawn);
        accumBitboard(whiteKnights, Piece.White | Piece.Knight);
        accumBitboard(whiteBishops, Piece.White | Piece.Bishop);
        accumBitboard(whiteRooks,   Piece.White | Piece.Rook);
        accumBitboard(whiteQueens,  Piece.White | Piece.Queen);
        accumBitboard(whiteKing,    Piece.White | Piece.King);
        accumBitboard(blackPawns,   Piece.Black | Piece.Pawn);
        accumBitboard(blackKnights, Piece.Black | Piece.Knight);
        accumBitboard(blackBishops, Piece.Black | Piece.Bishop);
        accumBitboard(blackRooks,   Piece.Black | Piece.Rook);
        accumBitboard(blackQueens,  Piece.Black | Piece.Queen);
        accumBitboard(blackKing,    Piece.Black | Piece.King);
    }

    private void accumBitboard(long bb, int piece) {
        while (bb != 0) {
            int sq = Long.numberOfTrailingZeros(bb);
            incAdjust(piece, sq, +1);
            bb &= bb - 1;
        }
    }
    
    private long recomputeZobristHash() {
        long hash = 0L;
        
        // XOR in all piece-square keys
        for (int square = 0; square < 64; square++) {
            int piece = getPieceFromBitboards(square);
            if (piece != Piece.None) {
                hash ^= ZobristHash.getKeyForPieceSquare(piece, square);
            }
        }
        
        // XOR in side-to-move
        if (Piece.isBlack(activeColor)) {
            hash ^= ZobristHash.getKeyForBlackToMove();
        }
        
        // XOR in castling rights
        hash ^= ZobristHash.getKeyForCastlingRights(castlingAvailabilityToInt());
        
        // XOR in en passant file only when an actual EP capture is legal for the side to move.
        // This ensures positions with the same pieces/rights but unreachable EP hash the same
        // (important for correct threefold-repetition detection).
        if (epTargetSquare >= 0 && isEnPassantCapturePossible(activeColor)) {
            hash ^= ZobristHash.getKeyForEnPassantFile(epTargetSquare % 8);
        }
        
        return hash;
    }
    
    /**
     * Returns true if the side given by {@code capturingColor} has a pawn that can capture
     * en passant at the current {@code epTargetSquare}.
     *
     * <p>This is used to make the Zobrist hash EP-aware only when a capture is actually legal,
     * which avoids false non-repetitions in threefold-repetition detection.</p>
     */
    private boolean isEnPassantCapturePossible(int capturingColor) {
        if (epTargetSquare < 0) return false;
        // The pawn that was double-pushed is one step away from the EP target square,
        // in the direction opposite to the capturing side's pawn advance.
        //   White captures (black pushed): moved pawn is at epTarget + 8 (one "down" in 0=a8 indexing)
        //   Black captures (white pushed): moved pawn is at epTarget - 8 (one "up")
        int movedPawnSquare = Piece.isWhite(capturingColor) ? epTargetSquare + 8 : epTargetSquare - 8;
        int file = movedPawnSquare % 8;
        if (file > 0) {
            int piece = getPiece(movedPawnSquare - 1);
            if (Piece.isColor(piece, capturingColor) && Piece.type(piece) == Piece.Pawn) return true;
        }
        if (file < 7) {
            int piece = getPiece(movedPawnSquare + 1);
            if (Piece.isColor(piece, capturingColor) && Piece.type(piece) == Piece.Pawn) return true;
        }
        return false;
    }
    
    private int castlingAvailabilityToInt() {
        int rights = 0;
        if (castlingAvailability[0]) rights |= 0x1; // White kingside
        if (castlingAvailability[1]) rights |= 0x2; // White queenside
        if (castlingAvailability[2]) rights |= 0x4; // Black kingside
        if (castlingAvailability[3]) rights |= 0x8; // Black queenside
        return rights;
    }
    
    private long getBitboard(int pieceType, int color) {
        if (color == Piece.White) {
            return switch (pieceType) {
                case Piece.Pawn -> whitePawns;
                case Piece.Knight -> whiteKnights;
                case Piece.Bishop -> whiteBishops;
                case Piece.Rook -> whiteRooks;
                case Piece.Queen -> whiteQueens;
                case Piece.King -> whiteKing;
                default -> 0L;
            };
        } else {
            return switch (pieceType) {
                case Piece.Pawn -> blackPawns;
                case Piece.Knight -> blackKnights;
                case Piece.Bishop -> blackBishops;
                case Piece.Rook -> blackRooks;
                case Piece.Queen -> blackQueens;
                case Piece.King -> blackKing;
                default -> 0L;
            };
        }
    }
    
    private void setBitboard(int pieceType, int color, long bitboard) {
        if (color == Piece.White) {
            switch (pieceType) {
                case Piece.Pawn -> whitePawns = bitboard;
                case Piece.Knight -> whiteKnights = bitboard;
                case Piece.Bishop -> whiteBishops = bitboard;
                case Piece.Rook -> whiteRooks = bitboard;
                case Piece.Queen -> whiteQueens = bitboard;
                case Piece.King -> whiteKing = bitboard;
            }
        } else {
            switch (pieceType) {
                case Piece.Pawn -> blackPawns = bitboard;
                case Piece.Knight -> blackKnights = bitboard;
                case Piece.Bishop -> blackBishops = bitboard;
                case Piece.Rook -> blackRooks = bitboard;
                case Piece.Queen -> blackQueens = bitboard;
                case Piece.King -> blackKing = bitboard;
            }
        }
    }
    
    private void setBit(int square, int piece) {
        long bit = 1L << square;
        int pieceType = Piece.type(piece);
        int color = Piece.color(piece);
        long bb = getBitboard(pieceType, color);
        setBitboard(pieceType, color, bb | bit);
    }
    
    private void clearBit(int square, int piece) {
        long bit = 1L << square;
        int pieceType = Piece.type(piece);
        int color = Piece.color(piece);
        long bb = getBitboard(pieceType, color);
        setBitboard(pieceType, color, bb & ~bit);
    }
    
    private int getPieceFromBitboards(int square) {
        long bit = 1L << square;
        
        // Check white pieces
        if ((whitePawns & bit) != 0) return Piece.White | Piece.Pawn;
        if ((whiteKnights & bit) != 0) return Piece.White | Piece.Knight;
        if ((whiteBishops & bit) != 0) return Piece.White | Piece.Bishop;
        if ((whiteRooks & bit) != 0) return Piece.White | Piece.Rook;
        if ((whiteQueens & bit) != 0) return Piece.White | Piece.Queen;
        if ((whiteKing & bit) != 0) return Piece.White | Piece.King;
        
        // Check black pieces
        if ((blackPawns & bit) != 0) return Piece.Black | Piece.Pawn;
        if ((blackKnights & bit) != 0) return Piece.Black | Piece.Knight;
        if ((blackBishops & bit) != 0) return Piece.Black | Piece.Bishop;
        if ((blackRooks & bit) != 0) return Piece.Black | Piece.Rook;
        if ((blackQueens & bit) != 0) return Piece.Black | Piece.Queen;
        if ((blackKing & bit) != 0) return Piece.Black | Piece.King;
        
        return Piece.None;
    }
    
    // ==================== End Bitboard Helper Methods ====================

    // Move to make after list of moves are sent to the client and the move made is reported back to the server:
    public void makeMove(Move move) {
        // Preserve the old validation contract before delegating to the int path.
        if (move.reaction != null && !VALID_REACTIONS.contains(move.reaction)) {
            throw new IllegalArgumentException("invalid move reaction : does not exist");
        }
        makeMove(move.pack());
    }

    /**
     * Primary make-move implementation using a packed int move encoding (no heap allocation).
     * The encoding is: bits 0-5 = from, bits 6-11 = to, bits 12-15 = flag (Move.FLAG_*).
     * Called by the search hot path and by the legacy makeMove(Move) delegate.
     */
    public void makeMove(int packed) {
        int startSquare = Move.from(packed);
        int targetSquare = Move.to(packed);
        int flag = Move.flag(packed);

        int movingPiece = getPiece(startSquare);
        int capturedPiece = getPiece(targetSquare);
        int capturedEPPiece = Piece.None;
        boolean isCapture = capturedPiece != Piece.None;
        int newEpTargetSquare = -1;
        int pieceOnTarget = movingPiece;

        // Save undo information before modifying the board (reuse pooled object — no allocation)
        UnmakeInfo unmakeInfo = unmakePool[unmakeSP++];
        unmakeInfo.set(packed, capturedPiece, capturedEPPiece,
                epTargetSquare, castlingAvailability, halfmoveClock, fullMoves,
                incMgScore, incEgScore);

        // Update Zobrist hash for side-to-move
        zobristHash ^= ZobristHash.getKeyForBlackToMove();

        // Save old castling rights for hash
        int oldCastlingRights = castlingAvailabilityToInt();

        switch (flag) {
            case Move.FLAG_CASTLING_K -> {
                int rookSquare = startSquare + 3;
                int rookPiece = getPiece(rookSquare);
                zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare);
                incAdjust(rookPiece, rookSquare, -1);       // rook leaves source
                castlingReaction(rookSquare, false);
                zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare - 2);
                incAdjust(rookPiece, rookSquare - 2, +1);   // rook arrives at destination
            }
            case Move.FLAG_CASTLING_Q -> {
                int rookSquare = startSquare - 4;
                int rookPiece = getPiece(rookSquare);
                zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare);
                incAdjust(rookPiece, rookSquare, -1);       // rook leaves source
                castlingReaction(rookSquare, false);
                zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare + 3);
                incAdjust(rookPiece, rookSquare + 3, +1);   // rook arrives at destination
            }
            case Move.FLAG_EN_PASSANT -> {
                int captureSquare = Piece.isWhite(movingPiece) ? targetSquare + 8 : targetSquare - 8;
                capturedEPPiece = getPiece(captureSquare);
                unmakeInfo.capturedEPPiece = capturedEPPiece;
                zobristHash ^= ZobristHash.getKeyForPieceSquare(capturedEPPiece, captureSquare);
                clearBit(captureSquare, capturedEPPiece);
                incAdjust(capturedEPPiece, captureSquare, -1); // captured EP pawn removed
                isCapture = true;
            }
            case Move.FLAG_EP_TARGET -> {
                newEpTargetSquare = Piece.isWhite(movingPiece) ? targetSquare + 8 : targetSquare - 8;
            }
            case Move.FLAG_PROMO_Q -> pieceOnTarget = Piece.color(movingPiece) | Piece.Queen;
            case Move.FLAG_PROMO_R -> pieceOnTarget = Piece.color(movingPiece) | Piece.Rook;
            case Move.FLAG_PROMO_B -> pieceOnTarget = Piece.color(movingPiece) | Piece.Bishop;
            case Move.FLAG_PROMO_N -> pieceOnTarget = Piece.color(movingPiece) | Piece.Knight;
        }

        // Update hash for en passant file.
        if (epTargetSquare >= 0 && isEnPassantCapturePossible(activeColor)) {
            zobristHash ^= ZobristHash.getKeyForEnPassantFile(epTargetSquare % 8);
        }
        epTargetSquare = newEpTargetSquare;
        int opponentColor = Piece.isWhite(activeColor) ? Piece.Black : Piece.White;
        if (newEpTargetSquare >= 0 && isEnPassantCapturePossible(opponentColor)) {
            zobristHash ^= ZobristHash.getKeyForEnPassantFile(newEpTargetSquare % 8);
        }

        // Clear captured piece if any
        if (capturedPiece != Piece.None) {
            zobristHash ^= ZobristHash.getKeyForPieceSquare(capturedPiece, targetSquare);
            clearBit(targetSquare, capturedPiece);
            incAdjust(capturedPiece, targetSquare, -1);  // captured piece removed
        }

        // Remove piece from source square
        zobristHash ^= ZobristHash.getKeyForPieceSquare(movingPiece, startSquare);
        clearBit(startSquare, movingPiece);
        incAdjust(movingPiece, startSquare, -1);         // moving piece vacates source

        // Place piece at target square (promotion can change piece type)
        zobristHash ^= ZobristHash.getKeyForPieceSquare(pieceOnTarget, targetSquare);
        setBit(targetSquare, pieceOnTarget);
        incAdjust(pieceOnTarget, targetSquare, +1);      // piece appears at target

        updateCastlingAvailabilityForMove(movingPiece, startSquare, capturedPiece, targetSquare);

        // Changing the active color after the move is made:
        if (Piece.isWhite(activeColor)) {
            activeColor = Piece.Black;
        } else {
            activeColor = Piece.White;
            fullMoves++;
        }

        // Updating the halfmove clock:
        if ((Piece.type(movingPiece) == Piece.Pawn) || isCapture) {
            halfmoveClock = 0;
        } else {
            halfmoveClock++;
        }

        // Update hash for castling rights if they changed
        int newCastlingRights = castlingAvailabilityToInt();
        if (oldCastlingRights != newCastlingRights) {
            zobristHash ^= ZobristHash.getKeyForCastlingRights(oldCastlingRights);
            zobristHash ^= ZobristHash.getKeyForCastlingRights(newCastlingRights);
        }

        // Update occupancy masks; invalidate lazy attacked-squares cache.
        recomputeOccupancies();
        attackedSquaresValid = false;

        if (!searchMode) {
            movesPlayed.add(new Move(startSquare, targetSquare, Move.reactionOf(packed)));
            boardStates.add(getCurrentFEN());
        }
        zobristStack[zobristSP++] = zobristHash;
    }

    // Reversing the latest move made using efficient bitboard operations:
    public void unmakeMove() {
        if (unmakeSP == 0) {
            throw new IllegalStateException("tried to unmake move when no move was made yet");
        }

        UnmakeInfo undoInfo = unmakePool[--unmakeSP];

        // Restore incremental material+PST scores before any bitboard changes
        incMgScore = undoInfo.previousMgScore;
        incEgScore = undoInfo.previousEgScore;
        int packed = undoInfo.packedMove;
        int startSquare = Move.from(packed);
        int targetSquare = Move.to(packed);
        int flag = Move.flag(packed);

        int movingPiece = getPiece(targetSquare);
        int pieceAtStartAfterUnmake = Move.isPromotion(packed)
            ? (Piece.color(movingPiece) | Piece.Pawn)
            : movingPiece;

        // Remove piece from target square
        clearBit(targetSquare, movingPiece);

        // Place piece back at source square
        setBit(startSquare, pieceAtStartAfterUnmake);

        // Restore captured piece if any
        if (undoInfo.capturedPiece != Piece.None) {
            setBit(targetSquare, undoInfo.capturedPiece);
        }

        // Handle special moves
        switch (flag) {
            case Move.FLAG_CASTLING_K -> {
                castlingReaction(startSquare + 3, true);
                castlingAvailability[Piece.isWhite(movingPiece) ? 0 : 2] = true;
            }
            case Move.FLAG_CASTLING_Q -> {
                castlingReaction(startSquare - 4, true);
                castlingAvailability[Piece.isWhite(movingPiece) ? 1 : 3] = true;
            }
            case Move.FLAG_EN_PASSANT -> {
                int captureSquare = Piece.isWhite(movingPiece) ? targetSquare + 8 : targetSquare - 8;
                setBit(captureSquare, undoInfo.capturedEPPiece);
            }
            // FLAG_EP_TARGET, FLAG_NORMAL, FLAG_PROMO_*: no special bitboard work; state restored below.
        }
        
        // Restore game state
        activeColor = Piece.isWhite(activeColor) ? Piece.Black : Piece.White;
        epTargetSquare = undoInfo.previousEpTargetSquare;
        halfmoveClock = undoInfo.previousHalfmoveClock;
        fullMoves = undoInfo.previousFullMoves;
        System.arraycopy(undoInfo.previousCastlingRights, 0, castlingAvailability, 0, 4);
        
        // Update occupancy masks; invalidate lazy attacked-squares cache.
        recomputeOccupancies();
        attackedSquaresValid = false;

        // Restore Zobrist hash in O(1) from history instead of full recomputation.
        // zobristStack[zobristSP-1] is the hash we just popped; [zobristSP-2] is the prior position.
        if (zobristSP >= 2) {
            zobristHash = zobristStack[zobristSP - 2];
        } else {
            // Edge case (unmaking the very first move): fall back to full recomputation.
            zobristHash = recomputeZobristHash();
        }
        
        if (!searchMode) {
            movesPlayed.remove(movesPlayed.size() - 1);
            boardStates.remove(boardStates.size() - 1);
        }
        if (zobristSP > 0) {
            zobristSP--;
        }
    }
    
    private void castlingReaction(int rookSquare, boolean reverse) {
        if (rookSquare % 8 == 0) {
            // Queen-side castling:
            if (!reverse) {
                int rook = getPiece(rookSquare);
                clearBit(rookSquare, rook);
                setBit(rookSquare + 3, rook);
            } else {
                int rook = getPiece(rookSquare + 3);
                clearBit(rookSquare + 3, rook);
                setBit(rookSquare, rook);
            }
        } else {
            // King-side castling:
            if (!reverse) {
                int rook = getPiece(rookSquare);
                clearBit(rookSquare, rook);
                setBit(rookSquare - 2, rook);
            } else {
                int rook = getPiece(rookSquare - 2);
                clearBit(rookSquare - 2, rook);
                setBit(rookSquare, rook);
            }
        }
    }

    private void updateCastlingAvailabilityForMove(int movingPiece, int startSquare, int capturedPiece, int targetSquare) {
        if (Piece.type(movingPiece) == Piece.King) {
            if (Piece.isWhite(movingPiece)) {
                castlingAvailability[0] = false;
                castlingAvailability[1] = false;
            } else {
                castlingAvailability[2] = false;
                castlingAvailability[3] = false;
            }
        }

        if (Piece.type(movingPiece) == Piece.Rook) {
            if (Piece.isWhite(movingPiece)) {
                if (startSquare == 63) castlingAvailability[0] = false;
                if (startSquare == 56) castlingAvailability[1] = false;
            } else {
                if (startSquare == 7) castlingAvailability[2] = false;
                if (startSquare == 0) castlingAvailability[3] = false;
            }
        }

        if (capturedPiece != Piece.None && Piece.type(capturedPiece) == Piece.Rook) {
            if (Piece.isWhite(capturedPiece)) {
                if (targetSquare == 63) castlingAvailability[0] = false;
                if (targetSquare == 56) castlingAvailability[1] = false;
            } else {
                if (targetSquare == 7) castlingAvailability[2] = false;
                if (targetSquare == 0) castlingAvailability[3] = false;
            }
        }
    }

    public NullMoveState makeNullMove() {
        NullMoveState state = new NullMoveState(activeColor, epTargetSquare, halfmoveClock, fullMoves, zobristHash);

        // Side to move always toggles in Zobrist on a null move.
        zobristHash ^= ZobristHash.getKeyForBlackToMove();

        // Remove old EP file from hash if it was materially reachable.
        if (epTargetSquare >= 0 && isEnPassantCapturePossible(activeColor)) {
            zobristHash ^= ZobristHash.getKeyForEnPassantFile(epTargetSquare % 8);
        }

        epTargetSquare = -1;

        if (Piece.isWhite(activeColor)) {
            activeColor = Piece.Black;
        } else {
            activeColor = Piece.White;
            fullMoves++;
        }

        // Null move is neither a pawn move nor a capture.
        halfmoveClock++;

        return state;
    }

    public void unmakeNullMove(NullMoveState state) {
        activeColor = state.previousActiveColor();
        epTargetSquare = state.previousEpTargetSquare();
        halfmoveClock = state.previousHalfmoveClock();
        fullMoves = state.previousFullMoves();
        zobristHash = state.previousZobristHash();
    }

    // Function for converting an index to a chess square notation (e5, d4, etc.):
    private String getChessSquare(int square) {
        if (square < 0 || square > 63) {
            throw new IllegalArgumentException("invalid square : index out of bounds");
        }

        int rank = 8 - (square / 8);
        char file = (char) (97 + (square % 8));

        return String.format("%s%d", file, rank);
    }

    public int getKingSquare(int color) {
        long bb = (color == Piece.White) ? whiteKing : blackKing;
        if (bb == 0L) throw new IllegalStateException("error: could not find king on board");
        return Long.numberOfTrailingZeros(bb);
    }

    public boolean isActiveColorInCheck() {
        int opponent = (activeColor == Piece.White) ? Piece.Black : Piece.White;
        return isSquareAttackedBy(getKingSquare(activeColor), opponent);
    }

    /** Returns true if the given color's king is attacked by any opponent piece. O(1) via bitboard lookups. */
    public boolean isColorKingInCheck(int color) {
        int opponent = (color == Piece.White) ? Piece.Black : Piece.White;
        return isSquareAttackedBy(getKingSquare(color), opponent);
    }

    /**
     * Returns true if square {@code sq} is attacked by any piece of {@code byColor}.
     * Uses magic bitboards for sliding pieces and precomputed tables for knights/kings.
     * O(1) — no move generation involved.
     */
    public boolean isSquareAttackedBy(int sq, int byColor) {
        long occ = allOccupancy;
        if (byColor == Piece.White) {
            if ((MagicBitboards.getRookAttacks(sq, occ)   & (whiteRooks | whiteQueens))   != 0) return true;
            if ((MagicBitboards.getBishopAttacks(sq, occ) & (whiteBishops | whiteQueens)) != 0) return true;
            if ((AttackTables.KNIGHT_ATTACKS[sq]          & whiteKnights)                 != 0) return true;
            if ((AttackTables.KING_ATTACKS[sq]            & whiteKing)                    != 0) return true;
            // White pawns attack diagonally upward (toward smaller indices)
            long pawnAttacks = ((whitePawns & ~FILE_A) >> 9) | ((whitePawns & ~FILE_H) >> 7);
            return (pawnAttacks & (1L << sq)) != 0;
        } else {
            if ((MagicBitboards.getRookAttacks(sq, occ)   & (blackRooks | blackQueens))   != 0) return true;
            if ((MagicBitboards.getBishopAttacks(sq, occ) & (blackBishops | blackQueens)) != 0) return true;
            if ((AttackTables.KNIGHT_ATTACKS[sq]          & blackKnights)                 != 0) return true;
            if ((AttackTables.KING_ATTACKS[sq]            & blackKing)                    != 0) return true;
            // Black pawns attack diagonally downward (toward larger indices)
            long pawnAttacks = ((blackPawns & ~FILE_A) << 7) | ((blackPawns & ~FILE_H) << 9);
            return (pawnAttacks & (1L << sq)) != 0;
        }
    }

    public boolean isCheckmate() {
        MovesGenerator movesGenerator = new MovesGenerator(this);
        boolean inCheck = movesGenerator.isKingInCheck(getKingSquare(activeColor), activeColor)[0];
        boolean hasLegalMoves = !movesGenerator.getActiveMoves(activeColor).isEmpty();
        return inCheck && !hasLegalMoves;
    }

    public boolean isStalemate() {
        MovesGenerator movesGenerator = new MovesGenerator(this);
        boolean inCheck = movesGenerator.isKingInCheck(getKingSquare(activeColor), activeColor)[0];
        boolean hasLegalMoves = !movesGenerator.getActiveMoves(activeColor).isEmpty();
        return !inCheck && !hasLegalMoves;
    }

    public boolean isThreefoldRepetition() {
        int repetitions = 0;
        for (int i = 0; i < zobristSP; i++) {
            if (zobristStack[i] == zobristHash) {
                repetitions++;
            }
        }
        return repetitions >= 3;
    }

    /**
     * Returns true if the current position has appeared at least once before in the
     * current search path (2-fold repetition detection for use inside alphaBeta).
     *
     * <p>The look-back window is bounded by {@code halfmoveClock + 1}: positions after
     * an irreversible move (capture or pawn push) can never be repetitions, so scanning
     * beyond the halfmove-clock window is both unnecessary and incorrect.
     *
     * <p>{@code zobristHistory} always contains the current position as its last element
     * (added by {@code makeMove()} before returning), so the first match is the "current"
     * occurrence and a second match indicates a true repetition.
     */
    public boolean isRepetitionDraw() {
        // Repetitions can only occur among same-side-to-move positions, i.e., every 2 plies.
        // Bound the window by the halfmove clock (reset on any irreversible move).
        int limit = Math.min(zobristSP, halfmoveClock + 1);
        int count = 0;
        for (int i = zobristSP - limit; i < zobristSP; i++) {
            if (zobristStack[i] == zobristHash) {
                count++;
                if (count >= 2) return true;
            }
        }
        return false;
    }

    public boolean isFiftyMoveRuleDraw() {
        return halfmoveClock >= 100;
    }

    public boolean isInsufficientMaterial() {
        if (whitePawns != 0 || whiteRooks != 0 || whiteQueens != 0) return false;
        if (blackPawns != 0 || blackRooks != 0 || blackQueens != 0) return false;
        int wMinors = Long.bitCount(whiteKnights) + Long.bitCount(whiteBishops);
        int bMinors = Long.bitCount(blackKnights) + Long.bitCount(blackBishops);
        if (wMinors == 0 && bMinors == 0) return true;
        if (wMinors == 1 && bMinors == 0) return true;
        if (wMinors == 0 && bMinors == 1) return true;
        if (wMinors == 1 && bMinors == 1 && whiteKnights == 0 && blackKnights == 0) {
            int wSq = Long.numberOfTrailingZeros(whiteBishops);
            int bSq = Long.numberOfTrailingZeros(blackBishops);
            return ((wSq / 8 + wSq % 8) % 2) == ((bSq / 8 + bSq % 8) % 2);
        }
        return false;
    }

    public String toFen() {
        return getCurrentFEN();
    }

    // Getters and setters:
    public int getPiece(int square) {
        if (square < 0 || square > 63) {
            throw new IllegalArgumentException("invalid square : index out of bounds");
        }

        return getPieceFromBitboards(square);
    }

    public int[] getGrid() {
        int[] grid = new int[64];
        for (int i = 0; i < 64; i++) {
            grid[i] = getPieceFromBitboards(i);
        }
        return grid;
    }

    public int getActiveColor() {
        return activeColor;
    }

    public boolean[] getCastlingAvailability() {
        return castlingAvailability;
    }

    public int getEpTargetSquare() {
        return epTargetSquare;
    }

    public int getHalfmoveClock() {
        return halfmoveClock;
    }

    public int getFullMoves() {
        return fullMoves;
    }
    
    // Bitboard getters for snapshots and serialization
    public long getWhitePawns() { return whitePawns; }
    public long getWhiteKnights() { return whiteKnights; }
    public long getWhiteBishops() { return whiteBishops; }
    public long getWhiteRooks() { return whiteRooks; }
    public long getWhiteQueens() { return whiteQueens; }
    public long getWhiteKing() { return whiteKing; }
    public long getBlackPawns() { return blackPawns; }
    public long getBlackKnights() { return blackKnights; }
    public long getBlackBishops() { return blackBishops; }
    public long getBlackRooks() { return blackRooks; }
    public long getBlackQueens() { return blackQueens; }
    public long getBlackKing() { return blackKing; }
    public long getWhiteOccupancy() { return whiteOccupancy; }
    public long getBlackOccupancy() { return blackOccupancy; }
    public long getAllOccupancy() { return allOccupancy; }
    
    public long getZobristHash() {
        return zobristHash;
    }

    /** Returns the incrementally maintained MG material+PST score (white minus black). */
    public int getIncMgScore() { return incMgScore; }

    /** Returns the incrementally maintained EG material+PST score (white minus black). */
    public int getIncEgScore() { return incEgScore; }

    /**
     * Compute a Zobrist hash for the pawn structure only (white and black pawns by square).
     * Used to key the pawn hash table in the evaluator. O(pawns) ≤ O(16) operations.
     */
    public long getPawnZobristHash() {
        long hash = 0L;
        int whitePawnPiece = Piece.White | Piece.Pawn;
        long wp = whitePawns;
        while (wp != 0) {
            hash ^= ZobristHash.getKeyForPieceSquare(whitePawnPiece, Long.numberOfTrailingZeros(wp));
            wp &= wp - 1;
        }
        int blackPawnPiece = Piece.Black | Piece.Pawn;
        long bp = blackPawns;
        while (bp != 0) {
            hash ^= ZobristHash.getKeyForPieceSquare(blackPawnPiece, Long.numberOfTrailingZeros(bp));
            bp &= bp - 1;
        }
        return hash;
    }

    public BitboardPosition toBitboardPosition() {
        BitboardPosition position = new BitboardPosition();
        position.whitePawns = this.whitePawns;
        position.whiteKnights = this.whiteKnights;
        position.whiteBishops = this.whiteBishops;
        position.whiteRooks = this.whiteRooks;
        position.whiteQueens = this.whiteQueens;
        position.whiteKing = this.whiteKing;
        position.blackPawns = this.blackPawns;
        position.blackKnights = this.blackKnights;
        position.blackBishops = this.blackBishops;
        position.blackRooks = this.blackRooks;
        position.blackQueens = this.blackQueens;
        position.blackKing = this.blackKing;
        position.whiteOccupancy = this.whiteOccupancy;
        position.blackOccupancy = this.blackOccupancy;
        position.allOccupancy = this.allOccupancy;
        position.whiteToMove = Piece.isWhite(this.activeColor);
        position.castlingRights = toCastlingRights(this.castlingAvailability);
        position.enPassantSquare = this.epTargetSquare;
        position.halfmoveClock = this.halfmoveClock;
        position.fullmoveNumber = this.fullMoves;
        return position;
    }
    
    private int toCastlingRights(boolean[] castlingAvailability) {
        int rights = 0;
        if (castlingAvailability[0]) rights |= 0x1; // White kingside
        if (castlingAvailability[1]) rights |= 0x2; // White queenside
        if (castlingAvailability[2]) rights |= 0x4; // Black kingside
        if (castlingAvailability[3]) rights |= 0x8; // Black queenside
        return rights;
    }
}