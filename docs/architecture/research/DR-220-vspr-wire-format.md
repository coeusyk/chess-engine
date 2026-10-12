# VSPR wire format (E-9 wire-level design record, #220)

**Status:** design + golden fixture specification only, no implementation. Split out of #209
per the roadmap's own module-placement decision. This document owns framing, magic/versioning,
byte order, primitive encodings, tagged unions, field ordering, length/count limits,
corruption/truncation handling, compatibility rules, and deterministic golden fixtures for the
Vex Self-Play Record (VSPR) format. It does not own `GameLoop` behavior, search policy,
move-selection weighting, adjudication thresholds, QuietWalk, dataset splitting, training-target
semantics, or generator-selection policy — those stay #209/#210/#212's concerns, and this
document does not relitigate or extend them.

Every semantic field VSPR encodes is taken from `DR-E9-stage3-game-search-label-contract.md`
(#209) section 9's type table, treated as authoritative. No new training semantics are
introduced here — where #209 itself defers a value (adjudication thresholds, diversity
weighting), this document defines a bounded, opaque wire slot for it rather than inventing a
concrete schema #209 didn't specify.

**Revision note (this pass):** this is a corrective revision of the version committed at
`d4af3a4`, following adversarial review that found six defects: insufficient producer-identity
fields, an internally-contradictory extension mechanism, conflated normal-read and
recovery-writer truncation semantics, a chess-illegal canonical fixture, an overclaimed
timestamp-ordering guarantee, and #220/#211 acceptance-criteria drift. All six are corrected
below; see each section's revision note for what changed and why. Reviewed against the
repository at commit `d4af3a4` on `phase/15-nnue`.

---

## 1. Verdict

VSPR is specified as a single top-level framing: a fixed-format header (one per file, carrying
run-level `GameConfig` and producer identity) followed by a sequence of length-prefixed,
CRC-32-checked `GameFrame` records (one per game), each internally structured as required
fields in a fixed order. This is the simplest model of the three candidates considered (§4)
that still satisfies every requirement in scope: streaming decode, bounded memory, corruption
detection at frame boundaries, multiple games per file, and deterministic serialization.

The format reuses `.nnue`'s two load-bearing conventions with a stated reason each: big-endian
byte order (§3) and a single, exact-match, hard-reject version field with no lenient parsing and
no speculative extension mechanism (§4, §7 — this revision removes the prior draft's
header-extension TLV list entirely, per §4's revision note). It reuses the engine's own existing
16-bit packed move encoding verbatim rather than inventing a new move representation, and treats
every #209-deferred numeric policy (adjudication thresholds, diversity weighting) as an opaque,
length-bounded, schema-tagged blob that VSPR bounds-checks but never interprets. Producer
identity is now three distinct, explicit fields (§6): a logical network identity, an exact
network-artifact hash, and an exact engine-build identity — none of them a filesystem path.

Six golden fixtures are fully specified below with exact hex and SHA-256 (ordinary CP sample,
mate-score sample, natural draw, infrastructure-terminated unresolved game, candidate-section
present, maximum-length FEN), computed by a throwaway, non-committed construction script that
mechanically applies this document's own byte layout — not hand-typed, and not a decoder in
either direction. Every fixture representing a complete game now plays out a real, legal move
sequence consistent with its recorded outcome and termination reason (§10's revision note). The
remaining four required fixtures are specified as exact byte-level deltas, which is the correct
representation for a corruption/malformed case: it *is*, by definition, a known-good fixture
with one documented byte changed.

## 2. Design goals / non-goals

**Goals:** cross-language interoperability through shared golden vectors (not shared code);
bounded decoder memory regardless of input; loud rejection of anything malformed, truncated, or
version-mismatched — including a file whose only content is a truncated trailing frame (§9,
§11); streaming decode of one game at a time; every #209 semantic field representable without
ambiguity; optional `SearchCandidate` persistence that can be cleanly absent; exact producer
provenance (logical network identity, exact network artifact, exact engine build) without ever
relying on a filesystem path as identity.

**Non-goals:** a generic, reusable serialization framework or speculative forward-compatibility
mechanism (§4 — no header extensions exist because no current V1 requirement needs one); pinning
#209's own deferred numeric policy values; cryptographic tamper-resistance (§15); solving
distributed/multi-writer generation (§9); wall-clock-synchronized cross-run ordering (§6's
timestamp note); any Java or Python implementation code (§16 forbids it this turn).

## 3. Wire-level primitives

**Byte order: big-endian**, explicitly deviating from a little-endian default on strong repo
evidence: `NnueNetwork.java`'s own docstring states its binary format is "big-endian, this
engine's own versioned layout," using `DataInputStream`/`DataOutputStream` (Java's
`DataInput`/`DataOutput` are always big-endian, by design). `trainer/trainer/export/
exporter.py` matches this deliberately — its own docstring says "Java's `DataInput`/`DataOutput`
interfaces are always big-endian, by design (`struct.pack(">i", ...)` rather than the
little-endian layout a from-scratch design might otherwise pick)." VSPR is the second
Java/Python cross-language exchange format in this repository; matching the first one's
established, load-bearing convention avoids a second, inconsistent idiom for no benefit.

**Primitive widths**, matching `.nnue`'s own precedent of using full-width header fields where
header size is immaterial, and narrower fields where a value repeats per-record at scale:

| Type | Width | Notes |
|---|---|---|
| `u8` | 1 byte | unsigned |
| `u16` | 2 bytes | unsigned, big-endian |
| `u32` | 4 bytes | unsigned, big-endian |
| `i32` | 4 bytes | signed, big-endian, two's complement |
| `i64` | 8 bytes | signed, big-endian, two's complement |
| `u8[N]` | N bytes | fixed-width raw bytes, no length prefix — used only where the width is a wire-format constant, never a variable-length value (magic, `runId`, `generatorNetworkSha256`) |
| `bstr8` | 1+N bytes | `u8` length prefix (0-255), then N raw bytes |
| `bstr16` | 2+N bytes | `u16` length prefix (0-65535), then N raw bytes |
| `bool` | 1 byte | `u8`, must be exactly `0x00` or `0x01`; any other value is rejected as an invalid tag (§13) |
| `optional<T>` | 1+sizeof(T) or 1 byte | a `bool` presence flag, followed by `T` only if the flag is `0x01` |

No sentinel values are used anywhere in this format to mean "absent" (e.g. no reuse of `-1`,
`Long.MIN_VALUE`, or `0` as a dual-purpose "no value" marker) — every optional field uses the
explicit `optional<T>` presence-flag form above. This generalizes the same idea
`mmap_shard.py`'s own `has_eval_cp`/`has_eval_mate`/`has_wdl` boolean-flag convention already
applies one layer down in this pipeline (its comment: "a wdl-only label had no representable
field at all" was exactly the bug that convention exists to prevent) — VSPR applies it
consistently at the wire level, for every optional field, not just scores.

## 4. File/header structure

**Revision note:** the prior draft paired a `u16 headerLength` (max 65,535 bytes) with a
`headerExtensionCount` allowing up to 65,536 extension sections of up to 1 MiB each — a value
that could never actually fit inside the field meant to describe it, and no current V1
requirement needed the extension mechanism at all. Both are removed. V1's header is now fully
fixed-shape: a decoder that supports `formatVersion = 1` knows its exact byte layout and length
without reading any length-prefix or extension-count field first. If a future revision needs to
add fields, that is a new `formatVersion` value with its own exact-match hard-reject rule (same
posture `.nnue` already uses for its own single `formatVersion` field) — not a backward-lenient
extension inside the existing version.

One VSPR file = one header + zero or more `GameFrame` records, concatenated with no inter-record
padding.

```
VSPR file:
  u8[4]    magic = "VSPR"
  i32      formatVersion          -- exactly 1 for this specification; any other value is a
                                      hard reject, no partial/lenient parsing (§7, §11)
  u8[16]   runId                  -- raw 128-bit UUID, identifies this file/run (§9)
  i64      createdAtEpochSeconds  -- informational/provenance only; NOT an authoritative
                                      cross-run ordering signal (§6)
  -- GameConfig (required singleton, §9's per-run fields) --
  bstr16   generatorNetworkUuid   -- max 128 bytes; logical/network identity (§6)
  u8[32]   generatorNetworkSha256 -- exact network-artifact identity (§6)
  bstr8    engineBuildId          -- max 64 bytes; exact engine-producer-build identity (§6)
  bstr16   generatorNetworkPath   -- max 512 bytes; informational only, NEVER identity (§6)
  i32      maxPlies               -- the run's configured move-cap (#209 §4.3); format-level
                                      bound on this value is independent and larger (§7)
  u8       searchBudgetKind       -- 0=depth, 1=nodes, 2=timeMs
  i64      searchBudgetValue
  bool     resetSearchStateBetweenGames
  bool     candidatesPersisted    -- whether every GameFrame below carries its optional
                                      candidate-set section (§6, §8)
  optional<opaqueConfig>  adjudicationConfig   -- opaque-blob encoding, below
  optional<opaqueConfig>  diversityConfig      -- opaque-blob encoding, below; if present,
                                                   this also implies runSeed was used (#209 §9);
                                                   runSeed itself lives inside the opaque blob,
                                                   since its derivation/consumption is a
                                                   diversity-mechanism concern VSPR does not
                                                   interpret
  -- end of header; fully fixed length for formatVersion=1, nothing to skip --

GameFrame (repeated, one per game, until EOF):
  u32      frameLength      -- bytes of the frame body below, this field and the trailing crc32
                                excluded
  -- frame body (frameLength bytes) --
  i64      gameId
  optional<i64>  gameSeed
  u8       gameOutcome        -- enum, §6
  u8       terminationReason  -- enum, §6
  u8       outcomePerspective -- enum, §6; applies to gameOutcome, recorded once per game (§8)
  u32      playedMoveCount    -- bounded, §7
  repeat playedMoveCount times:  PlayedMoveDecision   -- §5
  u32      sampleCount        -- bounded by playedMoveCount, §7
  repeat sampleCount times:      TrainingSample        -- §5
  if header.candidatesPersisted:
    u32    candidateSetCount  -- bounded by playedMoveCount, §7
    repeat candidateSetCount times:  CandidateSet       -- §5
  -- end frame body --
  u32      crc32             -- CRC-32 (IEEE 802.3 polynomial, both languages' stdlib default:
                                 java.util.zip.CRC32, Python zlib.crc32) over exactly the
                                 frameLength bytes of the frame body above
```

`opaqueConfig` (used by `adjudicationConfig`/`diversityConfig`):
```
  u8    schemaId       -- reserved for whichever future document defines the concrete field
                           layout; VSPR bounds-checks length only, never interprets contents
  bstr16 payload        -- max 4096 bytes (§7)
```

## 5. Game-frame structure

The semantic content of each repeated element in a `GameFrame`:

**`PlayedMoveDecision`** (#209 §9):
```
  u16   move                  -- packed Move encoding (§6)
  u8    selectionMechanismKind -- 0 = bestMove, 1 = namedMechanism
  if selectionMechanismKind == 1:
    bstr8 mechanismName        -- max 32 bytes ASCII; identifies which diversity mechanism
                                   without VSPR inventing or enumerating specific mechanisms
                                   (#209 §7 leaves the mechanism itself undecided)
  optional<i64>  selectionSeed -- required present iff a diversity mechanism was used, per
                                   #209 §9's invariant; VSPR encodes this as an explicit
                                   presence flag rather than inferring it from
                                   selectionMechanismKind, so decode-time validation of the
                                   pairing (§13) is a real check, not an assumption
```

**`TrainingSample`** (#209 §9): `gameId`, `gameOutcome`, `terminationReason`,
`outcomePerspective`, and `generatorNetworkIdentity` are **not** repeated per sample — they are
game-scoped (constant across every sample in one `GameFrame`) and are already carried once, in
the frame header and file header respectively. This is the §8 normalization requirement applied
directly: repeating them per sample would multiply four small fields by however many samples a
game has, for a value that never varies within that game, with no streaming benefit (a consumer
already has the frame's header fields in memory by the time it decodes any sample within that
same frame).
```
  u32   ply           -- index into this frame's playedMoves list; must be < playedMoveCount (§13)
  bstr8 fen            -- max 100 bytes ASCII (§6)
  bool  onTrajectory   -- always true in the initial design (#209 §6), field present for a
                          future off-trajectory design
  u8    evalScoreKind  -- 0 = cp, 1 = mate
  i32   evalScore      -- centipawns, or mate distance in plies (signed; positive = the sampled
                          position's side to move delivers mate in N, matching this engine's own
                          side-to-move-relative convention, PositionLabel's documented sign
                          convention)
  u8    searchBudgetKind
  i64   searchBudgetValue
```

**`CandidateSet`** (one per ply where `SearchCandidate`-level data was persisted; present only
if `header.candidatesPersisted`):
```
  u32   ply
  i32   depth
  u16   candidateCount     -- bounded 0..218 (§7)
  repeat candidateCount times:  SearchCandidate
```

**`SearchCandidate`** (#209 §9):
```
  u16   move
  u16   rank               -- from pvIndex/multipv, search's own ranking, not re-sorted (#209 §5)
  u8    scoreKind
  i32   score
  u16   pvLength            -- bounded 0..128 (§7)
  repeat pvLength times: u16 move
  bool  complete
```

## 6. Field encoding detail

**Move encoding**: reuses `Move.java`'s existing packed-int scheme verbatim —
`from | (to << 6) | (flag << 12)`, truncated to the low 16 bits (`from`/`to` are 6-bit square
indices 0-63, `flag` is a 4-bit tag). VSPR encodes this as a plain `u16`. The flag values are
exactly `Move.java`'s `FLAG_NORMAL`(0) through `FLAG_PROMO_N`(8); flag values 9-15 are reserved
and a decoder must reject any packed move whose flag nibble falls outside 0-8 (§13) — this
engine already has, tests, and round-trips this exact encoding in `Board.makeMove`/`unmakeMove`
and `MovesGenerator`, so VSPR adds zero new move representation to get right independently in
two languages.

**FEN encoding**: `bstr8`, ASCII only (FEN's character set — piece letters, digits, `/`, space,
castling letters, `-`, algebraic square names, integers — is a strict ASCII subset; there is no
legitimate use for non-ASCII bytes here), max 100 bytes. `mmap_shard.py`'s own `_FEN_BYTES = 90`
fixed-field precedent (comment: "longest realistic FEN ... is well under 90 bytes; 90 gives
headroom") is the basis for VSPR's bound; VSPR uses a length-prefixed field instead of a fixed
90-byte field (unlike the shard format, VSPR is not a fixed-stride mmap layout) with 100 bytes
as the hard reject ceiling, matching the same "well under" margin the shard format already
established.

**`gameId`**: `i64`, always present (not `optional`) — #209 §9 marks `GameIdentity.gameId` as
unconditionally required, unlike #207's downstream `Optional[int]` need, which exists only once
this value crosses into the shard format's own optionality question (§12). VSPR itself never has
an "absent gameId" state to represent.

**Centipawn / mate score**: tagged union, `u8 scoreKind` (`0 = cp`, `1 = mate`) followed by
`i32 score`, used identically for `TrainingSample.evalScore` and `SearchCandidate.score`. There
is exactly one score field, its kind is always explicit, and a decoder never has to infer which
convention applies from the numeric magnitude (§13 explains why a magnitude-based heuristic
would be actively wrong here).

**`GameOutcome`** / **`TerminationReason`** / **`outcomePerspective`**: three separate `u8`
enums, never collapsed, per #209 §8's explicit design requirement:

| `GameOutcome` | value |
|---|---|
| `whiteWin` | 0 |
| `blackWin` | 1 |
| `draw` | 2 |
| `unresolved` | 3 |

| `TerminationReason` | value | category |
|---|---|---|
| `checkmate` | 0 | A |
| `stalemate` | 1 | A |
| `threefoldRepetition` | 2 | A |
| `fiftyMoveRule` | 3 | A |
| `insufficientMaterial` | 4 | A |
| `adjudicatedScore` | 5 | B |
| `moveCap` | 6 | C |
| `searchAbortOrFailure` | 7 | C |

| `outcomePerspective` | value |
|---|---|
| `white` | 0 |
| `sideToMoveAtSample` | 1 |

Valid `(outcome, terminationReason)` pairings, enforced at decode time (§13):

| `terminationReason` | permitted `gameOutcome` values |
|---|---|
| `checkmate` | `whiteWin`, `blackWin` |
| `stalemate` | `draw` |
| `threefoldRepetition` | `draw` |
| `fiftyMoveRule` | `draw` |
| `insufficientMaterial` | `draw` |
| `adjudicatedScore` | `whiteWin`, `blackWin`, `draw` — #209 doesn't rule out a drawn adjudication policy, so this document doesn't invent that restriction either |
| `moveCap` | `unresolved` only |
| `searchAbortOrFailure` | `unresolved` only |

This table constrains the `(outcome, terminationReason)` pair only — it says nothing about how
many plies a game must have to reach a given `terminationReason`, which is a chess-legality
question this document does not attempt to encode structurally (a decoder cannot verify chess
legality without a full move generator). It is, however, a requirement on this document's own
**golden fixtures** (§10): every fixture representing a `checkmate`/`stalemate`/
`threefoldRepetition` termination must play out a `playedMoves` sequence that is actually capable
of reaching that termination under the real rules of chess, not merely one that satisfies the
enum-pairing table in isolation.

**Search-budget metadata**: `u8 searchBudgetKind` (`0 = depth`, `1 = nodes`, `2 = timeMs`) + `i64
searchBudgetValue` — one tagged union, reused identically for `GameConfig.searchBudget` (header,
once per run) and `TrainingSample.searchBudget` (per sample, since #209 §6 requires each sample
to carry the budget its own evaluating search actually ran under). `i64` for the value (not
`i32`) matches `mmap_shard.py`'s own justification for `search_nodes` being an 8-byte int: "a
node budget can exceed int32 range at high search depth/time" — the same overflow risk applies
here.

**Producer identity — three distinct fields, never conflated:**

| Field | Answers | Width | Stability |
|---|---|---|---|
| `generatorNetworkUuid` | "Which network, logically?" | `bstr16`, max 128 bytes | Stable across re-exports of conceptually the same trained network; reuses `NnueNetwork.networkUuid`'s existing `writeUTF`-compatible string, already present in every `.nnue` file today |
| `generatorNetworkSha256` | "Which exact bytes?" | `u8[32]`, fixed | Changes if the `.nnue` file's bytes change at all (re-quantization, re-export, even a bit-identical rebuild that reorders nothing still hashes the same — the point is it changes whenever the bytes actually do) |
| `engineBuildId` | "Which exact engine build produced this game?" | `bstr8`, max 64 bytes | For a normal repository build, the git commit SHA (or another immutable build identifier) the engine binary was built from — the exact code that ran `GameLoop`/`Searcher`, independent of which network it loaded |

The prior draft carried only `generatorNetworkUuid` and `generatorNetworkPath` — insufficient
for exact provenance, since neither pins the exact artifact bytes (a UUID is a logical identity,
stable across a re-export that changes nothing meaningful but still produces different bytes)
nor the exact engine code that ran (nothing previously identified the engine build at all).
`generatorNetworkSha256` and `engineBuildId` close both gaps. `generatorNetworkPath` (`bstr16`,
max 512 bytes) is retained for human-readable provenance only and remains explicitly **not** an
identity field: a decoder or consumer must never compare two games' provenance using this field;
`generatorNetworkUuid` and `generatorNetworkSha256` are the only valid identity comparisons
(logical and exact-artifact, respectively), and `engineBuildId` is the only valid exact-build
comparison. No field in this format uses a local filesystem path as identity.

**RNG seeds** (`GameFrame.gameSeed`, `PlayedMoveDecision.selectionSeed`): `i64`, matching
`i64 createdAtEpochSeconds`'s width class for any 64-bit value in this format. `GameConfig.
runSeed` lives inside the opaque `diversityConfig` blob (§4) rather than as a top-level header
field, since #209 §9 only requires `runSeed` when `diversityConfig` is set, and nesting it there
avoids a second `optional<i64>` at the header level that duplicates the same presence condition
`diversityConfig`'s own presence flag already expresses.

**Timestamps — informational/provenance only, not an ordering guarantee.** `createdAtEpochSeconds`
is the run's own wall-clock start time, recorded once, as reported by whatever machine produced
this file. It is useful for a human skimming provenance ("roughly when was this run"), but this
document makes **no claim** that it provides authoritative ordering across multiple runs or
multiple files: distributed generation workers are not assumed to have synchronized clocks, and
nothing in this format corrects for skew, drift, or a worker with a misconfigured clock. Identity
and ordering of runs come from `runId` (§9) plus whatever ingestion/run metadata #210's own
dataset-generation manifest records (explicit run sequencing, if any is ever needed, is that
manifest's job) — never from comparing `createdAtEpochSeconds` values across files and assuming
the comparison means anything authoritative.

## 7. Bounds

Every bound below is either (a) derived directly from a cited engine constant, or (b) an
explicit, generously-sized decoder-allocation safety ceiling that is **not** presented as an
engine semantic limit. `GameConfig.maxPlies`'s current recommended default (500) and its derived
safe ceiling (618, from `Board.UNMAKE_POOL_SIZE`, `Searcher.MAX_PLY`, `MAX_CHECK_EXTENSIONS`,
`MAX_Q_DEPTH` per #209 §4.3) are **not** encoded as VSPR's own format maximum — those numbers are
runtime-enforced by `GameConfig.maxPlies` itself (an `i32` value the wire format carries, checked
by the producer, not re-derived or re-validated by a VSPR decoder, which has no way to know a
future engine build's `MAX_PLY` value anyway).

| Quantity | Bound | Basis |
|---|---|---|
| `playedMoveCount` per game | 0..65,536 | decoder-allocation safety ceiling only, ~131x the 500-ply recommended default, independent of any engine constant |
| `sampleCount` per game | 0..`playedMoveCount` | structural: every sample is on-trajectory in the initial design (#209 §6), so it must index a real played move |
| `candidateSetCount` per game | 0..`playedMoveCount` | structural: at most one candidate set per ply |
| `candidateCount` per `CandidateSet` | 0..218 | the proven maximum number of legal chess moves in any reachable position — a real combinatorial bound, not a guess |
| `pvLength` | 0..128 | `Searcher.MAX_PLY = 128` (#209 §2, `Searcher.java:34`) — a PV cannot exceed the iterative-deepening loop's own depth ceiling |
| `mechanismName` length | 0..32 bytes | generous for a short identifier string, not a policy decision |
| `fen` length | 0..100 bytes | `mmap_shard.py`'s own 90-byte precedent plus the same margin it already uses (§6) |
| `generatorNetworkUuid` length | 0..128 bytes | a UUID is ~36 bytes; generous headroom for a longer future identity scheme without a format change |
| `generatorNetworkSha256` length | exactly 32 bytes | fixed by SHA-256's own output size — not length-prefixed, nothing to bound |
| `engineBuildId` length | 0..64 bytes | a git commit SHA is 40 (SHA-1) or 64 (SHA-256) hex characters; 64 covers both with no truncation |
| `generatorNetworkPath` length | 0..512 bytes | a filesystem path, generous but bounded |
| `opaqueConfig.payload` length | 0..4096 bytes | generous for a future adjudication/diversity schema this document does not define, bounded so a corrupt length field cannot force a large allocation |
| `frameLength` (one `GameFrame`) | 0..64 MiB | decoder-allocation safety ceiling; a realistic 500-ply game with samples is a few KB, this is >1000x headroom, not a semantic limit |

Every one of these bounds is checked **before** the corresponding allocation is made (matching
`NnueNetwork.load`'s own established pattern: `hiddenWidth` is range-checked before any array of
that size is allocated).

## 8. Outcome/termination encoding

Covered fully in §6 (enum tables and the valid-pairing table) and §5 (game-scoped fields
recorded once per frame, never duplicated per sample). `GameOutcome` and `TerminationReason` are
stored as two independent `u8` fields with no shared bit-packing between them, specifically so
#209 §8's requirement ("these are two different pieces of information ... and need to be
distinguishable, not collapsed into one field") is a structural property of the format, not a
documentation-only convention a producer could still violate by packing them into one byte.

## 9. Identity / provenance and resume semantics

**File/run identity**: `runId`, a 128-bit UUID in the header, identifies one VSPR file as one
generation run. **One VSPR file = one run**, by design — the simplest choice that satisfies
#209's actual requirement ("`gameId` ... unique within an ingestion run") without building any
cross-file or distributed-writer coordination this document does not need to solve.

**`gameId` scope**: unique within one file's `runId`, assigned sequentially by the writer
(`GameLoop`) starting at an implementation-chosen value — VSPR does not mandate starting at 0 or
1, only that it never repeats within one file. Cross-run uniqueness is explicitly **not**
guaranteed by VSPR and is #210's stated responsibility (#209 §10).

### Normal reader / ingester semantics (strict — the default, and the only mode a plain "open and
decode this file" operation ever uses)

- A truncated header (fewer bytes available than the fixed V1 header requires, or `magic`/
  `formatVersion` mismatched) is a **hard reject of the whole file**. There is no valid file
  identity to recover a partial read from.
- A truncated `GameFrame` (declared `frameLength` extends past EOF, or the trailing `crc32` is
  missing or doesn't match) is an **error**, not a silently-shorter result. A streaming decoder
  yields each complete, `crc32`-verified frame as it's parsed; when it reaches an incomplete
  trailing frame, it **raises an explicit truncation error** rather than treating end-of-input
  as end-of-file. This applies uniformly regardless of how many complete frames came before it —
  including **zero**: a file consisting of nothing but one incomplete frame must never decode as
  a valid, zero-game file. The absence of an error is what "no more games" means; running out of
  bytes mid-frame is never silently reinterpreted as that.
- A `crc32` mismatch on an otherwise length-complete frame is the same category of error —
  reject that frame, do not attempt to use its contents.
- **No silent skip-and-continue past corruption in the middle of a file.** A normal reader must
  not attempt to resynchronize by scanning for the next plausible frame boundary after a corrupt
  or unrecognized frame — corruption anywhere in a file must surface as an error, never as "the
  file just had fewer games than expected, no error."

### Explicit resume/recovery writer semantics (opt-in, separate operation — never invoked
implicitly by a normal read)

A writer that wants to append to an existing VSPR file (an operational choice, not the
recommended default — see below) performs a distinct, explicitly-invoked recovery procedure,
never something a normal reader does on its own:

1. Read the header and confirm the `runId` it intends to continue.
2. Scan forward frame-by-frame from just after the header, verifying each frame's declared
   `frameLength` and `crc32`.
3. **If and only if** the very last frame at end-of-file is incomplete due to tail truncation
   (exactly what a crash mid-write produces) — truncate the file back to the start of that
   incomplete frame before appending anything new. Never append after unverified trailing bytes.
4. Continue `gameId` numbering from one past the last successfully verified frame's `gameId`.
5. **This procedure never repairs or skips mid-file corruption.** It handles exactly one
   situation: a well-formed prefix of complete, verified frames followed by one incomplete frame
   at the physical end of the file. If corruption is found anywhere before that trailing
   position — a `crc32` mismatch, a bound violation, an invalid enum — the recovery procedure
   stops and reports failure; it does not attempt to salvage a "good" file by cutting out the
   corrupt middle.

**Recommended default: do not append; start a new file with a new `runId` per resumption.** This
trivially avoids `gameId` collision (a new `runId` means a resumed run's games are unambiguously
a different run, even if its `gameId` sequence restarts from the same starting value) and needs
no special writer logic beyond "open a new file." The append path above exists because every
`GameFrame` is self-delimiting and therefore *supports* it, for an operational need that
justifies the extra procedure — not because it is the recommended way to resume.

## 10. Cross-language golden fixture specification

**Revision note:** the prior draft's "ordinary CP sample" fixture recorded a two-ply game
(`e2e4 e7e5`) with `terminationReason = fiftyMoveRule` — a pairing the enum-pairing table in §6
permits in isolation, but one no real two-ply game can ever actually reach (the fifty-move rule
requires 100 consecutive halfmoves without a pawn move or capture; both of that game's moves
*were* pawn moves). §6's revision note above makes this an explicit requirement on fixtures, not
just the enum table: every fixture representing a complete game must play out a real, legal move
sequence capable of reaching its recorded termination. All fixtures below that represent a full
game now do so — verified move-by-move, not merely "a valid enum pairing."

Six fixtures are given in full below (computed once, by a throwaway construction script — see
the note at the end of this section — never hand-typed and never committed to the repository).
The remaining four required fixtures are specified as exact byte-level deltas, which is the
correct representation for a corruption/malformed case: it *is*, by definition, a known-good
fixture with one documented byte changed.

All fixtures share one header (`GameConfig`): `formatVersion=1`, `runId =
0000...00AAAA` (16 raw bytes, all-zero except the last two, chosen to be visually unambiguous in
a hex dump), `createdAtEpochSeconds = 1700000000`, `generatorNetworkUuid = "fixture-net-0001"`
(16 ASCII bytes), `generatorNetworkSha256` = 30 zero bytes followed by `0xBE 0xEF` (32 bytes
total, same "visually unambiguous fixture marker" convention as `runId`), `engineBuildId =
"fixture-build-0001"` (18 ASCII bytes), `generatorNetworkPath = ""` (empty — deliberately, to
also exercise the zero-length
`bstr16` case), `maxPlies = 500`, `searchBudgetKind = 0` (depth), `searchBudgetValue = 6`,
`resetSearchStateBetweenGames = true`, `candidatesPersisted = false` (except Fixture 6, which
flips it), `adjudicationConfig` absent, `diversityConfig` absent. This header serializes to
exactly **120 bytes**, verified below field-by-field against the script's actual output (not
hand-summed):

| Field | Offset | Size |
|---|---|---|
| `magic` | 0 | 4 |
| `formatVersion` | 4 | 4 |
| `runId` | 8 | 16 |
| `createdAtEpochSeconds` | 24 | 8 |
| `generatorNetworkUuid` | 32 | 18 |
| `generatorNetworkSha256` | 50 | 32 |
| `engineBuildId` | 82 | 19 |
| `generatorNetworkPath` | 101 | 2 |
| `maxPlies` | 103 | 4 |
| `searchBudgetKind` | 107 | 1 |
| `searchBudgetValue` | 108 | 8 |
| `resetSearchStateBetweenGames` | 116 | 1 |
| `candidatesPersisted` | 117 | 1 |
| `adjudicationConfigPresent` | 118 | 1 |
| `diversityConfigPresent` | 119 | 1 |
| **total** | | **120** |

The frame section starts at offset 120 in every fixture. Within any frame body, `gameId` starts
at offset 124 (120 + 4-byte `frameLength`), `hasGameSeed` at 132, **`gameOutcome` at 133**,
`terminationReason` at 134, `outcomePerspective` at 135, `playedMoveCount` at 136 — identical
across every fixture below, since all of them share the same header length and the same
`gameId`/`gameSeed`-absent shape up to that point.

### Fixture 1 — ordinary CP sample game

Semantic object: **Fool's Mate** — the shortest possible legal checkmate in chess: `1.f3 e5 2.g4
Qh4#` (4 plies: `f2f3`, `e7e5`, `g2g4`, `d8h4`, all `FLAG_NORMAL`). `gameId=1`, outcome
`blackWin` / `checkmate` (Black delivers mate) — a real, verifiable pairing, not merely an
enum-table-legal one. One on-trajectory sample at `ply=0` (the position after `1.f3`, Black to
move), `evalScoreKind=cp`, `evalScore=35`, `searchBudget = depth 6`. No diversity, no candidate
sets.

Full bytes (242 bytes total), hex:
```
56535052000000010000000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d30303031000000000000000000000000000000000000000000000000000000000000
beef12666978747572652d6275696c642d303030310000000001f400000000000000000601000000
0000007200000000000000010001000000000004054d000009340000078e000007fb000000000001
000000003a726e62716b626e722f70707070707070702f382f382f382f3550322f50505050503150
502f524e42514b424e522062204b516b71202d203020310100000000230000000000000000065196
8ab5
```

SHA-256: `aa1f3c2c8db50d7ba38da13563e2155c24062a3a46874c9f2a407cf77661f055`

### Fixture 2 — mate-score sample

Same header, same Fool's Mate move sequence, `gameId=2`, same outcome (`blackWin`/`checkmate`).
The sample is now taken at `ply=2` — the position after `1.f3 e5 2.g4`, Black to move, where
`...Qh4#` is actually available: `evalScoreKind=mate`, `evalScore=1` (mate in 1, side-to-move's
own perspective). This is a genuinely correct mate-in-1 claim for that exact position, not just a
structurally valid tag.

Full bytes (245 bytes total), hex:
```
56535052000000010000000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d30303031000000000000000000000000000000000000000000000000000000000000
beef12666978747572652d6275696c642d303030310000000001f400000000000000000601000000
0000007500000000000000020001000000000004054d000009340000078e000007fb000000000001
000000023d726e62716b626e722f70707070317070702f382f3470332f3650312f3550322f505050
505032502f524e42514b424e522062204b516b71202d203020320101000000010000000000000000
06521e8e94
```

SHA-256: `a7298f75fb56deb3776a8784228d58009e87067bb9277505c55523acf9b330b8`

### Fixture 3 — drawn game by natural rule

Semantic object: threefold repetition via a repeated knight shuffle, `1.Nf3 Nf6 2.Ng1 Ng8 3.Nf3
Nf6 4.Ng1 Ng8` (8 plies: `g1f3, g8f6, f3g1, f6g8, g1f3, g8f6, f3g1, f6g8`, all `FLAG_NORMAL`) —
legal, and the starting position recurs a third time exactly after the 8th ply, since nothing
but the two knights ever moves. `gameId=3`, outcome `draw` / `threefoldRepetition` — a real
threefold, not merely an enum-table-legal pairing. One sample at `ply=0` (the position after
`1.Nf3`), `evalScoreKind=cp`, `evalScore=10`.

Full bytes (258 bytes total), hex:
```
56535052000000010000000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d30303031000000000000000000000000000000000000000000000000000000000000
beef12666978747572652d6275696c642d303030310000000001f400000000000000000601000000
0000008200000000000000030002020000000008054600000b7e0000019500000fad000005460000
0b7e0000019500000fad000000000001000000003a726e62716b626e722f70707070707070702f38
2f382f382f354e322f50505050505050502f524e42514b4231522062204b516b71202d2031203101
000000000a00000000000000000607fb328a
```

SHA-256: `dcf84913117080e89a8650db617c29e20179c6b58b6f45310df7253d46b80bd9`

### Fixture 4 — infrastructure termination, unresolved outcome, zero samples

Semantic object: an entirely ordinary, non-terminal, two-ply position (`1.Nf3 Nf6`, both
`FLAG_NORMAL`) — capped or aborted mid-game, which is an infrastructure/configuration event, not
a chess-rules claim, so there is no legality constraint to satisfy here the way there is for a
natural termination. `gameId=4`, outcome `unresolved` / `moveCap`, **zero samples** (exercising
the accept-zero-sample case, §13).

Full bytes (156 bytes total), hex:
```
56535052000000010000000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d30303031000000000000000000000000000000000000000000000000000000000000
beef12666978747572652d6275696c642d303030310000000001f400000000000000000601000000
0000001c00000000000000040003060000000002054600000b7e000000000000189cefa9
```

SHA-256: `e7b5d4245aa69a66acd5211de206f40cbd93b3bb9f26437a81d32036e739b01a`

### Fixture 5 — optional candidate section absent

Fixtures 1, 2, 3, and 4 above are all already this case (`candidatesPersisted = 0x00` at header
offset 117) — no separate fixture needed.

### Fixture 6 — optional candidate section present

Fixture 1's header with byte offset 117 (`candidatesPersisted`) changed `0x00 -> 0x01`, plus one
`CandidateSet` appended to the frame body before the `crc32` trailer: `ply=0, depth=6,
candidateCount=1`, one `SearchCandidate{move=0x070c (f2f3), rank=0, scoreKind=0, score=35,
pvLength=0, complete=1}`; `frameLength` and `crc32` recomputed for the new body length.

Full bytes (268 bytes total), hex:
```
56535052000000010000000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d30303031000000000000000000000000000000000000000000000000000000000000
beef12666978747572652d6275696c642d303030310000000001f400000000000000000601010000
0000008c00000000000000010001000000000004054d000009340000078e000007fb000000000001
000000003a726e62716b626e722f70707070707070702f382f382f382f3550322f50505050503150
502f524e42514b424e522062204b516b71202d203020310100000000230000000000000000060000
000100000000000000060001054d000000000000230000013da4705e
```

SHA-256: `194abdf017ef31cd89226044452757ed848a9671a60c5326571bb66be514cc8a`

### Fixture 7 — maximum-length-but-valid FEN/string case

Fixture 1's shape, sample `fen` field replaced with a 100-byte ASCII string (the real FEN content
padded with trailing space characters past its natural length — spaces are ASCII and this only
tests the length boundary, not content validity), `bstr8` length byte `= 100`; `frameLength`/
`crc32` recomputed.

Full bytes (284 bytes total), hex:
```
56535052000000010000000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d30303031000000000000000000000000000000000000000000000000000000000000
beef12666978747572652d6275696c642d303030310000000001f400000000000000000601000000
0000009c00000000000000010001000000000004054d000009340000078e000007fb000000000001
0000000064726e62716b626e722f70707070707070702f382f382f382f3550322f50505050503150
502f524e42514b424e522062204b516b71202d203020312020202020202020202020202020202020
20202020202020202020202020202020202020202020202020010000000023000000000000000006
a9069298
```

SHA-256: `0e27990b82172f41bdcb8f1cb5b4063ecf94d966fc30f60fac4d63193adbc08e`

### Fixtures 8, 9, 10 — specified as exact deltas from Fixture 1

**Revision note:** the prior draft's Fixture 8 entry said a file consisting only of a truncated
trailing frame "yields zero games, not an error" for a *normal* read. That directly contradicted
§9's own normal-reader rule and is corrected here: a normal read of this fixture is now
specified to **error**, matching §9/§11 precisely. The bytes and truncation point are unchanged
in spirit (still Fixture 1 cut mid-body); only the documented decoder behavior for a *normal*
read changes.

| # | Case | Construction | Normal-reader behavior |
|---|---|---|---|
| 8 | Malformed / truncated frame | Fixture 1 (242 bytes), truncated to its first 190 bytes — the header (120 bytes) and the 4-byte `frameLength` field are complete, but only 66 of the declared 114-byte frame body follow, well short of the full body plus the trailing 4-byte `crc32` | **reject with an explicit truncation error** — this file contains zero complete frames and a normal reader must never report that as "a valid file with zero games"; only the *explicit resume/recovery procedure* in §9 is permitted to treat a trailing incomplete frame leniently, and only as a precursor to truncate-and-append, never as a successful plain decode |
| 9 | Unknown format version | Fixture 1's header byte offset 7 (the low byte of the big-endian 4-byte `formatVersion` field, which reads `0x00000001`) changed `0x01 -> 0x02`, making `formatVersion = 2` | reject — hard, whole-file rejection, no lenient parsing attempted (§4, §7) |
| 10 | Invalid enum/tag | Fixture 1's frame byte at offset 133 (`gameOutcome`) changed `0x01 -> 0xFF` | reject — `0xFF` is not a defined `GameOutcome` value (§6, §13) |

**On construction method**: all fixtures above are produced from the same field values this
document specifies in prose (§4-§8), applied by a single-purpose, non-reusable, uncommitted
construction script (inline `struct.pack` calls per field, no `encode()`/`decode()` function, no
class) run in this session's scratch directory to eliminate hand-arithmetic transcription errors
in the hex dumps above — not the real Java serializer or Python decoder this task forbids, and
not checked into the repository. This corrective pass caught and fixed **two independent bugs**
in the prior pass's own construction script before recomputing these fixtures: a `runId` literal
that was one byte short (15 raw bytes where the spec requires 16 — the script's own bug, not a
spec bug, but exactly the kind of error this construction-and-cross-check discipline exists to
catch) and the chess-illegal Fixture 1 content described in this section's opening revision
note. Materializing these as actual `.bin` fixture files plus real Java/Python stub decoders that
consume them is #211's scope (§12).

## 11. Corruption/truncation behavior

**Revision note:** this section previously blurred normal-read and recovery-writer semantics
together. It is now a pure summary of §9's two explicitly separated rule sets; see §9 for the
full statement and the reasoning.

- **Header-level corruption** (bad magic, wrong `formatVersion`, or a read that runs out of
  bytes before the fixed 120-byte V1 header completes): reject the **whole file**.
- **Frame-level corruption** (declared `frameLength` extends past EOF, `crc32` mismatch, any
  frame-body field violating a §7 bound or §13 validity rule): a normal reader **errors** on
  that frame — it does not silently treat the file as ending early with no error. Frames before
  it, already successfully decoded and `crc32`-verified, remain valid; a streaming decoder does
  not need to re-validate or discard them, but the overall decode operation must still surface
  the error, even if some frames were already yielded.
- **A file whose only content is one incomplete frame** is the boundary case this revision
  fixes explicitly: it is **not** a valid zero-game file to a normal reader. It errors, the same
  as any other truncated frame — there is no special case where "the very first frame is also
  the last (incomplete) one" becomes silently acceptable.
- **Only the explicit, separately-invoked resume/recovery procedure** (§9) may treat a trailing
  incomplete frame leniently — and only as the trigger for its own truncate-and-append behavior,
  never as a successful plain decode. A normal reader never performs this procedure implicitly.
- **No silent skip-and-continue past corruption anywhere in a file, under either mode.** Recovery
  handles exactly one shape (a good prefix plus one incomplete trailing frame); it does not
  repair or skip a corrupt frame in the middle of a file, and neither does a normal reader.

## 12. #207 / #210 / #211 exports and ownership

**To #207 (shard/game-ID storage):** VSPR's `gameId` is an always-present `i64`, unique within
one `runId` (§9) — exactly the value #207's `Optional[int]`-equivalent field needs to receive
once it crosses into the shard format; VSPR itself has no "absent gameId" state, so the
`Optional`-ness only begins at #207's own boundary (a shard record not sourced from VSPR at all).

**To #210 (ingestion):** the decoder #210 builds must validate, before yielding any semantic
record: magic + `formatVersion` (whole-file reject on mismatch, §4/§7), every
`frameLength`/`crc32` pair (frame reject on mismatch, §9/§11 — including the "only frame is
incomplete" case), every bound in §7 (reject on violation, before the corresponding allocation),
every enum tag in §6 (reject on an undefined value), the `(gameOutcome, terminationReason)`
pairing table in §6 (reject on an invalid pairing), and that `ply` values on samples and
candidate sets are `< playedMoveCount` (reject on violation). It should also use
`generatorNetworkSha256` and `engineBuildId` (§6), not just `generatorNetworkUuid`, wherever
exact-artifact or exact-build provenance is what a consumer actually needs — #210's own
dataset-generation manifest is the natural place to carry these through. #210's decoder is
**not** responsible for research-policy decisions — it does not interpret `opaqueConfig`
payloads, does not decide whether a `gameId` collision across multiple input files is acceptable
(that is #210's own ingestion-time policy), and does not re-derive or second-guess
`GameConfig.maxPlies` against `Board.UNMAKE_POOL_SIZE`.

**Ownership split between #220 and #211 (resolved, revision to the prior acceptance criteria):**

- **#220 (this document) owns:** the semantic wire contract, the exact byte layout, the exact
  golden fixture specification (§10, given as hex/SHA-256 plus deltas), and the corruption/
  version rejection rules (§7, §9, §11, §13). #220 does **not** own, and does not depend on,
  any materialized `.bin` fixture files or any Java/Python code, real or stub.
- **#211 owns:** materializing §10's fixtures as real, checked-in files; a real Java
  implementation/stub and a real Python implementation/stub that each consume those exact bytes;
  and the deterministic, cross-language PR-CI checks that assert both languages agree on the
  same golden vectors (matching #211's own current scope, which already lists "Java ↔ Python
  decoding of the same golden vectors" and "malformed, truncated, and version-mismatched input
  rejection" as hand-authored-fixture tests against #220's vectors).

This resolves the prior drift, where #220's own acceptance criteria asked for "a Java and a
Python decoder (stubs are acceptable)" checked against the golden vectors — that requirement is
removed from #220 (see the GitHub issue update accompanying this revision) and now belongs
entirely to #211, which already depends on #220 for its input. #211 does not depend on anything
#220 doesn't already provide as of this revision, so there is no dependency cycle: #220 → #211
is the only edge.

## 13. Failure-mode review

| Scenario | Behavior |
|---|---|
| Truncated header | reject (whole file, §11) |
| Truncated game frame | reject with an explicit error (that frame; §9, §11) |
| A file whose only content is one incomplete frame | reject with an explicit error — **not** a valid zero-game file (§9, §11; this revision's central fix) |
| Corrupted length field (`frameLength` implying a read past EOF, or exceeding the 64 MiB §7 ceiling) | reject (that frame) |
| Absurd candidate count (>218) | reject, before allocating the candidate array (§7) |
| Absurd PV length (>128) | reject, before allocating (§7) |
| Non-ASCII FEN bytes (any byte ≥ 0x80) | reject — FEN is specified ASCII-only (§6) |
| Unknown score kind (not 0 or 1) | reject (§6, §13) |
| Mate score encoded with CP tag (tag says `cp`, value is semantically mate-shaped) | **not detectable by VSPR alone** — there is no reliable magnitude heuristic that distinguishes a legitimate large centipawn evaluation from a mis-tagged mate score without false-positives on real extreme-but-valid CP evaluations; the only enforceable check is that `scoreKind ∈ {0, 1}` (an invalid tag value is rejected), and a self-consistent-but-semantically-wrong tag is a producer bug outside what any wire format can catch |
| Unresolved outcome with natural checkmate termination | reject — `checkmate` only pairs with `whiteWin`/`blackWin` per the table in §6; `unresolved` only pairs with `moveCap`/`searchAbortOrFailure` |
| Duplicate `gameId` within one run | reject at decode time — a decoder tracks seen `gameId` values within one file and rejects a repeat |
| Resumed file with overlapping IDs | primarily avoided procedurally (§9's recommended default: new `runId` per resume); the same duplicate-`gameId` rule is the defense-in-depth check if an append path is used instead |
| Partial final write | treated as a trailing incomplete frame — a normal read **errors**; only the explicit resume procedure may truncate-and-continue (§9, §11) |
| Unknown/future format version | reject — no extension or lenient-parsing mechanism exists in V1; any version other than exactly `1` is a hard reject (§4, §7) |
| Zero-sample game | **accept** — a game may legitimately complete with no sampled positions; not corruption |
| Game with samples but no played moves | reject — structurally impossible: any sample's `ply` must be `< playedMoveCount`, and `playedMoveCount = 0` makes every possible `ply` value invalid |
| Inconsistent sample `ply` > game length | reject — the same `ply < playedMoveCount` check |

## 14. Open decisions

- **Golden fixtures' exact `.bin` materialization and cross-language stub verification** are
  #211's scope, not this document's — §10 gives the byte-exact specification; #211 produces the
  checked-in files and the code that reads them (§12).
- **`opaqueConfig` schema IDs for `adjudicationConfig`/`diversityConfig`** are reserved
  (`schemaId = 1` used only as a fixture placeholder in §10) but not assigned a real meaning —
  whichever future document defines the concrete adjudication-threshold or diversity-weighting
  field layout also owns registering its `schemaId`. This document deliberately does not invent
  that schema.
- **Whether `SearchCandidate` persistence is ever actually turned on for a real generation run**
  is unresolved by #209 itself and stays unresolved here — VSPR supports it cleanly either way
  (`header.candidatesPersisted`), which is the only thing this document needed to guarantee.
- **CRC-32's collision resistance is intentionally weak** (§15) — if a future failure model
  changes (e.g. VSPR files start crossing an untrusted boundary), this decision should be
  revisited explicitly, not silently upgraded.
- **A future genuinely-additive format change** (one where an old decoder could safely ignore
  new content) has no defined mechanism in this revision — it would need its own `formatVersion`
  value and its own explicitly-written compatibility rule at the time it's actually needed,
  rather than a speculative extension point kept around unused in the meantime.

## 15. Checksum / integrity decision

**Per-frame CRC-32, no file-level or cryptographic checksum.** Justified by the actual failure
model this format faces: accidental corruption from truncated files, partial writes during a
crash mid-generation-run, and ordinary disk/transfer corruption on trusted infrastructure — not
adversarial tampering. This matches the two existing precedents in this codebase rather than
inventing a third posture: `.nnue`'s own integrity check is a total-file-size validation
(`NnueNetwork.load`'s `expectedTotal` check), not a checksum at all; the shard format's
provenance protection is an **external** SHA-256 recorded in the network-export manifest
(`manifest_schema.py`'s `nnue_sha256` field), not an embedded per-record checksum. VSPR sits
between these two needs: unlike a `.nnue` file (one monolithic weight blob, whole-file-or-nothing
by nature), VSPR is an append-friendly stream of many independent game records, where the
realistic failure (a crash mid-write corrupting exactly the game being written when it happened)
is naturally frame-scoped — a per-frame CRC-32 catches exactly that failure at the exact
granularity it occurs, with negligible cost (4 bytes per game, standard-library-only in both
languages: `java.util.zip.CRC32`, Python's `zlib.crc32`). A whole-file SHA-256 is still the right
tool for **provenance** (proving a specific VSPR file hasn't been altered since generation) and
belongs in #210's dataset-generation manifest, exactly where the existing `.nnue` precedent
already puts that responsibility — not duplicated inside VSPR itself.

## 16. Recommended next task

**Materialize §10's fixtures and write the Java encoder/decoder plus Python decoder stub
against them — #211's stated scope**, now that this document gives it byte-exact golden vectors,
a complete field-by-field specification, and (per §12) sole, unambiguous ownership of the
implementation work. #210 (ingestion) remains blocked on both this document and #207 landing,
per #209's own dependency order; nothing in this revision changes that ordering. No self-play,
training, dataset generation, or SPRT work is unblocked by this document — those all remain
gated behind #209/#210/#211/#212 as already established.

Not recommended next: any Java or Python implementation against this specification in the same
turn that produced it, per this task's explicit instruction.
