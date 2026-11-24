#!/usr/bin/env bash
#
# precondition.sh - the preconditioning burst (device preconditioning) for the
# write matrix.
#
# The write methodology (docs/benchmark-methodology.md): before a write session,
# write the 10 GiB write file to steady state - 3x file size of 4K randwrite at
# QD64 (~30 GiB) or 120 s minimum; then fsync.  Do not start a measured write
# cell on a fresh file or after a long idle.  Record the write pass and total
# bytes written since the last idle.
#
# This script does that with fio (io_uring, O_DIRECT, 4K randwrite, QD64, one
# job pinned to --cpu), in chunks of --chunk-bytes (default 4 GiB).
#
# Two stop conditions (--condition):
#   target (default)  Stop as soon as bytes_written >= target (--target-mult x
#                     file size, e.g. 32,212,254,720 B for the 10 GiB file).
#                     This is the write-matrix fix for the 2026-11-13 block, where the
#                     old AND condition wrote 128.8 GiB: 4 GiB chunks reach the
#                     32 GiB target in 8 chunks, no 120 s minimum is imposed.
#                     --min-sec is only a time cap here: if the target is NOT
#                     reached by --min-sec, the burst stops with
#                     stop_reason=min-sec-cap and rc=1 (capped), exactly like
#                     the --max-sec hard cap.
#   and               Old behaviour (reproduce the 2026-11-13 block): stop only
#                     when BOTH bytes_written >= target AND elapsed >=
#                     --min-sec.  To reproduce that block's full 12 x 10 GiB
#                     burst, also pass --chunk-bytes 10g (the old default chunk
#                     was one file size).
# --max-sec is always the hard safety cap (rc=1, capped) in both conditions.
#
# It then fsyncs the file, and emits a machine-readable PRECONDITION_RESULT line
# with bytes written, elapsed time, condition, stop_reason, fsync status and
# pre/post device counters from /sys/block/nvme1n1/stat so a run block is
# reproducible.
#
# A monotonically increasing "write pass" number is stored under
#   <session-dir>/state/write-pass.counter
# and every burst appends one row to
#   <session-dir>/state/write-passes.tsv
# so measured cells can be tied to the burst that preceded them.
#
# Usage:
#   bench/write/precondition.sh                 # real burst (needs the write file)
#   bench/write/precondition.sh --dry-run       # print the exact fio commands
#
# Exit status: 0 the stop condition was met (target reached / and-satisfied),
# 1 capped or failed (target not reached within --min-sec/--max-sec, or fio
# error), 2 usage error.
#
set -euo pipefail

PROG=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)

READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
WRITE_FILE=${WRITE_FILE:-$REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin}
SESSION_DIR=${SESSION_DIR:-$REPO_ROOT/bench/results/write-2026-11-13}
DEVICE_STAT=${DEVICE_STAT:-/sys/block/nvme1n1/stat}

TARGET_MULT=3
MIN_SEC=120
MAX_SEC=900
CHUNK_SPEC=               # empty -> default 4 GiB chunks (write-matrix default)
DEFAULT_CHUNK_BYTES=$(( 4 * 1073741824 ))
CONDITION=target          # target (write-matrix default) | and (old 2026-11-13 behaviour)
BS=4k
QD=64
CPU=2
NO_FSYNC=0
DRY_RUN=0
ALLOW_ANY_PATH=0

usage() {
    cat <<'EOF'
precondition.sh - preconditioning burst (4K randwrite QD64) + fsync

Usage:
  bench/write/precondition.sh [options]

Options:
  --file PATH            write dataset file
                         (default <repo>/nvme-data/write_files/single_file_bench_w.bin)
  --session-dir DIR      session dir for state/logs
                         (default <repo>/bench/results/write-2026-11-13)
  --device-stat PATH     sysfs stat file (default /sys/block/nvme1n1/stat)
  --target-mult N        bytes target = N x file size (default 3)
  --condition target|and stop rule (default target = stop as soon as the target
                         is reached; `and` = old behaviour: target AND --min-sec)
  --min-sec SEC          target: time cap if the target is not reached (default
                         120; stopping there is capped, rc=1);
                         and: minimum burst duration (default 120)
  --max-sec SEC          hard safety cap on burst duration (default 900)
  --chunk-bytes SIZE     fio --io_size per chunk (default 4 GiB)
  --bs SIZE              block size (default 4k)
  --qd N                 per-job queue depth (default 64)
  --cpu N                CPU to pin fio to with taskset (default 2)
  --no-fsync             skip the fsync after the burst (not the preconditioning rule)
  --dry-run              print the exact fio commands/loop, write nothing
  --allow-any-path       skip the "must be below <repo>/nvme-data" guard
  -h, --help             this text

Machine-readable output: one PRECONDITION_RESULT line on stdout:
  pass, condition, stop_reason, chunks, bytes_written, target_bytes,
  min_sec, elapsed_s, fsync, capped, pre/post write sectors, device write
  delta, loadavg start/end.

This script writes ONLY the given --file (via fio) and files under
<session-dir>/{state,logs}; it never touches the read dataset.
EOF
}

die() { printf '%s: error: %s\n' "$PROG" "$*" >&2; exit 1; }
warn() { printf '%s: warning: %s\n' "$PROG" "$*" >&2; }

parse_size() {
    local raw=$1 lower num mult
    lower=$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')
    num=${raw%%[a-zA-Z]*}
    [[ "$num" =~ ^[0-9]+$ ]] || return 1
    case "$lower" in
        *t|*tb|*tib) mult=1099511627776 ;;
        *g|*gb|*gib) mult=1073741824 ;;
        *m|*mb|*mib) mult=1048576 ;;
        *k|*kb|*kib) mult=1024 ;;
        *)           mult=1 ;;
    esac
    echo $(( num * mult ))
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --file)            [[ $# -ge 2 ]] || die "--file needs a value"; WRITE_FILE=$2; shift 2 ;;
        --session-dir)     [[ $# -ge 2 ]] || die "--session-dir needs a value"; SESSION_DIR=$2; shift 2 ;;
        --device-stat)     [[ $# -ge 2 ]] || die "--device-stat needs a value"; DEVICE_STAT=$2; shift 2 ;;
        --target-mult)     [[ $# -ge 2 ]] || die "--target-mult needs a value"; TARGET_MULT=$2; shift 2 ;;
        --condition)       [[ $# -ge 2 ]] || die "--condition needs a value"; CONDITION=$2; shift 2 ;;
        --min-sec)         [[ $# -ge 2 ]] || die "--min-sec needs a value"; MIN_SEC=$2; shift 2 ;;
        --max-sec)         [[ $# -ge 2 ]] || die "--max-sec needs a value"; MAX_SEC=$2; shift 2 ;;
        --chunk-bytes)     [[ $# -ge 2 ]] || die "--chunk-bytes needs a value"; CHUNK_SPEC=$2; shift 2 ;;
        --bs)              [[ $# -ge 2 ]] || die "--bs needs a value"; BS=$2; shift 2 ;;
        --qd)              [[ $# -ge 2 ]] || die "--qd needs a value"; QD=$2; shift 2 ;;
        --cpu)             [[ $# -ge 2 ]] || die "--cpu needs a value"; CPU=$2; shift 2 ;;
        --no-fsync)        NO_FSYNC=1; shift ;;
        --dry-run)         DRY_RUN=1; shift ;;
        --allow-any-path)  ALLOW_ANY_PATH=1; shift ;;
        -h|--help)         usage; exit 0 ;;
        *) printf '%s: error: unknown option %s (try --help)\n' "$PROG" "$1" >&2; exit 2 ;;
    esac
done

[[ "$TARGET_MULT" =~ ^[1-9][0-9]*$ ]] || die "--target-mult must be a positive integer"
[[ "$CONDITION" == "target" || "$CONDITION" == "and" ]] || die "--condition must be target or and"
[[ "$MIN_SEC" =~ ^[0-9]+$ ]] || die "--min-sec must be a non-negative integer"
[[ "$MAX_SEC" =~ ^[0-9]+$ ]] || die "--max-sec must be a non-negative integer"
[[ "$QD" =~ ^[1-9][0-9]*$ ]] || die "--qd must be a positive integer"
[[ "$CPU" =~ ^[0-9]+$ ]] || die "--cpu must be a CPU number"
(( MAX_SEC >= MIN_SEC )) || die "--max-sec ($MAX_SEC) must be >= --min-sec ($MIN_SEC)"
[[ -r "$DEVICE_STAT" ]] || die "cannot read device stat file: $DEVICE_STAT"
[[ "$BS" =~ ^[0-9]+[kKmMgG]?$ ]] || die "--bs must look like 4k/1m (got '$BS')"

canon() { realpath -m -- "$1"; }
WF=$(canon "$WRITE_FILE")
RF=$(canon "$READ_FILE")
ND=$(canon "$REPO_ROOT/nvme-data")

[[ "$WF" == "$RF" ]] && die "refusing: target resolves to the read dataset ($RF)"
case "$WF" in
    /mnt/TestData/read_files/*) die "refusing: target is below the read dataset ($WF)" ;;
esac
if [[ "$WF" != "$ND"/* ]] && (( ! ALLOW_ANY_PATH )); then
    die "refusing: --file must be below $ND (got $WF); pass --allow-any-path to override"
fi

now_s() { date +%s; }
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
devstat() {
    awk '
        {
            r_ios = $1 + 0; r_sec = $3 + 0
            w_ios = $5 + 0; w_sec = $7 + 0
            in_flight = $9 + 0
        }
        END { print r_ios, r_sec, w_ios, w_sec, in_flight }
    ' "$DEVICE_STAT"
}

FILE_EXISTS=0
FILE_SIZE=0
if [[ -e "$WF" ]]; then
    FILE_EXISTS=1
    [[ -f "$WF" ]] || die "not a regular file: $WF"
    FILE_SIZE=$(stat -c '%s' -- "$WF")
    (( FILE_SIZE > 0 )) || die "file has zero size: $WF"
    if (( ! DRY_RUN )); then
        [[ -w "$WF" ]] || die "write file is not writable: $WF"
        (( FILE_SIZE % 4096 == 0 )) || warn "file size ($FILE_SIZE) is not a multiple of 4096; 4K O_DIRECT may fail"
    fi
else
    if (( ! DRY_RUN )); then
        die "write file does not exist: $WF (run bench/write/create-write-file.sh first)"
    fi
    warn "write file does not exist yet ($WF); dry-run assumes a 10 GiB file for the example commands"
fi

if (( FILE_SIZE > 0 )); then
    TARGET_BYTES=$(( FILE_SIZE * TARGET_MULT ))
else
    TARGET_BYTES=$(( 10 * 1073741824 * TARGET_MULT ))
fi

if [[ -n "$CHUNK_SPEC" ]]; then
    CHUNK_BYTES=$(parse_size "$CHUNK_SPEC") || die "cannot parse --chunk-bytes '$CHUNK_SPEC'"
else
    CHUNK_BYTES=$DEFAULT_CHUNK_BYTES
fi
(( CHUNK_BYTES >= 1048576 )) || die "--chunk-bytes must be >= 1 MiB"
if (( CHUNK_BYTES > TARGET_BYTES )); then
    warn "--chunk-bytes ($CHUNK_BYTES) exceeds the target ($TARGET_BYTES); one chunk will overshoot"
fi
# Chunks needed if the target were hit exactly, for the dry-run/help estimate.
EXPECTED_CHUNKS=$(( (TARGET_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES ))

read -r PREV_R_IOS PREV_R_SEC PREV_W_IOS PREV_W_SEC PREV_INFLIGHT <<<"$(devstat)"
for v in "$PREV_R_IOS" "$PREV_R_SEC" "$PREV_W_IOS" "$PREV_W_SEC" "$PREV_INFLIGHT"; do
    [[ "$v" =~ ^[0-9]+$ ]] || die "cannot parse counters from $DEVICE_STAT"
done
LOADAVG_START=$(awk '{print $1"/"$2"/"$3}' /proc/loadavg)
GOVERNOR=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)
PROBE_DIR=$(dirname -- "$WF")
while [[ ! -e "$PROBE_DIR" ]]; do
    PROBE_DIR=$(dirname -- "$PROBE_DIR")
done
FSTYPE=$(findmnt -no FSTYPE -T "$PROBE_DIR" 2>/dev/null | tail -n1 || true)
SOURCE=$(findmnt -no SOURCE -T "$PROBE_DIR" 2>/dev/null | tail -n1 || true)

if (( DRY_RUN )); then
    printf '# dry-run: precondition.sh (preconditioning burst)\n'
    printf '#   file=%s exists=%s size_bytes=%s\n' "$WF" "$FILE_EXISTS" "$FILE_SIZE"
    printf '#   target_mult=%s condition=%s target_bytes=%s min_sec=%s max_sec=%s chunk_bytes=%s expected_chunks_if_exact=%s\n' \
        "$TARGET_MULT" "$CONDITION" "$TARGET_BYTES" "$MIN_SEC" "$MAX_SEC" "$CHUNK_BYTES" "$EXPECTED_CHUNKS"
    printf '#   bs=%s qd=%s cpu=%s fsync=%s fstype=%s source=%s\n' \
        "$BS" "$QD" "$CPU" "$( (( NO_FSYNC )) && echo skipped || echo after-burst )" "${FSTYPE:-unknown}" "${SOURCE:-unknown}"
    if [[ "$CONDITION" == "target" ]]; then
        printf '#   loop condition: stop as soon as bytes_written >= %s; --max-sec=%s hard cap;\n' \
            "$TARGET_BYTES" "$MAX_SEC"
        printf '#     --min-sec=%s is only a time cap when the target is NOT reached (stop_reason=min-sec-cap, rc=1)\n' \
            "$MIN_SEC"
    else
        printf '#   loop condition (and): bytes_written >= %s AND elapsed_s >= %s, capped at %s s\n' \
            "$TARGET_BYTES" "$MIN_SEC" "$MAX_SEC"
    fi
    printf '#   chunk command (repeated, chunk index increments; each chunk writes %s bytes):\n' "$CHUNK_BYTES"
    printf 'taskset -c %s fio --name=precondition_pass<PASS>_c<CHUNK> --filename=%s --ioengine=io_uring --direct=1 --rw=randwrite --bs=%s --iodepth=%s --numjobs=1 --io_size=%s --randrepeat=0 --group_reporting --clocksource=clock_gettime --output-format=json --output=%s\n' \
        "$CPU" "$WF" "$BS" "$QD" "$CHUNK_BYTES" \
        "$SESSION_DIR/logs/precondition_pass<PASS>_chunk<CHUNK>.json"
    if (( ! NO_FSYNC )); then
        printf 'sync -f %s   # fsync after the burst\n' "$(printf '%q' "$WF")"
    fi
    printf 'PRECONDITION_RESULT action=dry-run file=%s pass=would-increment condition=%s stop_reason=not-run target_bytes=%s min_sec=%s max_sec=%s chunk_bytes=%s fsync=%s\n' \
        "$WF" "$CONDITION" "$TARGET_BYTES" "$MIN_SEC" "$MAX_SEC" "$CHUNK_BYTES" "$( (( NO_FSYNC )) && echo skipped || echo after-burst )"
    exit 0
fi

# ------------------------------------------------------------------ real burst
mkdir -p -- "$SESSION_DIR/state" "$SESSION_DIR/logs"
COUNTER_FILE="$SESSION_DIR/state/write-pass.counter"
PASS=0
if [[ -f "$COUNTER_FILE" ]]; then
    PASS=$(tr -cd '0-9' <"$COUNTER_FILE")
    [[ -n "$PASS" ]] || PASS=0
fi
PASS=$(( PASS + 1 ))
printf '%s\n' "$PASS" >"$COUNTER_FILE"

PASS_TSV="$SESSION_DIR/state/write-passes.tsv"
if [[ ! -s "$PASS_TSV" ]]; then
    printf '%s\n' $'pass\tstarted_utc\tfile\tfile_size_bytes\tbytes_written\ttarget_bytes\tmin_sec\telapsed_s\tchunks\tfsync\tpre_write_sectors\tpost_write_sectors\tdevice_write_sectors_d\tloadavg_start\tloadavg_end\tcapped\tcondition\tstop_reason' >"$PASS_TSV"
fi

RUN_LOG="$SESSION_DIR/logs/precondition_pass${PASS}-$(date -u +%Y%m%dT%H%M%SZ).log"
{
    printf '=== precondition.sh burst pass=%s %s\n' "$PASS" "$(now_utc)"
    printf 'file=%s size_bytes=%s target_mult=%s condition=%s target_bytes=%s min_sec=%s max_sec=%s chunk_bytes=%s\n' \
        "$WF" "$FILE_SIZE" "$TARGET_MULT" "$CONDITION" "$TARGET_BYTES" "$MIN_SEC" "$MAX_SEC" "$CHUNK_BYTES"
    printf 'bs=%s qd=%s cpu=%s fstype=%s source=%s governor=%s loadavg=%s\n' \
        "$BS" "$QD" "$CPU" "$FSTYPE" "$SOURCE" "$GOVERNOR" "$LOADAVG_START"
    printf 'pre_device_stat: read_ios=%s read_sectors=%s write_ios=%s write_sectors=%s inflight=%s\n' \
        "$PREV_R_IOS" "$PREV_R_SEC" "$PREV_W_IOS" "$PREV_W_SEC" "$PREV_INFLIGHT"
} >"$RUN_LOG"

START_S=$(now_s)
TOTAL_BYTES=0
CHUNKS=0
ZERO_CHUNKS=0
CAPPED=0
STOP_REASON="unset"

while :; do
    ELAPSED=$(( $(now_s) - START_S ))
    if [[ "$CONDITION" == "target" ]] && (( TOTAL_BYTES >= TARGET_BYTES )); then
        STOP_REASON="target-reached"
        break
    fi
    if [[ "$CONDITION" == "and" ]] && (( TOTAL_BYTES >= TARGET_BYTES && ELAPSED >= MIN_SEC )); then
        STOP_REASON="and-satisfied"
        break
    fi
    if (( MAX_SEC > 0 && ELAPSED >= MAX_SEC )); then
        CAPPED=1
        STOP_REASON="max-sec-cap"
        warn "reached --max-sec=$MAX_SEC before the stop condition; stopping burst (bytes=$TOTAL_BYTES elapsed=${ELAPSED}s)"
        break
    fi
    if [[ "$CONDITION" == "target" ]] && (( MIN_SEC > 0 && ELAPSED >= MIN_SEC )); then
        CAPPED=1
        STOP_REASON="min-sec-cap"
        warn "target $TARGET_BYTES B not reached within --min-sec=$MIN_SEC s (bytes=$TOTAL_BYTES); stopping capped (rc=1)"
        break
    fi
    CHUNKS=$(( CHUNKS + 1 ))
    CHUNK_JSON="$SESSION_DIR/logs/precondition_pass${PASS}_chunk${CHUNKS}.json"
    CMD=(taskset -c "$CPU" fio
        --name="precondition_pass${PASS}_c${CHUNKS}"
        --filename="$WF"
        --ioengine=io_uring --direct=1
        --rw=randwrite --bs="$BS" --iodepth="$QD" --numjobs=1
        --io_size="$CHUNK_BYTES" --randrepeat=0 --group_reporting
        --clocksource=clock_gettime
        --output-format=json --output="$CHUNK_JSON")
    {
        printf '=== chunk=%s %s elapsed_s=%s bytes_so_far=%s\n' "$CHUNKS" "$(now_utc)" "$ELAPSED" "$TOTAL_BYTES"
        printf 'CMD: %s\n' "${CMD[*]}"
    } >>"$RUN_LOG"
    set +e
    "${CMD[@]}" >>"$RUN_LOG" 2>&1
    RC=$?
    set -e
    if (( RC != 0 )); then
        printf 'CHUNK_EXIT chunk=%s rc=%s\n' "$CHUNKS" "$RC" >>"$RUN_LOG"
        die "fio precondition chunk $CHUNKS failed (rc=$RC); see $RUN_LOG"
    fi
    CB=$(python3 - "$CHUNK_JSON" <<'PY'
import json
import sys
try:
    with open(sys.argv[1], "r", encoding="utf-8") as fh:
        doc = json.load(fh)
    job = doc["jobs"][0]
    wr = job.get("write") or {}
    b = wr.get("io_bytes")
    if b is None:
        b = int(wr.get("io_kbytes", 0)) * 1024
    print(int(b))
except Exception:
    print(0)
PY
)
    [[ "$CB" =~ ^[0-9]+$ ]] || CB=0
    TOTAL_BYTES=$(( TOTAL_BYTES + CB ))
    {
        printf '=== chunk=%s done %s rc=0 bytes=%s chunk_json=%s\n' "$CHUNKS" "$(now_utc)" "$CB" "$CHUNK_JSON"
    } >>"$RUN_LOG"
    if (( CB == 0 )); then
        ZERO_CHUNKS=$(( ZERO_CHUNKS + 1 ))
        warn "chunk $CHUNKS wrote no bytes (json=$CHUNK_JSON)"
        if (( ZERO_CHUNKS >= 3 )); then
            die "three consecutive zero-byte precondition chunks; aborting (see $RUN_LOG)"
        fi
    else
        ZERO_CHUNKS=0
    fi
    if (( ! NO_FSYNC )); then
        {
            printf '=== fsync after chunk=%s %s\n' "$CHUNKS" "$(now_utc)"
        } >>"$RUN_LOG"
        sync -f -- "$WF" 2>/dev/null || sync
    fi
done

if (( ! NO_FSYNC )); then
    sync -f -- "$WF" 2>/dev/null || sync
fi

END_S=$(now_s)
ELAPSED=$(( END_S - START_S ))
read -r POST_R_IOS POST_R_SEC POST_W_IOS POST_W_SEC POST_INFLIGHT <<<"$(devstat)"
W_DELTA=$(( POST_W_IOS - PREV_W_IOS ))
WSEC_DELTA=$(( POST_W_SEC - PREV_W_SEC ))
(( W_DELTA < 0 )) && W_DELTA=0
(( WSEC_DELTA < 0 )) && WSEC_DELTA=0
LOADAVG_END=$(awk '{print $1"/"$2"/"$3}' /proc/loadavg)
FSYNC_STATUS="ok"
(( NO_FSYNC )) && FSYNC_STATUS="skipped"
(( CAPPED )) && FSYNC_STATUS="${FSYNC_STATUS}-capped"

{
    printf '=== precondition end %s elapsed_s=%s bytes_written=%s chunks=%s capped=%s condition=%s stop_reason=%s fsync=%s\n' \
        "$(now_utc)" "$ELAPSED" "$TOTAL_BYTES" "$CHUNKS" "$CAPPED" "$CONDITION" "$STOP_REASON" "$FSYNC_STATUS"
    printf 'post_device_stat: read_ios=%s read_sectors=%s write_ios=%s write_sectors=%s inflight=%s\n' \
        "$POST_R_IOS" "$POST_R_SEC" "$POST_W_IOS" "$POST_W_SEC" "$POST_INFLIGHT"
    printf 'device_write_ios_delta=%s device_write_sectors_delta=%s loadavg_end=%s\n' \
        "$W_DELTA" "$WSEC_DELTA" "$LOADAVG_END"
} >>"$RUN_LOG"

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$PASS" "$(now_utc)" "$WF" "$FILE_SIZE" "$TOTAL_BYTES" "$TARGET_BYTES" "$MIN_SEC" \
    "$ELAPSED" "$CHUNKS" "$FSYNC_STATUS" "$PREV_W_SEC" "$POST_W_SEC" "$WSEC_DELTA" \
    "$LOADAVG_START" "$LOADAVG_END" "$CAPPED" "$CONDITION" "$STOP_REASON" >>"$PASS_TSV"

printf 'PRECONDITION_RESULT file=%s pass=%s condition=%s stop_reason=%s chunks=%s bytes_written=%s target_bytes=%s min_sec=%s elapsed_s=%s fsync=%s capped=%s pre_write_sectors=%s post_write_sectors=%s device_write_sectors_delta=%s device_write_mib=%s loadavg_start=%s loadavg_end=%s log=%s\n' \
    "$WF" "$PASS" "$CONDITION" "$STOP_REASON" "$CHUNKS" "$TOTAL_BYTES" "$TARGET_BYTES" "$MIN_SEC" "$ELAPSED" \
    "$FSYNC_STATUS" "$CAPPED" "$PREV_W_SEC" "$POST_W_SEC" "$WSEC_DELTA" \
    "$(( WSEC_DELTA / 2048 ))" "$LOADAVG_START" "$LOADAVG_END" "$RUN_LOG"

if (( CAPPED )); then
    exit 1
fi
exit 0
