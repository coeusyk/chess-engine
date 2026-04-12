package coeusyk.game.chess.tuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
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
 *   java -jar engine-tuner.jar &lt;dataset&gt; [maxPositions] [maxIterations] [--optimizer adam|coordinate|lbfgs] [--no-recalibrate-k]
 * </pre>
 *
 * <ul>
 *   <li>{@code dataset}           — path to the EPD / annotated-FEN file</li>
 *   <li>{@code maxPositions}      — optional: cap on positions loaded (default: all)</li>
 *   <li>{@code maxIterations}     — optional: optimizer iteration cap
 *                                   (default: optimizer-specific DEFAULT_MAX_ITERATIONS)</li>
 *   <li>{@code --optimizer adam|coordinate|lbfgs} — optional: choose optimizer (default: adam).
 *                                   {@code lbfgs} uses limited-memory BFGS with m=10 history pairs
 *                                   and gradient norm convergence (Issue #137).</li>
 *   <li>{@code --no-recalibrate-k} — optional: disable K recalibration after each pass
 *                                   (default: enabled)</li>
 *   <li>{@code --coverage-audit}   — compute Fisher diagonal, print starved parameters, exit</li>
 * </ul>
 *
 * <p>Output is written to {@code tuned_params.txt} in the working directory.
 * Copy values from that file manually into the engine-core source constants.
 * Never inject tuned parameters at runtime into the live Evaluator.
 */
public final class TunerMain {

    private static final Logger LOG = LoggerFactory.getLogger(TunerMain.class);

    /**
     * TEMPO-anchored Fisher threshold for coverage audit (A-2).
     * Defined as TEMPO_FISHER / 10 = 1.753763e-7 / 10.
     * Params below this threshold have less than 10% of the sensitivity of a term
     * that fires every position — treated as STARVED.
     * Params with PARAM_MIN == PARAM_MAX are intentionally fixed and reported as LOCKED,
     * never STARVED, regardless of their Fisher value.
     */
    static final double COVERAGE_STARVED_THRESHOLD = 1.753763e-8;

    private TunerMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
        LOG.error("Usage: engine-tuner <dataset> [maxPositions] [maxIterations] [--optimizer adam|coordinate|lbfgs] [--param-group material|pst|pawn-structure|king-safety|mobility|scalars] [--corpus-format csv|epd] [--no-recalibrate-k] [--freeze-k] [--k <value>] [--freeze-params] [--corpus <csv>] [--coverage-audit]");
            System.exit(1);
        }

        Path   datasetPath    = Paths.get(args[0]);
        int    maxPositions   = Integer.MAX_VALUE;
        int    maxIters       = -1; // sentinel: use optimizer default
        String optimizer      = "adam";
        boolean recalibrateK  = true;
        boolean freezeK       = false; // --freeze-k:      Phase B — keep K fixed during Adam
        boolean freezeParams  = false; // --freeze-params: Phase A — calibrate K only, skip optimizer
        double  initialK      = Double.NaN; // --k <value>: supply K directly, skipping KFinder
        boolean coverageAudit = false;
        Path   corpusPath     = null;  // #130: optional Stockfish-annotated CSV
        String corpusFormat   = "auto"; // #140: "auto", "csv", "epd"
        String paramGroup     = null;  // --param-group: restrict optimizer to one parameter group
        boolean skipSmoke      = false; // --skip-smoke
        boolean skipSanity     = false; // --skip-sanity
        boolean skipConvergence = false; // --skip-convergence
        int smokeGames         = 100;  // --smoke-games N
        int smokeDepth         = 3;    // --smoke-depth N

        // Parse remaining positional args and named flags
        for (int i = 1; i < args.length; i++) {
            if ("--optimizer".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--optimizer requires a value: adam|coordinate");
                    System.exit(1);
                }
                optimizer = args[++i].toLowerCase();
                if (!"adam".equals(optimizer) && !"coordinate".equals(optimizer) && !"lbfgs".equals(optimizer)) {
                    LOG.error("Unknown optimizer: {} (valid: adam, coordinate, lbfgs)", optimizer);
                    System.exit(1);
                }
            } else if ("--no-recalibrate-k".equals(args[i])) {
                recalibrateK = false;
            } else if ("--freeze-k".equals(args[i])) {
                freezeK = true;
                recalibrateK = false;
            } else if ("--k".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--k requires a numeric value, e.g. --k 1.145");
                    System.exit(1);
                }
                initialK = Double.parseDouble(args[++i]);
                if (initialK <= 0) {
                    LOG.error("--k value must be positive, got: {}", initialK);
                    System.exit(1);
                }
            } else if ("--freeze-params".equals(args[i])) {
                freezeParams = true;
            } else if ("--coverage-audit".equals(args[i])) {
                coverageAudit = true;
            } else if ("--param-group".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--param-group requires a value: material|pst|pawn-structure|king-safety|mobility|scalars");
                    System.exit(1);
                }
                paramGroup = args[++i];
                // Validate early so the user gets a clear error message before loading data.
                try {
                    EvalParams.buildGroupMask(paramGroup);
                } catch (IllegalArgumentException e) {
                    LOG.error("--param-group: {}", e.getMessage());
                    System.exit(1);
                }
            } else if ("--corpus-format".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--corpus-format requires a value: csv|epd");
                    System.exit(1);
                }
                corpusFormat = args[++i].toLowerCase();
                if (!"csv".equals(corpusFormat) && !"epd".equals(corpusFormat)) {
                    LOG.error("Unknown corpus format: {} (valid: csv, epd)", corpusFormat);
                    System.exit(1);
                }
            } else if ("--corpus".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--corpus requires a path argument: --corpus <csv_path>");
                    System.exit(1);
                }
                corpusPath = Paths.get(args[++i]);
                if (!corpusPath.toFile().exists()) {
                    LOG.error("--corpus file not found: {}", corpusPath.toAbsolutePath());
                    System.exit(1);
                }
            } else if ("--skip-smoke".equals(args[i])) {
                skipSmoke = true;
            } else if ("--skip-sanity".equals(args[i])) {
                skipSanity = true;
            } else if ("--skip-convergence".equals(args[i])) {
                skipConvergence = true;
            } else if ("--smoke-games".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--smoke-games requires a numeric value");
                    System.exit(1);
                }
                smokeGames = Integer.parseInt(args[++i]);
            } else if ("--smoke-depth".equals(args[i])) {
                if (i + 1 >= args.length) {
                    LOG.error("--smoke-depth requires a numeric value");
                    System.exit(1);
                }
                smokeDepth = Integer.parseInt(args[++i]);
            } else if (maxPositions == Integer.MAX_VALUE) {
                maxPositions = Integer.parseInt(args[i]);
            } else if (maxIters == -1) {
                maxIters = Integer.parseInt(args[i]);
            }
        }

        // Apply optimizer-specific defaults for maxIters
        if (maxIters == -1) {
            if ("coordinate".equals(optimizer)) {
                maxIters = CoordinateDescent.DEFAULT_MAX_ITERATIONS;
            } else {
                maxIters = GradientDescent.DEFAULT_MAX_ITERATIONS;
            }
        }

        LOG.info("[TunerMain] Dataset:       {}", datasetPath.toAbsolutePath());
        LOG.info("[TunerMain] Max positions: {}",
                maxPositions == Integer.MAX_VALUE ? "all" : String.format("%,d", maxPositions));
        LOG.info("[TunerMain] Max iters:     {}", maxIters);
        LOG.info("[TunerMain] Optimizer:     {}", optimizer);
        LOG.info("[TunerMain] Recalibrate K: {}", recalibrateK ? "yes" : "no (--no-recalibrate-k)");
        LOG.info("[TunerMain] Freeze K:      {}", freezeK      ? "yes (--freeze-k, Phase B)"      : "no");
        LOG.info("[TunerMain] Freeze params: {}", freezeParams ? "yes (--freeze-params, Phase A)"  : "no");
        LOG.info("[TunerMain] Coverage audit: {}", coverageAudit ? "yes (will exit after audit)" : "no");
        LOG.info("[TunerMain] Corpus format: {}", corpusFormat);
        LOG.info("[TunerMain] Param group:   {}", paramGroup != null ? paramGroup : "all (no mask)");
        if (corpusPath != null) {
            LOG.info("[TunerMain] Corpus CSV:    {} (overrides dataset for training data)", corpusPath.toAbsolutePath());
        }

        // --- Load positions (streaming with early stop at maxPositions) ---
        Instant loadStart = Instant.now();
        List<LabelledPosition> positions;
        if ("epd".equals(corpusFormat)) {
            // #140: load quiet-labeled.epd-compatible file with in-check and material filters
            positions = PositionLoader.loadEpd(datasetPath, maxPositions);
        } else if ("csv".equals(corpusFormat) || corpusPath != null) {
            // #130: load Stockfish-annotated CSV; sf_cp converted to pseudo-WDL via sigmoid(cp/340)
            Path src = (corpusPath != null) ? corpusPath : datasetPath;
            positions = PositionLoader.loadCsv(src, maxPositions);
        } else {
            positions = PositionLoader.load(datasetPath, maxPositions);
        }
        long loadMs = Duration.between(loadStart, Instant.now()).toMillis();
        LOG.info(String.format("[TunerMain] Loaded %,d positions in %,d ms", positions.size(), loadMs));

        // --- Extract initial parameters from hardcoded engine constants ---
        double[] params = EvalParams.extractFromCurrentEval();
        LOG.info("[TunerMain] Parameter count: {}", params.length);

        // --- Precompute feature vectors (one-time cost, eliminates bitboard ops during training) ---
        LOG.info("[TunerMain] Building precomputed feature vectors...");
        Instant featStart = Instant.now();
        List<PositionFeatures> features = PositionFeatures.buildList(positions);
        long featMs = Duration.between(featStart, Instant.now()).toMillis();
        LOG.info(String.format("[TunerMain] Feature vectors built in %,d ms", featMs));

        // --- Find optimal K using fast feature-based MSE ---
        double k;
        if (!Double.isNaN(initialK)) {
            k = initialK;
            LOG.info("[TunerMain] K supplied via --k: {}", k);
        } else {
            LOG.info("[TunerMain] Finding optimal K...");
            k = KFinder.findKFromFeatures(features, params);
        }

        // --- Coverage audit: compute Fisher diagonal, report starved params, exit ---
        if (coverageAudit) {
            LOG.info("[CoverageAudit] Computing Fisher diagonal over {} positions...", features.size());
            double[] fisherDiag = GradientDescent.computeFisherDiagonal(features, params, k);
            long[]   activationCounts = GradientDescent.computeActivationCounts(features, EvalParams.TOTAL_PARAMS);
            Integer[] indices = new Integer[EvalParams.TOTAL_PARAMS];
            for (int i = 0; i < indices.length; i++) indices[i] = i;
            java.util.Arrays.sort(indices,
                    (a, b) -> Double.compare(fisherDiag[a], fisherDiag[b]));
            int starvedCount = 0;
            int lockedCount  = 0;
            System.out.printf("%-32s  %4s  %10s  %12s  %8s  %8s  %s%n",
                    "PARAMETER", "IDX", "FISHER", "ACTIVATIONS", "VALUE", "MIN", "STATUS");
            System.out.println("-".repeat(90));
            for (int idx : indices) {
                boolean locked  = EvalParams.PARAM_MIN[idx] == EvalParams.PARAM_MAX[idx];
                boolean starved = !locked && fisherDiag[idx] < COVERAGE_STARVED_THRESHOLD;
                if (locked)  lockedCount++;
                if (starved) starvedCount++;
                String statusLabel = locked ? "*** LOCKED ***" : starved ? "*** STARVED ***" : "ok";
                System.out.printf("%-32s  %4d  %10.3e  %12d  %8.1f  %8.1f  %s%n",
                        EvalParams.getParamName(idx), idx, fisherDiag[idx],
                        activationCounts[idx], params[idx], EvalParams.PARAM_MIN[idx],
                        statusLabel);
            }
            System.out.println("-".repeat(90));
            LOG.info("[CoverageAudit] LOCKED parameters (min==max): {} / {}", lockedCount, EvalParams.TOTAL_PARAMS);
            LOG.info("[CoverageAudit] STARVED parameters (Fisher < {}): {} / {}",
                    String.format("%.3e", COVERAGE_STARVED_THRESHOLD), starvedCount, EvalParams.TOTAL_PARAMS - lockedCount);
            // Write CSV report file (A-1 acceptance criterion)
            Path reportPath = Paths.get("coverage-audit-report.csv");
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("param_name,idx,fisher_diagonal,activation_count,value,min_value,max_value,status\n");
                for (int idx : indices) {
                    boolean locked  = EvalParams.PARAM_MIN[idx] == EvalParams.PARAM_MAX[idx];
                    boolean starved = !locked && fisherDiag[idx] < COVERAGE_STARVED_THRESHOLD;
                    String csvStatus = locked ? "LOCKED" : starved ? "STARVED" : "ok";
                    sb.append(EvalParams.getParamName(idx)).append(',')
                      .append(idx).append(',')
                      .append(String.format("%.6e", fisherDiag[idx])).append(',')
                      .append(activationCounts[idx]).append(',')
                      .append(String.format("%.1f", params[idx])).append(',')
                      .append(String.format("%.1f", EvalParams.PARAM_MIN[idx])).append(',')
                      .append(String.format("%.1f", EvalParams.PARAM_MAX[idx])).append(',')
                      .append(csvStatus).append('\n');
                }
                Files.writeString(reportPath, sb.toString());
                LOG.info("[CoverageAudit] Report written to: {}", reportPath.toAbsolutePath());
            } catch (java.io.IOException e) {
                LOG.warn("[CoverageAudit] Failed to write report file: {}", e.getMessage());
            }
            System.exit(0);
        }

        // --- Phase A: --freeze-params — K calibrated, params untouched, optimizer skipped ---
        if (freezeParams) {
            LOG.info(String.format("[TunerMain] --freeze-params: Phase A complete. K = %.6f  (optimizer skipped)", k));
            LOG.info("[TunerMain] Re-run with --freeze-k to begin Phase B parameter tuning.");
            Path outputPath = Paths.get("tuned_params.txt");
            EvalParams.writeToFile(params, k, outputPath);
            LOG.info("[TunerMain] Initial parameters written to: {}  (K calibrated, params unchanged)",
                    outputPath.toAbsolutePath());
            return;
        }

        // --- Run chosen optimizer ---
        double[] tuned;
        boolean[] groupMask = (paramGroup != null) ? EvalParams.buildGroupMask(paramGroup) : null;
        TunerRunMetrics metrics = new TunerRunMetrics();
        if ("adam".equals(optimizer)) {
            LOG.info(String.format("[TunerMain] Running Adam gradient descent (K=%.6f, maxIters=%d, fast-path)...", k, maxIters));
            tuned = GradientDescent.tuneWithFeatures(features, params, k, maxIters, recalibrateK, groupMask, metrics);
        } else if ("lbfgs".equals(optimizer)) {
            LOG.info(String.format("[TunerMain] Running L-BFGS (K=%.6f, maxIters=%d, m=10, ||\u2207L||<1e-5 convergence)...", k, maxIters));
            tuned = GradientDescent.tuneWithFeaturesLBFGS(features, params, k, maxIters, recalibrateK, groupMask, metrics);
        } else {
            LOG.info(String.format("[TunerMain] Running coordinate descent (K=%.6f, maxIters=%d)...", k, maxIters));
            tuned = CoordinateDescent.tune(positions, params, k, maxIters, recalibrateK);
        }

        // Final K after tuning
        LOG.info("[TunerMain] Computing final K...");
        double finalK = KFinder.findKFromFeatures(features, tuned);
        LOG.info(String.format("[TunerMain] Final K = %.6f", finalK));

        // --- Post-run validation gate ---
        TunerPostRunValidator.ValidatorConfig vConfig = new TunerPostRunValidator.ValidatorConfig(
                skipConvergence, skipSanity, skipSmoke, smokeGames, smokeDepth, 0.30);
        TunerPostRunValidator.ValidationResult vResult =
                TunerPostRunValidator.validate(params, tuned, metrics, vConfig);

        // Always write the validation report (pass or fail)
        Path reportPath = Paths.get("validator-report.txt");
        Files.writeString(reportPath, vResult.reportText());
        LOG.info("[TunerMain] Validation report written to: {}", reportPath.toAbsolutePath());

        // --- Write results (only on validation pass) ---
        Path outputPath = Paths.get("tuned_params.txt");
        if (vResult.passed()) {
            EvalParams.writeToFile(tuned, finalK, outputPath);
            LOG.info("[TunerMain] Tuned parameters written to: {}", outputPath.toAbsolutePath());
            LOG.info("[TunerMain] Copy values manually from tuned_params.txt into engine-core source files.");
        } else {
            LOG.error("[TunerMain] Validation FAILED — tuned_params.txt NOT written. See validator-report.txt.");
            System.exit(2);
        }
    }
}
