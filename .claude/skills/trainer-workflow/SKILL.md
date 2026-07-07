---
name: trainer-workflow
description: Guide NNUE training end-to-end — dataset generation, Stockfish labeling, self-play, validation, training config, quantization, .nnue export, provenance manifests, release reports. Use when the user says "train a net", "generate training data", "label positions", "export the nnue", or plans/reviews a training run. Advisory only — never writes training code; verifies setup and reproducibility.
---

# Trainer Workflow

You are the training-run supervisor, not the implementer. **Never write training code directly.** Your job at every stage: verify reproducibility, verify datasets, verify metadata, verify experiment setup — and flag what's missing before compute is spent.

**Always finish every invocation by generating a reproducible experiment checklist** (see bottom) filled in with the run's actual values, with unknowns marked `TODO`.

## Stage guidance

Work through whichever stages the user is at. For each, check the items and report gaps.

### 1. Dataset generation
- Source declared: self-play, existing PGN corpus, or downloaded dataset (with URL + hash).
- Position filters recorded: min/max ply, no in-check positions, no immediate-capture bestmove (if filtering noisy labels), material/eval bounds.
- Seeds fixed and logged for any randomized opening book or move selection.
- Target size stated with rationale (positions per epoch × epochs).

### 2. Stockfish labeling
- Exact Stockfish version + net (or classical), binary hash.
- Search limit recorded: fixed depth vs fixed nodes (prefer nodes for reproducibility across hardware).
- Score convention: white-relative vs side-to-move — must match trainer's expectation.
- Mate scores handled (clamped or excluded); units (cp) and any WDL conversion documented.

### 3. Self-play
- Engine version/commit for both sides, time control or node limit, adjudication rules (resign/draw thresholds), opening book + seed.
- Concurrency doesn't leak nondeterminism into the dataset ordering assumption (shuffle later anyway).

### 4. Dataset validation (verify before training)
- Legal-position check on a sample; FEN round-trip.
- Label sanity: eval distribution (mean ≈ 0 on balanced corpora, no truncation spikes at clamp bounds).
- Dedup rate measured; train/validation split is by game, not by position (avoid leakage).
- Counts and file hashes recorded.

### 5. Feature encoding
- Feature set named exactly (e.g. HalfKP, HalfKAv2, king buckets count).
- Encoder version matches the engine's inference-side extractor — same index formula. Cross-check one position's active features between trainer and engine.
- Perspective/mirroring convention documented.

### 6. Training configuration
- All hyperparameters in a config file, not CLI history: LR schedule, batch size, epochs, loss (MSE vs sigmoid-scaled/WDL blend, scaling constant K), optimizer, weight decay.
- Random seed fixed; framework + version pinned; hardware noted.
- Resume/checkpoint policy stated.

### 7. Validation metrics
- Held-out loss tracked per epoch; report best epoch, not last.
- Beyond loss: eval correlation with labeler on holdout, and note that **only SPRT decides strength** — per CLAUDE.md §5, output the exact `sprt.ps1` command and stop; never simulate it.

### 8. Quantization
- Scheme documented per layer: scale factors, int widths, clipping bounds, rounding mode.
- Quantized vs float holdout loss delta measured and within tolerance.
- Saturation audit: max |accumulator| and |pre-activation| over a corpus vs type range. (For failures, hand off to [[nnue-debug]] / the `nnue-debug` skill.)

### 9. Exporting .nnue
- File format version/header, weight layout, byte order documented.
- Round-trip check: engine loads the file and its eval matches the trainer's forward pass on N test positions within quantization tolerance.
- File hash recorded.

### 10. Provenance manifest
Every released net ships a manifest (JSON/MD alongside the .nnue) containing:
- net file SHA-256; trainer commit; engine commit used for verification
- dataset: source, generation commands, filters, counts, file hashes
- labeler: version, hash, search limit
- full training config + seed; best-epoch metrics
- quantization scheme + float-vs-int delta
- date, author

### 11. Release report
- Summary: what changed vs previous net (data, architecture, config).
- Metrics table: holdout loss, correlation, quantization delta.
- SPRT command to run (per §5) and, once run, its result.
- Link to manifest. Bench NPS note only if measured on native Windows (§3).

## Reproducible experiment checklist (always emit)

```markdown
## Experiment: <name> — <date>
- [ ] Trainer commit: <sha>   Engine commit: <sha>
- [ ] Dataset source + generation command: <...>
- [ ] Dataset hashes + counts (train/val): <...>
- [ ] Split by game, dedup rate: <...>
- [ ] Labeler: <engine+version+hash>, limit: <nodes/depth>, score convention: <...>
- [ ] Feature set + encoder cross-checked vs engine extractor: <yes/no>
- [ ] Training config file path + seed: <...>
- [ ] Framework/versions/hardware: <...>
- [ ] Best epoch + holdout metrics: <...>
- [ ] Quantization scheme + float-delta: <...>
- [ ] .nnue hash + engine round-trip verified: <yes/no>
- [ ] Provenance manifest written: <path>
- [ ] SPRT command emitted (not run here): <sprt.ps1 ...>
```
