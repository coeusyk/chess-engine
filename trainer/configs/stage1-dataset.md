# Stage 1 dataset choice

Resolves `docs/NNUE_PRD.md` §5 Open Question #1 and `docs/adr/ADR-007-staged-training-data.md`'s
Open Question, at PR D-2's start, per issue #193's acceptance criteria.

**Chosen: Lichess evaluated positions** (candidate named in the PRD alongside the
Zurichess quiet set).

**Rationale:**
- CC0-licensed and openly published (`database.lichess.org`), actively maintained by
  an ongoing organization — matches this project's own licensing/originality
  transparency requirement (PRD §"Security & Privacy": "training data provenance...
  recorded per net for licensing/originality transparency").
- Large volume, well past the PRD's 50-100M position target scale.
- The Zurichess quiet set's provenance and current availability could not be verified
  with the same confidence (Zurichess is a much smaller, less actively maintained
  project) — not rejected on technical grounds, just less verifiable right now.

**Format normalization boundary (PRD §"End-to-End Reproducibility" item 1: "each
dataset has an identifier, an acquisition or generation script, and recorded
provenance").** Lichess's own published dump format is out of this PR's scope to pin
byte-for-byte — `TextDatasetProvider` (`trainer/trainer/dataset/text_provider.py`)
reads a **normalized** CSV: one `fen,eval_cp[,eval_mate]` record per line. Converting
Lichess's actual published dump into this normalized CSV is a separate acquisition
script, tracked as a real, later, small task — not part of D-2, and not fabricated
here. D-2's own tests run against a small, explicitly-synthetic fixture CSV
(`trainer/tests/fixtures/stage1_sample.csv`) — real positions, plausible but
not-live-fetched eval numbers, the same "clearly a test fixture, not real data"
convention the Java side already uses for `TestNetworks.synthetic()`.

**`DatasetMetadata` for this source**, once acquisition exists: `stage="public"`,
`identifier="lichess-evals-<version>"`, `source_ref="https://database.lichess.org/#evals"`.
