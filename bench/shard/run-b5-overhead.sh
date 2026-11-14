#!/usr/bin/env bash
#
# run-b5-overhead.sh -- B5 observability overhead A/B session driver
# (docs/b5-design.md §7.1; SEASTAR-PLAN.md §4 B5 "< 1 % throughput cost").
#
# Arms (same host/JDK/flags/dataset/protocol, fresh JVM+process per invocation):
#   b4 = commit 6cc1a75, built in a scratch worktree via `git archive` + offline
#        maven (`mvn -o -q -DskipTests compile`) and its own javac bench build.
#        The workspace is NEVER edited for the baseline arm.
#   b5 = the current (frozen) workspace tree with B5 metrics ON: per-ring
#        ring_metrics, 1 Hz ring_series during the measured phase, and the shard
#        runtime's library dispatch-latency sampling (withLatencySampleEvery(1)).
#
# Cells (3): shard N=1, shard N=8, threads N=1 -- 4K randread QD 64 per worker,
# CPUs 1..N via taskset (shard mode also --first-cpu 1), the 10 GiB aged
# read-only dataset /mnt/TestData/read_files/single_file_bench.bin,
# warmup 10 s + measured 30 s, R=5 reps, 30 s rest between invocations, arms
# interleaved with the arm order alternating per rep, every rep kept.
#
# Metric (frozen): paired per-rep ratio throughput(B5)/throughput(B4) for the same
# cell/rep/position. Primary bar: mean paired ratio >= 0.99 (i.e. < 1 % cost) in
# EVERY cell. PASS also requires no individual rep below 0.95 unless it is
# state-gated and disclosed. The analyzer reports per-rep ratios, stdev, the
# one-sided 95 % t CI (df = n-1) and the state flags; it never trims a rep.
#
# State gate (docs/benchmark-methodology.md; disclosed, not trimmed):
#   - fast-state p99 < 600 us for this 4K randread QD64 protocol;
#   - IOPS anomaly rule: a rep < 0.92 x the maximum IOPS of its own (cell, arm);
#   - F14 background gate: no Maven, no other benchmark harness, no foreign
#     process > 20 % CPU, loadavg(1m) <= 0.5 (own decaying residual is recorded
#     as own-residual(la1=...) and is not foreign interference).
#   A pair whose B4 or B5 invocation is slow-state is disclosed as state-gated:
#   it is still reported and still enters the mean, but a sub-0.95 ratio there
#   is not counted as an undisclosed regression (the report says so explicitly).
#
# IMPORTANT: this session must run ALONE on the machine. The driver refuses to
# start while Maven or another benchmark harness is running. The baseline arm is
# built by `--prep-only` BEFORE the session (that is the only Maven invocation);
# the session itself only javac-compiles the B5 harness once against the Lead's
# frozen target/classes (no `mvn`) and then runs every cell with
# JURING_SKIP_COMPILE=1 / a direct java launcher.
#
# Results (gitignored): bench/results/b5-overhead-<date>/ with
#   cells/<cell>__<arm>__r<rep>.json     raw per-process JSON (+ .gc.log)
#   logs/<id>.log, logs/<id>.env         process output; loadavg/ps snapshots
#   cell-meta.tsv                         one row per invocation (all reps, never trimmed)
#   manifest.txt                          arms/build identity, host/device, protocol, gate
#   aggregate/overhead.{json,md}          written by bench/shard/analyze_b5_overhead.py
#
# Usage:
#   # 0) build the baseline scratch tree (Maven, offline) + the B5 harness; before the session
#   bench/shard/run-b5-overhead.sh --prep-only
#   # 1) plan only (no files, no I/O): preflight + exact commands + estimated wall
#   bench/shard/run-b5-overhead.sh --dry-run
#   # 2) the official session (~40 min, run alone on the machine)
#   FROZEN_SRC_SHA1=<sha> FROZEN_TEST_SHA1=<sha> FROZEN_HEAD=<sha> bench/shard/run-b5-overhead.sh
#   # resume an interrupted session (same directory; keeps complete invocations)
#   bench/shard/run-b5-overhead.sh --resume --session b5-overhead-2026-11-14
#
# Options: --session NAME, --reps N, --seconds S, --warmup S, --rest S,
#          --dry-run, --force, --resume, --prep-only, --prep-baseline,
#          --allow-unfrozen.
# Environment: READ_FILE, BASELINE_COMMIT (default 6cc1a75), BASELINE_DIR,
#   JAVA_BIN (default java), JAVAC_BIN (default javac),
#   JVM_OPTS (default "-Xms8g -Xmx8g -XX:+AlwaysPreTouch"),
#   JURING_RUN (default bench/juring/run-juring.sh),
#   FROZEN_HEAD / FROZEN_SRC_SHA1 / FROZEN_TEST_SHA1 (the Lead's freeze identity;
#   required for a real session unless --allow-unfrozen),
#   ALLOW_RESIDUAL_LOAD=1 (relax only the start-of-session loadavg gate).
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../.." && pwd)
cd "$REPO_ROOT"

SESSION=${SESSION:-b5-overhead-$(date +%Y-%m-%d)}
READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
JURING_RUN=${JURING_RUN:-$REPO_ROOT/bench/juring/run-juring.sh}
JAVA_BIN=${JAVA_BIN:-java}
JAVAC_BIN=${JAVAC_BIN:-javac}
JAVAC_RELEASE=${JAVAC_RELEASE:-25}
JVM_OPTS=${JVM_OPTS:--Xms8g -Xmx8g -XX:+AlwaysPreTouch}
BASELINE_COMMIT=${BASELINE_COMMIT:-6cc1a75}
BASELINE_DIR=${BASELINE_DIR:-$REPO_ROOT/scratch/b5-bench/baseline}
ANALYZER=$HERE/analyze_b5_overhead.py

FROZEN_HEAD=${FROZEN_HEAD:-}
FROZEN_SRC_SHA1=${FROZEN_SRC_SHA1:-}
FROZEN_TEST_SHA1=${FROZEN_TEST_SHA1:-}

QD=64
BS=4k
CPU_FIRST=1
SEED=${SEED:-20261114}
DATASET_MIN=10737418240
REPS=5
WARMUP=10
DURATION=30
REST=30
DRY_RUN=0
FORCE=0
RESUME=0
PREP_ONLY=0
PREP_BASELINE=0
ALLOW_UNFROZEN=0
CELLS="shard1 shard8 threads1"

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
        --force) FORCE=1 ;;
        --resume) RESUME=1 ;;
        --prep-only) PREP_ONLY=1 ;;
        --prep-baseline) PREP_BASELINE=1 ;;
        --allow-unfrozen) ALLOW_UNFROZEN=1 ;;
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
if ! [[ $SESSION =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "error: --session may only contain [A-Za-z0-9._-], got '$SESSION'" >&2
    exit 2
fi
if (( RESUME == 1 && FORCE == 1 )); then
    echo "error: --resume and --force are mutually exclusive" >&2
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
now_ns() { date +%s.%N; }
loadavg() { awk '{print $1","$2","$3}' /proc/loadavg; }
load1() { awk '{print $1}' /proc/loadavg; }
wall_sec() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.3f", b-a}'; }
dataset_size() { stat -c%s "$READ_FILE" 2>/dev/null || echo 0; }
head_commit() { git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown; }
src_main_sha1() { find src/main/java -type f | sort | xargs cat | sha1sum | cut -d' ' -f1; }
src_test_sha1() { find src/test/java -type f | sort | xargs cat | sha1sum | cut -d' ' -f1; }
argv_str() { printf '%q ' "$@"; }

cpu_list() { # cpu_list <N>
    if [[ $1 == 1 ]]; then
        echo "$CPU_FIRST"
    else
        echo "$CPU_FIRST-$((CPU_FIRST + $1 - 1))"
    fi
}

cell_n() {
    case $1 in
        shard1 | threads1) echo 1 ;;
        shard8) echo 8 ;;
        *) echo 0 ;;
    esac
}

cell_runtime() {
    case $1 in
        shard1 | shard8) echo shard ;;
        threads1) echo threads ;;
    esac
}

arm_label() {
    case $1 in
        b4) echo "B4 baseline (commit $BASELINE_COMMIT, no B5 metrics)" ;;
        b5) echo "B5 frozen tree, metrics on" ;;
    esac
}

# arm order alternates per rep so slow drift is common-mode (methodology D6).
arm_order() {
    local rep=$1
    if (( rep % 2 == 1 )); then
        echo "b4 b5"
    else
        echo "b5 b4"
    fi
}

# LAST_END_TS lets us distinguish our own decaying loadavg residual from foreign load.
LAST_END_TS=0

# gate_state -> "ok" | "foreign>20% CPU: ..." | "loadavg>0.5(...)" | "own-residual(la1=...)"
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

# json_summary <arm> <cell> <path> -> tab fields:
#   status schema metrics_enabled series_points ops iops p50_us p99_us bytes errors runtime shards threads
json_summary() {
    python3 - "$1" "$2" "$3" <<'PY'
import json
import sys

arm, cell, path = sys.argv[1], sys.argv[2], sys.argv[3]
fallback = ["missing", "?", "?", "?", "-1", "-1", "-1", "-1", "-1", "-1", "?", "?", "?", "?"]
try:
    with open(path, "r", encoding="utf-8") as f:
        d = json.load(f)
except Exception:
    print("\t".join(fallback))
    raise SystemExit(0)

runs = d.get("runs") or []
r = runs[0] if runs else {}
cfg = d.get("config") or {}
lat = r.get("latency_us") or {}
run_metrics = r.get("metrics") or {}
status = d.get("status") or "missing"
errs = int(r.get("errors") or 0)
iops = float(r.get("iops") or 0.0)
if status == "ok" and (errs != 0 or iops <= 0):
    status = "fail"
series_points = 0
for w in (r.get("shards") or []) + (r.get("threads") or []):
    series = w.get("ring_series")
    if isinstance(series, list):
        series_points += len(series)
if not series_points:
    series_points = int(run_metrics.get("ring_series_points") or 0)
print("\t".join([
    status, str(d.get("schema_version") or "?"),
    "on" if run_metrics.get("enabled") else "off",
    str(series_points), str(int(r.get("ops") or 0)), "%.3f" % iops,
    "%.3f" % float(lat.get("p50") or 0.0), "%.3f" % float(lat.get("p99") or 0.0),
    str(int(r.get("bytes") or 0)), str(errs),
    str(cfg.get("runtime", "threads")), str(cfg.get("shards", 0)),
    str(cfg.get("threads", 0))]))
PY
}

# run_argv <cell> <arm> <rep>: sets ARGV to the exact process command (without taskset),
# plus ID/JSON/LOG.
run_argv() {
    local cell=$1 arm=$2 rep=$3
    local n runtime cpus id json log jvm
    n=$(cell_n "$cell")
    runtime=$(cell_runtime "$cell")
    cpus=$(cpu_list "$n")
    ID="${cell}__${arm}__r${rep}"
    JSON="$CELLS_DIR/$ID.json"
    LOG="$LOGS_DIR/$ID.log"
    jvm="$JVM_OPTS -Xlog:gc:file=$LOGS_DIR/$ID.gc.log"
    ARGV=()
    if [[ $arm == b4 ]]; then
        ARGV=(env JURING_GIT_COMMIT="$BASELINE_COMMIT" JURING_GIT_DIRTY=false \
            JURING_GIT_VERSION="$BASELINE_COMMIT" \
            "$JAVA_BIN" --enable-native-access=ALL-UNNAMED)
        # shellcheck disable=SC2086
        ARGV+=($jvm)
        ARGV+=(-cp "$BASELINE_DIR/target/classes:$BASELINE_DIR/bench/juring/out"
            bench.juring.JUringFileBench
            --workload randread --file "$READ_FILE" --size "$(dataset_size)" \
            --bs "$BS" --qd "$QD" --runtime "$runtime")
    else
        ARGV=(env JURING_SKIP_COMPILE=1 JURING_JAVA_OPTS="$jvm" bash "$JURING_RUN"
            --workload randread --file "$READ_FILE" --size "$(dataset_size)" \
            --bs "$BS" --qd "$QD" --runtime="$runtime")
    fi
    if [[ $runtime == shard ]]; then
        ARGV+=(--shards "$n" --first-cpu "$CPU_FIRST")
    else
        ARGV+=(--threads "$n")
    fi
    ARGV+=(--seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1 --seed "$SEED"
        --label "$ID" --readonly --json "$JSON")
    if [[ $arm == b5 ]]; then
        ARGV+=(--metrics on --metrics-interval 1.0)
    fi
}

# run_row <cell> <arm> <rep>: one fresh process; log + JSON + env snapshots + meta row.
run_row() {
    local cell=$1 arm=$2 rep=$3
    local n runtime cpus id json log start end wall la0 la1 t0 t1 rc status gate0 gate1 p99_state
    local jstatus schema metrics_on series_points ops iops p50 p99 bytes errors jruntime jshards jthreads cmd_str
    run_argv "$cell" "$arm" "$rep"
    n=$(cell_n "$cell")
    runtime=$(cell_runtime "$cell")
    cpus=$(cpu_list "$n")
    id=$ID
    json=$JSON
    log=$LOG

    ENVF=$LOGS_DIR/$id.env
    : >"$ENVF"
    env_snapshot "$ENVF" "before"
    gate0=$(gate_state)
    if [[ $gate0 != ok && $gate0 != own-residual* ]]; then
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
    gate1=$(gate_state)
    env_snapshot "$ENVF" "after"

    status=ok
    if (( rc != 0 )); then status=fail; fi
    if [[ ! -s $json ]]; then
        status=fail
        jstatus=missing schema='?' metrics_on='?' series_points=0 ops=-1 iops=-1 p50=-1 p99=-1
        bytes=-1 errors=-1 jruntime='?' jshards='?' jthreads='?'
    else
        IFS=$'\t' read -r jstatus schema metrics_on series_points ops iops p50 p99 bytes errors \
            jruntime jshards jthreads < <(json_summary "$arm" "$cell" "$json")
        if [[ $jstatus != ok ]]; then status=fail; fi
        # Identity checks: the right arm must have really run the right topology.
        if [[ $runtime == shard ]]; then
            if [[ $jruntime != shard || $jshards != "$n" || $jthreads != "$n" ]]; then
                echo "warn: cell $id expected runtime=shard shards=$n threads=$n, JSON says runtime=$jruntime shards=$jshards threads=$jthreads" >&2
                status=fail
            fi
        else
            if [[ $jruntime == shard || $jthreads != "$n" ]]; then
                echo "warn: cell $id expected runtime=threads threads=$n, JSON says runtime=$jruntime threads=$jthreads" >&2
                status=fail
            fi
        fi
        # Arm identity: baseline must be schema 1.0 with metrics off; B5 must be 1.1 metrics on.
        if [[ $arm == b4 ]]; then
            if [[ $schema != "1.0" ]]; then
                echo "warn: cell $id expected baseline schema 1.0, JSON says $schema" >&2
                status=fail
            fi
        else
            if [[ $schema != "1.1" || $metrics_on != "on" ]]; then
                echo "warn: cell $id expected B5 schema 1.1 metrics on, JSON says schema=$schema metrics=$metrics_on" >&2
                status=fail
            fi
        fi
    fi
    # 4K randread QD64 fast-state threshold (methodology §5 / M1 calibration).
    p99_state=unknown
    if [[ $p99 != "-1" ]]; then
        if awk -v x="$p99" 'BEGIN{exit !(x < 600.0)}'; then
            p99_state=fast
        else
            p99_state="slow(p99>=600us)"
        fi
    fi
    if [[ $status == ok && $gate0 != ok && $gate0 != own-residual* ]]; then status=gate; fi
    if [[ $status == ok && $gate1 != ok && $gate1 != own-residual* ]]; then status=gate; fi

    printf 'cell %-24s status=%-4s wall=%-7ss iops=%-11s p99=%-8s state=%s gate=%s\n' \
        "$id" "$status" "$wall" "$iops" "$p99" "$p99_state" "$gate0"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$id" "$cell" "$arm" "$rep" "$cpus" "$start" "$end" "$wall" \
        "$la0" "$la1" "$gate0" "$gate1" "$p99_state" "$status" "$rc" "$ops" "$iops" "$p50" "$p99" \
        "$bytes" "$errors" "$json" "$cmd_str" >>"$META"
    [[ $status == ok || $status == gate ]]
}

# ---------------------------------------------------------------------------
# baseline scratch build (the only Maven invocation; run BEFORE the session)
# ---------------------------------------------------------------------------
baseline_identity() {
    local src_sha bench_sha classes
    if [[ ! -d $BASELINE_DIR/target/classes || ! -f $BASELINE_DIR/bench/juring/out/bench/juring/JUringFileBench.class ]]; then
        echo "missing"
        return 0
    fi
    src_sha=$(cd "$BASELINE_DIR" && find src/main/java -type f | sort | xargs cat | sha1sum | cut -d' ' -f1)
    bench_sha=$(sha256sum "$BASELINE_DIR/bench/juring/JUringFileBench.java" | cut -d' ' -f1)
    classes=$(stat -c '%y' "$BASELINE_DIR/target/classes" 2>/dev/null || echo '?')
    echo "src_main_sha1=$src_sha bench_sha256=$bench_sha target_classes_mtime='$classes'"
}

build_baseline() {
    local archive_sha
    if ! git -C "$REPO_ROOT" cat-file -e "$BASELINE_COMMIT^{commit}" 2>/dev/null; then
        echo "error: baseline commit '$BASELINE_COMMIT' not found in $REPO_ROOT" >&2
        return 1
    fi
    archive_sha=$(git -C "$REPO_ROOT" archive "$BASELINE_COMMIT" | sha1sum | cut -d' ' -f1)
    echo "[b5-overhead] building BASELINE arm: commit $BASELINE_COMMIT (archive sha1=$archive_sha)"
    echo "[b5-overhead]   scratch tree: $BASELINE_DIR (workspace is never edited for the baseline)"
    rm -rf "$BASELINE_DIR"
    mkdir -p "$BASELINE_DIR"
    git -C "$REPO_ROOT" archive "$BASELINE_COMMIT" | tar -x -C "$BASELINE_DIR"
    echo "$archive_sha" >"$BASELINE_DIR/ARCHIVE_SHA1"
    echo "[b5-overhead]   mvn -o -q -DskipTests compile (offline; before the session)"
    ( cd "$BASELINE_DIR" && mvn -o -q -DskipTests compile ) || {
        echo "error: baseline maven compile failed" >&2
        return 1
    }
    mkdir -p "$BASELINE_DIR/bench/juring/out"
    echo "[b5-overhead]   javac baseline bench (its own build)"
    "$JAVAC_BIN" -cp "$BASELINE_DIR/target/classes" -d "$BASELINE_DIR/bench/juring/out" \
        --release "$JAVAC_RELEASE" "$BASELINE_DIR/bench/juring/JUringFileBench.java" || {
        echo "error: baseline javac build failed" >&2
        return 1
    }
    echo "[b5-overhead] baseline arm identity: $(baseline_identity)"
}

build_b5_harness() {
    echo "[b5-overhead] javac-only B5 harness build against the Lead's target/classes (no Maven)"
    JURING_SKIP_MVN=1 bash "$JURING_RUN" --help >/dev/null 2>"$LOGS_DIR/compile-b5.log" || {
        echo "error: B5 harness javac compile failed; see $LOGS_DIR/compile-b5.log" >&2
        return 1
    }
    echo "[b5-overhead] B5 harness: $(sha256sum bench/juring/JUringFileBench.java | cut -d' ' -f1) $(stat -c '%y' bench/juring/out 2>/dev/null || true)"
}

# ---------------------------------------------------------------------------
# preflight
# ---------------------------------------------------------------------------
preflight() {
    local strict=$1 fail=0 rd_size govs maxn foreign mvn_procs other_procs classes
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
    check $(( rd_size >= DATASET_MIN && rd_size % 4096 == 0 )) \
        "dataset size $rd_size B >= 10 GiB, 4K aligned" \
        "dataset size $rd_size B invalid (need >= 10 GiB, 4K aligned)"
    check $([[ $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo 1) == 0 ]] && echo 1 || echo 0) \
        "io_uring enabled" "io_uring disabled (kernel.io_uring_disabled != 0)"

    govs=$(for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort -u | tr '\n' ' ')
    check $([[ $govs == "performance " ]] && echo 1 || echo 0) \
        "CPU governor performance on all CPUs" "CPU governor is '$govs', expected performance"
    maxn=8
    check $([[ -e /sys/devices/system/cpu/cpu$((CPU_FIRST + maxn - 1)) ]] && echo 1 || echo 0) \
        "CPUs $CPU_FIRST..$((CPU_FIRST + maxn - 1)) online" \
        "CPU $((CPU_FIRST + maxn - 1)) not online (needed for shard8)"

    classes=(RingMetrics LatencySnapshot ShardMetrics IoShard RingGroup JUring ShardOptions)
    for c in "${classes[@]}"; do
        check $([[ -f target/classes/com/davidvlijmincx/lio/api/$c.class ]] && echo 1 || echo 0) \
            "B5 API class present in target/classes: $c" \
            "target/classes missing $c -- the Lead must build the frozen B5 tree before the session"
    done

    check $([[ $(baseline_identity) != missing ]] && echo 1 || echo 0) \
        "baseline scratch arm built in $BASELINE_DIR" \
        "baseline scratch arm missing -- run '--prep-only' BEFORE the session (Maven, offline)"

    mvn_procs=$(pgrep -af 'org.codehaus.plexus.classworlds.launcher.Launcher' 2>/dev/null || true)
    check $([[ -z $mvn_procs ]] && echo 1 || echo 0) \
        "no Maven build running" "Maven is running (must not overlap a benchmark session): $mvn_procs"
    other_procs=$(pgrep -af 'JUring(File|Stream)Bench|seastar_(file|stream)_bench|io_tester|fio ' 2>/dev/null |
        grep -v -E "pgrep|run-b5-overhead" || true)
    check $([[ -z $other_procs ]] && echo 1 || echo 0) \
        "no other benchmark harness running" "another benchmark harness is running: $other_procs"
    foreign=$(ps -eo pcpu=,pid=,comm= --sort=-pcpu |
        awk -v self="$$" '$2 != self && $1 > 20 {printf "%s(pid %s,%.0f%%) ", $3, $2, $1}')
    check $([[ -z $foreign ]] && echo 1 || echo 0) \
        "no foreign process >20% CPU" "foreign process >20% CPU: $foreign"
    if awk -v x="$(load1)" 'BEGIN{exit !(x <= 0.5)}'; then
        printf 'preflight: PASS  1-min loadavg %s <= 0.5\n' "$(load1)"
    elif [[ ${ALLOW_RESIDUAL_LOAD:-0} == 1 ]]; then
        printf 'preflight: NOTE  1-min loadavg %s > 0.5 (ALLOW_RESIDUAL_LOAD=1: our own residual)\n' "$(load1)"
    else
        printf 'preflight: FAIL  1-min loadavg %s > 0.5 (F14 gate)\n' "$(load1)"
        fail=1
    fi

    # frozen-tree identity (G3/G4 gate): the official session must run on the tree the
    # Lead froze. A dry-run / prep may proceed with a note; a real session requires it
    # unless --allow-unfrozen.
    if [[ -z $FROZEN_SRC_SHA1 ]]; then
        if (( DRY_RUN == 1 || PREP_ONLY == 1 || ALLOW_UNFROZEN == 1 )); then
            printf 'preflight: NOTE  FROZEN_SRC_SHA1/FROZEN_TEST_SHA1/FROZEN_HEAD unset (live hashes only)\n'
        else
            printf 'preflight: FAIL  frozen-tree identity unset: export FROZEN_HEAD/FROZEN_SRC_SHA1/FROZEN_TEST_SHA1 from the Lead'"'"'s freeze (or --allow-unfrozen)\n'
            fail=1
        fi
    else
        check $([[ $(src_main_sha1) == "$FROZEN_SRC_SHA1" ]] && echo 1 || echo 0) \
            "src/main/java content sha1 == frozen $FROZEN_SRC_SHA1" \
            "src/main/java content sha1 $(src_main_sha1) != frozen $FROZEN_SRC_SHA1 (tree moved)"
        check $([[ $(src_test_sha1) == "$FROZEN_TEST_SHA1" ]] && echo 1 || echo 0) \
            "src/test/java content sha1 == frozen $FROZEN_TEST_SHA1" \
            "src/test/java content sha1 $(src_test_sha1) != frozen $FROZEN_TEST_SHA1 (tree moved)"
        if [[ -n $FROZEN_HEAD ]]; then
            check $([[ $(head_commit) == "$FROZEN_HEAD" ]] && echo 1 || echo 0) \
                "git HEAD == frozen $FROZEN_HEAD" "git HEAD $(head_commit) != frozen $FROZEN_HEAD"
        fi
    fi

    for tool in "$JAVA_BIN" "$JAVAC_BIN" python3 taskset; do
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
# manifest
# ---------------------------------------------------------------------------
write_manifest() {
    local govs rd_size nvme_line build_state baseline_ident k
    govs=$(for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort | uniq -c | tr '\n' ' ')
    rd_size=$(dataset_size)
    nvme_line=$(lsblk -o NAME,MODEL,SERIAL,SIZE --noheadings 2>/dev/null | grep -E 'nvme' || true)
    build_state=$(stat -c '%y %n' target/classes 2>/dev/null || echo 'target/classes MISSING')
    baseline_ident=$(baseline_identity)
    {
        echo "# B5 observability overhead A/B session manifest (docs/b5-design.md §7.1)"
        echo "# generated: $(date -Iseconds)  host: $(hostname)"
        echo "# workspace: $REPO_ROOT"
        echo "# M3 is NOT signed off by this session: it only proves the B5 metrics overhead bar."
        echo
        echo "## Host / kernel"
        uname -a
        echo
        echo "## CPU"
        lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket|Socket|NUMA node|MHz' || true
        echo "pinned CPU range per cell: CPUs ${CPU_FIRST}..$((CPU_FIRST + 8 - 1)) (CPUs 16-31 = SMT siblings idle)"
        echo
        echo "## CPU governor (unique values over all CPUs)"
        echo "    $govs"
        echo
        echo "## Memory / hugepages / memlock"
        grep -E 'MemTotal|MemAvailable|HugePages_Total|Hugepagesize' /proc/meminfo
        echo "ulimit -l = $(ulimit -l) KiB (registered-buffer budget for JUring)"
        echo
        echo "## Storage identity (data NVMe) and mounts"
        echo "$nvme_line"
        echo "read dataset: $READ_FILE size=$rd_size (read-only; only read cells)"
        stat -c 'stat: size=%s blocks=%b mtime=%y' "$READ_FILE" 2>/dev/null || true
        findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS --target "$READ_FILE" 2>/dev/null || \
            findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$READ_FILE" 2>/dev/null || true
        echo
        echo "## Kernel I/O controls"
        echo "kernel.io_uring_disabled = $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?')"
        echo
        echo "## Tool versions"
        echo "java: $("$JAVA_BIN" -version 2>&1 | head -1)"
        echo "javac: $("$JAVAC_BIN" -version 2>&1 | head -1)"
        echo "python3: $(python3 --version 2>&1)"
        echo "runs under taskset (util-linux): $(taskset --version 2>&1)"
        echo
        echo "## Arms"
        echo "B4 arm : commit $BASELINE_COMMIT (git archive scratch tree; no B5 metrics; schema 1.0)"
        echo "         scratch tree: $BASELINE_DIR"
        echo "         identity: $baseline_ident"
        echo "         build: mvn -o -q -DskipTests compile + javac (done by --prep-only BEFORE this session)"
        echo "         source sha256 of archived bench: $(sha256sum "$BASELINE_DIR/bench/juring/JUringFileBench.java" 2>/dev/null | cut -d' ' -f1 || echo '?')"
        echo "B5 arm : frozen workspace tree with metrics ON (schema 1.1)"
        echo "         live HEAD: $(head_commit)"
        echo "         src/main/java content sha1: $(src_main_sha1)"
        echo "         src/test/java content sha1: $(src_test_sha1)"
        echo "         frozen expected: HEAD=${FROZEN_HEAD:-<unset>} src=${FROZEN_SRC_SHA1:-<unset>} test=${FROZEN_TEST_SHA1:-<unset>}"
        echo "         target/classes state: $build_state"
        echo "         B5 harness sha256: $(sha256sum bench/juring/JUringFileBench.java | cut -d' ' -f1)"
        echo "git status (porcelain):"
        git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -40 || true
        echo
        echo "## Harness / driver sha256"
        sha256sum "$JURING_RUN" "$REPO_ROOT/bench/juring/JUringFileBench.java" \
            "$HERE/run-b5-overhead.sh" "$ANALYZER" 2>/dev/null || true
        echo
        echo "## F14 machine gate at start"
        echo "ALLOW_RESIDUAL_LOAD=${ALLOW_RESIDUAL_LOAD:-0} (1 = start allowed with a decaying"
        echo "own/verifier loadavg residual and no foreign process; residual recorded per cell)"
        echo "loadavg at manifest time: $(loadavg)"
        echo "The driver NEVER invokes Maven: baseline was built by --prep-only, the B5 harness is"
        echo "javac-only against the Lead's frozen target/classes, then every cell runs with"
        echo "JURING_SKIP_COMPILE=1 (B5) or a direct java launcher (baseline)."
        echo
        echo "## Protocol (docs/b5-design.md §7.1)"
        echo "cells: shard N=1, shard N=8, threads N=1 (juring-group / juring-manual runtimes)"
        echo "protocol: workload=randread bs=$BS qd=$QD/worker warmup=${WARMUP}s measured=${DURATION}s"
        echo "          reps=$REPS rest=${REST}s between invocations, seed=$SEED"
        echo "          CPUs 1..N via taskset; shard mode also --first-cpu $CPU_FIRST"
        echo "          arms interleaved, arm order alternates per rep: rep odd=b4,b5; rep even=b5,b4"
        echo "          fresh process/JVM per invocation; every rep kept, no trimming"
        echo "arms: b4=baseline commit $BASELINE_COMMIT (metrics off / schema 1.0);"
        echo "      b5=frozen tree, metrics on, ring_series 1.0 s, ShardOptions.withLatencySampleEvery(1)"
        echo "metric: paired per-rep ratio throughput(B5)/throughput(B4) per cell/rep/position;"
        echo "        primary bar mean ratio >= 0.99 in EVERY cell; no individual rep < 0.95"
        echo "        unless state-gated and disclosed. Report: per-rep ratios, stdev, one-sided"
        echo "        95 % t CI (df=n-1); PASS/FAIL from bench/shard/analyze_b5_overhead.py."
        echo "state gate: fast-state p99 < 600 us (4K randread QD64 protocol) + IOPS anomaly rule"
        echo "            (rep < 0.92 x max IOPS of its cell/arm) + F14 background gate; disclosed,"
        echo "            reps never trimmed."
        echo "jvm: identical flags for both arms: $JVM_OPTS (fresh JVM per invocation)"
        echo
        echo "## Exact per-cell command templates"
        echo "b4 shard  : taskset -c <1..N> env JURING_GIT_COMMIT=$BASELINE_COMMIT JURING_GIT_DIRTY=false \\"
        echo "             $JAVA_BIN --enable-native-access=ALL-UNNAMED $JVM_OPTS \\"
        echo "             -cp $BASELINE_DIR/target/classes:$BASELINE_DIR/bench/juring/out \\"
        echo "             bench.juring.JUringFileBench --workload randread --file $READ_FILE --size <bytes> \\"
        echo "             --bs $BS --qd $QD --runtime shard --shards N --first-cpu $CPU_FIRST --seconds $DURATION \\"
        echo "             --warmup-sec $WARMUP --runs 1 --seed $SEED --label <id> --readonly --json <cells/id.json>"
        echo "b4 threads: same with --runtime threads --threads 1"
        echo "b5 shard  : taskset -c <1..N> env JURING_SKIP_COMPILE=1 JURING_JAVA_OPTS='$JVM_OPTS -Xlog:gc:file=<log>' \\"
        echo "             bash bench/juring/run-juring.sh --workload randread --file $READ_FILE --size <bytes> \\"
        echo "             --bs $BS --qd $QD --runtime=shard --shards N --first-cpu $CPU_FIRST --seconds $DURATION \\"
        echo "             --warmup-sec $WARMUP --runs 1 --seed $SEED --label <id> --readonly --json <cells/id.json> \\"
        echo "             --metrics on --metrics-interval 1.0"
        echo "b5 threads: same with --runtime=threads --threads 1"
        echo
        echo "started: $(now_utc)"
    } >"$MANIFEST"
}

init_meta() {
    if [[ ! -s $META ]]; then
        printf 'id\tcell\tarm\trep\tcpus\tstart_utc\tend_utc\twall_s\tloadavg_start\tloadavg_end\tgate_start\tgate_end\tp99_state\tstatus\texit\tops\tiops\tp50_us\tp99_us\tbytes\terrors\tjson\targv\n' >"$META"
    fi
}

# complete-cell helpers for --resume
meta_status() {
    [[ -s $META ]] || return 0
    awk -F'\t' -v id="$1" 'NR > 1 && $1 == id { print $14; exit }' "$META"
}

drop_meta_rows() {
    local id=$1 tmp
    [[ -s $META ]] || return 0
    tmp=$(mktemp "${META}.tmp.XXXXXX")
    awk -F'\t' -v id="$id" 'NR == 1 || $1 != id' "$META" >"$tmp"
    mv "$tmp" "$META"
}

cell_json_complete() {
    local arm=$1 cell=$2 rep=$3 f="$CELLS_DIR/${cell}__${arm}__r${rep}.json"
    [[ -s $f ]] || return 1
    python3 - "$arm" "$f" <<'PY'
import json
import sys

arm, path = sys.argv[1], sys.argv[2]
try:
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
except Exception:
    raise SystemExit(1)
runs = d.get("runs") or []
if d.get("status") != "ok" or not runs:
    raise SystemExit(1)
r = runs[0]
iops = float(r.get("iops") or 0.0)
if iops <= 0 or int(r.get("errors") or 0) != 0:
    raise SystemExit(1)
schema = str(d.get("schema_version") or "")
if arm == "b4" and schema != "1.0":
    raise SystemExit(1)
if arm == "b5":
    m = r.get("metrics") or {}
    if schema != "1.1" or not m.get("enabled"):
        raise SystemExit(1)
raise SystemExit(0)
PY
}

archive_incomplete() {
    local cell=$1 arm=$2 rep=$3 id="${cell}__${arm}__r${rep}" ts dest="$RESULTS/incomplete-archive" moved=0 f
    mkdir -p "$dest"
    ts=$(date +%Y%m%dT%H%M%SZ)
    for f in "$CELLS_DIR/$id.json" "$LOGS_DIR/$id.log" "$LOGS_DIR/$id.env" "$LOGS_DIR/$id.gc.log"; do
        if [[ -e $f ]]; then
            mv "$f" "$dest/$(basename "$f").$ts.incomplete"
            moved=1
        fi
    done
    drop_meta_rows "$id"
    if (( moved )); then
        echo "[b5-overhead] resume: archived incomplete artifacts of $id -> $dest (ts=$ts)" |
            tee -a "$RESULTS/resume.log"
    fi
}

# ---------------------------------------------------------------------------
# plan (--dry-run)
# ---------------------------------------------------------------------------
print_plan() {
    local cell rep arm rows=0 total invocations
    total=$(( $(wc -w <<<"$CELLS") * REPS * 2 ))
    echo
    echo "=============== B5 OBSERVABILITY OVERHEAD A/B PLAN (--dry-run; nothing executed) ==============="
    echo "session dir : $RESULTS"
    echo "cells dir   : $CELLS_DIR"
    echo "aggregate   : $AGG_DIR/overhead.json, $AGG_DIR/overhead.md"
    echo "arms        : b4=$BASELINE_COMMIT (baseline, metrics off)  b5=frozen tree (metrics on)"
    echo "cells       : $CELLS  (shard N=1, shard N=8, threads N=1; 4K randread QD $QD/worker)"
    echo "protocol    : R=$REPS warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s seed=$SEED"
    echo "read file   : $READ_FILE ($(dataset_size) B, read-only)"
    echo "JVM flags   : $JVM_OPTS (identical for both arms; fresh JVM/process per invocation)"
    echo "state gate  : fast p99 < 600 us + IOPS anomaly rep < 0.92 x max (cell,arm) + F14; disclosed"
    echo "frozen tree : HEAD=${FROZEN_HEAD:-<unset>} src=${FROZEN_SRC_SHA1:-<unset>} test=${FROZEN_TEST_SHA1:-<unset>}"
    echo "baseline    : $(baseline_identity)  ($BASELINE_DIR)"
    echo
    for cell in $CELLS; do
        echo "--- cell $cell: runtime=$(cell_runtime "$cell") N=$(cell_n "$cell") cpus=$(cpu_list "$(cell_n "$cell")") qd/worker=$QD"
        for rep in $(seq 1 "$REPS"); do
            echo "    rep $rep/$REPS (arm order: $(arm_order "$rep"))"
            for arm in $(arm_order "$rep"); do
                run_argv "$cell" "$arm" "$rep"
                rows=$((rows + 1))
                echo "      [$rows] taskset -c $(cpu_list "$(cell_n "$cell")") $(argv_str "${ARGV[@]}")"
            done
            if (( rep < REPS )); then
                echo "      rest ${REST}s"
            fi
        done
    done
    echo
    invocations=$rows
    echo "total process invocations: $invocations (all reps kept, no trimming)"
    echo "estimated minimum wall: $(awk -v n="$invocations" -v w="$WARMUP" -v s="$DURATION" \
        -v r="$REST" 'BEGIN{printf "%.0f min (bench only: n*(warmup+measured) + (n-1)*rest; no VM/JIT start overhead)", (n*(w+s) + (n-1)*r)/60}')"
    echo "after a real run the driver invokes: python3 $ANALYZER --session $SESSION"
    echo "================================================================================================"
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
echo "[b5-overhead] B5 observability overhead A/B (docs/b5-design.md §7.1; M3 NOT signed off)"
echo "[b5-overhead] session=$SESSION reps=$REPS cells='$CELLS' baseline=$BASELINE_COMMIT"

if (( PREP_ONLY == 1 || PREP_BASELINE == 1 )); then
    mkdir -p "$LOGS_DIR" "$AGG_DIR"
    build_baseline || exit 1
    if (( PREP_ONLY == 1 )); then
        build_b5_harness || exit 1
        echo "[b5-overhead] prep done; baseline and B5 harness are built (run '--dry-run' next)"
    else
        echo "[b5-overhead] baseline prep done; the B5 harness is built at session start"
    fi
    exit 0
fi

if [[ $DRY_RUN == 1 ]]; then
    preflight -1 || true
    print_plan
    exit 0
fi

if (( RESUME == 1 )); then
    if [[ ! -s $META ]]; then
        echo "error: --resume needs an existing session with cell-meta.tsv: $META" >&2
        exit 1
    fi
    echo "[b5-overhead] resume mode: continuing session $SESSION (keeps complete invocations)"
elif [[ -d $CELLS_DIR ]] && compgen -G "$CELLS_DIR/*.json" >/dev/null; then
    if (( FORCE != 1 )); then
        echo "error: session '$SESSION' already contains cell JSONs under $CELLS_DIR" >&2
        echo "       start a new session with --session NAME, or pass --force to append." >&2
        exit 1
    fi
    echo "[b5-overhead] warning: --force given; appending to existing session $SESSION" >&2
fi

preflight 1 || {
    echo "error: preflight failed; refusing to run the A/B session (see FAIL lines above)" >&2
    exit 1
}

mkdir -p "$CELLS_DIR" "$LOGS_DIR" "$AGG_DIR"
write_manifest
init_meta

build_b5_harness || exit 1
echo "[b5-overhead] manifest: $MANIFEST"
echo "[b5-overhead] started: $(now_utc) loadavg=$(loadavg)"

FAILS=0
ROWS_DONE=0
SKIPPED=0
if (( RESUME == 1 )); then
    mkdir -p "$RESULTS/incomplete-archive"
    : >"$RESULTS/resume.log"
    for cell in $CELLS; do
        for rep in $(seq 1 "$REPS"); do
            for arm in $(arm_order "$rep"); do
                id="${cell}__${arm}__r${rep}"
                st=$(meta_status "$id")
                if [[ $st == ok || $st == gate ]] && cell_json_complete "$arm" "$cell" "$rep"; then
                    SKIPPED=$((SKIPPED + 1))
                    echo "[b5-overhead] resume: keep $id (status=$st)"
                else
                    archive_incomplete "$cell" "$arm" "$rep"
                fi
            done
        done
    done
    echo "[b5-overhead] resume: $SKIPPED complete invocation(s) kept"
fi

N_TOTAL=$(wc -w <<<"$CELLS")
N_IDX=0
for cell in $CELLS; do
    N_IDX=$((N_IDX + 1))
    for rep in $(seq 1 "$REPS"); do
        for arm in $(arm_order "$rep"); do
            id="${cell}__${arm}__r${rep}"
            if (( RESUME == 1 )); then
                st=$(meta_status "$id")
                if [[ $st == ok || $st == gate ]] && cell_json_complete "$arm" "$cell" "$rep"; then
                    continue
                fi
            fi
            if (( ROWS_DONE > 0 )); then
                echo "[b5-overhead] rest ${REST}s before $id (loadavg=$(loadavg))"
                sleep "$REST"
            fi
            run_row "$cell" "$arm" "$rep" || FAILS=$((FAILS + 1))
            ROWS_DONE=$((ROWS_DONE + 1))
        done
    done
done

EXPECTED=$(( N_TOTAL * REPS * 2 ))
ACTUAL=$(( $(wc -l <"$META") - 1 ))
echo "[b5-overhead] session done: $(now_utc) ran=$ROWS_DONE skipped_resume=$SKIPPED failed/flagged=$FAILS"
echo "[b5-overhead] cell-meta rows: $ACTUAL / $EXPECTED expected (all reps kept, none trimmed)"
if (( ACTUAL != EXPECTED )); then
    echo "warn: cell-meta row count mismatch -- expected one row per invocation" >&2
    FAILS=$((FAILS + 1))
fi
echo "[b5-overhead] cell-meta: $META"

if [[ -f $ANALYZER ]]; then
    echo "[b5-overhead] analyzing: $ANALYZER --session $SESSION"
    rc_a=0
    if ! python3 "$ANALYZER" --session "$SESSION" \
        --baseline-commit "$BASELINE_COMMIT" \
        --frozen-head "${FROZEN_HEAD:-}" \
        --frozen-src-sha1 "${FROZEN_SRC_SHA1:-}" \
        --frozen-test-sha1 "${FROZEN_TEST_SHA1:-}" \
        >"$AGG_DIR/analyze.log" 2>&1; then
        rc_a=$?
    fi
    cat "$AGG_DIR/analyze.log"
    if (( rc_a == 0 )); then
        echo "[b5-overhead] aggregate: $AGG_DIR/overhead.json, $AGG_DIR/overhead.md"
    else
        echo "warn: analyzer verdict is not PASS (exit $rc_a); see $AGG_DIR/analyze.log" >&2
        FAILS=$((FAILS + 1))
    fi
fi

if (( FAILS > 0 )); then
    echo "error: $FAILS invocation(s) failed / were gate-flagged / the overhead bar missed;" >&2
    echo "       inspect cell-meta.tsv, logs/ and aggregate/overhead.md" >&2
    exit 1
fi
echo "[b5-overhead] ok"
