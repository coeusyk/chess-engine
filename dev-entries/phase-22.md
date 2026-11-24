# Phase 22. API input correctness

### [2026-11-24] Invalid FEN responses

**Built:** The board parser reports unknown piece characters and malformed FEN fields as invalid input. The legacy game load, game API, evaluation and SSE endpoints return a JSON HTTP 400 for that error.

**Decisions Made:** Map the specific parsing exception in the existing controller advice. Unrelated argument errors reach normal server exception handling.

**Broke / Fixed:** An unknown piece character failed during integer unboxing, and analysis treated an internal argument error as bad input. The tests-only CI run reproduced both behaviors and passed all five Perft cases.

### [2026-11-24] Remaining chess-engine-api defects from #276

**Built:** Move and setup responses carry a detached copy of the board taken under the session lock. The legacy load and new-game endpoints clear the redo stack, and make-move accepts an optional promotion letter. The session store is capped at 1,000 entries and drops the least recently used one, never the default session. Analysis takes ownership of the active slot atomically, releases it when an evaluate call finishes, reports a superseded evaluation as HTTP 409 instead of a truncated 200, serializes the final SSE send against the timeout thread, and stops its daemon executor on shutdown. FEN parsing requires eight ranks of eight squares and an en-passant square on the board. The API refuses positions without exactly one king per side. The API module now builds an executable JAR and the Dockerfile builds it from the repository root on Java 21.

**Decisions Made:** The snapshot replays the move history onto a fresh board so every serialized field matches the live board and the JSON contract does not change. The king check lives in the API layer because engine-core tests and tools build partial positions on purpose. The FEN shape and en-passant bounds sit in the parser because any valid FEN already satisfies them. The en-passant rank is only bounded to 1 to 8, not restricted to 3 and 6, to avoid rejecting input the parser used to take. Eviction is by recency and not by idle time, which keeps the store free of timers. A session dropped while a request holds its lock finishes that request on the detached session, and later requests get the existing not-found response. The legacy endpoints create a session for any id, so a dropped id comes back there as a fresh game, and enough distinct ids can push other sessions out. There is no per-client quota. A promotion letter on a move that is not a promotion is ignored, so a client that always sends one keeps working. A superseded SSE stream is now completed with no terminal event instead of staying open until the emitter timeout. A finished evaluation that a newer request supersedes in the instant before the abort check is reported as 409, which is accepted because the request was superseded.

**Broke / Fixed:** A rank that was too short or too long slid pieces onto the wrong squares, and an overlong one wrapped the bit shift past square 63. An en-passant square such as a0 or i3 produced an out-of-range index. The two lifecycle fixes with no deterministic test are the SSE completion race and the timeout-thread interleaving. They are covered by review of the synchronization, not by a test.

**Measurements:** 42 targeted API tests and 11 engine-core FEN tests pass locally. Perft and the full suites run in CI, because a native SPRT is using the host.
