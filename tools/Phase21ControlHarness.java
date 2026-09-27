import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import java.lang.reflect.Field;
import java.util.stream.Collectors;

/** Deterministic Phase 21 controls; no clock-based search or timing conclusions. */
public class Phase21ControlHarness {
    public static void main(String[] args) throws Exception {
        boolean instrumentation = Boolean.parseBoolean(args[0]);
        Field corpus = Class.forName("coeusyk.game.chess.uci.BenchRunner").getDeclaredField("BENCH_FENS");
        corpus.setAccessible(true);
        String[] fens = (String[]) corpus.get(null);
        if (fens.length != 31) throw new AssertionError("Canonical corpus changed");
        System.out.println("index\tdepth\tmove\tscore\tnodes\tqnodes\ttt_hits\tpv");
        long total = 0;
        for (int i = 0; i < fens.length; i++) total += search(fens[i], i, 13, instrumentation).nodesVisited();
        if (total != 24_780_049L) throw new AssertionError("Depth-13 total: " + total);
        int[] indexes = {0, 2, 6, 13, 20};
        String[] expected = {
            "e2e4\t25\t14926\t39927\t4908\te2e4 e7e5 b1c3 b8c6 g1f3",
            "e1d2\t122\t1226\t1816\t1024\te1d2 e8d7 e2e4 d7d6 d2d3 d6e5 d3e3 e5e6",
            "b4b2\t-63\t34694\t88266\t14691\tb4b2 e3d2 b2b6 d3d4 e6c4 f1b1 b6a5 f3e5 c4b5",
            "b4f4\t14\t6456\t14776\t1938\tb4f4 h4g3 f4c4 h5c5 a5b4 c5c4 b4c4 g3g2 c4d3 g2f2",
            "d7d2\t1565\t8902\t16736\t3982\td7d2 c4d5 a8a3 a1b1 c5c4 f1d1 d2c3 f2f4"
        };
        for (int i = 0; i < indexes.length; i++) {
            SearchResult result = search(fens[indexes[i]], indexes[i], 8, instrumentation);
            if (!semantic(result).equals(expected[i])) throw new AssertionError("P18-5 row " + indexes[i]);
        }
        System.err.println("CONTROL PASS total=" + total + " reference_rows=5 instrumentation=" + instrumentation);
    }

    private static SearchResult search(String fen, int index, int depth, boolean instrumentation) {
        System.err.println("CASE index=" + index + " depth=" + depth);
        Searcher searcher = new Searcher();
        searcher.setTranspositionTableSizeMb(16);
        searcher.setInstrumentationEnabled(instrumentation);
        Board board = new Board(fen);
        board.setSearchMode(true);
        SearchResult result = searcher.searchDepth(board, depth);
        if (result.aborted() || result.depthReached() != depth) throw new AssertionError("Incomplete control");
        System.out.println(index + "\t" + depth + "\t" + semantic(result));
        return result;
    }

    private static String semantic(SearchResult result) {
        String pv = result.principalVariation().stream().map(Phase21ControlHarness::uci).collect(Collectors.joining(" "));
        return uci(result.bestMove()) + "\t" + result.scoreCp() + "\t" + result.nodesVisited()
                + "\t" + result.quiescenceNodes() + "\t" + result.ttHits() + "\t" + pv;
    }

    private static String uci(Move move) {
        String suffix = move.reaction != null && move.reaction.startsWith("promote-")
                ? move.reaction.substring(8) : "";
        return "" + (char) ('a' + move.startSquare % 8) + (8 - move.startSquare / 8)
                + (char) ('a' + move.targetSquare % 8) + (8 - move.targetSquare / 8) + suffix;
    }
}
