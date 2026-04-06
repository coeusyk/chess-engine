# Vex — CCRL Submission Guide

## Engine Information

| Field          | Value                                                              |
|----------------|--------------------------------------------------------------------|
| Engine name    | Vex                                                                |
| Author         | Yash Karecha (coeusyk)                                             |
| Repository     | https://github.com/coeusyk/chess-engine                            |
| Releases page  | https://github.com/coeusyk/chess-engine/releases                  |
| Language       | Java 17+                                                           |
| Architecture   | Bitboard, Negamax + Alpha-Beta, PVS, aspiration windows, Lazy SMP |
| Evaluation     | Hand-crafted (HCE), tapered mid/endgame, Texel-tuned parameters   |
| Tablebases     | Syzygy WDL + DTZ (5-man by default; configurable)                  |

---

## Submission Artifact

The submission artifact is the fat (shaded) JAR produced by `engine-uci`:

```
engine-uci/target/engine-uci-<version>.jar
```

This JAR has no external runtime dependencies. It runs directly with:

```
java -jar engine-uci-<version>.jar
```

**Requirements:** Java 17 or later (Java 21 recommended). No Maven, no classpath setup.

---

## UCI Options

The engine advertises the following options via the `uci` command. Options relevant to
the tester are listed first.

| Option name        | Type   | Default | Min | Max   | Notes                                                                     |
|--------------------|--------|---------|-----|-------|---------------------------------------------------------------------------|
| `Hash`             | spin   | 64      | 1   | 65536 | Transposition table size in MiB. Set to ≥ 128 MiB for long TC games.     |
| `Threads`          | spin   | 1       | 1   | 512   | Search thread count (Lazy SMP). 1–2 threads recommended for CCRL testing. |
| `SyzygyPath`       | string | (empty) | —   | —     | Semicolon-separated list of directories containing Syzygy .rtbw/.rtbz files. Required for correct KBN vs K conversion. |
| `SyzygyProbeDepth` | spin   | 1       | 0   | 100   | Minimum remaining depth before a WDL Syzygy probe fires.                  |
| `Syzygy50MoveRule` | check  | true    | —   | —     | Honor the 50-move rule inside Syzygy DTZ rescoring.                        |
| `Ponder`           | check  | false   | —   | —     | Enable pondering.                                                          |
| `OwnBook`          | check  | false   | —   | —     | Use the built-in Polyglot opening book.                                    |
| `BookFile`         | string | Performance.bin | — | —  | Path to a custom Polyglot book file.                                      |
| `MoveOverhead`     | spin   | 30      | 0   | 5000  | Safety buffer subtracted from time allocation in milliseconds.             |
| `MultiPV`          | spin   | 1       | 1   | 500   | Number of principal variations reported.                                   |
| `PawnHashSize`     | spin   | 1       | 1   | 256   | Pawn hash table size in MiB.                                               |

### Recommended CCRL settings

```
setoption name Hash value 128
setoption name Threads value 1
```

If Syzygy tablebase files are available on the test machine:

```
setoption name SyzygyPath value C:\Syzygy\345
```

---

## Recommended Time Control

The engine has been tested primarily at **10+0.1** (10 seconds per game with 100 ms
increment) and **40/4** (40 moves in 4 minutes). Either is suitable for CCRL entry
testing. CCRL standard time controls are accepted.

---

## Known Limitations

### KBN vs K without Syzygy files

Bishop + Knight vs lone King is the hardest elementary checkmate, requiring up to
33 moves of optimal play. Classical evaluation cannot force mate within any
practical search depth.

**Without Syzygy files:** the engine will evaluate the position as winning on
material but will fail to convert and will likely draw by the 50-move rule.

**With 5-man Syzygy files installed:** the tablebase probe fires at the root
(4 pieces ≤ 5-piece limit) and returns the DTZ-optimal move. Conversion is
guaranteed.

**Recommendation:** install 5-man Syzygy tablebases and set `SyzygyPath`
before running CCRL games involving KBN vs K endgames.

### Syzygy probe fires only when not in check

WDL probes inside `alphaBeta` and DTZ probes at the root are guarded with an
in-check condition: the probe is skipped when the side to move is in check, and
the engine falls back to classical search. This is correct behaviour and does
not affect probe accuracy for legal tablebase positions.

### Maximum search depth

The engine enforces a hard cap of 127 plies. This cap is never reached in
practical play at any standard time control.

---

## Pre-Submission Verification

Run the automated checklist before submitting:

```powershell
cd <repo>/chess-engine
.\tools\pre_submission_check.ps1
```

The script:
1. Locates the fat JAR in `engine-uci/target/`.
2. Sends `uci` to the JAR and verifies `uciok` is in the response.
3. Sends `isready` and verifies `readyok`.
4. Runs `bench` twice at depth 13 and asserts the node count lines are identical.
5. Prints **PASS** or **FAIL** for each check.

---

## Changelog Summary (Phase 11 additions relevant to CCRL)

- **Syzygy in-check guard (#124):** DTZ root probe and WDL alpha-beta probe are
  skipped when the side to move is in check, preventing illegal score injection.
- **KQK / KRK repetition draw fix (#125):** MopUp evaluation gradient doubled;
  draw contempt added (`CONTEMPT_THRESHOLD = 300 cp`, `DRAW_CONTEMPT = 20 cp`).
  Repetition and 50-move draws now return a penalty for the winning side, so the
  engine avoids cycling in won KQ vs K and KR vs K endgames.
- **KBN vs K probe verification (#126):** Confirmed that 4-piece positions are
  within the default 5-piece limit. Probe plumbing is correct; Syzygy files
  required for actual conversion.
