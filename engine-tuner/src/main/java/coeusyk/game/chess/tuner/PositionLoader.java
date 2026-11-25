package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Parses position+outcome datasets in three formats:
 *
 * Format 1 — FEN with bracketed result:
 *   rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1 [1.0]
 *
 * Format 2 — EPD with c9 result annotation:
 *   rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2 c9 "1-0";
 *
 * Format 3 — EPD with c0 result annotation (quiet-labeled.epd / Ethereal format):
 *   rnbqkbnr/pp1ppppp/8/2p5/4P3/8/PPPP1PPP/RNBQKBNR w KQkq c6 0 2 c0 "1/2-1/2";
 *
 * Lines that cannot be parsed are silently skipped.
 */
public final class PositionLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PositionLoader.class);

    private PositionLoader() {}

    /**
     * Loads all positions from the file.
     */
    public static List<LabelledPosition> load(Path file) throws IOException {
        return load(file, Integer.MAX_VALUE);
    }

    /**
     * Loads up to {@code maxPositions} positions from the file.
     * Stops reading as soon as the cap is reached, avoiding OOM on large corpora.
     *
     * @param file         dataset path
     * @param maxPositions maximum number of positions to load
     * @return parsed positions (size ≤ maxPositions)
     */
    public static List<LabelledPosition> load(Path file, int maxPositions) throws IOException {
        List<LabelledPosition> result = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                LabelledPosition lp = tryParse(line);
                if (lp != null) {
                    result.add(lp);
                    if (result.size() >= maxPositions) break;
                } else {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            LOG.info(String.format("[PositionLoader] Skipped %,d unparseable lines", skipped));
        }
        return result;
    }

    private static LabelledPosition tryParse(String line) {
        try {
            // Format 1: ends with [1.0], [0.5], or [0.0]
            if (line.contains("[")) {
                return parseFormat1(line);
            }
            // Format 2: contains c9 keyword and ends with ;
            if (line.contains("c9")) {
                return parseFormat2(line, "c9");
            }
            // Format 3: contains c0 keyword (quiet-labeled.epd / Ethereal)
            if (line.contains("c0")) {
                return parseFormat2(line, "c0");
            }
        } catch (Exception ignored) {
            // Skip unparseable lines
        }
        return null;
    }

    private static LabelledPosition parseFormat1(String line) {
        int bracketOpen  = line.lastIndexOf('[');
        int bracketClose = line.lastIndexOf(']');
        if (bracketOpen < 0 || bracketClose < bracketOpen) return null;

        String resultStr = line.substring(bracketOpen + 1, bracketClose).strip();
        double outcome   = parseOutcome(resultStr);
        if (outcome < 0) return null;

        String fenPart = line.substring(0, bracketOpen).strip();
        TunerPosition pos = parseFen(fenPart);
        if (pos == null) return null;

        return new LabelledPosition(pos, outcome);
    }

    private static LabelledPosition parseFormat2(String line, String marker) {
        // Strip trailing semicolon
        String stripped = line.endsWith(";") ? line.substring(0, line.length() - 1).strip() : line;

        int markerIdx = stripped.indexOf(marker);
        if (markerIdx < 0) return null;

        String fenPart    = stripped.substring(0, markerIdx).strip();
        String resultPart = stripped.substring(markerIdx + 2).strip();

        // Result is quoted: "1-0", "0-1", "1/2-1/2"
        resultPart = resultPart.replace("\"", "").strip();
        double outcome = parseOutcome(resultPart);
        if (outcome < 0) return null;

        TunerPosition pos = parseFen(fenPart);
        if (pos == null) return null;

        return new LabelledPosition(pos, outcome);
    }

    /**
     * Converts FEN or EPD position to a compact {@link TunerPosition}.
     * Temporarily creates a full Board to extract bitboards, then discards it.
     * Appends "0 1" if halfmove/fullmove counters are absent (EPD format).
     */
    private static TunerPosition parseFen(String fen) {
        String[] parts = fen.split("\\s+");
        // EPD has 4 mandatory fields (position, color, castling, ep); FEN has 6
        String fullFen = parts.length >= 6 ? fen : fen + " 0 1";
        try {
            Board board = new Board(fullFen);
            return TunerPosition.from(board, fullFen);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Loads up to {@code maxPositions} labelled positions from a Stockfish-annotated
     * Texel corpus CSV generated by {@code tools/generate_texel_corpus.ps1}.
     *
     * <p>Expected CSV format (header row required):
     * <pre>
     *   fen,sf_cp,game_result
     *   "rnbqkbnr/... w KQkq -",47,0.5
     * </pre>
     *
     * <p>The {@code sf_cp} column is a raw Stockfish centipawn evaluation from White's
     * perspective. It is converted in-place to a pseudo-WDL outcome via
     * {@code sigmoid(sf_cp / K_SF)} where {@code K_SF = 340.0} (Stockfish
     * NormalizeToPawnValue=328 + 3.6% Vex-scale correction). The {@code game_result}
     * column is present in the file but not used by the tuner. Lines with unparseable
     * FENs or cp values are silently skipped.
     *
     * @param csvPath      path to the CSV file
     * @param maxPositions maximum number of positions to load
     * @return parsed positions (size &le; maxPositions)
     * @throws IOException if the file cannot be read
     */
    public static List<LabelledPosition> loadCsv(Path csvPath, int maxPositions) throws IOException {
        List<LabelledPosition> result  = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // Skip header row (starts with "fen" after stripping optional BOM/quotes)
                if (firstLine) {
                    firstLine = false;
                    if (line.replaceAll("^\"|\"$", "").toLowerCase().startsWith("fen")) continue;
                }
                LabelledPosition lp = tryParseCsvLine(line);
                if (lp != null) {
                    result.add(lp);
                    if (result.size() >= maxPositions) break;
                } else {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            LOG.info(String.format("[PositionLoader] CSV: skipped %,d unparseable lines", skipped));
        }
        return result;
    }

    /**
     * K scaling factor for Stockfish cp → pseudo-WDL conversion.
     * Derived from Stockfish NormalizeToPawnValue=328 with a 3.6% empirical
     * correction for Vex's centipawn scale. Do not change without a K-fit audit.
     */
    private static final double K_SF = 340.0;

    /**
     * Parses one CSV row: {@code "fen",sf_cp,game_result}
     * Converts the raw Stockfish centipawn value to a pseudo-WDL outcome via
     * {@code sigmoid(sf_cp / K_SF)}. Returns {@code null} if the row is malformed.
     */
    private static LabelledPosition tryParseCsvLine(String line) {
        try {
            // Handle optional quoted FEN field: "fen content",47,1.0
            String fenPart;
            String rest;
            if (line.startsWith("\"")) {
                int closeQuote = line.indexOf('"', 1);
                if (closeQuote < 0) return null;
                fenPart = line.substring(1, closeQuote);
                rest    = line.substring(closeQuote + 1).stripLeading();
                if (rest.startsWith(",")) rest = rest.substring(1);
            } else {
                int comma = line.indexOf(',');
                if (comma < 0) return null;
                fenPart = line.substring(0, comma).strip();
                rest    = line.substring(comma + 1);
            }
            // Next field: sf_cp (raw Stockfish centipawn evaluation)
            int comma2 = rest.indexOf(',');
            String cpStr = comma2 >= 0 ? rest.substring(0, comma2).strip() : rest.strip();
            double sfCp = Double.parseDouble(cpStr);
            if (!Double.isFinite(sfCp)) return null;
            // Convert to pseudo-WDL via sigmoid(sf_cp / K_SF)
            double pseudoWdl = 1.0 / (1.0 + Math.exp(-sfCp / K_SF));

            TunerPosition pos = parseFen(fenPart);
            if (pos == null) return null;
            return new LabelledPosition(pos, pseudoWdl);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Loads up to {@code maxPositions} labelled positions from a quiet-labeled.epd-compatible
     * annotated EPD file (Issue #140).
     *
     * <p>Supported annotation keywords: {@code c0} (Ethereal/quiet-labeled.epd), {@code c9},
     * and bracketed format {@code [result]}.
     *
     * <p>Two filters are applied:
     * <ol>
     *   <li>Positions where the active side is in check are skipped.</li>
     *   <li>Positions with material count &gt; 32 are skipped (opening-book noise filter).</li>
     * </ol>
     *
     * @param file         path to the annotated EPD file
     * @param maxPositions maximum number of positions to load
     * @return parsed positions (size &le; maxPositions)
     * @throws IOException if the file cannot be read
     */
    public static List<LabelledPosition> loadEpd(Path file, int maxPositions) throws IOException {
        List<LabelledPosition> result = new ArrayList<>();
        int skipped = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;

                LabelledPosition lp = tryParseEpdLine(line);
                if (lp != null) {
                    result.add(lp);
                    if (result.size() >= maxPositions) break;
                } else {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            LOG.info(String.format("[PositionLoader] EPD: skipped %,d filtered/unparseable lines", skipped));
        }
        return result;
    }

    /**
     * Parses a single annotated EPD line with in-check and material-count filters.
     * Handles c0 (Ethereal), c9, and bracketed result formats.
     * Returns {@code null} if the line is unparseable or filtered out.
     */
    private static LabelledPosition tryParseEpdLine(String line) {
        try {
            String fenPart;
            double outcome;

            if (line.contains("[")) {
                // Bracketed format: <FEN> [result]
                int open  = line.lastIndexOf('[');
                int close = line.lastIndexOf(']');
                if (open < 0 || close < open) return null;
                outcome = parseOutcome(line.substring(open + 1, close).strip());
                if (outcome < 0) return null;
                fenPart = line.substring(0, open).strip();
            } else {
                // c0 or c9 annotation format
                String stripped = line.endsWith(";") ? line.substring(0, line.length() - 1).strip() : line;
                int markerIdx;
                int c0Idx = stripped.indexOf("c0");
                int c9Idx = stripped.indexOf("c9");
                if (c0Idx >= 0 && (c9Idx < 0 || c0Idx <= c9Idx)) {
                    markerIdx = c0Idx;
                } else if (c9Idx >= 0) {
                    markerIdx = c9Idx;
                } else {
                    return null;
                }
                fenPart = stripped.substring(0, markerIdx).strip();
                String resultPart = stripped.substring(markerIdx + 2).strip().replace("\"", "").strip();
                outcome = parseOutcome(resultPart);
                if (outcome < 0) return null;
            }

            // Build full FEN (EPD has 4 fields, FEN has 6)
            String[] parts  = fenPart.split("\\s+");
            String   fullFen = parts.length >= 6 ? fenPart : fenPart + " 0 1";

            Board board = new Board(fullFen);

            // Filter 1: skip positions where the active side is in check
            if (board.isActiveColorInCheck()) return null;

            // Filter 2: skip positions with material count > 32 (opening-book noise)
            TunerPosition pos = TunerPosition.from(board, fullFen);
            int materialCount = Long.bitCount(pos.getWhiteOccupancy() | pos.getBlackOccupancy());
            if (materialCount > 32) return null;

            return new LabelledPosition(pos, outcome);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Parses an outcome string.
     *
     * @return 1.0 (white win), 0.5 (draw), 0.0 (black win), or -1 on failure.
     */
    private static double parseOutcome(String s) {
        return switch (s.strip()) {
            case "1.0", "1-0"       -> 1.0;
            case "0.5", "1/2-1/2"   -> 0.5;
            case "0.0", "0-1"       -> 0.0;
            default -> {
                try { yield Double.parseDouble(s); }
                catch (NumberFormatException e) { yield -1.0; }
            }
        };
    }

    /**
     * Loads up to {@code maxPositions} positions from an EPD file, then augments the
     * corpus with a color-flipped copy of every position, eliminating color bias.
     *
     * <p>For each loaded position with outcome {@code o}, a color-flipped copy is added
     * with outcome {@code 1.0 - o} (exchanging the White/Black perspective). The
     * resulting list interleaves original and flipped entries.
     *
     * @param file         EPD dataset path
     * @param maxPositions maximum number of base positions to load (list will be ≤ 2 × this)
     * @return list of size ≤ 2 × maxPositions
     * @throws IOException if the file cannot be read
     */
    public static List<LabelledPosition> loadBalanced(Path file, int maxPositions) throws IOException {
        List<LabelledPosition> base = loadEpd(file, maxPositions);

        // Pre-validate flips once to determine which indices produce a valid
        // color-flipped FEN.  We store only the source index, not the flipped
        // TunerPosition — that is computed on demand in AbstractList.get().
        int[] flipBuf = new int[base.size()];
        int flipCount = 0;
        for (int i = 0; i < base.size(); i++) {
            if (colorFlipFen(base.get(i).pos().fen()) != null) {
                flipBuf[flipCount++] = i;
            }
        }
        final int[] validFlipIdx = java.util.Arrays.copyOf(flipBuf, flipCount);

        LOG.info("[PositionLoader] loadBalanced: {} base + {} flipped = {} total",
                 base.size(), validFlipIdx.length, base.size() + validFlipIdx.length);

        return new BalancedList(base, validFlipIdx);
    }

    /** A corpus that knows which records are generated twins, so splitting does not have to infer it. */
    interface TwinIdentified {
        /** Index of the record that is the colour-flipped twin of record {@code i}, or -1 if it has none. */
        int twinOf(int i);
    }

    /** Read-only base records followed by the colour-flipped copies of the ones that flip. */
    private static final class BalancedList extends AbstractList<LabelledPosition> implements TwinIdentified {
        private final List<LabelledPosition> base;
        private final int[] flipSource;   // flipped record r (index base.size() + r) copies base[flipSource[r]]
        private final int[] flipOfBase;   // base record i has its flip at index flipOfBase[i], or -1

        BalancedList(List<LabelledPosition> base, int[] flipSource) {
            this.base = base;
            this.flipSource = flipSource;
            this.flipOfBase = new int[base.size()];
            java.util.Arrays.fill(flipOfBase, -1);
            for (int r = 0; r < flipSource.length; r++) flipOfBase[flipSource[r]] = base.size() + r;
        }

        @Override
        public LabelledPosition get(int i) {
            if (i < base.size()) return base.get(i);
            LabelledPosition src = base.get(flipSource[i - base.size()]);
            String flippedFen = colorFlipFen(src.pos().fen()); // non-null by construction
            TunerPosition flipped = parseFen(flippedFen);
            if (flipped == null) {
                // Extremely rare: FEN was valid at validation time but failed now.
                // Fall back to the unflipped original to avoid NPE.
                return src;
            }
            return new LabelledPosition(flipped, 1.0 - src.outcome());
        }

        @Override
        public int size() { return base.size() + flipSource.length; }

        @Override
        public int twinOf(int i) { return i < base.size() ? flipOfBase[i] : flipSource[i - base.size()]; }
    }

    /**
     * Returns the color-flipped FEN of the given position (mirrors the board vertically,
     * swaps piece colors, toggles the side to move, and adjusts castling/en-passant).
     * Returns {@code null} if the FEN is malformed.
     */
    private static String colorFlipFen(String fen) {
        try {
            String[] parts = fen.split(" ");
            if (parts.length < 4) return null;
            String placement = parts[0];
            String color     = parts[1];
            String castling  = parts[2];
            String ep        = parts[3];
            String rest      = parts.length > 4 ? parts[4] + " " + parts[5] : "0 1";

            // Reverse rank order and swap piece case
            String[] ranks = placement.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = ranks.length - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append('/');
                for (char c : ranks[i].toCharArray()) {
                    if      (Character.isUpperCase(c)) sb.append(Character.toLowerCase(c));
                    else if (Character.isLowerCase(c)) sb.append(Character.toUpperCase(c));
                    else                               sb.append(c);
                }
            }

            // Toggle active color
            String newColor = color.equals("w") ? "b" : "w";

            // Swap castling rights (K↔k, Q↔q)
            StringBuilder cr = new StringBuilder();
            for (char c : castling.toCharArray()) {
                if      (c == 'K') cr.append('k');
                else if (c == 'k') cr.append('K');
                else if (c == 'Q') cr.append('q');
                else if (c == 'q') cr.append('Q');
                else               cr.append(c);
            }

            // Mirror en-passant rank (3↔6)
            String newEp = ep;
            if (!ep.equals("-") && ep.length() == 2) {
                char rank = ep.charAt(1);
                if      (rank == '3') newEp = "" + ep.charAt(0) + '6';
                else if (rank == '6') newEp = "" + ep.charAt(0) + '3';
            }

            return sb + " " + newColor + " " + cr + " " + newEp + " " + rest;
        } catch (Exception e) {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Train / Val / Test split
    // -----------------------------------------------------------------------

    /**
     * Splits the supplied positions into train, validation, and test partitions
     * using a stratified shuffle.
     *
     * <p>Stratification buckets positions by outcome:
     * <ul>
     *   <li>win   — outcome within ±0.05 of 1.0</li>
     *   <li>draw  — outcome within ±0.05 of 0.5</li>
     *   <li>loss  — outcome within ±0.05 of 0.0</li>
     *   <li>other — pseudo-WDL values that don't fall in the above buckets</li>
     * </ul>
     *
     * <p>The split is performed by shuffling integer indices within each bucket
     * (deterministic for the same {@code seed}) and then slicing them according
     * to the requested fractions.  No position data is copied.
     *
     * @param positions the full labelled corpus
     * @param trainFrac fraction of data for training (e.g. 0.80)
     * @param valFrac   fraction of data for validation / K calibration (e.g. 0.10)
     * @param seed      random seed for reproducibility
     * @return a {@link CorpusPartition} with three unmodifiable list views
     * @throws IllegalArgumentException if trainFrac + valFrac >= 1.0 or fractions are non-positive
     */
    public static CorpusPartition split(List<LabelledPosition> positions,
                                        double trainFrac, double valFrac, long seed) {
        if (trainFrac <= 0 || valFrac <= 0) {
            throw new IllegalArgumentException(
                    "trainFrac and valFrac must be positive, got "
                    + trainFrac + " / " + valFrac);
        }
        if (trainFrac + valFrac >= 1.0) {
            throw new IllegalArgumentException(
                    "trainFrac + valFrac must be < 1.0, got "
                    + (trainFrac + valFrac));
        }

        // A position and its colour-flipped twin (from loadBalanced) carry the same information, so they move
        // together; split on a group of indices, not on single indices (#274). Exact duplicates stay separate.
        List<int[]> groups = twinGroups(positions);

        // Partition groups into outcome buckets, by the outcome of the first member
        List<int[]> winGroups   = new ArrayList<>();
        List<int[]> drawGroups  = new ArrayList<>();
        List<int[]> lossGroups  = new ArrayList<>();
        List<int[]> otherGroups = new ArrayList<>();

        for (int[] g : groups) {
            double o = positions.get(g[0]).outcome();
            if (Math.abs(o - 1.0) <= 0.05)      winGroups.add(g);
            else if (Math.abs(o - 0.5) <= 0.05) drawGroups.add(g);
            else if (Math.abs(o - 0.0) <= 0.05) lossGroups.add(g);
            else                                 otherGroups.add(g);
        }

        // Shuffle each bucket with the same seed (different per-bucket offset)
        shuffle(winGroups,   new Random(seed));
        shuffle(drawGroups,  new Random(seed + 1));
        shuffle(lossGroups,  new Random(seed + 2));
        shuffle(otherGroups, new Random(seed + 3));

        // Collect per-bucket slices into global train/val/test index lists
        List<Integer> trainIdx = new ArrayList<>();
        List<Integer> valIdx   = new ArrayList<>();
        List<Integer> testIdx  = new ArrayList<>();

        for (List<int[]> bucket : List.of(winGroups, drawGroups, lossGroups, otherGroups)) {
            int n        = bucket.size();
            int nTrain   = (int) Math.round(n * trainFrac);
            int nVal     = (int) Math.round(n * valFrac);
            // Clamp to avoid going past end
            nTrain = Math.min(nTrain, n);
            nVal   = Math.min(nVal, n - nTrain);

            addAll(trainIdx, bucket.subList(0, nTrain));
            addAll(valIdx,   bucket.subList(nTrain, nTrain + nVal));
            addAll(testIdx,  bucket.subList(nTrain + nVal, n));
        }

        return new CorpusPartition(
                indexView(positions, Collections.unmodifiableList(trainIdx)),
                indexView(positions, Collections.unmodifiableList(valIdx)),
                indexView(positions, Collections.unmodifiableList(testIdx)));
    }

    /** Shuffle helper (Fisher-Yates on an ArrayList of integers). */
    private static <T> void shuffle(List<T> list, Random rng) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private static void addAll(List<Integer> out, List<int[]> groups) {
        for (int[] g : groups) for (int idx : g) out.add(idx);
    }

    /**
     * Groups colour-flipped twins. A record is paired with an earlier record of the opposite orientation that has
     * the same position and the mirrored outcome (what {@link #loadBalanced} produces); everything else is a group
     * of one. Records are matched by a 64-bit hash of the position key. Groups come out in the order of their first member, so a corpus without twins splits exactly as it
     * did when single indices were shuffled.
     */
    static List<int[]> twinGroups(List<LabelledPosition> positions) {
        return twinGroups(positions, PositionLoader::hash64);
    }

    /** Same with the hash supplied, so a test can force every record into one hash run. */
    static List<int[]> twinGroups(List<LabelledPosition> positions, java.util.function.ToLongFunction<String> hasher) {
        final int n = positions.size();
        int[] partner = new int[n];
        java.util.Arrays.fill(partner, -1);
        if (positions instanceof TwinIdentified known) {
            // Generated corpora say which records are twins, so no label or hash comparison is involved.
            for (int i = 0; i < n; i++) {
                int t = known.twinOf(i);
                if (t >= 0 && t != i && partner[i] == -1 && partner[t] == -1) { partner[i] = t; partner[t] = i; }
            }
        } else {
            inferTwins(positions, partner, hasher);
        }
        List<int[]> groups = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (partner[i] == -1) groups.add(new int[]{i});
            else if (partner[i] > i) groups.add(new int[]{i, partner[i]});
        }
        return groups;
    }

    /** Outcomes of a twin pair sum to 1 up to the rounding of {@code 1.0 - o} applied twice. */
    private static final double TWIN_OUTCOME_TOLERANCE = 1e-9;

    /**
     * Pairs twins in a corpus that does not say which they are. A 64-bit hash of the twin key only narrows the
     * search. Records pair up only when their twin keys are equal strings (so a hash collision never pairs two
     * different positions), they are on opposite orientations, and their outcomes mirror within a tolerance
     * (no rounding of labels into buckets).
     */
    private static void inferTwins(List<LabelledPosition> positions, int[] partner,
                                   java.util.function.ToLongFunction<String> hasher) {
        final int n = positions.size();
        long[] hash = new long[n];
        for (int i = 0; i < n; i++) hash[i] = hasher.applyAsLong(twinKey(positions.get(i).pos().fen()));

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> {
            int c = Long.compare(hash[x], hash[y]);
            return c != 0 ? c : Integer.compare(x, y);
        });

        int start = 0;
        while (start < n) {
            int end = start + 1;
            while (end < n && hash[order[end]] == hash[order[start]]) end++;
            if (end - start > 1) {
                java.util.Map<String, List<Integer>> byKey = new java.util.LinkedHashMap<>();
                for (int r = start; r < end; r++) {
                    int i = order[r];
                    byKey.computeIfAbsent(twinKey(positions.get(i).pos().fen()), key -> new ArrayList<>()).add(i);
                }
                for (List<Integer> sameKey : byKey.values()) {
                    if (sameKey.size() > 1) pairMirrored(positions, sameKey, partner);
                }
            }
            start = end;
        }
    }

    /** Within records that share a twin key, pairs an opposite-orientation record whose outcome mirrors this one's. */
    private static void pairMirrored(List<LabelledPosition> positions, List<Integer> sameKey, int[] partner) {
        List<Integer> ordered = new ArrayList<>(sameKey);
        Collections.sort(ordered);
        java.util.TreeMap<Double, java.util.ArrayDeque<Integer>> waitingCanonical = new java.util.TreeMap<>();
        java.util.TreeMap<Double, java.util.ArrayDeque<Integer>> waitingFlipped = new java.util.TreeMap<>();
        for (int i : ordered) {
            LabelledPosition lp = positions.get(i);
            String fen = lp.pos().fen();
            String own = positionKey(fen);
            String flipped = colorFlipFen(fen);
            boolean canonical = flipped == null || own.compareTo(positionKey(flipped)) <= 0;
            var opposite = canonical ? waitingFlipped : waitingCanonical;
            var mine = canonical ? waitingCanonical : waitingFlipped;
            double target = 1.0 - lp.outcome();
            Double key = opposite.floorKey(target + TWIN_OUTCOME_TOLERANCE);
            if (key != null && key >= target - TWIN_OUTCOME_TOLERANCE) {
                var queue = opposite.get(key);
                int j = queue.poll();
                if (queue.isEmpty()) opposite.remove(key);
                partner[i] = j;
                partner[j] = i;
            } else {
                mine.computeIfAbsent(lp.outcome(), o -> new java.util.ArrayDeque<>()).add(i);
            }
        }
    }

    /** FNV-1a over the characters with a final avalanche; collisions are far below one in 10^9 for a million keys. */
    private static long hash64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return h;
    }

    /** Position, side to move, castling rights (sorted) and en passant square; the move counters are left out. */
    private static String positionKey(String fen) {
        String[] parts = fen.split(" ");
        if (parts.length < 4) return fen;
        char[] castling = parts[2].toCharArray();
        java.util.Arrays.sort(castling);
        return parts[0] + " " + parts[1] + " " + new String(castling) + " " + parts[3];
    }

    /** Same for a position and its colour-flipped twin. Package-private for tests. */
    static String twinKey(String fen) {
        String own = positionKey(fen);
        String flipped = colorFlipFen(fen);
        String other = flipped == null ? own : positionKey(flipped);
        return own.compareTo(other) <= 0 ? own : other;
    }

    /**
     * Returns an unmodifiable view of {@code positions} restricted to the given index list.
     * No position data is copied.
     */
    private static List<LabelledPosition> indexView(List<LabelledPosition> positions,
                                                     List<Integer> indices) {
        return new AbstractList<LabelledPosition>() {
            @Override
            public LabelledPosition get(int i) { return positions.get(indices.get(i)); }
            @Override
            public int size() { return indices.size(); }
        };
    }

}
