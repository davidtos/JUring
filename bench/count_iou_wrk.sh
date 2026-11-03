#!/usr/bin/env bash
# count_iou_wrk.sh: Samples iou-wrk thread count and total CPU during a process run
set -euo pipefail
if [[ $# -lt 1 ]]; then echo "Usage: $0 <pid> [out_file]" >&2; exit 1; fi
PID="$1"; OUT_FILE="${2:-results/$(date +%F)/iou-wrk-buffered-write.json}"
mkdir -p "$(dirname "$OUT_FILE")"
SAMPLES=(); MAX_THREADS=0; SUM_THREADS=0; MAX_CPU=0; N=0
while kill -0 "$PID" 2>/dev/null; do
    LINES=$(ps -L -o tid,comm,%cpu -p "$PID" 2>/dev/null | grep 'iou-wrk' || true)
    if [[ -n "$LINES" ]]; then
        CNT=$(echo "$LINES" | grep -c 'iou-wrk' || echo 0)
        CPU=$(echo "$LINES" | awk '{sum+=$3} END{print sum+0}')
    else
        CNT=0; CPU=0.0
    fi
    if [[ $CNT -gt $MAX_THREADS ]]; then MAX_THREADS=$CNT; fi
    if awk "BEGIN {exit !($CPU > $MAX_CPU)}"; then MAX_CPU=$CPU; fi
    SUM_THREADS=$(( SUM_THREADS + CNT ))
    N=$(( N + 1 ))
    SAMPLES+=("{\"sample\": $N, \"threads\": $CNT, \"cpu_percent\": $CPU}")
    sleep 0.5 || break
done
AVG_THREADS=0
if [[ $N -gt 0 ]]; then AVG_THREADS=$(awk -v s="$SUM_THREADS" -v n="$N" 'BEGIN {printf "%.2f", s / n}'); fi
jq -n --argjson pid "$PID" --argjson samples_count "$N" --argjson max_threads "$MAX_THREADS" --argjson avg_threads "$AVG_THREADS" --argjson max_cpu "$MAX_CPU" --argjson samples "$(printf '%s\n' "${SAMPLES[@]:-}" | jq -s . 2>/dev/null || echo '[]')" '{target_pid: $pid, sample_count: $samples_count, max_iou_wrk_threads: $max_threads, avg_iou_wrk_threads: $avg_threads, max_cpu_percent: $max_cpu, samples: $samples}' > "$OUT_FILE"
echo "iou-wrk sampling complete ($N samples). Max threads: $MAX_THREADS, Max CPU: ${MAX_CPU}%. Logged to $OUT_FILE"
