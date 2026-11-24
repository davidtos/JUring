#!/usr/bin/env bash
#
# run-stream-matrix.sh -- stream matrix driver (protocol frozen before the campaign).
#
# Cells (one process per rep, pinned to CPU 2, primary O_DIRECT rows on both
# engines, engines interleaved inside every rep, plus one disclosed JUring
# --buffered exploratory row per cell):
#
#   stream-write-128k  stream_write  128 KiB buffer  qd 8  nvme-data/write_files/single_file_bench_w.bin (10 GiB)
#   stream-read-128k   stream_read   128 KiB buffer  qd 8  /mnt/TestData/read_files/single_file_bench.bin (10 GiB)
#   stream-write-1m    stream_write  1 MiB buffer    qd 8  same writable dataset
#   stream-read-1m     stream_read   1 MiB buffer    qd 8  same read dataset
#
# Cell IDs are the canonical names from bench/analysis/cells.py; --cells only
# accepts those canonical names.
#
# Primary engines:
#   juring   bench/juring/run-juring-stream.sh --buffered off (O_DIRECT), registered file,
#            SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN, entries=max(256,2*qd), stream
#            bufferSize=bs, readAhead=qd-1 / writeBehind=qd, alignment=4096
#   seastar  bench/seastar/seastar_stream_bench (fstream), --smp 1 --cpuset 2 --memory 1G
#            --lock-memory 0 --reactor-backend io_uring
#   exploratory: JUring --buffered (no O_DIRECT) per cell; never used for the >=85 % verdict.
#
# Default protocol (preliminary): warmup 10 s + measured 30 s per
# process, R=3 reps per cell, 60 s rest between successive reps of a cell.
# Overrides: --reps N --seconds S --warmup S --rest S --cells LIST.
#
# Results (gitignored): bench/results/<session>/ with
#   cells/<cell>__<engine>__<variant>__r<rep>.json   raw per-process JSON
#   logs/<same>.log                                  stdout+stderr of the process
#   cell-meta.tsv                                    one row per process invocation
#   manifest.txt                                     host/device/tool/protocol manifest
#   aggregate/stream.{json,md}                       written by bench/stream/analyze_stream.py
#
# The script is side-effect free with --dry-run: it prints preflight results and
# the full command plan without creating files or running benchmarks.
#
# Re-running: sessions do not overwrite each other; if the session's cells/
# directory already contains JSON, the script refuses to continue. Start a new
# session with `--session NAME` (or pass --force to deliberately append).
#
# Usage:
#   bench/stream/run-stream-matrix.sh [--dry-run] [--session NAME]
#       [--reps N] [--seconds S] [--warmup S] [--rest S] [--cells "stream-write-128k,..."]
#       [--force]
#
# Environment: CPU (default 2), READ_FILE, WRITE_FILE, SEASTAR_BIN, JURING_RUN,
# ALLOW_RESIDUAL_LOAD=1 (relax the 1-min loadavg gate for chained sessions).
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../.." && pwd)
# shellcheck source=bench/cells.sh
source "$REPO_ROOT/bench/cells.sh"
cd "$REPO_ROOT"

CPU=${CPU:-2}
SESSION=stream-matrix
READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
WRITE_FILE=${WRITE_FILE:-$REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin}
SEASTAR_BIN=${SEASTAR_BIN:-$REPO_ROOT/bench/seastar/seastar_stream_bench}
JURING_RUN=${JURING_RUN:-$REPO_ROOT/bench/juring/run-juring-stream.sh}
ANALYZER=$HERE/analyze_stream.py
SYSROOT_LIB=$REPO_ROOT/.sysroot/root/usr/lib64
SEED=${SEED:-20261113}
DATASET_MIN=10737418240          # 10 GiB expected dataset size
QD=8

REPS=3
WARMUP=10
DURATION=30
REST=60
CELLS="stream-write-128k stream-read-128k stream-write-1m stream-read-1m"
DRY_RUN=0
FORCE=0
ALL_CELLS="stream-write-128k stream-read-128k stream-write-1m stream-read-1m"

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

while (($#)); do
    case $1 in
        --dry-run) DRY_RUN=1 ;;
        --session)
            SESSION=${2:?--session needs a value}
            shift
            ;;
        --reps)
            REPS=${2:?--reps needs a value}
            shift
            ;;
        --seconds)
            DURATION=${2:?--seconds needs a value}
            shift
            ;;
        --warmup)
            WARMUP=${2:?--warmup needs a value}
            shift
            ;;
        --rest)
            REST=${2:?--rest needs a value}
            shift
            ;;
        --cells)
            CELLS=${2:?--cells needs a value}
            CELLS=${CELLS//,/ }
            shift
            ;;
        --force) FORCE=1 ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            echo "error: unknown argument '$1' (try --help)" >&2
            exit 2
            ;;
    esac
    shift
done

if ! [[ $REPS =~ ^[1-9][0-9]*$ ]]; then
    echo "error: --reps must be a positive integer, got '$REPS'" >&2
    exit 2
fi
for v in "$WARMUP" "$DURATION" "$REST"; do
    if ! [[ $v =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        echo "error: --warmup/--seconds/--rest must be numbers, got '$v'" >&2
        exit 2
    fi
done
# Validate requested cells: only canonical slugs are accepted; every output
# file and manifest uses the canonical slug.
CELLS_CANON=""
for c in $CELLS; do
    canonical=$(cell_slug "$c")
    case " $ALL_CELLS " in
        *" $canonical "*) ;;
        *)
            echo "error: unknown cell '$c' (expected: $ALL_CELLS)" >&2
            exit 2
            ;;
    esac
    CELLS_CANON="${CELLS_CANON:+$CELLS_CANON }$canonical"
done
CELLS=$CELLS_CANON

if ! [[ $SESSION =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "error: --session may only contain [A-Za-z0-9._-], got '$SESSION'" >&2
    exit 2
fi

RESULTS=$REPO_ROOT/bench/results/$SESSION
CELLS_DIR=$RESULTS/cells
LOGS_DIR=$RESULTS/logs
AGG_DIR=$RESULTS/aggregate
META=$RESULTS/cell-meta.tsv
MANIFEST=$RESULTS/manifest.txt

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
loadavg() { awk '{print $1","$2","$3}' /proc/loadavg; }
wall_sec() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.3f", b-a}'; }

cell_workload() {
    case $(cell_slug "$1") in
        stream-write-*) echo stream_write ;;
        stream-read-*) echo stream_read ;;
        *) return 1 ;;
    esac
}
cell_bs() {
    case $(cell_slug "$1") in
        *-128k) echo 128k ;;
        *-1m) echo 1m ;;
        *) return 1 ;;
    esac
}
cell_file() {
    case $(cell_workload "$1") in
        stream_write) echo "$WRITE_FILE" ;;
        stream_read) echo "$READ_FILE" ;;
        *) return 1 ;;
    esac
}
cell_dataset() {
    local f
    f=$(cell_file "$1")
    if [[ -f $f ]]; then
        stat -c%s "$f"
    else
        echo "$DATASET_MIN"
    fi
}

# run_argv <cell> <engine> <variant> <rep>
# Sets the global array ARGV to the exact process command (without taskset).
run_argv() {
    local cell=$1 engine=$2 variant=$3 rep=$4
    local wl bs file size id
    wl=$(cell_workload "$cell") || return 1
    bs=$(cell_bs "$cell") || return 1
    file=$(cell_file "$cell") || return 1
    size=$(cell_dataset "$cell")
    id="${cell}__${engine}__${variant}__r${rep}"
    ARGV=()
    if [[ $engine == juring ]]; then
        ARGV=(env JURING_SKIP_COMPILE=1 bash "$JURING_RUN"
            --workload "$wl" --file "$file" --bs "$bs" --qd "$QD" --size "$size"
            --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1
            --seed "$SEED" --label "$id" --json "$CELLS_DIR/$id.json")
        if [[ $wl == stream_read ]]; then ARGV+=(--readonly); fi
        if [[ $variant == buffered ]]; then ARGV+=(--buffered); fi
    elif [[ $engine == seastar ]]; then
        ARGV=("$SEASTAR_BIN"
            --workload "$wl" --file "$file" --bs "$bs" --qd "$QD" --size "$size"
            --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1
            --seed "$SEED" --label "$id" --json "$CELLS_DIR/$id.json"
            --smp 1 --cpuset "$CPU" --memory 1G --lock-memory 0 --reactor-backend io_uring)
        if [[ $wl == stream_read ]]; then ARGV+=(--readonly); fi
    else
        echo "error: unknown engine '$engine'" >&2
        return 1
    fi
}

argv_str() {
    printf '%q ' "$@"
}

# engine_exec_order <rep>: primary engine order alternates per rep (the alternating order
# cancellation); the exploratory buffered JUring row always runs last.
engine_order() {
    if (( $1 % 2 == 1 )); then
        echo "juring seastar buffered"
    else
        echo "seastar juring buffered"
    fi
}

# json_summary <path> -> tab-separated: status ops mibs p99_us bytes errors
json_summary() {
    python3 -c '
import json, sys
try:
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        d = json.load(f)
except Exception:
    print("missing\t-1\t-1\t-1\t-1\t-1")
    raise SystemExit(0)
agg = d.get("aggregate") or {}
mibs = agg.get("mibs") or {}
lat = agg.get("latency_us") or {}
print("%s\t%s\t%.3f\t%.3f\t%s\t%s" % (
    d.get("status", "?"), agg.get("ops_total", 0), float(mibs.get("mean", 0.0)),
    float(lat.get("p99", 0.0)), agg.get("bytes_total", 0), agg.get("errors_total", 0)))
' "$1" 2>/dev/null || printf 'missing\t-1\t-1\t-1\t-1\t-1\n'
}

# ---------------------------------------------------------------------------
# preflight: gate the machine (frozen preflight). Returns 1 on failure; with report_only
# (-1) it always returns 0 and prints the verdicts.
# ---------------------------------------------------------------------------
preflight() {
    local strict=$1 fail=0
    local govs avail rd_size wr_size la1 foreign io_disabled

    check() { # check <ok> <message> [failure-message]
        if (( $1 )); then
            printf 'preflight: PASS  %s\n' "$2"
        else
            printf 'preflight: FAIL  %s\n' "${3:-$2}"
            fail=1
        fi
    }

    check $([[ -f $READ_FILE ]] && echo 1 || echo 0) "read dataset exists: $READ_FILE" \
        "missing read dataset: $READ_FILE"
    check $([[ -f $WRITE_FILE ]] && echo 1 || echo 0) "write dataset exists: $WRITE_FILE" \
        "missing write dataset: $WRITE_FILE"
    check $([[ -f $READ_FILE && -r $READ_FILE ]] && echo 1 || echo 0) "read dataset readable" \
        "read dataset not readable: $READ_FILE"
    check $([[ -f $WRITE_FILE && -w $WRITE_FILE ]] && echo 1 || echo 0) "write dataset writable" \
        "write dataset not writable: $WRITE_FILE"

    rd_size=$(stat -c%s "$READ_FILE" 2>/dev/null || echo 0)
    wr_size=$(stat -c%s "$WRITE_FILE" 2>/dev/null || echo 0)
    check $(( rd_size >= DATASET_MIN && rd_size % 1048576 == 0 )) \
        "read dataset size $(numfmt --to=iec "$rd_size" 2>/dev/null || echo "$rd_size") >= 10 GiB" \
        "read dataset size $rd_size is not a >= 10 GiB multiple of 1 MiB (expect $DATASET_MIN)"
    check $(( wr_size >= DATASET_MIN && wr_size % 1048576 == 0 )) \
        "write dataset size $(numfmt --to=iec "$wr_size" 2>/dev/null || echo "$wr_size") >= 10 GiB" \
        "write dataset size $wr_size is not a >= 10 GiB multiple of 1 MiB (expect $DATASET_MIN)"
    if (( rd_size != DATASET_MIN || wr_size != DATASET_MIN )); then
        printf 'preflight: NOTE  dataset sizes are not exactly 10 GiB (read=%s write=%s); cell datasets are the actual file sizes\n' \
            "$rd_size" "$wr_size"
    fi

    avail=$(df -B1 --output=avail "$WRITE_FILE" 2>/dev/null | tail -n 1 | tr -d ' ' || echo 0)
    check $(( ${avail:-0} >= 2 * 1024 * 1024 * 1024 )) \
        "free space on write filesystem: $(numfmt --to=iec "${avail:-0}" 2>/dev/null || echo "$avail")" \
        "write filesystem has less than 2 GiB free (${avail:-0} B)"

    io_disabled=$(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?')
    check $([[ $io_disabled == 0 ]] && echo 1 || echo 0) \
        "io_uring enabled (kernel.io_uring_disabled=$io_disabled)" \
        "io_uring disabled (kernel.io_uring_disabled=$io_disabled)"

    govs=$(for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort -u | tr '\n' ' ')
    check $([[ $govs == "performance " ]] && echo 1 || echo 0) "CPU governor performance (all CPUs)" \
        "CPU governor is '$govs', expected performance (all CPUs)"

    check $([[ -e /sys/devices/system/cpu/cpu$CPU ]] && echo 1 || echo 0) "CPU $CPU online" \
        "CPU $CPU not present"

    la1=$(awk '{print $1}' /proc/loadavg)
    if awk -v x="$la1" 'BEGIN{exit !(x < 1.0)}'; then
        printf 'preflight: PASS  1-min loadavg %s < 1.0\n' "$la1"
    elif [[ ${ALLOW_RESIDUAL_LOAD:-0} == 1 ]]; then
        printf 'preflight: NOTE  1-min loadavg %s >= 1.0 (ALLOW_RESIDUAL_LOAD=1, residual from our own session)\n' "$la1"
    else
        printf 'preflight: FAIL  1-min loadavg %s >= 1.0\n' "$la1"
        fail=1
    fi

    foreign=$(ps -eo pcpu=,pid=,comm= --sort=-pcpu |
        awk -v self="$$" '$2 != self && $1 > 20 {printf "%s(pid %s, %.0f%%) ", $3, $2, $1}')
    check $([[ -z $foreign ]] && echo 1 || echo 0) "no foreign process >20% CPU" \
        "foreign process >20% CPU: $foreign"

    check $([[ -x $JURING_RUN ]] && echo 1 || echo 0) "JUring runner executable: $JURING_RUN" \
        "JUring runner missing/not executable: $JURING_RUN"
    check $([[ -x $SEASTAR_BIN ]] && echo 1 || echo 0) "seastar stream binary executable: $SEASTAR_BIN" \
        "seastar stream binary missing/not executable: $SEASTAR_BIN (build it with bench/seastar/build-stream-bench.sh)"
    check $([[ -f $ANALYZER ]] && echo 1 || echo 0) "analyzer present: $ANALYZER" \
        "analyzer missing: $ANALYZER"

    if [[ $strict == 1 ]] && (( fail != 0 )); then
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# manifest.txt
# ---------------------------------------------------------------------------
write_manifest() {
    local govs rd_size wr_size seastar_version
    govs=$(for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort | uniq -c | tr '\n' ' ')
    rd_size=$(stat -c%s "$READ_FILE" 2>/dev/null || echo '?')
    wr_size=$(stat -c%s "$WRITE_FILE" 2>/dev/null || echo '?')
    seastar_version=$(git -C "$REPO_ROOT/SeaStarRef" describe --tags --always --dirty 2>/dev/null ||
        echo 'seastar-25.05.0-1392-g04b1c04d')
    {
        echo "# Stream session manifest -- JUring streams vs seastar fstream"
        echo "# generated: $(date -Iseconds)  host: $(hostname)"
        echo "# workspace: $REPO_ROOT"
        echo
        echo "## Host / kernel"
        uname -a
        echo
        echo "## CPU"
        lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket|Socket|NUMA node|MHz' || true
        echo
        echo "## CPU governor (unique values over all CPUs)"
        echo "    $govs"
        echo "pinned CPU: $CPU"
        echo
        echo "## Memory / hugepages"
        grep -E 'MemTotal|MemAvailable|HugePages_Total|Hugepagesize' /proc/meminfo
        echo
        echo "## Locked-memory limit"
        echo "ulimit -l = $(ulimit -l) KiB"
        echo
        echo "## Datasets and mounts"
        echo "read  dataset: $READ_FILE size=$rd_size"
        echo "write dataset: $WRITE_FILE size=$wr_size"
        echo "read mount:"
        findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$READ_FILE" 2>/dev/null || true
        echo "write mount:"
        findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$WRITE_FILE" 2>/dev/null || true
        df -h "$READ_FILE" "$WRITE_FILE" 2>/dev/null || true
        lsblk -o NAME,MODEL,SERIAL,SIZE,MOUNTPOINTS | grep -E 'nvme|NAME' || true
        echo
        echo "## Kernel I/O controls"
        echo "kernel.io_uring_disabled = $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?')"
        echo
        echo "## Tool versions"
        echo "java: $(java -version 2>&1 | head -1)"
        echo "g++: $(g++ --version 2>/dev/null | head -1)"
        echo "seastar: $seastar_version ($(git -C "$REPO_ROOT/SeaStarRef" rev-parse HEAD 2>/dev/null || echo '?'))"
        echo
        echo "## Repo state"
        echo "git commit: $(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "git describe: $(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
        echo "git status (porcelain):"
        git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -20 || true
        echo
        echo "## Engine binaries and harness (sha256)"
        sha256sum "$SEASTAR_BIN" 2>/dev/null || true
        sha256sum "$JURING_RUN" "$HERE/run-stream-matrix.sh" "$ANALYZER" \
            "$REPO_ROOT/bench/juring/JUringStreamBench.java" 2>/dev/null || true
        echo
        echo "## Protocol (frozen; preliminary)"
        echo "cells: $CELLS"
        echo "protocol: reps=$REPS warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s seed=$SEED qd=$QD cpu=$CPU"
        echo "primary rows: juring O_DIRECT registered file | seastar fstream (open_file_dma, DMA/O_DIRECT)"
        echo "exploratory row: juring --buffered (no O_DIRECT), never used for the >=85% verdict"
        echo "engine order: alternates juring-first / seastar-first per rep; buffered row last"
        echo "engines per rep: one fresh process per invocation, all under taskset -c $CPU"
        echo
        echo "## Disclosed engine asymmetries (each engine's documented best configuration)"
        echo "juring  : io_uring; SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN; entries=max(256,2*qd);"
        echo "          file registered (JuringFile.register()); streams allocate their own aligned buffers"
        echo "          (fixed_buffers=false); bufferSize=bs, readAhead=qd-1 / writeBehind=qd,"
        echo "          alignment=4096, dynamic adjustments disabled; blocking JuringFile.await"
        echo "          completes stream-owned in-flight reads/writes (no future/fiber machinery)."
        echo "seastar : fstream over file_input_stream/file_output_stream; seastar io_uring reactor"
        echo "          backend; stock 200-entry ring, no SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN;"
        echo "          no registered files/buffers; io_queue + futures in the path."
        echo "both    : same file, same O_DIRECT, same bs/qd/dataset, one thread on CPU $CPU,"
        echo "          same per-chunk latency definition and JSON schema."
        echo
        echo "## Session cell plan (order recorded for the alternating engine order)"
        echo "cells-major, reps inner, engines interleaved per rep; baseline rests ${REST}s between reps."
        echo
        echo "started: $(now_utc)"
    } >"$MANIFEST"
}

init_meta() {
    if [[ ! -s $META ]]; then
        printf 'cell\tengine\tvariant\tworkload\tbs\tqd\trep\tstart_utc\tend_utc\twall_s\tloadavg_start\tloadavg_end\tstatus\texit\tops\tmibs\tp99_us\tbytes\terrors\targv\n' >"$META"
    fi
}

# run_row <cell> <engine> <variant> <rep>: one fresh process, log + JSON + meta row.
run_row() {
    local cell=$1 engine=$2 variant=$3 rep=$4
    local wl bs file size id json log argv_str_ cmd_str
    local t0 t1 start end wall la0 la1 rc status summary
    local jstatus='missing' ops='-1' mibs='-1' p99='-1' bytes='-1' errors='-1'

    wl=$(cell_workload "$cell")
    bs=$(cell_bs "$cell")
    file=$(cell_file "$cell")
    size=$(cell_dataset "$cell")
    id="${cell}__${engine}__${variant}__r${rep}"
    json="$CELLS_DIR/$id.json"
    log="$LOGS_DIR/$id.log"
    run_argv "$cell" "$engine" "$variant" "$rep" || return 1
    cmd_str="taskset -c $CPU $(argv_str "${ARGV[@]}")"

    t0=$(date +%s.%N)
    start=$(now_utc)
    la0=$(loadavg)
    rc=0
    if [[ $engine == seastar ]]; then
        LD_LIBRARY_PATH="$SYSROOT_LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            taskset -c "$CPU" "${ARGV[@]}" >"$log" 2>&1 || rc=$?
    else
        taskset -c "$CPU" "${ARGV[@]}" >"$log" 2>&1 || rc=$?
    fi
    t1=$(date +%s.%N)
    end=$(now_utc)
    la1=$(loadavg)
    wall=$(wall_sec "$t0" "$t1")

    status=ok
    if (( rc != 0 )); then status=fail; fi
    if [[ ! -s $json ]]; then
        status=fail
    else
        summary=$(json_summary "$json")
        IFS=$'\t' read -r jstatus ops mibs p99 bytes errors <<<"$summary"
        if [[ $jstatus != ok || $errors != 0 ]]; then status=fail; fi
    fi
    (( rc == 0 )) || echo "warn: cell $id exited $rc (see $log)" >&2
    if [[ $jstatus != ok ]] && [[ -s $json ]]; then
        echo "warn: cell $id JSON status=$jstatus errors=$errors" >&2
    fi

    printf 'cell %-52s status=%-4s wall=%-7ss mibs=%-10s p99_us=%-9s ops=%s\n' \
        "$id" "$status" "$wall" "$mibs" "$p99" "$ops"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$cell" "$engine" "$variant" "$wl" "$bs" "$QD" "$rep" "$start" "$end" "$wall" \
        "$la0" "$la1" "$status" "$rc" "$ops" "$mibs" "$p99" "$bytes" "$errors" "$cmd_str" >>"$META"
    [[ $status == ok ]]
}

# ---------------------------------------------------------------------------
# plan printing (--dry-run)
# ---------------------------------------------------------------------------
print_plan() {
    local cell rep order eng variant rows=0
    echo
    echo "================ STREAM MATRIX PLAN (--dry-run; nothing executed) ================"
    echo "session dir : $RESULTS"
    echo "cells dir   : $CELLS_DIR"
    echo "logs dir    : $LOGS_DIR"
    echo "aggregate   : $AGG_DIR/stream.json, $AGG_DIR/stream.md"
    echo "cells       : $CELLS"
    echo "protocol    : R=$REPS warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s seed=$SEED qd=$QD cpu=$CPU"
    echo "read  file  : $READ_FILE ($(stat -c%s "$READ_FILE" 2>/dev/null || echo '?') B)"
    echo "write file  : $WRITE_FILE ($(stat -c%s "$WRITE_FILE" 2>/dev/null || echo '?') B)"
    echo "juring row  : O_DIRECT + registered file (disclosed: fixed_buffers=false, streams allocate buffers)"
    echo "seastar row : fstream, LD_LIBRARY_PATH=$SYSROOT_LIB"
    echo "extra row   : juring --buffered per cell (exploratory, never used for the >=85% verdict)"
    echo "engine order: rep odd = juring, seastar, juring-buffered; rep even = seastar, juring, juring-buffered"
    echo
    for cell in $CELLS; do
        echo "--- cell $cell: workload=$(cell_workload "$cell") bs=$(cell_bs "$cell") qd=$QD"
        echo "    dataset=$(cell_dataset "$cell") B file=$(cell_file "$cell")"
        for rep in $(seq 1 "$REPS"); do
            order=$(engine_order "$rep")
            echo "    rep $rep/$REPS (order: $order)"
            for eng in $order; do
                if [[ $eng == buffered ]]; then
                    run_argv "$cell" juring buffered "$rep"
                    rows=$((rows + 1))
                    echo "      [$rows] taskset -c $CPU $(argv_str "${ARGV[@]}")"
                else
                    run_argv "$cell" "$eng" std "$rep"
                    rows=$((rows + 1))
                    if [[ $eng == seastar ]]; then
                        echo "      [$rows] LD_LIBRARY_PATH=$SYSROOT_LIB taskset -c $CPU $(argv_str "${ARGV[@]}")"
                    else
                        echo "      [$rows] taskset -c $CPU $(argv_str "${ARGV[@]}")"
                    fi
                fi
            done
        done
        echo "    rests: ${REST}s between successive reps of this cell (after reps 1..$((REPS - 1)))"
    done
    echo
    echo "total process invocations: $rows"
    echo "estimated minimum wall: $(awk -v n="$rows" -v w="$WARMUP" -v s="$DURATION" \
        -v r="$REST" -v c="$(wc -w <<<"$CELLS")" -v reps="$REPS" \
        'BEGIN{printf "%.0f min (bench only; no process start overhead)", (n*(w+s) + c*(reps-1)*r)/60}')"
    echo "after a real run the driver invokes: python3 $ANALYZER --session $SESSION"
    echo "==========================================================================================="
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
echo "[stream-matrix] stream matrix (protocol frozen)"
echo "[stream-matrix] session=$SESSION cells='$CELLS' R=$REPS warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s cpu=$CPU"

if [[ $DRY_RUN == 1 ]]; then
    preflight -1 || true
    print_plan
    exit 0
fi

if [[ -d $CELLS_DIR ]] && compgen -G "$CELLS_DIR/*.json" >/dev/null; then
    if (( FORCE != 1 )); then
        echo "error: session '$SESSION' already contains cell JSONs under $CELLS_DIR" >&2
        echo "       start a new session with --session NAME (e.g. --session stream-matrix-r2)," >&2
        echo "       or pass --force to append to the existing session." >&2
        exit 1
    fi
    echo "[stream-matrix] warning: --force given; appending to existing session $SESSION" >&2
fi

preflight 1 || {
    echo "error: preflight failed; refusing to run the campaign (see FAIL lines above)" >&2
    exit 1
}

mkdir -p "$CELLS_DIR" "$LOGS_DIR" "$AGG_DIR"
write_manifest
init_meta

echo "[stream-matrix] compiling JUring stream runner (JURING_SKIP_MVN=1, no Maven) ..."
if ! JURING_SKIP_MVN=1 bash "$JURING_RUN" --help >/dev/null; then
    echo "error: JUring stream runner compile/--help failed; is the stream API in target/classes?" >&2
    exit 1
fi
echo "[stream-matrix] JUring stream runner ready"
echo "[stream-matrix] manifest: $MANIFEST"
echo "[stream-matrix] started: $(now_utc)"

FAILS=0
for cell in $CELLS; do
    for rep in $(seq 1 "$REPS"); do
        order=$(engine_order "$rep")
        for eng in $order; do
            if [[ $eng == buffered ]]; then
                run_row "$cell" juring buffered "$rep" || FAILS=$((FAILS + 1))
            else
                run_row "$cell" "$eng" std "$rep" || FAILS=$((FAILS + 1))
            fi
        done
        if (( rep < REPS )); then
            echo "[stream-matrix] rest ${REST}s before $cell rep $((rep + 1)) (loadavg=$(loadavg))"
            sleep "$REST"
        fi
    done
done

echo "[stream-matrix] matrix done: $(now_utc) failures=$FAILS"
echo "[stream-matrix] cell-meta: $META"

if [[ -f $ANALYZER ]]; then
    echo "[stream-matrix] analyzing: $ANALYZER --session $SESSION"
    if python3 "$ANALYZER" --session "$SESSION" >"$AGG_DIR/analyze.log" 2>&1; then
        echo "[stream-matrix] aggregate: $AGG_DIR/stream.json, $AGG_DIR/stream.md"
    else
        echo "warn: analyzer failed; see $AGG_DIR/analyze.log" >&2
    fi
fi

if (( FAILS > 0 )); then
    echo "error: $FAILS cell invocation(s) failed; inspect cell-meta.tsv and logs/" >&2
    exit 1
fi
echo "[stream-matrix] ok"
