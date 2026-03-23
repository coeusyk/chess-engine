package coeusyk.game.chess.core.models;

import coeusyk.game.chess.core.bitboard.BitboardPosition;
import coeusyk.game.chess.core.bitboard.ZobristHash;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.util.*;


public class Board {
    // Inner class to store move undo information for efficient unmake without FEN parsing
    private static class UnmakeInfo {
        int capturedPiece;              // Piece captured on target square (Piece.None if none)
        int capturedEPPiece;            // For en passant: piece captured on EP square
        int previousEpTargetSquare;     // En passant target square before the move
        boolean[] previousCastlingRights; // Castling availability before the move
        int previousHalfmoveClock;      // Halfmove clock before the move
        int previousFullMoves;          // Full move counter before the move
        Move move;                      // The move that was made

        UnmakeInfo(Move move, int capturedPiece, int capturedEPPiece, int prevEP, 
                   boolean[] prevCastling, int prevHalf, int prevFull) {
            this.move = move;
            this.capturedPiece = capturedPiece;
            this.capturedEPPiece = capturedEPPiece;
            this.previousEpTargetSquare = prevEP;
            this.previousCastlingRights = prevCastling.clone();
            this.previousHalfmoveClock = prevHalf;
            this.previousFullMoves = prevFull;
        }
    }

    // Bitboard representation (12 piece types: 6 per color)
    private long whitePawns, whiteKnights, whiteBishops, whiteRooks, whiteQueens, whiteKing;
    private long blackPawns, blackKnights, blackBishops, blackRooks, blackQueens, blackKing;
    
    // Occupancy masks
    private long whiteOccupancy, blackOccupancy, allOccupancy;

    static final String STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
//    static final String STARTING_FEN = "8/8/5k2/8/p7/P1B5/4K3/8 b - - 0 1";

    // Board Attributes:
    private int activeColor = Piece.White;  // The color to move

    private final boolean[] castlingAvailability = { false, false, false, false };  // First two for white king, other for black king
//    private final int[] castledRookSquares = { 5, 3, 61, 59 };  // Even indices for king side, odd indices for queen side

    private int epTargetSquare = -1;  // The current target square for En Passant
    private int halfmoveClock;  // This takes care of enforcing the 50-move rule (resets after captures and pawn moves)
    private int fullMoves = 1;  // Starts at 1 and updates after black's move
    
    // Zobrist hash for the current position
    private long zobristHash;

    // Possible Move Reactions:
    private final String[] reactionIds = {
        "castle-k", "castle-q", "en-passant", "ep-target",
        "promote-q", "promote-r", "promote-b", "promote-n"
    };

    // Moves made until current position:
    public ArrayList<Move> movesPlayed = new ArrayList<>();
    public ArrayList<String> boardStates = new ArrayList<>();  // Storing the states of the board
    private final ArrayList<Long> zobristHistory = new ArrayList<>();
    
    // Stack of move undo information for efficient unmake without FEN parsing
    private Stack<UnmakeInfo> unmakeStack = new Stack<>();

    public Board() {
        initWithFEN(STARTING_FEN);

        if (!boardStates.contains(STARTING_FEN)) {
            boardStates.add(STARTING_FEN);
        }
    }

    public Board(String fenString) {
        initWithFEN(fenString);

        if (!boardStates.contains(fenString)) {
            boardStates.add(fenString);
        }
    }

    // Initializing the board with a FEN string:
    private void initWithFEN(String fenString) {
        // Clearing all bitboards
        whitePawns = whiteKnights = whiteBishops = whiteRooks = whiteQueens = whiteKing = 0L;
        blackPawns = blackKnights = blackBishops = blackRooks = blackQueens = blackKing = 0L;
        whiteOccupancy = blackOccupancy = allOccupancy = 0L;
        zobristHistory.clear();

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
        zobristHistory.add(zobristHash);
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
        int movingPiece = getPiece(move.startSquare);
        int capturedPiece = getPiece(move.targetSquare);
        int capturedEPPiece = Piece.None;
        boolean isCapture = capturedPiece != Piece.None;
        int newEpTargetSquare = -1;
        int pieceOnTarget = movingPiece;
        
        // Validate reaction before any state/hash mutations to avoid partial corruption on error
        if (move.reaction != null && !Arrays.asList(reactionIds).contains(move.reaction)) {
            throw new IllegalArgumentException("invalid move reaction : does not exist");
        }
        
        // Save undo information before modifying the board
        UnmakeInfo unmakeInfo = new UnmakeInfo(move, capturedPiece, capturedEPPiece, 
                epTargetSquare, castlingAvailability, halfmoveClock, fullMoves);
        
        // Update Zobrist hash for side-to-move
        zobristHash ^= ZobristHash.getKeyForBlackToMove();
        
        // Save old castling rights for hash
        int oldCastlingRights = castlingAvailabilityToInt();
        
        if (move.reaction != null) {
            switch (move.reaction) {
                    case "castle-k" -> {
                        // Update hash for rook on start and end position
                        int rookSquare = move.startSquare + 3;
                        int rookPiece = getPiece(rookSquare);
                        zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare);
                        
                        castlingReaction(rookSquare, false);
                        
                        // Update hash for rook on new position
                        zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare - 2);
                        
                    }

                    case "castle-q" -> {
                        // Update hash for rook on start and end position
                        int rookSquare = move.startSquare - 4;
                        int rookPiece = getPiece(rookSquare);
                        zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare);
                        
                        castlingReaction(rookSquare, false);
                        
                        // Update hash for rook on new position
                        zobristHash ^= ZobristHash.getKeyForPieceSquare(rookPiece, rookSquare + 3);
                        
                    }

                    case "en-passant" -> {
                        int captureSquare = Piece.isWhite(movingPiece) ? move.targetSquare + 8 : move.targetSquare - 8;
                        capturedEPPiece = getPiece(captureSquare);
                        unmakeInfo.capturedEPPiece = capturedEPPiece;
                        
                        // Update hash for captured pawn
                        zobristHash ^= ZobristHash.getKeyForPieceSquare(capturedEPPiece, captureSquare);
                        
                        clearBit(captureSquare, capturedEPPiece);
                        isCapture = true;  // Mark as capture for halfmove clock
                    }

                    case "ep-target" -> {
                        if (Piece.isWhite(movingPiece)) {
                            newEpTargetSquare = move.targetSquare + 8;
                        } else {
                            newEpTargetSquare = move.targetSquare - 8;
                        }
                    }

                    case "promote-q", "promote-r", "promote-b", "promote-n" ->
                            pieceOnTarget = getPromotionPieceForReaction(movingPiece, move.reaction);
                }
        }
        
        // Update hash for en passant file.
        // Only include EP in the hash when a capture is actually legal (FIDE rule for position identity).
        // Remove old EP hash contribution if one was present and capturable.
        if (epTargetSquare >= 0 && isEnPassantCapturePossible(activeColor)) {
            zobristHash ^= ZobristHash.getKeyForEnPassantFile(epTargetSquare % 8);
        }
        epTargetSquare = newEpTargetSquare;
        // Add new EP hash contribution only if the opponent can actually capture.
        int opponentColor = Piece.isWhite(activeColor) ? Piece.Black : Piece.White;
        if (newEpTargetSquare >= 0 && isEnPassantCapturePossible(opponentColor)) {
            zobristHash ^= ZobristHash.getKeyForEnPassantFile(newEpTargetSquare % 8);
        }

        // Clear captured piece if any
        if (capturedPiece != Piece.None) {
            zobristHash ^= ZobristHash.getKeyForPieceSquare(capturedPiece, move.targetSquare);
            clearBit(move.targetSquare, capturedPiece);
        }
        
        // Remove piece from source square
        zobristHash ^= ZobristHash.getKeyForPieceSquare(movingPiece, move.startSquare);
        clearBit(move.startSquare, movingPiece);
        
        // Place piece at target square (promotion can change piece type)
        zobristHash ^= ZobristHash.getKeyForPieceSquare(pieceOnTarget, move.targetSquare);
        setBit(move.targetSquare, pieceOnTarget);

        updateCastlingAvailabilityForMove(movingPiece, move.startSquare, capturedPiece, move.targetSquare);

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

        // Update occupancy masks
        recomputeOccupancies();
        
        // Push undo information
        unmakeStack.push(unmakeInfo);
        
        movesPlayed.add(move);
        boardStates.add(getCurrentFEN());
        zobristHistory.add(zobristHash);
    }

    // Reversing the latest move made using efficient bitboard operations:
    public void unmakeMove() {
        if (movesPlayed.isEmpty() || unmakeStack.isEmpty()) {
            throw new IllegalStateException("tried to unmake move when no move was made yet");
        }

        UnmakeInfo undoInfo = unmakeStack.pop();
        Move move = undoInfo.move;
        int movingPiece = getPiece(move.targetSquare);
        int pieceAtStartAfterUnmake = isPromotionReaction(move.reaction)
            ? (Piece.color(movingPiece) | Piece.Pawn)
            : movingPiece;
        
        // Remove piece from target square
        clearBit(move.targetSquare, movingPiece);
        
        // Place piece back at source square
        setBit(move.startSquare, pieceAtStartAfterUnmake);
        
        // Restore captured piece if any
        if (undoInfo.capturedPiece != Piece.None) {
            setBit(move.targetSquare, undoInfo.capturedPiece);
        }
        
        // Handle special moves
        if (move.reaction != null) {
            switch (move.reaction) {
                case "castle-k" -> {
                    castlingReaction(move.startSquare + 3, true);
                    castlingAvailability[Piece.isWhite(movingPiece) ? 0 : 2] = true;
                }
                
                case "castle-q" -> {
                    castlingReaction(move.startSquare - 4, true);
                    castlingAvailability[Piece.isWhite(movingPiece) ? 1 : 3] = true;
                }
                
                case "en-passant" -> {
                    int captureSquare = Piece.isWhite(movingPiece) ? move.targetSquare + 8 : move.targetSquare - 8;
                    setBit(captureSquare, undoInfo.capturedEPPiece);
                }
                
                case "ep-target" -> {
                    // Just restore the previous ep square (already handled below)
                }
            }
        }
        
        // Restore game state
        activeColor = Piece.isWhite(activeColor) ? Piece.Black : Piece.White;
        epTargetSquare = undoInfo.previousEpTargetSquare;
        halfmoveClock = undoInfo.previousHalfmoveClock;
        fullMoves = undoInfo.previousFullMoves;
        System.arraycopy(undoInfo.previousCastlingRights, 0, castlingAvailability, 0, 4);
        
        // Update occupancy masks
        recomputeOccupancies();
        
        // Restore Zobrist hash in O(1) from history instead of full recomputation.
        // zobristHistory contains the hash after each move; the second-to-last entry is
        // the hash for the position we are restoring to.
        if (zobristHistory.size() >= 2) {
            zobristHash = zobristHistory.get(zobristHistory.size() - 2);
        } else {
            // Edge case (unmaking the very first move): fall back to full recomputation.
            zobristHash = recomputeZobristHash();
        }
        
        movesPlayed.remove(movesPlayed.size() - 1);
        boardStates.remove(boardStates.size() - 1);
        if (!zobristHistory.isEmpty()) {
            zobristHistory.remove(zobristHistory.size() - 1);
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

    private boolean isPromotionReaction(String reaction) {
        return "promote-q".equals(reaction)
                || "promote-r".equals(reaction)
                || "promote-b".equals(reaction)
                || "promote-n".equals(reaction);
    }

    private int getPromotionPieceForReaction(int pawnPiece, String reaction) {
        int color = Piece.color(pawnPiece);
        return switch (reaction) {
            case "promote-q" -> color | Piece.Queen;
            case "promote-r" -> color | Piece.Rook;
            case "promote-b" -> color | Piece.Bishop;
            case "promote-n" -> color | Piece.Knight;
            default -> pawnPiece;
        };
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

    public int getKingSquare(int activeColor) {
        for (int sq = 0; sq < 64; sq++) {
            int piece = getPiece(sq);
            if ((Piece.type(piece) == Piece.King) && Piece.isColor(piece, activeColor)) {
                return sq;
            }
        }

        throw new IllegalStateException("error: could not find king on board");
    }

    public boolean isActiveColorInCheck() {
        MovesGenerator movesGenerator = new MovesGenerator(this);
        return movesGenerator.isKingInCheck(getKingSquare(activeColor), activeColor)[0];
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
        for (long hash : zobristHistory) {
            if (hash == zobristHash) {
                repetitions++;
            }
        }
        return repetitions >= 3;
    }

    public boolean isFiftyMoveRuleDraw() {
        return halfmoveClock >= 100;
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