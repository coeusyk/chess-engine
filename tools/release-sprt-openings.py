"""Freeze the opening list for the v0.5.7 vs v0.6.0 release SPRT.

Selects COUNT positions from the archived Phase 21 opening book by ranking each line on
sha256(SALT + line) and keeping the smallest keys, so the result does not depend on the
Python version or on the order of the source file. Output order is the rank order.

    python tools/release-sprt-openings.py            # write the frozen file
    python tools/release-sprt-openings.py --check    # verify the committed file reproduces
"""
import argparse
import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "tools/results/phase21-stage6/20261011T061318503Z-b5a507c6/noob_3moves.epd"
SOURCE_SHA256 = "2011193b4854e9a8cfdc05312ca2dbaffa6ceae3abbdee20e2ead2a18a603347"
OUTPUT = ROOT / "docs/architecture/research/release-strength-sprt-v0.5.7-v0.6.0/openings-v1.epd"
SALT = b"vex-release-sprt-v0.5.7-v0.6.0-openings-v1\n"
COUNT = 10000


def build(source_bytes):
    if hashlib.sha256(source_bytes).hexdigest() != SOURCE_SHA256:
        raise SystemExit("source opening book does not match the archived SHA-256")
    lines = source_bytes.decode("ascii").split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    if len(set(lines)) != len(lines):
        raise SystemExit("source opening book contains duplicate lines")
    ranked = sorted(lines, key=lambda line: hashlib.sha256(SALT + line.encode("ascii")).digest())
    return ("\n".join(ranked[:COUNT]) + "\n").encode("ascii")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data = build(SOURCE.read_bytes())
    digest = hashlib.sha256(data).hexdigest()
    if args.check:
        if OUTPUT.read_bytes() != data:
            sys.exit("committed opening list differs from the regenerated list")
        print(f"OK {COUNT} openings, sha256 {digest}")
    else:
        OUTPUT.write_bytes(data)
        print(f"wrote {OUTPUT.relative_to(ROOT)}: {COUNT} openings, sha256 {digest}")


if __name__ == "__main__":
    main()
