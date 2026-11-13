#!/usr/bin/env bash
#
# bench/m1/run-iotester-check.sh -- cross-check seastar's io_tester against our
# seastar_file_bench on the same device/workload.
#
# This became possible when nvme-data/ was made writable (bind mount of the data
# NVMe /dev/nvme1n1p1): io_tester creates and prefills one file per shard under
# --storage. With --keep-files=1 the first (setup) run fills the 10 GiB file and
# the measured reps re-use it, so they are not measuring the fill.
#
# IMPORTANT: the setup fill writes 10 GiB to the SAME device as the read dataset.
# Run this AFTER the read throughput/latency cells, not between them.
#
# Protocol: --smp 1 --cpuset 2, io_uring backend, 10 s warmup is not a thing in
# io_tester (it has no warmup option); measured 30 s, R=3, interleaved.
# The same spec is also run against /dev/null (framework-overhead probe, W24)
# and, for a sanity bracket, against the seastar_file_bench on the existing file.
#
# Env overrides: DURATION REPS QDS M1_RESULTS M1_CPU STORAGE KEEP_STORAGE
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/m1/lib.sh
source "$HERE/lib.sh"
cd "$M1_REPO_ROOT"

DURATION=${DURATION:-30}
REPS=${REPS:-3}
QDS=${QDS:-"1 64"}
STORAGE=${STORAGE:-$M1_REPO_ROOT/nvme-data/iotester-storage}
KEEP_STORAGE=${KEEP_STORAGE:-0}
SETUP_DURATION=${SETUP_DURATION:-3}
IOT_TSV=$M1_RESULTS/iotester-check.tsv

mkdir -p "$STORAGE"
init_tsv() {
    if [[ ! -s $IOT_TSV ]]; then
        printf 'case\tengine\tvariant\tqd\trep\tts_utc\tloadavg_start\tloadavg_end\tread_sectors_delta\twrite_sectors_delta\tiops\tthroughput_kbs\tp50_us\tp99_us\tp999_us\tmax_us\twall_s\tstatus\n' >"$IOT_TSV"
    fi
}

case_for_qd() {
    case $1 in
        1) echo W1 ;;
        16) echo W2 ;;
        64) echo W3 ;;
        128) echo W4 ;;
        *) echo "W?qd$1" ;;
    esac
}

# run_iotester <qd> <variant> <label> <rep> <duration> [storage]
run_iotester() {
    local qd=$1 variant=$2 label=$3 rep=$4 dur=$5 storage=${6:-$STORAGE}
    local conf="$M1_REPO_ROOT/bench/seastar/conf/randread_4k_qd${qd}.yaml"
    local out="$M1_RESULTS/iotester/${label}.yaml" log="$M1_RESULTS/logs/${label}.log"
    local rs0 rsec0 ws0 wsec0 rs1 rsec1 ws1 wsec1 la0 la1 t0 t1 wall status=ok
    read -r rs0 rsec0 ws0 wsec0 <<<"$(devstat)"
    la0=$(loadavg)
    t0=$(date +%s.%N)
    if ! LD_LIBRARY_PATH="$M1_SYSROOT/lib64" taskset -c "$M1_CPU" "$M1_IOTESTER_BIN" \
        --storage "$storage" --conf "$conf" --duration "$dur" --smp 1 \
        --cpuset "$M1_CPU" --memory 1G --lock-memory 0 \
        --reactor-backend io_uring --keep-files=1 >"$out" 2>"$log"; then
        status=fail
    fi
    t1=$(date +%s.%N)
    wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.3f", b-a}')
    read -r rs1 rsec1 ws1 wsec1 <<<"$(devstat)"
    la1=$(loadavg)
    local stats
    stats=$(python3 - "$out" <<'PY' 2>/dev/null || echo '-1 -1 -1 -1 -1 -1'
import sys, re
try:
    txt = open(sys.argv[1]).read()
except Exception:
    print('-1 -1 -1 -1 -1 -1'); raise SystemExit(0)
def get(pat):
    m = re.search(pat, txt)
    return m.group(1) if m else '-1'
iops = get(r'IOPS:\s*([0-9.]+)')
thr = get(r'throughput:\s*([0-9.]+)')
p50 = get(r'p0\.5:\s*([0-9.]+)')
p99 = get(r'p0\.99:\s*([0-9.]+)')
p999 = get(r'p0\.999:\s*([0-9.]+)')
mx = get(r'max:\s*([0-9.]+)')
print(iops, thr, p50, p99, p999, mx)
PY
)
    read -r iops thr p50 p99 p999 mx <<<"$stats"
    local case_id; case_id=$(case_for_qd "$qd")
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$case_id" "seastar-io_tester" "$variant" "$qd" "$rep" "$(now_utc)" \
        "$la0" "$la1" "$((rsec1 - rsec0))" "$((wsec1 - wsec0))" \
        "$iops" "$thr" "$p50" "$p99" "$p999" "$mx" "$wall" "$status" >>"$IOT_TSV"
    printf 'iotester %-58s iops=%-10s p99_us=%-9s wall=%-6ss %s\n' "$label" "$iops" "$p99" "$wall" "$status"
    [[ $status == ok ]]
}

preflight
init_tsv
echo "io_tester cross-check: duration=${DURATION}s reps=${REPS} qds='${QDS}' storage=$STORAGE"
echo "started=$(now_utc)"
for qd in $QDS; do
    # one setup run per qd: truncate+prefill the shard file, then measured reps reuse it
    run_iotester "$qd" setup "setup_qd${qd}" 0 "$SETUP_DURATION" ||
        echo "warn: io_tester setup failed (qd=$qd)" >&2
    for rep in $(seq 1 "$REPS"); do
        run_iotester "$qd" std "W$([ "$qd" = 1 ] && echo 1 || echo 3)__seastar-io_tester__std__bs4k__qd${qd}__t1__r${rep}" \
            "$rep" "$DURATION" || echo "warn: io_tester rep failed (qd=$qd rep=$rep)" >&2
    done
done

# framework-overhead probe on /dev/null (W24 reference; no storage I/O)
if [[ ${WITH_DEV_NULL:-1} == 1 ]]; then
    run_iotester 64 devnull "W24__seastar-io_tester__devnull__bs4k__qd64__t1__r1" 1 10 /dev/null ||
        echo "warn: /dev/null probe failed" >&2
fi

if [[ $KEEP_STORAGE != 1 ]]; then
    echo "removing storage test files under $STORAGE (KEEP_STORAGE=1 to keep)"
    rm -f "$STORAGE"/test-* 2>/dev/null || true
fi
echo "io_tester cross-check done: $(now_utc)"
