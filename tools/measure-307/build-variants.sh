#!/usr/bin/env bash
# Builds the isolated #307 measurement variants from the pinned develop base.
# Each variant is a detached worktree of BASE with a subset of the patches applied, built to its own fat JAR.
# Usage: tools/measure-307/build-variants.sh <output-dir> [variant ...]   (default: all variants)
# Run from the repository root. Needs git, mvn and a JDK 21 on PATH. Nothing here runs the engine.
set -euo pipefail

BASE=92aedb8   # develop at the merge of #306; the patches were generated and checked against it
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:?output directory}
shift || true

declare -A PATCHES=(
  [base]=""
  [terminal]="01-evaluate-terminal"
  [onmake]="02-onmake-deferral"
  [poll]="03-stop-polling"
  [searchmode]="04-search-mode"
  [all]="01-evaluate-terminal 02-onmake-deferral 03-stop-polling 04-search-mode"
  [all-no-poll]="01-evaluate-terminal 02-onmake-deferral 04-search-mode"
)
ORDER=(base terminal onmake poll searchmode all all-no-poll)
VARIANTS=("${@:-${ORDER[@]}}")

mkdir -p "$OUT"
: > "$OUT/SHA256SUMS"
for v in "${VARIANTS[@]}"; do
  [[ -v PATCHES[$v] ]] || { echo "unknown variant: $v" >&2; exit 2; }
  tree="$OUT/src-$v"
  git worktree add -q --detach "$tree" "$BASE"
  for p in ${PATCHES[$v]}; do
    git -C "$tree" apply "$HERE/patches/$p.patch"
  done
  (cd "$tree" && mvn -B -q -pl engine-core,engine-uci -am package -DskipTests)
  jar=$(ls "$tree"/engine-uci/target/engine-uci-*-SNAPSHOT.jar | grep -v original | head -1)
  cp "$jar" "$OUT/$v.jar"
  (cd "$OUT" && sha256sum "$v.jar" >> SHA256SUMS)
  git worktree remove --force "$tree"
  echo "built $v"
done
cat "$OUT/SHA256SUMS"
