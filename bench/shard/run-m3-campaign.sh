#!/usr/bin/env bash
#
# run-m3-campaign.sh -- M3 W17-W20 multi-shard scaling campaign driver
# (docs/m3-design.md FROZEN 2026-11-15; SEASTAR-PLAN.md §5 M3).
#
# Cells (12 cells / 60 invocations, R=5):
#   juring-group + fio + seastar --smp N for N in {1,2,4,8}
#   4K randread, QD 64 per worker/shard, one aged shared 10 GiB read-only file
#   (DS-S), 10 s warmup + 30 s measured, fresh process/JVM per invocation,
#   300 s rest between invocations, engines interleaved and the order alternating
#   per rep block, every rep kept and reported.
#
# Placement (frozen design §3):
#   N=1..4: CPUs 1..N for every engine (juring-group --first-cpu 1).
#   N=8   : data NVMe hctx CPU set 1,2,4,6,8,10,12,14. fio and seastar are
#           confined/pinned to that exact set; juring-group runs UNPINNED
#           (--first-cpu -1) inside a taskset mask of the same set (the B4 naive
#           1..8 pinning is deliberately not reused; mq/*/cpu_list is recorded).
#
# Resumability: every invocation is checkpointed in cell-meta.tsv + a raw JSON.
# `--resume` keeps complete cells, archives incomplete artifacts (never deletes),
# and runs only the missing invocations. `--max-blocks K` runs at most K whole
# (N, rep) engine blocks per driver invocation (a chunk), so an interrupted run
# never loses good cells. The 300 s rest is enforced across chunk boundaries by
# reading the last invocation's end_utc from cell-meta.tsv.
#
# Usage:
#   bench/shard/run-m3-campaign.sh [--dry-run] [--session NAME] [--resume]
#       [--n-list "1,2,4,8"] [--engines "juring-group,fio,seastar"]
#       [--reps N] [--seconds S] [--warmup S] [--rest S]
#       [--max-blocks K] [--force]
#
# Environment:
#   READ_FILE=/mnt/TestData/read_files/single_file_bench.bin
#   JURING_RUN=bench/juring/run-juring.sh   FIO_BIN=fio
#   SEASTAR_BIN=bench/seastar/seastar_file_bench
#   JVM_OPTS="-Xms8g -Xmx8g -XX:+AlwaysPreTouch"
#   FIO_GROUP_REPORTING=1   (frozen matched template; 1 => fio emits one job)
#   ALLOW_RESIDUAL_LOAD=1   relax ONLY the start-of-session loadavg gate.
#
# The driver never invokes Maven: the JUring harness is compiled javac-only
# (JURING_SKIP_MVN=1 ... --help) against target/classes the Lead built on the
# frozen tree; cells then run with JURING_SKIP_COMPILE=1.
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../.." && pwd)
cd "$REPO_ROOT"

SESSION=${SESSION:-m3-$(date +%Y-%m-%d)}
READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
JURING_RUN=${JURING_RUN:-$REPO_ROOT/bench/juring/run-juring.sh}
FIO_BIN=${FIO_BIN:-fio}
SEASTAR_BIN=${SEASTAR_BIN:-$REPO_ROOT/bench/seastar/seastar_file_bench}
ANALYZER=$HERE/analyze_m3.py
JVM_OPTS=${JVM_OPTS:--Xms8g -Xmx8g -XX:+AlwaysPreTouch}
FIO_GROUP_REPORTING=${FIO_GROUP_REPORTING:-1}

# Frozen identity of the code under test (B5 tree, M3 design §"Frozen identity").
FROZEN_SRC_SHA1=04f12dbc85d723a10dd952b7ac3c51bb6253ed46
FROZEN_TEST_SHA1=666e0644dc063c0b15c5bb52571002a79b5ec657
FROZEN_DESIGN_PATH=docs/m3-design.md
FROZEN_DESIGN_SHA1=334bb55a43d93f5dfdc93e2110431ec5156a82c5
FROZEN_DESIGN_COMMIT=b8b7832

QD=64
BS=4k
SEED=${SEED:-20261114}
N_LIST="1 2 4 8"
ALL_N="1 2 4 8"
ENGINES="juring-group fio seastar"
ALL_ENGINES="juring-group fio seastar juring-manual"
HCTX_CPUS="1,2,4,6,8,10,12,14"   # one CPU per NVMe hctx (nvme1n1 mq/0..7)
REPS=5
WARMUP=10
DURATION=30
REST=300
SETTLE=0
MAX_BLOCKS=0
DRY_RUN=0
FORCE=0
RESUME=0

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
        --n-list)
            N_LIST=${2:?--n-list needs a value}
            N_LIST=${N_LIST//,/ }
            shift
            ;;
        --engines)
            ENGINES=${2:?--engines needs a value}
            ENGINES=${ENGINES//,/ }
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
        --settle)
            SETTLE=${2:?--settle needs a value}
            shift
            ;;
        --max-blocks)
            MAX_BLOCKS=${2:?--max-blocks needs a value}
            shift
            ;;
        --force) FORCE=1 ;;
        --resume) RESUME=1 ;;
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
for v in "$WARMUP" "$DURATION" "$REST" "$SETTLE"; do
    if ! [[ $v =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        echo "error: --warmup/--seconds/--rest/--settle must be numbers, got '$v'" >&2
        exit 2
    fi
done
if ! [[ $MAX_BLOCKS =~ ^[0-9]+$ ]]; then
    echo "error: --max-blocks must be a non-negative integer, got '$MAX_BLOCKS'" >&2
    exit 2
fi
N_LIST=$(echo "$N_LIST" | tr -s ' ')
ENGINES=$(echo "$ENGINES" | tr -s ' ')
for n in $N_LIST; do
    case " $ALL_N " in
        *" $n "*) ;;
        *)
            echo "error: unknown N '$n' (this session supports: $ALL_N)" >&2
            exit 2
            ;;
    esac
done
for e in $ENGINES; do
    case " $ALL_ENGINES " in
        *" $e "*) ;;
        *)
            echo "error: unknown engine '$e' (supported: $ALL_ENGINES)" >&2
            exit 2
            ;;
    esac
done
if ! [[ $SESSION =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "error: --session may only contain [A-Za-z0-9._-], got '$SESSION'" >&2
    exit 2
fi
case $FIO_GROUP_REPORTING in
    0 | 1) ;;
    *)
        echo "error: FIO_GROUP_REPORTING must be 0 or 1" >&2
        exit 2
        ;;
esac

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
now_ns() { date +%s.%N; }
loadavg() { awk '{print $1","$2","$3}' /proc/loadavg; }
load1() { awk '{print $1}' /proc/loadavg; }
wall_sec() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.3f", b-a}'; }

# cpu_set_for_n <N>: frozen placement (design §3).
cpu_set_for_n() { # cpu_set_for_n <N> -> taskset/--cpuset list
    if [[ $1 -ge 8 ]]; then
        echo "$HCTX_CPUS"
    elif [[ $1 == 1 ]]; then
        echo "1"
    else
        echo "1-$1"
    fi
}

dataset_size() { stat -c%s "$READ_FILE" 2>/dev/null || echo 0; }
src_main_sha1() { find src/main/java -type f | sort | xargs cat | sha1sum | cut -d' ' -f1; }
src_test_sha1() { find src/test/java -type f | sort | xargs cat | sha1sum | cut -d' ' -f1; }
design_sha1() { sha1sum "$FROZEN_DESIGN_PATH" 2>/dev/null | cut -d' ' -f1; }
head_commit() { git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown; }
argv_str() { printf '%q ' "$@"; }

env_snapshot() { # env_snapshot <path> <tag>
    {
        echo "## $2: $(now_utc)"
        echo "uptime: $(uptime)"
        echo "loadavg: $(cat /proc/loadavg)"
        echo "top-5 CPU:"
        ps -eo pcpu=,pid=,comm= --sort=-pcpu | head -5
        echo
    } >>"$1"
}

# Last invocation end (epoch seconds) so we can tell our own decaying loadavg
# residual from genuine foreign interference (F14).
LAST_END_TS=0
# Epoch seconds of the previous invocation's process exit; used to enforce the
# frozen 300 s inter-invocation rest even across chunk boundaries / --resume.
LAST_END_EPOCH=0

gate_state() {
    local la1 foreign age
    la1=$(load1)
    foreign=$(ps -eo pcpu=,pid=,comm= --sort=-pcpu |
        awk -v self="$$" '$2 != self && $1 > 20 {printf "%s(pid %s,%.0f%%) ", $3, $2, $1}')
    if [[ -n $foreign ]]; then
        echo "foreign>20% CPU: $foreign"
        return 0
    fi
    if awk -v x="$la1" 'BEGIN{exit !(x <= 0.5)}'; then
        echo "ok"
        return 0
    fi
    if [[ ${ALLOW_RESIDUAL_LOAD:-0} == 1 ]]; then
        echo "ok"
        return 0
    fi
    age=$(( $(date +%s) - LAST_END_TS ))
    if (( LAST_END_TS > 0 && age < 180 )); then
        echo "own-residual(la1=$la1)"
        return 0
    fi
    echo "loadavg>0.5(la1=$la1)"
}

# json_summary <engine> <N> <path> -> tab fields:
#   status ops iops p50_us p99_us bytes errors runtime shards threads
json_summary() {
    python3 - "$1" "$2" "$3" <<'PY'
import json
import sys

engine, expected_n, path = sys.argv[1], int(sys.argv[2]), sys.argv[3]
fallback = ["missing", "-1", "-1", "-1", "-1", "-1", "-1", "?", "?", "?"]
try:
    with open(path, "r", encoding="utf-8") as f:
        d = json.load(f)
except Exception:
    print("\t".join(fallback))
    raise SystemExit(0)

if engine.startswith("fio"):
    jobs = d.get("jobs") or []
    iops = 0.0
    ops = 0.0
    nbytes = 0.0
    errs = 0
    p50s = []
    p99s = []
    for j in jobs:
        rd = j.get("read") or {}
        iops += float(rd.get("iops") or 0.0)
        ops += float(rd.get("total_ios") or 0.0)
        nbytes += float(rd.get("io_bytes") or 0.0)
        errs += int(j.get("error") or 0) + int(rd.get("short_ios") or 0)
        pct = (rd.get("clat_ns") or {}).get("percentile") or {}
        p50s.append(float(pct.get("50.000000") or 0.0) / 1000.0)
        p99s.append(float(pct.get("99.000000") or 0.0) / 1000.0)
    p50 = sum(p50s) / len(p50s) if p50s else -1.0
    p99 = sum(p99s) / len(p99s) if p99s else -1.0
    status = "ok" if jobs and errs == 0 and iops > 0 else "fail"
    print("\t".join([
        status, str(int(ops)), "%.3f" % iops, "%.3f" % p50, "%.3f" % p99,
        str(int(nbytes)), str(errs), "fio",
        str(len(jobs)), str(expected_n)]))
    raise SystemExit(0)

runs = d.get("runs") or []
r = runs[0] if runs else {}
cfg = d.get("config") or {}
lat = r.get("latency_us") or {}
status = d.get("status") or "missing"
if engine == "seastar":
    man = d.get("manifest") or {}
    errs = int(r.get("errors_total") or 0)
    iops = float(r.get("iops") or 0.0)
    runtime = str(cfg.get("runtime") or "smp")
    shards = int(cfg.get("shards") or 0)
    threads = int(cfg.get("threads") or 0)
    backend = str(man.get("reactor_backend") or "")
    if status == "ok" and (errs != 0 or iops <= 0 or backend != "io_uring"):
        status = "fail"
    print("\t".join([
        status, str(int(r.get("ops_total") or 0)), "%.3f" % iops,
        "%.3f" % float(lat.get("p50") or 0.0), "%.3f" % float(lat.get("p99") or 0.0),
        str(int(r.get("bytes_total") or 0)), str(errs),
        runtime, str(shards), str(threads)]))
    raise SystemExit(0)

errs = int(r.get("errors") or 0)
iops = float(r.get("iops") or 0.0)
if status == "ok" and (errs != 0 or iops <= 0):
    status = "fail"
print("\t".join([
    status, str(int(r.get("ops") or 0)), "%.3f" % iops,
    "%.3f" % float(lat.get("p50") or 0.0), "%.3f" % float(lat.get("p99") or 0.0),
    str(int(r.get("bytes") or 0)), str(errs),
    str(cfg.get("runtime", "threads")), str(cfg.get("shards", 0)),
    str(cfg.get("threads", 0))]))
PY
}

# engine_order <N> <rep> -> engine order for this rep block.
# Frozen: odd (rep+N) = juring-group, fio, seastar; even = seastar, fio, juring-group.
# `juring-manual` (optional reference) is only run at N=8 and only when enabled.
engine_order() {
    local n=$1 rep=$2
    local arr=() out=() e i
    for e in $ENGINES; do
        if [[ $e == juring-manual && $n != 8 ]]; then continue; fi
        arr+=("$e")
    done
    if ((${#arr[@]} == 0)); then
        echo ""
        return 0
    fi
    if (( (rep + n) % 2 == 0 )); then
        for ((i = ${#arr[@]} - 1; i >= 0; i--)); do out+=("${arr[i]}"); done
    else
        out=("${arr[@]}")
    fi
    echo "${out[@]}"
}

# rest_before_next: enforce the frozen REST seconds between the previous
# invocation's process exit and the next invocation's start (also across chunks).
rest_before_next() {
    local now elapsed deficit
    (( LAST_END_EPOCH > 0 )) || return 0
    now=$(date +%s)
    elapsed=$(( now - LAST_END_EPOCH ))
    deficit=$(awk -v r="$REST" -v e="$elapsed" 'BEGIN{printf "%d", (r-e>0)?(r-e):0}')
    if (( deficit > 0 )); then
        echo "[m3] rest ${deficit}s (inter-invocation rest ${REST}s; elapsed since last exit ${elapsed}s)"
        sleep "$deficit"
    else
        echo "[m3] rest satisfied (elapsed since last exit ${elapsed}s >= ${REST}s)"
    fi
}

# run_argv <N> <engine> <rep>
run_argv() {
    local n=$1 engine=$2 rep=$3
    local cpus jvm
    cpus=$(cpu_set_for_n "$n")
    CPU_SET=$cpus
    ID="N${n}__${engine}__r${rep}"
    JSON="$CELLS_DIR/$ID.json"
    LOG="$LOGS_DIR/$ID.log"
    ARGV=()
    case $engine in
        juring-group)
            jvm="$JVM_OPTS -Xlog:gc:file=$LOGS_DIR/$ID.gc.log"
            local first_cpu=1
            if [[ $n -ge 8 ]]; then first_cpu=-1; fi  # frozen: N=8 unpinned within hctx set
            # shellcheck disable=SC2086
            ARGV=(env JURING_SKIP_COMPILE=1 JURING_JAVA_OPTS="$jvm" bash "$JURING_RUN"
                --workload randread --file "$READ_FILE" --size "$(dataset_size)" \
                --bs "$BS" --qd "$QD" --runtime=shard --shards "$n" --first-cpu "$first_cpu" \
                --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1 --seed "$SEED" \
                --label "$ID" --readonly --json "$JSON")
            ;;
        juring-manual)
            jvm="$JVM_OPTS -Xlog:gc:file=$LOGS_DIR/$ID.gc.log"
            # shellcheck disable=SC2086
            ARGV=(env JURING_SKIP_COMPILE=1 JURING_JAVA_OPTS="$jvm" bash "$JURING_RUN"
                --workload randread --file "$READ_FILE" --size "$(dataset_size)" \
                --bs "$BS" --qd "$QD" --runtime=threads --threads "$n" \
                --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1 --seed "$SEED" \
                --label "$ID" --readonly --json "$JSON")
            ;;
        fio)
            ARGV=("$FIO_BIN" --name="$ID" --filename="$READ_FILE" --readonly \
                --direct=1 --rw=randread --bs="$BS" --iodepth="$QD" --numjobs="$n" \
                --ioengine=io_uring --registerfiles=1 --fixedbufs=1 --randrepeat=1 \
                --time_based --runtime="$DURATION" --ramp_time="$WARMUP" \
                --clocksource=clock_gettime --percentile_list=50:95:99:99.9 \
                --clat_percentiles=1 --lat_percentiles=0 \
                --group_reporting="$FIO_GROUP_REPORTING" \
                --output-format=json --output="$JSON")
            ;;
        seastar)
            ARGV=("$SEASTAR_BIN" --workload randread --file "$READ_FILE" \
                --size "$(dataset_size)" --bs "$BS" --qd "$QD" \
                --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1 --seed "$SEED" \
                --label "$ID" --readonly --json "$JSON" \
                --smp "$n" --cpuset "$cpus" --memory 1G --lock-memory 0 \
                --reactor-backend io_uring)
            ;;
    esac
}

# run_row <N> <engine> <rep>: one fresh process, log + JSON + env snapshots + meta row.
run_row() {
    local n=$1 engine=$2 rep=$3
    local id json log start end wall la0 la1 t0 t1 rc status gate0 gate1
    local jstatus ops iops p50 p99 bytes errors runtime shards threads cmd_str t1s
    run_argv "$n" "$engine" "$rep"
    id=$ID
    json=$JSON
    log=$LOG
    local cpus=$CPU_SET

    ENVF=$LOGS_DIR/$id.env
    : >"$ENVF"
    env_snapshot "$ENVF" "before"
    gate0=$(gate_state)
    if [[ $gate0 != ok ]]; then
        echo "warn: F14 gate violation before $id: $gate0 (recorded, still run; not trimmed)" >&2
    fi

    cmd_str="taskset -c $cpus $(argv_str "${ARGV[@]}")"
    start=$(now_utc)
    la0=$(loadavg)
    t0=$(now_ns)
    rc=0
    taskset -c "$cpus" "${ARGV[@]}" >"$log" 2>&1 || rc=$?
    t1=$(now_ns)
    end=$(now_utc)
    la1=$(loadavg)
    wall=$(wall_sec "$t0" "$t1")
    LAST_END_TS=$(date +%s)
    LAST_END_EPOCH=$(date +%s)
    gate1=$(gate_state)
    env_snapshot "$ENVF" "after"

    status=ok
    if (( rc != 0 )); then status=fail; fi
    if [[ ! -s $json ]]; then
        status=fail
        jstatus=missing ops=-1 iops=-1 p50=-1 p99=-1 bytes=-1 errors=-1 runtime='?' shards='?' threads='?'
    else
        IFS=$'\t' read -r jstatus ops iops p50 p99 bytes errors runtime shards threads < <(json_summary "$engine" "$n" "$json")
        if [[ $jstatus != ok ]]; then status=fail; fi
        if [[ $engine == juring-group ]]; then
            if [[ $runtime != shard || $shards != "$n" || $threads != "$n" ]]; then
                echo "warn: cell $id expected runtime=shard shards=$n threads=$n, JSON says runtime=$runtime shards=$shards threads=$threads" >&2
                status=fail
            fi
        elif [[ $engine == juring-manual ]]; then
            if [[ $runtime == shard || $threads != "$n" ]]; then
                echo "warn: cell $id expected runtime=threads threads=$n, JSON says runtime=$runtime threads=$threads" >&2
                status=fail
            fi
        elif [[ $engine == seastar ]]; then
            if ! grep -q 'Reactor backend: io_uring' "$log"; then
                echo "warn: cell $id seastar log has no 'Reactor backend: io_uring'" >&2
                status=fail
            fi
            if [[ $shards != "$n" || $threads != "$n" ]]; then
                echo "warn: cell $id expected seastar smp/shards/threads=$n, JSON says shards=$shards threads=$threads" >&2
                status=fail
            fi
        fi
    fi
    if [[ $status == ok && $gate0 != ok && $gate0 != own-residual* ]]; then status=gate; fi
    if [[ $status == ok && $gate1 != ok && $gate1 != own-residual* ]]; then status=gate; fi

    printf 'cell %-32s status=%-4s wall=%-7ss iops=%-11s p50_us=%-8s p99_us=%-8s ops=%s gate=%s\n' \
        "$id" "$status" "$wall" "$iops" "$p50" "$p99" "$ops" "$gate0"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "N$n" "$engine" "std" "$n" "$cpus" "$rep" "$start" "$end" "$wall" \
        "$la0" "$la1" "$gate0" "$gate1" "$status" "$rc" "$ops" "$iops" "$p50" "$p99" \
        "$bytes" "$cmd_str" >>"$META"
    [[ $status == ok || $status == gate ]]
}

# ---------------------------------------------------------------------------
# manifest
# ---------------------------------------------------------------------------
write_manifest() {
    local govs rd_size nvme_line build_state mq_dump e
    govs=$(for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort | uniq -c | tr '\n' ' ')
    rd_size=$(dataset_size)
    nvme_line=$(lsblk -o NAME,MODEL,SERIAL,SIZE --noheadings 2>/dev/null | grep -E 'nvme' || true)
    build_state=$(stat -c '%y %n' target/classes 2>/dev/null || echo 'target/classes MISSING')
    mq_dump=$(for m in /sys/block/nvme1n1/mq/*/cpu_list; do
        echo "    $(basename "$(dirname "$m")") -> $(cat "$m")"
    done 2>/dev/null || true)
    {
        echo "# M3 W17-W20 multi-shard scaling campaign manifest (docs/m3-design.md, frozen)"
        echo "# generated: $(date -Iseconds)  host: $(hostname)"
        echo "# workspace: $REPO_ROOT"
        echo
        echo "## Frozen design + code identity"
        echo "design file: $FROZEN_DESIGN_PATH"
        echo "design sha1 (expected): $FROZEN_DESIGN_SHA1"
        echo "design sha1 (live):     $(design_sha1)"
        echo "design freeze commit:   $FROZEN_DESIGN_COMMIT ($(git -C "$REPO_ROOT" log -1 --format='%s' "$FROZEN_DESIGN_COMMIT" 2>/dev/null || echo '?'))"
        echo "code under test: B5 frozen tree (B4+B5 delivered), src/main/java content sha1 (expected): $FROZEN_SRC_SHA1"
        echo "src/test/java content sha1 (expected): $FROZEN_TEST_SHA1"
        echo "live src/main/java content sha1: $(src_main_sha1)"
        echo "live src/test/java content sha1: $(src_test_sha1)"
        echo "git HEAD: $(head_commit)"
        echo "git status (porcelain):"
        git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -30 || true
        echo "target/classes state: $build_state"
        echo
        echo "## Host / kernel"
        uname -a
        echo
        echo "## CPU"
        lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket|Socket|NUMA node|MHz' || true
        echo "frozen placement: N=1..4 CPUs 1..N (juring-group --first-cpu 1); N=8 hctx CPU set"
        echo "  $HCTX_CPUS for fio/seastar and juring-group UNPINNED (--first-cpu -1) inside the same taskset mask"
        echo "  (CPUs 16-31 are SMT siblings and stay idle)"
        echo
        echo "## CPU governor (unique values over all CPUs)"
        echo "    $govs"
        echo
        echo "## Data NVMe blk-mq topology (/sys/block/nvme1n1/mq/*/cpu_list)"
        echo "$mq_dump"
        echo
        echo "## Memory / hugepages / memlock"
        grep -E 'MemTotal|MemAvailable|HugePages_Total|Hugepagesize' /proc/meminfo
        echo "ulimit -l = $(ulimit -l) KiB (registered-buffer budget for JUring)"
        echo
        echo "## Storage identity (data NVMe) and mounts"
        echo "$nvme_line"
        echo "read dataset: $READ_FILE size=$rd_size (read-only DS-S, only read cells are run)"
        stat -c 'stat: size=%s blocks=%b mtime=%y' "$READ_FILE" 2>/dev/null || true
        findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS --target "$READ_FILE" 2>/dev/null || true
        df -h "$READ_FILE" 2>/dev/null || true
        echo
        echo "## Kernel I/O controls"
        echo "kernel.io_uring_disabled = $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?')"
        echo
        echo "## Tool versions"
        echo "java: $(java -version 2>&1 | head -1)"
        echo "javac: $(javac -version 2>&1 | head -1)"
        echo "fio: $("$FIO_BIN" --version 2>&1 | head -1)"
        echo "python3: $(python3 --version 2>&1)"
        echo "seastar: $("$SEASTAR_BIN" --help 2>&1 | head -1 || true)"
        echo
        echo "## Harness / driver sha256"
        sha256sum "$JURING_RUN" "$REPO_ROOT/bench/juring/JUringFileBench.java" \
            "$HERE/run-m3-campaign.sh" "$HERE/analyze_m3.py" \
            "$SEASTAR_BIN" "$REPO_ROOT/bench/seastar/seastar_file_bench.cc" 2>/dev/null || true
        sha256sum "$(command -v "$FIO_BIN")" 2>/dev/null || true
        echo
        echo "## Frozen protocol (docs/m3-design.md §2)"
        echo "cells: N in {$(echo "$N_LIST" | tr ' ' ',')} x engines {$ENGINES} (R=$REPS)"
        echo "invocations expected: $(($(wc -w <<<"$N_LIST") * REPS * $(wc -w <<<"$ENGINES")))"
        echo "per invocation: 4K randread, QD $QD per worker/shard, warmup ${WARMUP}s, measured ${DURATION}s,"
        echo "                fresh process/JVM, seed $SEED, 300 s rest between invocations (frozen REST=$REST)"
        echo "engine order: alternating per rep block; odd (rep+N) = $ENGINES; even = reversed"
        echo "dataset: ONE shared aged 10 GiB read-only file for every engine/shard (DS-S);"
        echo "         the W18-W20 DS-M one-file-per-shard layout is NOT used (recorded limitation)"
        echo "juring : io_uring, SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN, registered file + fixed buffers,"
        echo "         runtime=shard, one IoShard/ring per worker through RingGroup, metrics on (schema 1.1);"
        echo "         JVM: $JVM_OPTS (fresh JVM per invocation)"
        echo "fio    : io_uring numjobs=N, --registerfiles=1 --fixedbufs=1 --iodepth=$QD --direct=1"
        echo "         --group_reporting=$FIO_GROUP_REPORTING (1 => one aggregated fio job; per-job spread n/a)"
        echo "seastar: --smp N --cpuset <set> --memory 1G --lock-memory 0 --reactor-backend io_uring;"
        echo "         one reactor/shard, $QD in-flight fibers/shard; JSON schema-1.1 parity"
        echo "only read cells: --readonly for fio, O_DIRECT read-only fds for JUring, read-only seastar."
        echo
        echo "## Exact per-cell command templates"
        echo "group  : taskset -c <set> env JURING_SKIP_COMPILE=1 JURING_JAVA_OPTS='$JVM_OPTS -Xlog:gc:file=<log>' \\"
        echo "         bash bench/juring/run-juring.sh --workload randread --file $READ_FILE --size <bytes> \\"
        echo "         --bs $BS --qd $QD --runtime=shard --shards N --first-cpu {1 for N<8,-1 for N=8} --seconds $DURATION \\"
        echo "         --warmup-sec $WARMUP --runs 1 --seed $SEED --label <id> --readonly --json <cells/id.json>"
        echo "fio    : taskset -c <set> fio --name=<id> --filename=$READ_FILE --readonly --direct=1 --rw=randread \\"
        echo "         --bs=$BS --iodepth=$QD --numjobs=N --ioengine=io_uring --registerfiles=1 --fixedbufs=1 \\"
        echo "         --randrepeat=1 --time_based --runtime=$DURATION --ramp_time=$WARMUP \\"
        echo "         --clocksource=clock_gettime --percentile_list=50:95:99:99.9 --clat_percentiles=1 \\"
        echo "         --lat_percentiles=0 --group_reporting=$FIO_GROUP_REPORTING --output-format=json --output=<cells/id.json>"
        echo "seastar: taskset -c <set> $SEASTAR_BIN --workload randread --file $READ_FILE --size <bytes> \\"
        echo "         --bs $BS --qd $QD --seconds $DURATION --warmup-sec $WARMUP --runs 1 --seed $SEED \\"
        echo "         --label <id> --readonly --json <cells/id.json> --smp N --cpuset <set> --memory 1G \\"
        echo "         --lock-memory 0 --reactor-backend io_uring"
        echo
        echo "## Chunking / rest enforcement"
        echo "MAX_BLOCKS=$MAX_BLOCKS (0 = run all remaining blocks in this invocation)"
        echo "a block = one (N, rep) group of enabled engines; the driver stops only at block boundaries"
        echo "the 300 s inter-invocation rest is made up on --resume from the last cell-meta end_utc"
        echo "started: $(now_utc)"
    } >"$MANIFEST"
}

init_meta() {
    if [[ ! -s $META ]]; then
        printf 'cell\tengine\tvariant\tN\tcpus\trep\tstart_utc\tend_utc\twall_s\tloadavg_start\tloadavg_end\tgate_start\tgate_end\tstatus\texit\tops\tiops\tp50_us\tp99_us\tbytes\targv\n' >"$META"
    fi
}

# ---------------------------------------------------------------------------
# resume support
# ---------------------------------------------------------------------------
meta_status() {
    [[ -s $META ]] || return 0
    awk -F'\t' -v c="$1" -v e="$2" -v r="$3" \
        'NR > 1 && $1 == c && $2 == e && $6 == r { print $14; exit }' "$META"
}

meta_end_epoch_of_last() {
    [[ -s $META ]] || {
        echo 0
        return 0
    }
    local end
    end=$(awk -F'\t' 'NR > 1 { e = $8 } END { print e }' "$META")
    [[ -n $end ]] || {
        echo 0
        return 0
    }
    date -d "$end" +%s 2>/dev/null || echo 0
}

drop_meta_rows() {
    local cell=$1 engine=$2 rep=$3 tmp
    [[ -s $META ]] || return 0
    tmp=$(mktemp "${META}.tmp.XXXXXX")
    awk -F'\t' -v c="$cell" -v e="$engine" -v r="$rep" \
        'NR == 1 || !($1 == c && $2 == e && $6 == r)' "$META" >"$tmp"
    mv "$tmp" "$META"
}

cell_json_complete() {
    local engine=$1 n=$2 rep=$3 f="$CELLS_DIR/N$2__$1__r$3.json"
    [[ -s $f ]] || return 1
    python3 - "$engine" "$f" <<'PY'
import json
import sys

engine, path = sys.argv[1], sys.argv[2]
try:
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
except Exception:
    raise SystemExit(1)
if engine.startswith("fio"):
    jobs = d.get("jobs") or []
    if not jobs:
        raise SystemExit(1)
    iops = 0.0
    errs = 0
    for j in jobs:
        rd = j.get("read") or {}
        iops += float(rd.get("iops") or 0.0)
        errs += int(j.get("error") or 0) + int(rd.get("short_ios") or 0)
    raise SystemExit(0 if iops > 0 and errs == 0 else 1)
if d.get("status") != "ok":
    raise SystemExit(1)
runs = d.get("runs") or []
if not runs:
    raise SystemExit(1)
r = runs[0]
if engine == "seastar":
    man = d.get("manifest") or {}
    if man.get("reactor_backend") != "io_uring":
        raise SystemExit(1)
    if int(r.get("errors_total") or 0) != 0:
        raise SystemExit(1)
    raise SystemExit(0 if float(r.get("iops") or 0) > 0 else 1)
raise SystemExit(0 if float(r.get("iops") or 0) > 0 and int(r.get("errors") or 0) == 0 else 1)
PY
}

archive_incomplete() {
    local n=$1 engine=$2 rep=$3 id=$4 ts dest="$RESULTS/incomplete-archive" moved=0 f
    mkdir -p "$dest"
    ts=$(date +%Y%m%dT%H%M%SZ)
    for f in "$CELLS_DIR/$id.json" "$LOGS_DIR/$id.log" "$LOGS_DIR/$id.env" "$LOGS_DIR/$id.gc.log"; do
        if [[ -e $f ]]; then
            mv "$f" "$dest/$(basename "$f").$ts.incomplete"
            moved=1
        fi
    done
    drop_meta_rows "N$n" "$engine" "$rep"
    if (( moved )); then
        echo "[m3] resume: archived incomplete artifacts of $id -> $dest (ts=$ts)"
        echo "$(now_utc) archived incomplete $id -> $dest ts=$ts" >>"$RESULTS/resume.log"
    else
        echo "[m3] resume: $id has no artifacts yet (never started)" | tee -a "$RESULTS/resume.log"
    fi
}

# ---------------------------------------------------------------------------
# preflight (frozen tree + F14 + machine)
# ---------------------------------------------------------------------------
preflight() {
    local strict=$1 fail=0 rd_size govs foreign mvn_procs other_procs
    check() {
        if (( $1 )); then
            printf 'preflight: PASS  %s\n' "$2"
        else
            printf 'preflight: FAIL  %s\n' "${3:-$2}"
            fail=1
        fi
    }

    check $([[ -f $READ_FILE && -r $READ_FILE ]] && echo 1 || echo 0) \
        "read dataset present and readable: $READ_FILE" "read dataset missing/unreadable: $READ_FILE"
    rd_size=$(dataset_size)
    check $(( rd_size >= 10737418240 && rd_size % 4096 == 0 )) \
        "dataset size $rd_size B >= 10 GiB, 4K aligned" \
        "dataset size $rd_size B invalid (need >= 10 GiB, 4K aligned)"

    check $([[ -f $FROZEN_DESIGN_PATH ]] && echo 1 || echo 0) \
        "frozen design present: $FROZEN_DESIGN_PATH" "frozen design missing: $FROZEN_DESIGN_PATH"
    check $([[ $(design_sha1) == "$FROZEN_DESIGN_SHA1" ]] && echo 1 || echo 0) \
        "design sha1 == frozen $FROZEN_DESIGN_SHA1" \
        "design sha1 $(design_sha1) != frozen $FROZEN_DESIGN_SHA1 (design moved after freeze)"

    check $([[ $(src_main_sha1) == "$FROZEN_SRC_SHA1" ]] && echo 1 || echo 0) \
        "src/main/java content sha1 == B5/M3 frozen $FROZEN_SRC_SHA1" \
        "src/main/java content sha1 $(src_main_sha1) != frozen $FROZEN_SRC_SHA1 (code changed)"
    check $([[ $(src_test_sha1) == "$FROZEN_TEST_SHA1" ]] && echo 1 || echo 0) \
        "src/test/java content sha1 == B5/M3 frozen $FROZEN_TEST_SHA1" \
        "src/test/java content sha1 $(src_test_sha1) != frozen $FROZEN_TEST_SHA1 (tests changed)"
    check $([[ -z $(git -C "$REPO_ROOT" status --porcelain -- src/main/java src/test/java) ]] && echo 1 || echo 0) \
        "no uncommitted change under src/main/java or src/test/java" \
        "uncommitted change under src/main/java or src/test/java: $(git -C "$REPO_ROOT" status --porcelain -- src/main/java src/test/java)"

    check $([[ $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo 1) == 0 ]] && echo 1 || echo 0) \
        "io_uring enabled" "io_uring disabled (kernel.io_uring_disabled != 0)"
    govs=$(for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort -u | tr '\n' ' ')
    check $([[ $govs == "performance " ]] && echo 1 || echo 0) \
        "CPU governor performance on all CPUs" "CPU governor is '$govs', expected performance"

    local missing_cpus="" c
    for n in $N_LIST; do
        for c in $(echo "$(cpu_set_for_n "$n")" | tr ',' ' ' | tr '-' ' '); do
            [[ -e /sys/devices/system/cpu/cpu$c ]] || missing_cpus="$missing_cpus $c"
        done
    done
    check $([[ -z $missing_cpus ]] && echo 1 || echo 0) \
        "every frozen-placement CPU online" "frozen-placement CPUs missing:$missing_cpus"

    check $([[ -f target/classes/com/davidvlijmincx/lio/api/IoShard.class &&
        -f target/classes/com/davidvlijmincx/lio/api/RingGroup.class &&
        -f target/classes/com/davidvlijmincx/lio/api/RingMetrics.class ]] && echo 1 || echo 0) \
        "B4+B5 API classes present in target/classes (IoShard/RingGroup/RingMetrics)" \
        "target/classes lacks B4+B5 API classes -- the Lead must build the frozen tree first"

    check $([[ -x $SEASTAR_BIN ]] && echo 1 || echo 0) \
        "seastar runner executable: $SEASTAR_BIN" "seastar runner missing/not executable: $SEASTAR_BIN"

    mvn_procs=$(pgrep -af 'org.codehaus.plexus.classworlds.launcher.Launcher' 2>/dev/null || true)
    check $([[ -z $mvn_procs ]] && echo 1 || echo 0) \
        "no Maven build running" "Maven is running (must not overlap a benchmark session): $mvn_procs"

    other_procs=$(pgrep -af 'JUring(File|Stream)Bench|seastar_(file|stream)_bench|io_tester|fio ' 2>/dev/null |
        grep -v -E "pgrep|run-m3-campaign" || true)
    check $([[ -z $other_procs ]] && echo 1 || echo 0) \
        "no other benchmark harness running" "another benchmark harness is running: $other_procs"

    foreign=$(ps -eo pcpu=,pid=,comm= --sort=-pcpu |
        awk -v self="$$" '$2 != self && $1 > 20 {printf "%s(pid %s,%.0f%%) ", $3, $2, $1}')
    check $([[ -z $foreign ]] && echo 1 || echo 0) \
        "no foreign process >20% CPU" "foreign process >20% CPU: $foreign"

    if awk -v x="$(load1)" 'BEGIN{exit !(x <= 0.5)}'; then
        printf 'preflight: PASS  1-min loadavg %s <= 0.5\n' "$(load1)"
    elif [[ ${ALLOW_RESIDUAL_LOAD:-0} == 1 ]]; then
        printf 'preflight: NOTE  1-min loadavg %s > 0.5 (ALLOW_RESIDUAL_LOAD=1: own residual)\n' "$(load1)"
    else
        printf 'preflight: FAIL  1-min loadavg %s > 0.5 (F14 gate)\n' "$(load1)"
        fail=1
    fi

    for tool in "$FIO_BIN" java javac python3 taskset; do
        check $([[ -x $(command -v "$tool" 2>/dev/null) ]] && echo 1 || echo 0) \
            "tool available: $tool" "tool missing: $tool"
    done
    check $([[ -x $JURING_RUN ]] && echo 1 || echo 0) \
        "JUring runner executable: $JURING_RUN" "JUring runner missing: $JURING_RUN"
    check $([[ -f $ANALYZER ]] && echo 1 || echo 0) \
        "analyzer present: $ANALYZER" "analyzer missing: $ANALYZER"

    if [[ $strict == 1 ]] && (( fail != 0 )); then
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# plan printing (--dry-run)
# ---------------------------------------------------------------------------
print_plan() {
    local n rep order eng rows=0 blocks=0 est
    echo
    echo "================ M3 W17-W20 MULTI-SHARD SCALING CAMPAIGN PLAN (--dry-run) ================"
    echo "session dir : $RESULTS"
    echo "cells dir   : $CELLS_DIR"
    echo "aggregate   : $AGG_DIR/m3.json, $AGG_DIR/m3.md"
    echo "cells       : N in {$N_LIST} x engines {$ENGINES} (R=$REPS)"
    echo "protocol    : warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s/invocation seed=$SEED"
    echo "read file   : $READ_FILE ($(dataset_size) B, read-only DS-S; only read cells)"
    echo "placement   : N=1..4 CPUs 1..N; N=8 fio/seastar set $HCTX_CPUS; N=8 juring-group UNPINNED in that set"
    echo "JVM flags   : $JVM_OPTS (fresh JVM per invocation)"
    echo "seastar     : --smp N --cpuset <set> --memory 1G --lock-memory 0 --reactor-backend io_uring"
    echo "fio json    : group_reporting=$FIO_GROUP_REPORTING (frozen template; 1 => fio per-job spread n/a)"
    echo "identity    : design $FROZEN_DESIGN_SHA1; src/main $FROZEN_SRC_SHA1; src/test $FROZEN_TEST_SHA1"
    if (( RESUME == 1 )); then
        local skip=0 remain=0 st last
        for n in $N_LIST; do
            for rep in $(seq 1 "$REPS"); do
                for eng in $(engine_order "$n" "$rep"); do
                    st=$(meta_status "N$n" "$eng" "$rep")
                    if [[ $st == ok || $st == gate ]] && cell_json_complete "$eng" "$n" "$rep"; then
                        skip=$((skip + 1))
                    else
                        remain=$((remain + 1))
                    fi
                done
            done
        done
        last=$(meta_end_epoch_of_last)
        echo "resume state: $skip complete invocation(s) kept, $remain remaining to run"
        echo "last invocation end (epoch): $last (the driver will make up the rest before the next run)"
    fi
    echo
    for n in $N_LIST; do
        echo "--- cell block N=$n: cpuset=$(cpu_set_for_n "$n") qd/shard=$QD"
        for rep in $(seq 1 "$REPS"); do
            order=$(engine_order "$n" "$rep")
            echo "    rep $rep/$REPS (engine order: $order)"
            for eng in $order; do
                run_argv "$n" "$eng" "$rep"
                rows=$((rows + 1))
                echo "      [$rows] taskset -c $(cpu_set_for_n "$n") $(argv_str "${ARGV[@]}")"
            done
            blocks=$((blocks + 1))
        done
    done
    echo
    echo "total process invocations: $rows   blocks: $blocks"
    est=$(awk -v r="$rows" -v w="$WARMUP" -v s="$DURATION" -v rest="$REST" \
        'BEGIN{printf "%.0f min (incl. %s s rests between invocations; no process-start overhead)", r*(w+s+rest)/60, rest}')
    echo "estimated wall: $est"
    if (( MAX_BLOCKS > 0 )); then
        echo "this invocation is capped at $MAX_BLOCKS block(s) then exits cleanly (resume with --resume)"
    fi
    echo "after a complete session the driver invokes: python3 $ANALYZER --session $SESSION"
    echo "=========================================================================================="
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
echo "[m3] M3 W17-W20 multi-shard scaling campaign (docs/m3-design.md FROZEN $FROZEN_DESIGN_SHA1)"
echo "[m3] session=$SESSION N='$N_LIST' R=$REPS warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s engines='$ENGINES'"

if [[ $DRY_RUN == 1 ]]; then
    preflight -1 || true
    print_plan
    exit 0
fi

if (( RESUME == 1 && FORCE == 1 )); then
    echo "error: --resume and --force are mutually exclusive" >&2
    exit 2
fi
if (( RESUME == 1 )); then
    if [[ ! -s $META ]]; then
        echo "error: --resume needs an existing session with cell-meta.tsv: $META" >&2
        exit 1
    fi
    echo "[m3] resume mode: continuing session $SESSION (keeps complete invocations)"
elif [[ -d $CELLS_DIR ]] && compgen -G "$CELLS_DIR/*.json" >/dev/null; then
    if (( FORCE != 1 )); then
        echo "error: session '$SESSION' already contains cell JSONs under $CELLS_DIR" >&2
        echo "       start a new session with --session NAME, or pass --force to append." >&2
        echo "       (an interrupted session should be continued with --resume)" >&2
        exit 1
    fi
    echo "[m3] warning: --force given; appending to existing session $SESSION" >&2
fi

preflight 1 || {
    echo "error: preflight failed; refusing to run the campaign (see FAIL lines above)" >&2
    exit 1
}

mkdir -p "$CELLS_DIR" "$LOGS_DIR" "$AGG_DIR"
write_manifest
init_meta

# Make up the frozen inter-invocation rest across a chunk boundary before the
# first invocation of this chunk.
if (( RESUME == 1 )); then
    LAST_END_EPOCH=$(meta_end_epoch_of_last)
    echo "[m3] last invocation end (epoch): $LAST_END_EPOCH"
fi

echo "[m3] compiling the JUring harness javac-only (JURING_SKIP_MVN=1; no Maven) ..."
if ! JURING_SKIP_MVN=1 bash "$JURING_RUN" --help >/dev/null 2>"$LOGS_DIR/compile.log"; then
    echo "error: JUring harness javac compile failed; see $LOGS_DIR/compile.log" >&2
    exit 1
fi
echo "[m3] harness ready; manifest: $MANIFEST"
echo "[m3] started: $(now_utc)"

FAILS=0
RUNS_DONE=0
SKIPPED=0
BLOCKS_RUN=0
CHUNKED=0
N_TOTAL=$(wc -w <<<"$N_LIST")
E_TOTAL=$(wc -w <<<"$ENGINES")
if (( RESUME == 1 )); then
    echo "[m3] resume: scanning existing meta/artifacts under $RESULTS ..."
    mkdir -p "$RESULTS/incomplete-archive"
    : >"$RESULTS/resume.log"
    for n in $N_LIST; do
        for rep in $(seq 1 "$REPS"); do
            for eng in $(engine_order "$n" "$rep"); do
                st=$(meta_status "N$n" "$eng" "$rep")
                if [[ $st == ok || $st == gate ]] && cell_json_complete "$eng" "$n" "$rep"; then
                    SKIPPED=$((SKIPPED + 1))
                else
                    archive_incomplete "$n" "$eng" "$rep" "N${n}__${eng}__r${rep}"
                fi
            done
        done
    done
    echo "[m3] resume: $SKIPPED complete invocation(s) kept; the rest will run now"
fi

for n in $N_LIST; do
    for rep in $(seq 1 "$REPS"); do
        order=$(engine_order "$n" "$rep")
        missing=()
        for eng in $order; do
            if (( RESUME == 1 )); then
                st=$(meta_status "N$n" "$eng" "$rep")
                if [[ $st == ok || $st == gate ]] && cell_json_complete "$eng" "$n" "$rep"; then
                    continue
                fi
            fi
            missing+=("$eng")
        done
        ((${#missing[@]} == 0)) && continue
        echo "[m3] === block N=$n rep=$rep/$REPS engines missing: ${missing[*]} ==="
        for eng in "${missing[@]}"; do
            rest_before_next
            run_row "$n" "$eng" "$rep" || FAILS=$((FAILS + 1))
            RUNS_DONE=$((RUNS_DONE + 1))
        done
        if (( SETTLE > 0 )); then
            echo "[m3] settle ${SETTLE}s (not part of the frozen protocol; diagnostic only)"
            sleep "$SETTLE"
        fi
        BLOCKS_RUN=$((BLOCKS_RUN + 1))
        if (( MAX_BLOCKS > 0 && BLOCKS_RUN >= MAX_BLOCKS )); then
            CHUNKED=1
            break 2
        fi
    done
done

EXPECTED_ROWS=$((N_TOTAL * REPS * E_TOTAL))
ACTUAL_ROWS=$(( $(wc -l <"$META") - 1 ))
echo "[m3] invocation_runs=$RUNS_DONE skipped_resume=$SKIPPED failed/flagged=$FAILS blocks_run=$BLOCKS_RUN"
echo "[m3] cell-meta rows: $ACTUAL_ROWS / $EXPECTED_ROWS expected (all reps kept, none trimmed)"
if (( CHUNKED == 1 && ACTUAL_ROWS < EXPECTED_ROWS )); then
    echo "[m3] CHUNK COMPLETE (--max-blocks $MAX_BLOCKS): $(now_utc)"
    echo "[m3] resume with: bench/shard/run-m3-campaign.sh --session $SESSION --resume"
    exit 0
fi
if (( ACTUAL_ROWS != EXPECTED_ROWS )); then
    echo "warn: cell-meta row count mismatch (expected one row per invocation)" >&2
    FAILS=$((FAILS + 1))
fi
echo "[m3] cell-meta: $META"

if [[ -f $ANALYZER ]]; then
    echo "[m3] analyzing: $ANALYZER --session $SESSION"
    if python3 "$ANALYZER" --session "$SESSION" >"$AGG_DIR/analyze.log" 2>&1; then
        echo "[m3] aggregate: $AGG_DIR/m3.json, $AGG_DIR/m3.md"
    else
        echo "warn: analyzer failed; see $AGG_DIR/analyze.log" >&2
        FAILS=$((FAILS + 1))
    fi
fi

if (( FAILS > 0 )); then
    echo "error: $FAILS invocation(s) failed or were gate-flagged; inspect cell-meta.tsv and logs/" >&2
    echo "       (re-run with --resume after fixing; good cells are kept, failed cells are retried)" >&2
    exit 1
fi
echo "[m3] ok"
