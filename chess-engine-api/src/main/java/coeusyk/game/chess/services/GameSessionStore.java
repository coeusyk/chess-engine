package coeusyk.game.chess.services;

import org.springframework.stereotype.Component;

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

    public String normalize(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return DEFAULT_GAME_ID;
        }

        return gameId.trim();
    }
}
