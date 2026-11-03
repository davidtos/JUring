#!/usr/bin/env bash
# precondition.sh: fio randwrite 4 KiB QD=32 on the target file until time runs out.
set -euo pipefail
if [[ $# -lt 2 ]]; then echo "Usage: $0 <target_file> <minutes> [results_dir]" >&2; exit 1; fi
TARGET_FILE="$1"; MINUTES="$2"; RESULTS_DIR="${3:-results/$(date +%F)}"
mkdir -p "$RESULTS_DIR"
if [[ ! -f "$TARGET_FILE" ]]; then echo "Error: target file $TARGET_FILE does not exist" >&2; exit 1; fi
FIO_BIN="${FIO_BIN:-fio}"
if ! command -v "$FIO_BIN" &>/dev/null; then echo "Error: fio ($FIO_BIN) not found in PATH" >&2; exit 1; fi
TOTAL_SECS=$(awk -v m="$MINUTES" 'BEGIN {print int(m * 60)}')
WIN_SECS=30
if [[ "$TOTAL_SECS" -lt 30 ]]; then WIN_SECS="$TOTAL_SECS"; if [[ "$WIN_SECS" -lt 1 ]]; then WIN_SECS=1; fi; fi
NUM_WINDOWS=$(( TOTAL_SECS / WIN_SECS ))
if [[ "$NUM_WINDOWS" -lt 1 ]]; then NUM_WINDOWS=1; fi
BASE_NAME=$(basename "$TARGET_FILE" .bin)
OUT_JSON="$RESULTS_DIR/precondition-${BASE_NAME}.json"
WINDOWS_DATA=()
START_TIME=$(date +%s)
for ((w = 1; w <= NUM_WINDOWS; w++)); do
    TMP_OUT=$(mktemp --suffix=.json)
    STATUS=0
    "$FIO_BIN" --name=precond --filename="$TARGET_FILE" --ioengine=io_uring --direct=1 --bs=4k --rw=randwrite --iodepth=32 --norandommap --randrepeat=0 --time_based --runtime="${WIN_SECS}s" --output-format=json --output="$TMP_OUT" || STATUS=$?
    if [[ $STATUS -ne 0 ]]; then echo "Error: fio exited with code $STATUS during window $w" >&2; rm -f "$TMP_OUT"; exit $STATUS; fi
    IOPS=$(jq -r '.jobs[0].write.iops // 0' "$TMP_OUT" 2>/dev/null || echo 0)
    rm -f "$TMP_OUT"
    ELAPSED=$(( $(date +%s) - START_TIME ))
    echo " [precondition] Window $w/$NUM_WINDOWS (${ELAPSED}s elapsed): $IOPS IOPS"
    WINDOWS_DATA+=("{\"window\": $w, \"elapsed_s\": $ELAPSED, \"iops\": $IOPS}")
done
jq -n --arg file "$TARGET_FILE" --argjson minutes "$MINUTES" --argjson total_s "$TOTAL_SECS" --argjson windows "$(printf '%s\n' "${WINDOWS_DATA[@]}" | jq -s .)" '{target_file: $file, target_minutes: $minutes, elapsed_seconds: $total_s, qd: 32, block_size: "4k", windows: $windows}' > "$OUT_JSON"
echo "Preconditioning complete. Logged $NUM_WINDOWS windows to $OUT_JSON"
