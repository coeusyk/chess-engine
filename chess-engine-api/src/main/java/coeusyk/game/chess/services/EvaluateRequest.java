package coeusyk.game.chess.services;

public record EvaluateRequest(String fen, Integer depth, Integer multiPv) {}
