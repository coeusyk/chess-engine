package coeusyk.game.chess.core.search;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Measurement harness for #287, off by default. Run with {@code -Dprobe.run=true} (optional
 * {@code -Dprobe.depth}, {@code -Dprobe.random}, {@code -Dprobe.contempt}, {@code -Dprobe.cycles},
 * {@code -Dprobe.hash} in MB, default 1).
 *
 * <p>For each position it builds a game history that repeats a four-ply quiet cycle once, then compares
 * a cold-table search of the position-with-history against one that follows a warm-up search of the same
 * position without history (and the reverse). Two control columns repeat the warm-up with the same
 * context, so a divergence that also shows up there is plain transposition-table warm-up noise and not a
 * repetition effect. It prints a table and asserts nothing. See
 * docs/engineering/investigations/2026-11-24-tt-repetition-history-contamination.md.
 */
class TtRepetitionContaminationProbe {
    static final String[] FENS = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "7k/R7/8/8/1B6/8/8/K5R1 w - - 0 1",
            "8/8/4k3/8/8/4K3/4P3/8 w - - 0 1",
            "6k1/5p2/6p1/8/7P/6P1/5P2/6K1 w - - 0 1",
            "3r2k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
            "8/8/8/3k4/8/8/3KQ3/8 w - - 0 1",
            "r4rk1/pp3ppp/2n1b3/8/8/2N1B3/PP3PPP/R4RK1 w - - 0 1",
            "4k3/6p1/8/8/8/8/1Q4PP/4K3 w - - 0 1",
            "2k5/8/2K5/8/8/8/8/1R6 b - - 0 1",
            "8/5k2/8/8/8/2B5/5K2/6N1 w - - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
            "8/8/8/8/8/5k2/6q1/5K2 w - - 0 1",
            "6k1/6p1/6P1/8/8/8/1R6/K7 w - - 0 1",
            "5rk1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1",
            "1k6/1p6/1P6/8/8/8/6R1/6K1 w - - 0 1",
    };

    static Searcher mk() {
        Searcher s = new Searcher();
        s.setContempt(Integer.getInteger("probe.contempt", 0));
        s.setTranspositionTableSizeMb(Integer.getInteger("probe.hash", 1));
        return s;
    }

    static List<Move> legal(Board b) {
        return new ArrayList<>(new MovesGenerator(b).getActiveMoves(b.getActiveColor()));
    }

    static Move find(Board b, int from, int to) {
        for (Move m : legal(b)) if (m.startSquare == from && m.targetSquare == to && m.reaction == null) return m;
        return null;
    }

    /** First four-ply quiet cycle m1 m2 back1 back2 that returns to the starting position. */
    static int[][] cycle(String fen) {
        Board b = new Board(fen);
        long hash = b.getZobristHash();
        for (Move m1 : legal(b)) {
            if (m1.reaction != null) continue;
            b.makeMove(m1);
            for (Move m2 : legal(b)) {
                if (m2.reaction != null) continue;
                b.makeMove(m2);
                Move r1 = find(b, m1.targetSquare, m1.startSquare);
                if (r1 != null) {
                    b.makeMove(r1);
                    Move r2 = find(b, m2.targetSquare, m2.startSquare);
                    if (r2 != null) {
                        b.makeMove(r2);
                        boolean back = b.getZobristHash() == hash;
                        b.unmakeMove();
                        if (back) {
                            b.unmakeMove(); b.unmakeMove(); b.unmakeMove();
                            return new int[][]{{m1.startSquare, m1.targetSquare}, {m2.startSquare, m2.targetSquare},
                                    {r1.startSquare, r1.targetSquare}, {r2.startSquare, r2.targetSquare}};
                        }
                    }
                    b.unmakeMove();
                }
                b.unmakeMove();
            }
            b.unmakeMove();
        }
        return null;
    }

    static Board withHistory(String fen, int[][] c, int cycles) {
        Board b = new Board(fen);
        for (int k = 0; k < cycles; k++) for (int[] mv : c) b.makeMove(find(b, mv[0], mv[1]));
        b.makeMove(find(b, c[0][0], c[0][1]));
        return b;
    }

    static String describe(SearchResult r) {
        Move m = r.bestMove();
        return (m == null ? "none" : m.startSquare + ">" + m.targetSquare) + "/" + r.scoreCp();
    }

    static boolean differs(SearchResult a, SearchResult b) {
        Move x = a.bestMove(), y = b.bestMove();
        boolean sameMove = x == null ? y == null : y != null && x.startSquare == y.startSquare && x.targetSquare == y.targetSquare;
        return !sameMove || a.scoreCp() != b.scoreCp();
    }

    @Test
    @EnabledIfSystemProperty(named = "probe.run", matches = "true")
    void probe() {
        int depth = Integer.getInteger("probe.depth", 9);
        int warmDepth = depth - 2;
        int cycles = Integer.getInteger("probe.cycles", 1);
        int treatment = 0, treatmentRev = 0, control = 0, controlHist = 0, n = 0;
        List<String> all = new ArrayList<>(List.of(FENS));
        java.util.Random rnd = new java.util.Random(287);
        int generated = Integer.getInteger("probe.random", 0);
        while (generated > 0) {
            Board g = new Board();
            int target = 20 + rnd.nextInt(100);
            boolean ok = true;
            for (int i = 0; i < target; i++) {
                List<Move> ms = legal(g);
                if (ms.isEmpty() || g.isThreefoldRepetition() || g.isFiftyMoveRuleDraw()) { ok = false; break; }
                g.makeMove(ms.get(rnd.nextInt(ms.size())));
            }
            if (!ok || g.isActiveColorInCheck() || legal(g).isEmpty() || g.isInsufficientMaterial()) continue;
            all.add(g.toFen());
            generated--;
        }
        boolean quiet = generated == 0 && Integer.getInteger("probe.random", 0) > 0;
        for (String fen : all) {
            int[][] c;
            try { c = cycle(fen); } catch (RuntimeException e) { System.out.println("BADFEN " + fen + " " + e.getMessage()); continue; }
            if (c == null) { System.out.println("NOCYCLE " + fen); continue; }
            n++;
            String rootFen = withHistory(fen, c, cycles).toFen();

            SearchResult coldHist = mk().iterativeDeepening(withHistory(fen, c, cycles), depth);
            SearchResult coldPlain = mk().iterativeDeepening(new Board(rootFen), depth);

            Searcher w1 = mk();
            w1.iterativeDeepening(new Board(rootFen), warmDepth);
            SearchResult warmHist = w1.iterativeDeepening(withHistory(fen, c, cycles), depth);

            Searcher w2 = mk();
            w2.iterativeDeepening(withHistory(fen, c, cycles), warmDepth);
            SearchResult warmPlain = w2.iterativeDeepening(new Board(rootFen), depth);

            Searcher w3 = mk();
            w3.iterativeDeepening(new Board(rootFen), warmDepth);
            SearchResult ctlPlain = w3.iterativeDeepening(new Board(rootFen), depth);

            Searcher w4 = mk();
            w4.iterativeDeepening(withHistory(fen, c, cycles), warmDepth);
            SearchResult ctlHist = w4.iterativeDeepening(withHistory(fen, c, cycles), depth);

            boolean t = differs(coldHist, warmHist), tr = differs(coldPlain, warmPlain),
                    cp = differs(coldPlain, ctlPlain), ch = differs(coldHist, ctlHist);
            if (t) treatment++; if (tr) treatmentRev++; if (cp) control++; if (ch) controlHist++;
            if (!quiet || t || tr || cp || ch) System.out.printf("%-70s coldHist=%s warmFromPlain=%s%s | coldPlain=%s warmFromHist=%s%s | ctlPlain=%s%s ctlHist=%s%s%n",
                    fen.length() > 68 ? fen.substring(0, 68) : fen,
                    describe(coldHist), describe(warmHist), t ? " DIFF" : "",
                    describe(coldPlain), describe(warmPlain), tr ? " DIFF" : "",
                    describe(ctlPlain), cp ? " DIFF" : "", describe(ctlHist), ch ? " DIFF" : "");
        }
        System.out.printf("SUMMARY depth=%d cycles=%d positions=%d  hist-after-plain-warm=%d  plain-after-hist-warm=%d  control-plain=%d  control-hist=%d%n",
                depth, cycles, n, treatment, treatmentRev, control, controlHist);
    }
}
