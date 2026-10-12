package coeusyk.game.chess.core.selfplay.vspr;

import java.util.List;

/** A fully decoded VSPR stream: the file header plus every game frame it contains. */
public record VsprFile(VsprHeader header, List<GameFrame> frames) {
}
