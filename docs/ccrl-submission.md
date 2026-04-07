# Vex — CCRL Submission Guide

## Engine Information

| Field          | Value                                                              |
|----------------|--------------------------------------------------------------------|
| Engine name    | Vex                                                                |
| Author         | Yash Karecha (coeusyk)                                             |
| Repository     | Private (closed source; JAR distributed directly to testers)       |
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

**For CCRL testers:** The repository is private. The JAR must be shared directly
(e.g. by email attachment or file-sharing service). The CCRL tester does not need
the source code — only the JAR and Java 17+ on their machine.

**On the avx2 requirement:** CCRL prefers avx2-compiled native executables. Vex is
a JVM-based engine and does not compile to a native binary. The JVM will use
available SIMD/avx2 hardware automatically at run time via JIT. No special build
step is required by the tester.

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

---

## Where to Submit

Post a new topic in the **CCRL Public Forum**:

> **URL:** http://kirill-kryukov.com/chess/discussion-board/viewforum.php?f=7

For submission instructions, see the pinned post:

> **"How to get your engine tested for CCRL":**
> http://kirill-kryukov.com/chess/discussion-board/viewtopic.php?t=11975

Required information (per Graham Banks, CCRL — exact fields from t=11975):
1. Engine name and version number
2. Estimated strength
3. Author name and nationality
4. Open source or closed source
5. Homepage (if any)
6. For closed source: credit all third-party code used
7. Executable files (avx2 preferred). For Java engines: the fat JAR + run command.
   The CCRL tester must have Java 17+ installed.

**On open/closed source:** The repository is private. The engine is built from
original source code only — no proprietary chess code, no licensed third-party
chess logic. Standard Maven dependencies (Spring Boot, JUnit, slf4j) are used
for the UCI interface and tests under their respective open-source licences.

### Forum Post Template for Vex 0.5.5

_(Update version number once the release workflow completes.)_

**Title:** New engine: Vex 0.5.5 (Java, closed source)

**Body:**

```
Engine:            Vex 0.5.5
Author:            Yash Karecha (coeusyk), India
Open source:       No (closed source, private repository)
Homepage:          N/A
Download:          JAR provided directly upon request (see attachment / PM)
Run command:       java -jar engine-uci-0.5.5.jar  (requires Java 17+)
Estimated strength ~2800–2900 (CCRL scale)
                   (internal SPRT vs v0.4.9: +149 Elo ±99, 24W-7L-11D, LLR 2.95)
Settings:          Hash 128 MiB, 1 thread
Architecture:      Bitboard, Negamax/PVS, aspiration windows, Lazy SMP,
                   Syzygy WDL+DTZ (5-piece)
Evaluation:        HCE tapered mid/endgame, Texel-tuned
Note:              Java engine — JVM handles SIMD/avx2 at run time via JIT.
                   Tester needs Java 17+ (Java 21 recommended).
```

### Submission record

| Version | Submitted   | Forum thread |
|---------|-------------|----------------------------------------------------|
| 0.5.4   | 2026-04-08  | http://kirill-kryukov.com/chess/discussion-board/viewforum.php?f=7 |
| 0.5.5   | (pending)   | — |

- **Forum:** http://kirill-kryukov.com/chess/discussion-board/viewforum.php?f=7
- **Instructions post:** http://kirill-kryukov.com/chess/discussion-board/viewtopic.php?t=11975
