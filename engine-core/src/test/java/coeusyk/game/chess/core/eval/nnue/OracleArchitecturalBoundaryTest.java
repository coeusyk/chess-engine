package coeusyk.game.chess.core.eval.nnue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bytecode-level architectural guard for the {@link NnueOracle} boundary (ADR-002:
 * float32 oracle is test/debug-only, never part of the live int16 evaluation path).
 *
 * <p>No ArchUnit dependency exists in this repo, and the rule needed here is
 * method-granular ("{@link NnueEvaluator#explainEval} may call {@link NnueOracle},
 * {@link NnueEvaluator#evaluate}/{@code onMake}/{@code onUnmake} may not") — a plain
 * package/class-level rule can't express that, since {@code NnueOracle} deliberately
 * lives in the same production package (issue #186). This disassembles compiled
 * {@code .class} files with the JDK's own {@code javap} rather than pull in a
 * framework for one boundary.
 */
class OracleArchitecturalBoundaryTest {

    private static final Path CLASSES_DIR = Path.of("target/classes");
    private static final String ORACLE = "NnueOracle";

    @Test
    void searcherNeverReferencesOracle() throws IOException, InterruptedException {
        assertFalse(disassemble("coeusyk.game.chess.core.search.Searcher").contains(ORACLE),
                "Searcher must never reference NnueOracle (ADR-005: pure NNUE runtime, no debug coupling)");
    }

    @Test
    void evaluatorStrategyNeverReferencesOracle() throws IOException, InterruptedException {
        assertFalse(disassemble("coeusyk.game.chess.core.eval.EvaluatorStrategy").contains(ORACLE),
                "EvaluatorStrategy interface must never reference NnueOracle");
    }

    @Test
    void nnueEvaluatorHotPathMethodsNeverInvokeOracle() throws IOException, InterruptedException {
        String disassembly = disassemble("coeusyk.game.chess.core.eval.nnue.NnueEvaluator");
        for (String method : List.of("public int evaluate(", "public void onMake(", "public void onUnmake(")) {
            String block = methodBlock(disassembly, method);
            assertFalse(block.contains(ORACLE), method + " must never invoke NnueOracle: " + block);
        }
    }

    /** Positive control: proves the method-block parsing above actually finds real references. */
    @Test
    void nnueEvaluatorExplainEvalIsTheApprovedOracleEntryPoint() throws IOException, InterruptedException {
        String disassembly = disassemble("coeusyk.game.chess.core.eval.nnue.NnueEvaluator");
        String block = methodBlock(disassembly, "public java.lang.String explainEval(");
        assertTrue(block.contains(ORACLE),
                "explainEval is the one approved debug entry point to NnueOracle; if this fails, "
                        + "the parsing above is vacuous and the negative checks in this class prove nothing");
    }

    @Test
    void productionEvalPackageOnlyReachesOracleThroughNnueEvaluator() throws IOException, InterruptedException {
        Path evalPackage = CLASSES_DIR.resolve("coeusyk/game/chess/core/eval");
        try (Stream<Path> classFiles = Files.walk(evalPackage)) {
            for (Path classFile : classFiles.filter(p -> p.toString().endsWith(".class")).toList()) {
                String simpleName = classFile.getFileName().toString().replace(".class", "");
                if (simpleName.equals("NnueOracle") || simpleName.startsWith("NnueOracle$")
                        || simpleName.equals("NnueEvaluator")) {
                    continue; // NnueOracle itself/its records, and NnueEvaluator (checked method-by-method above)
                }
                String fqcn = toFqcn(evalPackage, classFile);
                assertFalse(disassemble(fqcn).contains(ORACLE),
                        fqcn + " is a production eval class and must not depend on debug-only NnueOracle");
            }
        }
    }

    private static String methodBlock(String disassembly, String methodSignaturePrefix) {
        int start = disassembly.indexOf(methodSignaturePrefix);
        assertTrue(start >= 0, "method not found in disassembly: " + methodSignaturePrefix);
        int end = disassembly.indexOf("\n\n", start);
        return end < 0 ? disassembly.substring(start) : disassembly.substring(start, end);
    }

    private static String toFqcn(Path packageRoot, Path classFile) {
        Path relative = packageRoot.relativize(classFile);
        String withoutExtension = relative.toString().replace(".class", "");
        String pathPart = withoutExtension.contains("/")
                ? withoutExtension.substring(0, withoutExtension.lastIndexOf('/'))
                : "";
        String fileName = withoutExtension.contains("/")
                ? withoutExtension.substring(withoutExtension.lastIndexOf('/') + 1)
                : withoutExtension;
        String packagePrefix = "coeusyk.game.chess.core.eval" + (pathPart.isEmpty() ? "" : "." + pathPart.replace('/', '.'));
        return packagePrefix + "." + fileName;
    }

    private static String disassemble(String fqcn) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("javap", "-p", "-c", "-classpath", CLASSES_DIR.toString(), fqcn)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        assertTrue(exitCode == 0, "javap failed for " + fqcn + ":\n" + output);
        return output;
    }
}
