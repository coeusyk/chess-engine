package coeusyk.game.chess.services;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class GameSessionStore {
    public static final String DEFAULT_GAME_ID = "default";

    private final ConcurrentMap<String, GameSession> sessions = new ConcurrentHashMap<>();

    public GameSession getOrCreate(String gameId) {
        String normalizedId = normalize(gameId);
        return sessions.computeIfAbsent(normalizedId, key -> new GameSession());
    }

    public String create() {
        String gameId = UUID.randomUUID().toString();
        sessions.put(gameId, new GameSession());
        return gameId;
    }

    public GameSession get(String gameId) {
        String normalizedId = normalize(gameId);
        GameSession session = sessions.get(normalizedId);
        if (session == null) {
            throw new GameNotFoundException("Game not found: " + normalizedId);
        }
        return session;
    }

    String normalize(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return DEFAULT_GAME_ID;
        }
        return gameId.trim();
    }
}
