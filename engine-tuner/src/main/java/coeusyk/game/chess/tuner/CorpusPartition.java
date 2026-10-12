package coeusyk.game.chess.tuner;

import java.util.List;

/**
 * Result of a stratified train/val/test split on a labelled position corpus.
 *
 * <p>All three lists are unmodifiable views over the original corpus list —
 * no position data is copied during the split.
 *
 * @param train positions for optimizer gradient computation
 * @param val   positions for K calibration (held out of training)
 * @param test  positions for post-run validation (held out of training and K search)
 */
public record CorpusPartition(
        List<LabelledPosition> train,
        List<LabelledPosition> val,
        List<LabelledPosition> test) {
}
