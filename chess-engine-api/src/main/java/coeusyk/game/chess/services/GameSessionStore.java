package coeusyk.game.chess.services;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory session registry bounded by {@link #MAX_SESSIONS}. When the cap is exceeded the least
 * recently used session is dropped, except the legacy default session, which the unqualified
 * {@code /engine/*} endpoints depend on. A dropped id answers {@link GameNotFoundException} on the
 * {@code /api/game/{id}} endpoints, the same as an id that never existed. The legacy endpoints go
 * through {@link #getOrCreate}, which has always created a session for any id, so there a dropped
 * id comes back as a fresh game. There is no per-client quota: enough distinct ids can push other
 * sessions out, which is the price of a hard memory bound.
 */
@Component
public class GameSessionStore {
    public static final String DEFAULT_GAME_ID = "default";
    public static final int MAX_SESSIONS = 1000;

    private final int maxSessions;
    // Access-ordered, so iteration starts at the least recently used entry. Every access mutates
    // the order, which is why all methods below are synchronized.
    private final Map<String, GameSession> sessions = new LinkedHashMap<>(16, 0.75f, true);

    public GameSessionStore() {
        this(MAX_SESSIONS);
    }

    GameSessionStore(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public synchronized GameSession getOrCreate(String gameId) {
        String normalizedId = normalize(gameId);
        GameSession session = sessions.computeIfAbsent(normalizedId, key -> new GameSession());
        evictIfOverCapacity();
        return session;
    }

    public synchronized String create() {
        String gameId = UUID.randomUUID().toString();
        sessions.put(gameId, new GameSession());
        evictIfOverCapacity();
        return gameId;
    }

    public synchronized GameSession get(String gameId) {
        String normalizedId = normalize(gameId);
        GameSession session = sessions.get(normalizedId);
        if (session == null) {
            throw new GameNotFoundException("Game not found: " + normalizedId);
        }
        return session;
    }

    synchronized int size() {
        return sessions.size();
    }

    private void evictIfOverCapacity() {
        Iterator<String> oldestFirst = sessions.keySet().iterator();
        while (sessions.size() > maxSessions && oldestFirst.hasNext()) {
            if (!DEFAULT_GAME_ID.equals(oldestFirst.next())) {
                oldestFirst.remove();
            }
        }
    }

    String normalize(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return DEFAULT_GAME_ID;
        }
        return gameId.trim();
    }
}
