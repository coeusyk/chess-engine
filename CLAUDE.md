# CLAUDE.md

## 1. Project Overview

Vex is a Java chess engine speaking the UCI protocol.

- `engine-core` — pure chess logic: Board, MoveGenerator, Searcher, Evaluator, EvalParams.
- `engine-uci` — UCI protocol fat-JAR entry point.
- `engine-tuner` — standalone Texel tuning pipeline, depends on `engine-core` only.
- `chess-engine-api` — Spring Boot REST wrapper.
- `tools` — tuning/bench/SPRT scripts.

## 2. Workflow

Branch convention: one branch per phase, `phase/N-short-name` (e.g. `phase/14-eval-optimization`).
Never create per-issue branches.

## 3. Build Commands

```
mvn -pl engine-core test                                   # engine-core unit tests
mvn -pl engine-core,engine-tuner -am test                   # engine-core + engine-tuner (reactor; engine-tuner depends on engine-core)
mvn -pl engine-core,engine-uci -am package -DskipTests      # build UCI fat JAR
java -jar engine-uci/target/engine-uci-<version>-SNAPSHOT.jar --bench   # bench suite (31 positions, depth 13 default)
```

## 4. Critical Constraints

- `engine-core` must never depend on Spring/HTTP.
- No object allocation in hot paths (Searcher, Evaluator inner loops).
- NPS bench floor: aggregate >= 301,116 (5% below the 316,964 native-Windows baseline). Only
  enforce this gate on native Windows — WSL2 NPS is not a valid regression signal.
- Perft counts must pass before any Board/MoveGenerator commit.
- Evaluation must remain mirror-symmetric; run the mirror-symmetry test after every Evaluator change.

## 5. Issue Workflow

No GitHub MCP server is configured here — use `gh issue view <N> --json number,title,state,body,labels`.

All acceptance criteria must be checked and evidenced in the commit body before writing
"Closes #N" — never close on partial completion.

## 6. SPRT Rule

SPRTs run on native Windows only, never WSL. When an issue needs one, output the exact
`sprt.ps1` command and stop — never simulate or skip it.

## 7. Engineering Investigations

If an investigation produces reusable engineering knowledge (performance analysis,
benchmarking methodology, root-cause analysis, statistical/debugging methodology),
preserve it as a standalone case study under `docs/engineering/investigations/`.

Do not leave significant engineering findings only in GitHub issues or chat history.

Prefer preserving the investigation process (including disproven hypotheses), not just the final conclusion.

## 8. Commit Format (mandatory)

```
type(scope): summary <72 chars

Why: reason
What: key decisions
Out of scope: scope boundaries
Closes #N Phase: N — name
```
