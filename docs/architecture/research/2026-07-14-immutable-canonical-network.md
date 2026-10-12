# Research: Immutable Intermediate Representations for the Canonical Network

**Date:** 2026-07-14
**Question:** `NNUE_TRAINER_ARCHITECTURE.md` §5 currently defines one `CanonicalNetwork`
dataclass with a `quantized: bool` flag `Quantizer` flips — the doc's own text admits
this is ambiguous ("if the trainer later needs more than one axis of type state,
revisit"). Ahead of splitting this into `CanonicalNetwork` (immutable, float) and
`QuantizedCanonicalNetwork` (immutable, int16) with `Quantizer` as a pure
transformation, how do mature compiler and ML systems structure immutable IRs and
transformation pipelines, and what does that suggest for Vex specifically?

## 1. Compiler IRs: immutability is structural, not a bolt-on flag

MLIR's own language reference (mlir.llvm.org/docs/LangRef) states the model plainly:
"MLIR is fundamentally based on a graph-like data structure of nodes, called
*Operations*, and edges, called *Values*. Each Value is the result of exactly one
Operation or Block Argument." A Value is never reassigned after its producing
Operation runs — this is SSA form, the same discipline LLVM IR uses. Transformation
passes don't edit a Value in place; they build new Operations producing new Values and
rewire consumers to them. Immutability here isn't a language keyword, it's a structural
invariant the whole pass-and-rewrite pipeline is built around.

## 2. PyTorch's own export pipeline: functionalized by explicit design

PyTorch's own source docs (`torch/_native/README.md`, via context7 `/pytorch/pytorch`)
state plainly: "`torch.export` and `torch.compile` functionalize mutating calls" — any
in-place op traced during capture is rewritten to its functional, value-returning form
in the resulting graph. The PT2E quantization pipeline
(`torchao.quantization.pt2e.quantize_pt2e`, via context7 `/pytorch/ao`) documents its
own two-step API the same way in every example: `prepared_model = prepare_pt2e(exported_model, quantizer)`,
then `converted_model = convert_pt2e(prepared_model)` (`pt2e_quant_ptq.md`,
`pt2e_quantization/index.md`) — reassignment to a new name, never a call for a mutating
side effect on the original. Each stage consumes one `GraphModule`/`ExportedProgram`
and produces a distinct one.

## 3. The recurring shape: stage boundary = new immutable value, not an in-place edit

Across both systems, "quantization" and "export" are treated as ordinary transformation
stages, not privileged mutators — they follow the identical produce-new-value discipline
every other pass does. Nothing in either system's public API mutates its input in place
and returns it; the return value is always the new state, and the input remains valid
and unchanged for any other consumer holding a reference to it.

## 4. Making a Python dataclass *actually* immutable, not just frozen

`@dataclass(frozen=True)` blocks attribute reassignment (`obj.qa = 5` raises) but does
**not** block in-place mutation of a mutable field's contents —
`obj.ft_weights[0] = 5` still succeeds on a `frozen=True` dataclass holding a
`numpy.ndarray`, because the array object itself never changes, only its contents.
NumPy's own documentation (`numpy.ndarray.flags`, numpy.org) provides the standard
fix: setting `array.flags.writeable = False` makes the array's own buffer read-only —
any attempted in-place write raises `ValueError: assignment destination is read-only`.
This is one line per array, stdlib-adjacent (numpy is already a direct dependency), and
requires no new library or custom wrapper class.

## Applicable ideas for Vex

1. **Split into two dataclasses, not one dataclass with a bool flag** *(the change
   itself)* — matches every system surveyed: no compiler or ML pipeline reviewed
   represents "before this pass" and "after this pass" as the same mutable object with
   a state flag. `CanonicalNetwork` and `QuantizedCanonicalNetwork` as two
   `@dataclass(frozen=True)` types, structurally identical apart from field dtypes
   (`float32` arrays and no `quantized` field vs. `int16` arrays and no such field
   needed either, since the *type itself* now encodes what the bool used to).
2. **`frozen=True` plus `flags.writeable = False` on every ndarray field, set once in
   `__post_init__`** *(cheap; closes the real gap frozen-alone leaves)* — matches
   numpy's own documented mechanism exactly; no custom immutable-array wrapper class,
   no third-party dependency.
3. **`Quantizer` is a plain function, `CanonicalNetwork -> QuantizedCanonicalNetwork`,
   never taking `self` or mutating its argument** *(matches `prepare_pt2e`/
   `convert_pt2e`'s pure-function shape)* — no `Quantizer` class needed; a class would
   be an unrequested abstraction over a single pure function with no state to hold.
4. **Do not adopt SSA/graph-node-level machinery** *(explicitly rejected)* — MLIR's
   Value/Operation graph exists to support arbitrary composable passes over an open-ended
   instruction set. Vex has exactly one two-stage pipeline
   (`CanonicalNetwork -> QuantizedCanonicalNetwork`) with a fixed, small field list;
   object-level dataclass immutability delivers the same "no accidental in-place edit"
   guarantee at a fraction of the machinery, and CLAUDE.md's own anti-speculative-
   abstraction stance rules out building graph-pass infrastructure for a pipeline this
   shape.
5. **A `CheckpointLoader` stage (`checkpoint -> CanonicalNetwork`) is the same pattern
   already validated by §5's existing `checkpoint_to_canonical()`** — no new idea
   needed here, just naming the existing function as its own named pipeline stage per
   the user's requested `PyTorch checkpoint -> Checkpoint Loader -> CanonicalNetwork`
   shape, consistent with `torch.export`'s own "capture once, transform the captured
   value from then on" discipline (idea #2/#3 above).
