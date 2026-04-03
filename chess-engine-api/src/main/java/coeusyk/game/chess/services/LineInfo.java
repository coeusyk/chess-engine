package coeusyk.game.chess.services;

import java.util.List;

public record LineInfo(int rank, ScoreInfo score, List<MoveDto> pv) {}
