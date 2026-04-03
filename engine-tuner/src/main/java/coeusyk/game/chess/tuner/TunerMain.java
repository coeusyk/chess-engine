package coeusyk.game.chess.tuner;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Entry point for the Texel tuning pipeline.
 *
 * <p>Usage:
 * <pre>
 *   java -jar engine-tuner.jar &lt;dataset&gt; [maxPositions] [maxIterations]
 * </pre>
 *
 * <ul>
 *   <li>{@code dataset}       — path to the EPD / annotated-FEN file</li>
 *   <li>{@code maxPositions}  — optional: cap on positions loaded (default: all)</li>
 *   <li>{@code maxIterations} — optional: coordinate-descent iteration cap
 *                               (default: {@link CoordinateDescent#DEFAULT_MAX_ITERATIONS})</li>
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
            System.err.println("Usage: engine-tuner <dataset> [maxPositions] [maxIterations]");
            System.exit(1);
        }

        Path datasetPath  = Paths.get(args[0]);
        int  maxPositions = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;
        int  maxIters     = args.length > 2 ? Integer.parseInt(args[2]) : CoordinateDescent.DEFAULT_MAX_ITERATIONS;

        System.out.printf("[TunerMain] Dataset:       %s%n", datasetPath.toAbsolutePath());
        System.out.printf("[TunerMain] Max positions: %s%n",
                maxPositions == Integer.MAX_VALUE ? "all" : maxPositions);
        System.out.printf("[TunerMain] Max iters:     %d%n", maxIters);

        // --- Load positions ---
        List<LabelledPosition> positions = PositionLoader.load(datasetPath);
        if (positions.size() > maxPositions) {
            positions = positions.subList(0, maxPositions);
        }
        System.out.printf("[TunerMain] Loaded %,d positions%n", positions.size());

        // --- Extract initial parameters from hardcoded engine constants ---
        double[] params = EvalParams.extractFromCurrentEval();

        // --- Find optimal K (sigmoid scaling constant) ---
        System.out.println("[TunerMain] Finding optimal K...");
        double k = KFinder.findK(positions, params);

        // --- Run coordinate descent ---
        System.out.printf("[TunerMain] Running coordinate descent (K=%.6f, maxIters=%d)...%n", k, maxIters);
        double[] tuned = CoordinateDescent.tune(positions, params, k, maxIters);

        // --- Write results ---
        Path outputPath = Paths.get("tuned_params.txt");
        EvalParams.writeToFile(tuned, outputPath);
        System.out.printf("[TunerMain] Tuned parameters written to: %s%n",
                outputPath.toAbsolutePath());
        System.out.println("[TunerMain] Copy values manually from tuned_params.txt into engine-core source files.");
    }
}
