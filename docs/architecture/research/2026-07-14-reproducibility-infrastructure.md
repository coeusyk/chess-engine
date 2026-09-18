# Research: Reproducibility Infrastructure for the NNUE Trainer

**Date:** 2026-07-14
**Question:** Ahead of designing a reproducibility-infrastructure module for `trainer/`
(before PR D-4, the PyTorch model + training loop), is the reproducibility framing
already stated in `NNUE_TRAINER_ARCHITECTURE.md` §10 and §15 Invariant 7 — "statistically
equivalent net, not bit-exact across GPU hardware" for training vs. "byte-exact"
quantization/export — well-calibrated against what PyTorch itself documents as
achievable and recommended?

## 1. Deterministic PyTorch has real, documented limits

`torch.use_deterministic_algorithms()` "configure[s] PyTorch to use deterministic
algorithms instead of nondeterministic ones where available, and to throw an error if
an operation is known to be nondeterministic" without a deterministic alternative —
some ops simply have none (docs.pytorch.org/docs/stable/notes/randomness.html).
`cudnn.benchmark=False` forces deterministic convolution-algorithm selection at a
performance cost, distinct from the narrower `cudnn.deterministic` flag. Named
non-determinism sources: CUDA convolution benchmarking picking different algorithms
across runs, SDPA/flash-attention backward passes using non-deterministic atomics, and
RNN/LSTM CUDA kernels. "Deterministic operations are often slower than nondeterministic
operations." This directly validates Vex's existing framing: bit-exact GPU training
reproducibility is not just hard, PyTorch itself declines to fully guarantee it.

## 2. RNG seeding: three globals, plus a DataLoader-specific gotcha

PyTorch recommends seeding `torch.manual_seed()` (covers CPU and CUDA), `random.seed()`
("for custom operators"), and `numpy.random.seed()` ("if... any of the libraries you are
using rely on NumPy") together (same URL). Separately, multi-worker `DataLoader`
assigns each worker `base_seed + worker_id` for *PyTorch's* RNG only — "seeds for other
libraries may be duplicated upon initializing workers, causing each worker to return
identical random numbers" unless `worker_init_fn` reseeds them via
`torch.initial_seed()` (docs.pytorch.org/docs/2.13/data.html). This is a real, silent
gotcha the architecture doc does not currently mention.

## 3. Checkpoint metadata: two tiers, not one

PyTorch's own saving/loading tutorial recommends `model.state_dict()`,
`optimizer.state_dict()`, epoch, and loss for a resumable checkpoint —
and **does not mention RNG state at all**
(docs.pytorch.org/tutorials/beginner/saving_loading_models.html). Exact bit-for-bit
resume additionally needs `torch.get_rng_state()`/`torch.cuda.get_rng_state_all()`, a
separate, heavier tier PyTorch exposes but doesn't fold into its own tutorial's
"recommended checkpoint" — evidence the two tiers (identify-this-run vs.
exact-resume) are a real, documented distinction, not an invented one.

## 4. Statistical reproducibility is the field's actual bar

NeurIPS's reproducibility checklist (Pineau et al., 2021 program;
neurips.cc/public/guides/PaperChecklist) asks for code/data release, training details,
compute resources, and **"error bars... or other appropriate information about
statistical significance"** — it does not mandate seed-exact reproduction. PyTorch
Lightning's `Trainer(deterministic=True|False|"warn")` "sets the
`torch.backends.cudnn.deterministic` flag... might make your system slower" and
`"warn"` degrades gracefully on unsupported ops rather than crashing
(lightning.ai/docs/pytorch/stable/common/trainer.html) — confirming determinism is
treated industry-wide as an opt-in cost, not a default.

## Applicable ideas for Vex

1. **Keep §10/Invariant 7's framing as-is** — "statistically equivalent, not bit-exact
   on GPU" matches exactly what PyTorch documents as the real ceiling. No change needed.
2. **Single top-level seed, not per-component seeds** *(cheap; add now)* — thread one
   seed through `random`, `numpy`, `torch.manual_seed`, and the `DataLoader`
   `generator`. Per-component seeds (data-shuffle vs. init vs. augmentation) are a
   variance-isolation tool for sweep-scale research operations; Vex is single-maintainer
   scale with no demonstrated need to isolate those sources — YAGNI until Stage 3
   self-play variance debugging proves otherwise.
3. **Explicit `worker_init_fn`/`generator` on any multi-worker `DataLoader`**
   *(cheap; add now, not yet documented anywhere in the architecture)* — the
   base_seed+worker_id default only covers PyTorch's own RNG; NumPy/`random` calls
   inside a `Dataset.__getitem__` silently duplicate across workers otherwise.
4. **Don't add RNG-state checkpoint fields** *(skip; would be dead weight)* — exact
   mid-training resume is a heavier, separate tier PyTorch itself doesn't bundle into
   its own recommended checkpoint, and it wouldn't buy Vex anything past §9's existing
   run-identification manifest given Invariant 7 already disclaims bit-exactness.
5. **An explicit opt-in "full determinism" mode**
   (`torch.use_deterministic_algorithms(True)`, `cudnn.deterministic=True`,
   `cudnn.benchmark=False`) *(optional, real cost)* — useful for small/CI-scale runs
   (§12's tiny fixed-seed export-parity check) where the perf hit is negligible, but
   must not be the default for real training runs; some future op may still lack a
   deterministic kernel and raise at runtime.
