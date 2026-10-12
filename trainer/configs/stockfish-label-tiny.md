# `stockfish-label-tiny.json` — provenance of each value

A config for exercising the Stage 2 labeling driver's plumbing (D-8, issue #199) —
not a calibrated production labeling run.

- `engine_path: "/path/to/stockfish"` — **a literal placeholder, not a portable
  default.** Unlike this project's own committed test fixtures, a Stockfish binary
  is intentionally not vendored into the repository (it's a local/system install,
  and its exact location varies by machine/OS/package manager — e.g. this session's
  dev environment has it at `/usr/games/stockfish` via `apt`, which is not a
  portable assumption). Edit this field to your local Stockfish binary's real path
  before running `stockfish_label.py` against this config; the driver validates it
  at startup (a missing/non-executable path fails loudly, per `subprocess.Popen`'s
  own `FileNotFoundError`) rather than silently no-op-ing.
- `nodes: 10000` — a small, fast node budget chosen only to keep a plumbing run
  quick (well under a second per position on typical hardware), not a quality-tuned
  value for real training data. `docs/architecture/research/DR-D8-stockfish-labeling-driver.md`
  Open Question 1 explicitly leaves "what node budget best balances label quality
  against throughput at real corpus scale" unresolved — an empirical question for
  whoever runs a real labeling pass, not something this plumbing config answers.
- `timeout_seconds: 30.0` — generous relative to `nodes: 10000`'s expected
  sub-second real search time; sized for plumbing-run robustness (tolerating a
  loaded/slow CI or dev machine), not tuned as a production timeout policy. DR-D8
  Section 8 notes the right value should scale with the configured node/depth
  budget in a real deployment, not be a fixed constant independent of it.
- `depth` is intentionally absent — `nodes` and `depth` are mutually exclusive
  (`StockfishLabelConfig.__post_init__` enforces exactly one), and `nodes` is this
  driver's recommended default for reproducibility (DR-D8 Section 10/17: a node
  budget is deterministic across hardware in a way wall-clock time is not, and is a
  strictly safer reproducibility default than depth-based search under some
  engines' internal accounting).
