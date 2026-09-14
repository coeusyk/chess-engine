package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.models.Board;
import coeusyk.game.chess.core.models.Move;
import coeusyk.game.chess.core.movegen.MovesGenerator;
import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.search.Searcher;
import coeusyk.game.chess.core.selfplay.vspr.GameFrame;
import coeusyk.game.chess.core.selfplay.vspr.GameOutcome;
import coeusyk.game.chess.core.selfplay.vspr.PlayedMoveDecision;
import coeusyk.game.chess.core.selfplay.vspr.ScoreKind;
import coeusyk.game.chess.core.selfplay.vspr.SearchBudgetKind;
import coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind;
import coeusyk.game.chess.core.selfplay.vspr.TerminationReason;
import coeusyk.game.chess.core.selfplay.vspr.TrainingSample;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Owns one game's lifecycle, per DR-E9 sections 3/4/6: one persistent {@link Board} across the
 * whole game (real move history and search recursion share its stack, DR-E9 section 2 -- never
 * reset mid-game), one {@link Searcher}/evaluator pair, the game trajectory, terminal-rule
 * detection, the hard move cap, and on-trajectory sample creation. Does not decode or write
 * VSPR bytes itself (that is {@code VsprCodec}'s job, orchestrated by {@link SelfPlayCli}) and
 * does not select a generator or make an eligibility/promotion decision (that is
 * {@link EligibilitySmoke} and {@link GeneratorConfig}'s job, resolved before a {@code GameLoop}
 * is ever constructed).
 */
public final class GameLoop {

    // Duplicated from Searcher's own private constants (UciApplication.java does the same, for
    // the same reason: no public accessor exists, and the values are the search engine's own
    // wire-stable convention -- see printInfoLine's identical mate-window check).
    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;
    // Generous sanity bound on a raw scoreCp -- an int can't be NaN/Infinity, so "non-finite" here
    // means "wildly outside anything a real search could return," the same corruption check
    // EligibilitySmoke performs on its own smoke-search results.
    private static final int SANE_SCORE_BOUND = 1_000_000;

    private final GeneratorConfig config;
    private final Searcher searcher;
    private final MoveSelector moveSelector;
    private final CompletedRootCandidateAdapter candidateAdapter;

    public GameLoop(GeneratorConfig config, Searcher searcher, MoveSelector moveSelector) {
        if (config.searchBudgetKind() != SearchBudgetKind.DEPTH) {
            // Searcher's public entry points here take a ply depth, not a node or time budget --
            // wiring NODES/TIME_MS through searchWithTimeManager()/a node-limited variant is real
            // future work, not something to fake with an approximate depth conversion.
            throw new UnsupportedOperationException(
                    "GameLoop currently supports only SearchBudgetKind.DEPTH (got "
                            + config.searchBudgetKind() + ") -- Searcher has no node- or "
                            + "time-budget-limited iterativeDeepening() overload wired here yet");
        }
        this.config = config;
        this.searcher = searcher;
        this.moveSelector = moveSelector;
        this.candidateAdapter = new CompletedRootCandidateAdapter(1); // multiPV=1 for this vertical slice
    }

    /** Plays exactly one game from the standard starting position, honoring {@code config}'s
     * maxPlies/history-safety guard. Returns the finished {@link GameFrame}. Throws
     * {@link SelfPlayGenerationException} on any hard correctness failure -- the caller must not
     * catch it to skip this game and continue (#221 section 12). */
    public GameFrame playGame(long gameId, long gameSeed) {
        return playGame(gameId, gameSeed, new Board());
    }

    /** Same as {@link #playGame(long, long)}, but from an explicit starting {@link Board} --
     * exists for deterministic testing of terminal-state detection (checkmate/stalemate/
     * threefold/fifty-move/insufficient-material positions constructed directly, rather than
     * relying on search to reach them), not part of the normal generation path. */
    public GameFrame playGame(long gameId, long gameSeed, Board board) {
        List<PlayedMoveDecision> playedMoves = new ArrayList<>();
        List<TrainingSample> samples = new ArrayList<>();

        int ply = 0;
        GameOutcome outcome;
        TerminationReason reason;

        while (true) {
            TerminalCheck terminal = checkNaturalTermination(board);
            if (terminal != null) {
                outcome = terminal.outcome;
                reason = terminal.reason;
                break;
            }
            if (ply >= config.maxPlies()) {
                outcome = GameOutcome.UNRESOLVED;
                reason = TerminationReason.MOVE_CAP;
                break;
            }
            // Defensive runtime guard (section 5) -- checked before every root search, never
            // relied on as "already proven safe by construction" (GeneratorConfig's own compact
            // constructor already rejects a config that couldn't satisfy this, but a persistent
            // Board's real stack occupancy is re-checked here too, since GameLoop is the only
            // thing that knows the actual retained-history count at this exact moment).
            if (ply + 1 + GeneratorConfig.SEARCH_OCCUPANCY_MARGIN >= Board.UNMAKE_POOL_SIZE) {
                throw new SelfPlayGenerationException(
                        SelfPlayGenerationException.Reason.HISTORY_CAPACITY_DANGER,
                        "ply " + ply + " + search occupancy margin approaches Board.UNMAKE_POOL_SIZE ("
                                + Board.UNMAKE_POOL_SIZE + ") -- aborting before the shared history/"
                                + "unmake stack is put at risk");
            }

            String rootFen = board.toFen();
            candidateAdapter.reset();

            try {
                searcher.iterativeDeepening(board, (int) config.searchBudgetValue(), () -> false, () -> false,
                        candidateAdapter);
            } catch (RuntimeException e) {
                throw new SelfPlayGenerationException(
                        SelfPlayGenerationException.Reason.SEARCH_INVARIANT_FAILURE,
                        "search threw " + e.getClass().getSimpleName() + " at ply " + ply + " (fen=" + rootFen
                                + "): " + e.getMessage(), e);
            }

            if (!rootFen.equals(board.toFen())) {
                throw new SelfPlayGenerationException(
                        SelfPlayGenerationException.Reason.BOARD_INVARIANT_VIOLATION,
                        "board mutated by search at ply " + ply + " -- before=" + rootFen + " after="
                                + board.toFen());
            }

            if (candidateAdapter.lastCompletedSet().isEmpty()) {
                throw new SelfPlayGenerationException(
                        SelfPlayGenerationException.Reason.NO_COMPLETED_CANDIDATE,
                        "no completed root iteration at ply " + ply + " (fen=" + rootFen + ") -- search "
                                + "budget may be too small, or every depth aborted");
            }
            List<IterationInfo> completedSet = candidateAdapter.lastCompletedSet().get();

            IterationInfo evaluated = completedSet.get(0); // rank 0 -- the position's own search evaluation
            int selectedPacked = moveSelector.select(completedSet, gameSeed + ply);
            validateLegal(board, selectedPacked, ply);

            samples.add(toTrainingSample(ply, rootFen, evaluated));
            playedMoves.add(new PlayedMoveDecision(
                    selectedPacked, SelectionMechanismKind.BEST_MOVE, null, OptionalLong.empty()));

            board.makeMove(selectedPacked);
            ply++;
        }

        return new GameFrame(
                gameId,
                OptionalLong.of(gameSeed),
                outcome,
                reason,
                coeusyk.game.chess.core.selfplay.vspr.OutcomePerspective.WHITE,
                List.copyOf(playedMoves),
                List.copyOf(samples),
                List.of());
    }

    private record TerminalCheck(GameOutcome outcome, TerminationReason reason) {
    }

    private TerminalCheck checkNaturalTermination(Board board) {
        if (board.isCheckmate()) {
            // The side to move is checkmated -- the other side wins.
            GameOutcome outcome = board.getActiveColor() == coeusyk.game.chess.core.models.Piece.White
                    ? GameOutcome.BLACK_WIN
                    : GameOutcome.WHITE_WIN;
            return new TerminalCheck(outcome, TerminationReason.CHECKMATE);
        }
        if (board.isStalemate()) {
            return new TerminalCheck(GameOutcome.DRAW, TerminationReason.STALEMATE);
        }
        if (board.isThreefoldRepetition()) {
            return new TerminalCheck(GameOutcome.DRAW, TerminationReason.THREEFOLD_REPETITION);
        }
        if (board.isFiftyMoveRuleDraw()) {
            return new TerminalCheck(GameOutcome.DRAW, TerminationReason.FIFTY_MOVE_RULE);
        }
        if (board.isInsufficientMaterial()) {
            return new TerminalCheck(GameOutcome.DRAW, TerminationReason.INSUFFICIENT_MATERIAL);
        }
        return null;
    }

    private void validateLegal(Board board, int packed, int ply) {
        int[] legal = new int[256];
        int count = MovesGenerator.generate(board, legal);
        for (int i = 0; i < count; i++) {
            if (legal[i] == packed) {
                return;
            }
        }
        throw new SelfPlayGenerationException(
                SelfPlayGenerationException.Reason.ILLEGAL_MOVE,
                "selected move (packed=" + packed + ", from=" + Move.from(packed) + " to=" + Move.to(packed)
                        + ") is not legal at ply " + ply + " (fen=" + board.toFen() + ")");
    }

    private TrainingSample toTrainingSample(int ply, String fen, IterationInfo evaluated) {
        int scoreCp = evaluated.scoreCp();
        if (Math.abs(scoreCp) > SANE_SCORE_BOUND) {
            throw new SelfPlayGenerationException(
                    SelfPlayGenerationException.Reason.NON_FINITE_SCORE,
                    "score " + scoreCp + " at ply " + ply + " is outside the sane bound (+/-"
                            + SANE_SCORE_BOUND + ") -- treating as corrupted, not a real evaluation");
        }
        boolean isMate = Math.abs(scoreCp) >= MATE_SCORE - MAX_PLY;
        ScoreKind kind = isMate ? ScoreKind.MATE : ScoreKind.CP;
        int value;
        if (isMate) {
            // Mate distance in plies (DR-220 section 6), signed -- positive means the position's
            // side to move delivers mate in N plies, matching this record's own negamax
            // convention. Deliberately NOT UciApplication's "mate in moves" conversion
            // ((mateInPly + 1) / 2) -- DR-220's wire field is plies, not moves.
            int distancePlies = MATE_SCORE - Math.abs(scoreCp);
            value = scoreCp < 0 ? -distancePlies : distancePlies;
        } else {
            value = scoreCp;
        }
        return new TrainingSample(ply, fen, true, kind, value, SearchBudgetKind.DEPTH,
                config.searchBudgetValue());
    }
}
