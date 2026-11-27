#!/usr/bin/env python3
"""Amendment 3: focused terminal-only allocation comparison for #307 (base against terminal, Classical and NNUE).

Reuses measure.py unchanged (run_bench, parse_jfr, ALLOC_FLAGS, the headroom gate and the pressure monitor). Each mode runs
the order base, terminal, terminal, base (one comparison, the second pair is a replicate of the JFR sampling).
Keys are alloc2:..., so the original alloc: records are untouched. Writes to results.jsonl and alloc2/.
"""
import re
import sys
from pathlib import Path

import measure as m

HERE = m.HERE
ORDER = [("base", 1), ("terminal", 1), ("terminal", 2), ("base", 2)]


def main():
    out = HERE / "alloc2"
    out.mkdir(exist_ok=True)
    (HERE / "raw").mkdir(exist_ok=True)
    pressure = m.Pressure()
    pressure.start()
    try:
        for mode in ("classical", "nnue"):
            m.headroom_gate(f"s307:{mode}:alloc2")
            for variant, rep in ORDER:
                key = f"alloc2:s307:{mode}:{variant}:{rep}"
                if key in m.DONE:
                    continue
                if m.foreign_load():
                    raise SystemExit("foreign load appeared: " + ",".join(m.foreign_load()))
                base = out / f"{mode}-{variant}-{rep}"
                flags = [*m.ALLOC_FLAGS, f"-XX:StartFlightRecording=filename={base}.jfr,settings=profile", f"-Xlog:gc:file={base}.gc.log"]
                rec = m.run_bench("s307", variant, mode, flags=flags, log=f"alloc2-{mode}-{variant}-{rep}.txt")
                gc = Path(str(base) + ".gc.log").read_text(errors="replace")
                young = len(re.findall(r"Pause Young", gc))
                total, top_class, top_site = m.parse_jfr(Path(str(base) + ".jfr"))
                m.record(key, suite="s307", mode=mode, variant=variant, rep=rep, nodes=rec["nodes"], nps_with_jfr=rec["nps"],
                         start_utc=rec["start_utc"], end_utc=rec["end_utc"], sampled_allocation_bytes=total,
                         young_collections=young, top_classes=top_class, top_sites=top_site, bytes_per_node=total / rec["nodes"])
    finally:
        pressure.close()


if __name__ == "__main__":
    main()
