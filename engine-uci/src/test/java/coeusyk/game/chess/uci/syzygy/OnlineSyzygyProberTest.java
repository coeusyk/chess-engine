package coeusyk.game.chess.uci.syzygy;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.syzygy.DTZResult;
import coeusyk.game.chess.core.syzygy.WDLResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Local HTTP server stands in for the tablebase, so nothing here touches the network. */
class OnlineSyzygyProberTest {
    private static final String KQK = "8/8/4k3/8/8/8/4K3/Q7 w - - 0 1";
    private static final String KQK_LATER = "8/8/4k3/8/8/8/4K3/Q7 w - - 7 12";
    private static final String KRK = "8/8/4k3/8/8/8/4K3/R7 w - - 0 1";
    private static final String BODY = "{\"category\":\"win\",\"dtz\":5,\"moves\":[{\"uci\":\"a1a5\",\"category\":\"loss\"}]}";

    private HttpServer server;
    private final AtomicInteger redirects = new AtomicInteger();
    private final AtomicInteger answers = new AtomicInteger();
    private String endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/standard", exchange -> {
            redirects.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/final?" + exchange.getRequestURI().getRawQuery());
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            answers.incrementAndGet();
            respond(exchange);
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/standard";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    void defaultEndpointIsHttps() {
        assertTrue(OnlineSyzygyProber.DEFAULT_ENDPOINT.startsWith("https://tablebase.lichess.ovh/"));
    }

    @Test
    void followsRedirectsInsteadOfReturningInvalid() {
        try (OnlineSyzygyProber prober = new OnlineSyzygyProber(true, endpoint)) {
            assertEquals(WDLResult.win(), prober.probeWDL(new Board(KQK)));
            assertEquals(1, redirects.get());
            assertEquals(1, answers.get());
        }
    }

    @Test
    void oneProberReusesItsCacheAcrossProbesAndMoveCounters() {
        try (OnlineSyzygyProber prober = new OnlineSyzygyProber(true, endpoint)) {
            prober.probeWDL(new Board(KQK));
            DTZResult dtz = prober.probeDTZ(new Board(KQK_LATER));
            assertTrue(dtz.valid());
            assertEquals("a1a5", dtz.bestMoveUci());
            assertEquals(1, answers.get(), "the same position must not be fetched twice");
        }
    }

    @Test
    void closeStopsRequestsAndProbesAnswerInvalid() {
        OnlineSyzygyProber prober = new OnlineSyzygyProber(true, endpoint);
        prober.close();
        assertEquals(WDLResult.INVALID, prober.probeWDL(new Board(KRK)));
        assertEquals(0, redirects.get());
    }

    @Test
    void asksTheSearchToProbeAtTheRootOnly() {
        try (OnlineSyzygyProber prober = new OnlineSyzygyProber(true, endpoint)) {
            assertFalse(prober.allowsInteriorProbing());
        }
    }
}
