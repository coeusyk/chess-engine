package coeusyk.game.chess.tuner;

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

    private TunerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: engine-tuner <dataset> [maxPositions] [maxIterations] [--optimizer adam|coordinate] [--no-recalibrate-k]");
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
                    System.err.println("--optimizer requires a value: adam|coordinate");
                    System.exit(1);
                }
                optimizer = args[++i].toLowerCase();
                if (!"adam".equals(optimizer) && !"coordinate".equals(optimizer)) {
                    System.err.println("Unknown optimizer: " + optimizer + " (valid: adam, coordinate)");
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

        System.out.printf("[TunerMain] Dataset:       %s%n", datasetPath.toAbsolutePath());
        System.out.printf("[TunerMain] Max positions: %s%n",
                maxPositions == Integer.MAX_VALUE ? "all" : String.format("%,d", maxPositions));
        System.out.printf("[TunerMain] Max iters:     %d%n", maxIters);
        System.out.printf("[TunerMain] Optimizer:     %s%n", optimizer);
        System.out.printf("[TunerMain] Recalibrate K: %s%n", recalibrateK ? "yes" : "no (--no-recalibrate-k)");

        // --- Load positions (streaming with early stop at maxPositions) ---
        Instant loadStart = Instant.now();
        List<LabelledPosition> positions = PositionLoader.load(datasetPath, maxPositions);
        long loadMs = Duration.between(loadStart, Instant.now()).toMillis();
        System.out.printf("[TunerMain] Loaded %,d positions in %,d ms%n", positions.size(), loadMs);

        // --- Extract initial parameters from hardcoded engine constants ---
        double[] params = EvalParams.extractFromCurrentEval();
        System.out.printf("[TunerMain] Parameter count: %d%n", params.length);

        // --- Find optimal K (sigmoid scaling constant) ---
        System.out.println("[TunerMain] Finding optimal K...");
        double k = KFinder.findK(positions, params);

        // --- Run chosen optimizer ---
        double[] tuned;
        if ("adam".equals(optimizer)) {
            System.out.printf("[TunerMain] Running Adam gradient descent (K=%.6f, maxIters=%d)...%n", k, maxIters);
            tuned = GradientDescent.tune(positions, params, k, maxIters, recalibrateK);
        } else {
            System.out.printf("[TunerMain] Running coordinate descent (K=%.6f, maxIters=%d)...%n", k, maxIters);
            tuned = CoordinateDescent.tune(positions, params, k, maxIters, recalibrateK);
        }

        // Final K after tuning (written to file per #95 AC)
        System.out.println("[TunerMain] Computing final K...");
        double finalK = KFinder.findK(positions, tuned);
        System.out.printf("[TunerMain] Final K = %.6f%n", finalK);

        // --- Write results ---
        Path outputPath = Paths.get("tuned_params.txt");
        EvalParams.writeToFile(tuned, finalK, outputPath);
        System.out.printf("[TunerMain] Tuned parameters written to: %s%n",
                outputPath.toAbsolutePath());
        System.out.println("[TunerMain] Copy values manually from tuned_params.txt into engine-core source files.");
    }
}

