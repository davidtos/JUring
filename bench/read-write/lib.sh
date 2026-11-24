#!/usr/bin/env bash
# bench/read-write/lib.sh -- shared shell helpers for the read/write measurement session.
#
# Sourced by bench/read-write/run-*.sh; not meant to be executed directly.
#
# Provides:
#   preflight                    gate the machine (dataset, io_uring, governor, load)
#   init_meta                    create cell-meta.tsv header
#   build_engine_cmd             build $MATRIX_CMD for juring / seastar / fio
#   run_cell                     run one cell under taskset, log it, append meta
#   now_utc / loadavg / governor / devstat
#
# Cell identity: <case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>
#   case    canonical cell slug from bench/analysis/cells.py
#   engine  juring | seastar | fio-io_uring | fio-libaio | seastar-io_tester
#   variant std | matched | matchedbatch | default | batch8 | batch16 | batch32 | sqpoll | perf
#
# Every cell leaves cells/<id>.json, logs/<id>.log, and one row in cell-meta.tsv.
# All engine processes are pinned with `taskset -c $MATRIX_CPU` (default CPU 2; the
# same-CPU pinning rule: one thread/shard per engine on the same CPU).

MATRIX_LIB_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
MATRIX_REPO_ROOT=${MATRIX_REPO_ROOT:-$(cd -- "$MATRIX_LIB_DIR/../.." && pwd)}
# shellcheck source=bench/cells.sh
source "$MATRIX_REPO_ROOT/bench/cells.sh"
MATRIX_RESULTS=${MATRIX_RESULTS:-$MATRIX_REPO_ROOT/bench/results/read-write}
MATRIX_RD_FILE=${MATRIX_RD_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
MATRIX_WR_FILE=${MATRIX_WR_FILE:-$MATRIX_REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin}
MATRIX_CPU=${MATRIX_CPU:-2}
MATRIX_DEV=${MATRIX_DEV:-nvme1n1}
MATRIX_SYSROOT=${MATRIX_SYSROOT:-$MATRIX_REPO_ROOT/.sysroot/root/usr}
MATRIX_SEASTAR_BIN=${MATRIX_SEASTAR_BIN:-$MATRIX_REPO_ROOT/bench/seastar/seastar_file_bench}
MATRIX_IOTESTER_BIN=${MATRIX_IOTESTER_BIN:-$MATRIX_REPO_ROOT/SeaStarRef/build/release/apps/io_tester/io_tester}
MATRIX_META=$MATRIX_RESULTS/cell-meta.tsv

export LD_LIBRARY_PATH="$MATRIX_SYSROOT/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export JURING_SKIP_COMPILE=${JURING_SKIP_COMPILE:-1}

mkdir -p "$MATRIX_RESULTS/cells" "$MATRIX_RESULTS/logs" "$MATRIX_RESULTS/perf" "$MATRIX_RESULTS/iotester"

now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
loadavg() { awk '{print $1","$2","$3}' /proc/loadavg; }
governor() { cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown; }
# "reads sectors_read writes sectors_written" for the data device (kernel 7.x stat order)
devstat() { awk '{print $1, $3, $5, $7}' "/sys/block/$MATRIX_DEV/stat"; }

init_meta() {
    if [[ ! -s $MATRIX_META ]]; then
        printf 'case\tengine\tvariant\tbs\tqd\trep\tts_utc\tloadavg_start\tloadavg_end\tread_sectors_delta\twrite_sectors_delta\tops\tiops\tp99_us\twall_s\tstatus\tbytes\n' >"$MATRIX_META"
    fi
}

# append_meta <17 fields in header order>: one cell-meta.tsv row
append_meta() {
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$@" >>"$MATRIX_META"
}

preflight() {
    local fail=0 io_disabled gov la1 foreign
    [[ -f $MATRIX_RD_FILE ]] || { echo "preflight: missing read dataset $MATRIX_RD_FILE" >&2; fail=1; }
    io_disabled=$(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?')
    [[ $io_disabled == 0 ]] || { echo "preflight: io_uring disabled (kernel.io_uring_disabled=$io_disabled)" >&2; fail=1; }
    gov=$(governor)
    [[ $gov == performance ]] || { echo "preflight: CPU governor is '$gov', expected performance" >&2; fail=1; }
    la1=$(awk '{print $1}' /proc/loadavg)
    if ! awk -v x="$la1" 'BEGIN{exit !(x < 1.0)}'; then
        # Our own benchmark processes sit in D state and inflate loadavg, so a
        # chained phase legitimately starts above 1.0. Only an explicit env opt-in
        # relaxes this; the foreign-process check below always stays strict.
        if [[ ${ALLOW_RESIDUAL_LOAD:-0} == 1 ]]; then
            echo "preflight: note loadavg(1m)=$la1 (residual from our own session, allowed)" >&2
        else
            echo "preflight: loadavg(1m)=$la1 >= 1.0" >&2
            fail=1
        fi
    fi
    foreign=$(ps -eo pcpu=,pid=,comm= --sort=-pcpu | awk -v self="$$" '$2 != self && $1 > 20 {printf "%s(pid %s, %.0f%%) ", $3, $2, $1}')
    [[ -z $foreign ]] || { echo "preflight: foreign process >20% CPU: $foreign" >&2; fail=1; }
    ((fail == 0)) || return 1
    echo "preflight: ok governor=$gov loadavg=$(loadavg) device=$MATRIX_DEV"
}

# build_engine_cmd <engine> <workload> <bs> <qd> <secs> <warmup> <id> <json> [variant]
#   engine: juring | seastar | fio-io_uring | fio-libaio
#   workload: randread | randwrite | seqread | seqwrite
#   variant (fio only): matched | regfile | default | batch8 | batch16 | batch32 | sqpoll
# Sets the global array MATRIX_CMD (and MATRIX_FILE for the dataset used).
build_engine_cmd() {
    local engine=$1 workload=$2 bs=$3 qd=$4 secs=$5 warmup=$6 id=$7 json=${8:-} variant=${9:-std}
    local file readonly=1
    case $workload in
        randread | seqread) file=$MATRIX_RD_FILE ;;
        randwrite | seqwrite) file=$MATRIX_WR_FILE readonly=0 ;;
        *) echo "build_engine_cmd: bad workload '$workload'" >&2; return 2 ;;
    esac
    MATRIX_FILE=$file

    MATRIX_CMD=()
    case $engine in
        juring)
            MATRIX_CMD=(bash "$MATRIX_REPO_ROOT/bench/juring/run-juring.sh" \
                --workload "$workload" --file "$file" --bs "$bs" --qd "$qd" \
                --threads 1 --seconds "$secs" --warmup-sec "$warmup" --runs 1 \
                --seed 20261113 --label "$id" --json "$json")
            if ((readonly)); then MATRIX_CMD+=(--readonly); fi
            ;;
        seastar)
            MATRIX_CMD=("$MATRIX_SEASTAR_BIN" --workload "$workload" --file "$file" \
                --bs "$bs" --qd "$qd" --seconds "$secs" --warmup-sec "$warmup" \
                --runs 1 --seed 20261113 --label "$id" --json "$json" \
                --smp 1 --cpuset "$MATRIX_CPU" --memory 1G --lock-memory 0 \
                --reactor-backend io_uring)
            if ((readonly)); then MATRIX_CMD+=(--readonly); fi
            ;;
        fio-io_uring | fio-libaio)
            local fio_rw ioengine=io_uring
            case $workload in
                randread) fio_rw=randread ;;
                seqread) fio_rw=read ;;
                randwrite) fio_rw=randwrite ;;
                seqwrite) fio_rw=write ;;
            esac
            if [[ $engine == fio-libaio ]]; then ioengine=libaio; fi
            MATRIX_CMD=(fio --name="$id" --filename="$file" --direct=1 --rw="$fio_rw" \
                --bs="$bs" --iodepth="$qd" --numjobs=1 --ioengine="$ioengine" \
                --randrepeat=1 --time_based --runtime="$secs" --ramp_time="$warmup" \
                --clocksource=clock_gettime --percentile_list=50:95:99:99.9 \
                --clat_percentiles=1 --lat_percentiles=0 --group_reporting=1 \
                --output-format=json --output="$json")
            if ((readonly)); then MATRIX_CMD+=(--readonly); fi
            case $variant in
                matched) MATRIX_CMD+=(--registerfiles=1 --fixedbufs=1) ;;
                matchedbatch) MATRIX_CMD+=(--registerfiles=1 --fixedbufs=1 --iodepth_batch_submit="$qd") ;;
                regfile) MATRIX_CMD+=(--registerfiles=1) ;;
                default | std | none) ;;
                batch8) MATRIX_CMD+=(--iodepth_batch_submit=8) ;;
                batch16) MATRIX_CMD+=(--iodepth_batch_submit=16) ;;
                batch32) MATRIX_CMD+=(--iodepth_batch_submit=32) ;;
                sqpoll) MATRIX_CMD+=(--sqthread_poll=1) ;;
                *) echo "build_engine_cmd: unknown fio variant '$variant'" >&2; return 2 ;;
            esac
            ;;
        *)
            echo "build_engine_cmd: unknown engine '$engine'" >&2
            return 2
            ;;
    esac
}

# run_cell <case> <engine> <variant> <bs> <qd> <rep> <log> <json> [--] <command...>
# Runs under taskset, captures stdout+stderr to <log>, records device-sector
# deltas / loadavg before-after, and appends one row to cell-meta.tsv.
# Never aborts the caller: a failed engine run is recorded (status=fail) and the
# function returns 1.
run_cell() {
    local case_id=$1 engine=$2 variant=$3 bs=$4 qd=$5 rep=$6 logf=$7 jsonf=$8
    shift 8
    if [[ ${1:-} == -- ]]; then shift; fi
    local id="${case_id}__${engine}__${variant}__bs${bs}__qd${qd}__t1__r${rep}"
    local rs0 rsec0 ws0 wsec0 rs1 rsec1 ws1 wsec1 la0 la1
    read -r rs0 rsec0 ws0 wsec0 <<<"$(devstat)"
    la0=$(loadavg)
    local t0 t1 wall status=ok
    t0=$(date +%s.%N)
    if ! taskset -c "$MATRIX_CPU" "$@" >"$logf" 2>>"$logf"; then
        status=fail
    fi
    t1=$(date +%s.%N)
    wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.3f", b-a}')
    read -r rs1 rsec1 ws1 wsec1 <<<"$(devstat)"
    la1=$(loadavg)
    [[ -s $jsonf ]] || status=fail
    local ops iops p99 bytes summary
    summary=$(python3 "$MATRIX_LIB_DIR/json_summary.py" "$engine" "$jsonf" 2>/dev/null || echo '-1 -1 -1 -1')
    read -r ops iops p99 bytes <<<"$summary"
    printf 'cell %-62s iops=%-10s p99_us=%-9s wall=%-6ss %s\n' \
        "$id" "$iops" "$p99" "$wall" "$status"
    append_meta "$case_id" "$engine" "$variant" "$bs" "$qd" "$rep" "$(now_utc)" \
        "$la0" "$la1" "$((rsec1 - rsec0))" "$((wsec1 - wsec0))" \
        "$ops" "$iops" "$p99" "$wall" "$status" "$bytes"
    [[ $status == ok ]]
}
