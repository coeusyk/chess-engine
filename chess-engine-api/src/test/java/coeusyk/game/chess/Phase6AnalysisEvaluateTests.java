package coeusyk.game.chess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class Phase6AnalysisEvaluateTests {

    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidFenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"not-a-valid-fen\",\"depth\":1,\"multiPv\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void missingFenReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"depth\":1,\"multiPv\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validFenDepth1ReturnsCorrectResponseShape() throws Exception {
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + START_FEN + "\",\"depth\":1,\"multiPv\":1}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.bestMove").isString())
                .andExpect(jsonPath("$.score.type").isString())
                .andExpect(jsonPath("$.score.value").isNumber())
                .andExpect(jsonPath("$.depth").isNumber())
                .andExpect(jsonPath("$.nodes").isNumber())
                .andExpect(jsonPath("$.nps").isNumber())
                .andExpect(jsonPath("$.pv").isArray())
                .andExpect(jsonPath("$.lines").isArray())
                .andExpect(jsonPath("$.lines", hasSize(1)));
    }

    @Test
    void depth6OnStartingPositionReturnsValidResult() throws Exception {
        // Acceptance criteria: response shape correct for depth 6 on starting position.
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + START_FEN + "\",\"depth\":6,\"multiPv\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestMove").isString())
                .andExpect(jsonPath("$.depth", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.nodes", greaterThan(0)))
                .andExpect(jsonPath("$.pv").isArray())
                .andExpect(jsonPath("$.pv", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.lines", hasSize(1)));
    }

    @Test
    void multiPv3ReturnsThreeRankedLines() throws Exception {
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + START_FEN + "\",\"depth\":1,\"multiPv\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(3)))
                .andExpect(jsonPath("$.lines[0].rank").value(1))
                .andExpect(jsonPath("$.lines[1].rank").value(2))
                .andExpect(jsonPath("$.lines[2].rank").value(3))
                .andExpect(jsonPath("$.lines[0].score.type").isString())
                .andExpect(jsonPath("$.lines[0].pv").isArray());
    }

    @Test
    void depthAbove15IsSilentlyClamped() throws Exception {
        // Use a checkmate position — no legal moves, so the search completes instantly
        // at any depth. This lets us verify the cap without hitting the 60-second timeout.
        String checkmateFen = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4";
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + checkmateFen + "\",\"depth\":30,\"multiPv\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depth", lessThanOrEqualTo(15)));
    }

    @Test
    void scoreTypeIsEitherCpOrMate() throws Exception {
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fen\":\"" + START_FEN + "\",\"depth\":1,\"multiPv\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.type", anyOf(is("cp"), is("mate"))));
    }
}
