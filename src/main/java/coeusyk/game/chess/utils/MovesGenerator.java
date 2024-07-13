package coeusyk.game.chess.utils;

import coeusyk.game.chess.models.Board;
import coeusyk.game.chess.models.Move;
import coeusyk.game.chess.models.Piece;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


public class MovesGenerator {
    public static final int[] DirectionOffsets = { -8, 1, 8, -1, -9, -7, 9, 7 };
    private static final Logger log = LoggerFactory.getLogger(MovesGenerator.class);
    public static int[][] SquaresToEdges = new int[64][8];  // Holds the number of squares to each edge from each square (for easier computation)
    private final Board board;

    private final ArrayList<Move> possibleMoves = new ArrayList<>();

    public MovesGenerator(Board board) {
        this.board = board;
        ComputeMoveData();
        generateMoves();
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

    public ArrayList<Move> getAllMoves() {
        return possibleMoves;
    }

    public ArrayList<Move> getActiveMoves(int activeColor) {
        ArrayList<Move> colorSpecificMoves = new ArrayList<>();

        possibleMoves.forEach(move -> {
            if (Piece.color(board.getGrid()[move.startSquare]) == activeColor) {
                colorSpecificMoves.add(move);
            }
        });

        return colorSpecificMoves;
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
                possibleMoves.add(new Move(startSquare, targetSquare, "en-passant"));
            }

            // Opponent pawn on capture square check:
            else if ((Piece.color(targetPiece) != 0) && (!Piece.isColor(currentPawn, targetPiece))) {
                possibleMoves.add(new Move(startSquare, targetSquare));
            }
        }
    }

    // For knights:
    private void generateKnightMoves(int startSquare, int currentKnight) {
        // Offsets with their maximum allowed row shifts:
        Map<Integer, Integer> offsetsWithMaxRowMoves = new HashMap<>() {{
           put(-10, 1); put(-17, 2); put(-15, 2); put(-6, 1);
           put(10, 1); put(17, 2); put(15, 2); put(6, 1);
        }};

        // Adding the possible moves:
        offsetsWithMaxRowMoves.forEach((offset, maxRowMoves) -> {
            int targetSquare = startSquare + offset;

            if (Math.abs((startSquare / 8) - (targetSquare / 8)) <= maxRowMoves) {
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
        // Natural moves:
        for (int offset : DirectionOffsets) {
            int targetSquare = startSquare + offset;
            if (targetSquare < 0 || targetSquare > 63) continue;

            int targetPiece = board.getPiece(targetSquare);

            if (!Piece.isColor(currentKing, targetPiece)) {
                possibleMoves.add(new Move(startSquare, targetSquare));
            }
        }

        // For king side castling:
        int targetSquareKS = startSquare + 2;
        int targetPieceKS = board.getPiece(targetSquareKS);

        if (targetPieceKS == Piece.None) {
            possibleMoves.add(new Move(startSquare, targetSquareKS, "castle-k"));
        }

        // For queen side castling:
        int targetSquareQS = startSquare - 2;
        int targetPieceQS = board.getPiece(targetSquareQS);

        int QSRookOffsetSquare = startSquare - 3;  // The square beside the queen side rook (to check if there's a piece blocking the way)
        int QSRookOffsetPiece = board.getPiece(QSRookOffsetSquare);

        if (targetPieceQS == Piece.None && QSRookOffsetPiece == Piece.None) {
            possibleMoves.add(new Move(startSquare, QSRookOffsetSquare, "castle-q"));
        }
    }
}