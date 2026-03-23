package coeusyk.game.chess.core.movegen;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoardStateDeterminismTest {

    @Test
    void longMakeUnmakeSequenceRestoresExactBoardStateAndHash() {
        Board board = new Board();
        String initialSnapshot = snapshot(board);

        List<Move> playedMoves = new ArrayList<>();

        for (int ply = 0; ply < 80; ply++) {
            MovesGenerator movesGenerator = new MovesGenerator(board);
            ArrayList<Move> legalMoves = movesGenerator.getActiveMoves(board.getActiveColor());

            int selectedIndex = (ply * 7 + 3) % legalMoves.size();
            Move selected = legalMoves.get(selectedIndex);
            playedMoves.add(new Move(selected.startSquare, selected.targetSquare, selected.reaction));

            board.makeMove(selected);
        }

        for (int i = playedMoves.size() - 1; i >= 0; i--) {
            board.unmakeMove();
        }

        assertEquals(initialSnapshot, snapshot(board));
    }

    @Test
    void immediateMakeUnmakeKeepsStateDeterministicAcrossLegalMoves() {
        Board board = new Board("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        MovesGenerator movesGenerator = new MovesGenerator(board);
        ArrayList<Move> legalMoves = movesGenerator.getActiveMoves(board.getActiveColor());

        String baseline = snapshot(board);

        for (Move move : legalMoves) {
            board.makeMove(move);
            board.unmakeMove();
            assertEquals(baseline, snapshot(board));
        }
    }

    private String snapshot(Board board) {
        StringBuilder sb = new StringBuilder();
        int[] grid = board.getGrid();
        for (int piece : grid) {
            sb.append(piece).append(',');
        }
        sb.append("ac=").append(board.getActiveColor()).append(';');
        sb.append("ep=").append(board.getEpTargetSquare()).append(';');
        sb.append("hm=").append(board.getHalfmoveClock()).append(';');
        sb.append("fm=").append(board.getFullMoves()).append(';');
        boolean[] rights = board.getCastlingAvailability();
        sb.append("ca=").append(rights[0]).append(rights[1]).append(rights[2]).append(rights[3]).append(';');
        sb.append("zh=").append(board.getZobristHash());
        return sb.toString();
    }
}
