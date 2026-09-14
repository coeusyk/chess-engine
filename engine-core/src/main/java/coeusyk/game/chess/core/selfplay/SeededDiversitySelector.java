package coeusyk.game.chess.core.selfplay;

import coeusyk.game.chess.core.search.IterationInfo;
import coeusyk.game.chess.core.selfplay.vspr.SelectionMechanismKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * #222 Stage B's one preregistered diversity mechanism: bounded stochastic sampling among the
 * near-best completed root candidates. Parameters are not invented here -- see
 * {@code docs/architecture/research/DR-E14-seeded-diversity-audit-and-preregistration.md} for the
 * candidate-gap audit that motivates {@code maxRank}/{@code cpLossBoundCentipawns}/
 * {@code temperature}.
 *
 * <p>Eligibility, for candidate ranks 2..{@code maxRank} (rank-1 is always eligible):
 * <ul>
 *   <li>same score kind (CP vs MATE) as rank-1 -- DR-E9 section 7's explicit mixed-kind
 *       exclusion; the audit found rank1/lower-rank kind mixing at ~1% of plies and there is no
 *       defined CP-equivalent for a mate-distance loss.</li>
 *   <li>if rank-1 itself is a mate score (winning or losing): no diversity at all --
 *       deterministic rank-1. A winning mate is never abandoned for diversity; a losing-mate
 *       position has no genuinely "better" alternative worth trading determinism away for, and
 *       mixing mate distance into a CP-loss bound is undefined by design (#222 section 7).</li>
 *   <li>single-candidate plies: deterministic (nothing to sample from).</li>
 *   <li>CP loss versus rank-1, clamped to &gt;=0 -- the audit found small MultiPV rank
 *       inversions (rank-2 up to ~33cp <em>better</em> than rank-1, an ordinary multi-PV
 *       re-search artifact, not a data error) -- must not exceed {@code cpLossBoundCentipawns}.</li>
 * </ul>
 *
 * <p>Weights over the eligible set: {@code exp(-max(0, loss) / temperature)} -- rank-1 always
 * has weight 1, and weight decays smoothly with score loss (softmax-style), deterministic given
 * the per-ply seed ({@link SeedDerivation}).
 */
public final class SeededDiversitySelector implements MoveSelector {

    private static final int MATE_SCORE = 100_000;
    private static final int MAX_PLY = 128;

    private final int maxRank;
    private final int cpLossBoundCentipawns;
    private final double temperature;

    // Diagnostics from the most recent select() call -- read by GameLoop for #222 section 8's
    // quality-cost record; never consumed by this class itself.
    private int lastChosenRank = -1;
    private int lastEligibleCount = -1;
    private double lastSelectionWeight = Double.NaN;
    private double lastSelectionProbability = Double.NaN;

    public SeededDiversitySelector(int maxRank, int cpLossBoundCentipawns, double temperature) {
        if (maxRank < 2) {
            throw new IllegalArgumentException("maxRank must be >= 2 (rank-1 alone needs no selector)");
        }
        if (cpLossBoundCentipawns < 0) {
            throw new IllegalArgumentException("cpLossBoundCentipawns must be >= 0");
        }
        if (!(temperature > 0)) {
            throw new IllegalArgumentException("temperature must be > 0");
        }
        this.maxRank = maxRank;
        this.cpLossBoundCentipawns = cpLossBoundCentipawns;
        this.temperature = temperature;
    }

    @Override
    public int requiredCandidateCount() {
        return maxRank;
    }

    @Override
    public SelectionMechanismKind mechanismKind() {
        return SelectionMechanismKind.NAMED;
    }

    @Override
    public String mechanismName() {
        return "seeded-diversity-v1";
    }

    @Override
    public double lastSelectionWeight() {
        return lastSelectionWeight;
    }

    @Override
    public double lastSelectionProbability() {
        return lastSelectionProbability;
    }

    public int lastChosenRank() {
        return lastChosenRank;
    }

    public int lastEligibleCount() {
        return lastEligibleCount;
    }

    @Override
    public int select(List<IterationInfo> candidates, long seed) {
        if (candidates == null || candidates.isEmpty()) {
            throw new SelfPlayGenerationException(
                    SelfPlayGenerationException.Reason.NO_COMPLETED_CANDIDATE,
                    "SeededDiversitySelector received an empty candidate list");
        }
        IterationInfo rank1 = candidates.get(0);
        requirePv(rank1);

        if (candidates.size() == 1 || isMate(rank1.scoreCp())) {
            return deterministic(rank1);
        }

        List<IterationInfo> eligible = new ArrayList<>();
        eligible.add(rank1);
        int upper = Math.min(candidates.size(), maxRank);
        for (int i = 1; i < upper; i++) {
            IterationInfo c = candidates.get(i);
            if (isMate(c.scoreCp())) {
                continue; // mixed-kind: excluded, per class docstring
            }
            int loss = Math.max(0, rank1.scoreCp() - c.scoreCp());
            if (loss > cpLossBoundCentipawns) {
                continue;
            }
            eligible.add(c);
        }

        if (eligible.size() == 1) {
            return deterministic(rank1);
        }

        double[] weights = new double[eligible.size()];
        double sum = 0;
        for (int i = 0; i < eligible.size(); i++) {
            int loss = Math.max(0, rank1.scoreCp() - eligible.get(i).scoreCp());
            weights[i] = Math.exp(-loss / temperature);
            sum += weights[i];
        }

        Random rnd = new Random(SeedDerivation.remix(seed));
        double draw = rnd.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < eligible.size(); i++) {
            acc += weights[i];
            if (draw < acc || i == eligible.size() - 1) {
                IterationInfo chosen = eligible.get(i);
                requirePv(chosen);
                lastChosenRank = candidates.indexOf(chosen);
                lastEligibleCount = eligible.size();
                lastSelectionWeight = weights[i];
                lastSelectionProbability = weights[i] / sum;
                return chosen.pv().get(0).pack();
            }
        }
        throw new IllegalStateException("unreachable -- weighted draw always resolves to a candidate");
    }

    private int deterministic(IterationInfo rank1) {
        requirePv(rank1);
        lastChosenRank = 0;
        lastEligibleCount = 1;
        lastSelectionWeight = 1.0;
        lastSelectionProbability = 1.0;
        return rank1.pv().get(0).pack();
    }

    private static boolean isMate(int scoreCp) {
        return Math.abs(scoreCp) >= MATE_SCORE - MAX_PLY;
    }

    private static void requirePv(IterationInfo info) {
        if (info.pv() == null || info.pv().isEmpty()) {
            throw new SelfPlayGenerationException(
                    SelfPlayGenerationException.Reason.NO_COMPLETED_CANDIDATE,
                    "candidate at depth " + info.depth() + " has an empty principal variation");
        }
    }
}
