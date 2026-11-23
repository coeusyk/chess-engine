import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.models.Piece;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.OldStaticExchangeEvaluator;
import coeusyk.game.chess.core.search.StaticExchangeEvaluator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Counts capture decisions that differ between the pre-fix SEE (52fee7c, compiled as
 * OldStaticExchangeEvaluator) and the candidate SEE, over the WAC, draw_failures and BENCH corpora.
 *
 * Setup: extract the pre-fix source with `git show 52fee7c:<path>/StaticExchangeEvaluator.java`, rename the class
 * to OldStaticExchangeEvaluator, and compile it together with this file against engine-core and engine-uci classes.
 *
 * A "decision" is the SEE &lt; 0 test the Searcher uses to prune a capture in quiescence and to
 * rank it as losing in main-search ordering. Every legal capture in every node of a full-width
 * tree of the given depth below each corpus position is one decision.
 *
 * Usage: SeeDecisionDiff &lt;plies&gt; &lt;bench|file.epd&gt;...  (bench = the 31 BenchRunner positions)
 */
public final class SeeDecisionDiff {
    static final OldStaticExchangeEvaluator OLD = new OldStaticExchangeEvaluator();
    static final StaticExchangeEvaluator NEW = new StaticExchangeEvaluator();
    static long captures, valueDiff, prunedToSearched, searchedToPruned;
    static long[] capturesByPly, diffByPly;
    static String firstDiffs = "";
    static int shown;

    public static void main(String[] args) throws Exception {
        int plies = Integer.parseInt(args[0]);
        capturesByPly = new long[plies + 1];
        diffByPly = new long[plies + 1];
        int positions = 0;
        java.util.List<String> fens = new java.util.ArrayList<>();
        for (int a = 1; a < args.length; a++) {
            if (args[a].equals("bench")) {
                java.lang.reflect.Field f = Class.forName("coeusyk.game.chess.uci.BenchRunner").getDeclaredField("BENCH_FENS");
                f.setAccessible(true);
                for (String b : (String[]) f.get(null)) fens.add(b);
                continue;
            }
            for (String line : Files.readAllLines(Path.of(args[a]))) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                if (t.length < 5) continue;
                fens.add(t[0] + " " + t[1] + " " + t[2] + " " + t[3] + " 0 1");
            }
        }
        for (String fen : fens) {
            positions++;
            walk(new Board(fen), 0, plies, fen);
        }
        System.out.printf("positions=%d plies=%d%n", positions, plies);
        System.out.printf("capture_decisions=%d%n", captures);
        System.out.printf("see_value_differs=%d%n", valueDiff);
        System.out.printf("pruned_to_searched(old<0,new>=0)=%d%n", prunedToSearched);
        System.out.printf("searched_to_pruned(old>=0,new<0)=%d%n", searchedToPruned);
        for (int p = 0; p <= plies; p++) {
            System.out.printf("ply%d captures=%d value_diffs=%d%n", p, capturesByPly[p], diffByPly[p]);
        }
        System.out.print(firstDiffs);
    }

    static void walk(Board board, int ply, int maxPly, String root) {
        List<Move> moves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move m : moves) {
            boolean ep = "en-passant".equals(m.reaction);
            if (!ep && board.getPiece(m.targetSquare) == Piece.None) continue;
            int o = OLD.evaluate(board, m);
            int n = NEW.evaluate(board, m);
            captures++;
            capturesByPly[ply]++;
            if (o != n) {
                valueDiff++;
                diffByPly[ply]++;
                if (o < 0 && n >= 0) prunedToSearched++;
                if (o >= 0 && n < 0) searchedToPruned++;
                if (shown++ < 12) {
                    firstDiffs += String.format("diff root=[%s] ply=%d move=%d->%d old=%d new=%d%n",
                            root, ply, m.startSquare, m.targetSquare, o, n);
                }
            }
        }
        if (ply == maxPly) return;
        for (Move m : moves) {
            board.makeMove(m);
            walk(board, ply + 1, maxPly, root);
            board.unmakeMove();
        }
    }
}
