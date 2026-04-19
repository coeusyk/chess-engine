package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;

import java.util.ArrayList;

/**
 * Lightweight internal smoke-test harness that plays a short self-play match
 * between two parameter sets using a fixed-depth α-β minimax backed by
 * {@link TunerEvaluator}.  No external process, no real time control.
 *
 * <p>This is deliberately a shallow, fast proxy — not a replacement for SPRT.
 * Its only purpose is to catch obviously broken parameter sets (negative material
 * values, inverted eval, etc.) before they waste SPRT time.
 *
 * <p>LOS is computed as:
 * <pre>  LOS = Φ( (wins - losses) / sqrt(wins + losses) )  </pre>
 * where Φ is the standard normal CDF, approximated by a fast tabulation.
 */
public final class SmokeTestRunner {

    /** Maximum plies per game before the game is adjudicated as a draw. */
    private static final int MAX_PLIES = 200;

    /** Score magnitude (from side-to-move's perspective) at which the game is
     *  adjudicated as a win for the leading side (+/- 600 cp). */
    private static final int ADJUDICATION_CP = 600;

    /** Consecutive plies above ADJUDICATION_CP before adjudication fires. */
    private static final int ADJUDICATION_PLIES = 5;

    private static final int INF = 1_000_000;

    // A handful of opening FENs to provide variety without an opening book file.
    // These are standard middlegame positions from public domain chess theory.
    private static final String[] OPENING_FENS = {
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1",
        "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2",
        "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
        "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
        "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 3 4",
        "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2",
        "rnbqkbnr/ppp1pppp/8/3p4/3PP3/8/PPP2PPP/RNBQKBNR b KQkq d3 0 2",
        "rnbqkb1r/ppp1pppp/5n2/3p4/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 1 3",
        "rnbqkbnr/pppp1ppp/8/4p3/3PP3/8/PPP2PPP/RNBQKBNR b KQkq d3 0 2",
        "r1bqkbnr/pppp1ppp/2n5/4p3/3PP3/8/PPP2PPP/RNBQKBNR w KQkq - 1 3",
    };

    /**
     * Result of a smoke-test run.
     *
     * @param wins           games won by the candidate (tuned) params
     * @param draws          drawn games
     * @param losses         games lost by the candidate
     * @param los            likelihood-of-superiority in [0.0, 1.0]
     */
    public record SmokeResult(int wins, int draws, int losses, double los) {
        /** Returns "tuned" score (wins + draws/2) over total games. */
        public double score() {
            int total = wins + draws + losses;
            return total == 0 ? 0.5 : (wins + 0.5 * draws) / total;
        }
    }

    private SmokeTestRunner() {}

    /**
     * Runs {@code numGames} self-play games between {@code candidateParams} and
     * {@code baselineParams} at fixed depth {@code depth}.  Games alternate colours
     * to control for first-mover advantage.
     *
     * @param candidateParams tuned parameter array (the output to validate)
     * @param baselineParams  initial parameter array passed into the tuner
     * @param numGames        number of games to play (must be even; rounded up)
     * @param depth           fixed search depth per move
     * @return win/draw/loss results from the candidate's perspective
     */
    public static SmokeResult run(double[] candidateParams, double[] baselineParams,
                                  int numGames, int depth) {
        // Ensure even game count so each side plays equal games from each opening.
        int games = (numGames % 2 == 0) ? numGames : numGames + 1;
        int wins = 0, draws = 0, losses = 0;

        for (int g = 0; g < games; g++) {
            // Alternate which side plays candidate params
            boolean candidateIsWhite = (g % 2 == 0);
            String fen = OPENING_FENS[g % OPENING_FENS.length];

            GameOutcome outcome = playGame(fen, candidateParams, baselineParams,
                    candidateIsWhite, depth);
            switch (outcome) {
                case CANDIDATE_WIN -> wins++;
                case BASELINE_WIN  -> losses++;
                case DRAW          -> draws++;
            }
        }

        double los = computeLos(wins, losses);
        return new SmokeResult(wins, draws, losses, los);
    }

    // -----------------------------------------------------------------------
    // Internal game play
    // -----------------------------------------------------------------------

    private enum GameOutcome { CANDIDATE_WIN, BASELINE_WIN, DRAW }

    private static GameOutcome playGame(String fen, double[] candidateParams, double[] baselineParams,
                                        boolean candidateIsWhite, int depth) {
        Board board = new Board(fen);
        ArrayList<Move> moveBuffer = new ArrayList<>(64);
        int consecutiveHighScorePlies = 0;
        long[] positionHistory = new long[MAX_PLIES + 10];
        int histLen = 0;

        positionHistory[histLen++] = board.getZobristHash();

        for (int ply = 0; ply < MAX_PLIES; ply++) {
            // Determine whose params to use this move
            boolean isWhiteToMove = (board.getActiveColor() == 0); // 0 = WHITE
            double[] params = (isWhiteToMove == candidateIsWhite) ? candidateParams : baselineParams;

            // Generate legal moves
            moveBuffer.clear();
            MovesGenerator gen = new MovesGenerator(board, moveBuffer);
            ArrayList<Move> legalMoves = gen.getAllMoves();

            if (legalMoves.isEmpty()) {
                // No legal moves: checkmate (side to move is in check) or stalemate (draw)
                if (board.isActiveColorInCheck()) {
                    // Side to move is mated; the opponent wins
                    boolean whiteWon = !isWhiteToMove;
                    if (whiteWon == candidateIsWhite) return GameOutcome.CANDIDATE_WIN;
                    else return GameOutcome.BASELINE_WIN;
                }
                return GameOutcome.DRAW; // stalemate
            }

            // Repetition check
            long zkey = board.getZobristHash();
            int repCount = 0;
            for (int h = 0; h < histLen; h++) {
                if (positionHistory[h] == zkey) repCount++;
            }
            if (repCount >= 3) return GameOutcome.DRAW; // threefold repetition

            // Fifty-move rule
            if (board.getHalfmoveClock() >= 100) return GameOutcome.DRAW;

            // Choose best move using fixed-depth α-β
            Move bestMove = chooseBestMove(board, legalMoves, params, depth);

            // Adjudication: if the eval is very one-sided, end the game
            int evalScore = TunerEvaluator.evaluate(board, params);
            // evalScore is from White's perspective; convert to side-to-move perspective
            if (!isWhiteToMove) evalScore = -evalScore;
            if (Math.abs(evalScore) >= ADJUDICATION_CP) {
                consecutiveHighScorePlies++;
                if (consecutiveHighScorePlies >= ADJUDICATION_PLIES) {
                    boolean whiteWinning = (isWhiteToMove ? evalScore : -evalScore) > 0;
                    if (whiteWinning == candidateIsWhite) return GameOutcome.CANDIDATE_WIN;
                    else return GameOutcome.BASELINE_WIN;
                }
            } else {
                consecutiveHighScorePlies = 0;
            }

            board.makeMove(bestMove);
            if (histLen < positionHistory.length) {
                positionHistory[histLen++] = board.getZobristHash();
            }
        }

        return GameOutcome.DRAW; // exceeded max plies
    }

    /** Selects the best legal move using a simple fixed-depth negamax α-β. */
    private static Move chooseBestMove(Board board, ArrayList<Move> legalMoves,
                                       double[] params, int depth) {
        int bestScore = -INF;
        Move bestMove = legalMoves.get(0);

        for (int i = 0; i < legalMoves.size(); i++) {
            Move move = legalMoves.get(i);
            board.makeMove(move);
            int score;
            if (i == 0) {
                score = -negamax(board, depth - 1, -INF, INF, params);
            } else {
                score = -negamax(board, depth - 1, -bestScore - 1, -bestScore, params);
            }
            board.unmakeMove();
            if (score > bestScore) {
                bestScore = score;
                bestMove  = move;
            }
        }
        return bestMove;
    }

    /** Negamax α-β, using TunerEvaluator at leaf nodes. */
    private static int negamax(Board board, int depth, int alpha, int beta, double[] params) {
        if (depth <= 0) {
            boolean isWhite = (board.getActiveColor() == 0);
            int eval = TunerEvaluator.evaluate(board, params);
            return isWhite ? eval : -eval;
        }

        ArrayList<Move> moves = new MovesGenerator(board).getAllMoves();
        if (moves.isEmpty()) {
            return board.isActiveColorInCheck() ? -(INF - 100) : 0; // mate or stalemate
        }

        for (Move move : moves) {
            board.makeMove(move);
            int score = -negamax(board, depth - 1, -beta, -alpha, params);
            board.unmakeMove();
            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    // -----------------------------------------------------------------------
    // LOS calculation
    // -----------------------------------------------------------------------

    /**
     * Likelihood of Superiority using the normal approximation:
     * {@code LOS = Φ( (W - L) / sqrt(W + L) )}.
     * Returns 0.5 when W == L (or both are 0).
     */
    static double computeLos(int wins, int losses) {
        int n = wins + losses;
        if (n == 0) return 0.5;
        double z = (double)(wins - losses) / Math.sqrt(n);
        return normalCdf(z);
    }

    /** Fast rational approximation of the standard normal CDF (Abramowitz & Stegun 26.2.17). */
    private static double normalCdf(double z) {
        // Clamp to avoid underflow
        if (z < -6.0) return 0.0;
        if (z >  6.0) return 1.0;
        boolean negative = z < 0;
        if (negative) z = -z;
        double t = 1.0 / (1.0 + 0.2316419 * z);
        double poly = t * (0.319381530
                + t * (-0.356563782
                + t * (1.781477937
                + t * (-1.821255978
                + t *  1.330274429))));
        double pdf = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
        double cdf = 1.0 - pdf * poly;
        return negative ? 1.0 - cdf : cdf;
    }
}
