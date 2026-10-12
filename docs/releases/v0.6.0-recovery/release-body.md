# Vex Chess Engine v0.6.0

The main change in this release is Phase 21, a rework of Principal Variation Search (PVS). It cuts the nodes the engine searches and passed its strength test against the previous revision. The release also fixes several search correctness bugs, adds optional NNUE evaluation, and extends the self-play and training-data tooling.

## Principal Variation Search

PVS now decides which nodes are PV nodes from the active alpha-beta window, so pruning and re-search decisions follow the window the search is actually using. Moves are probed with zero-window searches, with full-depth verification where needed, and PV re-searches are handled by the PV node itself. New regression tests cover root fail-high, singular-search bounds, late-move-reduction verification, and legal PVs.

Measured against the control revision:

| Measurement | Control | Phase 21 | Change |
|---|---:|---:|---:|
| Main search nodes | 24,780,049 | 21,713,284 | 12.38% fewer |
| Quiescence nodes | 60,071,833 | 48,479,879 | 19.30% fewer |
| Median fixed-depth time (native Windows) | 71.795 s | 62.732 s | 12.62% faster |

The node counts come from the frozen 31-position depth-13 corpus. The timing comes from a separate native run of 16 invocations. Neither number says anything about playing strength on its own. The depth-13 best move changed in 8 of the 31 positions. A trace of those 8 found no illegal PV, mate-sign, or root-window defect.

## Other search fixes

- A lock-free transposition-table read race.
- Move-order metadata is now kept per ply.
- Singular-search result semantics.
- Termination after a root fail-high.
- SMP helper threads are now quiesced before the shared table is modified.

## Strength test

Phase 21 was tested against the previous revision with an SPRT: bounds [0, 10], alpha = beta = 0.05, 5+0.05 time control, one thread per engine.

- 13,118 scored games: 4,080 wins, 3,829 losses, 5,209 draws
- LLR 2.97, above the 2.94 upper bound, so H1 was accepted
- Estimated Elo difference +6.6 +/- 4.6

Accepting H1 under [0, 10] means the test found a gain. It does not show the gain is at least 10 Elo, and the point estimate is below that.

An earlier attempt at this test hit a setup fault. Its record shows six game starts and no preserved finished-game events, which does not prove that no game finished. The exact failure and any unrecorded outcomes cannot be reconstructed, and the maintainer accepted that limitation. The accepted run's evidence and this gap are documented in the [Phase 21 closure](https://github.com/coeusyk/chess-engine/blob/v0.6.0/docs/architecture/research/phase21-closure.md).

## NNUE evaluation

Vex can now use an NNUE network for evaluation. Set `EvalType` to `NNUE` and point `EvalFile` at a compatible network. Classical evaluation is still the default.

NNUE has not had its own strength test against Classical, so this release makes no claim that it plays better. The self-play and VSPR training-data pipeline also got corpus and run-manifest validation.

## Download and run

Download `engine-uci-0.6.0.jar` from the assets below. It needs Java 21 or newer.

```bash
java -jar engine-uci-0.6.0.jar
```

The engine speaks UCI, so it works in any UCI-compatible GUI. Sending `uci` returns `uciok`, and `isready` returns `readyok`.

To check the download, compare it with the `.sha256` file:

```bash
sha256sum -c engine-uci-0.6.0.jar.sha256
```

`release-manifest-0.6.0.json` records the version, tag, source commit (`bc3dc380e759526e8aa19cd8385af16ab22ac502`), minimum Java version, and the JAR's SHA-256.

The full technical changelog is in [CHANGELOG.md](https://github.com/coeusyk/chess-engine/blob/v0.6.0/CHANGELOG.md).
