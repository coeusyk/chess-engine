package coeusyk.game.chess.services;

import java.util.List;

public record GameStateResponse(
        String fen,
        List<String> moveHistory,
        String status,
        String activeColor,
        boolean canUndo,
        boolean canRedo
) {}
