package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.book.PolyglotKey;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * E-16 (#224, `DR-E16-shared-opening-prefix-preregistration.md` section 2): one-time,
 * deterministic extraction of the shared-opening pool from the engine's existing production
 * Polyglot book ({@code engine-uci/src/main/resources/books/Performance.bin}) -- not the
 * self-labeled "random-legal" {@code bench/nnue-corpus/opening.epd}. Excluded from the standard
 * test suite, same convention as {@link coeusyk.game.chess.core.eval.nnue.NnueCorpusGenerator}
 * (issue #188): the generator must be re-runnable on demand, but its output is committed once and
 * never regenerated automatically. Run manually with:
 * <pre>
 *   mvn -pl engine-core test -Dgroups=corpus-generation -Dtest=E16OpeningPoolGenerator -Dcorpus.generate=true
 * </pre>
 *
 * <p>Algorithm, exactly as preregistered: starting from the initial position, exhaustively expand
 * every {@code weight > 0} book entry at each reachable position (not a single weighted probe) to
 * a fixed depth of 6 plies (3 full moves per side), discarding any branch that runs out of book
 * before that depth. At each node, candidates are ordered by weight descending (ties broken by
 * the raw Polyglot move integer ascending, for a total order independent of file iteration order)
 * before expansion -- a well-defined canonicalization, not incidental file order. Deduplicates
 * transpositions by terminal FEN, keeping first-visitation order. Reuses the exact
 * {@code Board}/{@code MovesGenerator}/{@code PolyglotKey} legality/decode logic
 * {@link coeusyk.game.chess.core.book.OpeningBook} already uses -- no new decoder, no production
 * code touched by this generator.
 */
@Tag("corpus-generation")
class E16OpeningPoolGenerator {

    private static final int ENTRY_SIZE = 16;
    private static final int DEPTH = 6;

    private static final Path BOOK_PATH =
            Path.of("..", "engine-uci", "src", "main", "resources", "books", "Performance.bin");
    private static final Path OUTPUT_PATH = Path.of("..", "bench", "nnue-corpus", "e16-shared-opening-pool.txt");

    @Test
    void generatePool() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("corpus.generate"),
                "E-16 opening-pool generation only runs when explicitly requested via -Dcorpus.generate=true");

        Map<Long, List<long[]>> byKey = readBook(BOOK_PATH);

        Set<String> uniqueLeaves = new LinkedHashSet<>();
        int deadEnds = 0;
        int expandedEdges = 0;
        int leavesTotal = 0;

        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(new Board(), 0));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            if (frame.depth == DEPTH) {
                uniqueLeaves.add(frame.board.toFen());
                leavesTotal++;
                continue;
            }
            long key = PolyglotKey.compute(frame.board);
            List<long[]> candidates = byKey.get(key);
            if (candidates == null || candidates.isEmpty()) {
                deadEnds++;
                continue;
            }
            // Weight descending, then raw move ascending -- a total order independent of
            // whatever incidental order the book file itself stores same-key entries in.
            List<long[]> ordered = new ArrayList<>(candidates);
            ordered.sort(Comparator.<long[]>comparingLong(c -> -c[0]).thenComparingLong(c -> c[1]));
            // Push in reverse so the highest-weight candidate is popped (visited) first --
            // this is a stack, LIFO.
            for (int i = ordered.size() - 1; i >= 0; i--) {
                Move move = decodeMove(frame.board, (int) ordered.get(i)[1]);
                if (move == null || !isLegal(frame.board, move)) {
                    continue;
                }
                Board child = new Board(frame.board.toFen());
                child.makeMove(move);
                stack.push(new Frame(child, frame.depth + 1));
                expandedEdges++;
            }
        }

        List<String> lines = new ArrayList<>(uniqueLeaves);
        Files.write(OUTPUT_PATH, lines);

        System.out.println("E-16 opening pool: keys=" + byKey.size() + " expanded_edges=" + expandedEdges
                + " dead_ends=" + deadEnds + " leaves_total=" + leavesTotal
                + " unique_leaves=" + uniqueLeaves.size() + " -> " + OUTPUT_PATH);
    }

    private record Frame(Board board, int depth) {
    }

    private static Map<Long, List<long[]>> readBook(Path path) throws IOException {
        // TreeMap for a deterministic key-iteration order (not load-bearing for the DFS itself,
        // which visits by board reachability, not by map iteration -- but keeps this method's
        // own behavior independent of HashMap's unspecified bucket order, for good measure).
        Map<Long, List<long[]>> byKey = new TreeMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long entryCount = raf.length() / ENTRY_SIZE;
            for (long i = 0; i < entryCount; i++) {
                raf.seek(i * ENTRY_SIZE);
                long key = raf.readLong();
                int rawMove = readUShort(raf);
                int weight = readUShort(raf);
                raf.skipBytes(4); // learn field, ignored -- same as OpeningBook.readEntry()
                if (weight > 0) {
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new long[] {weight, rawMove});
                }
            }
        }
        return byKey;
    }

    private static int readUShort(RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        return ((b0 & 0xFF) << 8) | (b1 & 0xFF);
    }

    private static boolean isLegal(Board board, Move move) {
        int[] legal = new int[256];
        int count = MovesGenerator.generate(board, legal);
        int packed = move.pack();
        for (int i = 0; i < count; i++) {
            if (legal[i] == packed) {
                return true;
            }
        }
        return false;
    }

    /** Mirrors {@code OpeningBook.decodeMove()} exactly (that method is private) -- same Polyglot
     * move-integer layout, same promotion mapping, same legal-move matching by from/to/reaction. */
    private static Move decodeMove(Board board, int rawMove) {
        int toFile = rawMove & 0x7;
        int toRank = (rawMove >> 3) & 0x7;
        int fromFile = (rawMove >> 6) & 0x7;
        int fromRank = (rawMove >> 9) & 0x7;
        int promoIdx = (rawMove >> 12) & 0x7;
        int engineFrom = (7 - fromRank) * 8 + fromFile;
        int engineTo = (7 - toRank) * 8 + toFile;
        String promoReaction = switch (promoIdx) {
            case 1 -> "promote-n";
            case 2 -> "promote-b";
            case 3 -> "promote-r";
            case 4 -> "promote-q";
            default -> null;
        };

        List<Move> legal = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move m : legal) {
            if (m.startSquare != engineFrom || m.targetSquare != engineTo) {
                continue;
            }
            if (promoReaction == null) {
                if (m.reaction == null || m.reaction.startsWith("castle") || m.reaction.startsWith("ep")
                        || "en-passant".equals(m.reaction)) {
                    return m;
                }
            } else if (promoReaction.equals(m.reaction)) {
                return m;
            }
        }
        return null;
    }
}
