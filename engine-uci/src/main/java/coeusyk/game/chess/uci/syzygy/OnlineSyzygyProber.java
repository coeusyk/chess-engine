package coeusyk.game.chess.uci.syzygy;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.syzygy.DTZResult;
import coeusyk.game.chess.core.syzygy.SyzygyProber;
import coeusyk.game.chess.core.syzygy.WDLResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Syzygy tablebase prober using the lichess online API.
 * Endpoint: http://tablebase.lichess.ovh/standard?fen=...
 *
 * Results are cached by position FEN to avoid repeated API calls.
 */
public class OnlineSyzygyProber implements SyzygyProber {

    private static final String API_BASE = "http://tablebase.lichess.ovh/standard";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, CachedProbe> cache = new ConcurrentHashMap<>();
    private final boolean respect50MoveRule;

    public OnlineSyzygyProber(boolean respect50MoveRule) {
        this.respect50MoveRule = respect50MoveRule;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
    }

    @Override
    public WDLResult probeWDL(Board board) {
        String posKey = positionKey(board);
        CachedProbe cached = cache.get(posKey);
        if (cached != null) {
            return cached.wdl;
        }

        String json = fetchApi(board.toFen());
        if (json == null) {
            return WDLResult.INVALID;
        }

        CachedProbe probe = parseResponse(json);
        cache.put(posKey, probe);
        return probe.wdl;
    }

    @Override
    public DTZResult probeDTZ(Board board) {
        String posKey = positionKey(board);
        CachedProbe cached = cache.get(posKey);
        if (cached != null) {
            return cached.dtz;
        }

        String json = fetchApi(board.toFen());
        if (json == null) {
            return DTZResult.INVALID;
        }

        CachedProbe probe = parseResponse(json);
        cache.put(posKey, probe);
        return probe.dtz;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private String fetchApi(String fen) {
        try {
            String encodedFen = fen.replace(' ', '_');
            URI uri = URI.create(API_BASE + "?fen=" + encodedFen);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract a position key from the FEN (first 4 fields: pieces, active color,
     * castling, EP square) to use as a cache key. Halfmove clock and fullmove
     * number don't affect tablebase results.
     */
    private String positionKey(Board board) {
        String fen = board.toFen();
        String[] parts = fen.split(" ");
        if (parts.length >= 4) {
            return parts[0] + " " + parts[1] + " " + parts[2] + " " + parts[3];
        }
        return fen;
    }

    private CachedProbe parseResponse(String json) {
        try {
            String category = extractStringField(json, "category");
            WDLResult wdl = categoryToWDL(category);

            String dtzStr = extractRawField(json, "dtz");
            int dtzValue = 0;
            if (dtzStr != null && !"null".equals(dtzStr)) {
                dtzValue = Integer.parseInt(dtzStr.trim());
            }

            // Find the best move from the moves array
            String bestMoveUci = extractBestMoveFromMoves(json, category);
            WDLResult.WDL dtzWdl = wdl.valid() ? wdl.wdl() : null;
            DTZResult dtz = (bestMoveUci != null)
                    ? new DTZResult(bestMoveUci, dtzValue, dtzWdl, true)
                    : new DTZResult(null, dtzValue, dtzWdl, wdl.valid());

            return new CachedProbe(wdl, dtz);
        } catch (Exception e) {
            return new CachedProbe(WDLResult.INVALID, DTZResult.INVALID);
        }
    }

    private WDLResult categoryToWDL(String category) {
        if (category == null) {
            return WDLResult.INVALID;
        }
        return switch (category) {
            case "win", "syzygy-win" -> WDLResult.win();
            case "maybe-win", "cursed-win" -> respect50MoveRule ? WDLResult.draw() : WDLResult.win();
            case "draw" -> WDLResult.draw();
            case "maybe-loss", "blessed-loss" -> respect50MoveRule ? WDLResult.draw() : WDLResult.loss();
            case "loss", "syzygy-loss" -> WDLResult.loss();
            default -> WDLResult.INVALID;
        };
    }

    /**
     * Extract the best move UCI string from the "moves" array in the JSON.
     * The API returns moves sorted best-first, so we take the first one.
     */
    private String extractBestMoveFromMoves(String json, String rootCategory) {
        int movesIdx = json.indexOf("\"moves\"");
        if (movesIdx < 0) {
            return null;
        }
        int arrayStart = json.indexOf('[', movesIdx);
        if (arrayStart < 0) {
            return null;
        }

        // Find first "uci" field in the moves array
        int uciIdx = json.indexOf("\"uci\"", arrayStart);
        if (uciIdx < 0) {
            return null;
        }
        return extractStringFieldAt(json, uciIdx);
    }

    /**
     * Extract a string field value given that fieldNameIdx points to the opening quote of the field name.
     */
    private String extractStringFieldAt(String json, int fieldNameIdx) {
        int colonIdx = json.indexOf(':', fieldNameIdx);
        if (colonIdx < 0) {
            return null;
        }
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) {
            return null;
        }
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) {
            return null;
        }
        return json.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Extract a top-level string field value from JSON by field name.
     */
    private String extractStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        return extractStringFieldAt(json, idx);
    }

    /**
     * Extract a top-level raw (non-string) field value from JSON by field name.
     */
    private String extractRawField(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colonIdx = json.indexOf(':', idx);
        if (colonIdx < 0) {
            return null;
        }
        int start = colonIdx + 1;
        while (start < json.length() && json.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ']') {
                break;
            }
            end++;
        }
        return json.substring(start, end).trim();
    }

    private record CachedProbe(WDLResult wdl, DTZResult dtz) {}
}
