package coeusyk.game.chess;

import coeusyk.game.chess.controllers.AnalysisController;
import coeusyk.game.chess.controllers.GlobalExceptionHandler;
import coeusyk.game.chess.services.AnalysisService;
import coeusyk.game.chess.services.ChessGameService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FenInputTests {
    private static final String INVALID_FEN = "4k3/8/8/8/8/8/4X3/4K3 w - - 0 1";
    private static final String VALID_FEN = "4k3/8/8/8/8/8/8/4K3 w - - 0 1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChessGameService gameService;

    @Test
    void legacyLoadRejectsUnknownPieceAndMissingFen() throws Exception {
        mockMvc.perform(put("/engine/load-fen")
                        .contentType(APPLICATION_JSON)
                        .content("{\"fenString\":\"" + INVALID_FEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid FEN piece character 'X'"));

        mockMvc.perform(put("/engine/load-fen")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("fen is required"));
    }

    @Test
    void gameLoadKeepsItsPositionWhenFenIsInvalid() throws Exception {
        String gameId = gameService.createGame();
        gameService.loadFenForGame(gameId, VALID_FEN);

        mockMvc.perform(post("/api/game/{gameId}/load", gameId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"fen\":\"" + INVALID_FEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid FEN piece character 'X'"));

        mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fen").value(VALID_FEN));
    }

    @Test
    void evaluationRejectsUnknownPiece() throws Exception {
        mockMvc.perform(post("/api/analysis/evaluate")
                        .contentType(APPLICATION_JSON)
                        .content("{\"fen\":\"" + INVALID_FEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid FEN piece character 'X'"));
    }

    @Test
    void streamRejectsUnknownPieceBeforeStartingAsyncWork() throws Exception {
        mockMvc.perform(get("/api/analysis/stream")
                        .param("fen", INVALID_FEN)
                        .accept(TEXT_EVENT_STREAM))
                .andExpect(status().isBadRequest())
                .andExpect(request().asyncNotStarted())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Invalid FEN piece character 'X'"));
    }

    @Test
    void analysisDoesNotTreatAnInternalIllegalArgumentAsABadFen() {
        AnalysisService service = mock(AnalysisService.class);
        IllegalArgumentException failure = new IllegalArgumentException("internal error");
        when(service.evaluate(VALID_FEN, 15, 1)).thenThrow(failure);
        MockMvc controller = MockMvcBuilders.standaloneSetup(new AnalysisController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        ServletException thrown = assertThrows(ServletException.class, () ->
                controller.perform(post("/api/analysis/evaluate")
                        .contentType(APPLICATION_JSON)
                        .content("{\"fen\":\"" + VALID_FEN + "\"}")));
        assertSame(failure, thrown.getCause());
    }
}
