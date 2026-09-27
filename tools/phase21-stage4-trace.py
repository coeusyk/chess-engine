#!/usr/bin/env python3
"""Produce cold depth-14/15 traces for Phase 21 depth-13 move differences."""

import argparse
import csv
import hashlib
import pathlib
import subprocess
import sys
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
CONTROL_SHA256 = "cdf7fe59b773d55f4a79a59ccccd9b1b6d804f82332b6200cc88b9e65fa5e95b"
CANDIDATE_SHA256 = "4d1325c4bc3e1a4d312870b21ff53716e684a3f16637b7df1800d72341291da4"
RESULT_FIELDS = ("move", "score", "nodes", "qnodes", "tt_hits", "pv")
HEADER = (
    "index", "depth",
    "control_move", "control_score", "control_nodes", "control_qnodes", "control_tt_hits", "control_pv",
    "candidate_move", "candidate_score", "candidate_nodes", "candidate_qnodes", "candidate_tt_hits", "candidate_pv",
    "d13_source",
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def launch(command):
    return subprocess.run(command, cwd=ROOT, check=True, text=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--control-jar", type=pathlib.Path, required=True)
    parser.add_argument("--candidate-jar", type=pathlib.Path, required=True)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    control_jar = args.control_jar.resolve()
    candidate_jar = args.candidate_jar.resolve()
    if sha256(control_jar) != CONTROL_SHA256:
        raise SystemExit("control JAR SHA-256 does not match the frozen Phase 21 control")
    if sha256(candidate_jar) != CANDIDATE_SHA256:
        raise SystemExit("candidate JAR SHA-256 does not match the Stage 3 candidate")

    stage3_path = ROOT / "docs/architecture/research/phase21-stage3-results.tsv"
    with stage3_path.open(newline="") as source:
        stage3 = list(csv.DictReader(source, delimiter="\t"))
    d13 = {int(row["index"]): row for row in stage3 if row["depth"] == "13"}
    if len(d13) != 31:
        raise SystemExit(f"expected 31 frozen depth-13 rows, found {len(d13)}")
    changed = [index for index, row in sorted(d13.items()) if row["control_move"] != row["candidate_move"]]
    if len(changed) != 8:
        raise SystemExit(f"expected 8 frozen depth-13 move differences, found {len(changed)}")

    with tempfile.TemporaryDirectory(prefix="phase21-stage4-") as classes:
        launch([
            "rtk", "proxy", "javac", "--add-modules", "jdk.incubator.vector",
            "-cp", str(candidate_jar), "-d", classes,
            str(ROOT / "tools/Phase21DecisionTrace.java"),
        ])
        if args.validate_only:
            validated = 0
            for index in changed:
                for name, jar in (("control", control_jar), ("candidate", candidate_jar)):
                    sys.stderr.write(f"validating frozen {name} PV index={index} depth=13\n")
                    completed = launch([
                        "rtk", "proxy", "java", "--add-modules", "jdk.incubator.vector",
                        "-cp", str(pathlib.Path(classes)) + ":" + str(jar),
                        "Phase21DecisionTrace", "validate-pv", str(index), d13[index][f"{name}_pv"],
                    ])
                    if len(completed.stdout.splitlines()) != 1:
                        raise SystemExit(f"malformed PV validation for {name} index={index}")
                    validated += 1
            print(f"validated {validated} frozen depth-13 PVs without searching")
            return

        writer = csv.writer(sys.stdout, delimiter="\t", lineterminator="\n")
        writer.writerow(HEADER)
        for index in changed:
            frozen = d13[index]
            for depth in (13, 14, 15):
                if depth == 13:
                    control = tuple(frozen[f"control_{field}"] for field in RESULT_FIELDS)
                    candidate = tuple(frozen[f"candidate_{field}"] for field in RESULT_FIELDS)
                    source = "frozen-stage3"
                else:
                    results = []
                    for name, jar in (("control", control_jar), ("candidate", candidate_jar)):
                        sys.stderr.write(f"running {name} index={index} depth={depth}\n")
                        classpath = str(pathlib.Path(classes)) + ":" + str(jar)
                        completed = launch([
                            "rtk", "proxy", "java", "-Xms512m", "-Xmx512m", "-XX:+UseG1GC",
                            "--add-modules", "jdk.incubator.vector",
                            "-Dlogback.configurationFile=tools/phase21-logback.xml",
                            "-cp", classpath, "Phase21DecisionTrace", str(index), str(depth),
                        ])
                        lines = [line for line in completed.stdout.splitlines() if line.strip()]
                        if len(lines) != 1:
                            raise SystemExit(f"expected one result row for {name} index={index} depth={depth}")
                        fields = lines[0].split("\t")
                        if fields[:2] != [str(index), str(depth)] or len(fields) != 8:
                            raise SystemExit(f"malformed result for {name} index={index} depth={depth}: {lines[0]}")
                        results.append(tuple(fields[2:]))
                    control, candidate = results
                    source = "fresh-cold-search"
                writer.writerow((index, depth, *control, *candidate, source))


if __name__ == "__main__":
    main()
