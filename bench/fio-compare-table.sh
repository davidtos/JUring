#!/usr/bin/env bash
# bench/fio-compare-table.sh: Generates a summary comparison table from JMH and fio JSON results.
set -euo pipefail

DIR="${1:-}"
if [[ -z "$DIR" ]]; then
    DIR=$(ls -td results/* 2>/dev/null | head -n 1 || true)
fi

if [[ -z "$DIR" || ! -d "$DIR" ]]; then
    echo "Usage: $0 <results-directory>" >&2
    exit 1
fi

MODE_FILTER="${2:-}"
if [[ -n "$MODE_FILTER" && -f "$DIR/jmh-$MODE_FILTER-1.json" ]]; then
    JMH_FILES=("$DIR"/jmh-"$MODE_FILTER"-*.json)
    FIO_FILES=("$DIR"/fio-"$MODE_FILTER"-*.json)
else
    JMH_FILES=("$DIR"/jmh-*.json)
    FIO_FILES=("$DIR"/fio-*.json)
fi

if [[ ! -f "${JMH_FILES[0]:-}" ]]; then
    echo "Error: No JMH result files (jmh-*.json) found in $DIR" >&2
    exit 1
fi

if [[ ! -f "${FIO_FILES[0]:-}" ]]; then
    echo "Error: No fio result files (fio-*.json) found in $DIR" >&2
    exit 1
fi

# Calculate statistics using jq
STATS=$(jq -s '
  def mean: if length == 0 then 0 else (add / length) end;
  def stddev:
    if length <= 1 then 0
    else
      mean as $m |
      (map((. - $m) * (. - $m)) | add / (length - 1)) | sqrt
    end;

  # Split input files: JMH files have array type, fio files have object type with jobs
  [ .[] | if type == "array" then .[0] else empty end ] as $jmh_runs |
  [ .[] | if type == "object" and .jobs then . else empty end ] as $fio_runs |

  ($jmh_runs | map(.primaryMetric.score)) as $j_iops |
  ($jmh_runs | map((.secondaryMetrics.bytesProcessed.score // (.primaryMetric.score * 4096.0)) / 1048576.0)) as $j_bw |

  ($fio_runs | map(if ((.jobs[0].write.iops // 0) > 0) then .jobs[0].write.iops else .jobs[0].read.iops end)) as $f_iops |
  ($fio_runs | map((if ((.jobs[0].write.bw_bytes // 0) > 0) then .jobs[0].write.bw_bytes else .jobs[0].read.bw_bytes end) / 1048576.0)) as $f_bw |
  ($fio_runs | map((if ((.jobs[0].write.lat_ns.mean // 0) > 0) then .jobs[0].write.lat_ns.mean else .jobs[0].read.lat_ns.mean end) / 1000.0)) as $f_lat_mean |
  ($fio_runs | map((((if ((.jobs[0].write.lat_ns.percentile["50.000000"] // 0) > 0) then .jobs[0].write.lat_ns.percentile["50.000000"] else .jobs[0].read.lat_ns.percentile["50.000000"] end) | tonumber) / 1000.0))) as $f_lat_p50 |
  ($fio_runs | map((((if ((.jobs[0].write.lat_ns.percentile["99.000000"] // 0) > 0) then .jobs[0].write.lat_ns.percentile["99.000000"] else .jobs[0].read.lat_ns.percentile["99.000000"] end) | tonumber) / 1000.0))) as $f_lat_p99 |

  {
    jmh_count: ($j_iops | length),
    fio_count: ($f_iops | length),
    mode: ($fio_runs[0].jobs[0]["job options"].rw // "randread"),
    qd: ($fio_runs[0].jobs[0]["job options"].iodepth // "64"),
    file: ($fio_runs[0].jobs[0]["job options"].filename // "N/A"),
    bs: ($fio_runs[0].jobs[0]["job options"].bs // "4k"),
    runtime: ($fio_runs[0].jobs[0]["job options"].runtime // "N/A"),
    jmh_iops_mean: ($j_iops | mean),
    jmh_iops_stddev: ($j_iops | stddev),
    jmh_bw_mean: ($j_bw | mean),
    jmh_bw_stddev: ($j_bw | stddev),
    fio_iops_mean: ($f_iops | mean),
    fio_iops_stddev: ($f_iops | stddev),
    fio_bw_mean: ($f_bw | mean),
    fio_bw_stddev: ($f_bw | stddev),
    fio_lat_mean: ($f_lat_mean | mean),
    fio_lat_stddev: ($f_lat_mean | stddev),
    fio_lat_p50_mean: ($f_lat_p50 | mean),
    fio_lat_p50_stddev: ($f_lat_p50 | stddev),
    fio_lat_p99_mean: ($f_lat_p99 | mean),
    fio_lat_p99_stddev: ($f_lat_p99 | stddev)
  }
' "${JMH_FILES[@]}" "${FIO_FILES[@]}")

# Extract fields
J_COUNT=$(echo "$STATS" | jq -r '.jmh_count')
F_COUNT=$(echo "$STATS" | jq -r '.fio_count')
MODE=$(echo "$STATS" | jq -r '.mode')
QD=$(echo "$STATS" | jq -r '.qd')
FILE=$(echo "$STATS" | jq -r '.file')
BS=$(echo "$STATS" | jq -r '.bs')
RUNTIME=$(echo "$STATS" | jq -r '.runtime')

J_IOPS_MEAN=$(echo "$STATS" | jq -r '.jmh_iops_mean')
J_IOPS_STD=$(echo "$STATS" | jq -r '.jmh_iops_stddev')
J_BW_MEAN=$(echo "$STATS" | jq -r '.jmh_bw_mean')
J_BW_STD=$(echo "$STATS" | jq -r '.jmh_bw_stddev')

F_IOPS_MEAN=$(echo "$STATS" | jq -r '.fio_iops_mean')
F_IOPS_STD=$(echo "$STATS" | jq -r '.fio_iops_stddev')
F_BW_MEAN=$(echo "$STATS" | jq -r '.fio_bw_mean')
F_BW_STD=$(echo "$STATS" | jq -r '.fio_bw_stddev')

F_LAT_MEAN=$(echo "$STATS" | jq -r '.fio_lat_mean')
F_LAT_STD=$(echo "$STATS" | jq -r '.fio_lat_stddev')
F_P50_MEAN=$(echo "$STATS" | jq -r '.fio_lat_p50_mean')
F_P50_STD=$(echo "$STATS" | jq -r '.fio_lat_p50_stddev')
F_P99_MEAN=$(echo "$STATS" | jq -r '.fio_lat_p99_mean')
F_P99_STD=$(echo "$STATS" | jq -r '.fio_lat_p99_stddev')

# Pinning status
PIN_INFO="Unpinned"
if [[ -f "$DIR/meta.json" ]]; then
    PIN_TYPE=$(jq -r '.pinning // "unpinned"' "$DIR/meta.json")
    if [[ "$PIN_TYPE" == "pinned" ]]; then
        PIN_CPUS_VAL=$(jq -r '.pin_cpus // "N/A"' "$DIR/meta.json")
        PIN_APC_VAL=$(jq -r '.active_processor_count // "N/A"' "$DIR/meta.json")
        PIN_INFO="Pinned (CPUs: $PIN_CPUS_VAL, -XX:ActiveProcessorCount=$PIN_APC_VAL, -XX:+UseG1GC)"
    fi
fi

# Calculate percentage delta
DELTA=$(awk -v j="$J_IOPS_MEAN" -v f="$F_IOPS_MEAN" 'BEGIN {
  if (f > 0) printf "%+.2f%%", ((j / f) - 1.0) * 100.0;
  else printf "N/A";
}')

echo "========================================================================================================================"
echo "                             JUring vs Native C fio Head-to-Head Workload Comparison                                    "
echo "========================================================================================================================"
printf " Results Directory: %s (%d JMH runs, %d fio runs)\n" "$DIR" "$J_COUNT" "$F_COUNT"
printf " Pinning Status:    %s\n" "$PIN_INFO"
printf " Target File:       %s\n" "$FILE"
printf " Block Size:        %s (O_DIRECT, fixed buffers, registered files)\n" "$BS"
printf " Queue Depth (QD):  %s\n" "$QD"
printf " Workload Mode:     %s (norandommap, randrepeat=0)\n" "$MODE"
printf " Runtime / Window:  %s per repeat\n" "$RUNTIME"
echo "------------------------------------------------------------------------------------------------------------------------"
printf "%-24s | %-28s | %-24s | %-16s | %-16s\n" "Engine" "Throughput (ops/s)" "Bandwidth (MB/s)" "lat mean (µs)" "lat p99 (µs)"
echo "------------------------------------------------------------------------------------------------------------------------"
printf "%-24s | %'12.0f ± %'9.0f  | %'10.1f ± %'6.1f    | %16s | %16s\n" \
  "JUring (JMH)" "$J_IOPS_MEAN" "$J_IOPS_STD" "$J_BW_MEAN" "$J_BW_STD" "see .hlog" "see .hlog"
printf "%-24s | %'12.0f ± %'9.0f  | %'10.1f ± %'6.1f    | %8.2f ± %-5.2f | %8.2f ± %-5.2f\n" \
  "Native C fio" "$F_IOPS_MEAN" "$F_IOPS_STD" "$F_BW_MEAN" "$F_BW_STD" "$F_LAT_MEAN" "$F_LAT_STD" "$F_P99_MEAN" "$F_P99_STD"
echo "------------------------------------------------------------------------------------------------------------------------"
printf " Throughput Delta (JUring vs fio): %s\n" "$DELTA"
echo "========================================================================================================================"
