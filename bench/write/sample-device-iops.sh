#!/usr/bin/env bash
#
# sample-device-iops.sh - 1 s-resolution device I/O sampler for the write matrix.
#
# Why: the write methodology (docs/benchmark-methodology.md) requires an
# IOPS-vs-time series at 1 s resolution for every write cell so the SLC-cache
# cliff can be detected (>30% step drop) and burst vs steady-state reported
# separately.  Neither JUringFileBench nor seastar_file_bench emits a time
# series, so run this script in the background next to the engine and let it
# read the kernel's per-device counters.
#
# Source: /sys/block/nvme1n1/stat (read-only sysfs; no real I/O is issued).
# Field layout of that file (kernel 7.x, 17 fields):
#   1 read_ios          2 read_merges       3 read_sectors(512B)  4 read_ticks
#   5 write_ios         6 write_merges      7 write_sectors(512B) 8 write_ticks
#   9 in_flight        10 io_ticks         11 time_in_queue(ms)
#  12 discard_ios      13 discard_merges   14 discard_sectors    15 discard_ticks
#  16 flush_ios        17 flush_ticks
# The counters are system-wide for the whole block device (not the mounted
# subvolume), which is what we want: it also catches foreign I/O on the same
# NVMe (a contamination signal).
#
# Output: TSV.  Lines starting with '#' are metadata/header comments; consumers
# should skip them.  One data row per sample (tab-separated):
#
#   sample epoch_s iso_utc elapsed_ms dt_ms
#   read_ios_d write_ios_d read_sectors_d write_sectors_d
#   read_iops write_iops read_mib_s write_mib_s
#   in_flight io_busy_pct loadavg_1m
#
# Deltas are computed against the previous sample (the first sample's baseline
# is the counter read taken immediately before the loop starts).
#
# Usage examples:
#   # background next to an engine run, killed with SIGTERM when the run ends:
#   bench/write/sample-device-iops.sh --out series/CELL.tsv --label CELL &
#   ...
#   kill -TERM $!
#
#   # fixed-length standalone sample (e.g. idle/GC observation):
#   bench/write/sample-device-iops.sh --out /tmp/idle.tsv --duration 60
#
#   # dry run: print what would be sampled, read the counters once, exit
#   bench/write/sample-device-iops.sh --out /tmp/x.tsv --dry-run
#
# Exit status: 0 on clean finish (duration reached or SIGTERM/SIGINT), 2 on
# usage error, 1 on a runtime failure (device stat unreadable, output not
# writable).
#
# Safety: this script only opens files read-only (/sys, /proc) and creates the
# output file under the path you give it.  It never writes to the NVMe dataset.
#
set -euo pipefail

PROG=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
DEFAULT_DEVICE_STAT=/sys/block/nvme1n1/stat

DEVICE_STAT=$DEFAULT_DEVICE_STAT
INTERVAL_SEC=1
DURATION_SEC=0
LABEL=""
OUT=""
DRY_RUN=0

usage() {
    cat <<'EOF'
sample-device-iops.sh - 1 s-resolution NVMe device I/O sampler (the 1 s device I/O series)

Usage:
  bench/write/sample-device-iops.sh --out FILE [options]

Options:
  --out FILE             output TSV path, or '-' for stdout (required)
  --device-stat PATH     sysfs stat file
                         (default /sys/block/nvme1n1/stat)
  --interval SEC         sampling interval in whole seconds (default 1)
  --duration SEC         stop after SEC seconds of wall time;
                         0 = run until SIGTERM/SIGINT (default 0)
  --label TEXT           free-form label written into the TSV header
                         (e.g. the cell stem, so series can be joined)
  --dry-run              print configuration + one parsed counter snapshot,
                         write nothing, exit 0
  -h, --help             this text

Output columns (tab-separated, '#' header lines first):
  sample epoch_s iso_utc elapsed_ms dt_ms read_ios_d write_ios_d
  read_sectors_d write_sectors_d read_iops write_iops read_mib_s
  write_mib_s in_flight io_busy_pct loadavg_1m

Notes:
  * Sector counts are 512-byte sectors as reported by the kernel.
  * 'in_flight' is the instantaneous field-9 value at the sample instant.
  * io_busy_pct = delta(io_ticks) / dt_ms * 100 (kernel device-busy proxy).
  * Counters cover the whole /dev/nvme1n1 device (contamination detection
    included), not just the benchmark file.
EOF
}

die() { printf '%s: error: %s\n' "$PROG" "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --out)         [[ $# -ge 2 ]] || die "--out needs a value"; OUT=$2; shift 2 ;;
        --device-stat) [[ $# -ge 2 ]] || die "--device-stat needs a value"; DEVICE_STAT=$2; shift 2 ;;
        --interval)    [[ $# -ge 2 ]] || die "--interval needs a value"; INTERVAL_SEC=$2; shift 2 ;;
        --duration)    [[ $# -ge 2 ]] || die "--duration needs a value"; DURATION_SEC=$2; shift 2 ;;
        --label)       [[ $# -ge 2 ]] || die "--label needs a value"; LABEL=$2; shift 2 ;;
        --dry-run)     DRY_RUN=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        *) printf '%s: error: unknown option %s (try --help)\n' "$PROG" "$1" >&2; exit 2 ;;
    esac
done

[[ -n "$OUT" ]] || die "--out is required (try --help)"
[[ "$INTERVAL_SEC" =~ ^[1-9][0-9]*$ ]] || die "--interval must be a positive integer, got '$INTERVAL_SEC'"
[[ "$DURATION_SEC" =~ ^[0-9]+$ ]] || die "--duration must be a non-negative integer, got '$DURATION_SEC'"
[[ -r "$DEVICE_STAT" ]] || die "cannot read device stat file: $DEVICE_STAT"

now_ns() { date +%s%N; }

# snapshot() -> "<read_ios> <read_sectors> <write_ios> <write_sectors> <inflight> <io_ticks>"
snapshot() {
    awk '
        {
            r_ios = $1 + 0; r_sec = $3 + 0
            w_ios = $5 + 0; w_sec = $7 + 0
            inflight = $9 + 0; io_ticks = $10 + 0
        }
        END { print r_ios, r_sec, w_ios, w_sec, inflight, io_ticks }
    ' "$DEVICE_STAT"
}

iso_utc() { # $1 = epoch seconds
    date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ
}

read -r R_IOS R_SEC W_IOS W_SEC INFLIGHT IO_TICKS <<<"$(snapshot)"
for v in "$R_IOS" "$R_SEC" "$W_IOS" "$W_SEC" "$INFLIGHT" "$IO_TICKS"; do
    [[ "$v" =~ ^[0-9]+$ ]] || die "could not parse numeric counters from $DEVICE_STAT"
done

if (( DRY_RUN )); then
    printf '# dry-run: sample-device-iops.sh\n'
    printf '#   out=%s\n' "$OUT"
    printf '#   device_stat=%s\n' "$DEVICE_STAT"
    printf '#   interval_s=%s duration_s=%s label=%s\n' "$INTERVAL_SEC" "$DURATION_SEC" "${LABEL:-<none>}"
    printf '#   parsed snapshot: read_ios=%s read_sectors=%s write_ios=%s write_sectors=%s inflight=%s io_ticks=%s\n' \
        "$R_IOS" "$R_SEC" "$W_IOS" "$W_SEC" "$INFLIGHT" "$IO_TICKS"
    printf '#   would sample every %ss and write TSV rows to %s\n' "$INTERVAL_SEC" "$OUT"
    exit 0
fi

if [[ "$OUT" != "-" ]]; then
    out_dir=$(dirname -- "$OUT")
    mkdir -p -- "$out_dir" || die "cannot create output directory: $out_dir"
    exec 3>"$OUT" || die "cannot open output file: $OUT"
else
    exec 3>&1
fi

T0_NS=$(now_ns)
T0_EPOCH=$(( T0_NS / 1000000000 ))
{
    printf '# sample-device-iops.sh v1 (the 1 s device I/O series)\n'
    printf '# label=%s\n' "${LABEL:-<none>}"
    printf '# device_stat=%s\n' "$DEVICE_STAT"
    printf '# interval_s=%s duration_s=%s\n' "$INTERVAL_SEC" "$DURATION_SEC"
    printf '# start_utc=%s\n' "$(iso_utc "$T0_EPOCH")"
    printf '# start_epoch_s=%s\n' "$T0_EPOCH"
    printf '# columns=sample\tepoch_s\tiso_utc\telapsed_ms\tdt_ms\tread_ios_d\twrite_ios_d\tread_sectors_d\twrite_sectors_d\tread_iops\twrite_iops\tread_mib_s\twrite_mib_s\tin_flight\tio_busy_pct\tloadavg_1m\n'
} >&3

STOPPING=0
trap 'STOPPING=1' TERM INT

T_PREV_NS=$T0_NS
PREV_R_IOS=$R_IOS PREV_R_SEC=$R_SEC PREV_W_IOS=$W_IOS PREV_W_SEC=$W_SEC PREV_IO_TICKS=$IO_TICKS
SAMPLE=0

while (( STOPPING == 0 )); do
    sleep "$INTERVAL_SEC" || true
    NOW_NS=$(now_ns)
    read -r CUR_R_IOS CUR_R_SEC CUR_W_IOS CUR_W_SEC CUR_INFLIGHT CUR_IO_TICKS <<<"$(snapshot)"

    SAMPLE=$(( SAMPLE + 1 ))
    DT_MS=$(( (NOW_NS - T_PREV_NS) / 1000000 ))
    if (( DT_MS < 1 )); then DT_MS=1; fi
    ELAPSED_MS=$(( (NOW_NS - T0_NS) / 1000000 ))
    EPOCH_S=$(( NOW_NS / 1000000000 ))

    R_IOS_D=$(( CUR_R_IOS - PREV_R_IOS ));       if (( R_IOS_D < 0 )); then R_IOS_D=0; fi
    W_IOS_D=$(( CUR_W_IOS - PREV_W_IOS ));       if (( W_IOS_D < 0 )); then W_IOS_D=0; fi
    R_SEC_D=$(( CUR_R_SEC - PREV_R_SEC ));       if (( R_SEC_D < 0 )); then R_SEC_D=0; fi
    W_SEC_D=$(( CUR_W_SEC - PREV_W_SEC ));       if (( W_SEC_D < 0 )); then W_SEC_D=0; fi
    IO_TICKS_D=$(( CUR_IO_TICKS - PREV_IO_TICKS )); if (( IO_TICKS_D < 0 )); then IO_TICKS_D=0; fi

    metrics=$(awk -v dt_ms="$DT_MS" -v ri="$R_IOS_D" -v wi="$W_IOS_D" \
                  -v rs="$R_SEC_D" -v ws="$W_SEC_D" -v iot="$IO_TICKS_D" '
        BEGIN {
            s = dt_ms / 1000.0
            if (s <= 0) s = 0.001
            printf "%.3f %.3f %.3f %.3f %.2f",
                ri / s, wi / s,
                (rs * 512.0) / 1048576.0 / s,
                (ws * 512.0) / 1048576.0 / s,
                (iot / s) * 100.0
        }')
    read -r R_IOPS W_IOPS R_MIB W_MIB BUSY_PCT <<<"$metrics"
    LOAD1=$(awk '{print $1}' /proc/loadavg)

    printf '%d\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f\t%.3f\t%.3f\t%.3f\t%d\t%.1f\t%s\n' \
        "$SAMPLE" "$EPOCH_S" "$(iso_utc "$EPOCH_S")" "$ELAPSED_MS" "$DT_MS" \
        "$R_IOS_D" "$W_IOS_D" "$R_SEC_D" "$W_SEC_D" \
        "$R_IOPS" "$W_IOPS" "$R_MIB" "$W_MIB" \
        "$CUR_INFLIGHT" "$BUSY_PCT" "$LOAD1" >&3

    T_PREV_NS=$NOW_NS
    PREV_R_IOS=$CUR_R_IOS PREV_R_SEC=$CUR_R_SEC
    PREV_W_IOS=$CUR_W_IOS PREV_W_SEC=$CUR_W_SEC PREV_IO_TICKS=$CUR_IO_TICKS

    if (( DURATION_SEC > 0 && ELAPSED_MS >= DURATION_SEC * 1000 )); then
        break
    fi
done

END_NS=$(now_ns)
{
    printf '# end_utc=%s samples=%s elapsed_ms=%s\n' \
        "$(iso_utc "$(( END_NS / 1000000000 ))")" "$SAMPLE" "$(( (END_NS - T0_NS) / 1000000 ))"
} >&3

if [[ "$OUT" != "-" ]]; then
    exec 3>&-
fi
exit 0
