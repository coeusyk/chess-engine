package coeusyk.game.chess.services;

import java.util.List;

public record EvaluateResponse(
        String bestMove,
        ScoreInfo score,
        int depth,
        long nodes,
        long nps,
        List<String> pv,
        List<LineInfo> lines
) {}
