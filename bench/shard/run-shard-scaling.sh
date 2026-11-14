#!/usr/bin/env bash
#
# run-shard-scaling.sh -- B4 preliminary W17-W20 shard-scaling driver
# (docs/b4-design.md §13; SEASTAR-PLAN.md §5 M3; benchmark-methodology W17-W20).
#
# Cells: N in {1,2,4,8}, all pinned to CPUs 1..N (F3/F4), 4K randread, QD 64 per
# worker, one 10 GiB aged read-only dataset (DS-S), 10 s warmup + 30 s measured,
# R=3 reps, 60 s rest between rep blocks, fresh process/JVM per invocation:
#
#   juring-group-N   bench/juring/JUringFileBench --runtime=shard --shards N
#                    (one IoShard/ring per worker through RingGroup, handler-driven
#                    QD-64 pipeline; per-shard IOPS/latency + ShardStats in JSON)
#   juring-manual-N  bench/juring/JUringFileBench --runtime=threads --threads N
#                    (the historical harness: one Java thread + one ring per worker)
#   fio-N            fio io_uring numjobs=N, matched registration
#                    (--registerfiles=1 --fixedbufs=1 --iodepth=64 --direct=1)
#
# Protocol: warmup 10 s + measured 30 s per process, R=3, 60 s rest; engines are
# interleaved inside each rep and the engine order alternates per rep (D6 drift
# cancellation). Only read cells are run; the dataset is opened read-only and
# fio is given --readonly. No write, no seastar (out of scope this session).
#
# PRELIMINARY: R=3, one session, single shared file for all shards (not the DS-M
# one-file-per-shard layout of the full W18-W20 campaign), no 1 s state series.
# This can support "the B4 runtime scales", NOT the M3 sign-off (B5 metrics, the
# full R=5 campaign including seastar --smp N are still missing).
#
# Results (gitignored): bench/results/<session>/ with
#   cells/N<N>__<engine>__r<rep>.json    raw per-process JSON (+ .gc.log for JUring)
#   logs/<id>.log, logs/<id>.env         process output; loadavg/uptime/ps snapshots
#   cell-meta.tsv                        one row per invocation (all reps, never trimmed)
#   manifest.txt                         host/kernel/JDK/CPU/NVMe/commit/protocol manifest
#   aggregate/shard.{json,md}            written by bench/shard/analyze_shard.py
#
# Re-running: sessions do not overwrite each other. Start a new one with
# `--session NAME` (or pass --force to deliberately append).
#
# Usage:
#   bench/shard/run-shard-scaling.sh [--dry-run] [--session NAME]
#       [--n-list "1,2,4,8"] [--reps N] [--seconds S] [--warmup S]
#       [--rest S] [--settle S] [--force] [--resume]
#
# --resume continues an interrupted session in the SAME directory: it reads
# cell-meta.tsv, keeps every invocation whose meta status is ok/gate AND whose
# JSON validates, moves the artifacts of incomplete invocations to
# results/<session>/incomplete-archive/ (with the old meta row dropped), and
# runs only the remaining invocations (same protocol/order/rests). It never
# duplicates a completed good cell and never trims a rep silently.
#
# Environment:
#   READ_FILE=/mnt/TestData/read_files/single_file_bench.bin
#   JURING_RUN=bench/juring/run-juring.sh   FIO_BIN=fio
#   JVM_OPTS="-Xms8g -Xmx8g -XX:+AlwaysPreTouch"   (identical for both JUring engines, F8)
#   FIO_GROUP_REPORTING=1   frozen matched template value (fio 3.40 then emits ONE
#                           aggregated JSON job; per-job fio spread is reported n/a)
#   ALLOW_RESIDUAL_LOAD=1   relax the start-of-session loadavg gate ONLY (the
#                           foreign-process >20% CPU gate always stays strict)
#
# IMPORTANT (G5): this script must run alone on the machine. It refuses to start
# while Maven/another benchmark harness is running, and it never invokes mvn:
# the B4 harness is compiled javac-only against target/classes that the Lead
# builds on the frozen tree before the session.
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../.." && pwd)
cd "$REPO_ROOT"

SESSION=${SESSION:-b4-$(date +%Y-%m-%d)}
READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
JURING_RUN=${JURING_RUN:-$REPO_ROOT/bench/juring/run-juring.sh}
FIO_BIN=${FIO_BIN:-fio}
ANALYZER=$HERE/analyze_shard.py
JVM_OPTS=${JVM_OPTS:--Xms8g -Xmx8g -XX:+AlwaysPreTouch}
FIO_GROUP_REPORTING=${FIO_GROUP_REPORTING:-1}

QD=64
BS=4k
# Lead's G3 frozen-tree identity (docs/b4-design.md §14 G3/G5). The session must
# run on exactly this tree; the driver refuses to start on a different one.
FROZEN_HEAD=${FROZEN_HEAD:-25c664b28956992a8f35244ee0100ad79883d5c7}
FROZEN_SRC_SHA1=${FROZEN_SRC_SHA1:-93ce8a716c0f3b56e9ce76b8806db5eff99fc66f}
FROZEN_TEST_SHA1=${FROZEN_TEST_SHA1:-74f9c344596a4a7c8353f915b9dcbdf0ebae2f37}
SEED=${SEED:-20261114}
CPU_FIRST=1
DATASET_MIN=10737418240          # 10 GiB expected read dataset
N_LIST="1 2 4 8"
ALL_N="1 2 4 8"
REPS=3
WARMUP=10
DURATION=30
REST=60
SETTLE=30
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
N_LIST=$(echo "$N_LIST" | tr -s ' ')
for n in $N_LIST; do
    case " $ALL_N " in
        *" $n "*) ;;
        *)
            echo "error: unknown N '$n' (this session supports: $ALL_N)" >&2
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

cpu_list() { # cpu_list <N>
    if [[ $1 == 1 ]]; then
        echo "$CPU_FIRST"
    else
        echo "$CPU_FIRST-$((CPU_FIRST + $1 - 1))"
    fi
}

dataset_size() { stat -c%s "$READ_FILE" 2>/dev/null || echo 0; }

# Lead's frozen-tree content hash convention: sorted src/main/java file contents.
src_main_sha1() { find src/main/java -type f | sort | xargs cat | sha1sum | cut -d' ' -f1; }
src_test_sha1() { find src/test/java -type f | sort | xargs cat | sha1sum | cut -d' ' -f1; }
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

# gate_state -> "ok" | "foreign>20% CPU: ..." | "loadavg>0.5" | "own-residual(la1=X)"
# Our own benchmark threads are runnable, so loadavg(1m) stays well above 0.5 for
# minutes after a cell (it is an exponential average over runnable tasks). That
# residual is NOT foreign load: it is recorded verbatim as "own-residual" and is
# not a gate violation. Only a foreign process >20% CPU, or a loadavg>0.5 that our
# own recent run cannot explain, invalidates a cell.
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

# engine_order <N> <rep> -> space separated engine order for this rep (D6: the
# engine order alternates so slow drift is common-mode across engines).
engine_order() {
    local n=$1 rep=$2
    if (( (rep + n) % 2 == 1 )); then
        echo "juring-group juring-manual fio"
    else
        echo "fio juring-manual juring-group"
    fi
}

# run_argv <N> <engine> <rep>
# Sets the global array ARGV to the exact process command (without taskset) and
# ID/JSON/LOG to the cell identity/paths.
run_argv() {
    local n=$1 engine=$2 rep=$3
    local cpu_list id jvm
    cpu_list=$(cpu_list "$n")
    ID="N${n}__${engine}__r${rep}"
    JSON="$CELLS_DIR/$ID.json"
    LOG="$LOGS_DIR/$ID.log"
    ARGV=()
    case $engine in
        juring-group)
            jvm="$JVM_OPTS -Xlog:gc:file=$LOGS_DIR/$ID.gc.log"
            # shellcheck disable=SC2086
            ARGV=(env JURING_SKIP_COMPILE=1 JURING_JAVA_OPTS="$jvm" bash "$JURING_RUN"
                --workload randread --file "$READ_FILE" --size "$(dataset_size)" \
                --bs "$BS" --qd "$QD" --runtime=shard --shards "$n" --first-cpu "$CPU_FIRST" \
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
    esac
}

# run_row <N> <engine> <rep>: one fresh process, log + JSON + env snapshots + meta row.
run_row() {
    local n=$1 engine=$2 rep=$3
    local cpu_list id json log start end wall la0 la1 t0 t1 rc status gate0 gate1
    local jstatus ops iops p50 p99 bytes errors runtime shards threads cmd_str
    run_argv "$n" "$engine" "$rep"
    id=$ID
    json=$JSON
    log=$LOG
    cpu_list=$(cpu_list "$n")

    ENVF=$LOGS_DIR/$id.env
    : >"$ENVF"
    env_snapshot "$ENVF" "before"
    gate0=$(gate_state)
    if [[ $gate0 != ok ]]; then
        echo "warn: F14 gate violation before $id: $gate0 (recorded, still run; not trimmed)" >&2
    fi

    if [[ $engine == fio ]]; then
        cmd_str="taskset -c $cpu_list $(argv_str "${ARGV[@]}")"
    else
        cmd_str="taskset -c $cpu_list $(argv_str "${ARGV[@]}")"
    fi
    start=$(now_utc)
    la0=$(loadavg)
    t0=$(now_ns)
    rc=0
    taskset -c "$cpu_list" "${ARGV[@]}" >"$log" 2>&1 || rc=$?
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
        jstatus=missing ops=-1 iops=-1 p50=-1 p99=-1 bytes=-1 errors=-1 runtime='?' shards='?' threads='?'
    else
        IFS=$'\t' read -r jstatus ops iops p50 p99 bytes errors runtime shards threads < <(json_summary "$engine" "$n" "$json")
        if [[ $jstatus != ok ]]; then status=fail; fi
        # Identity checks: a cell must really have run with the expected topology,
        # otherwise the ratio would silently compare the wrong engines.
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
        fi
    fi
    if [[ $status == ok && $gate0 != ok && $gate0 != own-residual* ]]; then status=gate; fi
    if [[ $status == ok && $gate1 != ok && $gate1 != own-residual* ]]; then status=gate; fi

    printf 'cell %-28s status=%-4s wall=%-7ss iops=%-11s p50_us=%-8s p99_us=%-8s ops=%s gate=%s\n' \
        "$id" "$status" "$wall" "$iops" "$p50" "$p99" "$ops" "$gate0"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "N$n" "$engine" "std" "$n" "$cpu_list" "$rep" "$start" "$end" "$wall" \
        "$la0" "$la1" "$gate0" "$gate1" "$status" "$rc" "$ops" "$iops" "$p50" "$p99" \
        "$bytes" "$cmd_str" >>"$META"
    [[ $status == ok || $status == gate ]]
}

# ---------------------------------------------------------------------------
# manifest.txt
# ---------------------------------------------------------------------------
write_manifest() {
    local govs rd_size nvme_line build_state
    govs=$(for f in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort | uniq -c | tr '\n' ' ')
    rd_size=$(dataset_size)
    nvme_line=$(lsblk -o NAME,MODEL,SERIAL,SIZE --noheadings 2>/dev/null | grep -E 'nvme' || true)
    build_state=$(stat -c '%y %n' target/classes 2>/dev/null || echo 'target/classes MISSING')
    {
        echo "# B4 preliminary W17-W20 shard-scaling session manifest"
        echo "# generated: $(date -Iseconds)  host: $(hostname)"
        echo "# workspace: $REPO_ROOT"
        echo
        echo "## Host / kernel"
        uname -a
        echo
        echo "## CPU"
        lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket|Socket|NUMA node|MHz' || true
        echo "pinned CPU range for N workers: ${CPU_FIRST}..$((CPU_FIRST + 8 - 1)) (CPUs 16-31 = SMT siblings idle)"
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
        echo "read dataset: $READ_FILE size=$rd_size (read-only, only read cells are run)"
        stat -c 'stat: size=%s blocks=%b mtime=%y' "$READ_FILE" 2>/dev/null || true
        findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS --target "$READ_FILE" 2>/dev/null || \
            findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$READ_FILE" 2>/dev/null || true
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
        echo
        echo "## Repo state / B4 harness build (G3 frozen tree)"
        echo "G3 frozen HEAD (expected): $FROZEN_HEAD"
        echo "G3 frozen v2 src/main/java content sha1 (expected): $FROZEN_SRC_SHA1"
        echo "G3 frozen v2 src/test/java content sha1 (expected): $FROZEN_TEST_SHA1"
        echo "live HEAD: $(head_commit)"
        echo "live src/main/java content sha1: $(src_main_sha1)"
        echo "live src/test/java content sha1: $(src_test_sha1)"
        echo "frozen-tree convention: find src/main/java -type f | sort | xargs cat | sha1sum"
        echo "freeze v2 note: F1 CompletionTable fix applied by b4-engineer before freeze v2;"
        echo "note: the B4 working tree is intentionally uncommitted during this session"
        echo "      (base commit 25c664b + uncommitted B4 changes; git dirty=true expected);"
        echo "      identity is the G3 hashes above, not only the commit id."
        echo "git commit: $(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "git describe: $(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
        echo "git status (porcelain):"
        git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -30 || true
        echo "target/classes state: $build_state"
        echo "build: the JUring harness classes were compiled by the Lead on the frozen tree;"
        echo "       this session only runs 'JURING_SKIP_MVN=1 bench/juring/run-juring.sh --help'"
        echo "       (javac only, no Maven) and then 'JURING_SKIP_COMPILE=1' for every cell."
        echo
        echo "## Harness / driver sha256"
        sha256sum "$JURING_RUN" "$REPO_ROOT/bench/juring/JUringFileBench.java" \
            "$HERE/run-shard-scaling.sh" "$ANALYZER" 2>/dev/null || true
        sha256sum "$(command -v "$FIO_BIN")" 2>/dev/null || true
        echo
        echo "## F14 machine gate at start"
        echo "ALLOW_RESIDUAL_LOAD=${ALLOW_RESIDUAL_LOAD:-0} (1 = start allowed with a decaying"
        echo "own/verifier loadavg residual and no foreign process; residual recorded per cell)"
        echo "loadavg at manifest time: $(loadavg)"
        echo
        echo "## Protocol (docs/b4-design.md §13; PRELIMINARY -- M3 is NOT signed off)"
        echo "cells: N in {$(echo "$N_LIST" | tr ' ' ',')} x {juring-group, juring-manual, fio}"
        echo "protocol: bs=$BS qd=$QD/shard warmup=${WARMUP}s measured=${DURATION}s reps=$REPS"
        echo "          rest=${REST}s between rep blocks, settle=${SETTLE}s between N values, seed=$SEED"
        echo "engine order: alternates per rep (juring-group,manual,fio / fio,manual,group)"
        echo "engines: one fresh process per invocation, all under taskset -c <1..N>"
        echo "dataset: ONE shared 10 GiB read-only file for every engine/shard (W18-W20's"
        echo "         one-file-per-shard DS-M layout is not used here; recorded limitation)"
        echo "juring : io_uring; SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN; entries=max(256,2*qd);"
        echo "         registered file + registered fixed buffers; per-worker QD $QD."
        echo "         runtime=shard: One IoShard/ring per worker through RingGroup, readFixed,"
        echo "         CPUs ${CPU_FIRST}..$((CPU_FIRST + 8 - 1)) best-effort pinned; completion path ="
        echo "         ctx.onCompletion handlers dispatched by the shard poll loop."
        echo "         runtime=threads: one plain Java thread + one ring per worker (historical)."
        echo "fio    : io_uring numjobs=N numjobs matched: --registerfiles=1 --fixedbufs=1 --iodepth=$QD"
        echo "         --group_reporting=$FIO_GROUP_REPORTING (frozen template; 1 => fio JSON has one"
        echo "         aggregated job => fio per-job spread is n/a)"
        echo "jvm    : identical flags for both JUring engines: $JVM_OPTS (fresh JVM per rep)"
        echo "only read cells: --readonly for fio, O_DIRECT read-only fds for JUring; no write cell."
        echo
        echo "## Exact per-cell command templates"
        echo "group  : taskset -c <1..N> env JURING_SKIP_COMPILE=1 JURING_JAVA_OPTS='$JVM_OPTS -Xlog:gc:file=<log>' \\"
        echo "         bash bench/juring/run-juring.sh --workload randread --file $READ_FILE --size <bytes> \\"
        echo "         --bs $BS --qd $QD --runtime=shard --shards N --first-cpu $CPU_FIRST --seconds $DURATION \\"
        echo "         --warmup-sec $WARMUP --runs 1 --seed $SEED --label <id> --readonly --json <cells/id.json>"
        echo "manual : same with --runtime=threads --threads N"
        echo "fio    : taskset -c <1..N> fio --name=<id> --filename=$READ_FILE --readonly --direct=1 --rw=randread \\"
        echo "         --bs=$BS --iodepth=$QD --numjobs=N --ioengine=io_uring --registerfiles=1 --fixedbufs=1 \\"
        echo "         --randrepeat=1 --time_based --runtime=$DURATION --ramp_time=$WARMUP \\"
        echo "         --clocksource=clock_gettime --percentile_list=50:95:99:99.9 --clat_percentiles=1 \\"
        echo "         --lat_percentiles=0 --group_reporting=$FIO_GROUP_REPORTING --output-format=json --output=<cells/id.json>"
        echo
        echo "started: $(now_utc)"
    } >"$MANIFEST"
}

init_meta() {
    if [[ ! -s $META ]]; then
        printf 'cell\tengine\tvariant\tN\tcpus\trep\tstart_utc\tend_utc\twall_s\tloadavg_start\tloadavg_end\tgate_start\tgate_end\tstatus\texit\tops\tiops\tp50_us\tp99_us\tbytes\targv\n' >"$META"
    fi
}

# ---------------------------------------------------------------------------
# resume support (--resume): keep complete invocations, rerun only the rest
# ---------------------------------------------------------------------------
# meta_status <cell> <engine> <rep> -> status field or empty
meta_status() {
    [[ -s $META ]] || return 0
    awk -F'\t' -v c="$1" -v e="$2" -v r="$3" \
        'NR > 1 && $1 == c && $2 == e && $6 == r { print $14; exit }' "$META"
}

# drop_meta_rows <cell> <engine> <rep> -- remove one invocation's meta row(s)
drop_meta_rows() {
    local cell=$1 engine=$2 rep=$3 tmp
    [[ -s $META ]] || return 0
    tmp=$(mktemp "${META}.tmp.XXXXXX")
    awk -F'\t' -v c="$cell" -v e="$engine" -v r="$rep" \
        'NR == 1 || !($1 == c && $2 == e && $6 == r)' "$META" >"$tmp"
    mv "$tmp" "$META"
}

# cell_json_complete <engine> <N> <rep> -> 0 when the raw JSON is a valid,
# clean run of the expected engine (juring status=ok/errors=0/iops>0; fio
# jobs exist, total read IOPS>0, no job error/short_ios).
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
raise SystemExit(0 if float(r.get("iops") or 0) > 0 and int(r.get("errors") or 0) == 0 else 1)
PY
}

# archive_incomplete <N> <engine> <rep> <id>: move the artifacts of an incomplete
# invocation out of cells/logs (so the analyzer cannot pick up garbage) and drop
# its stale meta row. The move is recorded for disclosure, never silently deleted.
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
        echo "[shard-matrix] resume: archived incomplete artifacts of $id -> $dest (ts=$ts)"
        echo "$(now_utc) archived incomplete $id -> $dest ts=$ts" >>"$RESULTS/resume.log"
    else
        echo "[shard-matrix] resume: $id has no artifacts yet (never started)" | tee -a "$RESULTS/resume.log"
    fi
}

# ---------------------------------------------------------------------------
# preflight (F3/F4/F14 + "no Maven / no other harness")
# ---------------------------------------------------------------------------
preflight() {
    local strict=$1 fail=0 rd_size govs maxn foreign mvn_procs other_procs
    check() { # check <ok> <message> [failure-message]
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

    maxn=$(echo "$N_LIST" | tr ' ' '\n' | sort -n | tail -1)
    check $([[ -e /sys/devices/system/cpu/cpu$((CPU_FIRST + maxn - 1)) ]] && echo 1 || echo 0) \
        "CPUs $CPU_FIRST..$((CPU_FIRST + maxn - 1)) online" \
        "CPU $((CPU_FIRST + maxn - 1)) not online (needed for N=$maxn)"

    check $([[ -f target/classes/com/davidvlijmincx/lio/api/IoShard.class &&
        -f target/classes/com/davidvlijmincx/lio/api/RingGroup.class ]] && echo 1 || echo 0) \
        "B4 API classes present in target/classes (IoShard, RingGroup)" \
        "target/classes has no B4 IoShard/RingGroup -- the Lead must build the frozen tree before G5"

    check $([[ $(head_commit) == "$FROZEN_HEAD" ]] && echo 1 || echo 0) \
        "git HEAD == G3 frozen commit $FROZEN_HEAD" \
        "git HEAD $(head_commit) != G3 frozen commit $FROZEN_HEAD (tree moved after G3)"
    check $([[ $(src_main_sha1) == "$FROZEN_SRC_SHA1" ]] && echo 1 || echo 0) \
        "src/main/java content sha1 == G3 frozen v2 $FROZEN_SRC_SHA1" \
        "src/main/java content sha1 $(src_main_sha1) != G3 frozen v2 $FROZEN_SRC_SHA1 (src edited after G3)"
    check $([[ $(src_test_sha1) == "$FROZEN_TEST_SHA1" ]] && echo 1 || echo 0) \
        "src/test/java content sha1 == G3 frozen v2 $FROZEN_TEST_SHA1" \
        "src/test/java content sha1 $(src_test_sha1) != G3 frozen v2 $FROZEN_TEST_SHA1 (tests edited after G3)"

    mvn_procs=$(pgrep -af 'org.codehaus.plexus.classworlds.launcher.Launcher' 2>/dev/null || true)
    check $([[ -z $mvn_procs ]] && echo 1 || echo 0) \
        "no Maven build running" "Maven is running (must not overlap a benchmark session): $mvn_procs"

    other_procs=$(pgrep -af 'JUring(File|Stream)Bench|seastar_(file|stream)_bench|io_tester|fio ' 2>/dev/null |
        grep -v -E "pgrep|run-shard-scaling" || true)
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
    local n rep order eng rows=0
    echo
    echo "================ B4 PRELIMINARY W17-W20 SHARD-SCALING PLAN (--dry-run; nothing executed) ================"
    echo "session dir : $RESULTS"
    echo "cells dir   : $CELLS_DIR"
    echo "logs dir    : $LOGS_DIR"
    echo "aggregate   : $AGG_DIR/shard.json, $AGG_DIR/shard.md"
    echo "cells       : N in {$N_LIST} x {juring-group, juring-manual, fio}"
    echo "protocol    : R=$REPS warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s settle=${SETTLE}s seed=$SEED"
    echo "read file   : $READ_FILE ($(dataset_size) B, read-only; only read cells)"
    echo "cpu range   : per N, taskset -c $(cpu_list 1) .. $(cpu_list "$(echo "$N_LIST" | tr ' ' '\n' | sort -n | tail -1)")"
    echo "JVM flags   : $JVM_OPTS (identical for both JUring engines, fresh JVM per rep)"
    echo "fio json    : group_reporting=$FIO_GROUP_REPORTING (frozen template; 1 => fio per-job spread n/a)"
    if (( RESUME == 1 )); then
        local skip=0 remain=0 st
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
        echo "resume state: $skip complete invocation(s) kept, $remain remaining to run"
    fi
    echo
    for n in $N_LIST; do
        echo "--- cell N=$n: cpus=$(cpu_list "$n") qd/shard=$QD"
        for rep in $(seq 1 "$REPS"); do
            order=$(engine_order "$n" "$rep")
            echo "    rep $rep/$REPS (engine order: $order)"
            for eng in $order; do
                run_argv "$n" "$eng" "$rep"
                rows=$((rows + 1))
                echo "      [$rows] taskset -c $(cpu_list "$n") $(argv_str "${ARGV[@]}")"
            done
            if (( rep < REPS )); then
                echo "      rest ${REST}s"
            fi
        done
        echo "    settle ${SETTLE}s before the next N"
    done
    echo
    echo "total process invocations: $rows"
    echo "estimated minimum wall: $(awk -v n="$rows" -v w="$WARMUP" -v s="$DURATION" \
        -v r="$REST" -v c="$(wc -w <<<"$N_LIST")" -v reps="$REPS" -v settle="$SETTLE" \
        'BEGIN{printf "%.0f min (bench only; no process/VM start overhead)", (n*(w+s) + c*(reps-1)*r + (c-1)*settle)/60}')"
    echo "after a real run the driver invokes: python3 $ANALYZER --session $SESSION"
    echo "======================================================================================================="
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
echo "[shard-matrix] B4 PRELIMINARY W17-W20 shard scaling (docs/b4-design.md §13; M3 NOT signed off)"
echo "[shard-matrix] session=$SESSION N='$N_LIST' R=$REPS warmup=${WARMUP}s measured=${DURATION}s rest=${REST}s"

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
    echo "[shard-matrix] resume mode: continuing session $SESSION (keeps complete invocations)"
elif [[ -d $CELLS_DIR ]] && compgen -G "$CELLS_DIR/*.json" >/dev/null; then
    if (( FORCE != 1 )); then
        echo "error: session '$SESSION' already contains cell JSONs under $CELLS_DIR" >&2
        echo "       start a new session with --session NAME, or pass --force to append." >&2
        echo "       (an interrupted session should be continued with --resume)" >&2
        exit 1
    fi
    echo "[shard-matrix] warning: --force given; appending to existing session $SESSION" >&2
fi

preflight 1 || {
    echo "error: preflight failed; refusing to run the campaign (see FAIL lines above)" >&2
    exit 1
}

mkdir -p "$CELLS_DIR" "$LOGS_DIR" "$AGG_DIR"
write_manifest
init_meta

echo "[shard-matrix] compiling the JUring harness javac-only (JURING_SKIP_MVN=1; no Maven) ..."
if ! JURING_SKIP_MVN=1 bash "$JURING_RUN" --help >/dev/null 2>"$LOGS_DIR/compile.log"; then
    echo "error: JUring harness javac compile failed; see $LOGS_DIR/compile.log" >&2
    exit 1
fi
echo "[shard-matrix] harness ready; manifest: $MANIFEST"
echo "[shard-matrix] started: $(now_utc)"

FAILS=0
RUNS_DONE=0
SKIPPED=0
N_TOTAL=$(wc -w <<<"$N_LIST")
if (( RESUME == 1 )); then
    echo "[shard-matrix] resume: scanning existing meta/artifacts under $RESULTS ..."
    mkdir -p "$RESULTS/incomplete-archive"
    : >"$RESULTS/resume.log"
    for n in $N_LIST; do
        for rep in $(seq 1 "$REPS"); do
            for eng in $(engine_order "$n" "$rep"); do
                st=$(meta_status "N$n" "$eng" "$rep")
                if [[ $st == ok || $st == gate ]] && cell_json_complete "$eng" "$n" "$rep"; then
                    SKIPPED=$((SKIPPED + 1))
                    echo "[shard-matrix] resume: keep N$n/$eng/r$rep (status=$st)"
                else
                    archive_incomplete "$n" "$eng" "$rep" "N${n}__${eng}__r${rep}"
                fi
            done
        done
    done
    echo "[shard-matrix] resume: $SKIPPED complete invocation(s) kept; the rest will run now"
fi

N_IDX=0
for n in $N_LIST; do
    N_IDX=$((N_IDX + 1))
    RAN_IN_N=0
    for rep in $(seq 1 "$REPS"); do
        RAN_IN_BLOCK=0
        order=$(engine_order "$n" "$rep")
        for eng in $order; do
            if (( RESUME == 1 )); then
                st=$(meta_status "N$n" "$eng" "$rep")
                if [[ $st == ok || $st == gate ]] && cell_json_complete "$eng" "$n" "$rep"; then
                    continue
                fi
            fi
            run_row "$n" "$eng" "$rep" || FAILS=$((FAILS + 1))
            RUNS_DONE=$((RUNS_DONE + 1))
            RAN_IN_BLOCK=1
            RAN_IN_N=1
        done
        if (( RAN_IN_BLOCK == 1 && rep < REPS )); then
            echo "[shard-matrix] rest ${REST}s before N=$n rep $((rep + 1)) (loadavg=$(loadavg))"
            sleep "$REST"
        fi
    done
    if (( RAN_IN_N == 1 && N_IDX < N_TOTAL )); then
        echo "[shard-matrix] settle ${SETTLE}s before the next N (loadavg=$(loadavg))"
        sleep "$SETTLE"
    fi
done

EXPECTED_ROWS=$(( $(wc -w <<<"$N_LIST") * REPS * 3 ))
ACTUAL_ROWS=$(( $(wc -l <"$META") - 1 ))
echo "[shard-matrix] campaign done: $(now_utc) invocation_runs=$RUNS_DONE skipped_resume=$SKIPPED failed/flagged=$FAILS"
echo "[shard-matrix] cell-meta rows: $ACTUAL_ROWS / $EXPECTED_ROWS expected (all reps kept, none trimmed)"
if (( ACTUAL_ROWS != EXPECTED_ROWS )); then
    echo "warn: cell-meta row count mismatch -- expected one row per invocation" >&2
    FAILS=$((FAILS + 1))
fi
echo "[shard-matrix] cell-meta: $META"

if [[ -f $ANALYZER ]]; then
    echo "[shard-matrix] analyzing: $ANALYZER --session $SESSION"
    if python3 "$ANALYZER" --session "$SESSION" >"$AGG_DIR/analyze.log" 2>&1; then
        echo "[shard-matrix] aggregate: $AGG_DIR/shard.json, $AGG_DIR/shard.md"
    else
        echo "warn: analyzer failed; see $AGG_DIR/analyze.log" >&2
        FAILS=$((FAILS + 1))
    fi
fi

if (( FAILS > 0 )); then
    echo "error: $FAILS invocation(s) failed or were gate-flagged; inspect cell-meta.tsv and logs/" >&2
    exit 1
fi
echo "[shard-matrix] ok"
