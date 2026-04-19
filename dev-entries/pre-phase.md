# Dev Entries - Pre-Phase

### [2026-03-22] Pre-Phase 0 — UI Stabilization and UX Controls

**Built:**

- Fixed board flip behavior by switching from DOM child reordering to class-based board rotation.
- Implemented responsive layout pass and then partially rolled it back to preserve fixed 85x85 board squares.
- Restored board dimensions to 680x680 with 85x85 squares while keeping centered layout behavior.
- Added New Game control in UI sidebar and wired it to backend reset flow.
- Added capture-target overlay behavior and replaced capture ring SVG with a clearer chess-site style marker.
- Updated asset loading for capture overlay by importing the SVG in React instead of using a brittle relative runtime string.

**Decisions Made:**

- Board squares remain fixed-size for desktop consistency while other layout elements can adapt.
- New Game action should call explicit backend intent endpoint rather than relying only on loading a starting FEN payload.
- Capture indicators should be visually distinct overlays layered above occupied target pieces.

**Broke / Fixed:**

- Piece sizing regressed after responsive CSS changes and SVG source assets changed to hardcoded 45x45 dimensions.
- Multiple sizing strategies were tested; final approach uses centered absolute positioning plus scale transform to get consistent visual footprint.
- Capture overlay path resolution was unreliable under bundling; fixed by importing the SVG asset in component code.

**Measurements:**

- UI build: passed after each stabilization step.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Replace remaining direct DOM event listener patterns with pure React state-driven interactions.
- Tune piece scale and capture ring opacity only after backend phase tasks stabilize.

### [2026-03-22] Pre-Phase 0 — Backend Runtime Stability Fixes

**Built:**

- Fixed request-time crashes involving ConcurrentModificationException and illegal unmake state.
- Removed shared static move list behavior in move generation by making move storage instance-scoped.
- Added synchronization around board and move-generator access paths in REST controller logic.
- Added defensive move-generator initialization guards for endpoint call order tolerance.

**Decisions Made:**

- Correctness and request safety take priority over throughput in current architecture stage.
- Session-safe structure is required before search-strength work proceeds.

**Broke / Fixed:**

- Crash root cause: move list mutation and board mutation while request flows interleaved.
- Secondary failure: unmake invoked in corrupted move-state path.
- Fix strategy: isolate generator state per instance and serialize critical board mutation regions.

**Measurements:**

- Exception reproduction: resolved for reported traces after patching.
- Perft depth 5 (startpos): Not measured in this cycle.
- Nodes/sec: Not measured in this cycle.
- Elo vs. baseline: Not measured in this cycle.

**Next:**

- Replace coarse endpoint synchronization with per-game session state and thinner service boundaries.

