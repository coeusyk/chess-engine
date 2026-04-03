package coeusyk.game.chess;

import coeusyk.game.chess.core.bitboard.BitboardPosition;
import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@SuppressWarnings("null")
class Phase0StabilizationTests {

    private static final String FEN_GAME_A = "8/8/8/8/8/8/8/K6k w - - 0 1";
    private static final String FEN_GAME_B = "8/8/8/8/8/8/8/k6K w - - 0 1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void parallelGameSessionsRemainIsolated() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<String> gameATask = () -> runGameFlow("game-a", FEN_GAME_A, startLatch);
        Callable<String> gameBTask = () -> runGameFlow("game-b", FEN_GAME_B, startLatch);

        Future<String> gameAResult = executorService.submit(gameATask);
        Future<String> gameBResult = executorService.submit(gameBTask);

        startLatch.countDown();

        String gameASetup = gameAResult.get(10, TimeUnit.SECONDS);
        String gameBSetup = gameBResult.get(10, TimeUnit.SECONDS);

        executorService.shutdownNow();

        assertTrue(gameASetup.contains("\"grid\":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,14,0,0,0,0,0,0,22]"));
        assertTrue(gameBSetup.contains("\"grid\":[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,22,0,0,0,0,0,0,14]"));
    }

    @Test
    void boardToBitboardSnapshotPreservesPieceAndOccupancyState() {
        Board board = new Board(FEN_GAME_A);

        BitboardPosition bitboardPosition = board.toBitboardPosition();

        assertEquals(1L << 56, bitboardPosition.whiteKing);
        assertEquals(1L << 63, bitboardPosition.blackKing);
        assertEquals(0L, bitboardPosition.whitePawns);
        assertEquals(0L, bitboardPosition.blackPawns);
        assertEquals((1L << 56) | (1L << 63), bitboardPosition.allOccupancy);
        assertTrue(bitboardPosition.whiteToMove);
        assertEquals(-1, bitboardPosition.enPassantSquare);
        assertEquals(0, bitboardPosition.castlingRights);
    }

    private String runGameFlow(String gameId, String fen, CountDownLatch startLatch) throws Exception {
        if (!startLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting for start latch");
        }

        mockMvc.perform(put("/engine/load-fen")
                        .param("gameId", gameId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fenString\":\"" + fen + "\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/engine/setup").param("gameId", gameId))
                .andExpect(status().isOk())
                .andReturn();

        return result.getResponse().getContentAsString();
    }
}
