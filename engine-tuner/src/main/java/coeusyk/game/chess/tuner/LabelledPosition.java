package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;

/**
 * A chess position paired with its game outcome from White's perspective:
 *   1.0 = White win, 0.5 = draw, 0.0 = Black win (White loss).
 */
public record LabelledPosition(Board board, double outcome) {}
