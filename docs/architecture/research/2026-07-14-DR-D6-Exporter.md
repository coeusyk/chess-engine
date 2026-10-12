# 1. Executive Summary

We recommend that Vex define a **custom binary “.nnue” format** tailored to its needs rather than rely on a general ML format.  The file should begin with a **magic number and version fields**, followed by metadata (feature-spec ID, network ID, quantization details) and then the **raw quantized weights** in a fixed order.  This approach minimizes dependencies and parsing overhead [First principles] while ensuring reproducibility.  The format will use a fixed little-endian layout for all multi-byte fields.  A strong cryptographic checksum (e.g. SHA-256) will be embedded to detect corruption.  We embed metadata in the same file rather than using sidecars (so it “travels” with the model).  We include explicit version identifiers for the file format, the feature encoder, and the canonical network, so the loader can validate compatibility [First principles].  Compared to alternatives, ONNX or FlatBuffers impose heavy runtimes or parsing complexity; Stockfish’s format is simple but undocumented, whereas our custom format will be fully specified.  In short, Vex’s exporter will be a lean, deterministic pipeline that writes a single-file binary (.nnue) containing all model data, metadata, and integrity checks. 

# 2. Problem Definition

The Vex engine needs a robust pipeline to export NNUE networks from the Python trainer into a binary file that the Java engine can load.  This file format must serve the following goals: 
- **Cross-language parity & reproducibility:** The Python-generated file must be identical when read by the Java engine on any platform [First principles].  
- **Strict separation:** Python and Java components must interact only via this file interface, preserving architecture invariants.  
- **Versioning & evolution:** The format must accommodate future changes (e.g. new feature sets or network architectures) with clear compatibility rules [First principles].  
- **Integrity & security:** The loader must detect corruption or tampering, avoiding crashes or silent errors.  
- **Performance:** Export and load should be fast and use minimal resources.  
- **Maintainability:** The design must be clear and decomposed into well-defined components, with automatic validation (Graphify, ADRs) upholding invariants.  

Existing state: the dataset, feature encoder, trainer, and quantizer are complete, but no export pipeline or file format yet.  Our task is to select and design the best approach for this interface, comparing current practice (Stockfish NNUE, other engines, ONNX, etc.) and applying first-principles tradeoffs.  

# 3. Industry Survey

**Stockfish NNUE:**  Stockfish uses a custom `.nnue` binary.  In practice its networks are named `nn-<12 hex>.nnue` where the hex is SHA-256 of the file contents.  The file has a fixed header (with a description field) and raw weights.  However, Stockfish explicitly does _not_ guarantee cross-version compatibility: “different architectures require different inference code so you shouldn’t expect neither backwards nor forwards compatibility”.  The Stockfish approach is minimal and fast, but ad hoc (no public spec), relies on internal conventions, and is tied to its C++ inference.

**Other chess engines (Ethereal, Berserk, Koivisto, etc.):** Many modern engines (Ethereal, Berserk, Koivisto, Seer, Ceres, etc.) have added NNUE support, presumably using similar approaches (either adopting Stockfish’s format or their own).  There is no standard cross-engine NNUE format beyond Stockfish’s; many engines simply load Stockfish-format nets or convert them.  We found little published on their formats.  It suggests industry practice is to use a simple custom binary with fixed layout.

**ONNX:** The Open Neural Network Exchange (ONNX) is a general model format for deep learning.  ONNX files are protocol-buffer binaries that include not only weights but the full computation graph.  This enables framework interoperability (convert TensorFlow/PyTorch models easily to ONNX and back).  However, ONNX introduces heavy dependencies (protobuf parser), carries full graph structure, and typically produces larger files.  Stockfish developers pointed out that NNUE is “fast enough and ONNX models are not very beneficial, and it will clutter release binaries”.  In context, ONNX’s flexibility isn’t needed: Vex’s NNUE architecture is fixed and known, so storing a generic graph is unnecessary overhead [First principles].  We do not follow ONNX solely because it’s used in other ML contexts [Context-dependent].

**MLIR:** MLIR is an intermediate representation/compilation framework (from LLVM/TensorFlow) for optimizing ML models.  It is used internally by some compilers (e.g. ONNX-MLIR), but it is _not_ a distribution format for end models; it is a compile-time IR.  Thus MLIR is irrelevant to runtime model export [Known pattern].

**FlatBuffers/Cap’n Proto:** These are schema-based binary serialization systems (originally from Google).  Both allow zero-copy reads and no unpacking step, but require a schema and code generation.  In practice, they are used for message passing or game data, not common for neural network weight files.  Using FlatBuffers or Cap’n Proto would embed more abstraction (and parsing logic) than needed: we’d need to define a schema, link generated code in Java/Python, and handle versioning with them.  While they provide nice versioned schemas, the cost (new dependencies, learning curve, larger binary format) seems high for a simple static weight dump [First principles].  

**Protocol Buffers:** Google's protobufs allow extensible structured data, but they need a parser runtime.  An ONNX file *is* a protobuf, but directly designing a lightweight proto for Vex nets is possible.  However, protobuf parsing can be slower and requires linking (the engine would need a Java protobuf library).  Protobuf is typically used for RPC, not as a low-level model weight format.  It also doesn’t natively support efficient random access to large arrays (needs unpacking or repeated fields).  

**Other ML formats:**  Modern ML communities have spawned formats like GGUF (used by Llama.cpp), Safetensors, etc.  For example, Safetensors (Hugging Face) stores a JSON metadata section and raw tensor data, with a focus on security (no code execution) and fast loading.  Safetensors is used for sharing large vision/NLP models, supporting quantized tensors.  It exemplifies embedding metadata and a lock-step load design.  While instructive, Safetensors still requires a metadata JSON parser and is overkill for NNUE.  

**Key takeaways:** Industry practice for chess NNUE has trended toward simple custom binaries (Stockfish style) [Known pattern].  General ML formats (ONNX, TF, PT) emphasize graph interop, which we don’t need.  Serialization systems like FlatBuffers/Protobuf/Cap’n Proto add complexity.  Instead, we lean toward a minimal custom format with clear fields, drawing from known good patterns (magic number, version, checksum, structured header) [First principles].  

# 4. Comparative Analysis

We compare candidate approaches along Vex’s criteria:

| Approach | Flexibility/Extensibility | Overhead/Complexity | Performance (load/export) | Parsing Dependency | Suitability for Vex |
|---|---|---|---|---|---|
| **Stockfish .nnue (custom)** | Fixed schema; minimal versioning (implicit) | Low; only weights and a few fields | Very fast (raw I/O only) | None (simple code reads binary) | **High** for speed; but _no official spec_, limited version handling. |
| **ONNX** | High (graph + metadata) | High; file includes full compute graph | Moderate (parsing needed) | ONNX runtime / protobuf libs | **Low**: supports many ops but unnecessary; heavy binary and deps. |
| **FlatBuffers/Cap’n Proto** | Extensible schema; random access | Moderate; need schema files and code gen | Very fast (zero-copy) | FlatBuffers/C++ or Java runtime | **Medium**: fast but complex integration, not standard for models. |
| **Protobuf (custom schema)** | Extensible fields; forward/back compat | Moderate; binary with field tags | Slower than raw binary (parsing) | Protobuf runtime | **Low**: adds lib; more overhead, not needed. |
| **Safetensors (Hugging Face)** | Designed for tensors; JSON metadata | Low to moderate; JSON + raw segments | Fast; lazy-load possible | JSON parser needed | **Medium**: secure and simple but requires JSON and JSON parsing in Java. |
| **JSON/ASCII export** | Human-readable, flexible | High (very large files) | Very slow | Standard JSON parsing | **Low**: too slow/large; disallowed by performance requirement. |

- *Flexibility:* ONNX and protobuf allow adding new fields without breaking old readers, but our NNUE architecture changes rarely.  Stockfish’s experience suggests no backward compatibility is normal, so strict versioning is fine [First principles].
- *Overhead:* Only raw formats (Stockfish, custom binary, Safetensors) avoid heavy parsing.  ONNX/protobuf inflate file size and parsing.
- *Parsing:* A custom reader in Java is trivial vs. embedding ONNX libs.  FlatBuffers requires linking Google libs or codegen, which is heavy for engine distribution.
- *Performance:* Binary dumps load fastest.  JSON/ASCII is out of question given speed target.
- *Dependencies:* Custom binary or Safetensors (only JSON and memory map) have minimal extra libs.  ONNX/FlatBuffers would enlarge the runtime.

**Conclusion of analysis:** A purpose-built binary format [First principles] is best: it yields minimal file size, maximum load speed, and no heavy runtimes.  We avoid inheriting complexity solely from “industry standard” (Stockfish’s simplicity is a positive [Known pattern], but their specific layout is not mandated).  We favor embed-in-binary metadata (like Safetensors/PNG do).  Checksums and magic numbers are standard for integrity.  Little-endian is natural on modern hardware.  For versioning, we incorporate explicit fields (unlike Stockfish’s implicit method) so we can handle evolution systematically.  

# 5. Recommended Architecture

We propose an **export architecture** with these key elements:

- **Single-file format**: The `.nnue` file will contain *all* network data and metadata (weights, biases, version IDs, checksum) in one binary blob.  This “one file” approach avoids losing context.  
- **Magic number + version**: The file begins with a fixed ASCII or binary magic (e.g. `0x56 0x58 0x4E 0x4E 0x55 0x45` = “VXNnue”) and a format-version integer.  This identifies the file type and format revision [First principles].  Versioning here ensures loaders can reject unknown formats or parse new fields safely.  
- **Endian convention**: All multi-byte fields (ints, floats) are written in little-endian order [Context-dependent] (x86/ARM defaults).  The loader must use this convention to parse.  
- **Metadata section**: A header block (fixed-size or length-prefixed) containing: feature-spec ID (e.g. a hash or version number of the feature encoder), canonical-network version, network dimensions, quantizer parameters (bit depth, offsets), timestamp/author (optional), and a description string.  Embedding metadata inside the file ensures it “travels” with the data.  
- **Data section**: Following the header, store the quantized network arrays in a known sequence (e.g. first-hidden-layer weights, second layer, biases).  All integers/floats are laid out contiguously so that the loader can memory-map or sequentially read them quickly.  No compression or padding is needed; the format is already compact.  
- **Checksum**: At the end (or part of header), include a SHA-256 (or similar) digest of the file’s contents (excluding the digest field itself).  This allows the loader to verify integrity.  We recommend SHA-256 as a strong, collision-resistant default [First principles].  

This architecture emphasizes clarity: the header has explicit fields (not “hidden” bits), and the data layout is fully determined by those header values.  We avoid any external intermediate representation or ancillary files, aligning with the principle that “metadata travels with the file”.  Compared to Stockfish’s undocmented header or ONNX’s generic graph, our format is fully self-describing for Vex’s needs.  

# 6. Binary Format Specification

The `.nnue` file is structured as follows (all multi-byte fields are **little-endian** unless noted):

```
[FileHeader]
| magic (8 bytes) | format_version (u16) | feature_spec_id (u32) | network_id (u32) | quantization_bits (u8) | reserved (padding) | metadata_len (u16) | 

[Metadata]
| metadata_len bytes of UTF-8 (description, author, date, etc) |

[NetworkDimensions]
| input_size (u32) | hidden_size (u32) | output_size (u32) | other dims if needed |

[Weights]
| input_to_hidden_weights (array of int16 or float) |
| hidden_bias (array) | hidden_to_output_weights (array) | output_bias (value) |

[Footer]
| checksum (32 bytes SHA-256 digest) |
```

- **Magic:** A short fixed sequence (e.g. “VXNnue\u0000\u0001”) to identify Vex NNUE files.  The magic distinguishes it from other file types [First principles].  
- **Format version:** A small integer (initially 1) indicating this binary’s schema version.  If we change format, increment this.  The loader should reject unknown versions [Context-dependent].  
- **Feature spec ID:** A version or hash of the feature-encoder specification (immutable for a given training pipeline).  If features change, the ID changes.  The loader must check this to ensure it’s using the correct encoder.  
- **Network ID:** An identifier (e.g. incrementing build number or hash) for the canonical network architecture.  This helps track provenance and ensure the engine matches network assumptions.  
- **Quantization bits:** The bit-width of stored weights (e.g. 16).  Helps loader interpret the data type.  
- **Metadata:** Following the header, an optional UTF-8 text block (length given by `metadata_len`) for human-readable info (e.g. “trained on 2026-07-10 with dataset v3”, etc.).  This section is not needed for evaluation, but useful for reproducibility audits.  It is not parsed by the engine, but can be printed or logged for debugging.  
- **Network Dimensions:** Fields explicitly stating the number of inputs, hidden units, outputs.  This eliminates implicit assumptions in code.  The loader allocates arrays of these sizes.  
- **Weights data:** The core NNUE parameters, in a fixed order.  For example, store all weights of the first hidden layer (size = hidden_size × input_size) as int16s, then biases (hidden_size entries), then second-layer weights (hidden_size × output_size) and bias.  Because all dimensions are known, the loader simply reads the correct number of values.  No separators or delimiters are needed; lengths follow from the dimensions.  
- **Checksum:** The final 32 bytes are the SHA-256 of all previous bytes (header through weights).  On load, the engine can recompute and verify this digest to detect corruption.  

We define **little-endian** explicitly in the spec; big-endian processors are rare, and Java’s `ByteBuffer` can be set to little-endian.  All integer fields use fixed sizes (u8, u16, u32) to avoid ambiguity.  Floating-point (if used) should be IEEE 754 (float32) if any layer remains in float; but our plan is to quantize to int16 for the bulk, which is fastest for inference.

# 7. Export Pipeline

The export pipeline in Python proceeds in discrete stages (adhering to architecture invariants):

1. **Canonical network creation:** The trained CanonicalNetwork is produced (already available as ImmutableCanonicalNetwork).  
2. **Quantization:** The ImmutableQuantizedCanonicalNetwork is generated by applying the existing quantizer.  This yields all weights/biases as integers (e.g. int16) plus any scale parameters.  This stage is deterministic [First principles] given fixed inputs and quantization seed.  
3. **Binary writer:** A dedicated module takes the QuantizedCanonicalNetwork and writes the `.nnue` file according to the spec.  It writes header fields, then serializes the quantized arrays.  Care must be taken to write in consistent (e.g. sorted) order to be deterministic.  The writer then computes SHA-256 over the written bytes and appends it.  
4. **Graphify/Validation:** After writing, automated checks validate that the new file conforms to the spec (matching expected sizes, checksum, etc.).  Cross-language parity tests immediately follow (see Validation).  

At no point should the pipeline rely on any hidden state or non-determinism (no random shuffle, no timestamps in reproducible mode).  All steps must be idempotent and log their inputs/outputs for audit.  The use of ADRs ensures the writer’s logic matches the spec.  Graphify architecture audits should show that the writer is isolated (Python-only) and the Java loader is separate, communicating only via the file.

# 8. Loader Expectations

The Vex Java engine loader must **parse and validate** the `.nnue` file exactly as specified:

- **Magic and version check:** Read the magic bytes; if they don’t match, reject as unsupported format [Known pattern].  Read `format_version`; if it’s higher than what the engine supports, either refuse to load or fall back to a known reader.  
- **Header validation:** Read feature_spec_id and network_id.  Compare the feature_spec_id to the engine’s compiled feature encoder version; if mismatched, error out (cannot safely use this net).  Likewise verify network_id if applicable.  
- **Dimension check:** Read input_size, hidden_size, output_size.  If these do not match the engine’s expectations (for example, if engine’s code was built for a different architecture), refuse to load.  
- **Data loading:** Allocate fixed-size arrays and read the weight bytes into them.  Use buffered I/O or memory-mapping for performance if possible.  Since data is fixed-size, random access or streaming both work.  
- **Checksum verify:** Compute SHA-256 of all header+data read and compare to the stored checksum.  If it fails, abort with an error.  
- **Inference readiness:** Once loaded, the engine constructs its internal evaluation data structures.  For example, it may combine halves (white/black) or normalize biases as needed.  These steps should not alter the loaded weights (i.e. loader is pure conversion, not training).  

If any validation fails (magic wrong, version mismatch, checksum error, size inconsistency), the loader should raise a clear error.  Under no circumstances should the engine crash or use incomplete weights [First principles].  The loader code should be thoroughly unit-tested on malformed inputs as described below.  

# 9. Versioning Strategy

We handle versioning at multiple levels:

- **File-format version:** A 16-bit integer in the header.  This is incremented when the binary layout changes (e.g. adding fields).  Newer loaders can recognize older versions and either support them or decline.  Similar to PNG’s chunked format, we follow a one-file, one-version scheme; major changes should increment this [First principles].  
- **Feature-spec version:** An identifier for the feature encoder (for example, a UUID or hash of the feature-set definition).  If features (e.g. piece encodings) change, the file’s feature_spec_id changes.  The loader compares this to its compiled-in feature encoder and refuses mismatches.  There is no automatic compatibility across different feature sets [First principles].  
- **Network version:** We embed a network build number or unique ID so that one can trace which training run produced this file.  This is mainly for reproducibility records; the engine doesn’t need to check it.  
- **Backward vs Forward compatibility:** We assume **backward compatibility** only if we explicitly design for it (e.g. ignoring unknown new header fields).  In practice, we treat major changes as breaking: older engine versions will not load new networks unless explicitly coded.  This follows Stockfish’s approach.  We do not aim for forward-compatibility (old files with new engine) beyond the simple rule “unsupported fields mean error or skip”.  Thus, when evolving the format, we’ll document the policy: e.g. new engines may skip noncritical unknown metadata, but primary fields must match.

**Recommendation:** Keep the format version numeric and increase it on incompatible changes.  For optional future extensions, use a TLV (type-length-value) style in the header so unknown blocks can be skipped.  However, given our tight control over Vex’s architecture, explicit version fields suffice.  

# 10. Metadata Strategy

All essential metadata travels **inside the `.nnue` file**, not as external sidecars.  We include in the header:

- **Feature encoder version:** so the engine knows which input mapping to use.
- **Network ID and timestamp:** recording training provenance (who built it, when, on what code version).  This aids debugging and reproducibility audits.  
- **Quantization parameters:** e.g. scaling factors or zero-point if any.  (Alternatively, if quantizer is fixed, a flag might suffice.)  
- **User description:** A short UTF-8 text (max length) for notes (e.g. “trained on Stockfish games, 2026-06”).  This is strictly informational.  

We explicitly **avoid external manifests**.  As StackOverflow guidance notes, separate metadata files can easily get lost (“one day you copy the binary and not the meta”).  Embedding metadata means any copy of the model file is complete on its own.  The loader ignores any noncritical metadata fields (they’re just for humans).  

If future needs arise (e.g. large textual comments), we can reserve a byte-length field in the header as `metadata_len` to skip unknown-length text, as shown above.  

# 11. Validation Strategy

We will validate at each stage of implementation:

- **Unit tests:** Each component (header writer, weight serializer, loader parser) will have unit tests.  For example, writing a known small network and reading it back should reconstruct identical arrays.  
- **Integration tests:** The full pipeline is tested end-to-end.  E.g. train a tiny model in Python, export to `.nnue`, load it in Java, and verify that the feature input → NN output matches the Python evaluation (e.g. by evaluating a sample position).  Use randomized small nets to cover edge cases.  
- **Property tests:** Test invariants like “round-trip identity”: for random network data, export then import yields no data corruption.  Also “checksum bit flips”: manually alter a byte and expect loader to detect mismatch.  
- **Cross-language parity:** Automatic tests compare the Java engine’s NNUE evaluation to the Python-trained version on a suite of positions, ensuring exact parity.  Any discrepancy flags a serialization bug or numeric mismatch.  
- **Performance checks:** Measure time to export (in Python) and load (in Java) for typical network sizes.  Ensure they meet performance criteria (e.g. sub-100ms load).  Compare loading from memory vs file I/O, perhaps use memory-mapped I/O for large nets.  
- **Regression tests:** For each new release, keep a baseline exported `.nnue` file and ensure the new loader reads it unchanged (bitwise parity after loading).  Also ensure new exported files produce the same outputs as before.  
- **Failure injection:** Feed malformed files to the loader to ensure it fails gracefully: e.g. wrong magic, truncated weights, invalid version.  Confirm no crashes or hangs, and that errors are reported properly.  

This test matrix covers determinism, correctness, performance, and security of the pipeline [First principles].  Automated continuous integration (CI) will include these checks.  

# 12. Performance Considerations

- **I/O Speed:**  Because the format is binary and tightly packed, file I/O is the primary cost.  Quantized int16 data is compact, so even large networks (millions of weights) load quickly.  We should use buffered/bulk reads (e.g. Java `DataInputStream`) or memory-mapping to minimize overhead.  No extra parsing (like JSON) means minimal CPU cost.  
- **Memory:** The loader should allocate exact arrays (based on dimensions).  If a network has extreme dimensions, we may impose sanity limits to avoid OOM.  
- **Export cost:** Writing the binary is linear in network size, negligible compared to training time.  Computing SHA-256 adds a small overhead but is usually <1ms for typical sizes.  
- **Quantization impact:** Using int16 rather than float32 roughly halves memory and I/O, and allows using SIMD in Java during evaluation.  We should benchmark inference to ensure int16 arithmetic is efficient.  
- **No premature optimization:** Focus first on correctness.  If needed, we can optimize by memory-mapping or parallel I/O, but only after profiling reveals hotspots.  

# 13. Security Considerations

- **Integrity check:** The embedded checksum (SHA-256) guards against bit-flips or tampering.  The loader must verify this before using weights.  
- **Bounds checking:** The loader must validate all header values.  For example, if the header claims `hidden_size=9999999`, the loader should detect an overflow or unreasonable size and abort.  All array allocations should be size-checked to prevent resource exhaustion or integer overflow.  
- **Input validation:** Never trust the file beyond what the header says.  For instance, if the checksum is valid but header claims inconsistent sizes, abort.  
- **No code execution:** The format is purely data; no executable content is embedded.  By using a binary format (no pickle, no script), we eliminate code injection risks.  
- **Library safety:** The loader uses only standard file I/O and crypto libraries, not any parsing of untrusted text.  Using SHA-256 avoids weaker CRCs which can be collisioned.  
- **Platform resilience:** Little-endian is assumed, but if running on a big-endian JVM (rare), the loader should either byte-swap or fail, rather than silently misinterpret data.  

# 14. Future Evolution

We anticipate future changes and plan accordingly:

- **Extending format:** If new fields are needed, we will bump `format_version`.  The loader will be updated to accept multiple versions.  For minor additions, we could structure the header as TLV blocks (like PNG chunks) so that unknown blocks can be skipped.  However, given Vex’s controlled pipeline, version bumps with clear specs should suffice.  
- **Architectural changes:** If we change the neural architecture (e.g. different feature set or layer sizes), the feature_spec_id and dimension fields will detect mismatches.  In a major redesign, we would likely create a new format version rather than trying to support the old one.  
- **Quantization schemes:** If in future we support different quantization (e.g. 8-bit instead of 16-bit), the `quantization_bits` field and version allow the loader to branch on different scaling logic.  We would document any such changes in ADRs.  
- **Multiple networks:** Currently one file = one network.  If we ever need multi-network (e.g. for evaluation of multiple players), it’s simpler to keep separate files.  We do **not** plan to support multiple nets in one file (that would complicate lookup).  
- **Tooling:** We may add CLI tools in Python/Java to inspect .nnue files (print metadata, test load).  These are convenience, not required by format.

Throughout evolution, any decision will be captured in Architecture Decision Records (ADRs).  For example, an ADR might formalize “file format versioning scheme” or “metadata in header vs sidecar”.  

# 15. Alternatives Rejected

- **Stockfish binary clone:** We could have matched Stockfish’s exact binary layout.  But Stockfish’s `.nnue` is designed for their codebase (Noda’s HalfKP/KA nets), and its header fields (especially the trick of padding the description to engineer a hash) are hacky.  Moreover, Stockfish changed its feature set once and did not attempt compatibility.  We need explicit versioning and full spec, so we reject blindly adopting their format [Context-dependent].  
- **ONNX:** Although ONNX is an open standard for models, it includes a full graph and metadata, which Vex does not need.  ONNX adds extra size and a dependency on protobuf/onnxruntime.  We reject it since our architecture is fixed and simpler (no branches, etc.) [Context-dependent].  
- **Generic serialization (JSON/Pickle):** Human-readable or Python-specific formats (JSON, Python pickle) are too slow or insecure (pickle is prone to code injection).  These are ruled out.  
- **FlatBuffers/Cap’n Proto:** While efficient, they impose a schema and runtime libraries in both Java and Python.  The complexity and one-time cost of defining and maintaining a schema outweigh the marginal performance benefit.  We do not see a compelling advantage to them [Context-dependent].  
- **Separate metadata file:** Using a JSON or XML manifest alongside weights (a “sidecar”) was considered.  However, losing sync between files is a common pitfall.  Thus we reject sidecars in favor of one-file design [First principles].  
- **HDF5 or database:** Some projects use HDF5 for tensor storage, but HDF5 is heavy, platform-specific, and not ideal for a lightweight chess engine.  Similarly, embedding a database (like SQLite) is overkill.  We do not pursue these.  

# 16. Risks

- **Version mismatch:** If the engine and trainer versions diverge (e.g. an older engine tries to load a newer .nnue), we risk silent failure.  *Mitigation:* Strict version checks and clear errors, plus testing across versions.  *Likelihood:* Medium (multiple builds), *Impact:* High (engine cannot run).  
- **Endianness bug:** Forgetting to use little-endian consistently could corrupt all data on one platform.  *Mitigation:* Always wrap I/O calls with a known-endian API and test on both little/big-endian Java VMs (rare). *Likelihood:* Low, *Impact:* Severe (subtle data errors).  
- **Header mis-parsing:** An offset error in header parsing would misalign all data.  *Mitigation:* Unit-test header parsing thoroughly, include sanity checks on sizes (e.g. file length vs expected).  *Likelihood:* Medium, *Impact:* Severe (wrong evaluation or crash).  
- **Checksum errors:** Implementation mistake in computing/verifying SHA may fail to detect corruption.  *Mitigation:* Independent test vectors (store a sample file and its expected SHA-256). *Likelihood:* Low, *Impact:* Medium (undetected corruption).  
- **Quantization inconsistencies:** If Python and Java quantizers disagree on representation (e.g. clamping, rounding), the loaded net may differ.  *Mitigation:* Use a single quantizer implementation (perhaps port core quant logic to Java or compare results). *Likelihood:* Medium, *Impact:* High (wrong evaluation).  
- **Data overflow:** Maliciously large dimension in header could cause OOM in loader.  *Mitigation:* Put a sanity limit on hidden_size, etc., and reject absurd values. *Likelihood:* Low (trustworthy data), *Impact:* Medium.  
- **Human error in format spec:** Miscommunication between writer and loader implementations.  *Mitigation:* Rely on thorough tests (parity, integration) and ideally generate serialization code from a single schema (ADR). *Likelihood:* Medium, *Impact:* High (bugs).  
- **Future decay:** The format might become rigid.  *Mitigation:* Design versioning now and document a clear migration path in ADRs. *Likelihood:* Medium, *Impact:* Medium.  

# 17. Open Questions

- **String encoding:** Should metadata use UTF-8 or fixed ASCII?  We assume UTF-8 for flexibility, but must specify no NULL bytes.  Java can handle UTF-8 natively.  
- **Padding/alignment:** Do we align data (e.g. pad to 4-byte boundaries) for any reason?  Given we control both sides, packing tight is fine.  
- **Compression:** Some systems compress large models.  For NNUE, networks are relatively small (< few MB), so we do not plan compression.  If needed later, we could consider zlib as an extra header flag.  
- **Multi-file vs single:** Is one format ever reused for multiple models (e.g. ensembles)?  Vex currently uses one network.  If ensemble needed, maybe publish multiple files.  
- **Provenance embedding:** Do we include Git commit or training seed?  We could add a UUID or hash of training config in metadata for full reproducibility.  
- **Testing framework:** How to integrate cross-language tests best?  Possibly use one reference `.nnue` in Git with expected SHA, or generate during CI.  
- **Partial load:** Could the engine lazily load parts of the network?  Unlikely needed; the net is accessed frequently.  We keep it simple (load all at startup).  
- **Distribution packaging:** If the engine bundles a default net, we should ensure the default’s magic/version matches the code.  Deployment scripts will check this.

These questions may be resolved by future ADRs (for example, “ADR: NNUE file compression” or “ADR: Network provenance record”).

# 18. References

- Stockfish forum (Lichess) discussion on net filenames and hash-based names.  
- Stockfish Q&A: NNUE compatibility (no backward/forward).  
- ONNX ecosystem and Hugging Face article on model formats.  
- Wikipedia: Endianness description.  
- SoftwareEngineering.SE: backward compatibility design, PNG chunk approach.  
- SoftwareEngineering.SE: metadata inside file vs sidecar.  
- CodeSignal Learn (tutorial): file checksum purpose and SHA-256 recommendation.  

# Recommendations for Vex

- **Custom single-file format:** Define a binary `.nnue` format with a fixed header, metadata block, and raw weights.  Include a magic number and version for format identification [First principles].  
- **Metadata in-file:** Embed all metadata (feature spec ID, network version, notes) inside the file header.  Do not use external manifests.  
- **Little-endian fixed layout:** Store all multi-byte values in little-endian order.  The loader and writer agree explicitly on this endianness.  
- **Explicit dimension fields:** Include input/hidden/output sizes in the header so the loader knows how many weights to read.  
- **SHA-256 checksum:** Append a SHA-256 of the preceding bytes.  Validate it on load to detect corruption.  
- **Versioned:** Use `format_version` and feature-spec IDs in the header.  On format changes, increment version and update parsing logic [First principles].  
- **Deterministic export:** Ensure the Python exporter writes data in a consistent order (e.g. iterate weights in a fixed loop).  Sort any maps/fields if necessary to eliminate nondeterminism.  
- **Strict validation on load:** The Java loader must check magic, version, feature ID, and checksum before using data.  Reject mismatches rather than guessing.  
- **No heavy dependencies:** Avoid ONNX/Protobuf/FlatBuffers.  A simple custom parser (DataInputStream) is sufficient.  
- **Tests at every step:** Implement unit tests for header parsing, round-trip tests, cross-language parity checks.  The loader should gracefully handle malformations.  
- **Embed [Known pattern]/[First principles] labels:** Document in code/comments why each design choice was made, e.g. “magic number identifies file type” or “metadata embedded to prevent desynchronization.”