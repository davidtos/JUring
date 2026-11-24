#!/usr/bin/env bash
# run-host-setup.sh -- host representativeness campaign runner.
#
# FROZEN PROTOCOL: fixed before the campaign (host rules in docs/benchmark-methodology.md).
# Do not edit after the campaign starts; record changes as amendments.
#
# Two blocks, run in one session:
#   A: seastar THP vs seastar hugetlbfs bracket.
#            4 KiB random read at QD 64 and QD 128, 1 MiB sequential read at
#            QD 8 and QD 32, and the 8-shard 4 KiB random-read cell.
#            R=3, 10 s warmup + 30 s measured, arms interleaved per rep.
#   B:   memlock best-config read cells, engines interleaved per rep.
#            1 MiB sequential read at QD 1/8/32, juring + fio matched + seastar,
#            R=5. MUST be launched with `prlimit --memlock=unlimited:unlimited`.
#
# Usage (as user david, inside prlimit):
#   bench/host-setup/run-host-setup.sh --block all
#   bench/host-setup/run-host-setup.sh --block a --dry-run
#   bench/host-setup/run-host-setup.sh --block b --reps 5
#
# Output: <results>/manifest-run<N>.txt, cell-meta.tsv, cells/*.json, logs/*.log
#         Default results dir: bench/results/host-setup/
#
# Read-only: only the aged 10 GiB read dataset is opened (--readonly).
set -uo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
cd "$REPO"

RESULTS=${HOST_SETUP_RESULTS:-$REPO/bench/results/host-setup}
RD_FILE=${HOST_SETUP_RD_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
JURING_RUN=$REPO/bench/juring/run-juring.sh
SEASTAR_BIN=$REPO/bench/seastar/seastar_file_bench
JAVA_BIN=${JAVA_BIN:-/home/david/.jdks/openjdk-26.0.2/bin/java}
JAVA_HOME=${JAVA_HOME:-/home/david/.jdks/openjdk-26.0.2}
HP_MOUNT=${HP_MOUNT:-/mnt/hugepages-juring}
CPU=${HOST_SETUP_CPU:-2}
HCTX="1,2,4,6,8,10,12,14"
WARMUP=10
DURATION=30
REPS_A=3
REPS_B=5
SEED=20261113
BLOCK=all
DRY=0

while (( $# )); do
    case $1 in
        --block) BLOCK=$2; shift 2 ;;
        --reps) REPS_A=$2; REPS_B=$2; shift 2 ;;
        --warmup) WARMUP=$2; shift 2 ;;
        --duration) DURATION=$2; shift 2 ;;
        --results) RESULTS=$2; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
case $BLOCK in a|b|all) ;; *) echo "bad --block '$BLOCK'" >&2; exit 2 ;; esac

export LD_LIBRARY_PATH="$REPO/.sysroot/root/usr/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export JURING_SKIP_COMPILE=1

CELLS=$RESULTS/cells
LOGS=$RESULTS/logs
META=$RESULTS/cell-meta.tsv
mkdir -p "$CELLS" "$LOGS" "$RESULTS/analysis"

now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
loadavg() { awk '{print $1","$2","$3}' /proc/loadavg; }
la1() { awk '{print $1}' /proc/loadavg; }
governor() { cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown; }
huge_free() { awk '/HugePages_Free/{print $2}' /proc/meminfo 2>/dev/null || echo -1; }
huge_total() { awk '/HugePages_Total/{print $2}' /proc/meminfo 2>/dev/null || echo -1; }
memlock_soft() { ulimit -l 2>/dev/null || echo unknown; }
nvme_temp() {
    local f
    for f in /sys/class/nvme/nvme1/device/hwmon/hwmon*/temp1_input \
             /sys/class/nvme/nvme1/hwmon*/temp1_input; do
        [[ -r $f ]] && { awk '{printf "%.1f", $1/1000}' "$f"; return; }
    done
    echo '-1'
}
die() { echo "FATAL: $*" >&2; exit 3; }

if [[ ! -s $META ]]; then
    printf 'case\tengine\tvariant\tbs\tqd\trep\tts_utc\tloadavg_start\tloadavg_end\tread_sectors_delta\twrite_sectors_delta\tops\tiops\tp99_us\twall_s\tstatus\tbytes\thugepages_free_start\thugepages_free_end\ttemp_c_start\ttemp_c_end\n' >"$META"
fi

# ---------------------------------------------------------------- preflight
preflight() {
    local fail=0
    [[ -f $RD_FILE ]] || { echo "preflight: missing read dataset $RD_FILE" >&2; fail=1; }
    [[ $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?') == 0 ]] || { echo "preflight: io_uring disabled" >&2; fail=1; }
    [[ $(governor) == performance ]] || { echo "preflight: governor is '$(governor)'" >&2; fail=1; }
    [[ -x $SEASTAR_BIN ]] || { echo "preflight: missing seastar binary" >&2; fail=1; }
    [[ -x $JAVA_BIN ]] || { echo "preflight: missing JDK at $JAVA_BIN" >&2; fail=1; }
    if ! "$JAVA_BIN" -version 2>&1 | grep -q 'version "26\.0\.2"'; then
        echo "preflight: $JAVA_BIN is not OpenJDK 26.0.2 (campaign JDK)" >&2; fail=1
    fi
    if [[ $BLOCK != b ]]; then
        [[ -d $HP_MOUNT ]] || { echo "preflight: missing hugetlbfs mount $HP_MOUNT" >&2; fail=1; }
        touch "$HP_MOUNT/.host-setup-probe" 2>/dev/null && rm -f "$HP_MOUNT/.host-setup-probe" \
            || { echo "preflight: $HP_MOUNT not writable by $(id -un)" >&2; fail=1; }
        (( $(huge_total) >= 4608 )) || { echo "preflight: HugePages_Total=$(huge_total) < 4608; run: sudo sysctl -w vm.nr_hugepages=4608" >&2; fail=1; }
    fi
    if [[ $BLOCK != a ]]; then
        [[ $(memlock_soft) == unlimited ]] || {
            echo "preflight: memlock soft limit is '$(memlock_soft)', block B needs unlimited." >&2
            echo "  launch via: sudo prlimit --memlock=unlimited:unlimited -- runuser -u david -- ..." >&2
            fail=1
        }
    fi
    local l
    l=$(la1)
    if ! awk -v x="$l" 'BEGIN{exit !(x < 1.6)}'; then
        echo "preflight: loadavg(1m)=$l >= 1.6 (background-load gate); wait for the machine to go quiet" >&2; fail=1
    fi
    (( fail == 0 )) || die "preflight failed"
    echo "preflight: ok (user=$(id -un) jdk=$("$JAVA_BIN" -version 2>&1 | head -1) memlock=$(memlock_soft) huge_free=$(huge_free) mount=$HP_MOUNT)"
}

manifest() {
    local f
    f="$RESULTS/manifest-run$(date -u +%Y%m%dT%H%M%SZ).txt"
    {
        echo "# Host-representativeness campaign manifest (protocol frozen)"
        echo "# generated: $(now_utc)  host: $(hostname)"
        echo "# user: $(id -un)  uid=$(id -u)"
        echo "# kernel: $(uname -r)"
        echo "# cpu: $(lscpu | awk -F: '/Model name/{gsub(/^ +/,"",$2); print $2; exit}')  cpus=$(nproc)"
        echo "# governor: $(governor)  boost: $(cat /sys/devices/system/cpu/cpufreq/boost 2>/dev/null || echo unknown)"
        echo "# java: $("$JAVA_BIN" -version 2>&1 | head -1)"
        echo "# JAVA_BIN=$JAVA_BIN"
        echo "# memlock(soft): $(memlock_soft)"
        echo "# hugepages total/free: $(huge_total)/$(huge_free)  mount: $HP_MOUNT $(findmnt -no OPTIONS "$HP_MOUNT" 2>/dev/null || echo '<none>')"
        echo "# dataset: $RD_FILE $(stat -c 'size=%s blocks=%b mtime=%y' "$RD_FILE" 2>/dev/null)"
        echo "# git: $(git rev-parse HEAD 2>/dev/null || echo unknown) dirty=$([[ -n $(git status --porcelain 2>/dev/null | head -1) ]] && echo true || echo false)"
        echo "# harness sha256: $(sha256sum "$HERE/run-host-setup.sh" | cut -d' ' -f1)"
        echo "# analyzer sha256: $(sha256sum "$HERE/analyze_host_setup.py" 2>/dev/null | cut -d' ' -f1)"
        echo "# plan: block=$BLOCK reps_a=$REPS_A reps_b=$REPS_B warmup=${WARMUP}s measured=${DURATION}s"
        echo "# protocol: frozen; see docs/benchmark-methodology.md"
    } >"$f"
    echo "manifest -> $f"
}

# ---------------------------------------------------------------- executors
# run_cell <case> <engine> <variant> <bs> <qd> <rep> -- <command...>
run_cell() {
    local case_id=$1 engine=$2 variant=$3 bs=$4 qd=$5 rep=$6
    shift 6
    [[ ${1:-} == -- ]] && shift
    local id="${case_id}__${engine}__${variant}__bs${bs}__qd${qd}__r${rep}"
    local json=$CELLS/$id.json log=$LOGS/$id.log
    rm -f "$json"
    local la0 la1v t0 t1 wall hp0 hp1 ts0 ts1 status=ok rc=0
    local rs0 rsec0 ws0 wsec0 rs1 rsec1 ws1 wsec1
    read -r rs0 rsec0 ws0 wsec0 <<<"$(awk '{print $1,$3,$5,$7}' "/sys/block/nvme1n1/stat")"
    la0=$(loadavg); hp0=$(huge_free); ts0=$(nvme_temp); t0=$(date +%s.%N)
    if [[ $DRY == 1 ]]; then
        printf 'DRY %-58s :' "$id"
        printf ' %q' "$@"; echo
        return 0
    fi
    timeout 300 "$@" >"$log" 2>>"$log" || rc=$?
    sleep 2
    t1=$(date +%s.%N)
    [[ $rc -ne 0 ]] && status=fail
    [[ -s $json ]] || status=fail
    wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.3f", b-a}')
    read -r rs1 rsec1 ws1 wsec1 <<<"$(awk '{print $1,$3,$5,$7}' "/sys/block/nvme1n1/stat")"
    la1v=$(loadavg); hp1=$(huge_free); ts1=$(nvme_temp)
    local ops=-1 iops=-1 p99=-1 bytes=-1
    if [[ -s $json ]]; then
        read -r ops iops p99 bytes <<<"$(python3 "$REPO/bench/read-write/json_summary.py" "$engine" "$json" 2>/dev/null || echo '-1 -1 -1 -1')"
        awk -v i="$iops" 'BEGIN{exit !(i > 0)}' || status=fail
    fi
    # hugepage-backing evidence: hung arm must consume pages; warn (not fail) if not
    if [[ $variant == huge && $status == ok ]]; then
        if (( hp1 - hp0 > -100 )); then
            echo "WARN: $id consumed no hugepages (free $hp0 -> $hp1) - backing not proven" | tee -a "$LOGS/warnings.log" >&2
        fi
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$case_id" "$engine" "$variant" "$bs" "$qd" "$rep" "$(now_utc)" "$la0" "$la1v" \
        "$((rsec1 - rsec0))" "$((wsec1 - wsec0))" "$ops" "$iops" "$p99" "$wall" "$status" "$bytes" \
        "$hp0" "$hp1" "$ts0" "$ts1" >>"$META"
    printf 'cell %-58s iops=%-10s p99=%-8s huge=F%-5s wall=%-6ss %s\n' \
        "$id" "$iops" "$p99" "$hp1" "$wall" "$status"
    [[ $status == ok ]]
}

seastar_cmd() { # seastar_cmd <workload> <bs> <qd> <smp> <cpuset> <size|""> <variant thp|huge> <json> <label>
    CMD=("$SEASTAR_BIN" --workload "$1" --file "$RD_FILE" --bs "$2" --qd "$3" \
        --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1 --seed "$SEED" \
        --label "$9" --json "$8" --smp "$4" --cpuset "$5" --memory 1G --lock-memory 0 \
        --reactor-backend io_uring --readonly)
    [[ -n $6 ]] && CMD+=(--size "$6")
    [[ $7 == huge ]] && CMD+=(--hugepages "$HP_MOUNT")
}

juring_cmd() { # juring_cmd <workload> <bs> <qd> <json> <label>
    CMD=(env JAVA_BIN="$JAVA_BIN" JAVA_HOME="$JAVA_HOME" JURING_SKIP_COMPILE=1 \
        bash "$JURING_RUN" --workload "$1" --file "$RD_FILE" --bs "$2" --qd "$3" \
        --threads 1 --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1 \
        --seed "$SEED" --label "$5" --json "$4" --readonly)
}

fio_matched_cmd() { # fio_matched_cmd <workload> <bs> <qd> <json> <label>
    local rw
    case $1 in
        randread) rw=randread ;;
        seqread) rw=read ;;
        *) echo "fio_matched_cmd: bad workload '$1'" >&2; return 2 ;;
    esac
    CMD=(fio --name="$5" --filename="$RD_FILE" --readonly --direct=1 --rw="$rw" \
        --bs="$2" --iodepth="$3" --numjobs=1 --ioengine=io_uring \
        --registerfiles=1 --fixedbufs=1 --randrepeat=1 --time_based \
        --runtime="$DURATION" --ramp_time="$WARMUP" --clocksource=clock_gettime \
        --percentile_list=50:95:99:99.9 --clat_percentiles=1 --lat_percentiles=0 \
        --group_reporting=1 --output-format=json --output="$4")
}

# ---------------------------------------------------------------- Block A
run_block_a() {
    local reps=$1 cell c rep arm smp cpuset wl bs qd size
    # case: workload bs qd smp cpuset size ("" for the default)
    local -a cells=(
        "randread-4k-qd64 randread 4k 64 1 2"
        "randread-4k-qd128 randread 4k 128 1 2"
        "seqread-1m-qd8 seqread 1m 8 1 2"
        "seqread-1m-qd32 seqread 1m 32 1 2"
        "shards-8 randread 4k 64 8 $HCTX"
    )
    echo "=== Block A: seastar THP vs hugetlbfs, R=$reps, ${WARMUP}+${DURATION}s ==="
    for ((rep = 1; rep <= reps; rep++)); do
        for cell in "${cells[@]}"; do
            read -r c wl bs qd smp cpuset <<<"$cell"
            size=""; [[ $c == shards-8 ]] && size=$(stat -c%s "$RD_FILE")
            if (( rep % 2 == 1 )); then local -a arms=(thp huge); else local -a arms=(huge thp); fi
            for arm in "${arms[@]}"; do
                seastar_cmd "$wl" "$bs" "$qd" "$smp" "$cpuset" "$size" "$arm" \
                    "$CELLS/${c}__seastar__${arm}__bs${bs}__qd${qd}__r${rep}.json" \
                    "${c}__seastar__${arm}__bs${bs}__qd${qd}__r${rep}"
                local -a cmd=("${CMD[@]}")
                if (( smp == 1 )); then cmd=(taskset -c "$CPU" "${cmd[@]}"); fi
                run_cell "$c" seastar "$arm" "$bs" "$qd" "$rep" -- "${cmd[@]}"
            done
        done
    done
}

# ---------------------------------------------------------------- Block B
run_block_b() {
    local reps=$1 cell c rep eng wl bs qd
    local -a cells=("seqread-1m-qd1 seqread 1m 1" "seqread-1m-qd8 seqread 1m 8"
                   "seqread-1m-qd32 seqread 1m 32")
    local -a engines=(juring fio-io_uring seastar)
    echo "=== Block B: memlock best-config read cells, R=$reps, ${WARMUP}+${DURATION}s ==="
    for ((rep = 1; rep <= reps; rep++)); do
        for cell in "${cells[@]}"; do
            read -r c wl bs qd <<<"$cell"
            local -a order=("${engines[@]}")
            # rotate engine order per rep: rep 1 -> juring,fio,seastar; rep 2 -> fio,seastar,juring; ...
            local shift_n=$(( (rep - 1) % 3 ))
            order=("${engines[@]:shift_n}" "${engines[@]:0:shift_n}")
            for eng in "${order[@]}"; do
                case $eng in
                    juring) juring_cmd "$wl" "$bs" "$qd" \
                        "$CELLS/${c}__juring__std__bs${bs}__qd${qd}__r${rep}.json" \
                        "${c}__juring__std__bs${bs}__qd${qd}__r${rep}"
                        local -a cmd=(taskset -c "$CPU" "${CMD[@]}") ;;
                    fio-io_uring) fio_matched_cmd "$wl" "$bs" "$qd" \
                        "$CELLS/${c}__fio-io_uring__matched__bs${bs}__qd${qd}__r${rep}.json" \
                        "${c}__fio-io_uring__matched__bs${bs}__qd${qd}__r${rep}"
                        local -a cmd=(taskset -c "$CPU" "${CMD[@]}") ;;
                    seastar) seastar_cmd "$wl" "$bs" "$qd" 1 "$CPU" "" thp \
                        "$CELLS/${c}__seastar__std__bs${bs}__qd${qd}__r${rep}.json" \
                        "${c}__seastar__std__bs${bs}__qd${qd}__r${rep}"
                        local -a cmd=(taskset -c "$CPU" "${CMD[@]}") ;;
                esac
                run_cell "$c" "$eng" \
                    "$([[ $eng == fio-io_uring ]] && echo matched || echo std)" \
                    "$bs" "$qd" "$rep" -- "${cmd[@]}"
            done
        done
    done
}

# ---------------------------------------------------------------- main
echo "Host-representativeness campaign: block=$BLOCK reps_a=$REPS_A reps_b=$REPS_B dry=$DRY"
echo "results: $RESULTS"
if [[ $DRY == 1 ]]; then echo "preflight skipped (dry-run)"; else preflight; fi
manifest
case $BLOCK in
    a) run_block_a "$REPS_A" ;;
    b) run_block_b "$REPS_B" ;;
    all) run_block_a "$REPS_A"; run_block_b "$REPS_B" ;;
esac
[[ $DRY == 1 ]] && { echo "dry-run complete (nothing executed)"; exit 0; }
echo "campaign done: $(now_utc)"
echo "next: python3 bench/host-setup/analyze_host_setup.py --dir $RESULTS"
