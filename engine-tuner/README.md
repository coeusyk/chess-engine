# engine-tuner

Texel tuning pipeline for the Vex chess engine.
Optimises the 812 evaluation parameters in `engine-core` via coordinate descent
on a labelled game-position dataset (EPD format).

---

## Prerequisites

| Requirement | Notes |
|-------------|-------|
| Java 17+ JDK | Tested with Temurin 21 |
| Maven wrapper | `./mvnw` / `mvnw.cmd` in the root |
| Labelled EPD dataset | Quiet positions with `[outcome]` labels — see *Dataset* below |

---

## Building the shaded JAR

From the repository root:

```
mvnw -pl engine-tuner --also-make package -DskipTests
```

Output: `engine-tuner/target/engine-tuner-0.2.0-SNAPSHOT-shaded.jar`

---

## Running the tuner

```
java -jar engine-tuner/target/engine-tuner-0.2.0-SNAPSHOT-shaded.jar \
     path/to/quiet-labeled.epd \
     [maxPositions] \
     [maxIterations]
```

| Argument | Default | Description |
|----------|---------|-------------|
| `dataset` | (required) | Path to the EPD/annotated-FEN file |
| `maxPositions` | all | Subsample cap (useful for quick sanity checks) |
| `maxIterations` | 500 | Coordinate-descent iteration cap |

The tuner writes **`tuned_params.txt`** in the working directory when it finishes.

### Example: quick run on the bundled sample

```
java -jar engine-tuner/target/engine-tuner-0.2.0-SNAPSHOT-shaded.jar \
     engine-tuner/src/test/resources/quiet-labeled-sample.epd \
     1000 \
     10
```

### Full production run (~830 K positions)

```
java -Xmx4g \
     -jar engine-tuner/target/engine-tuner-0.2.0-SNAPSHOT-shaded.jar \
     quiet-labeled.epd
```

---

## Dataset

### Format 1 — FEN with bracketed result (preferred)

```
rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1 [1.0]
```

Outcome values: `1.0` (white win), `0.5` (draw), `0.0` (black win).
Alternative tokens `1-0`, `1/2-1/2`, `0-1` are also accepted.

### Format 2 — EPD with `c9` annotation

```
rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1 c9 "1-0";
```

Lines that cannot be parsed are silently skipped.

### Acquiring a full dataset

The original Zurichess `quiet-labeled.epd` (~830 K positions) is no longer
hosted at the original Bitbucket URL. Alternatives:

- Generate your own dataset by extracting FEN positions from self-play PGN files
  and labelling each position with the game result.
- Use any EPD file in Format 1 or Format 2 above.

A 1 020-line sample for CI purposes lives in
`engine-tuner/src/test/resources/quiet-labeled-sample.epd`.

---

## Applying tuned parameters

**Never** inject tuned parameters at runtime into the live `Evaluator`.
After the tuner finishes:

1. Open `tuned_params.txt` (output in the working directory).
2. Compare the tuned values against the constants in the four source files:
   - `engine-core/.../eval/PieceSquareTables.java` — PST arrays (params 12–779)
   - `engine-core/.../eval/Evaluator.java` — material values (params 0–11), mobility (804–811)
   - `engine-core/.../eval/PawnStructure.java` — passed/isolated/doubled pawn bonuses (780–795)
   - `engine-core/.../eval/KingSafety.java` — king safety weights (796–803)
3. Copy changed values by hand into the source constants.
4. Rebuild and run the full Perft suite to confirm move generation is unaffected:
   ```
   mvnw -pl engine-core -Dtest=PerftTest test
   ```
5. Run SPRT to confirm the new constants are statistically stronger:
   ```
   sprt_smp.bat
   ```

The parameter index layout is documented in `EvalParams.java`.

---

## Running tests

```
# Always-on unit tests (37 tests, ~2 s)
mvnw -pl engine-tuner test

# Integration test against a full dataset (requires env var)
TUNER_DATASET=/path/to/quiet-labeled.epd mvnw -pl engine-tuner test
```

The `DatasetLoadingTest.fullDatasetLoadsAtLeast100kPositionsAndLogsMse` test is
disabled by default (requires `TUNER_DATASET` env var) and logs the starting MSE
for the current eval constants when enabled.

---

## Parameter layout (812 total)

| Range | Eval term |
|-------|-----------|
| `[0..11]` | Material MG/EG: pawn, knight, bishop, rook, queen, king |
| `[12..779]` | Piece-square tables MG/EG (6 pieces × 64 squares × 2 phases) |
| `[780..785]` | Passed pawn MG bonus (ranks 2–7) |
| `[786..791]` | Passed pawn EG bonus (ranks 2–7) |
| `[792..793]` | Isolated pawn MG/EG penalty |
| `[794..795]` | Doubled pawn MG/EG penalty |
| `[796]` | King safety: pawn shield rank-2 bonus |
| `[797]` | King safety: pawn shield rank-3 bonus |
| `[798]` | King safety: open-file penalty |
| `[799]` | King safety: half-open-file penalty |
| `[800..803]` | King safety: attacker weights (N, B, R, Q) |
| `[804..807]` | Mobility MG bonus per square (N, B, R, Q) |
| `[808..811]` | Mobility EG bonus per square (N, B, R, Q) |
