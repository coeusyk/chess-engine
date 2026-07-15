# `stockfish-label-e2-real.json` — provenance of each value

The first real (non-plumbing) Stockfish labeling run, issue #202 (E-2). Unlike
`stockfish-label-tiny.json` (D-8's plumbing-only config), this run's output is a real
Stage 2 dataset, and `nodes` was chosen deliberately rather than for speed alone.

## Storage convention for this issue's two datasets (decided at issue #202's start)

Both real datasets this issue produces (Stage 1: `trainer/outputs/datasets/stage1-lichess/`;
Stage 2: `trainer/outputs/datasets/stage2-quiet-sf/`) are **local-only, not committed to
git**: `trainer/.gitignore`'s existing `outputs/` rule already covers both directories
(`git check-ignore -v` confirms it), consistent with `NNUE_TRAINER_ARCHITECTURE.md` §16's
documented `outputs/ # gitignored` layout convention — this issue does not introduce a
new convention, it reuses the existing one for the first time at real scale. Each
dataset's `manifest.json` (committed evidence of what was produced is this doc plus the
issue's closing commit message, not the manifest files themselves) records the full
provenance (source URL / engine identity, position count, `trainer_commit`,
`output_sha256`/binary hash) needed to reproduce either dataset from scratch by re-running
the corresponding script (`scripts/acquire_stage1_lichess.py`, `scripts/stockfish_label.py`)
against the same commit — regenerating is treated as cheaper and more honest than
committing large derived binary/CSV data that would immediately go stale relative to
whichever script produced it.

- `engine_path: "/home/coeusyk/.local/bin/stockfish"` — this session's dev-environment
  path, a symlink to a manually-installed Stockfish 18 release binary (superseding the
  `apt`-packaged Stockfish 16 at `/usr/games/stockfish`, kept installed but no longer
  first on `PATH`) — captured into the run's own manifest via
  `engine_uci_id`/`engine_binary_sha256` regardless (per the driver's own
  engine-identity-agnostic design, `NNUE_TRAINER_ARCHITECTURE.md` §9.3) — editing this
  field for a different machine or engine version does not change what the manifest
  records, only which binary produced it.
- `nodes: 25000` — 2.5x `stockfish-label-tiny.json`'s plumbing value (10000), chosen
  empirically at this issue's start (DR-D8 Open Question 1, "what node budget best
  balances label quality against throughput at real corpus scale" — explicitly left
  unresolved pending a real run). Measured throughput on this session's machine at
  three budgets before choosing: `nodes=10000` → ~50 pos/sec; `nodes=25000` → ~17
  pos/sec; `nodes=50000` → ~6 pos/sec. `25000` was chosen as the point past the
  plumbing config's admittedly-too-fast-to-be-meaningful budget while keeping a
  20,000-position run inside ~20 minutes wall-clock, appropriate for this issue's
  explicitly small/session-scoped run (order of magnitude decided at issue start,
  informed by available compute, per #202's own Scope). Not claimed as the
  production-scale optimum — a later, larger real run (E-9+ self-play or a bigger
  bootstrap redo) should re-measure at its own scale rather than inherit this value
  unexamined.
- `timeout_seconds: 30.0` — unchanged from the tiny-plumbing config's value; still
  generous relative to `nodes: 25000`'s expected sub-second real search time on this
  hardware, and DR-D8's own "scale with node budget" recommendation (Open Question 3)
  remains unimplemented in the driver itself, so there is no smaller value this
  config could productively express.
- Position source: `trainer/outputs/datasets/stage2-quiet-sf-input.fen`, 20,000 FENs,
  derived by evenly sampling (every 36th line, matching the existing
  `bench/nnue-corpus/*.epd` sampling convention) `data/quiet-labeled.epd` — the
  Zurichess-derived, already-quiet-filtered, already-present-on-disk 725,000-position
  corpus used elsewhere in this repo for Texel tuning (`engine-tuner/README.md`).
  Reused here as a real position *source* for Stage 2 (its own pre-existing `c9`
  game-result annotations are discarded; Stage 2 assigns fresh Stockfish evals) — this
  does not reopen or re-litigate the Stage 1 dataset-choice decision
  (`trainer/configs/stage1-dataset.md`: Lichess evaluated positions), which governs
  Stage 1's pre-labeled data only, not Stage 2's raw position source.

## Observed throughput of the real run (issue #202 Required Validation)

The three per-node-budget figures above (`~50`/`~17`/`~6` pos/sec) were measured on
small calibration samples *before* choosing `nodes=25000`, to pick a budget — not a
measurement of the real run itself, and were measured against Stockfish 16 (this
session's original `apt`-packaged engine, before the Stockfish 18 upgrade below). The
actual full run, labeling all 20,000 positions at `nodes=25000, threads=1` against
Stockfish 16:

```
$ time uv run python -m scripts.stockfish_label configs/stockfish-label-e2-real.json \
    outputs/datasets/stage2-quiet-sf-input.fen outputs/datasets/stage2-quiet-sf \
    stage2-sf-labeled-quiet-2026-07-15
labeled 20000, skipped 0 -> outputs/datasets/stage2-quiet-sf/shard-0.bin

real    7m48.715s
user    7m52.213s
sys     0m7.991s
```

20,000 positions / 468.7s ≈ **42.7 positions/sec** sustained, single-threaded,
sequential (D-8's v1 design, no worker pool) — roughly 2.5x faster than the ~17
pos/sec calibration estimate at the same node budget. The discrepancy is most likely
sample-size variance in the small calibration set combined with position-dependent
search cost (tactically sharper positions burn more nodes per unit time than quiet
ones; `data/quiet-labeled.epd`'s positions are pre-filtered for quietness, which the
tiny calibration sample may not have represented proportionally) — not investigated
further, since this issue's Non-Scope excludes upgrading `stockfish_label.py` itself
and the real run's own number is what DR-D8 Open Question 4 asked this issue to
produce. At this sustained rate, a 1M-position run would take ~6.5 hours
single-threaded; the DR-D8 §5-§8 worker-pool upgrade remains the documented follow-up
for scaling past a single session's wall-clock budget, not attempted here per
Non-Scope.

## Redo against Stockfish 18 (same session, same input, same node budget)

Mid-session, this session's environment was upgraded from Stockfish 16 (`apt` package,
`/usr/games/stockfish`) to a manually-installed Stockfish 18 release binary
(`/home/coeusyk/.local/bin/stockfish`, now first on `PATH`). The Stage 2 run above was
re-executed identically (same 20,000-FEN input file, same `nodes=25000,
timeout_seconds=30.0, threads=1`) against Stockfish 18 to get a real (not
retroactively-adjusted) manifest and throughput number for the engine version this
dataset actually ships with:

```
$ time uv run python -m scripts.stockfish_label configs/stockfish-label-e2-real.json \
    outputs/datasets/stage2-quiet-sf-input.fen outputs/datasets/stage2-quiet-sf \
    stage2-sf-labeled-quiet-2026-07-15
labeled 20000, skipped 0 -> outputs/datasets/stage2-quiet-sf/shard-0.bin

real    5m1.777s
user    5m6.360s
sys     0m8.304s
```

20,000 / 301.8s ≈ **66.3 positions/sec** — ~1.55x faster than Stockfish 16's 42.7
pos/sec at the identical node budget and position set, consistent with Stockfish 18's
several-generations-newer search/NNUE-eval efficiency doing more useful work per node.
This is the dataset's now-current, superseding manifest (`engine_uci_id: "Stockfish
18"`, its own `engine_binary_sha256`) — the Stockfish-16 run above is retained in this
doc only as a same-machine throughput comparison point across engine versions, not as
a second live dataset.

## Run Configuration (for future throughput regression comparisons)

Documentation only, added per code-review feedback: E-2 itself needs no benchmarking
infrastructure, but a later phase (E-8/E-9/E-10, or any future re-labeling run) asking
"why did labeling get slower?" needs the *complete* search/hardware configuration
recorded alongside the throughput number, not just the throughput alone — a nodes-only
comparison across two runs on different hardware, or with a different `Hash`/`MultiPV`,
is not a valid apples-to-apples comparison.

**This run (current, live dataset):**

| Field | Value |
|---|---|
| Positions | 20,000 |
| Engine | Stockfish 18 |
| Engine binary SHA-256 | `65c1e4dade6102e4f8219be7d24181d25e7e1b6f039a20b3ae49488623ba61e5` |
| Engine path | `/home/coeusyk/.local/bin/stockfish` |
| Nodes per position | 25,000 |
| Threads | 1 (driver hardcodes `setoption name Threads value 1` — not a config knob, see `stockfish_label.py`'s own docstring) |
| Hash | 16 MB (Stockfish's own UCI default — the driver does not send `setoption name Hash`, so whatever the binary defaults to at startup governs; not explicitly pinned) |
| MultiPV | 1 (Stockfish's own UCI default — likewise not sent by the driver) |
| Tablebase (`SyzygyPath`) | disabled (empty by default — not sent by the driver) |
| Timeout per position | 30.0s (driver's own safety timeout; not hit — actual per-position time was ~15ms) |
| Total runtime | 5m1.777s (`real`), 5m6.360s (`user`), 0m8.304s (`sys`) |
| Throughput | 66.3 positions/sec |
| Host OS | Ubuntu 24.04.4 LTS under WSL2 (kernel `5.15.167.4-microsoft-standard-WSL2`) |
| Host CPU | AMD Ryzen 7 7700X (8C/16T reported to the WSL2 VM) |
| Host RAM | 15 GiB reported to the WSL2 VM (not necessarily the physical host's full allocation — WSL2 VMs are memory-capped independently of the Windows host, per this repo's existing "NPS bench on WSL2 is not a valid regression gate" caveat, `CLAUDE.md` §3) |

**Superseded run (Stockfish 16, same input/node-budget, kept only as a same-machine comparison point — not a second live dataset):**

| Field | Value |
|---|---|
| Engine | Stockfish 16 (`apt` package `16-1build1`) |
| Engine path | `/usr/games/stockfish` |
| Nodes per position | 25,000 (identical to the Stockfish 18 run above) |
| Threads / Hash / MultiPV / Tablebase | identical driver behavior to the Stockfish 18 run — none of these are engine-version-dependent settings the driver sends |
| Total runtime | 7m48.715s (`real`) |
| Throughput | 42.7 positions/sec |
| Host | identical machine/OS/CPU/RAM to the Stockfish 18 run above (same session, no hardware change between the two runs) |

Only `nodes`/`depth` and `timeout_seconds` are exposed as config knobs by
`stockfish_label.py` today (`StockfishLabelConfig`, `trainer/scripts/stockfish_label.py`);
`threads` is hardcoded to 1, not a config field at all — `Hash`/`MultiPV`/`SyzygyPath` are
recorded above as "not sent by the driver, Stockfish's own default governs" rather than as
a deliberate choice, since making them configurable is out of this issue's scope
(Non-Scope: no changes to `stockfish_label.py`'s own logic). A future run that needs to pin
these explicitly would require extending `StockfishLabelConfig`, which is a real (if
currently unneeded) follow-up, not implied by anything in this table.
