# Phase 28. Search overhead

### [2026-11-24] Allocation and timed-search overhead candidates (#270, #271)

**Built:** Four localized changes. `evaluateTerminal` returns mate or stalemate from the check state instead of building a `MovesGenerator` through `Board.isCheckmate()`. `evaluator.onMake` now runs after the losing-capture and futility pruning decisions, so a pruned move costs no accumulator update. The hard-stop supplier is polled once per 1,024 interior and quiescence nodes, and still at every root move. `AnalysisService` and `GameLoop` search in search mode, with `GameLoop` restoring normal mode in a `finally` before the game move is made.

**Decisions Made:** Each of the four callers of `evaluateTerminal` has just generated the legal moves and found none, so the check state alone separates mate from stalemate. The pruning decisions read the board only, so moving `onMake` after them changes no result. The per-go `Searcher` pooling, the book probe before helper threads and the Helper 2 start depth from #270 are not in this change: they alter lifecycle or Lazy SMP behaviour and need their own measurement. The bitboard `givesCheck` of #271 step B is also left out.

**Broke / Fixed:** My first throughput test bounded polls at a hundredth of the nodes and failed at 11,588 nodes, because the root loop legitimately polls at every root move. The bound is a tenth, against about one poll per node before.

**Measurements:** Not measured. Exact depth-8 node counts (`NodeCountRegressionTest`), the NNUE-mode regression (34 cases), self-play and adapter tests and the API analysis tests pass unchanged, which is the evidence that search results are the same. No NPS, timed-search or allocation measurement was run, because a native SPRT occupies the host. The issues' measurement requirements (bench-raw 13 before and after, a timed movetime benchmark, allocation profile) are still open.
