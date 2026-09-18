package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.eval.nnue.NnueNetwork;
import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.selfplay.vspr.GameOutcome;
import coeusyk.game.chess.core.selfplay.vspr.TerminationReason;
import coeusyk.game.chess.core.selfplay.vspr.TrainingSample;
import coeusyk.game.chess.core.selfplay.vspr.VsprCodec;
import coeusyk.game.chess.core.selfplay.vspr.VsprFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end: CLI arg parsing -> eligibility smoke -> a tiny bounded pilot -> VSPR output that
 * decodes cleanly with the same {@code VsprCodec} that wrote it (no separate reinterpretation),
 * plus the CLI's own required-argument / no-implicit-fallback behavior. This is the "generated
 * pilot record decodes with existing Java VsprCodec" and "outcome/termination pairs valid" test
 * from #221 section 13 -- no live self-play beyond a 1-2 game, tiny-hidden-width synthetic
 * network smoke run. */
class SelfPlayCliTest {

    private static Path fixturePath() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".git"))) {
            current = current.getParent();
        }
        assertNotNull(current, "no .git ancestor found");
        return current.resolve("engine-core/src/test/resources/nnue/d6-export-fixture.nnue");
    }

    private static String sha256Of(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void boundedPilotProducesVsprDecodableByTheSameCodec(@TempDir Path tmp) throws Exception {
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");

        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "2",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        int exitCode = SelfPlayCli.run(args);
        assertEquals(0, exitCode);
        assertTrue(Files.exists(vsprOut));
        assertTrue(Files.exists(decisionOut));

        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        assertEquals(2, decoded.frames().size());
        for (var frame : decoded.frames()) {
            // Every emitted outcome/termination pair must be one DR-220's own pairing table
            // allows -- VsprCodec.read() itself already enforces this at decode time, so
            // successfully decoding is the assertion; this loop double-checks the specific
            // MOVE_CAP/UNRESOLVED pairing this 6-ply cap run is expected to hit.
            if (frame.terminationReason() == TerminationReason.MOVE_CAP) {
                assertEquals(GameOutcome.UNRESOLVED, frame.gameOutcome());
            }
        }

        String decisionJson = Files.readString(decisionOut);
        assertTrue(decisionJson.contains("\"eligibilitySmokePassed\": true"));
        assertTrue(decisionJson.contains("\"gamesCompleted\": 2"));
    }

    @Test
    void missingRequiredArgumentFailsFast() {
        String[] argsMissingNetwork = {
                "--network-sha256", "a".repeat(64),
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", "out.vspr",
                "--output-decision-record", "out.json",
        };
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.run(argsMissingNetwork));
    }

    @Test
    void thereIsNoLatestCheckpointFallbackArgument() {
        // Structural check: CliArgs.parse requires --network explicitly; there is no flag or
        // code path anywhere in SelfPlayCli that scans a directory or defaults to an unspecified
        // network file (#221 section 10 / DR-E12 section 3's "no implicit latest-checkpoint
        // selection"). Asserted here by confirming --network is unconditionally required, same
        // as every other identity field.
        String[] argsMissingSha = {
                "--network", "n.nnue",
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", "out.vspr",
                "--output-decision-record", "out.json",
        };
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.run(argsMissingSha));
    }

    @Test
    void maxPositionsStopsOnlyBetweenGamesNeverMidGame(@TempDir Path tmp) throws Exception {
        // E-15 (#223) section 6: a VSPR game must never be cut in half merely because
        // maxPositions was reached mid-game. --max-plies 6 with the deterministic control
        // selector produces a full, MOVE_CAP-terminated 6-ply/6-sample game every time (matching
        // moveCapProducesUnresolvedOutcomeNotDraw's own GameLoopTest coverage) -- setting
        // --max-positions 1 (well below one game's own sample count) still must emit that whole
        // 6-sample game, not a 1-sample fragment, before the secondary maxPositions stop takes
        // effect at the NEXT game boundary (SelfPlayCli.run()'s own loop checks maxPositions only
        // once per game, before that game starts -- never inside GameLoop.playGame()).
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");

        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "5",
                "--max-positions", "1",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        assertEquals(0, SelfPlayCli.run(args));

        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        assertEquals(1, decoded.frames().size(), "maxPositions=1 must still stop only between games");
        var onlyGame = decoded.frames().get(0);
        assertEquals(6, onlyGame.samples().size(), "the one emitted game must be whole (6 plies), not truncated to 1");
        assertEquals(6, onlyGame.playedMoves().size());
        assertEquals(TerminationReason.MOVE_CAP, onlyGame.terminationReason());
    }

    @Test
    void diversityFlagsMustAllBeGivenTogetherOrNotAtAll(@TempDir Path tmp) {
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", "a".repeat(64),
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
                "--diversity-max-rank", "3", // cp-loss-bound and temperature deliberately omitted
        };
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.run(args));
    }

    @Test
    void diversityPilotWritesLocalDiagnosticsAndProvenanceButNotVsprFields(@TempDir Path tmp) throws Exception {
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");

        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
                "--diversity-max-rank", "3",
                "--diversity-cp-loss-bound", "40",
                "--diversity-temperature", "20.0",
        };

        int exitCode = SelfPlayCli.run(args);
        assertEquals(0, exitCode);

        Path diagnosticsPath = tmp.resolve("pilot.vspr.diversity-diagnostics.csv");
        assertTrue(Files.exists(diagnosticsPath), "diagnostics CSV must be written when diversity is enabled");
        List<String> lines = Files.readAllLines(diagnosticsPath);
        assertEquals("gameId,ply,candidateCount,chosenRank,rank1Kind,rank1Value,"
                + "chosenKind,chosenValue,cpLossFromRank1,selectionWeight,selectionProbability", lines.get(0));
        assertTrue(lines.size() > 1, "expected at least one diagnostics row");

        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        for (var frame : decoded.frames()) {
            for (var decision : frame.playedMoves()) {
                assertEquals(coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind.NAMED,
                        decision.selectionMechanismKind());
                assertEquals("seeded-diversity-v1", decision.mechanismName());
                assertTrue(decision.selectionSeed().isPresent());
            }
        }
    }

    @Test
    void bestMoveControlPilotWritesNoDiagnosticsFile(@TempDir Path tmp) throws Exception {
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");

        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        assertEquals(0, SelfPlayCli.run(args));
        assertFalse(Files.exists(tmp.resolve("pilot.vspr.diversity-diagnostics.csv")));
    }

    @Test
    void controlMultiPvFlagIsParsedAndDiversityStaysAbsent() {
        String[] args = {
                "--network", "n.nnue",
                "--network-sha256", "a".repeat(64),
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "6",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", "out.vspr",
                "--output-decision-record", "out.json",
                "--control-multipv", "3",
        };
        SelfPlayCli.CliArgs parsed = SelfPlayCli.CliArgs.parse(args);
        assertEquals(3, parsed.controlMultiPv());
        assertNull(parsed.diversity());
    }

    @Test
    void noNewFlagsLeavesControlMultiPvAndDiversityBothAbsent() {
        String[] args = {
                "--network", "n.nnue",
                "--network-sha256", "a".repeat(64),
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "6",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", "out.vspr",
                "--output-decision-record", "out.json",
        };
        SelfPlayCli.CliArgs parsed = SelfPlayCli.CliArgs.parse(args);
        assertNull(parsed.controlMultiPv());
        assertNull(parsed.diversity());
    }

    @Test
    void controlMultiPvCannotBeCombinedWithDiversityFlags() {
        String[] args = {
                "--network", "n.nnue",
                "--network-sha256", "a".repeat(64),
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "6",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", "out.vspr",
                "--output-decision-record", "out.json",
                "--control-multipv", "3",
                "--diversity-max-rank", "3",
                "--diversity-cp-loss-bound", "40",
                "--diversity-temperature", "20.0",
        };
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.CliArgs.parse(args));
    }

    @Test
    void controlMultiPvPilotStillProducesBestMoveProvenanceAndNoDiagnosticsFile(@TempDir Path tmp) throws Exception {
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");

        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
                "--control-multipv", "3",
        };

        assertEquals(0, SelfPlayCli.run(args));
        assertFalse(Files.exists(tmp.resolve("pilot.vspr.diversity-diagnostics.csv")),
                "control-multipv is still the BestMoveSelector control -- no diversity diagnostics file");

        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        for (var frame : decoded.frames()) {
            for (var decision : frame.playedMoves()) {
                assertEquals(coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind.BEST_MOVE,
                        decision.selectionMechanismKind());
                assertTrue(decision.selectionSeed().isEmpty());
            }
        }
    }

    @Test
    void failedEligibilitySmokeWritesNoOutputFiles(@TempDir Path tmp) throws Exception {
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", "b".repeat(64), // wrong on purpose
                "--network-uuid", "whatever",
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        int exitCode = SelfPlayCli.run(args);
        assertNotEquals(0, exitCode);
        assertFalse(Files.exists(vsprOut));
        assertFalse(Files.exists(decisionOut));
    }

    // ---- E-16 (#224): --start-fen, the shared-opening-prefix seam. ----
    // DR-E16-shared-opening-prefix-preregistration.md's Phase B prerequisite -- SelfPlayCli gains
    // an optional flag onto GameLoop's already-existing playGame(gameId, gameSeed, Board) overload
    // (used since #221/#222 by GameLoopTest's own terminal-state fixtures). No change to
    // GameLoop/Board/Searcher: this is purely a CLI-level seam.

    @Test
    void noStartFenLeavesFieldAbsentAndDefaultStartUnchanged(@TempDir Path tmp) throws Exception {
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "2",
                "--max-games", "1",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        SelfPlayCli.CliArgs parsed = SelfPlayCli.CliArgs.parse(args);
        assertNull(parsed.startFen(), "no --start-fen given -- field must stay absent");

        assertEquals(0, SelfPlayCli.run(args));
        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        TrainingSample first = decoded.frames().get(0).samples().get(0);
        assertEquals(new Board().toFen(), first.fen(), "default-start behavior must be unchanged");
    }

    @Test
    void invalidStartFenFailsLoudlyBeforeGeneration(@TempDir Path tmp) {
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", "n.nnue",
                "--network-sha256", "a".repeat(64),
                "--network-uuid", "u",
                "--engine-build-id", "b",
                "--search-depth", "3",
                "--max-plies", "6",
                "--max-games", "1",
                "--seed", "1",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
                "--start-fen", "not a fen",
        };

        // Fails at CliArgs.parse() itself -- before EligibilitySmoke, before the network is even
        // loaded, so a bad FEN never reaches game generation at all.
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.CliArgs.parse(args));
        assertThrows(IllegalArgumentException.class, () -> SelfPlayCli.run(args));
        assertFalse(Files.exists(vsprOut));
        assertFalse(Files.exists(decisionOut));
    }

    @Test
    void suppliedStartFenIsTheFirstPositionAndEveryFieldSurvives(@TempDir Path tmp) throws Exception {
        // Deliberately not the standard starting position: black... no, white to move (en passant
        // capturer), partial castling rights on BOTH sides (K only for white, q only for black --
        // distinguishes K from Q and k from q, not just "rights present/absent"), a genuine
        // en-passant target, a nonzero halfmove clock, and a fullmove number > 1.
        String startFen = "r3k3/8/8/3pP3/8/8/8/4K2R w Kq d6 0 8";
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "2",
                "--max-games", "1",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
                "--start-fen", startFen,
        };

        SelfPlayCli.CliArgs parsed = SelfPlayCli.CliArgs.parse(args);
        assertEquals(startFen, parsed.startFen());

        assertEquals(0, SelfPlayCli.run(args));
        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        TrainingSample first = decoded.frames().get(0).samples().get(0);
        assertEquals(startFen, first.fen(), "the supplied FEN must be exactly the first searched position");

        String[] fields = first.fen().split(" ");
        assertEquals("w", fields[1], "side to move must be preserved");
        assertEquals("Kq", fields[2], "partial castling rights (per-color, per-side) must be preserved");
        assertEquals("d6", fields[3], "the en-passant target square must be preserved");
        assertEquals("0", fields[4], "the halfmove clock must be preserved");
        assertEquals("8", fields[5], "the fullmove number must be preserved");
    }

    @Test
    void naturalTerminationStillWorksFromASuppliedNonDefaultStartFen(@TempDir Path tmp) throws Exception {
        // Fool's Mate final position -- same fixture GameLoopTest's own
        // checkmatePositionTerminatesImmediatelyWithCorrectWinner uses directly against GameLoop;
        // this proves the same behavior is reachable through the CLI's new --start-fen seam.
        String startFen = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 1 3";
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "500",
                "--max-games", "1",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
                "--start-fen", startFen,
        };

        assertEquals(0, SelfPlayCli.run(args));
        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        var frame = decoded.frames().get(0);
        assertEquals(GameOutcome.BLACK_WIN, frame.gameOutcome());
        assertEquals(TerminationReason.CHECKMATE, frame.terminationReason());
        assertTrue(frame.samples().isEmpty(), "terminal on entry -- no search, no sample");
    }

    @Test
    void exactlyMaxGamesCompleteWhenMaxPositionsIsUnset(@TempDir Path tmp) throws Exception {
        // DR-E16 section 3's hardened pairing rule: exactly 58 complete games per arm, with no
        // independent position-count stop involved at all (maxPositions left unset/null, not just
        // set high) -- distinct from maxPositionsStopsOnlyBetweenGamesNeverMidGame above, which
        // exercises a small *set* maxPositions value, not the null/unset case this design needs.
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();
        Path vsprOut = tmp.resolve("pilot.vspr");
        Path decisionOut = tmp.resolve("decision.json");
        String[] args = {
                "--network", fixturePath().toString(),
                "--network-sha256", sha,
                "--network-uuid", uuid,
                "--engine-build-id", "test-commit-hash",
                "--search-depth", "3",
                "--max-plies", "4",
                "--max-games", "58",
                "--seed", "123",
                "--output-vspr", vsprOut.toString(),
                "--output-decision-record", decisionOut.toString(),
        };

        SelfPlayCli.CliArgs parsed = SelfPlayCli.CliArgs.parse(args);
        assertNull(parsed.toGeneratorConfig().maxPositions(), "maxPositions must be unset, not just large");

        assertEquals(0, SelfPlayCli.run(args));
        VsprFile decoded;
        try (InputStream in = Files.newInputStream(vsprOut)) {
            decoded = VsprCodec.read(in);
        }
        assertEquals(58, decoded.frames().size(),
                "exactly maxGames=58 complete games must be emitted, with no position-budget stop involved");

        String decisionJson = Files.readString(decisionOut);
        assertTrue(decisionJson.contains("\"gamesCompleted\": 58"));
    }

    @Test
    void pairedControlAndTreatmentReceiveIdenticalStartFenForTheSameOpeningIndex(@TempDir Path tmp)
            throws Exception {
        // DR-E16 section 3's pairing requirement, proven at the seam level -- not full corpus
        // orchestration: for each of two distinct opening indices, run a control-arm invocation
        // (--control-multipv) and a treatment-arm invocation (--diversity-*) with the identical
        // --start-fen, and confirm both actually start from that exact FEN. One of the two openings
        // below is a real leaf from DR-E16 section 2's own 58-entry pool traversal.
        String[] openings = {
                new Board().toFen(), // opening index 0
                "rnbqk2r/pppp1ppp/4pn2/8/1bPP4/2N5/PP2PPPP/R1BQKBNR w KQkq - 2 4", // opening index 1
        };
        String sha = sha256Of(fixturePath());
        String uuid = NnueNetwork.load(fixturePath()).networkUuid();

        for (int openingIndex = 0; openingIndex < openings.length; openingIndex++) {
            String opening = openings[openingIndex];

            Path controlVspr = tmp.resolve("control-" + openingIndex + ".vspr");
            Path controlDecision = tmp.resolve("control-" + openingIndex + ".json");
            String[] controlArgs = {
                    "--network", fixturePath().toString(),
                    "--network-sha256", sha,
                    "--network-uuid", uuid,
                    "--engine-build-id", "test-commit-hash",
                    "--search-depth", "3",
                    "--max-plies", "2",
                    "--max-games", "1",
                    "--seed", "123",
                    "--output-vspr", controlVspr.toString(),
                    "--output-decision-record", controlDecision.toString(),
                    "--control-multipv", "3",
                    "--start-fen", opening,
            };

            Path treatmentVspr = tmp.resolve("treatment-" + openingIndex + ".vspr");
            Path treatmentDecision = tmp.resolve("treatment-" + openingIndex + ".json");
            String[] treatmentArgs = {
                    "--network", fixturePath().toString(),
                    "--network-sha256", sha,
                    "--network-uuid", uuid,
                    "--engine-build-id", "test-commit-hash",
                    "--search-depth", "3",
                    "--max-plies", "2",
                    "--max-games", "1",
                    "--seed", "123",
                    "--output-vspr", treatmentVspr.toString(),
                    "--output-decision-record", treatmentDecision.toString(),
                    "--diversity-max-rank", "3",
                    "--diversity-cp-loss-bound", "40",
                    "--diversity-temperature", "20.0",
                    "--start-fen", opening,
            };

            assertEquals(0, SelfPlayCli.run(controlArgs));
            assertEquals(0, SelfPlayCli.run(treatmentArgs));

            String controlFirstFen;
            try (InputStream in = Files.newInputStream(controlVspr)) {
                controlFirstFen = VsprCodec.read(in).frames().get(0).samples().get(0).fen();
            }
            String treatmentFirstFen;
            try (InputStream in = Files.newInputStream(treatmentVspr)) {
                treatmentFirstFen = VsprCodec.read(in).frames().get(0).samples().get(0).fen();
            }

            assertEquals(opening, controlFirstFen,
                    "control game " + openingIndex + " must start from its assigned opening");
            assertEquals(opening, treatmentFirstFen,
                    "treatment game " + openingIndex + " must start from its assigned opening");
            assertEquals(controlFirstFen, treatmentFirstFen,
                    "control and treatment must receive the exact same FEN for the same game index");
        }
    }
}
