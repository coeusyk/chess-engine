#!/bin/bash
# Usage: ./tools/sprt.sh <new-engine.jar> <old-engine.jar>
# Runs SPRT(elo0, elo1, alpha, beta) to validate whether the new engine
# is stronger than the old engine.
#
# SPRT Parameters (adjust these constants as needed):
#   ELO0  — null hypothesis Elo bound (default 0: no improvement)
#   ELO1  — alternative hypothesis Elo bound (default 50: meaningful gain)
#   ALPHA — false positive rate (default 0.05 = 5%)
#   BETA  — false negative rate (default 0.05 = 5%)
#
# Reading Results (from cutechess-cli output):
#   "H1 accepted" (LLR >= upper bound) — patch improves strength → merge
#   "H0 accepted" (LLR <= lower bound) — no improvement detected → investigate or discard
#   If the game limit is reached without a decision, result is inconclusive.

set -euo pipefail

# --- SPRT parameters (edit here) ---
ELO0=0
ELO1=50
ALPHA=0.05
BETA=0.05
MAX_GAMES=20000
TC="tc=10+0.1"

NEW=${1:?Usage: ./tools/sprt.sh <new-engine.jar> <old-engine.jar>}
OLD=${2:?Usage: ./tools/sprt.sh <new-engine.jar> <old-engine.jar>}

RESULTS_DIR="$(dirname "$0")/results"
mkdir -p "$RESULTS_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
PGN_OUT="$RESULTS_DIR/sprt_${TIMESTAMP}.pgn"

cutechess-cli \
  -engine name=Vex-new cmd="java -jar $NEW" proto=uci \
  -engine name=Vex-old cmd="java -jar $OLD" proto=uci \
  -each "$TC" \
  -games "$MAX_GAMES" \
  -repeat \
  -recover \
  -resign movecount=5 score=600 \
  -draw movenumber=40 movecount=8 score=10 \
  -sprt elo0="$ELO0" elo1="$ELO1" alpha="$ALPHA" beta="$BETA" \
  -concurrency 2 \
  -pgnout "$PGN_OUT"

echo "PGN saved to: $PGN_OUT"
