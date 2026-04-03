#!/bin/bash
# Usage: ./tools/match.sh <engine1.jar> <engine2.jar> [games] [tc]
# Example: ./tools/match.sh engine-v1.jar engine-v2.jar 100 "tc=10+0.1"

set -euo pipefail

ENGINE1=${1:?Usage: ./tools/match.sh <engine1.jar> <engine2.jar> [games] [tc]}
ENGINE2=${2:?Usage: ./tools/match.sh <engine1.jar> <engine2.jar> [games] [tc]}
GAMES=${3:-100}
TC=${4:-"tc=10+0.1"}

RESULTS_DIR="$(dirname "$0")/results"
mkdir -p "$RESULTS_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PGN_OUT="$RESULTS_DIR/match_${TIMESTAMP}.pgn"

cutechess-cli \
  -engine name=Vex-new cmd="java -jar \"$ENGINE1\"" proto=uci \
  -engine name=Vex-old cmd="java -jar \"$ENGINE2\"" proto=uci \
  -each "$TC" \
  -games "$GAMES" \
  -repeat \
  -recover \
  -resign movecount=5 score=600 \
  -draw movenumber=40 movecount=8 score=10 \
  -pgnout "$PGN_OUT" \
  -concurrency 2

echo "PGN saved to: $PGN_OUT"
