import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import java.lang.reflect.Field;
import java.util.stream.Collectors;

/** Cold, fixed-depth Phase 21 decision trace for one canonical BENCH position. */
public final class Phase21DecisionTrace {
    public static void main(String[] args) throws Exception {
        Field corpus = Class.forName("coeusyk.game.chess.uci.BenchRunner").getDeclaredField("BENCH_FENS");
        corpus.setAccessible(true);
        String[] fens = (String[]) corpus.get(null);
        if (fens.length != 31) throw new AssertionError("Canonical corpus changed");

        if (args[0].equals("validate-pv")) {
            int index = Integer.parseInt(args[1]);
            if (index < 0 || index >= 31 || args.length != 3) {
                throw new IllegalArgumentException("Expected validate-pv, BENCH index 0..30, and a UCI PV");
            }
            int moves = assertUciPvIsLegal(new Board(fens[index]), args[2], index);
            System.out.printf("%d\tlegal_moves=%d%n", index, moves);
            return;
        }

        int index = Integer.parseInt(args[0]);
        int depth = Integer.parseInt(args[1]);
        if (index < 0 || index >= 31 || depth < 13 || depth > 15) {
            throw new IllegalArgumentException("Expected BENCH index 0..30 and depth 13..15");
        }

        Searcher searcher = new Searcher();
        searcher.setTranspositionTableSizeMb(16);
        searcher.setInstrumentationEnabled(false);
        Board board = new Board(fens[index]);
        board.setSearchMode(true);
        SearchResult result = searcher.searchDepth(board, depth);
        if (result.aborted() || result.depthReached() != depth || result.bestMove() == null) {
            throw new AssertionError("Incomplete search index=" + index + " depth=" + depth);
        }
        assertPrincipalVariationIsLegal(board, result.principalVariation(), index, depth);

        String pv = result.principalVariation().stream()
                .map(Phase21DecisionTrace::uci)
                .collect(Collectors.joining(" "));
        System.out.printf("%d\t%d\t%s\t%d\t%d\t%d\t%d\t%s%n",
                index, depth, uci(result.bestMove()), result.scoreCp(), result.nodesVisited(),
                result.quiescenceNodes(), result.ttHits(), pv);
    }

    private static void assertPrincipalVariationIsLegal(Board board, java.util.List<Move> pv, int index, int depth) {
        for (Move move : pv) {
            boolean legal = new MovesGenerator(board).getAllMoves().stream()
                    .anyMatch(candidate -> candidate.pack() == move.pack());
            if (!legal) throw new AssertionError("Illegal PV move at index=" + index + " depth=" + depth);
            board.makeMove(move);
        }
    }

    private static int assertUciPvIsLegal(Board board, String pv, int index) {
        if (pv.isBlank()) throw new AssertionError("Empty PV at index=" + index);
        String[] moves = pv.split(" ");
        for (String text : moves) {
            Move move = new MovesGenerator(board).getAllMoves().stream()
                    .filter(candidate -> uci(candidate).equals(text))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Illegal PV move at index=" + index + ": " + text));
            board.makeMove(move);
        }
        return moves.length;
    }

    private static String uci(Move move) {
        String suffix = move.reaction != null && move.reaction.startsWith("promote-")
                ? move.reaction.substring(8) : "";
        return "" + (char) ('a' + move.startSquare % 8) + (8 - move.startSquare / 8)
                + (char) ('a' + move.targetSquare % 8) + (8 - move.targetSquare / 8) + suffix;
    }
}
