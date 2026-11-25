package coeusyk.game.chess.tuner;

import coeusyk.game.chess.core.models.Board;
import org.junit.jupiter.api.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Partition isolation along the real CLI-to-optimiser path (#274): the optimisers receive the train partition
 * for the objective and the val partition for K, and nothing else, for coordinate descent as well as Adam and
 * L-BFGS.
 */
class KCalibrationIsolationTest {

    private static List<LabelledPosition> corpus(int n) {
        List<LabelledPosition> out = new ArrayList<>();
        List<String> fens = CoreTunerParityTest.seededPositions(n, 99);
        for (int i = 0; i < n; i++) {
            out.add(new LabelledPosition(TunerPosition.from(new Board(fens.get(i))), (i % 3) * 0.5));
        }
        return out;
    }

    /** A list view that counts reads, so a test can tell which partition an optimiser touched. */
    private static final class Counting extends AbstractList<LabelledPosition> {
        final List<LabelledPosition> inner;
        final AtomicInteger reads = new AtomicInteger();
        Counting(List<LabelledPosition> inner) { this.inner = inner; }
        @Override public LabelledPosition get(int i) { reads.incrementAndGet(); return inner.get(i); }
        @Override public int size() { return inner.size(); }
    }

    @Test
    void tuningDataTakesFeaturesFromTheTrainAndValPartitionsOnly() {
        CorpusPartition p = PositionLoader.split(corpus(60), 0.6, 0.2, 7);

        TunerMain.TuningData data = TunerMain.TuningData.of(p);

        assertSame(p.train(), data.trainPositions());
        assertSame(p.val(), data.valPositions());
        assertEquals(p.train().size(), data.trainFeatures().size(), "the optimiser trains on the whole train partition");
        assertEquals(p.val().size(), data.valFeatures().size(), "K is fitted on the val partition, not a slice of train");
        assertTrue(data.trainFeatures().size() + data.valFeatures().size() < 60, "the test partition is not included");
    }

    @Test
    void anEmptyValPartitionIsRejectedInsteadOfFallingBackToTrain() {
        CorpusPartition p = new CorpusPartition(corpus(5), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> TunerMain.TuningData.of(p));
    }

    @Test
    void coordinateDescentTrainsOnTrainAndCalibratesKOnVal() {
        List<LabelledPosition> all = corpus(30);
        Counting train = new Counting(all.subList(0, 20));
        Counting val = new Counting(all.subList(20, 30));
        TunerMain.TuningData data = new TunerMain.TuningData(train, val, List.of(), List.of());
        boolean[] oneParam = new boolean[EvalParams.TOTAL_PARAMS];
        oneParam[EvalParams.IDX_TEMPO] = true;

        TunerRunMetrics metrics = new TunerRunMetrics();
        TunerMain.optimize("coordinate", data, EvalParams.extractFromCurrentEval(), 1.0, 1, true, oneParam, metrics);

        assertTrue(train.reads.get() > 0, "the objective reads the train partition");
        assertTrue(val.reads.get() > 0, "K recalibration reads the val partition, not train");
        assertNotEquals("NOT_RUN", metrics.terminationReason, "the run must report how it ended");
    }
}
