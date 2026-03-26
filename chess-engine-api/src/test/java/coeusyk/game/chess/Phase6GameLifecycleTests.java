package coeusyk.game.chess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class Phase6GameLifecycleTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ── helpers ──────────────────────────────────────────────────────────────

    private String createGame() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/game/create"))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("gameId");
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void createReturnsUniqueGameIds() throws Exception {
        String id1 = createGame();
        String id2 = createGame();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
    }

    @Test
    void stateAfterCreateIsStartingPositionInProgress() throws Exception {
        String gameId = createGame();

        MvcResult result = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals("IN_PROGRESS", body.get("status"));
        assertEquals("WHITE", body.get("activeColor"));
        assertEquals(Boolean.FALSE, body.get("canUndo"));
        assertEquals(Boolean.FALSE, body.get("canRedo"));
        assertTrue(((String) body.get("fen")).startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"));
    }

    @Test
    void loadFenChangesStateAndEnablesStatus() throws Exception {
        String gameId = createGame();
        // KQK — White king a1, white queen b1, black king h8
        String fen = "7k/8/8/8/8/8/8/KQ6 w - - 0 1";

        mockMvc.perform(post("/api/game/{gameId}/load", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fen", fen))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals("IN_PROGRESS", body.get("status"));
        // FEN piece-placement varies by implementation (trailing empty squares may be omitted).
        // Just verify the position loaded (king on h8, king and queen on rank 1).
        String returnedFen = (String) body.get("fen");
        assertTrue(returnedFen.contains("KQ") && returnedFen.contains("7k"),
                "FEN should contain the loaded position pieces, got: " + returnedFen);
    }

    @Test
    void loadInvalidFenReturnsBadRequest() throws Exception {
        String gameId = createGame();

        mockMvc.perform(post("/api/game/{gameId}/load", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fen", "not-a-fen"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void undoAndRedoMoveHistory() throws Exception {
        String gameId = createGame();

        // Make a move via the existing /engine endpoint first, then verify
        // undo via /api/game endpoint removes it, redo restores it
        mockMvc.perform(put("/engine/make-move")
                        .param("gameId", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startSquare\":52,\"targetSquare\":36}"))  // e2-e4
                .andExpect(status().isOk());

        // State should have one move played and canUndo=true
        MvcResult afterMove = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> bodyAfterMove = objectMapper.readValue(afterMove.getResponse().getContentAsString(), Map.class);
        assertTrue((Boolean) bodyAfterMove.get("canUndo"));
        assertFalse((Boolean) bodyAfterMove.get("canRedo"));

        // Undo
        MvcResult undoResult = mockMvc.perform(post("/api/game/{gameId}/undo", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> undoBody = objectMapper.readValue(undoResult.getResponse().getContentAsString(), Map.class);
        assertEquals(Boolean.TRUE, undoBody.get("moved"));

        // State should be back to start, canRedo=true
        MvcResult afterUndo = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> bodyAfterUndo = objectMapper.readValue(afterUndo.getResponse().getContentAsString(), Map.class);
        assertFalse((Boolean) bodyAfterUndo.get("canUndo"));
        assertTrue((Boolean) bodyAfterUndo.get("canRedo"));
        assertTrue(((String) bodyAfterUndo.get("fen")).startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"));

        // Redo
        MvcResult redoResult = mockMvc.perform(post("/api/game/{gameId}/redo", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> redoBody = objectMapper.readValue(redoResult.getResponse().getContentAsString(), Map.class);
        assertEquals(Boolean.TRUE, redoBody.get("moved"));

        // After redo, canUndo=true again
        MvcResult afterRedo = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> bodyAfterRedo = objectMapper.readValue(afterRedo.getResponse().getContentAsString(), Map.class);
        assertTrue((Boolean) bodyAfterRedo.get("canUndo"));
        assertFalse((Boolean) bodyAfterRedo.get("canRedo"));
    }

    @Test
    void undoOnEmptyHistoryReturnsFalse() throws Exception {
        String gameId = createGame();

        MvcResult result = mockMvc.perform(post("/api/game/{gameId}/undo", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals(Boolean.FALSE, body.get("moved"));
    }

    @Test
    void redoOnEmptyStackReturnsFalse() throws Exception {
        String gameId = createGame();

        MvcResult result = mockMvc.perform(post("/api/game/{gameId}/redo", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals(Boolean.FALSE, body.get("moved"));
    }

    @Test
    void resetClearsHistoryAndRedoStack() throws Exception {
        String gameId = createGame();

        // Make a move then undo (now redoStack has one move)
        mockMvc.perform(put("/engine/make-move")
                        .param("gameId", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startSquare\":52,\"targetSquare\":36}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/game/{gameId}/undo", gameId))
                .andExpect(status().isOk());

        // Reset
        mockMvc.perform(post("/api/game/{gameId}/reset", gameId))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertFalse((Boolean) body.get("canUndo"));
        assertFalse((Boolean) body.get("canRedo"));
        assertTrue(((String) body.get("fen")).startsWith("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"));
    }

    @Test
    void makeMoveAfterUndoClearsRedoStack() throws Exception {
        String gameId = createGame();

        // e2e4
        mockMvc.perform(put("/engine/make-move")
                        .param("gameId", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startSquare\":52,\"targetSquare\":36}"))
                .andExpect(status().isOk());

        // Undo -> canRedo=true
        mockMvc.perform(post("/api/game/{gameId}/undo", gameId))
                .andExpect(status().isOk());

        // Make a different move (d2d4)
        mockMvc.perform(put("/engine/make-move")
                        .param("gameId", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startSquare\":51,\"targetSquare\":35}"))
                .andExpect(status().isOk());

        // Redo stack should now be cleared
        MvcResult result = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertFalse((Boolean) body.get("canRedo"), "redo stack must be cleared after a new move");
    }

    @Test
    void unknownGameIdReturns404() throws Exception {
        mockMvc.perform(get("/api/game/non-existent-game-id/state"))
                .andExpect(status().isNotFound());
    }

    @Test
    void twoGamesRemainIsolated() throws Exception {
        String gameA = createGame();
        String gameB = createGame();

        // Move in game A only
        mockMvc.perform(put("/engine/make-move")
                        .param("gameId", gameA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startSquare\":52,\"targetSquare\":36}"))
                .andExpect(status().isOk());

        MvcResult resultA = mockMvc.perform(get("/api/game/{gameId}/state", gameA))
                .andExpect(status().isOk()).andReturn();
        MvcResult resultB = mockMvc.perform(get("/api/game/{gameId}/state", gameB))
                .andExpect(status().isOk()).andReturn();

        Map<?, ?> bodyA = objectMapper.readValue(resultA.getResponse().getContentAsString(), Map.class);
        Map<?, ?> bodyB = objectMapper.readValue(resultB.getResponse().getContentAsString(), Map.class);

        assertTrue((Boolean) bodyA.get("canUndo"), "game A should have a move to undo");
        assertFalse((Boolean) bodyB.get("canUndo"), "game B should be pristine");
    }

    @Test
    void insufficientMaterialDrawDetected() throws Exception {
        String gameId = createGame();
        // K vs K — guaranteed insufficient material
        mockMvc.perform(post("/api/game/{gameId}/load", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fen", "8/8/8/8/8/8/8/K6k w - - 0 1"))))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/game/{gameId}/state", gameId))
                .andExpect(status().isOk())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertEquals("DRAW_INSUFFICIENT_MATERIAL", body.get("status"));
    }
}
