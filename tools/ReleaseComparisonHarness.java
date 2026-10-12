import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fixed-depth release-to-release comparison driver. Adapted from Phase21ControlHarness so the same
 * source runs unchanged against v0.5.7 and v0.6.0. The FEN list comes from a file (the two releases
 * ship different BenchRunner corpora), and the instrumentation toggle that only exists from v0.6.0
 * is called by reflection, switched off.
 *
 * Usage: ReleaseComparisonHarness corpus.txt depth hashMb [warmupDepth]
 * Output (stdout, TSV): index depth move score nodes qnodes tt_hits elapsed_ns pv
 *
 * With warmupDepth > 0 the whole corpus is first searched once at that depth, untimed and discarded,
 * in the same JVM, so the timed pass does not include cold-JIT cost. Every search, warm-up or timed,
 * uses a fresh Searcher and an empty transposition table, so node counts do not depend on the warm-up.
 */
public class ReleaseComparisonHarness {
    public static void main(String[] args) throws Exception {
        List<String> fens = Files.readAllLines(Path.of(args[0])).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        int depth = Integer.parseInt(args[1]);
        int hashMb = Integer.parseInt(args[2]);
        int warmupDepth = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        if (warmupDepth > 0) {
            for (String fen : fens) {
                Searcher searcher = newSearcher(hashMb);
                Board board = new Board(fen);
                board.setSearchMode(true);
                searcher.searchDepth(board, warmupDepth);
            }
        }

        Searcher first = newSearcher(hashMb);
        // The runner checks this line, so a changed evaluator or setting cannot go unnoticed.
        java.lang.reflect.Field evaluator = Searcher.class.getDeclaredField("evaluator");
        evaluator.setAccessible(true);
        System.err.println("CONFIG depth=" + depth + " hash_mb=" + hashMb + " contempt=0 threads=1 evaluator="
                + evaluator.get(first).getClass().getSimpleName() + " instrumentation=" + instrumentation()
                + " positions=" + fens.size() + " warmup_depth=" + warmupDepth);

        System.out.println("index\tdepth\tmove\tscore\tnodes\tqnodes\ttt_hits\telapsed_ns\tpv");
        long totalNodes = 0, totalNanos = 0;
        for (int i = 0; i < fens.size(); i++) {
            Searcher searcher = i == 0 ? first : newSearcher(hashMb);
            Board board = new Board(fens.get(i));
            board.setSearchMode(true);
            long start = System.nanoTime();
            SearchResult result = searcher.searchDepth(board, depth);
            long elapsed = System.nanoTime() - start;
            if (result.aborted() || result.depthReached() != depth) throw new AssertionError("Incomplete search at " + i);
            assertLegal(board, result.principalVariation(), i);
            totalNodes += result.nodesVisited();
            totalNanos += elapsed;
            System.out.println(i + "\t" + depth + "\t" + uci(result.bestMove()) + "\t" + result.scoreCp()
                    + "\t" + result.nodesVisited() + "\t" + result.quiescenceNodes() + "\t" + result.ttHits()
                    + "\t" + elapsed + "\t"
                    + result.principalVariation().stream().map(ReleaseComparisonHarness::uci).collect(Collectors.joining(" ")));
        }
        System.err.println("TOTAL nodes=" + totalNodes + " elapsed_ms=" + totalNanos / 1_000_000
                + " nps=" + (totalNanos > 0 ? totalNodes * 1_000_000_000L / totalNanos : 0));
    }

    private static Searcher newSearcher(int hashMb) throws Exception {
        Searcher searcher = new Searcher();
        searcher.setTranspositionTableSizeMb(hashMb);
        searcher.setContempt(0);
        try {
            Searcher.class.getMethod("setInstrumentationEnabled", boolean.class).invoke(searcher, false);
        } catch (NoSuchMethodException absent) {
            // v0.5.7 has no instrumentation switch
        }
        return searcher;
    }

    private static String instrumentation() {
        try {
            Searcher.class.getMethod("setInstrumentationEnabled", boolean.class);
            return "off";
        } catch (NoSuchMethodException absent) {
            return "absent";
        }
    }

    private static void assertLegal(Board board, List<Move> pv, int index) {
        for (Move move : pv) {
            boolean legal = new MovesGenerator(board).getAllMoves().stream().anyMatch(m -> m.pack() == move.pack());
            if (!legal) throw new AssertionError("Illegal PV move at index " + index);
            board.makeMove(move);
        }
    }

    private static String uci(Move move) {
        String suffix = move.reaction != null && move.reaction.startsWith("promote-") ? move.reaction.substring(8) : "";
        return "" + (char) ('a' + move.startSquare % 8) + (8 - move.startSquare / 8)
                + (char) ('a' + move.targetSquare % 8) + (8 - move.targetSquare / 8) + suffix;
    }
}
