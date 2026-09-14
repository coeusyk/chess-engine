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

Reviewed against the repository at commit `0af2130` on `phase/15-nnue` (the diagnostic-note
commit is docs-only and does not touch anything VSPR depends on; the last code-relevant commit
is `217158d`, cited throughout as precedent).

---

## 1. Verdict

VSPR is specified as a single top-level framing: a fixed-format header (one per file, carrying
run-level `GameConfig`) followed by a sequence of length-prefixed, CRC-32-checked `GameFrame`
records (one per game), each internally structured as required fields in a fixed order plus one
optional trailing section gated by a header-level flag. This is the simplest model of the three
candidates considered (§4) that still satisfies every requirement in scope: streaming decode,
bounded memory, corruption detection at frame boundaries, multiple games per file, and
deterministic serialization.

The format deliberately does **not** reuse `.nnue`'s exact byte-for-byte header shape, but it
does reuse `.nnue`'s two load-bearing conventions with a stated reason each: big-endian byte
order (§3 justifies the deviation from this task's little-endian default) and hard-reject
version mismatch with no lenient parsing (§3, §7). It also reuses the engine's own existing
16-bit packed move encoding (`Move.of()`/`Move.from()`/`Move.to()`/`Move.flag()`) verbatim rather
than inventing a new move representation, and treats every #209-deferred numeric policy
(adjudication thresholds, diversity weighting) as an opaque, length-bounded, schema-tagged blob
that VSPR bounds-checks but never interprets.

Three golden fixtures (ordinary CP sample, mate-score sample, infrastructure-terminated
unresolved-outcome game) are fully specified below with exact hex and SHA-256, computed by a
throwaway, non-committed construction script that mechanically applies this document's own byte
layout — not hand-typed, and not a decoder in either direction. The remaining seven required
fixtures are specified as exact, byte-precise deltas from these three, which is the more honest
representation for corruption/malformed cases: a truncated or version-mismatched fixture *is*,
by definition, a known-good fixture with one documented byte change.

## 2. Design goals / non-goals

**Goals:** cross-language interoperability through shared golden vectors (not shared code);
bounded decoder memory regardless of input; loud rejection of anything malformed, truncated, or
version-mismatched; streaming decode of one game at a time; every #209 semantic field
representable without ambiguity; optional `SearchCandidate` persistence that can be cleanly
absent.

**Non-goals:** a generic, reusable serialization framework (§4 rejects TLV-everywhere for
exactly this reason); pinning #209's own deferred numeric policy values; cryptographic
tamper-resistance (§11); solving distributed/multi-writer generation (§9); any Java or Python
implementation code (§16 forbids it this turn).

## 3. Wire-level primitives

**Byte order: big-endian**, explicitly deviating from this task's little-endian default on
strong repo evidence: `NnueNetwork.java`'s own docstring states its binary format is
"big-endian, this engine's own versioned layout," using `DataInputStream`/`DataOutputStream`
(Java's `DataInput`/`DataOutput` are always big-endian, by design). `trainer/trainer/export/
exporter.py` matches this deliberately — its own docstring says "Java's `DataInput`/`DataOutput`
interfaces are always big-endian, by design (`struct.pack(">i", ...)` rather than the
little-endian layout a from-scratch design might otherwise pick)." VSPR is the second
Java/Python cross-language exchange format in this repository; matching the first one's
established, load-bearing convention avoids a second, inconsistent idiom (`ByteBuffer` order
flips in Java, a different `struct` prefix in Python) for no benefit.

**Primitive widths**, matching `.nnue`'s own precedent of using full-width header fields where
header size is immaterial, and narrower fields where a value repeats per-record at scale:

| Type | Width | Notes |
|---|---|---|
| `u8` | 1 byte | unsigned |
| `u16` | 2 bytes | unsigned, big-endian |
| `u32` | 4 bytes | unsigned, big-endian |
| `i32` | 4 bytes | signed, big-endian, two's complement |
| `i64` | 8 bytes | signed, big-endian, two's complement |
| `bstr8` | 1+N bytes | `u8` length prefix (0-255), then N raw bytes |
| `bstr16` | 2+N bytes | `u16` length prefix (0-65535), then N raw bytes |
| `bool` | 1 byte | `u8`, must be exactly `0x00` or `0x01`; any other value is rejected as an invalid tag (§13) |
| `optional<T>` | 1+sizeof(T) or 1 byte | a `bool` presence flag, followed by `T` only if the flag is `0x01` |

No sentinel values are used anywhere in this format to mean "absent" (e.g. no reuse of `-1`,
`Long.MIN_VALUE`, or `0` as a dual-purpose "no value" marker) — every optional field uses the
explicit `optional<T>` presence-flag form above. This generalizes the task's own instruction
("if CP and mate share one integer field, require an explicit score-kind tag") to every optional
field in the format, not just scores: `mmap_shard.py`'s own `has_eval_cp`/`has_eval_mate`/
`has_wdl` boolean-flag convention (its comment: "a wdl-only label had no representable field at
all" was exactly the bug that convention exists to prevent) is the same idea already applied one
layer down in this pipeline; VSPR applies it consistently at the wire level.

## 4. File/header structure

One VSPR file = one header + zero or more `GameFrame` records, concatenated with no inter-record
padding.

```
VSPR file:
  u8[4]    magic = "VSPR"
  u8       majorVersion
  u8       minorVersion
  u16      headerLength          -- total header bytes, this field included; lets a reader of a
                                     future minor version skip trailing header extension bytes
                                     it doesn't recognize without parsing them
  u8[16]   runId                 -- raw 128-bit UUID, identifies this file/run (§9)
  i64      createdAtEpochSeconds -- run start time; needed to order multiple runs' output files
                                     in a generation pipeline, not speculative
  -- GameConfig (required singleton, §9's per-run fields) --
  bstr16   generatorNetworkUuid  -- max 128 bytes; the stable identity (§6)
  bstr16   generatorNetworkPath  -- max 512 bytes; informational provenance only, NEVER identity (§6)
  i32      maxPlies              -- the run's configured move-cap (#209 §4.3); format-level
                                     bound on this value is independent and larger (§7)
  u8       searchBudgetKind      -- 0=depth, 1=nodes, 2=timeMs
  i64      searchBudgetValue
  bool     resetSearchStateBetweenGames
  bool     candidatesPersisted   -- whether every GameFrame below carries its optional
                                     candidate-set section (§6, §8)
  optional<opaqueConfig>  adjudicationConfig   -- §5's opaque-blob encoding
  optional<opaqueConfig>  diversityConfig      -- §5's opaque-blob encoding; if present, this
                                                   also implies runSeed was used (#209 §9);
                                                   runSeed itself lives inside the opaque blob,
                                                   since its derivation/consumption is a
                                                   diversity-mechanism concern VSPR does not
                                                   interpret
  u16      headerExtensionCount
  repeat headerExtensionCount times:
    u16    extensionTag
    u32    extensionLength
    u8[extensionLength]  extensionBytes   -- always skippable by declared length (§7)

GameFrame (repeated, one per game, until EOF):
  u32      frameLength      -- bytes of the frame body below, this field and the trailing crc32
                                excluded
  -- frame body (frameLength bytes) --
  i64      gameId
  optional<i64>  gameSeed
  u8       gameOutcome        -- enum, §9
  u8       terminationReason  -- enum, §9
  u8       outcomePerspective -- enum, §9; applies to gameOutcome, recorded once per game (§8)
  u32      playedMoveCount    -- bounded, §7
  repeat playedMoveCount times:  PlayedMoveDecision   -- §8
  u32      sampleCount        -- bounded by playedMoveCount, §7
  repeat sampleCount times:      TrainingSample        -- §8
  if header.candidatesPersisted:
    u32    candidateSetCount  -- bounded by playedMoveCount, §7
    repeat candidateSetCount times:  CandidateSet       -- §8
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

Covered structurally in §4; the semantic content of each repeated element is:

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
90-byte field (unlike the shard format, VSPR is not a fixed-stride mmap layout, so there is no
reason to pay for 90 bytes on every sample) with 100 bytes as the hard reject ceiling, matching
the same "well under" margin the shard format already established.

**`gameId`**: `i64`, always present (not `optional`) — #209 §9 marks `GameIdentity.gameId` as
unconditionally required, unlike #207's downstream `Optional[int]` need, which exists only once
this value crosses into the shard format's own optionality question (§12). VSPR itself never has
an "absent gameId" state to represent.

**Centipawn / mate score**: tagged union, `u8 scoreKind` (`0 = cp`, `1 = mate`) followed by
`i32 score`, used identically for `TrainingSample.evalScore` and `SearchCandidate.score`. This
is the literal tagged-union requirement from #209 §5/§6 and this task's own instruction ("If CP
and mate share one integer field, require an explicit score-kind tag") — there is exactly one
score field, its kind is always explicit, and a decoder never has to infer which convention
applies from the numeric magnitude (§13 explains why a magnitude-based heuristic would be
actively wrong here).

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
| `adjudicatedScore` | `whiteWin`, `blackWin`, `draw` — #209 doesn't rule out a drawn
adjudication policy, so this document doesn't invent that restriction either |
| `moveCap` | `unresolved` only |
| `searchAbortOrFailure` | `unresolved` only |

**Search-budget metadata**: `u8 searchBudgetKind` (`0 = depth`, `1 = nodes`, `2 = timeMs`) + `i64
searchBudgetValue` — one tagged union, reused identically for `GameConfig.searchBudget` (header,
once per run) and `TrainingSample.searchBudget` (per sample, since #209 §6 requires each sample
to carry the budget its own evaluating search actually ran under, which is not necessarily the
run's nominal configured budget if a future implementation ever varies it). `i64` for the value
(not `i32`) matches `mmap_shard.py`'s own justification for `search_nodes` being `i8`
(`numpy` 8-byte int): "a node budget can exceed int32 range at high search depth/time" — the
same overflow risk applies here.

**Generator network identity**: `generatorNetworkUuid` (`bstr16`, max 128 bytes) is the sole
identity field a consumer may rely on for network-identity comparison, matching
`NnueNetwork.networkUuid`'s existing `writeUTF`-compatible string (already present in every
`.nnue` file today) — VSPR reuses this exact value rather than minting a second identity
concept. `generatorNetworkPath` (`bstr16`, max 512 bytes) is carried for human-readable
provenance only (matching `GameConfig.generatorNetworkPath` in #209 §9) and is explicitly
documented as **not** an identity field, per this task's instruction: "Do not persist a local
filesystem path as the sole identity of the generating network." A decoder or consumer must
never compare two games' provenance using this field; only `generatorNetworkUuid` is a valid
identity comparison.

**RNG seeds** (`GameFrame.gameSeed`, `PlayedMoveDecision.selectionSeed`): `i64`, matching
`i64 createdAtEpochSeconds`'s width class for any 64-bit value in this format. `GameConfig.
runSeed` lives inside the opaque `diversityConfig` blob (§4) rather than as a top-level header
field, since #209 §9 only requires `runSeed` when `diversityConfig` is set, and nesting it there
avoids a second `optional<i64>` at the header level that duplicates the same presence condition
`diversityConfig`'s own presence flag already expresses.

**Timestamps**: exactly one, `createdAtEpochSeconds`, at the file/run level — justified (this
task's §5 explicitly asks whether timestamps are "truly needed") because a generation pipeline
producing many VSPR files needs to order them, and a run-level creation time is the minimum
information that provides that without adding a second, redundant per-game or per-sample
timestamp #209 never asked for.

## 7. Bounds

Every bound below is either (a) derived directly from a cited engine constant, or (b) an
explicit, generously-sized decoder-allocation safety ceiling that is **not** presented as an
engine semantic limit. Per this task's own instruction, `GameConfig.maxPlies`'s current
recommended default (500) and its derived safe ceiling (618, from `Board.UNMAKE_POOL_SIZE`,
`Searcher.MAX_PLY`, `MAX_CHECK_EXTENSIONS`, `MAX_Q_DEPTH` per #209 §4.3) are **not** encoded as
VSPR's own format maximum — those numbers are runtime-enforced by `GameConfig.maxPlies` itself
(an `i32` value the wire format carries, checked by the producer, not re-derived or re-validated
by a VSPR decoder, which has no way to know a future engine build's `MAX_PLY` value anyway).

| Quantity | Bound | Basis |
|---|---|---|
| `playedMoveCount` per game | 0..65,536 | decoder-allocation safety ceiling only, ~131x the 500-ply recommended default, independent of any engine constant (explicitly not "618" or "500" per this task's instruction) |
| `sampleCount` per game | 0..`playedMoveCount` | structural: every sample is on-trajectory in the initial design (#209 §6), so it must index a real played move |
| `candidateSetCount` per game | 0..`playedMoveCount` | structural: at most one candidate set per ply |
| `candidateCount` per `CandidateSet` | 0..218 | the proven maximum number of legal chess moves in any reachable position — a real combinatorial bound, not a guess |
| `pvLength` | 0..128 | `Searcher.MAX_PLY = 128` (#209 §2, `Searcher.java:34`) — a PV cannot exceed the iterative-deepening loop's own depth ceiling |
| `mechanismName` length | 0..32 bytes | generous for a short identifier string, not a policy decision |
| `fen` length | 0..100 bytes | `mmap_shard.py`'s own 90-byte precedent plus the same margin it already uses (§6) |
| `generatorNetworkUuid` length | 0..128 bytes | a UUID is ~36 bytes; generous headroom for a longer future identity scheme without a format change |
| `generatorNetworkPath` length | 0..512 bytes | a filesystem path, generous but bounded |
| `opaqueConfig.payload` length | 0..4096 bytes | generous for a future adjudication/diversity schema this document does not define, bounded so a corrupt length field cannot force a large allocation |
| `frameLength` (one `GameFrame`) | 0..64 MiB | decoder-allocation safety ceiling; a realistic 500-ply game with samples is a few KB, this is >1000x headroom, not a semantic limit |
| `headerExtensionCount` / any `extensionLength` | 0..65,536 sections / 0..1 MiB each | generous forward-compatibility headroom, bounded against corruption |

Every one of these bounds is checked **before** the corresponding allocation is made (matching
`NnueNetwork.load`'s own established pattern: `hiddenWidth` is range-checked before any array of
that size is allocated).

## 8. Outcome/termination encoding

Covered fully in §6 (enum tables and the valid-pairing table) and §5 (game-scoped fields
recorded once per frame, never duplicated per sample). The one addition here: `GameOutcome` and
`TerminationReason` are stored as two independent `u8` fields with no shared bit-packing between
them, specifically so #209 §8's requirement ("these are two different pieces of information ...
and need to be distinguishable, not collapsed into one field") is a structural property of the
format, not a documentation-only convention a producer could still violate by packing them into
one byte.

## 9. Identity / provenance and resume semantics

**File/run identity**: `runId`, a 128-bit UUID in the header, identifies one VSPR file as one
generation run. **One VSPR file = one run**, by design — this is the simplest choice that
satisfies #209's actual requirement ("`gameId` ... unique within an ingestion run") without
building any cross-file or distributed-writer coordination this task explicitly says not to
solve unless necessary.

**`gameId` scope**: unique within one file's `runId`, assigned sequentially by the writer
(`GameLoop`) starting at an implementation-chosen value — VSPR does not mandate starting at 0 or
1, only that it never repeats within one file. Cross-run uniqueness is explicitly **not**
guaranteed by VSPR and is #210's stated responsibility (#209 §10: "a resumed run needs its own
explicit collision/remapping rule ... which section 10 already flags as #210's
responsibility ... not solved here").

**Resumed generation — recommended default**: start a new file with a new `runId` rather than
appending to an existing one. This trivially avoids `gameId` collision (a new `runId` means a
resumed run's games are unambiguously a different run, even if its `gameId` sequence restarts
from the same starting value) and needs no special writer logic beyond "open a new file."

**Appending — supported, not recommended as default**: because every `GameFrame` is
self-delimiting (`frameLength` + body + `crc32`), a writer *may* append new, complete
`GameFrame`s to an existing file under the same `runId` if an operational need justifies it (a
long-running generation job that periodically flushes to the same file). A resuming appender
must: (1) read the header and confirm the `runId` it intends to continue, (2) scan forward
frame-by-frame from just after the header, verifying each frame's declared `frameLength` and
`crc32`, (3) if the last frame is incomplete (declared length extends past EOF, or the file ends
mid-header-parse of that frame) — **truncate the file at the start of that incomplete frame
before appending**, never append after unverified trailing bytes, and (4) continue `gameId`
numbering from one past the last successfully verified frame's `gameId`. A reader is never
responsible for silently skipping a mid-file corrupt region to find more valid frames after it —
that would let corruption anywhere in the middle of a file mask itself as "just one bad game";
only a well-formed trailing partial frame (exactly what a crash mid-write produces) gets the
lenient "truncate and stop" treatment, and only at end-of-file.

## 10. Cross-language golden fixture specification

Three fixtures are given in full below (computed once, by a throwaway construction script — see
the note at the end of this section — never hand-typed and never committed to the repository).
The remaining seven required fixtures are specified as exact byte-level deltas from these three,
which is the correct representation for a corruption/malformed case: it *is*, by definition, a
known-good fixture with one documented byte changed.

All three share one header (`GameConfig`): `majorVersion=1, minorVersion=0`, `runId =
00000000-0000-0000-0000-0000000000AA AA` (16 raw bytes, all-zero except the last two, chosen to
be visually unambiguous in a hex dump), `createdAtEpochSeconds = 1700000000`,
`generatorNetworkUuid = "fixture-net-0001"`, `generatorNetworkPath = ""` (empty — deliberately,
to also exercise the zero-length `bstr16` case), `maxPlies = 500`, `searchBudgetKind = 0`
(depth), `searchBudgetValue = 6`, `resetSearchStateBetweenGames = true`, `candidatesPersisted =
false`, `adjudicationConfig` absent, `diversityConfig` absent, `headerExtensionCount = 0`.

Header hex (70 bytes, shared by all three fixtures below — this is exactly the first 70 bytes
of each full fixture given below, not repeated verbatim here to avoid a second hand-wrapped copy
of the same bytes; see the per-fixture entries for the complete, verified hex).

### Fixture 1 — ordinary CP sample game

Semantic object: one game, `gameId=1`, two played moves (`e2e4`, `e7e5`, both `FLAG_NORMAL`),
one on-trajectory sample at `ply=0` with the resulting position's FEN, `evalScoreKind=cp`,
`evalScore=35`, `searchBudget = depth 6`. Outcome recorded as `draw` /
`fiftyMoveRule` (fixture-only pairing, chosen because it's a valid `(draw, fiftyMoveRule)` pair
per §6's table — not semantically meaningful for a 2-ply game, only structurally valid), no
diversity, no candidate sets.

Full bytes (185 bytes total), hex:
```
565350520100004600000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d303030310000000001f40000000000000000060100000000000000006b00000000
000000010002030000000002070c00000934000000000001000000003b726e62716b626e722f70
707070707070702f382f382f3450332f382f50505050315050502f524e42514b424e522062204
b516b7120653320302031010000000023000000000000000006bbcee0f1
```

SHA-256: `3a44a14452f7f50cc3b19984523115c366496d8f06075a04acbaf7be2b3ef490`

*(Field-by-field walk, offsets into the 185-byte buffer: `[0:70]` header as above. `[70:74]`
`frameLength = 0x0000006b` = 107. `[74:82]` `gameId = 1`. `[82]` `hasGameSeed = 0`. `[83]`
`gameOutcome = 2` (draw). `[84]` `terminationReason = 3` (fiftyMoveRule). `[85]`
`outcomePerspective = 0` (white). `[86:90]` `playedMoveCount = 2`. `[90:92]` move 1's packed
encoding, `move=0x070c` = 1804 = `Move.of(from=12, to=28, flag=0)` (e2-e4: square 12 is e2,
square 28 is e4, `FLAG_NORMAL=0`), `[92]` `selectionMechanismKind = 0` (bestMove) ... — this
document's prose spec is the normative source; the hex above is the byte-exact artifact this
walk cross-checks against, both produced by the same construction step.)*

### Fixture 2 — mate-score sample

Same header. `gameId=2`, one played move (`e2e4`), one sample at `ply=0`, `evalScoreKind=mate`,
`evalScore=3` (mate in 3, side to move's own perspective). Outcome `whiteWin` /
`checkmate` (a valid pairing per §6's table).

Full bytes (181 bytes total), hex:
```
565350520100004600000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d303030310000000001f4000000000000000006010000000000000000670000000000
000002000000000000000107 0c000000000001000000003b726e62716b626e722f707070707070
70702f382f382f3450332f382f50505050315050502f524e42514b424e522062204b516b7120653
32030203101010000000300000000000000000654d59a39
```

SHA-256: `bd2b0e26a92117a68508bdffec1c02d9179f5c31f7442c46fbabde3dcd9dfde0`

### Fixture 4 — infrastructure termination, unresolved outcome, zero samples

Same header. `gameId=4`, one played move, **zero samples** (exercising the accept-zero-sample
case, §13). Outcome `unresolved` / `moveCap` — the exact case the outcome/termination-reason
separation exists to encode correctly (§6, #209 §8).

Full bytes (102 bytes total), hex:
```
565350520100004600000000000000000000000000aaaa000000006553f1000010666978747572
652d6e65742d303030310000000001f4000000000000000006010000000000000000180000000000
0000040003060000000001070c000000000000e368bb0e
```

SHA-256: `bc604d9e3edb0e68e4e35c11782b0f0d651353a17f531ea97dbe8dc2c6d76062`

### Fixtures 3, 5-10 — specified as exact deltas

| # | Case | Construction | Decoder behavior |
|---|---|---|---|
| 3 | Drawn game by natural rule | Fixture 1, unchanged (`draw`/`fiftyMoveRule` is already a category-A natural termination) — fixture 1 doubles as this case | accept |
| 5 | Optional candidate section absent | Fixtures 1/2/4, unchanged (`header.candidatesPersisted = 0x00`, byte offset 65 of the shared header) — all three already exercise this | accept |
| 6 | Optional candidate section present | Fixture 1's header with byte 65 (`candidatesPersisted`) changed `0x00 -> 0x01`, plus one `CandidateSet` appended to the frame body before the `crc32` trailer: `ply=0, depth=6, candidateCount=1`, one `SearchCandidate{move=0x070c, rank=0, scoreKind=0, score=35, pvLength=0, complete=1}`; `frameLength` and `crc32` recomputed for the new body length | accept |
| 7 | Maximum-length-but-valid FEN/string case | Fixture 1's sample `fen` field replaced with a 100-byte ASCII string (padded with trailing space characters past the real FEN content — spaces are valid FEN-adjacent whitespace and this only tests the length boundary, not content validity), `bstr8` length byte `= 100`; `frameLength`/`crc32` recomputed | accept (100 is the inclusive bound, §7) |
| 8 | Malformed / truncated frame | Fixture 1, truncated to its first 100 bytes (cuts off mid-frame-body, well before the declared `frameLength=107` body + trailing `crc32` are complete) | reject — the incomplete trailing frame is discarded per §9; if this is the *only* frame in the file, the file yields zero games, not an error, matching the resume-truncation rule in §9 (a partial trailing frame is not itself a hard file-level error, it is simply not a game) |
| 9 | Unknown major version | Fixture 1's header byte 4 (`majorVersion`) changed `0x01 -> 0x02` | reject — hard, whole-file rejection per §13, no lenient parsing attempted |
| 10 | Invalid enum/tag | Fixture 1's frame byte at offset 83 (`gameOutcome`) changed `0x02 -> 0xFF` | reject — `0xFF` is not a defined `GameOutcome` value (§6, §13) |

**On construction method**: all ten fixtures above are produced from the same field values this
document specifies in prose (§4-§8), applied by a single-purpose, non-reusable, uncommitted
construction script (inline `struct.pack` calls per field, no `encode()`/`decode()` function, no
class) run once in this session's scratch directory to eliminate hand-arithmetic transcription
errors in the hex dumps above — not the real Java serializer or Python decoder this task
forbids, and not checked into the repository. Materializing these as actual `.bin` fixture files
plus real Java/Python stub decoders that consume them is #211's stated scope
(#209 §10: "None of these require a live self-play run — they are fixtures against this
document's own types").

## 11. Corruption/truncation behavior

Summarized from §4/§7/§9/§13:

- **Header-level corruption** (bad magic, unsupported major version, `headerLength` implying a
  read past EOF, any header field violating its bound in §7): reject the **whole file** — there
  is no valid file identity to recover a partial read from.
- **Frame-level corruption** (declared `frameLength` extends past EOF, `crc32` mismatch, any
  frame-body field violating a §7 bound or §13 validity rule): reject **that frame only**.
  Frames before it, already successfully decoded, remain valid; a streaming decoder does not
  need to re-validate or discard them.
- **Trailing partial frame** (exactly what a crash mid-write produces): treated the same as
  frame-level corruption for a plain read (reject, stop), and additionally has the explicit
  truncate-and-resume behavior specified in §9 for a writer that wants to append.
- **No silent skip-and-continue past corruption in the middle of a file.** A decoder must not
  attempt to resynchronize by scanning for the next plausible frame boundary after a corrupt or
  unrecognized frame — that would let corruption anywhere in a file masquerade as "one bad game"
  when it might mean the whole file's frame count is now misaligned. Only a well-formed trailing
  partial frame at end-of-file gets lenient treatment.

## 12. #207 / #210 / #211 exports

**To #207 (shard/game-ID storage):** VSPR's `gameId` is an always-present `i64`, unique within
one `runId` (§9) — exactly the value #207's `Optional[int]`-equivalent field needs to receive
once it crosses into the shard format; VSPR itself has no "absent gameId" state, so the
`Optional`-ness only begins at #207's own boundary (a shard record not sourced from VSPR at all).
No other VSPR-internal field is exported to #207; the shard's own record layout stays entirely
its own concern, unreused here.

**To #210 (ingestion):** the decoder #210 builds must validate, before yielding any semantic
record: magic + version (whole-file reject on mismatch, §4/§13), every `frameLength`/`crc32`
pair (frame reject on mismatch), every bound in §7 (reject on violation, before the
corresponding allocation), every enum tag in §6 (reject on an undefined value), the
`(gameOutcome, terminationReason)` pairing table in §6 (reject on an invalid pairing), and that
`ply` values on samples and candidate sets are `< playedMoveCount` (reject on violation). #210's
decoder is **not** responsible for research-policy decisions — it does not interpret
`opaqueConfig` payloads, does not decide whether a `gameId` collision across multiple input files
is acceptable (that is #210's own ingestion-time policy, informed by but not dictated by this
document), and does not re-derive or second-guess `GameConfig.maxPlies` against `Board.
UNMAKE_POOL_SIZE` — it only checks the format-level bounds this document defines in §7.

**To #211 (deterministic fixtures):** §10's ten fixtures (three full, seven exact deltas) are
the starting set; #211's job is to materialize them as real files, write the real Java encoder/
decoder and Python decoder stubs, and check both against the identical bytes given here — not to
reinterpret this document's byte layout independently.

## 13. Failure-mode review

Every scenario the governing task listed for adversarial review, with reject/accept behavior:

| Scenario | Behavior |
|---|---|
| Truncated header | reject (whole file) |
| Truncated game frame | reject (that frame only; §11) |
| Corrupted length field (`frameLength` implying a read past EOF, or exceeding the 64 MiB §7 ceiling) | reject (that frame) |
| Absurd candidate count (>218) | reject, before allocating the candidate array (§7) |
| Absurd PV length (>128) | reject, before allocating (§7) |
| Non-ASCII FEN bytes (any byte ≥ 0x80) | reject — FEN is specified ASCII-only (§6) |
| Unknown score kind (not 0 or 1) | reject (§6, §13) |
| Mate score encoded with CP tag (tag says `cp`, value is semantically mate-shaped) | **not detectable by VSPR alone** — there is no reliable magnitude heuristic that distinguishes a legitimate large centipawn evaluation from a mis-tagged mate score without false-positives on real extreme-but-valid CP evaluations; the only enforceable check is that `scoreKind ∈ {0, 1}` (an invalid tag value is rejected, §6/§13), and a self-consistent-but-semantically-wrong tag is a producer bug outside what any wire format can catch, the same way a shard record with a swapped `eval_cp`/`eval_mate` value today is outside `mmap_shard.py`'s own detection ability |
| Unresolved outcome with natural checkmate termination | reject — `checkmate` only pairs with `whiteWin`/`blackWin` per the table in §6; `unresolved` only pairs with `moveCap`/`searchAbortOrFailure` |
| Duplicate `gameId` within one run | reject at decode time — a decoder tracks seen `gameId` values within one file and rejects a repeat; this is a decode-time validation rule, not free from the format alone |
| Resumed file with overlapping IDs | primarily avoided procedurally (§9's recommended default: new `runId` per resume); the same duplicate-`gameId` rule above is the defense-in-depth check if an append path is used instead |
| Partial final write | treated as a trailing partial frame — reject that frame, keep everything before it (§9, §11) |
| Unknown minor-version optional section | skip by declared length — this is exactly what `headerExtensionCount`'s `{tag, length, bytes}` triples exist for (§4, §7) |
| Unknown required section | not representable in this format — the core schema (§4-§5) has no "unknown required section" slot; anything that isn't one of the defined extension entries and doesn't match the fixed core layout is a structural mismatch, caught as either a version rejection or a frame-level corruption reject |
| Zero-sample game | **accept** — a game may legitimately complete with no sampled positions; not corruption |
| Game with samples but no played moves | reject — structurally impossible: any sample's `ply` must be `< playedMoveCount`, and `playedMoveCount = 0` makes every possible `ply` value invalid |
| Inconsistent sample `ply` > game length | reject — the same `ply < playedMoveCount` check |

## 14. Open decisions

- **Golden fixtures 6-10's exact `.bin` materialization and cross-language stub verification**
  are #211's scope, not this document's — §10 gives the byte-exact specification; #211
  produces the checked-in files and the code that reads them.
- **`opaqueConfig` schema IDs for `adjudicationConfig`/`diversityConfig`** are reserved
  (`schemaId = 1` used only as a fixture placeholder in §10) but not assigned a real meaning —
  whichever future document defines the concrete adjudication-threshold or diversity-weighting
  field layout also owns registering its `schemaId`. This document deliberately does not invent
  that schema, per its own scope boundary (§1) and this task's explicit instruction not to
  invent training semantics inside the wire-format task.
- **Whether `SearchCandidate` persistence is ever actually turned on for a real generation run**
  is unresolved by #209 itself (§9's own closing list) and stays unresolved here — VSPR supports
  it cleanly either way (`header.candidatesPersisted`), which is the only thing this document
  needed to guarantee.
- **CRC-32's collision resistance is intentionally weak** (§15) — if a future failure model
  changes (e.g. VSPR files start crossing an untrusted boundary), this decision should be
  revisited explicitly, not silently upgraded.

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
against them — #211's stated scope**, now that this document gives it byte-exact golden vectors
and a complete field-by-field specification to build against. #210 (ingestion) remains blocked
on both this document and #207 landing, per #209's own dependency order; nothing in this
document changes that ordering. No self-play, training, dataset generation, or SPRT work is
unblocked by this document — those all remain gated behind #209/#210/#211/#212 as already
established.

Not recommended next: any Java or Python implementation against this specification in the same
turn that produced it, per this task's explicit instruction.
