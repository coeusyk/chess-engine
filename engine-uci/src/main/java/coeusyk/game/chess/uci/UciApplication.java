package coeusyk.game.chess.uci;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.search.SearchResult;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.search.TimeManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class UciApplication {
    private static final String ENGINE_NAME = "Vex";
    private static final String ENGINE_AUTHOR = "coeusyk";
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    private static final int MAX_SEARCH_DEPTH = 127;

    private Board board = new Board();
    private int multiPV = 1;

    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile boolean searchRunning = false;
    private volatile Move latestIterativeBestMove;
    private volatile Thread searchThread;

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
                System.out.println("option name MultiPV type spin default 1 min 1 max 500");
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
                handleSetOption(line);
            } else if (line.startsWith("go")) {
                handleGo(line);
            } else if ("stop".equals(line)) {
                stopRequested.set(true);
                if (!searchRunning) {
                    emitBestMove(latestIterativeBestMove);
                }
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

    private void handleSetOption(String command) {
        String lower = command.toLowerCase();
        if (lower.contains("name multipv") && lower.contains("value")) {
            String[] parts = command.split("\\s+");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("value".equalsIgnoreCase(parts[i])) {
                    try {
                        int value = Integer.parseInt(parts[i + 1]);
                        multiPV = Math.max(1, value);
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                }
            }
        }
    }

    private void handleGo(String command) {
        // Keep input processing responsive by running search on a worker thread.
        if (searchRunning) {
            stopRequested.set(true);
            return;
        }

        stopRequested.set(false);
        latestIterativeBestMove = null;
        searchRunning = true;

        String positionSnapshot = board.boardStates.get(board.boardStates.size() - 1);
        Thread worker = new Thread(() -> runSearch(command, new Board(positionSnapshot)), "uci-search-thread");
        worker.setDaemon(true);
        searchThread = worker;
        worker.start();
    }

    private void runSearch(String command, Board searchBoard) {
        try {
            String[] parts = command.split("\\s+");
            Searcher searcher = new Searcher();
            if (multiPV > 1) {
                searcher.setMultiPV(multiPV);
            }
            SearchResult result;

            if (contains(parts, "movetime")) {
                long movetime = parseLongArg(parts, "movetime", 1000L);
                TimeManager manager = new TimeManager();
                manager.configureMovetime(movetime);
                result = searcher.searchWithTimeManager(
                        searchBoard,
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
                manager.configureClock(searchBoard.getActiveColor(), wtime, btime, winc, binc);
                result = searcher.searchWithTimeManager(
                        searchBoard,
                        64,
                        manager,
                        stopRequested::get,
                        this::printInfoLine
                );
            } else {
                int depth = parseDepthArg(parts, "depth", 4);
                result = searcher.iterativeDeepening(
                        searchBoard,
                        depth,
                        stopRequested::get,
                        stopRequested::get,
                        this::printInfoLine
                );
            }

            Move bestMove = result.bestMove() != null ? result.bestMove() : latestIterativeBestMove;
            emitBestMove(bestMove);
        } finally {
            searchRunning = false;
            searchThread = null;
            System.out.flush();
        }
    }

    private void printInfoLine(IterationInfo info) {
        if (info.multipv() == 1 && !info.pv().isEmpty()) {
            latestIterativeBestMove = info.pv().get(0);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("info depth ").append(info.depth());
        sb.append(" seldepth ").append(info.seldepth());

        if (multiPV > 1) {
            sb.append(" multipv ").append(info.multipv());
        }

        int score = info.scoreCp();
        if (Math.abs(score) >= MATE_SCORE - MAX_PLY) {
            int mateInPly = MATE_SCORE - Math.abs(score);
            int mateInMoves = (mateInPly + 1) / 2;
            if (score < 0) {
                mateInMoves = -mateInMoves;
            }
            sb.append(" score mate ").append(mateInMoves);
        } else {
            sb.append(" score cp ").append(score);
        }

        sb.append(" nodes ").append(info.nodes());

        long timeMs = info.timeMs();
        long nps = timeMs > 0 ? info.nodes() * 1000L / timeMs : 0;
        sb.append(" nps ").append(nps);
        sb.append(" time ").append(timeMs);
        sb.append(" hashfull ").append(info.hashfull());

        if (!info.pv().isEmpty()) {
            sb.append(" pv");
            for (Move move : info.pv()) {
                sb.append(' ').append(moveToUci(move));
            }
        }

        System.out.println(sb.toString());
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

    private int parseDepthArg(String[] parts, String key, int fallback) {
        long rawDepth = parseLongArg(parts, key, fallback);
        long clampedDepth = Math.max(1L, Math.min((long) MAX_SEARCH_DEPTH, rawDepth));
        return (int) clampedDepth;
    }
}
