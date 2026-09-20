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

## 9. Dev Entries (Decision Log)

`dev-entries/` holds the development log, split one file per phase (`dev-entries/phase-N.md`),
following `dev-entries/README.md`. This is where phase decisions, rationale, and measurements are
recorded — separate from `docs/engineering/investigations/` (Section 7, standalone case studies for
reusable methodology) and separate from GitHub issues (which track task state, not decision
rationale).

Add an entry to the active phase's file for every non-trivial decision made during that phase:
what was built, why a particular approach was chosen over the alternatives considered, what broke
or got fixed, and the measurements that back the decision. Follow the existing per-entry format
(date-stamped heading, `Built:` / `Decisions Made:` / `Broke / Fixed:` / `Measurements:`) — see any
existing `phase-N.md` file for the pattern. Create the phase's file (and add it to the list in
`dev-entries/README.md`) the first time that phase does work, if it does not already exist.

## 10. Public-Facing Writing

Before writing or editing anything a person will actually read outside the terminal, docs under
`docs/`, GitHub issue bodies and comments, PR bodies and comments, run the `human` skill (`/human`)
on the draft first. This applies to research reports (`DR-*.md`), investigation write-ups, commit
message bodies where feasible, and any other text meant for a human reader rather than for the
model's own working notes.

Never use a double hyphen (`--`) as a dash. When a sentence wants a dash, restructure it instead
with a period, a comma, a parenthetical, or two sentences, so the double hyphen is never typed as
punctuation in the first place. A double hyphen used as a literal flag token (`--verbose`) is not
affected by this rule.

Never cap or truncate the length of a GitHub issue body/comment, PR body/comment, or commit message
body for its own sake. Write the complete thing: every task, every "do not," every open question,
every decision and its rationale, in full. This mirrors the global "never cap or truncate length"
rule but is repeated here because it applies specifically and without exception to this project's
issue tracker and commit history, which double as the project's record of what was decided and why
— cutting them short for brevity destroys that record. This does not license padding; state
everything that needs stating, and nothing that doesn't.
