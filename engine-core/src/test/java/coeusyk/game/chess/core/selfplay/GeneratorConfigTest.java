package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.selfplay.vspr.SearchBudgetKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorConfigTest {

    private static final String VALID_SHA256 = "a".repeat(64);

    @Test
    void validConfigConstructsSuccessfully() {
        GeneratorConfig config = new GeneratorConfig(
                Path.of("network.nnue"), VALID_SHA256, "uuid-1", "build-1",
                SearchBudgetKind.DEPTH, 6, 500, 1, null, 42L,
                Path.of("out.vspr"), Path.of("out.json"));
        assertEquals(500, config.maxPlies());
    }

    @Test
    void zeroMaxGamesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "b", SearchBudgetKind.DEPTH, 6, 500, 0, null, 1L,
                Path.of("o.vspr"), Path.of("o.json")));
    }

    @Test
    void negativeMaxGamesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "b", SearchBudgetKind.DEPTH, 6, 500, -1, null, 1L,
                Path.of("o.vspr"), Path.of("o.json")));
    }

    @Test
    void zeroMaxPositionsRejectedWhenGiven() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "b", SearchBudgetKind.DEPTH, 6, 500, 1, 0, 1L,
                Path.of("o.vspr"), Path.of("o.json")));
    }

    @Test
    void nullMaxPositionsIsAllowedNoSecondaryCap() {
        GeneratorConfig config = new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "b", SearchBudgetKind.DEPTH, 6, 500, 1, null, 1L,
                Path.of("o.vspr"), Path.of("o.json"));
        assertNull(config.maxPositions());
    }

    @Test
    void malformedSha256Rejected() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratorConfig(
                Path.of("n"), "not-a-sha", "u", "b", SearchBudgetKind.DEPTH, 6, 500, 1, null, 1L,
                Path.of("o.vspr"), Path.of("o.json")));
    }

    @Test
    void maxPliesTooCloseToUnmakePoolSizeRejected() {
        // 700 + SEARCH_OCCUPANCY_MARGIN(150) >= Board.UNMAKE_POOL_SIZE(768)
        assertThrows(IllegalArgumentException.class, () -> new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "b", SearchBudgetKind.DEPTH, 6, 700, 1, null, 1L,
                Path.of("o.vspr"), Path.of("o.json")));
    }

    @Test
    void conservativeDefaultFiveHundredPliesIsAccepted() {
        GeneratorConfig config = new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "b", SearchBudgetKind.DEPTH, 6, 500, 1, null, 1L,
                Path.of("o.vspr"), Path.of("o.json"));
        assertEquals(500, config.maxPlies());
    }

    @Test
    void nonPositiveSearchBudgetValueRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "b", SearchBudgetKind.DEPTH, 0, 500, 1, null, 1L,
                Path.of("o.vspr"), Path.of("o.json")));
    }

    @Test
    void blankEngineBuildIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GeneratorConfig(
                Path.of("n"), VALID_SHA256, "u", "  ", SearchBudgetKind.DEPTH, 6, 500, 1, null, 1L,
                Path.of("o.vspr"), Path.of("o.json")));
    }
}
