#!/usr/bin/env bash
#
# run-comparison.sh - drive the JUring vs seastar (vs fio) comparison matrix.
#
# What it does, per case:
#   1. JUring  : bench/juring/run-juring.sh  -> RESULTS_DIR/raw/juring/<case>.json
#   2. fio     : if `fio` is on PATH         -> RESULTS_DIR/raw/fio/<case>.json
#   3. seastar : only if SEASTAR_BIN is set  -> RESULTS_DIR/raw/seastar/<case>.yaml
# Then writes RESULTS_DIR/manifest.txt and calls bench/parse_results.py to
# produce RESULTS_DIR/results.csv + a table on stdout.
#
# Everything this script writes goes below RESULTS_DIR (default ./bench-results).
# The only exception is the one-time compile step, which necessarily touches
# target/ and bench/juring/out/ so that the Java harness can run at all.
#
# Usage (from the repo root; other cwds are fine too):
#   bench/run-comparison.sh
#   RESULTS_DIR=/tmp/my-run MATRIX=default bench/run-comparison.sh
#   SEASTAR_BIN=/opt/seastar/build/release/apps/io_tester/io_tester \
#   DATA_DIR=/mnt/nvme/seastar-bench bench/run-comparison.sh
#
# Environment:
#   JURING_FILE         read-only 10GiB file for read cases
#                       (default /mnt/TestData/read_files/single_file_bench.bin)
#   JURING_WRITE_FILE   writable file for write cases; write cases are skipped
#                       when unset (e.g. when JURING_FILE is on a read-only mount)
#   DATA_DIR            writable directory for seastar io_tester storage, on the
#                       device under test (default nvme-data/iotester-storage).
#                       io_tester creates and prefills its own 10 GiB files there;
#                       point it at the data NVMe, not the repo volume.
#   RESULTS_DIR         output directory (default ./bench-results)
#   MATRIX              default|read|small|all or a space/comma separated case list
#                       cases: randread_4k_qd1 randread_4k_qd64 randread_4k_qd128
#                              seqread_1m_qd8 randwrite_4k_qd64 seqwrite_1m_qd8
#                              nop_qd64
#   SEASTAR_BIN         path to apps/io_tester/io_tester; absent => seastar skipped
#   SEASTAR_CONF_DIR    default <repo>/bench/seastar/conf
#   SEASTAR_REACTOR_BACKEND  default io_uring
#   SEASTAR_SMP         default 1
#   SEASTAR_CPUSET      default empty (seastar picks)
#   SEASTAR_IO_PROPS    io-properties file. Empty by default: Seastar probes the
#                       device. Only pass a file measured on THIS host whose
#                       mountpoint backs DATA_DIR (see bench/seastar/build-and-run.md).
#   SEASTAR_OVERPROVISIONED=1  add --overprovisioned
#   SEASTAR_SECONDS     default 10
#   JURING_SECONDS      default 10
#   JURING_WARMUP_SEC   default 1
#   JURING_RUNS         default 1
#   JURING_CPUS         optional taskset CPU list for the JUring, fio and Seastar
#                       processes (e.g. 2)
#   FIO_SECONDS         default 10
#   FIO_WARMUP_SEC      default JURING_WARMUP_SEC (fio --ramp_time)
#   FIO_BIN             default fio
#
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
cd "$REPO_ROOT"

JURING_FILE=${JURING_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
JURING_WRITE_FILE=${JURING_WRITE_FILE:-}
# io_tester creates and prefills its own files, so its storage must be on the
# device under test. The repo checkout is usually on the OS disk (on this host
# it is btrfs on nvme0n1), so defaulting DATA_DIR under the repo benchmarks the
# wrong device silently. nvme-data/ is the writable mount of the data NVMe.
DATA_DIR=${DATA_DIR:-$REPO_ROOT/nvme-data/iotester-storage}
RESULTS_DIR=${RESULTS_DIR:-$REPO_ROOT/bench-results}
MATRIX=${MATRIX:-default}
SEASTAR_BIN=${SEASTAR_BIN:-}
SEASTAR_CONF_DIR=${SEASTAR_CONF_DIR:-$REPO_ROOT/bench/seastar/conf}
SEASTAR_REACTOR_BACKEND=${SEASTAR_REACTOR_BACKEND:-io_uring}
SEASTAR_SMP=${SEASTAR_SMP:-1}
SEASTAR_CPUSET=${SEASTAR_CPUSET:-}
# Empty by default. The shipped example file has placeholder numbers and an
# example mountpoint; passing it unchecked either aborts Seastar at startup
# (mountpoint missing) or installs a fictional I/O scheduler model.
SEASTAR_IO_PROPS=${SEASTAR_IO_PROPS:-}
SEASTAR_SECONDS=${SEASTAR_SECONDS:-10}
JURING_SECONDS=${JURING_SECONDS:-10}
JURING_WARMUP_SEC=${JURING_WARMUP_SEC:-1}
JURING_RUNS=${JURING_RUNS:-1}
JURING_CPUS=${JURING_CPUS:-}
FIO_BIN=${FIO_BIN:-fio}
FIO_SECONDS=${FIO_SECONDS:-10}
FIO_WARMUP_SEC=${FIO_WARMUP_SEC:-$JURING_WARMUP_SEC}

# The prebuilt io_tester links against the repo sysroot (libyaml-cpp, boost).
if [[ -n "$SEASTAR_BIN" ]]; then
    export LD_LIBRARY_PATH="$REPO_ROOT/.sysroot/root/usr/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

usage() {
    sed -n '2,60p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

# ---------------------------------------------------------------- case matrix
case_meta() {
    # $1 = case name; prints: <workload> <bs> <qd> <threads> <seastar-conf> <fio-rw>
    case "$1" in
        randread_4k_qd1)    echo "randread  4k  1   1 randread_4k_qd1.yaml   randread" ;;
        randread_4k_qd64)   echo "randread  4k  64  1 randread_4k_qd64.yaml  randread" ;;
        randread_4k_qd128)  echo "randread  4k  128 1 randread_4k_qd128.yaml randread" ;;
        seqread_1m_qd8)     echo "seqread   1m  8   1 seqread_1m_qd8.yaml    read" ;;
        randwrite_4k_qd64)  echo "randwrite 4k  64  1 randwrite_4k_qd64.yaml randwrite" ;;
        seqwrite_1m_qd8)    echo "seqwrite  1m  8   1 seqwrite_1m_qd8.yaml   write" ;;
        nop_qd64)           echo "nop       -   64  1 -                      -" ;;
        *) return 1 ;;
    esac
}

READ_CASES="randread_4k_qd1 randread_4k_qd64 randread_4k_qd128 seqread_1m_qd8 nop_qd64"
WRITE_CASES="randwrite_4k_qd64 seqwrite_1m_qd8"

parse_matrix() {
    local m
    m=$(printf '%s' "$1" | tr ',' ' ')
    case "$1" in
        default|read|small|"") printf '%s\n' $READ_CASES ;;
        all)                   printf '%s\n' $READ_CASES $WRITE_CASES ;;
        *)
            local c
            for c in $m; do
                if ! case_meta "$c" >/dev/null; then
                    echo "error: unknown MATRIX case '$c'" >&2
                    exit 2
                fi
                printf '%s\n' "$c"
            done
            ;;
    esac
}

CASES=$(parse_matrix "$MATRIX")

# ---------------------------------------------------------------- directory/setup
mkdir -p "$RESULTS_DIR/raw/juring" "$RESULTS_DIR/raw/fio" "$RESULTS_DIR/raw/seastar" "$RESULTS_DIR/logs"

FAILURES=0
SKIPS=0

note() { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }

# size_to_bytes 4k|128k|1m|4096 -> bytes (the harness only uses k/m suffixes).
size_to_bytes() {
    local s=$1 n
    n=${s%[kKmMgG]}
    case ${s: -1} in
        k|K) echo $(( n * 1024 )) ;;
        m|M) echo $(( n * 1024 * 1024 )) ;;
        g|G) echo $(( n * 1024 * 1024 * 1024 )) ;;
        *)   echo "$n" ;;
    esac
}

# One-time compile for the JUring harness (the only write outside RESULTS_DIR;
# required to build the benchmark binary).  On a warm tree this is ~2-4 s.
note "== compiling JUring harness (mvn -o compile + javac) =="
if ! ./bench/juring/run-juring.sh --help >/dev/null 2>"$RESULTS_DIR/logs/juring-compile.log"; then
    warn "JUring harness compile failed; see $RESULTS_DIR/logs/juring-compile.log"
    FAILURES=$((FAILURES + 1))
fi
export JURING_SKIP_COMPILE=1

# ---------------------------------------------------------------- manifest
{
    echo "# comparison run manifest"
    echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "hostname=$(cat /proc/sys/kernel/hostname 2>/dev/null || uname -n)"
    echo "kernel=$(uname -r)"
    echo "machine=$(uname -m)"
    echo "cpu=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')"
    echo "cpus=$(nproc)"
    echo "juring_git_commit=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "juring_git_dirty=$(if [[ -n "$(git status --porcelain 2>/dev/null | head -n1)" ]]; then echo true; else echo false; fi)"
    echo "jdk=$(java -version 2>&1 | head -n1)"
    if [[ -d /home/david/projects/seastar ]]; then
        echo "seastar_git_commit=$(git -C /home/david/projects/seastar rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "seastar_git_version=$(git -C /home/david/projects/seastar describe --tags --always 2>/dev/null || echo unknown)"
    fi
    echo "fio=$("$FIO_BIN" --version 2>/dev/null || echo not-found)"
    echo "seastar_bin=${SEASTAR_BIN:-<unset>}"
    echo "seastar_reactor_backend=$SEASTAR_REACTOR_BACKEND"
    echo "seastar_smp=$SEASTAR_SMP"
    echo "seastar_cpuset=${SEASTAR_CPUSET:-<unset>}"
    echo "seastar_io_props=${SEASTAR_IO_PROPS}"
    echo "juring_file=$JURING_FILE"
    echo "juring_write_file=${JURING_WRITE_FILE:-<unset>}"
    echo "dataset_devno=$(stat -c %d "$JURING_FILE" 2>/dev/null || echo unknown)"
    echo "data_dir=$DATA_DIR"
    echo "data_dir_fstype=$(stat -f -c %T "$DATA_DIR" 2>/dev/null || echo unknown)"
    echo "data_dir_devno=$(stat -c %d "$DATA_DIR" 2>/dev/null || echo unknown)"
    echo "results_dir=$RESULTS_DIR"
    echo "matrix=$MATRIX"
    echo "juring_seconds=$JURING_SECONDS warmup=$JURING_WARMUP_SEC runs=$JURING_RUNS"
    echo "fio_seconds=$FIO_SECONDS fio_warmup_sec=$FIO_WARMUP_SEC"
    echo "seastar_seconds=$SEASTAR_SECONDS"
    echo "cpu_governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)"
    echo "loadavg_at_start=$(cat /proc/loadavg)"
    echo "nproc_online=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown)"
} > "$RESULTS_DIR/manifest.txt"

note "== comparison run: RESULTS_DIR=$RESULTS_DIR MATRIX=$MATRIX =="
note "   juring_file=$JURING_FILE"
note "   data_dir=$DATA_DIR seastar_bin=${SEASTAR_BIN:-<unset>}"

# ---------------------------------------------------------------- runners
run_juring() {
    local case="$1" wl="$2" bs="$3" qd="$4" thr="$5"
    local out="$RESULTS_DIR/raw/juring/$case.json"
    local log="$RESULTS_DIR/logs/juring-$case.log"
    local -a cmd=(./bench/juring/run-juring.sh
        --workload "$wl" --bs "$bs" --qd "$qd" --threads "$thr"
        --seconds "$JURING_SECONDS" --warmup-sec "$JURING_WARMUP_SEC"
        --runs "$JURING_RUNS" --label "$case")
    if [[ "$wl" == "nop" ]]; then
        : # nop needs no file; --bs/--qd carried above
    elif [[ "$wl" == "randread" || "$wl" == "seqread" ]]; then
        if [[ ! -r "$JURING_FILE" ]]; then warn "juring $case: read file not readable: $JURING_FILE"; SKIPS=$((SKIPS+1)); return; fi
        cmd+=(--file "$JURING_FILE" --readonly)
    else
        if [[ -z "$JURING_WRITE_FILE" || ! -w "$JURING_WRITE_FILE" ]]; then
            warn "juring $case: JURING_WRITE_FILE unset/not writable; skipping write case"
            SKIPS=$((SKIPS+1)); return
        fi
        cmd+=(--file "$JURING_WRITE_FILE" --size 10g)
    fi
    note "== [juring] $case (${JURING_SECONDS}s) =="
    local status=0
    if [[ -n "$JURING_CPUS" ]] && command -v taskset >/dev/null 2>&1; then
        taskset -c "$JURING_CPUS" "${cmd[@]}" >"$out" 2>"$log" || status=$?
    else
        "${cmd[@]}" >"$out" 2>"$log" || status=$?
    fi
    if [[ $status -ne 0 ]]; then
        warn "juring $case failed (exit $status); see $log"
        FAILURES=$((FAILURES + 1))
    else
        note "   -> $out"
    fi
}

run_fio() {
    local case="$1" bs="$2" qd="$3" thr="$4" rw="$5"
    local out="$RESULTS_DIR/raw/fio/$case.json"
    local log="$RESULTS_DIR/logs/fio-$case.log"
    if ! command -v "$FIO_BIN" >/dev/null 2>&1; then
        warn "fio not found ($FIO_BIN); skipping fio cases"
        return
    fi
    local file readonly=0
    if [[ "$rw" == "randread" || "$rw" == "read" ]]; then
        file="$JURING_FILE"; readonly=1
    else
        file="$JURING_WRITE_FILE"
    fi
    if (( readonly )); then
        if [[ ! -r "$file" ]]; then warn "fio $case: file not readable: $file"; SKIPS=$((SKIPS+1)); return; fi
    else
        if [[ -z "$file" || ! -w "$file" ]]; then
            warn "fio $case: write file unset/not writable: ${file:-<unset>}; skipping write case"
            SKIPS=$((SKIPS+1)); return
        fi
    fi
    # fio registers bs * iodepth * numjobs of pinned memory for --fixedbufs, and
    # registration over RLIMIT_MEMLOCK makes fio exit 1 with error=12 and no
    # message. Use the same 75 % rule as JUring's Buffers.decideFixed: keep fixed
    # buffers while the registration fits, otherwise register the file only.
    local bs_bytes memlock_bytes memlock_kb
    bs_bytes=$(size_to_bytes "$bs")
    memlock_kb=$(ulimit -l)
    if [[ "$memlock_kb" == "unlimited" ]]; then
        memlock_bytes=$(( 1 << 40 ))
    else
        memlock_bytes=$(( memlock_kb * 1024 ))
    fi
    local -a reg=(--registerfiles=1)
    if (( (qd * thr * bs_bytes + 131072) * 4 <= memlock_bytes * 3 )); then
        reg+=(--fixedbufs=1)
    fi
    # Registration-matched fio reference (same fixed-file/fixed-buffer config as
    # JUring, same ramp as JUring's warmup, same monotonic clock).
    local -a cmd=("$FIO_BIN" --name="$case" --filename="$file" --ioengine=io_uring --direct=1
        "${reg[@]}"
        --rw="$rw" --bs="$bs" --iodepth="$qd" --numjobs="$thr"
        --runtime="$FIO_SECONDS" --ramp_time="$FIO_WARMUP_SEC" --time_based
        --clocksource=clock_gettime --randrepeat=1
        --percentile_list=50:95:99:99.9 --clat_percentiles=1 --lat_percentiles=0
        --group_reporting=1 --output-format=json --output="$out")
    if (( readonly )); then cmd+=(--readonly); else cmd+=(--size 10g); fi
    note "== [fio]    $case (${FIO_SECONDS}s + ${FIO_WARMUP_SEC}s ramp) =="
    local status=0
    if [[ -n "$JURING_CPUS" ]] && command -v taskset >/dev/null 2>&1; then
        taskset -c "$JURING_CPUS" "${cmd[@]}" >/dev/null 2>"$log" || status=$?
    else
        "${cmd[@]}" >/dev/null 2>"$log" || status=$?
    fi
    if [[ $status -eq 0 && -s "$out" ]]; then
        note "   -> $out"
    else
        warn "fio $case failed (exit $status); see $log"
        FAILURES=$((FAILURES + 1))
    fi
}

run_seastar() {
    local case="$1" conf="$2"
    local out="$RESULTS_DIR/raw/seastar/$case.yaml"
    local log="$RESULTS_DIR/logs/seastar-$case.log"
    if [[ -z "$SEASTAR_BIN" ]]; then return; fi
    if [[ ! -x "$SEASTAR_BIN" ]]; then warn "seastar: SEASTAR_BIN not executable: $SEASTAR_BIN"; FAILURES=$((FAILURES+1)); return; fi
    mkdir -p "$DATA_DIR"
    if [[ ! -w "$DATA_DIR" ]]; then warn "seastar $case: DATA_DIR not writable: $DATA_DIR"; SKIPS=$((SKIPS+1)); return; fi
    # io_tester generates its own files under --storage; if that directory is not
    # on the dataset's device, the Seastar row is not comparable to JUring/fio.
    local storage_dev dataset_dev
    storage_dev=$(stat -c %d "$DATA_DIR" 2>/dev/null || echo unknown)
    dataset_dev=$(stat -c %d "$JURING_FILE" 2>/dev/null || echo unknown)
    if [[ "$storage_dev" != "unknown" && "$dataset_dev" != "unknown" && "$storage_dev" != "$dataset_dev" ]]; then
        warn "seastar $case: DATA_DIR ($DATA_DIR) is on a different device than the dataset; the result is not comparable"
    fi
    local -a cmd=("$SEASTAR_BIN" --storage "$DATA_DIR" --conf "$SEASTAR_CONF_DIR/$conf"
        --duration "$SEASTAR_SECONDS" --smp "$SEASTAR_SMP"
        --reactor-backend "$SEASTAR_REACTOR_BACKEND" --keep-files true)
    if [[ -n "$SEASTAR_CPUSET" ]]; then cmd+=(--cpuset "$SEASTAR_CPUSET"); fi
    # Only a host-measured file is useful; the shipped example is a template.
    if [[ -n "$SEASTAR_IO_PROPS" && -f "$SEASTAR_IO_PROPS" ]]; then cmd+=(--io-properties-file "$SEASTAR_IO_PROPS"); fi
    if [[ "${SEASTAR_OVERPROVISIONED:-0}" == "1" ]]; then cmd+=(--overprovisioned); fi
    note "== [seastar] $case (${SEASTAR_SECONDS}s, backend=$SEASTAR_REACTOR_BACKEND) =="
    if "${cmd[@]}" >"$out" 2>"$log"; then
        note "   -> $out"
    else
        warn "seastar $case failed; see $log"
        FAILURES=$((FAILURES + 1))
    fi
}

# ---------------------------------------------------------------- main loop
for case in $CASES; do
    read -r workload bs qd thr conf rw <<< "$(case_meta "$case")"
    if [[ "$workload" == "nop" ]]; then
        run_juring "$case" "nop" 4096 64 1
        note "   (fio/seastar have no nop workstream: juring only)"
        continue
    fi
    run_juring "$case" "$workload" "$bs" "$qd" "$thr"
    run_fio "$case" "$bs" "$qd" "$thr" "$rw"
    run_seastar "$case" "$conf"
done

# ---------------------------------------------------------------- report
note ""
note "== parsing results =="
if python3 bench/parse_results.py --dir "$RESULTS_DIR" --csv "$RESULTS_DIR/results.csv"; then
    :
else
    warn "parse_results.py found no complete results (see $RESULTS_DIR/logs)"
    FAILURES=$((FAILURES + 1))
fi

{
    echo "loadavg_at_end=$(cat /proc/loadavg)"
    echo "failures=$FAILURES"
    echo "skips=$SKIPS"
} >> "$RESULTS_DIR/manifest.txt"

note ""
note "results:  $RESULTS_DIR/results.csv"
note "raw:      $RESULTS_DIR/raw/{juring,fio,seastar}/"
note "logs:     $RESULTS_DIR/logs/"
note "manifest: $RESULTS_DIR/manifest.txt"
if [[ $FAILURES -ne 0 ]]; then
    warn "$FAILURES case(s) failed, $SKIPS skipped"
    exit 1
fi
note "done: 0 failures, $SKIPS skipped"
