# Phase 23. Opening-book correctness

## 2026-11-24. Decode Polyglot castling and release extracted books

**Built:** Matched Polyglot king-to-rook castling entries against generated legal castle moves. The UCI move retains the actual king destination.

**Decisions Made:** Keep ordinary book moves on the existing matching path. The book owns classpath extracts through close and replacement. Caller-owned files remain on disk.

**Broke / Fixed:** Four castling encodings returned no move. Failed opens and book replacement left temporary files behind until JVM exit. The loader now closes the resource stream and deletes owned paths after the file handle closes.

**Measurements:** The tests-only CI run reproduced all four missing moves and both extraction leaks. The test set also covers the ordinary-rook control and missing castling rights.
