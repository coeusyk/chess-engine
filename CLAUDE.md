# CLAUDE.md

## 1. Project Overview

Vex is a Java chess engine speaking the UCI protocol.

- `engine-core` — pure chess logic: Board, MoveGenerator, Searcher, Evaluator, EvalParams.
- `engine-uci` — UCI protocol fat-JAR entry point.
- `engine-tuner` — standalone Texel tuning pipeline, depends on `engine-core` only.
- `chess-engine-api` — Spring Boot REST wrapper.
- `tools` — tuning/bench/SPRT scripts.

Active branch convention: `phase/N-short-name` (e.g. `phase/14-eval-optimization`).

## 2. Build Commands

```
mvn -pl engine-core test                                   # engine-core unit tests
mvn -pl engine-core,engine-tuner -am test                   # engine-core + engine-tuner (reactor; engine-tuner depends on engine-core)
mvn -pl engine-core,engine-uci -am package -DskipTests      # build UCI fat JAR
java -jar engine-uci/target/engine-uci-<version>-SNAPSHOT.jar --bench   # bench suite (31 positions, depth 13 default)
```

## 3. Critical Constraints

- `engine-core` must never have Spring/HTTP dependencies.
- No object allocation in hot paths (Searcher, Evaluator inner loops).
- NPS bench floor: aggregate >= 301,116 NPS (5% below 316,964 baseline).
- NPS bench on WSL2 is not a valid regression gate — baseline was measured on native
  Windows. Only enforce NPS gates when running on native Windows.
- Perft counts must pass before any Board/MoveGenerator commit.
- Eval must be symmetric — run mirror symmetry test after every Evaluator change.

## 4. Issue Workflow

No GitHub MCP server is configured in this repo's environment. Use the `gh` CLI to read
issue content: `gh issue view <N> --json number,title,state,body,labels`.

When working on any GitHub issue, ALL acceptance criteria in the issue body must be met
and verified before the closing commit. Do not write a commit message that says
"Closes #N" until every checkbox in the issue's acceptance criteria is checked and
evidenced in the commit message body.

## 5. SPRT Rule

SPRTs are run on Windows (PC only, not WSL). When an issue requires SPRT, output the
exact `sprt.ps1` command and stop. Do not simulate or skip it.

## 6. Branch Rule

One branch per phase only (`phase/N-short-name`). Never create per-issue branches.

## 7. Commit Format (mandatory)

```
type(scope): summary <72 chars

Why: reason
What: key decisions
Left out: scope boundaries
Closes #N Phase: N — name
```
