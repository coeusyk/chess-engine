# Deep Research Report — D-8: Stage 2 Stockfish Labeling Driver

**Status:** Research only. Not an implementation guide. No code. Purpose: determine the
optimal architecture for the Stage 2 Stockfish labeling driver before implementation
begins.

**Scope discipline:** this document does not discuss repository structure, file
layout, or PR breakdown. It determines architecture — component responsibilities,
data flow, process model, failure handling, provenance, and validation strategy — for
a system that takes already-sourced chess positions and produces Stockfish-labeled
training records at scale.

---

## 1. Executive Summary

Stage 2 is an **offline batch labeling job**: given a corpus of chess positions, run a
local Stockfish binary against each one under a fixed, versioned search policy, and
emit labeled records in the same durable format the rest of the training pipeline
already consumes. It is not a self-play data generator (Stage 3, deferred), not a
distributed multi-machine system (no evidence any comparable open-source project runs
this stage across machines — it is uniformly a single-host, multi-process workload),
and not something that needs to interoperate with the Java engine at runtime — the
engine must never depend on Stockfish, and nothing in this design requires it to.

The research converges on five load-bearing conclusions:

1. **A persistent pool of single-threaded, reused Stockfish UCI subprocesses**, driven
   by a **dynamic shared work queue** (not static per-worker sharding), is the correct
   process/scheduling model — directly supported by both the chess-engine survey (lc0's
   subprocess-reuse-plus-pull-model architecture, OpenBench's `genfens` one-process-
   per-thread pattern) and general distributed-systems theory (Cilk work-stealing,
   Ray's default dynamic dispatch) given Stockfish search cost varies 10-100x by
   position.
2. **Reproducibility is conditional, not automatic**, and this must be stated
   explicitly rather than assumed: a fixed **node budget** with **`Threads=1` per
   worker** is deterministic; a time budget or multi-threaded search inside a worker is
   not. This single decision governs the checkpointing/idempotence story, the
   validation strategy, and the provenance schema simultaneously.
3. **Filtering is architecturally split into two tiers**, matching what every surveyed
   engine's ecosystem independently converged on (Grapheus/bullet: raw storage,
   caller-supplied filter function; Ethereal's tuner: filtering entirely upstream of
   the compute-heavy stage): cheap **pre-search structural filters** (legality, check
   status, opening-ply skip, in-run dedup) that avoid wasting a Stockfish search on
   junk, and **post-label quality filters** (mate-score policy, confidence/stability
   capture) that run after a label exists. Generic corpus-level filtering
   (deduplication across runs, phase balancing) is explicitly **not** this stage's job.
4. **Checkpointing is a manifest problem, not a state-machine problem.** A durable,
   atomically-updated record of "which shards, and which positions within an
   in-progress shard, are already committed" — directly modeled on Hadoop's
   `FileOutputCommitter` temp-then-rename pattern and Beam's restriction-checkpoint
   model — is sufficient for exactly-once-effective output under crash/restart, without
   needing a write-ahead log or a distributed coordinator.
5. **No comparable open-source engine publishes a fully general, reusable labeling
   driver.** Every engine surveyed either has no public data-gen code at all
   (Stockfish's current repo, Koivisto), keeps it on a deleted/ephemeral branch
   (Ethereal, Berserk), or solves a narrower problem than this one (Ceres: tablebase
   ground truth, not search-based labels; Stockfish's own historical `gensfen`: self-play
   generation with quiet-PV-walking, not labeling of externally-sourced positions).
   This means Vex is not choosing between competing public reference architectures —
   it is synthesizing one from documented sub-patterns, which is exactly what this
   report's Comparative Analysis and Recommended Architecture sections do.

---

## 2. Problem Definition

**Given:** a corpus of already-sourced chess positions (FENs), reaching potentially
millions in count, none yet labeled with a Stockfish evaluation.

**Produce:** for each position, a training record carrying — at minimum — the
position, a centipawn or forced-mate evaluation from a fixed-policy Stockfish search,
and enough metadata (search depth/nodes, engine identity) to make the label's
provenance and reproducibility auditable later.

**Constraints, stated up front because they shape every downstream decision:**

- The Java engine must never depend on Stockfish, at build time or runtime. Stockfish
  is strictly an offline tool invoked by the Python-side pipeline.
- The workload is large (millions of search calls) and each call's cost is highly
  variable (a quiet middlegame position searches in milliseconds at a modest node
  budget; a position with a long forced tactical sequence at the same node budget can
  take substantially longer per node explored, and depth-based budgets can extend a
  single search dramatically further).
- The process must survive interruption. A run spanning millions of positions will,
  in practice, be stopped and restarted — by choice (a machine reboot, a multi-day run
  split across sessions) or by failure (an engine crash, a stalled subprocess, a
  filesystem error). Losing all progress on any of these is not acceptable at this
  scale.
- Every produced label must be traceable to exactly what produced it — this is a
  hard requirement inherited from the project's own established provenance discipline
  (every other pipeline stage already treats "what produced this artifact" as
  mandatory, not optional), and it is doubly important here because label quality is
  entirely a function of engine version, search policy, and options that are trivial
  to silently drift between runs.
- This is a **single-host, multi-process** problem, not a distributed-systems problem.
  Nothing in the research below found evidence that any comparable project runs
  Stockfish-labeling across multiple machines; the closest multi-machine architecture
  found (lc0's client/server self-play network) solves a different problem (game
  generation with network-level game upload), not single-corpus offline labeling.

---

## 3. Industry Survey

### 3.1 Chess engines and their data-generation tooling

**Stockfish.** The current `official-stockfish/Stockfish` mainline repository contains
no data-generation code at all — no `learn/` or `tools/` directory, and a scoped code
search for `gensfen` returns zero results. The historical NNUE data-gen tool
("gensfen") lives only in the community fork `nodchip/Stockfish`
(`src/learn/gensfen.cpp`), and even the official `nnue-pytorch` repository's own
example script pins an *unofficial* fork commit to run it. `gensfen` is not a
subprocess/UCI-driven external pipeline — it is an in-process UCI verb inside the
engine binary itself, parallelized across Stockfish's own internal thread pool
(`Threads.execute_with_workers`), with output centralized through one dedicated writer
thread that periodically rotates output files every `save_every` positions. Label
generation truncates scores at an `eval_limit` (default 3000, capped at `mate_in(2)`)
— once a search score exceeds that bound, the position is treated as decided and only
the game outcome is recorded, not the raw score. An `ensure_quiet` option actively
walks the search's principal variation to a tactically quiet position before recording
a label, rather than trusting the input position to already be quiet. `fishtest`, the
project's public testing infrastructure, is confirmed to be exclusively an SPRT
game-testing framework, not a labeling pipeline.

**Ethereal, Berserk, Koivisto.** None of these engines' current default-branch
repositories contain public data-generation code. Berserk's own README is explicit
about why: its data-gen tool lived on a separate branch (`fen-gen`), now deleted, once
its era of usefulness passed. Ethereal's README credits the same deleted Berserk
branch as its own historical source. All three engines' current trainer is a shared
project, `Grapheus`, whose own source contains **no filtering logic at all** — it is a
pure batch-loading/training consumer of already-labeled, already-filtered data.
Filtering is therefore, in this ecosystem, architecturally pushed entirely upstream of
the public, shared trainer, into private or since-deleted engine-specific tooling.
Ethereal does ship its own in-tree Texel-style static-evaluation tuner
(`src/tuner.c`), which is a clean primary source for the *original* Texel tuning
method: no search at all (static eval only), a single fixed pre-filtered dataset file
loaded once, and in-process thread-parallel gradient computation over that fixed data
— structurally unrelated to NNUE label generation, which requires real search.

**OpenBench's `genfens` infrastructure.** This is the one genuinely reusable,
fully public primitive found across the Ethereal/Berserk/Koivisto ecosystem: a
standardized UCI extension and orchestration wrapper (`AndyGrant/OpenBench`) for
position/opening generation. Its process model is instructive even though it targets
opening-book generation, not full labeling: **one-shot subprocess per work chunk**
(not an interactive long-lived UCI session), one OS process per configured worker
thread (not shared processes), a shared multiprocessing queue for results, and an
explicit **15-second stall timeout** — if no worker reports within that window, the
code force-kills the engine process by name and raises a typed failure, rather than
hanging indefinitely.

**Leela Chess Zero (lc0).** The best-documented distributed architecture surveyed,
split across an engine repo, a client orchestrator, and a coordination server. The
client spawns `lc0` as a **long-lived subprocess reused across many self-play games**
(not relaunched per game), with an explicit `Retry` signal channel that triggers a
managed restart if the wrapped process fails unexpectedly. Games are generated locally
and uploaded via HTTP with exponential backoff, capped at 3 retries. Scheduling is a
**pull model**: a worker requests its next unit of work from the server rather than
owning a pre-assigned shard, and the server-side Postgres table of uploaded games is
the durable ledger — a worker that dies mid-game simply never uploads that game and
pulls a fresh assignment on restart. Partial games are discarded and regenerated, not
resumed mid-game.

**Ceres / CeresTrain.** Architecturally distinct from every alpha-beta engine
surveyed: Ceres's published results train on **endgame-tablebase-derived ground-truth
WDL labels** (Syzygy, ≤7 pieces), not search-based centipawn labels, explicitly
because the project's own documentation states generating labels "on the fly" is
infeasible at the target scale given implementation-language constraints. Data is
pre-generated in bulk into compressed shard files ahead of any training run, and
distributed training is SSH-coordinated across a fixed host list — a push-checkpoint
model, not a resumable-shard-pull model.

### 3.2 General large-scale data-processing systems

**Checkpointing granularity** varies by what a system tracks as its unit of progress.
Spark Structured Streaming tracks exact per-record offsets via an offset log plus a
write-ahead log, giving record-level resume. Apache Beam's Splittable DoFn model
checkpoints at the level of a "restriction" — a structured description of remaining
work (e.g., an offset range) that a `ProcessContinuation.resume()` signal persists and
hands to a (possibly different) worker later. Ray has no general pipeline-level
checkpoint primitive at all; its fault tolerance is built on stateless, idempotent
task retry — resumability comes from re-submitting the unit of work, not from a
persisted log.

**Atomic, crash-safe output writing** has one dominant, independently-confirmed
pattern across systems: write to a task-scoped temporary location, and make the
output visible only via an atomic rename to its final path once fully written. Both
Beam's `FileIO` (deterministic per-shard naming, commit-on-visibility) and Hadoop's
`FileOutputCommitter` (documented task-attempt-scoped temp directories, atomic rename
on `commitTask`) implement exactly this. A driver-side manifest of which shards have
completed their rename is the standard complement, letting a re-run skip already-committed
work without re-deriving completeness from the output bytes themselves.

**Work distribution under variable task cost** has a well-established theoretical
answer: Cilk's proven work-stealing scheduler shows that idle-driven load balancing
(workers pull more work, or steal from a peer, when their own queue empties) is
provably work-efficient under arbitrary task-cost variance, whereas static
pre-partitioning is provably vulnerable to straggler shards. Ray's scheduler
documents the same principle at the distributed level, framing static node-affinity
placement as an explicit opt-out from its dynamic default, not the default itself.

**Process-pool crash and hang handling** in Python's standard library is
asymmetric and worth stating precisely: `concurrent.futures.ProcessPoolExecutor`
detects abrupt worker *crashes* and raises `BrokenProcessPool`, but this generally
requires recreating the whole executor, not just the failed worker; `multiprocessing.Pool`'s
`maxtasksperchild` provides scheduled worker recycling (useful for bounding
memory growth in a long-lived subprocess-driving worker) but is not crash detection.
Neither primitive detects a **hung-but-alive** subprocess — that is documented,
explicitly, as the caller's responsibility, via an externally enforced per-task
timeout that kills and replaces only the offending worker.

**Label-noise detection** has a specific, citable, well-established technique:
Confident Learning (Northcutt, Jiang & Chuang), implemented in the widely used
`cleanlab` library, which flags likely-mislabeled examples using out-of-sample model
predictions compared against given labels. Its generalization to continuous
(non-categorical) labels — directly applicable to a centipawn evaluation rather than a
class label — is residual/outlier-based filtering: flag examples where a trained
reference model's prediction diverges sharply from the recorded label.

**Provenance/metadata standards**, synthesized across four independent sources
(Datasheets for Datasets' seven-category documentation checklist, W3C PROV's
Entity/Activity/Agent relational model, MLflow's params/metrics/artifacts-per-run
schema, and DVC's content-hash-based data versioning), converge on one minimum set:
the identity of the generating activity (exact tool version/hash, exact options), a
content hash of the produced artifact, a run/experiment identifier tying the artifact
to the exact configuration that produced it, and a human-readable composition/intended-use
note.

**Memory-bounded streaming processing** for corpora too large to hold in memory has
one dominant idiom across every scale surveyed, from a single Python process
(`itertools`'s generator-composition model, explicitly documented as designed for
memory efficiency) up to a distributed batch framework (Beam's `PCollection`, whose
own design principle is "no upper limit on the number of elements... contain"): never
materialize the full corpus, process it as a stream through bounded-size windows.

---

## 4. Comparative Analysis

| Dimension | Stockfish `gensfen` | OpenBench `genfens` (Ethereal/Berserk/Koivisto ecosystem) | lc0 client/server | Ceres/CeresTrain | General data-processing systems (Beam/Spark/Ray/Hadoop) |
|---|---|---|---|---|---|
| Process model | In-process, engine's own thread pool | One-shot subprocess per chunk, one OS process per worker | Long-lived reused subprocess per client | Bulk pre-generation, no online search | N/A (not chess-specific) |
| Scheduling | Internal to engine, no external scheduler | Static: one process per worker thread | Pull-model, server-assigned | Static, pre-generated shards | Dynamic (Ray default), restriction-based (Beam) |
| Crash/hang handling | None documented beyond full-run restart | 15s stall timeout, force-kill by name | `Retry` channel, managed client restart | N/A (not online) | `BrokenProcessPool` (crash only), external timeout (hang) |
| Label determinism | Not addressed (self-play generation, not fixed-position labeling) | N/A (position generation, not evaluation) | N/A (game outcomes, not per-position eval) | Deterministic (tablebase ground truth) | N/A |
| Filtering | In-engine quiet-PV walk, eval-limit truncation | N/A | N/A | N/A (no search-based labels) | Caller-supplied (Beam/Ray), not framework-owned |
| Checkpointing | Periodic file rotation only, no resume-from-N | None (whole-workload retry on stall) | Server DB is the ledger; partial work discarded | Push-checkpoint per host | Manifest/offset-log/restriction (framework-owned) |
| Applicability to Stage 2 | Partial — quiet-search and eval-limit ideas transferable; self-play generation itself is not this stage's job | High — process-pool and timeout pattern directly transferable | Partial — pull-model and subprocess-reuse transferable; network upload model is not relevant (single host) | Low — different problem (ground truth vs. search-based labels); tablebase-position handling is a relevant future idea | High — checkpointing, atomic writes, and work-stealing scheduling patterns transfer directly |

**Synthesis.** No single surveyed system is a template to copy. The right synthesis
is: OpenBench's process-pool-and-timeout model plus lc0's subprocess-reuse-and-pull-model,
for the worker architecture; Beam/Hadoop's checkpoint-and-atomic-write model, for
crash recovery; Cilk/Ray's dynamic-queue model, for scheduling under variable cost;
and Stockfish's own quiet-search and eval-limit ideas, adapted (not copied) for label
quality — with the explicit caveat, discussed in §10, that Stage 2 labels
already-sourced positions rather than generating them via self-play, which changes how
much quiet-search machinery is actually needed.

---

## 5. Recommended Architecture

A single-host pipeline of eight components, matching the decomposition the task
itself names, each with one clear responsibility and a narrow interface to its
neighbors:

```
[position source] → Reader → Scheduler → Labeler (worker pool) → Filter → Writer → [shards]
                                  ↑                                   ↓
                            Checkpointing ←――――――――――――――――――― Recovery
                                  ↓
                               Metrics
```

The **Labeler** owns a fixed-size pool of persistent, single-threaded Stockfish UCI
subprocess workers. The **Scheduler** is a shared, dynamically-drained queue — not a
static per-worker partition — feeding individual positions to whichever worker is
next idle. The **Reader** performs only cheap, pre-search structural filtering before
a position ever reaches the queue. The **Filter** performs only post-label
quality/policy decisions after a label exists. The **Writer** commits fixed-size
shards atomically. **Checkpointing** is a small, separately-durable manifest, not
folded into the shard-writing path. **Recovery** is a startup-time reconciliation
against that manifest, not a runtime concern threaded through every component.
**Metrics** is a passive observer wired to every other component's completion events,
never a dependency any component needs for correctness.

This is deliberately **not** a distributed architecture — every surveyed comparable
system that solves *this specific* problem (labeling an existing corpus, not
generating and uploading self-play games across a network of contributors) does so on
a single host. Distribution, if ever needed, is a future-evolution question (§16), not
a v1 requirement.

---

## 6. Component Responsibilities

- **Reader.** Enumerates the position source (a corpus of FENs, however sourced — this
  document does not prescribe how positions are sourced, only what happens once they
  exist) and applies cheap, pre-search structural filters: FEN legality/parseability,
  side-to-move-not-in-check (a "quiet position" proxy that costs nothing to check),
  opening-ply skip, and in-run deduplication. Its job is to never hand the Labeler a
  position not worth spending a search on. It does not touch the network or spawn
  subprocesses.
- **Scheduler.** Maintains one shared, bounded-size work queue (bounded so the Reader
  cannot run arbitrarily far ahead of the Labeler and exhaust memory) and dispatches
  positions to idle Labeler workers as they become free. Owns no state about *why* a
  position is being labeled — purely a dispatch mechanism.
- **Labeler.** Owns the Stockfish worker pool. Each worker: one persistent,
  single-threaded (`Threads=1`) Stockfish subprocess, reused across many positions
  (never relaunched per position); drives it via synchronous UCI request/response
  (`position fen ...` then `go nodes N` or `go depth N`); enforces a per-position
  wall-clock timeout; on timeout or process death, kills and replaces only that
  worker, never the whole pool.
- **Filter.** Post-label, policy-level decisions only: how a forced-mate score is
  represented in the output record (passed through unmolested — see §10), whether a
  position's score-stability signal (if captured) disqualifies it, and any other
  label-quality gate that requires the label to already exist. Deliberately does
  **not** perform corpus-level deduplication or phase balancing — those are generic,
  format-agnostic concerns this project already has a shared, composable mechanism
  for, applied uniformly to every data source, and duplicating that mechanism here
  would violate the same don't-repeat-yourself discipline the rest of this pipeline
  already follows.
- **Writer.** Streams filtered, labeled records into fixed-size shards using this
  project's existing shard format and atomic per-shard commit convention (temp-file
  write, then atomic rename) — not a new format, and not a new atomicity mechanism;
  both already exist elsewhere in this pipeline and this stage should be a consumer
  of them, not a reinventor.
- **Checkpointing.** A small, separately-durable manifest recording, per shard: which
  positions have been committed to that shard's output. Updated atomically (its own
  temp-then-rename or append-only log), independent of the shard-writing path itself,
  so a crash mid-manifest-update never corrupts the shard data and vice versa.
- **Recovery.** A startup-time step only: read the manifest, determine which shards
  are fully complete (skip them entirely) and which positions within an in-progress
  shard are already committed (re-queue only the remainder). Also owns the
  circuit-breaker policy — if N consecutive worker crashes/timeouts occur without a
  single successful label in between, abort the run with a clear, loud error rather
  than burning the remaining budget respawning a broken binary indefinitely.
- **Metrics.** A passive counter/logger subscribed to completion and failure events
  from every other component: positions labeled per second, per-worker utilization,
  timeout/crash counts, and (if score-stability capture is enabled) its distribution.
  Exists for operational visibility during a multi-hour/multi-day run, not for
  correctness.

---

## 7. Pipeline Data Flow

```
position source
   │
   ▼
Reader  ──(structural pre-filter: legality, not-in-check, opening-ply skip, in-run dedup)──▶
   │
   ▼
Scheduler (shared dynamic queue, bounded depth)
   │
   ▼
Labeler worker pool (N persistent, single-threaded Stockfish subprocesses;
                      per-position timeout; kill+respawn on stall/crash)
   │
   ▼
Filter  ──(mate-score passthrough policy; optional confidence capture)──▶
   │
   ▼
Writer  ──(fixed-size shards; atomic per-shard commit)──▶
   │
   ├──▶ shard files (the actual output)
   └──▶ Checkpointing manifest (updated atomically, independent of shard writes)

Metrics observes every arrow above; Recovery reads the Checkpointing manifest once,
at startup, before the Reader emits anything.
```

Backpressure flows right-to-left: the Scheduler's queue is bounded, so a slow Labeler
pool (all workers busy on expensive positions) naturally stalls the Reader rather than
letting it race arbitrarily far ahead and exhaust memory — this is the same principle
Beam's unbounded-but-windowed `PCollection` model and Python's own generator/`itertools`
idiom both express: never materialize more than the pipeline can currently consume.

---

## 8. Worker Architecture

Each Labeler worker is a single OS process, not a thread — UCI is a stateful,
sequential, single-command-in-flight protocol per the specification's own model, so
sharing one Stockfish subprocess across multiple threads would require serializing
access anyway, at which point a dedicated process per worker (no shared mutable state,
crash isolation for free from the OS) is strictly simpler and matches every surveyed
system's own choice (OpenBench: one process per worker thread; lc0: one subprocess per
client).

**Pool sizing.** Worker count should default to a modest reservation below total
available cores (leaving room for the orchestrating process itself and the OS), not
to the full core count — and each worker's Stockfish instance should run with
`Threads=1`, never internally multi-threaded. Running N single-threaded workers
saturates the same core budget as fewer multi-threaded workers would, without the
determinism cost multi-threaded search carries (§10).

**Lifecycle.** A worker's Stockfish subprocess is launched once and reused across
many positions — not relaunched per position, matching lc0's explicit design choice
and avoiding the real, nontrivial cost of repeated process startup (and, if the
labeling engine has any NNUE evaluation file configured, repeated network-load
overhead) that a per-position subprocess model would pay millions of times over.

**Timeout and recovery.** Because neither of Python's standard process-pool
primitives detects a hung-but-alive subprocess, the Scheduler side must enforce an
explicit per-position wall-clock deadline. On timeout: kill that worker's subprocess,
discard the in-flight position back onto the queue (it was never committed, so
redelivery is safe — see idempotence, §12), and replace the worker with a fresh
Stockfish subprocess. This never blocks or restarts the rest of the pool. OpenBench's
15-second stall timeout is a reasonable reference point, though the right value here
depends on the configured node/depth budget and should scale with it, not be a fixed
constant independent of search cost.

**Crash vs. hang.** A worker whose subprocess exits (crash) and a worker whose
subprocess is alive but unresponsive (hang) require the same recovery action —
kill-if-still-alive, respawn, redeliver the in-flight position — so the recovery path
should be unified rather than split into two separate code paths keyed on which
failure mode occurred.

---

## 9. UCI Protocol Strategy

**Command shape.** For each position: `position fen <fen>` followed by `go nodes N`
(preferred) or `go depth N`, then a blocking read loop until a `bestmove` line
appears, extracting the final `info ... score cp X` (or `score mate Y`) line seen
before it. This is a synchronous request/response cycle per position — UCI has no
native multi-position batch verb, so "batching" in practice means keeping one process
alive across many sequential request/response cycles (engine reuse), not any
protocol-level batch command.

**Synchronization.** Exactly one worker (one OS process) owns each Stockfish
subprocess exclusively for its entire lifetime. No cross-worker sharing of a single
subprocess, and no overlapping in-flight commands on one subprocess — UCI's
documented model assumes a synchronous single-command-in-flight sequence per engine
instance.

**Error handling.** Permissive on unrecognized output (ignore any `info` line that
doesn't match the expected score-line shape, rather than treating an unfamiliar line
as fatal — Stockfish's `info` stream carries many fields this pipeline has no use
for), but strict on the terminal condition: a label is only accepted once a
well-formed `bestmove` line has been observed, preceded by at least one well-formed
score line. A search that completes without ever emitting a parseable score line is
treated as a failure for that position (logged, not silently accepted with a
missing/default value), matching this pipeline's own established convention elsewhere
of failing loudly on data it cannot honestly represent rather than silently
substituting a default.

**Process recovery.** On any UCI protocol violation severe enough that the worker's
internal state can no longer be trusted (a `bestmove` that never arrives before
timeout, an engine process that exits mid-command), the correct action is not to
attempt protocol-level recovery (re-synchronizing with a possibly-confused engine
process) but to kill and fully replace the subprocess — cheaper and strictly safer
than trying to recover UCI-level synchronization with a process already known to be
misbehaving.

---

## 10. Dataset Quality Strategy

**Two-tier filtering, not one.** Pre-search filters (Reader) exist to avoid spending
a Stockfish search on a position not worth labeling at all: illegal or unparseable
FENs, the side to move already in check (a cheap, well-established quiet-position
proxy every surveyed Texel-tuning pipeline applies upstream of its compute-heavy
stage), positions within the configured opening-ply skip, and positions already seen
within the current run (in-run deduplication, cheap relative to a search call).
Post-label filters (Filter) exist to make policy decisions that can only be made once
a label exists: how a forced-mate score is represented, and (optionally) whether a
position's search-score stability disqualifies it from the output.

**Forced mates: pass through, don't collapse.** A labeling driver should record
`eval_mate` as its own distinct field when Stockfish reports a mate score, rather than
converting it to a stand-in centipawn value at labeling time. Collapsing a mate score
into an approximate centipawn equivalent is a training-time concern (how should a
"certain win" be weighted against a large-but-uncertain centipawn score in the loss
function), not a labeling-time concern — conflating the two would duplicate a decision
this pipeline's training stage already needs to make independently, and would make
that decision harder to revise later since it would be baked irreversibly into the
stored label rather than computed at consumption time.

**Full self-play-style quiet-search walking is not needed here, and adding it would
be over-engineering for this stage's actual job.** Stockfish's own `gensfen` performs
an active PV-walk to find a quiet position because it is *generating* positions via
self-play from an essentially arbitrary starting point and has no other guarantee of
quietness. Stage 2, as scoped, labels **already-sourced** positions (drawn from real
games via an existing quiet/not-in-check extraction step this project already has),
so a cheap not-in-check check at the Reader stage is a proportionate substitute — not
a downgrade, a match to the actual input distribution's needs. If a future data
source feeds Stage 2 arbitrary/synthetic positions with no such upstream guarantee,
this tradeoff should be revisited then, not preemptively built for now.

**Tablebase-range positions.** Positions with few enough pieces that exact
tablebase-derived WDL is available (a class Ceres's own approach exploits fully) are
architecturally a genuine special case — a search-based centipawn label is strictly
lower-quality information than the exact known outcome, for the same position, at
higher cost. This is real but out of this document's recommended v1 scope: neither
ADR-007 nor the issue driving this stage calls for tablebase integration, and
building it without a demonstrated need would be speculative. Recorded here as a
clearly-flagged future-evolution item (§16), not silently ignored.

**Confidence/score-stability capture is optional, not mandatory.** Stockfish's
iterative-deepening search naturally exposes multiple `info depth N score cp X` lines
per search; the variance of the score across the final few depths is a real, cheap
signal for how "settled" a label is. Capturing it costs only additional parsing of
output the pipeline is already reading — but consuming it (filtering on it, or
weighting training loss by it) is downstream work this stage does not need to solve.
Recommended as an optional metadata field, not a mandatory filter, so the decision of
how to use it can be made later with real data in hand.

**What this stage explicitly does not own.** Cross-run deduplication and phase
balancing are corpus-level, format-agnostic concerns this project already solves
generically, uniformly across every data source. Duplicating that logic inside the
labeling driver would create two divergent implementations of the same idea — a
maintenance liability with no benefit, since the generic mechanism already runs
downstream of every `DatasetProvider`, including whatever thin reader eventually
exposes this stage's output to the rest of the pipeline.

---

## 11. Provenance Strategy

Every labeling run must record enough to answer "what produced this exact label" for
any single record in its output, without asking anyone. Synthesizing the general
ML-provenance research (§3.2) against this project's own already-established
provenance conventions (a training run's identity is captured once, at run start; a
network export's identity is captured once, at export time — never partially,
never reconstructed after the fact):

- **Engine identity.** The Stockfish binary's self-reported UCI identity (`id name`),
  captured directly from the protocol rather than assumed from a file name — plus,
  for stronger-than-self-reported-string identity (a self-reported version string can
  be ambiguous for a locally built or patched binary), a content hash of the
  executable itself.
- **Evaluation source.** Whether the labeling engine used its own NNUE evaluation (and
  if so, which network file) or classical evaluation. This matters more than it might
  first appear: if Stockfish labels positions using its own NNUE, the resulting labels
  are influenced by whatever network that Stockfish binary ships with or was
  configured to use — a future Stockfish build with a different default network would
  silently produce systematically different labels from an otherwise-identical run.
  This field closes that gap.
- **Search policy.** The exact node or depth budget used, `Threads` setting (expected
  to be `1` per §10/§12's determinism argument — recorded regardless, as an explicit
  confirmation, not an assumption), hash table size, and any other non-default UCI
  option set.
- **Exact invocation.** The command/configuration that started the run, sufficient to
  reproduce it verbatim.
- **Temporal and code identity.** Run date, and this project's own trainer commit hash
  (the same field every other pipeline stage already threads through its provenance
  chain) — not a separately-invented identity scheme.
- **Dataset identity.** A dataset identifier and stage label for the labeling run's
  output, matching the shape this project's provenance chain already uses for every
  other dataset source, so a downstream consumer treats Stage 2 output no differently
  from Stage 1 output at the metadata level.
- **Randomness, if any.** A random seed is only meaningful here if some part of the
  pipeline is non-deterministic by design (e.g., if work-queue dispatch order is ever
  made seed-reproducible for exact replay purposes — not required for correctness,
  since label *values* don't depend on dispatch order, only wall-clock throughput
  does). If nothing in the pipeline actually consumes randomness, this field should
  say so explicitly rather than being silently absent.
- **Hardware.** CPU identity/core count, recorded because it affects wall-clock
  throughput and is relevant context for any time-budget-based run (even though
  node-budget-based runs are the recommended default specifically to reduce
  hardware-sensitivity of the *labels themselves*, per §10).
- **Feature specification version — explicitly not this stage's concern.** Stage 2
  produces positions and labels, not encoded features; feature encoding happens in a
  later pipeline stage this document's scope does not cover. Recorded here only to
  head off a plausible over-scoping mistake, not because this stage needs to track it.

---

## 12. Failure Recovery Strategy

**Idempotence is conditional on the determinism decision already made in §10.** With
`Threads=1` and a fixed node budget, labeling the same position twice with the same
engine binary and options produces the same label — so at-least-once redelivery of an
in-flight-at-crash-time position (the natural consequence of the checkpoint/recovery
design below) is safe by construction, not by careful bookkeeping. This would not hold
under multi-threaded per-worker search, where a second labeling of the same position
could legitimately produce a slightly different score — one more concrete reason
`Threads=1` is the recommended default, not merely a reproducibility nicety.

**Checkpoint granularity.** A durable manifest tracking, per shard, which positions
have been committed — not a byte-offset log (unnecessary precision for this
workload's actual failure mode) and not shard-level-only tracking (too coarse; a
crash partway through a large shard should not force re-labeling everything already
committed within it). This mirrors Beam's restriction-checkpoint model scaled down to
this workload's actual granularity, and is cheaper to implement than Spark's full
offset-log/WAL model, which solves a harder problem (exactly-once streaming semantics)
than this batch workload actually has.

**Atomicity, two layers.** Shard output files are committed via write-to-temp-then-atomic-rename
— the same pattern this project already uses for other artifacts, not a new mechanism.
The checkpoint manifest itself needs the same treatment independently: a crash
mid-manifest-update must never corrupt the recovery state, so the manifest's own
writes should be atomic and independent of the shard-writing path's atomicity, not
coupled to it (a single combined atomic operation covering both a shard file and the
manifest simultaneously is unnecessary complexity — sequential independent atomicity
for each is sufficient, since the worst outcome of an interrupted second write is a
handful of already-labeled positions being redundantly re-labeled on resume, which
§12's idempotence argument already establishes is safe).

**Resume behavior.** On restart: read the manifest before emitting any work; fully
skip shards marked complete; for an in-progress shard, re-queue only the positions not
yet recorded as committed. This bounds wasted re-labeling work to, at most, whatever
was in-flight (issued to a worker but not yet committed) at the moment of
interruption — never the whole shard, and never the whole run.

**Circuit breaker.** Recovery must distinguish "a worker crashed once, respawn it" from
"the labeling engine itself is broken and every worker keeps crashing." A run that
sees N consecutive failures with zero successful labels in between should abort
loudly with a clear diagnostic, rather than silently respawning an unbounded number of
times and burning the entire run's wall-clock budget on a binary that will never
succeed.

---

## 13. Performance Strategy

**Streaming discipline throughout, not just at the boundaries.** Every component in
§7's data flow should be expressible as a generator/iterator over its input, never
materializing more than one bounded batch at a time — matching both the general
`itertools`/generator idiom (§3.2) and this project's own already-established
streaming contract for how labeled positions are consumed downstream (an existing
project convention already requires a data source not to load a full shard into
memory at once; a labeling driver producing that same kind of shard should hold
itself to the identical discipline as a producer, not merely rely on downstream code
enforcing it as a consumer).

**Reuse the existing shard format, not a new one.** This project already has a
streaming, batched shard writer used by its first data source. Stage 2's Writer
component should target that same format rather than inventing a second one — not
because "reuse" is a default virtue in the abstract, but because a second shard format
would force every downstream consumer (encoding, training) to branch on which format
it's reading, for no benefit this stage's actual requirements demand.

**Compression: not recommended for v1.** CeresTrain's zstd-compressed shard format is
a legitimate reference point, but for fixed-size structured binary records at this
project's current scale, the complexity of adding a compression layer is not
justified without a demonstrated disk-footprint problem. Flagged as a future-evolution
item (§16), not built preemptively.

**Worker-pool sizing should scale with, not saturate, available hardware.** A
reasonable default reserves headroom below full core count for the orchestrating
process and OS, and sizes the worker pool to that reservation with each worker
single-threaded — never fewer multi-threaded workers occupying the same core budget,
for the determinism reasons already established (§10, §12), independent of any
throughput argument.

**Disk I/O.** Fixed-size shard rotation (write N positions, close and commit the
shard, start a new one) bounds both memory (never holding an unbounded shard in
memory before writing) and blast radius (a corrupted or lost shard loses at most one
shard's worth of labels, not the whole run's output) — the same rationale Stockfish's
own `gensfen` applies with its `save_every` rotation, arrived at independently here
from this project's own atomic-shard-commit convention rather than copied from it.

---

## 14. Validation Strategy

**Correctness: a golden-fixture regression test.** A small, fixed corpus of known
positions, labeled once under a pinned engine version and search policy, with the
resulting scores committed and asserted against on every future run of the labeling
driver's own test suite. This is not a new pattern for this project — it mirrors an
already-established golden-file regression discipline used elsewhere in this
pipeline for exactly the same reason (catching silent behavioral drift in something
whose correctness can't be checked by inspection alone).

**Reproducibility: an explicit two-run determinism check.** Given §10/§12's
conditional-determinism argument (`Threads=1`, fixed node budget), the driver's own
test suite should assert this concretely: run the same tiny fixture twice, through the
full pipeline, and assert identical output — the same shape of check this project
already applies to its export pipeline's own reproducibility guarantee, applied here
to labeling instead of export. This is the single most important validation this
stage needs, because label reproducibility is a load-bearing assumption for
everything the provenance chain (§11) claims to guarantee.

**Deterministic replay is a corollary of provenance, not a separate mechanism.**
Because §11 already mandates capturing exact engine identity, exact search policy, and
exact input position set, a "replay" of any labeling run is definitionally just
re-running the driver with that exact captured configuration against that exact
input — no separate replay tooling needs to be designed or built; the provenance
record *is* the replay recipe.

**What validation does not need to do at this stage.** Confident-Learning-style
post-hoc label-quality auditing (§3.2, §10) requires a trained model to compare
against — it is a downstream validation concern (this project's existing validation
stage already has a natural home for an eval-scale-style check), not something the
labeling driver itself can meaningfully perform in isolation before any model exists.
Flagged here so it isn't silently expected of this stage.

---

## 15. Security Considerations

**Malformed positions.** Every position reaching the Labeler should already have
passed a legality/parseability check at the Reader stage (§10) — defense in depth,
not redundant, since a defensively-validated boundary is cheaper to reason about than
trusting every upstream source to have already validated correctly.

**Untrusted/externally-sourced input.** If this stage is ever pointed at an
externally-sourced position corpus (not just internally-curated games), that input
should be treated as untrusted at the Reader boundary — validated and sanitized
there, consistent with this project's own general engineering discipline of
validating only at system boundaries rather than defensively re-checking already-trusted
internal data everywhere. A malformed or adversarially-crafted input file should
never be able to crash the pipeline or cause unbounded resource consumption (for
example, an absurdly long synthetic game or a position string containing unexpected
control characters).

**Broken engine binaries.** The circuit-breaker policy in §12 is this stage's primary
defense against a broken or misbehaving Stockfish binary — repeated failure is
detected and the run aborts loudly, rather than silently consuming the entire
run's compute budget respawning a binary that will never produce a valid label.

**Unexpected UCI output.** §9's permissive-on-unrecognized-lines,
strict-on-terminal-condition parsing strategy is itself the security-relevant
decision here: a compromised, buggy, or simply unfamiliar Stockfish build should never
be able to cause a label to be silently accepted from malformed or partial output —
the parser's strictness on what counts as a valid `bestmove`/score pair is the actual
safety boundary, not an incidental parsing detail.

**Filesystem failures.** A full disk, a permissions error, or any other failure
during a shard or manifest write must fail the run loudly and visibly, never silently
drop the affected positions — matching this project's own established precedent
elsewhere of raising loudly on data that cannot be honestly represented, rather than
silently discarding it.

---

## 16. Future Evolution

- **Tablebase-aware labeling** (§10): special-case positions within available
  tablebase range to use exact WDL instead of a search-approximated score, once a
  concrete need or measured quality gap motivates it.
- **Confidence-weighted training** (§10, §14): once score-stability metadata exists
  and a first trained model exists, wire Confident-Learning-style residual filtering
  into the existing downstream validation stage.
- **Compression** (§13): revisit if disk footprint becomes a measured constraint at
  real corpus scale, not preemptively.
- **Multi-host distribution**: no comparable system surveyed runs this specific
  workload (labeling an existing corpus) across multiple machines; if corpus size ever
  demands it, the manifest-based checkpoint design in §12 generalizes naturally to a
  pull-model coordinator (structurally similar to lc0's server, but coordinating shard
  claims rather than uploaded games) — but this is speculative and unbuilt until a
  real need appears.
- **WDL capture from Stockfish's own internal win-rate model**, where available in a
  given Stockfish build, as an additional optional label source — noted as available,
  not required, since the project's own staged-data plan reserves WDL-as-primary-label
  for a later stage.

---

## 17. Alternatives Rejected

- **Static per-worker sharding of the position corpus**, rejected in favor of a
  dynamic shared queue, because Stockfish search cost variance (roughly 10-100x
  between quiet and deep-tactical positions) makes static sharding provably vulnerable
  to straggler shards (§3.2's Cilk/Ray evidence), with no offsetting benefit for this
  workload's actual constraints (no cross-host network cost to avoid by pre-partitioning,
  since this is single-host).
- **One Stockfish subprocess per position** (launch, search, exit, repeat), rejected
  in favor of persistent reused workers, because repeated process/engine startup cost
  paid millions of times over is a real, avoidable throughput cost every surveyed
  reused-subprocess system (lc0, OpenBench) avoids for the same reason.
- **Time-budget-based search** as the default label-generation policy, rejected in
  favor of a node budget, because wall-clock time is inherently hardware- and
  system-load-dependent, undermining the reproducibility guarantee §11's provenance
  chain and §14's validation strategy both depend on; depth-based search is a
  documented, simpler middle ground (also supported) but is somewhat less
  strictly deterministic than a node budget under some engines' internal accounting,
  so node-based is the stronger default recommendation.
- **Full self-play-style active quiet-search PV-walking** (Stockfish's own `gensfen`
  approach), rejected as unnecessary complexity for this stage specifically — not
  rejected as a bad idea in general, but as a mismatch for a stage that labels
  already-sourced, already-largely-quiet positions rather than generating arbitrary
  ones from scratch (§10's detailed reasoning).
- **A write-ahead-log/exactly-once-streaming checkpoint model** (Spark's full
  approach), rejected as solving a harder problem than this batch workload actually
  has; a shard-and-position-level completion manifest (Beam-restriction-inspired,
  scaled down) is sufficient and substantially simpler to build and reason about.
- **Building a general-purpose distributed labeling coordinator up front**, rejected
  because no surveyed comparable system needed one for this exact problem, and
  building distributed-systems machinery ahead of a demonstrated single-host
  throughput ceiling would be speculative generality with no current justification.

---

## 18. Risks

- **Non-determinism from accidental multi-threading.** If a future change
  inadvertently sets a worker's Stockfish `Threads` above 1 (a plausible slip if
  someone later "optimizes" throughput without understanding why single-threaded
  workers were chosen), every downstream reproducibility guarantee in §11/§12/§14
  silently degrades without any visible symptom until someone specifically notices
  labels aren't reproducible. Mitigation: this should be an explicit, tested invariant
  (§14's two-run determinism check exists precisely to catch this class of
  regression), not just a documented convention.
- **Label quality is only as good as the search policy**, and a search policy that's
  too shallow/cheap for throughput reasons produces systematically worse labels than
  one confirmed to teach a net anything — this is a real, unavoidable tradeoff between
  throughput and quality that no architectural choice in this document eliminates,
  only makes visible and measurable (via the retraining-is-measured-not-assumed
  discipline this project's own staged-data decision already commits to).
- **Silent drift in engine identity across a long-running or resumed job.** If a
  Stockfish binary is upgraded mid-run (accidentally or deliberately) without
  restarting cleanly, positions labeled before and after the change would carry
  different, unrecorded-per-record label semantics under one nominal run identity.
  Mitigation: capture engine identity at worker-startup time, not just run-start
  time, and treat a mismatch between a resumed run's recorded engine identity and its
  current environment as a hard error, not a warning.
- **Filter-policy decisions made too early get baked into stored data.** Any filter
  applied at labeling time (rather than deferred to a later, revisable stage) is
  effectively irreversible without re-labeling — this is the core reasoning behind
  keeping the Filter component's scope deliberately thin (§6, §10): every decision
  pushed into the Filter stage is a decision that becomes expensive to revisit later.

---

## 19. Open Questions

These are architecture-relevant unknowns this document does not resolve, because
resolving them requires information (real hardware, real corpus scale, real measured
throughput) that doesn't exist yet:

1. What node/depth budget best balances label quality against achievable throughput
   at this project's actual target corpus scale? This is an empirical question,
   answerable only once the driver exists and can be measured against, not a design
   question this document can settle in advance.
2. Should score-stability/confidence metadata be captured from the first
   implementation, or added later once there's a concrete downstream consumer for it?
   §10 recommends treating it as optional; whether "optional and unused" is worth its
   implementation cost even at v1 is a judgment call outside this document's scope.
3. What is the right per-position timeout value, and should it be a fixed constant or
   scaled dynamically from the configured node/depth budget? §8 argues it should scale
   with search cost but does not prescribe an exact formula.
4. Is single-host throughput actually sufficient for the corpus scale this project
   ultimately needs, or will multi-host distribution (§16) become a real requirement
   sooner than currently assumed? Unknowable without a measured single-host baseline.

---

## 20. References

**Chess engines and tooling** (all findings verified against primary source files via
direct repository inspection, not secondary summaries):
- `official-stockfish/Stockfish` (mainline repository, `src/` tree)
- `nodchip/Stockfish`, `src/learn/{gensfen.cpp,gensfen.h,sfen_writer.h,convert.cpp}`
- `official-stockfish/nnue-pytorch`, `scripts/gensfen.sh`
- `official-stockfish/fishtest`, `docs/6-worker.md`
- `AndyGrant/Ethereal`, `README.md`, `src/tuner.c`, `src/tuner.h`
- `jhonnold/Berserk`, `README.md`
- `Luecx/Koivisto`
- `Luecx/Grapheus`, `src/dataset/{batchloader.h,dataset.h,io.h,process.h}`
- `LeelaChessZero/lc0`, `LeelaChessZero/lczero-client` (`lc0_main.go`),
  `LeelaChessZero/lczero-server` (`README.md`)
- `dje-dev/Ceres`, `dje-dev/CeresTrain` (`README.md`, `text/distributed_training.md`)
- `AndyGrant/OpenBench`, `Client/genfens.py`, `Client/worker.py`,
  `Scripts/genfens_engine.py`
- `jw1912/bullet`, `docs/3-data.md`

**General data-processing and distributed-systems sources:**
- Apache Spark Structured Streaming documentation (spark.apache.org/docs/latest/streaming/)
- Apache Beam Programming Guide, §12 "Splittable DoFns" (beam.apache.org/documentation/programming-guide/)
- Apache Beam `FileIO` API reference (beam.apache.org/releases/javadoc/current/)
- Ray Task Fault Tolerance documentation (docs.ray.io/en/latest/ray-core/fault_tolerance/tasks.html)
- Ray Scheduling documentation (docs.ray.io/en/latest/ray-core/scheduling/index.html)
- Hadoop `mapred-default.xml`, `FileOutputCommitter` algorithm documentation
- Blumofe & Leiserson, "Scheduling Multithreaded Computations by Work Stealing," J.ACM 1999
- Python `concurrent.futures` and `multiprocessing` standard library documentation
- Northcutt, Jiang & Chuang, "Confident Learning: Estimating Uncertainty in Dataset
  Labels," arXiv:1911.00068 / JAIR 2021
- `cleanlab` reference implementation (github.com/cleanlab/cleanlab)
- Gebru et al., "Datasheets for Datasets," arXiv:1803.09010
- W3C PROV-Overview, W3C Working Group Note
- MLflow Tracking documentation (mlflow.org/docs/latest/ml/tracking/)
- DVC "Versioning Data and Models" documentation (dvc.org)
- Python `itertools` standard library documentation

**Project-internal context** (this project's own prior architectural decisions and
established conventions, referenced for continuity, not re-derived here): ADR-007
(staged training data), the project's DatasetProvider contract and its existing
Invariant 1 isolation guarantee, its existing atomic-write and streaming-shard
conventions, its existing golden-fixture and two-run-determinism testing conventions,
and its existing per-run provenance-metadata conventions.

---

## Recommendations for Vex

**Recommended architecture.** Eight components — Reader, Scheduler, Labeler, Filter,
Writer, Checkpointing, Recovery, Metrics — as detailed in §5-§8, wired as the linear
pipeline in §7, running as a single-host, multi-process batch job. `[Known pattern]`

**Component boundaries that must not blur:**
- The Labeler's worker pool and the Scheduler's dispatch queue must be dynamic, not
  statically pre-sharded — this is the single highest-leverage architectural decision
  in this document, directly caused by Stockfish's variable per-position search cost.
  `[First principles]`
- The Filter component owns only label-existing-dependent policy (mate-score
  handling, optional confidence capture) — it must not duplicate corpus-level
  deduplication or phase balancing, which belong to this project's existing generic,
  shared post-processing mechanism, applied uniformly across every data source.
  `[Context-dependent]` — specific to this project already having that shared
  mechanism; a project without one would need to make this filtering the labeling
  driver's own responsibility instead.
- Checkpointing (the manifest) and shard-writing (the Writer) must remain
  independently atomic, not coupled into one combined transaction — sequential
  independent atomicity is sufficient given this workload's idempotence guarantee.
  `[First principles]`

**Invariants that must never be violated:**
1. The Java engine must never depend on Stockfish, at build or runtime — this
   document's entire design is offline/Python-side by construction, and nothing in it
   requires or implies otherwise. `[Known pattern]` — directly inherited from this
   project's own existing, already-frozen engine/trainer isolation invariant.
2. Every Labeler worker's Stockfish instance runs single-threaded (`Threads=1`) with a
   fixed node (preferred) or depth budget — this is the load-bearing assumption behind
   every reproducibility, idempotence, and validation claim in this document.
   `[First principles]`
3. A labeling run's full provenance (engine identity/hash, evaluation source, search
   policy, exact invocation, commit, dataset identity) must be captured once, at run
   start, and never reconstructed after the fact. `[Known pattern]` — matches this
   project's own existing provenance discipline for every other pipeline stage.
4. Output must never be silently partial or silently dropped on any failure
   (filesystem error, worker crash, malformed engine output) — every failure mode must
   either recover automatically (worker respawn) or fail the run loudly (circuit
   breaker, filesystem error). `[Known pattern]` — matches this project's own existing
   fail-loudly-not-silently convention.

**What should intentionally NOT be implemented during D-8:**
- Self-play position generation of any kind (Stage 3, explicitly a later phase per
  ADR-007). `[Known pattern]`
- Tablebase-aware special-case labeling. `[Context-dependent]` — a real, valid idea
  with no current concrete need forcing it; building it now would be speculative.
- Any multi-host/distributed coordination mechanism. `[First principles]` — no
  surveyed comparable system needed one for this exact problem, and no evidence
  single-host throughput is actually insufficient yet.
- Compression of shard output. `[Context-dependent]` — revisit only if disk footprint
  becomes a measured, real constraint.
- Confident-Learning-style post-hoc label auditing. `[First principles]` — structurally
  requires a trained model to compare against, which cannot exist before this stage's
  own output does; it belongs in this project's existing downstream validation stage,
  not in the labeling driver itself.
- WDL capture from Stockfish's internal win-rate model. `[Context-dependent]` — this
  project's own staged-data plan reserves WDL-as-primary-label for a later stage;
  capturing it opportunistically now would be scope creep against that plan without a
  stated need.

**What should be deferred to later phases:**
- Multi-host distribution, if single-host throughput ever proves genuinely
  insufficient at real corpus scale (§16, §19). `[Context-dependent]`
- Score-stability-weighted training loss, once the metadata exists and a first
  trained model can validate whether it matters (§10, §16). `[Context-dependent]`
- Any revisiting of the `Threads=1`/node-budget determinism default, only if a
  measured throughput ceiling under that constraint proves to be the actual
  bottleneck rather than a comfortable margin — not preemptively. `[Context-dependent]`
