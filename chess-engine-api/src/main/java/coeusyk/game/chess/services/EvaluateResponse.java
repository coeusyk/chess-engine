package coeusyk.game.chess.services;

import java.util.List;

public record EvaluateResponse(
        MoveDto bestMove,
        ScoreInfo score,
        int depth,
        long nodes,
        long nps,
        List<MoveDto> pv,
        List<LineInfo> lines
) {}
