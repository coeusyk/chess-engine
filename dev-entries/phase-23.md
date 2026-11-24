# Phase 23. Opening-book correctness

## 2026-11-24. Decode Polyglot castling and release extracted books

**Built:** Matched Polyglot king-to-rook castling entries against generated legal castle moves. The UCI move retains the actual king destination.

**Decisions Made:** Keep ordinary book moves on the existing matching path. The book owns classpath extracts through close and replacement. Caller-owned files remain on disk.

**Broke / Fixed:** Four castling encodings returned no move. Failed opens and book replacement left temporary files behind until JVM exit. The loader now closes the resource stream and deletes owned paths after the file handle closes.

**Measurements:** The tests-only CI run reproduced all four missing moves and both extraction leaks. The test set also covers the ordinary-rook control and missing castling rights.

### [2026-11-24] Online Syzygy prober from #279

**Built:** The online prober calls the tablebase over HTTPS and follows redirects, with downgrades to plain HTTP refused. UciApplication keeps one prober for the session, so the position cache and the HTTP client persist across `go` commands. The prober is closed on quit, on end of input, and when `SyzygyOnline` is switched off. A new default method on the prober interface, `allowsInteriorProbing`, lets a blocking prober say it may only be asked at the root. The online prober returns false, and the search skips its interior WDL probe for such probers.

**Decisions Made:** The root DTZ probe stays, because it is what turns a tablebase hit into the chosen move and its score. The interior probe is skipped through the interface and not by checking for the online class, so a future local reader keeps interior probing by default. A change of `Syzygy50MoveRule` builds a new prober, since the flag is fixed at construction. A failed request is remembered for 30 s, because the root is probed once per iterative-deepening iteration and an unreachable server would otherwise cost one timeout per depth. The memory expires so a short outage does not disable a position for the whole session. Closing the prober on `setoption SyzygyOnline false` waits for the running search first, as Hash does, because the search worker creates and uses it.

**Broke / Fixed:** The old endpoint was plain HTTP with redirects disabled, so any redirect to HTTPS returned an invalid probe. Every `go` built a new client and dropped the cache. The search could wait up to 5 s on a network request inside an interior node.

**Measurements:** 6 prober tests against a local HTTP server, including a failing server probed five times with one request seen and a second request after the memory expires, 3 search tests with a counting prober, and the existing book and endgame tests pass locally. The search tests show zero interior probes and at least one root probe for a blocking prober, interior probes for a safe one, and a root answer chosen as the move. No node-rate comparison was run, since a native SPRT occupies the host. With the online prober the interior path is now skipped entirely, so there is no network latency left to measure there.
