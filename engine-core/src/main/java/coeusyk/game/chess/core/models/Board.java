package coeusyk.game.chess.core.models;

import coeusyk.game.chess.core.bitboard.BitboardPosition;

import java.util.*;


public class Board {
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

    // Possible Move Reactions:
    private final String[] reactionIds = { "castle-k", "castle-q", "en-passant", "ep-target" };

    // Moves made until current position:
    public ArrayList<Move> movesPlayed = new ArrayList<>();
    public ArrayList<String> boardStates = new ArrayList<>();  // Storing the states of the board

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

            int baseFileAscii = 'a';
            int fileAscii = boardInformation[2].charAt(0);

            int eptsRank = Character.getNumericValue(boardInformation[2].charAt(1));

            epTargetSquare = (8 * (eptsRank - 1)) + (fileAscii - baseFileAscii) + 1;
        }

        // Initializing the Halfmove Clock:
        halfmoveClock = Integer.parseInt(boardInformation[3]);

        // Initializing the Full Moves:
        fullMoves = Integer.parseInt(boardInformation[4]);

        // Adding the current FEN to the board states (only if it doesn't exist beforehand):
        if (!boardStates.contains(fenString)) {
            boardStates.add(fenString);
        }
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
        boolean isCapture = capturedPiece != Piece.None;
        
        if (move.reaction != null) {
            if (!Arrays.asList(reactionIds).contains(move.reaction)) {
                throw new IllegalArgumentException("invalid move reaction : does not exist");
            } else {
                switch (move.reaction) {
                    case "castle-k" -> {
                        castlingReaction(move.startSquare + 3, false);
                        if (Piece.isWhite(movingPiece)) {
                            castlingAvailability[0] = false;
                        } else {
                            castlingAvailability[2] = false;
                        }
                    }

                    case "castle-q" -> {
                        castlingReaction(move.startSquare - 4, false);
                        if (Piece.isWhite(movingPiece)) {
                            castlingAvailability[1] = false;
                        } else {
                            castlingAvailability[3] = false;
                        }
                    }

                    case "en-passant" -> {
                        int captureSquare = Piece.isWhite(movingPiece) ? move.targetSquare + 8 : move.targetSquare - 8;
                        int capturedEPPiece = getPiece(captureSquare);
                        clearBit(captureSquare, capturedEPPiece);
                        isCapture = true;  // Mark as capture for halfmove clock
                    }

                    case "ep-target" -> {
                        if (Piece.isWhite(movingPiece)) {
                            epTargetSquare = move.targetSquare + 8;
                        } else {
                            epTargetSquare = move.targetSquare - 8;
                        }
                    }
                }
            }
        }

        // Clear captured piece if any
        if (capturedPiece != Piece.None) {
            clearBit(move.targetSquare, capturedPiece);
        }
        
        // Remove piece from source square
        clearBit(move.startSquare, movingPiece);
        
        // Place piece at target square
        setBit(move.targetSquare, movingPiece);

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

        // Update occupancy masks
        recomputeOccupancies();
        
        movesPlayed.add(move);
        boardStates.add(getCurrentFEN());
    }

    // Reversing the latest move made:
    public void unmakeMove() {
        if (movesPlayed.isEmpty()) {
            throw new IllegalStateException("tried to unmake move when no move was made yet");
        }

        movesPlayed.remove(movesPlayed.size() - 1);
        boardStates.remove(boardStates.size() - 1);

        // Setting the board back to the previous state by re-setting the board based on previous state's FEN string:
        String prevBoardState = boardStates.get(boardStates.size() - 1);
        initWithFEN(prevBoardState);
    }
    
    private void castlingReaction(int rookSquare, boolean reverse) {
        int rook = getPiece(rookSquare);
        
        if (rookSquare % 8 == 0) {
            // Queen-side castling:
            if (!reverse) {
                clearBit(rookSquare, rook);
                setBit(rookSquare + 3, rook);
            } else {
                clearBit(rookSquare + 3, rook);
                setBit(rookSquare, rook);
            }
        } else {
            // King-side castling:
            if (!reverse) {
                clearBit(rookSquare, rook);
                setBit(rookSquare - 2, rook);
            } else {
                clearBit(rookSquare - 2, rook);
                setBit(rookSquare, rook);
            }
        }
    }

    // Function for converting an index to a chess square notation (e5, d4, etc.):
    private String getChessSquare(int square) {
        if (square < 0 || square > 63) {
            throw new IllegalArgumentException("invalid square : index out of bounds");
        }

        int rank = (square / 8) + 1;
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