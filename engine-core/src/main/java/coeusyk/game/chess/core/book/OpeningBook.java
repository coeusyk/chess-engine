package coeusyk.game.chess.core.book;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reads a Polyglot (.bin) opening book and probes it for moves.
 *
 * Binary format: each entry is exactly 16 bytes, big-endian:
 *   uint64 key    — Zobrist hash of the position
 *   uint16 move   — encoded move
 *   uint16 weight — relative weight for move selection
 *   uint32 learn  — ignored
 *
 * Entries are sorted ascending by key, enabling binary search.
 *
 * Move encoding (Polyglot):
 *   bits  0-2  to-file
 *   bits  3-5  to-rank  (0=rank1, 7=rank8)
 *   bits  6-8  from-file
 *   bits  9-11 from-rank (0=rank1, 7=rank8)
 *   bits 12-14 promo-piece (0=none, 1=knight, 2=bishop, 3=rook, 4=queen)
 */
public final class OpeningBook {

    private static final int ENTRY_SIZE = 16;
    private static final long UNSIGNED_MASK = 0xFFFFFFFFL;

    /** variance ∈ [0,100]: 0 = always pick best, 100 = uniform random. */
    private final int variance;
    private final Random rng = new Random();

    private RandomAccessFile raf;
    private long entryCount;
    private boolean open;

    public OpeningBook(int variance) {
        this.variance = Math.max(0, Math.min(100, variance));
    }

    /**
     * Opens the book at the given path.
     *
     * @throws IOException if the file cannot be opened or has an invalid size.
     */
    public void open(Path path) throws IOException {
        close();
        raf = new RandomAccessFile(path.toFile(), "r");
        long length = raf.length();
        if (length % ENTRY_SIZE != 0) {
            raf.close();
            throw new IOException("Polyglot book has invalid file size: " + length);
        }
        entryCount = length / ENTRY_SIZE;
        open = true;
    }

    /** Attempts to open the book from a classpath resource (extracts to temp file). */
    public boolean openFromClasspath(String resourceName) {
        try {
            var stream = OpeningBook.class.getResourceAsStream(resourceName);
            if (stream == null) {
                stream = OpeningBook.class.getResourceAsStream("/" + resourceName);
            }
            if (stream == null) {
                return false;
            }
            java.io.File tmp = java.io.File.createTempFile("vex-book-", ".bin");
            tmp.deleteOnExit();
            try (var out = new java.io.FileOutputStream(tmp)) {
                stream.transferTo(out);
            }
            open(tmp.toPath());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Closes the book file. */
    public void close() {
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) {}
            raf = null;
        }
        open = false;
        entryCount = 0;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Probes the book for the given board position.
     *
     * @return a legal engine Move, or {@code null} if no book entry exists.
     */
    public Move probe(Board board) {
        if (!open || entryCount == 0) {
            return null;
        }

        long key = PolyglotKey.compute(board);
        long firstIdx = bisectKeyLeft(key);
        if (firstIdx >= entryCount) {
            return null;
        }

        // Collect all entries matching this key
        List<long[]> candidates = new ArrayList<>(); // [weight, polyMove]
        for (long i = firstIdx; i < entryCount; i++) {
            long[] entry = readEntry(i);
            if (entry == null || entry[0] != key) {
                break;
            }
            long weight = entry[1];
            long rawMove = entry[2];
            if (weight > 0) {
                candidates.add(new long[]{ weight, rawMove });
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // Select a candidate with weighted roulette according to variance setting
        long[] chosen = selectCandidate(candidates);
        if (chosen == null) {
            return null;
        }

        return decodeMove(board, (int) chosen[1]);
    }

    // ---- Private helpers ----

    private long[] selectCandidate(List<long[]> candidates) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        if (variance == 0) {
            // Always pick the highest-weight move
            long[] best = candidates.get(0);
            for (long[] c : candidates) {
                if (c[0] > best[0]) best = c;
            }
            return best;
        }

        // Adjust weights by variance: w' = w^(1 - variance/100)
        // variance=100 → w'=1 for all (uniform), variance=0 → w'=w (best-move)
        double exponent = 1.0 - variance / 100.0;
        double[] adjusted = new double[candidates.size()];
        double total = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            double w = Math.pow(candidates.get(i)[0], exponent);
            adjusted[i] = w;
            total += w;
        }
        if (total <= 0.0) {
            return candidates.get(0);
        }

        double r = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += adjusted[i];
            if (r <= cumulative) {
                return candidates.get(i);
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    /** Returns {key, weight, rawMove} or null on IO error. */
    private long[] readEntry(long index) {
        try {
            raf.seek(index * ENTRY_SIZE);
            long key    = raf.readLong();
            int rawMove = readUShort();
            int weight  = readUShort();
            // learn (4 bytes) ignored
            raf.skipBytes(4);
            return new long[]{ key, weight, rawMove };
        } catch (IOException e) {
            return null;
        }
    }

    private int readUShort() throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        return ((b0 & 0xFF) << 8) | (b1 & 0xFF);
    }

    /** Binary search: returns the lowest index i such that entry[i].key >= targetKey. */
    private long bisectKeyLeft(long targetKey) {
        long lo = 0, hi = entryCount;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            try {
                raf.seek(mid * ENTRY_SIZE);
                long midKey = raf.readLong();
                // Compare as unsigned longs
                if (Long.compareUnsigned(midKey, targetKey) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            } catch (IOException e) {
                return entryCount;
            }
        }
        return lo;
    }

    /**
     * Decodes a Polyglot raw move integer and matches it to a legal engine Move.
     * Returns null if no matching legal move is found.
     */
    private Move decodeMove(Board board, int rawMove) {
        int toFile   =  rawMove        & 0x7;
        int toRank   = (rawMove >> 3)  & 0x7; // 0=rank1
        int fromFile = (rawMove >> 6)  & 0x7;
        int fromRank = (rawMove >> 9)  & 0x7; // 0=rank1
        int promoIdx = (rawMove >> 12) & 0x7; // 0=none, 1=knight, 2=bishop, 3=rook, 4=queen

        // Convert Polyglot squares (rank 0=rank1) to engine squares (rank_idx 0=rank8)
        int engineFrom = (7 - fromRank) * 8 + fromFile;
        int engineTo   = (7 - toRank)   * 8 + toFile;

        String promoReaction = promoReaction(promoIdx);

        List<Move> legal = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move m : legal) {
            if (m.startSquare != engineFrom || m.targetSquare != engineTo) {
                continue;
            }
            if (promoReaction == null) {
                // Accept any non-promotion legal move; also accept castling
                if (m.reaction == null
                        || m.reaction.startsWith("castle")
                        || m.reaction.startsWith("ep")
                        || "en-passant".equals(m.reaction)) {
                    return m;
                }
            } else {
                if (promoReaction.equals(m.reaction)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String promoReaction(int promoIdx) {
        return switch (promoIdx) {
            case 1 -> "promote-n";
            case 2 -> "promote-b";
            case 3 -> "promote-r";
            case 4 -> "promote-q";
            default -> null;
        };
    }
}
