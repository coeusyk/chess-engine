"""Freeze the opening lists for the v0.5.7 vs v0.6.0 release SPRT and its smoke test.

Every line of the archived Phase 21 opening book is ranked by sha256(SALT + line). The production list is the
COUNT lowest ranks in rank order. The smoke-test list is the next SMOKE_COUNT ranks, so the two never share a
position. The result does not depend on the Python version, a random-number generator or the order of the source.

    python tools/release-sprt-openings.py            # write both frozen files
    python tools/release-sprt-openings.py --check    # verify both committed files reproduce
"""
import argparse
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "tools/results/phase21-stage6/20261011T061318503Z-b5a507c6/noob_3moves.epd"
SOURCE_SHA256 = "2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347"
RESEARCH = ROOT / "docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0"
OUTPUT = RESEARCH / "openings-v1.epd"
SMOKE_OUTPUT = RESEARCH / "openings-smoke-v1.epd"
SALT = b"vex-release-sprt-v0.5.7-v0.6.0-openings-v1\n"
COUNT = 10000
SMOKE_COUNT = 10


def build(source_bytes):
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise SystemExit("source opening book does not match the archived SHA-256")
    lines = source_bytes.decode("ascii").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if len(set(lines)) != len(lines):
        raise SystemExit("source opening book contains duplicate lines")
    key = lambda line: " ".join(line.split()[:4])
    if len({key(line) for line in lines}) != len(lines):
        raise SystemExit("source opening book has positions that differ only in move counters")
    ranked = sorted(lines, key=lambda line: hashlib.sha256(SALT + line.encode("ascii")).digest())
    encode = lambda part: ("\n".join(part) + "\n").encode("ascii")
    return encode(ranked[:COUNT]), encode(ranked[COUNT:COUNT + SMOKE_COUNT])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for path, data, count in zip((OUTPUT, SMOKE_OUTPUT), build(SOURCE.read_bytes()), (COUNT, SMOKE_COUNT)):
        digest = hashlib.sha256(data).hexdigest()
        if args.check:
            if path.read_bytes() != data:
                sys.exit(f"committed {path.name} differs from the regenerated list")
            print(f"OK {path.name}: {count} openings, sha256 {digest}")
        else:
            path.write_bytes(data)
            print(f"wrote {path.relative_to(ROOT)}: {count} openings, sha256 {digest}")


if __name__ == "__main__":
    main()
