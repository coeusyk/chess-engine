# Phase 25 trainer contracts

### [2026-11-24] Shard and terminal label regression tests

**Built:** Added the exact 100-byte VSPR fixture to the Trainer CI suite. The test ingests it into a v2 shard and reads it back through the shard reader. The Stage 2 test follows a terminal `score mate 0` from the stub UCI engine through the stored label.

**Decisions Made:** New shards use v2 and readers continue to accept v1. An exact `score mate 0` is a valid label for a position where the side to move is already checkmated.

**Measurements:** The two regressions and the 20 existing shard tests passed locally. A 90-byte field limit and a parser that discarded mate zero each failed their corresponding regression.
