package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;

import java.util.*;


public class MovesGenerator {
    public static final int[] DirectionOffsets = { -8, 1, 8, -1, -9, -7, 9, 7 };
    public static int[][] SquaresToEdges = new int[64][8];  // Holds the number of squares to each edge from each square (for easier computation)
    private final Board board;

    private final ArrayList<Move> possibleMoves = new ArrayList<>();

    public MovesGenerator(Board board) {
        this.board = board;
        ComputeMoveData();
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

        for (int sq = 0; sq < 64; sq++) {
            int currentPiece = board.getPiece(sq);

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

    // Returns if the king is in check, and if it is in a double check:
    public boolean[] isKingInCheck(int kingSquare, int activeColor) {
        int inactiveColor = (Piece.isWhite(activeColor)) ? Piece.Black : Piece.White;
        ArrayList<Move> opponentMoves = getActiveMoves(inactiveColor);

        ArrayList<Move> kingCaptureMoves = new ArrayList<>();

        for (Move move : opponentMoves) {
            if (move.targetSquare == kingSquare) {
                kingCaptureMoves.add(move);
            }
        }

        return new boolean[] {!kingCaptureMoves.isEmpty(), kingCaptureMoves.size() == 2};
    }

    public ArrayList<Move> getAllMoves() {
        return (ArrayList<Move>) possibleMoves.clone();
    }

    public ArrayList<Move> getActiveMoves(int activeColor) {
        ArrayList<Move> colorSpecificMoves = new ArrayList<>();

        for (Move move : possibleMoves) {
            if (Piece.isColor(board.getGrid()[move.startSquare], activeColor)) {
                colorSpecificMoves.add(move);
            }
        }

        return colorSpecificMoves;
    }

    private void makeMovesLegal(int activeColor) {
        ArrayList<Move> initialPossibleMoves = getAllMoves();
        ArrayList<Move> colorSpecificMoves = getActiveMoves(activeColor);

        for (Move move : colorSpecificMoves) {
            board.makeMove(move);
            generateMoves();  // To update the possible moves after a move was made

            // Obtaining the king square here so that if the move made was a king move, then the updated square is taken into account:
            int kingSquare = board.getKingSquare(activeColor);

            boolean[] kingCheck = isKingInCheck(kingSquare, activeColor);
            boolean inCheck = kingCheck[0], doubleCheck = kingCheck[1];

            if (inCheck || doubleCheck) {
                initialPossibleMoves.remove(move);
            }

            board.unmakeMove();

            // Reverting the possible moves back to the initial state (before the move was made):
            possibleMoves.clear();
            possibleMoves.addAll(initialPossibleMoves);
        }
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

    // For long range pieces - queen, rook, bishop:
    private void generateSlidingMoves(int startSquare, int currentPiece) {
        int startDirIndex;
        int endDirIndex;
        int pieceType = Piece.type(currentPiece);

        startDirIndex = (pieceType == Piece.Bishop) ? 4 : 0;
        endDirIndex = (pieceType == Piece.Rook) ? 3 : 7;

        for (int direction = startDirIndex; direction <= endDirIndex; direction++) {
            for (int num = 0; num < SquaresToEdges[startSquare][direction]; num++) {
                int targetSquare = startSquare + (DirectionOffsets[direction] * (num + 1));
                int targetPiece = board.getPiece(targetSquare);

                // Changing direction if path is blocked by a friendly piece:
                if (Piece.isColor(currentPiece, targetPiece)) break;

                possibleMoves.add(new Move(startSquare, targetSquare));

                // Changing direction after capture of opposite color piece:
                if (Piece.type(targetPiece) != Piece.None) break;
            }
        }
    }

    // For pawns:
    private void generatePawnMoves(int startSquare, int currentPawn) {
        int naturalOffset;
        int numOfSquaresToCheck = 1;

        int[] captureOffsets = new int[2];

        if (Piece.isWhite(currentPawn)) {
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

            if (i == 0) {
                possibleMoves.add(new Move(startSquare, targetSquare));
            } else {
                possibleMoves.add(new Move(startSquare, targetSquare, "ep-target"));
            }
        }

        // Capturing moves:
        for (int offset : captureOffsets) {
            int targetSquare = startSquare + offset;
            int targetPiece = board.getPiece(targetSquare);

            // En passant square check:
            if (targetSquare == board.getEpTargetSquare()) {
                int epPawnSquare;
                if (board.getEpTargetSquare() % 3 == 2) {
                    epPawnSquare = targetSquare + 8;
                } else {
                    epPawnSquare = targetSquare - 8;
                }

                // Obtaining the en-passant pawn (epPawn), and then adding the move only if the current pawn and the
                // epPawn are of opposite colors:
                int epPawn = board.getPiece(epPawnSquare);

                if (!Piece.isColor(epPawn, currentPawn)) {
                    possibleMoves.add(new Move(startSquare, targetSquare, "en-passant"));
                }
            }

            // Opponent pawn on capture square check:
            else if ((Piece.color(targetPiece) != 0) && (!Piece.isColor(currentPawn, targetPiece))) {
                possibleMoves.add(new Move(startSquare, targetSquare));
            }
        }
    }

    // For knights:
    private void generateKnightMoves(int startSquare, int currentKnight) {
        // Offsets with their allowed row shifts:
        Map<Integer, Integer> offsetsWithRowMoves = new HashMap<>() {{
           put(-10, 1); put(-17, 2); put(-15, 2); put(-6, 1);
           put(10, 1); put(17, 2); put(15, 2); put(6, 1);
        }};

        // Adding the possible moves:
        offsetsWithRowMoves.forEach((offset, rowMoves) -> {
            int targetSquare = startSquare + offset;

            if (Math.abs((startSquare / 8) - (targetSquare / 8)) == rowMoves) {
                if (targetSquare >= 0 && targetSquare <= 63) {
                    int targetPiece = board.getPiece(targetSquare);

                    if (!Piece.isColor(currentKnight, targetPiece)) {
                        possibleMoves.add(new Move(startSquare, targetSquare));
                    }
                }
            }
        });
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

        // Knight attacks
        Map<Integer, Integer> knightOffsetsWithRowMoves = new HashMap<>() {{
            put(-10, 1); put(-17, 2); put(-15, 2); put(-6, 1);
            put(10, 1); put(17, 2); put(15, 2); put(6, 1);
        }};

        for (Map.Entry<Integer, Integer> entry : knightOffsetsWithRowMoves.entrySet()) {
            int sourceSquare = square + entry.getKey();
            if (sourceSquare < 0 || sourceSquare > 63) continue;

            if (Math.abs((sourceSquare / 8) - (square / 8)) != entry.getValue()) continue;

            int piece = board.getPiece(sourceSquare);
            if (Piece.isColor(piece, attackerColor) && Piece.type(piece) == Piece.Knight) {
                return true;
            }
        }

        // Sliding attacks (rook/queen orthogonal, bishop/queen diagonal)
        for (int direction = 0; direction < DirectionOffsets.length; direction++) {
            for (int distance = 0; distance < SquaresToEdges[square][direction]; distance++) {
                int sourceSquare = square + DirectionOffsets[direction] * (distance + 1);
                int piece = board.getPiece(sourceSquare);

                if (piece == Piece.None) continue;
                if (!Piece.isColor(piece, attackerColor)) break;

                int type = Piece.type(piece);
                boolean orthogonalDirection = direction <= 3;

                if ((orthogonalDirection && (type == Piece.Rook || type == Piece.Queen))
                        || (!orthogonalDirection && (type == Piece.Bishop || type == Piece.Queen))) {
                    return true;
                }

                break;
            }
        }

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
}