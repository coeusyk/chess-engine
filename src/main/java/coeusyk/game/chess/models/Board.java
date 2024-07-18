package coeusyk.game.chess.models;

import java.util.*;


public class Board {
    private final int[] grid = new int[64];

    static final String STARTING_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    // Board Attributes:
    private int activeColor = Piece.White;  // The color to move
    private final boolean[] castlingAvailability = { true, true, true, true };  // First two for white king, other for black king
    private int epTargetSquare;  // The current target square for En Passant
    private int halfmoveClock;  // This takes care of enforcing the 50-move rule (resets after captures and pawn moves)
    private int fullMoves = 1;  // Starts at 1 and updates after black's move

    // Possible Move Reactions:
    private final String[] reactionIds = { "castle-k", "castle-q", "en-passant", "ep-target" };

    // Moves made until current position:
    public ArrayList<Move> movesPlayed = new ArrayList<>();

    public Board() {
        initWithFEN(STARTING_FEN);
    }

    public Board(String fenString) {
        initWithFEN(fenString);
    }

    private void initWithFEN(String fenString) {
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

        Map<String, Integer> castlingChars = new HashMap<>() {{
            put("K", 0); put("Q", 1);
            put("k", 2); put("q", 3);
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

                grid[index] = value | pieceChars.get(ch);
                index++;
            }
        }

        // Initialising the active color:
        if (boardInformation[0].equals("b")) activeColor = Piece.Black;

        // Initializing the castling availability:
        for (Map.Entry<String, Integer> entry : castlingChars.entrySet()) {
            if (!boardInformation[1].contains(entry.getKey())) {
                castlingAvailability[entry.getValue()] = false;
            }
        }

        // Initializing the En Passant Target Square:
        if (!boardInformation[2].equals("-")) {
            checkEPTargetSquare(boardInformation[2]);

            int fileAscii = boardInformation[2].charAt(0);
            int eptsRank = Character.getNumericValue(boardInformation[2].charAt(1));

            epTargetSquare = (8 * eptsRank) + (fileAscii - 97) - 1;
        }

        // Initializing the Halfmove Clock;
        halfmoveClock = Integer.parseInt(boardInformation[3]);

        // Initializing the Full Moves:
        fullMoves = Integer.parseInt(boardInformation[4]);
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

    // Move to make after list of moves are sent to the client and the move made is reported back to the server:
    public void makeMove(Move move) {
        if (move.reaction != null) {
            if (!Arrays.asList(reactionIds).contains(move.reaction)) {
                throw new IllegalArgumentException("invalid move reaction : does not exist");
            } else {
                switch (move.reaction) {
                    case "castle-k" -> {
                        castlingReaction(move.startSquare + 3);
                        if (Piece.isWhite(grid[move.startSquare])) {
                            castlingAvailability[0] = false;
                        } else {
                            castlingAvailability[2] = false;
                        }
                    }

                    case "castle-q" -> {
                        castlingReaction(move.startSquare - 4);
                        if (Piece.isWhite(grid[move.startSquare])) {
                            castlingAvailability[1] = false;
                        } else {
                            castlingAvailability[3] = false;
                        }
                    }

                    case "en-passant" -> {
                        if (Piece.isWhite(grid[move.startSquare])) {
                            grid[move.targetSquare + 8] = Piece.None;
                        } else {
                            grid[move.targetSquare - 8] = Piece.None;
                        }
                    }

                    case "ep-target" -> {
                        if (Piece.isWhite(grid[move.startSquare])) {
                            epTargetSquare = move.targetSquare + 8;
                        } else {
                            epTargetSquare = move.targetSquare - 8;
                        }
                    }
                }
            }
        }

        // Changing the active color after the move is made:
        if (Piece.isWhite(grid[move.startSquare])) {
            activeColor = Piece.Black;
        } else {
            activeColor = Piece.White;
            fullMoves++;
        }

        grid[move.targetSquare] = grid[move.startSquare];
        grid[move.startSquare] = Piece.None;

        movesPlayed.add(move);
    }

    private void castlingReaction(int rookSquare) {
        if (rookSquare % 8 == 0) {
            // Queen-side castling:
            grid[rookSquare + 3] = grid[rookSquare];
        } else {
            // King-side castling:
            grid[rookSquare - 2] = grid[rookSquare];
        }

        grid[rookSquare] = Piece.None;
    }

    // Getters and setters:
    public int getPiece(int square) {
        if (square < 0 || square > 63) {
            throw new IllegalArgumentException("invalid square : index out of bounds");
        }

        return grid[square];
    }

    public int[] getGrid() {
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
}