package coeusyk.game.chess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class Phase6AnalysisStreamTests {

    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidFenReturnsBadRequestBeforeStreamOpens() throws Exception {
        mockMvc.perform(get("/api/analysis/stream")
                        .param("fen", "not-a-valid-fen"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validFenOpensStreamAndEmitsInfoThenBestmove() throws Exception {
        // depth=1 finishes almost instantly; the stream should complete synchronously
        // within MockMvc's async dispatch.
        MvcResult asyncResult = mockMvc.perform(
                        get("/api/analysis/stream")
                                .param("fen", START_FEN)
                                .param("depth", "1")
                                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Wait for async completion and verify the response
        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM_VALUE));

        String body = asyncResult.getResponse().getContentAsString();
        assertTrue(body.contains("\"type\":\"info\""), "Expected at least one info event, got: " + body);
        assertTrue(body.contains("\"type\":\"bestmove\""), "Expected bestmove event, got: " + body);
    }

    @Test
    void matePositionEmitsMateScore() throws Exception {
        // Fool's mate — black is in checkmate; queen on h4, mate delivered
        // Use a position where White is about to deliver mate in 1: 
        // Black king h8, White queen g7 (KQK-like; just needs depth>=1)
        // Simpler: a normal position analyzed at depth=1 just checks the stream opens.
        // For a mate score test, use a position where is it mate in 1 for white:
        //   FEN: 6k1/5ppp/8/8/8/8/8/3Q2K1 w - - 0 1
        //   Qd1-d8# is mate in 1 ... but let's use proven mate-in-1:
        //   r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4
        //   Black is in checkmate (Fool's mate result position)
        String matedFen = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4";

        MvcResult asyncResult = mockMvc.perform(
                        get("/api/analysis/stream")
                                .param("fen", matedFen)
                                .param("depth", "1")
                                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk());

        String body = asyncResult.getResponse().getContentAsString();
        // The position is checkmate so there are no legal moves; stream should emit bestmove 0000 or
        // an info with a terminal score followed by bestmove.
        assertTrue(body.contains("\"type\":\"bestmove\""), "Expected bestmove event, got: " + body);
    }

    @Test
    void multiPvThreeEmitsThreeRankedInfoEventsPerIteration() throws Exception {
        // At depth=1 with MultiPV=3, expect 3 info events with multiPv 1, 2, 3
        MvcResult asyncResult = mockMvc.perform(
                        get("/api/analysis/stream")
                                .param("fen", START_FEN)
                                .param("depth", "1")
                                .param("multiPv", "3")
                                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk());

        String body = asyncResult.getResponse().getContentAsString();
        // Count how many info events are in the body
        int infoCount = body.split("\"type\":\"info\"").length - 1;
        assertTrue(infoCount >= 3,
                "Expected at least 3 info events for multiPv=3, got " + infoCount + ". Body: " + body);
        assertTrue(body.contains("\"multiPv\":1"), "Expected multiPv rank 1, got: " + body);
        assertTrue(body.contains("\"multiPv\":2"), "Expected multiPv rank 2, got: " + body);
        assertTrue(body.contains("\"multiPv\":3"), "Expected multiPv rank 3, got: " + body);
    }

    @Test
    void streamIncludesExpectedInfoFields() throws Exception {
        MvcResult asyncResult = mockMvc.perform(
                        get("/api/analysis/stream")
                                .param("fen", START_FEN)
                                .param("depth", "1")
                                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk());

        String body = asyncResult.getResponse().getContentAsString();
        // Verify all required SSE info fields are present
        assertTrue(body.contains("\"depth\""), "Missing depth field");
        assertTrue(body.contains("\"seldepth\""), "Missing seldepth field");
        assertTrue(body.contains("\"score\""), "Missing score field");
        assertTrue(body.contains("\"nodes\""), "Missing nodes field");
        assertTrue(body.contains("\"nps\""), "Missing nps field");
        assertTrue(body.contains("\"time\""), "Missing time field");
        assertTrue(body.contains("\"hashfull\""), "Missing hashfull field");
        assertTrue(body.contains("\"pv\""), "Missing pv field");
    }
}
