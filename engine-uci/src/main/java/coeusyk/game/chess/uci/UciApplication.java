package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.search.TimeManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class UciApplication {
    private static final String ENGINE_NAME = "ChessEngine-UCI";
    private static final String ENGINE_AUTHOR = "coeusyk";

    private Board board = new Board();

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile boolean searchRunning = false;
    private volatile Move latestIterativeBestMove;

    public static void main(String[] args) throws IOException {
        new UciApplication().run();
    }

    private void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            if ("uci".equals(line)) {
                System.out.println("id name " + ENGINE_NAME);
                System.out.println("id author " + ENGINE_AUTHOR);
                System.out.println("uciok");
            } else if ("isready".equals(line)) {
                System.out.println("readyok");
            } else if ("ucinewgame".equals(line)) {
                stopRequested.set(true);
                board = new Board();
            } else if (line.startsWith("position")) {
                stopRequested.set(true);
                handlePosition(line);
            } else if (line.startsWith("setoption")) {
                // Phase 2 scope: ignored, wired later in Phase 5.
            } else if (line.startsWith("go")) {
                handleGo(line);
            } else if ("stop".equals(line)) {
                stopRequested.set(true);
                emitBestMove(latestIterativeBestMove);
            } else if ("quit".equals(line)) {
                stopRequested.set(true);
                break;
            }

            System.out.flush();
        }
    }

    private void handlePosition(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length < 2) {
            return;
        }

        int index = 1;
        if ("startpos".equals(parts[index])) {
            board = new Board();
            index++;
        } else if ("fen".equals(parts[index])) {
            if (parts.length < index + 7) {
                return;
            }
            String fen = String.join(" ",
                    parts[index + 1],
                    parts[index + 2],
                    parts[index + 3],
                    parts[index + 4],
                    parts[index + 5],
                    parts[index + 6]);
            board = new Board(fen);
            index += 7;
        } else {
            return;
        }

        if (index < parts.length && "moves".equals(parts[index])) {
            index++;
            for (; index < parts.length; index++) {
                Move move = findLegalMoveByUci(parts[index]);
                if (move == null) {
                    return;
                }
                board.makeMove(move);
            }
        }
    }

    private void handleGo(String command) {
        if (searchRunning) {
            stopRequested.set(true);
        }

        stopRequested.set(false);
        latestIterativeBestMove = null;
        searchRunning = true;

        try {
            String[] parts = command.split("\\s+");
            Searcher searcher = new Searcher();
            SearchResult result;

            if (contains(parts, "movetime")) {
                long movetime = parseLongArg(parts, "movetime", 1000L);
                TimeManager manager = new TimeManager();
                manager.configureMovetime(movetime);
                result = searcher.searchWithTimeManager(
                        board,
                        64,
                        manager,
                        stopRequested::get,
                        this::printInfoLine
                );
            } else if (contains(parts, "wtime") || contains(parts, "btime")) {
                long wtime = parseLongArg(parts, "wtime", 0L);
                long btime = parseLongArg(parts, "btime", 0L);
                long winc = parseLongArg(parts, "winc", 0L);
                long binc = parseLongArg(parts, "binc", 0L);

                TimeManager manager = new TimeManager();
                manager.configureClock(board.getActiveColor(), wtime, btime, winc, binc);
                result = searcher.searchWithTimeManager(
                        board,
                        64,
                        manager,
                        stopRequested::get,
                        this::printInfoLine
                );
            } else {
                int depth = (int) parseLongArg(parts, "depth", 4L);
                result = searcher.iterativeDeepening(
                        board,
                        Math.max(1, depth),
                        stopRequested::get,
                        stopRequested::get,
                        this::printInfoLine
                );
            }

            Move bestMove = result.bestMove() != null ? result.bestMove() : latestIterativeBestMove;
            emitBestMove(bestMove);
        } finally {
            searchRunning = false;
            System.out.flush();
        }
    }

    private void printInfoLine(int depth, int scoreCp, Move bestMove) {
        latestIterativeBestMove = bestMove;
        System.out.println("info depth " + depth + " score cp " + scoreCp);
    }

    private void emitBestMove(Move move) {
        if (move == null) {
            System.out.println("bestmove 0000");
        } else {
            System.out.println("bestmove " + moveToUci(move));
        }
    }

    private Move findLegalMoveByUci(String uciMove) {
        List<Move> legalMoves = new MovesGenerator(board).getActiveMoves(board.getActiveColor());
        for (Move move : legalMoves) {
            if (moveToUci(move).equals(uciMove)) {
                return move;
            }
        }
        return null;
    }

    private String moveToUci(Move move) {
        StringBuilder builder = new StringBuilder();
        builder.append(squareToUci(move.startSquare));
        builder.append(squareToUci(move.targetSquare));

        if ("promote-q".equals(move.reaction)) builder.append('q');
        if ("promote-r".equals(move.reaction)) builder.append('r');
        if ("promote-b".equals(move.reaction)) builder.append('b');
        if ("promote-n".equals(move.reaction)) builder.append('n');

        return builder.toString();
    }

    private String squareToUci(int square) {
        int file = square % 8;
        int rank = 8 - (square / 8);
        char fileChar = (char) ('a' + file);
        return "" + fileChar + rank;
    }

    private boolean contains(String[] parts, String token) {
        for (String part : parts) {
            if (token.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private long parseLongArg(String[] parts, String key, long fallback) {
        for (int i = 0; i < parts.length - 1; i++) {
            if (key.equals(parts[i])) {
                try {
                    return Long.parseLong(parts[i + 1]);
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
