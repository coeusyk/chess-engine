# Phase 26. WSL2 qualification

### [2026-11-24] Conditional WSL2 use for Threads=1 SPRTs

**Built:** The qualification record now covers stages 3 and 4: a 1,000-game A/A and a 4,000-game fixed-count replication of the Phase 21 Stage 6 pair. CLAUDE.md allows Threads=1 SPRTs on the qualified WSL2 setup and relative NPS comparisons inside one WSL session. The throughput investigation points to the record and keeps its earlier text as history.

**Decisions Made:** The replication plan was hashed before any game was played, so the result could not shape the comparison. The absolute 301,116 floor, Lazy SMP and thread scaling, release comparisons and host-frozen preregistrations stay native only. The earlier condition that a WSL result does not decide a merge alone is kept, because the replication tells us the effect reproduced but cannot show equivalence. Running two matches at once stays disallowed, including a WSL job beside a native one.

**Broke / Fixed:** The first replication attempt was refused by the headroom gate, with host memory available at 5,620, 7,285 and 7,152 MiB against an 8 GiB minimum. It is archived. The retry used the unchanged protocol and passed at 8,462 MiB. The A/A also saw host available memory drop to 1,908 MiB while the VM kept more than 8 GiB and recorded no memory stalls.

**Measurements:** A/A 341 wins, 341 draws, 318 losses, score 51.15% (nominal 95% 48.64% to 53.66%), no forfeits or faults. Replication: WSL +10.77 Elo (+2.71 to +18.85), native matched pairs +13.64 (+5.33 to +21.98), native full +6.60 (+2.04 to +11.16), WSL minus native -2.87 (-14.46 to +8.72). Native intervals come from sequentially stopped matches, so all intervals are nominal.
