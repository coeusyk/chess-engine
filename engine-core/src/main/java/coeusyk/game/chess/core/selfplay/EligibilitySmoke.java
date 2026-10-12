package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.eval.nnue.NnueEvaluator;
import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * DR-E12-stage3-generator-selection.md section 4's ENGINE/LEGALITY + IDENTITY categories,
 * implemented: loads the exact network artifact, verifies its SHA-256 and logical UUID against
 * the operator-supplied {@link GeneratorConfig}, then runs a fixed, deterministic search on a
 * small set of smoke positions and checks every returned move is legal, every score is within a
 * sane bound, and the board is left unchanged by each search. This does not run the PROTOCOL
 * category (VSPR round-trip) or the BOUNDED PILOT category (DR-E12 section 4) -- those are
 * exercised by {@link SelfPlayCli}'s own end-to-end pilot run, not by this class.
 */
public final class EligibilitySmoke {

    /** A handful of structurally distinct positions -- opening, a tactical middlegame position,
     * and a simple endgame -- chosen only to exercise ordinary move generation/search, not to be
     * a comprehensive correctness suite (Perft/SearchRegressionSuite already own that). */
    private static final List<String> SMOKE_FENS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
            "8/8/8/4k3/8/8/4P3/4K3 w - - 0 1"
    );

    private static final int SMOKE_DEPTH = 4;
    private static final int SANE_SCORE_BOUND = 1_000_000; // generous; Searcher's own MATE_SCORE is 100_000

    public record SmokeResult(boolean passed, List<String> evidence) {
        public static SmokeResult pass(List<String> evidence) {
            return new SmokeResult(true, List.copyOf(evidence));
        }

        public static SmokeResult fail(List<String> evidence) {
            return new SmokeResult(false, List.copyOf(evidence));
        }
    }

    private EligibilitySmoke() {
    }

    /** Runs IDENTITY + ENGINE/LEGALITY checks. Never throws for an ordinary eligibility failure --
     * returns {@code SmokeResult.fail(...)} with the reasons instead, so a caller can record a
     * clean eligibility decision. Throws only for an I/O failure actually loading the file. */
    public static SmokeResult run(GeneratorConfig config) {
        List<String> evidence = new ArrayList<>();

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(config.networkPath());
        } catch (IOException e) {
            evidence.add("could not read network file at " + config.networkPath() + ": " + e.getMessage());
            return SmokeResult.fail(evidence);
        }

        String actualSha256 = sha256Hex(fileBytes);
        if (!actualSha256.equals(config.expectedNetworkSha256())) {
            evidence.add("IDENTITY: network SHA-256 mismatch -- expected " + config.expectedNetworkSha256()
                    + ", actual " + actualSha256);
            return SmokeResult.fail(evidence);
        }
        evidence.add("IDENTITY: network SHA-256 matches (" + actualSha256 + ")");

        NnueNetwork network;
        try {
            network = NnueNetwork.load(config.networkPath());
        } catch (IOException e) {
            evidence.add("ENGINE/LEGALITY: network failed to load: " + e.getMessage());
            return SmokeResult.fail(evidence);
        }

        if (!network.networkUuid().equals(config.expectedNetworkUuid())) {
            evidence.add("IDENTITY: network UUID mismatch -- expected " + config.expectedNetworkUuid()
                    + ", actual " + network.networkUuid());
            return SmokeResult.fail(evidence);
        }
        evidence.add("IDENTITY: network UUID matches (" + network.networkUuid() + ")");
        evidence.add("IDENTITY: engineBuildId recorded as operator-supplied (" + config.engineBuildId()
                + ") -- not independently re-derived; no build-identity utility exists in this "
                + "repository to verify it against (see EligibilitySmoke class docstring).");

        for (String fen : SMOKE_FENS) {
            String failure = smokeOnePosition(fen, network);
            if (failure != null) {
                evidence.add("ENGINE/LEGALITY: " + failure);
                return SmokeResult.fail(evidence);
            }
            evidence.add("ENGINE/LEGALITY: " + fen + " -- legal move, sane score, board unchanged");
        }

        return SmokeResult.pass(evidence);
    }

    private static String smokeOnePosition(String fen, NnueNetwork network) {
        Board board = new Board(fen);
        String fenBefore = board.toFen();

        Searcher searcher = new Searcher();
        searcher.setEvaluatorStrategy(new NnueEvaluator(network));

        SearchResult result;
        try {
            result = searcher.iterativeDeepening(board, SMOKE_DEPTH);
        } catch (RuntimeException e) {
            return "search threw " + e.getClass().getSimpleName() + " on " + fen + ": " + e.getMessage();
        }

        String fenAfter = board.toFen();
        if (!fenBefore.equals(fenAfter)) {
            return "board mutated by search on " + fen + " -- before=" + fenBefore + " after=" + fenAfter;
        }

        if (result.bestMove() == null) {
            return "search returned no move on non-terminal position " + fen;
        }

        int packed = result.bestMove().pack();
        if (!Double.isFinite((double) result.scoreCp()) || Math.abs(result.scoreCp()) > SANE_SCORE_BOUND) {
            return "search returned an out-of-bound score " + result.scoreCp() + " on " + fen;
        }

        int[] legal = new int[256];
        int legalCount = MovesGenerator.generate(board, legal);
        boolean isLegal = false;
        for (int i = 0; i < legalCount; i++) {
            if (legal[i] == packed) {
                isLegal = true;
                break;
            }
        }
        if (!isLegal) {
            return "search returned illegal move (packed=" + packed + ", from="
                    + Move.from(packed) + " to=" + Move.to(packed) + ") on " + fen;
        }

        return null;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
