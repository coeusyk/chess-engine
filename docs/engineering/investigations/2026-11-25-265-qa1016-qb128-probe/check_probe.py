"""Checks a replay of the #265 probe against the recorded results, and exits non-zero on any difference.

    python check_probe.py JAVA_OUTPUT_FILE [PROBE_OUT_DIR]

JAVA_OUTPUT_FILE is the captured output of the Java half (surefire console output or its -output.txt file). Every
line containing "PROBE " must equal, field by field, the matching line in results.txt. That covers the loaded
scale, positions, javaVsPythonMismatches, vectorDotVsScalarMismatches, the accumulator range, the fuzz check count
and the incremental divergence counts. When PROBE_OUT_DIR is given, the SHA-256 of both .scores.tsv files must
match the hashes in results.txt as well. The exported .nnue files are not compared: their header holds the
export time, so their bytes differ on every run while the scores stay identical.
"""
import hashlib
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def probe_lines(text):
    lines = []
    for raw in text.splitlines():
        if "PROBE " in raw:
            lines.append(raw[raw.index("PROBE "):].strip())
    return lines


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: python check_probe.py JAVA_OUTPUT_FILE [PROBE_OUT_DIR]")
    expected = probe_lines((HERE / "results.txt").read_text(encoding="utf-8"))
    if len(expected) != 7:
        sys.exit(f"results.txt should hold 7 PROBE lines, found {len(expected)}")
    java_file = Path(sys.argv[1])
    if not java_file.is_file():
        sys.exit(f"Java output not found: {java_file}")
    actual = probe_lines(java_file.read_text(encoding="utf-8", errors="replace"))
    problems = []
    if len(actual) != len(expected):
        problems.append(f"expected {len(expected)} PROBE lines, found {len(actual)}")
    for i, (want, got) in enumerate(zip(expected, actual), start=1):
        if want != got:
            problems.append(f"PROBE line {i} differs:\n  recorded: {want}\n  replayed: {got}")
    if len(sys.argv) == 3:
        recorded = dict(re.findall(r"^\s+([0-9a-f]{64})\s+(\S+\.scores\.tsv)$", (HERE / "results.txt").read_text(), re.M))
        recorded = {name: digest for digest, name in recorded.items()}
        if len(recorded) != 2:
            problems.append("results.txt should record 2 score-file hashes")
        for name, digest in recorded.items():
            path = Path(sys.argv[2]) / name
            if not path.is_file():
                problems.append(f"missing {path}")
            elif hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                problems.append(f"{name}: sha256 differs from the recorded {digest}")
    if problems:
        sys.exit("REPLAY DIFFERS FROM results.txt\n" + "\n".join(problems))
    print(f"replay matches results.txt ({len(expected)} PROBE lines" + (", 2 score hashes)" if len(sys.argv) == 3 else ")"))


if __name__ == "__main__":
    main()
