#!/usr/bin/env bash
# bench/fio-compare-table.sh: Generates a summary comparison table from JMH and fio JSON results.
set -euo pipefail

DIR="${1:-}"
if [[ -z "$DIR" ]]; then
    DIR=$(ls -td results/* 2>/dev/null | head -n 1 || true)
fi

if [[ -z "$DIR" || ! -d "$DIR" ]]; then
    echo "Usage: $0 <results-directory> [read|write]" >&2
    exit 1
fi

MODE_FILTER="${2:-read}"

# Find JMH files
JMH_FILES=($(ls -v "$DIR"/jmh-"$MODE_FILTER"-*.json 2>/dev/null || true))
if [[ ${#JMH_FILES[@]} -eq 0 ]]; then
    JMH_FILES=($(ls -v "$DIR"/jmh-*.json 2>/dev/null || true))
fi

# Find fio default files
FIO_DEF_FILES=($(ls -v "$DIR"/fio-default-"$MODE_FILTER"-*.json 2>/dev/null || true))
if [[ ${#FIO_DEF_FILES[@]} -eq 0 ]]; then
    # Fallback to standard fio files
    FIO_DEF_FILES=($(ls -v "$DIR"/fio-"$MODE_FILTER"-*.json 2>/dev/null || true))
fi

# Find fio best files
FIO_BEST_FILES=($(ls -v "$DIR"/fio-best-"$MODE_FILTER"-*.json 2>/dev/null || true))

if [[ ${#JMH_FILES[@]} -eq 0 ]]; then
    echo "Error: No JMH result files found in $DIR" >&2
    exit 1
fi

if [[ ${#FIO_DEF_FILES[@]} -eq 0 && ${#FIO_BEST_FILES[@]} -eq 0 ]]; then
    echo "Error: No fio result files found in $DIR" >&2
    exit 1
fi

BEST_B="unknown"
if [[ -f "$DIR/fio-best-$MODE_FILTER.txt" ]]; then
    BEST_B=$(cat "$DIR/fio-best-$MODE_FILTER.txt" | tr -d '[:space:]')
fi

# Enters/op data
FIO_DEF_ENTERS="—"
FIO_BEST_ENTERS="—"
JMH_ENTERS="—"
if [[ -f "$DIR/fio-enters-$MODE_FILTER.json" ]]; then
    FIO_DEF_ENTERS=$(jq -r '.fio_default_enters_op // "—"' "$DIR/fio-enters-$MODE_FILTER.json")
    FIO_BEST_ENTERS=$(jq -r '.fio_best_enters_op // "—"' "$DIR/fio-enters-$MODE_FILTER.json")
    JMH_ENTERS=$(jq -r '.juring_enters_op // "—"' "$DIR/fio-enters-$MODE_FILTER.json")
fi

# Helper to compute mean and stddev using jq
compute_stats() {
    local files=("$@")
    if [[ ${#files[@]} -eq 0 ]]; then
        echo "0 0 0 0"
        return
    fi
    jq -s '
      def mean: if length == 0 then 0 else (add / length) end;
      def stddev:
        if length <= 1 then 0
        else
          mean as $m |
          (map((. - $m) * (. - $m)) | add / (length - 1)) | sqrt
        end;

      [ .[] | 
        if type == "array" then 
          .[0].primaryMetric.score
        elif type == "object" and .jobs then
          (if ((.jobs[0].write.iops // 0) > 0) then .jobs[0].write.iops else .jobs[0].read.iops end)
        else empty end
      ] as $iops |

      [ .[] | 
        if type == "array" then 
          ((.[0].secondaryMetrics.bytesProcessed.score // (.[0].primaryMetric.score * 4096.0)) / 1048576.0)
        elif type == "object" and .jobs then
          ((if ((.jobs[0].write.bw_bytes // 0) > 0) then .jobs[0].write.bw_bytes else .jobs[0].read.bw_bytes end) / 1048576.0)
        else empty end
      ] as $bw |

      "\($iops | mean) \($iops | stddev) \($bw | mean) \($bw | stddev)"
    ' "${files[@]}" | tr -d '"'
}

read -r J_IOPS_MEAN J_IOPS_STD J_BW_MEAN J_BW_STD < <(compute_stats "${JMH_FILES[@]}")
read -r F_DEF_IOPS_MEAN F_DEF_IOPS_STD F_DEF_BW_MEAN F_DEF_BW_STD < <(compute_stats "${FIO_DEF_FILES[@]}")

if [[ ${#FIO_BEST_FILES[@]} -gt 0 ]]; then
    read -r F_BEST_IOPS_MEAN F_BEST_IOPS_STD F_BEST_BW_MEAN F_BEST_BW_STD < <(compute_stats "${FIO_BEST_FILES[@]}")
else
    F_BEST_IOPS_MEAN=$F_DEF_IOPS_MEAN
    F_BEST_IOPS_STD=$F_DEF_IOPS_STD
    F_BEST_BW_MEAN=$F_DEF_BW_MEAN
    F_BEST_BW_STD=$F_DEF_BW_STD
fi

# Compare against fio best
DELTA="N/A"
if awk "BEGIN {exit !($F_BEST_IOPS_MEAN > 0)}"; then
    DELTA=$(awk -v j="$J_IOPS_MEAN" -v f="$F_BEST_IOPS_MEAN" 'BEGIN {printf "%+.1f%%", ((j / f) - 1.0) * 100.0}')
fi

echo "========================================================================================================================"
echo "                             JUring vs Native C fio Head-to-Head Workload Comparison                                    "
echo "                                           (± = stddev over N repeats)                                                  "
echo "========================================================================================================================"
printf " Results Directory: %s (%d JMH runs, %d fio default runs, %d fio best runs)\n" \
    "$DIR" "${#JMH_FILES[@]}" "${#FIO_DEF_FILES[@]}" "${#FIO_BEST_FILES[@]}"
printf " Workload Mode:     rand%s (QD=64, 4KB, single file O_DIRECT)\n" "$MODE_FILTER"
echo "------------------------------------------------------------------------------------------------------------------------"
printf "%-32s | %-28s | %-22s | %-12s | %-12s\n" "Engine" "Throughput (ops/s)" "Bandwidth (MB/s)" "Enters / op" "Delta vs Best"
echo "------------------------------------------------------------------------------------------------------------------------"
printf "%-32s | %'12.1f ± %'9.1f  | %'9.1f ± %'6.1f    | %-12s | %-12s\n" \
  "fio (default settings)" "$F_DEF_IOPS_MEAN" "$F_DEF_IOPS_STD" "$F_DEF_BW_MEAN" "$F_DEF_BW_STD" "$FIO_DEF_ENTERS" "—"
printf "%-32s | %'12.1f ± %'9.1f  | %'9.1f ± %'6.1f    | %-12s | %-12s\n" \
  "fio (best batching: B=$BEST_B)" "$F_BEST_IOPS_MEAN" "$F_BEST_IOPS_STD" "$F_BEST_BW_MEAN" "$F_BEST_BW_STD" "$FIO_BEST_ENTERS" "baseline"
printf "%-32s | %'12.1f ± %'9.1f  | %'9.1f ± %'6.1f    | %-12s | %-12s\n" \
  "JUring (JMH)" "$J_IOPS_MEAN" "$J_IOPS_STD" "$J_BW_MEAN" "$J_BW_STD" "$JMH_ENTERS" "$DELTA"
echo "------------------------------------------------------------------------------------------------------------------------"
printf " Headline Throughput Delta (JUring vs fio best): %s\n" "$DELTA"
echo "========================================================================================================================"
