package coeusyk.game.chess.tuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Entry point for the Texel tuning pipeline.
 *
 * <p>Usage:
 * <pre>
 *   java -jar engine-tuner.jar &lt;dataset&gt; [maxPositions] [maxIterations] [--optimizer adam|coordinate] [--no-recalibrate-k]
 * </pre>
 *
 * <ul>
 *   <li>{@code dataset}           — path to the EPD / annotated-FEN file</li>
 *   <li>{@code maxPositions}      — optional: cap on positions loaded (default: all)</li>
 *   <li>{@code maxIterations}     — optional: optimizer iteration cap
 *                                   (default: optimizer-specific DEFAULT_MAX_ITERATIONS)</li>
 *   <li>{@code --optimizer adam|coordinate} — optional: choose optimizer (default: adam)</li>
 *   <li>{@code --no-recalibrate-k} — optional: disable K recalibration after each pass
 *                                   (default: enabled)</li>
 * </ul>
 *
 * <p>Output is written to {@code tuned_params.txt} in the working directory.
 * Copy values from that file manually into the engine-core source constants.
 * Never inject tuned parameters at runtime into the live Evaluator.
 */
public final class TunerMain {

    private static final Logger LOG = LoggerFactory.getLogger(TunerMain.class);

    private TunerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            LOG.error("Usage: engine-tuner <dataset> [maxPositions] [maxIterations] [--optimizer adam|coordinate] [--no-recalibrate-k]");
            System.exit(1);
        }

        Path   datasetPath    = Paths.get(args[0]);
        int    maxPositions   = Integer.MAX_VALUE;
        int    maxIters       = -1; // sentinel: use optimizer default
        String optimizer      = "adam";
        boolean recalibrateK  = true;

        // Parse remaining positional args and named flags
        for (int i = 1; i < args.length; i++) {
            if ("--optimizer".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--optimizer requires a value: adam|coordinate");
                    System.exit(1);
                }
                optimizer = args[++i].toLowerCase();
                if (!"adam".equals(optimizer) && !"coordinate".equals(optimizer)) {
                    LOG.error("Unknown optimizer: {} (valid: adam, coordinate)", optimizer);
                    System.exit(1);
                }
            } else if ("--no-recalibrate-k".equals(args[i])) {
                recalibrateK = false;
            } else if (maxPositions == Integer.MAX_VALUE) {
                maxPositions = Integer.parseInt(args[i]);
            } else if (maxIters == -1) {
                maxIters = Integer.parseInt(args[i]);
            }
        }

        // Apply optimizer-specific defaults for maxIters
        if (maxIters == -1) {
            maxIters = "adam".equals(optimizer)
                    ? GradientDescent.DEFAULT_MAX_ITERATIONS
                    : CoordinateDescent.DEFAULT_MAX_ITERATIONS;
        }

        LOG.info("[TunerMain] Dataset:       {}", datasetPath.toAbsolutePath());
        LOG.info("[TunerMain] Max positions: {}",
                maxPositions == Integer.MAX_VALUE ? "all" : String.format("%,d", maxPositions));
        LOG.info("[TunerMain] Max iters:     {}", maxIters);
        LOG.info("[TunerMain] Optimizer:     {}", optimizer);
        LOG.info("[TunerMain] Recalibrate K: {}", recalibrateK ? "yes" : "no (--no-recalibrate-k)");

        // --- Load positions (streaming with early stop at maxPositions) ---
        Instant loadStart = Instant.now();
        List<LabelledPosition> positions = PositionLoader.load(datasetPath, maxPositions);
        long loadMs = Duration.between(loadStart, Instant.now()).toMillis();
        LOG.info(String.format("[TunerMain] Loaded %,d positions in %,d ms", positions.size(), loadMs));

        // --- Extract initial parameters from hardcoded engine constants ---
        double[] params = EvalParams.extractFromCurrentEval();
        LOG.info("[TunerMain] Parameter count: {}", params.length);

        // --- Find optimal K (sigmoid scaling constant) ---
        LOG.info("[TunerMain] Finding optimal K...");
        double k = KFinder.findK(positions, params);

        // --- Run chosen optimizer ---
        double[] tuned;
        if ("adam".equals(optimizer)) {
            LOG.info(String.format("[TunerMain] Running Adam gradient descent (K=%.6f, maxIters=%d)...", k, maxIters));
            tuned = GradientDescent.tune(positions, params, k, maxIters, recalibrateK);
        } else {
            LOG.info(String.format("[TunerMain] Running coordinate descent (K=%.6f, maxIters=%d)...", k, maxIters));
            tuned = CoordinateDescent.tune(positions, params, k, maxIters, recalibrateK);
        }

        // Final K after tuning (written to file per #95 AC)
        LOG.info("[TunerMain] Computing final K...");
        double finalK = KFinder.findK(positions, tuned);
        LOG.info(String.format("[TunerMain] Final K = %.6f", finalK));

        // --- Write results ---
        Path outputPath = Paths.get("tuned_params.txt");
        EvalParams.writeToFile(tuned, finalK, outputPath);
        LOG.info("[TunerMain] Tuned parameters written to: {}", outputPath.toAbsolutePath());
        LOG.info("[TunerMain] Copy values manually from tuned_params.txt into engine-core source files.");
    }
}

