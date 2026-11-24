#!/usr/bin/env bash
#
# run-group-commit-bench.sh -- group-commit micro-benchmark driver
# (protocol frozen before the campaign).
#
# JUring-only protocol: seastar has no group-commit primitive (the durability
# seastar-fdatasync arm is the nearest equivalent: one fstream flush per batch,
# which also drains write-behind -- disclosed).
#
# Cells: groupcommit-4k-batch1, groupcommit-4k-batch8, groupcommit-4k-batch32, groupcommit-4k-batch128
#   bs = 4096 B, --batch B (GroupCommit maxBatchSize), durability fdatasync,
#   --seconds 15 measured + --warmup-sec 5, one fresh process per invocation,
#   taskset -c 2, R=3.
# Rests (rest-time decision): a 5 s pause between reps inside a cell and a 120 s idle
# rest between cells (3 rests; total ≈8–12 min).  --rest-between cells|reps|none
# selects the 120 s placement (default cells; the official run uses the default).
#
# Every measured run is followed by the bench's own fresh-process restart check
# (JUringDurabilityBench spawns --verify-only and records restart_verify in the JSON):
# fsyncs_per_group must be exactly 1.0, groups_total * batch_size == committed_writes,
# and the verified prefix must match the deterministic pattern.
#
# Session layout (gitignored): bench/results/<session>/ with
#   cells/<cell>__juring__groupcommit__r<rep>.json   raw JSON (schema 1.0 +
#                                                    schema_extensions ["b6-durability-1"])
#   logs/<same>.log
#   cell-meta.tsv                                    one row per invocation (20 columns)
#   manifest.txt
#   aggregate/group-commit.{json,md}                 written by analyze_group_commit.py
#
# Usage:
#   bench/stream/run-group-commit-bench.sh [--dry-run] [--resume] [--session NAME]
#       [--reps N] [--warmup S] [--seconds S] [--rest S] [--rest-between cells|reps|none]
#       [--rep-pause S] [--batches "1 8 32 128"] [--file PATH] [--file-size SIZE]
#       [--skip-compile] [--no-analyze] [--force]
#
# Environment: CPU (default 2), GC_FILE, GC_FILE_SIZE, JURING_CLASSES (default
#   legacy env aliases W25_FILE/W25_FILE_SIZE also accepted),
# target/classes), JURING_OUT (default bench/juring/out), ALLOW_RESIDUAL_LOAD=1.
# The driver compiles bench/juring/JUringDurabilityBench.java with javac against an
# EXISTING target/classes (never runs Maven; the Lead owns repo-root builds).
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../.." && pwd)
# shellcheck source=bench/cells.sh
source "$REPO_ROOT/bench/cells.sh"
cd "$REPO_ROOT"

CPU=${CPU:-2}
SESSION=b6-group-commit-$(date +%F)
GC_FILE=${GC_FILE:-${W25_FILE:-$REPO_ROOT/nvme-data/write_files/groupcommit_4k_batch.bin}}
GC_FILE_SIZE=${GC_FILE_SIZE:-${W25_FILE_SIZE:-1g}}
JURING_CLASSES=${JURING_CLASSES:-$REPO_ROOT/target/classes}
JURING_OUT=${JURING_OUT:-$REPO_ROOT/bench/juring/out}
ANALYZER=$HERE/analyze_group_commit.py
SEED=${SEED:-20261113}

BS=4096
DURABILITY=fdatasync
BATCHES=${BATCHES:-"1 8 32 128"}

REPS=3
WARMUP=5
DURATION=15
REST=120
REST_BETWEEN=cells
REP_PAUSE=5
DRY_RUN=0
RESUME=0
NO_ANALYZE=0
SKIP_COMPILE=0
FORCE=0

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

while (($#)); do
    case $1 in
        --dry-run) DRY_RUN=1 ;;
        --resume) RESUME=1 ;;
        --skip-compile) SKIP_COMPILE=1 ;;
        --session)
            SESSION=${2:?--session needs a value}
            shift
            ;;
        --reps)
            REPS=${2:?--reps needs a value}
            shift
            ;;
        --warmup)
            WARMUP=${2:?--warmup needs a value}
            shift
            ;;
        --seconds)
            DURATION=${2:?--seconds needs a value}
            shift
            ;;
        --rest)
            REST=${2:?--rest needs a value}
            shift
            ;;
        --rest-between)
            REST_BETWEEN=${2:?--rest-between needs a value}
            shift
            ;;
        --rep-pause)
            REP_PAUSE=${2:?--rep-pause needs a value}
            shift
            ;;
        --batches)
            BATCHES=${2:?--batches needs a value}
            BATCHES=${BATCHES//,/ }
            shift
            ;;
        --file)
            GC_FILE=${2:?--file needs a value}
            shift
            ;;
        --file-size)
            GC_FILE_SIZE=${2:?--file-size needs a value}
            shift
            ;;
        --no-analyze) NO_ANALYZE=1 ;;
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
for v in "$WARMUP" "$DURATION" "$REST" "$REP_PAUSE"; do
    if ! [[ $v =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        echo "error: --warmup/--seconds/--rest/--rep-pause must be numbers, got '$v'" >&2
        exit 2
    fi
done
case $REST_BETWEEN in
    cells | reps | none) ;;
    *)
        echo "error: --rest-between must be cells|reps|none, got '$REST_BETWEEN'" >&2
        exit 2
        ;;
esac
if ! [[ $SESSION =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "error: --session may only contain [A-Za-z0-9._-], got '$SESSION'" >&2
    exit 2
fi
for b in $BATCHES; do
    if ! [[ $b =~ ^[1-9][0-9]*$ ]] || (( b > 4096 )); then
        echo "error: --batches values must be integers in [1,4096], got '$b'" >&2
        exit 2
    fi
done

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
size_bytes() { local v=${1^^}; numfmt --from=iec "$v" 2>/dev/null || echo "$1"; }
size_human() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1"; }

cell_name() { echo "groupcommit-4k-batch$1"; }
row_id() { echo "$(cell_name "$1")__juring__groupcommit__r$2"; }
row_json() { echo "$CELLS_DIR/$(row_id "$1" "$2").json"; }
row_log() { echo "$LOGS_DIR/$(row_id "$1" "$2").log"; }

run_argv() {
    local batch=$1 rep=$2
    local id json
    id=$(row_id "$batch" "$rep")
    json=$(row_json "$batch" "$rep")
    ARGV=(env
        JURING_GIT_COMMIT="${JURING_GIT_COMMIT:-}"
        JURING_GIT_DIRTY="${JURING_GIT_DIRTY:-true}"
        JURING_GIT_VERSION="${JURING_GIT_VERSION:-unknown}"
        "$JAVA_BIN" --enable-native-access=ALL-UNNAMED
        -cp "$JURING_CLASSES:$JURING_OUT" bench.juring.JUringDurabilityBench
        --file "$GC_FILE" --file-size "$GC_FILE_SIZE" --bs "$BS" --batch "$batch"
        --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1 --seed "$SEED"
        --durability "$DURABILITY" --label "$id" --json "$json")
}

argv_str() { printf '%q ' "$@"; }

# json_validate <path> <batch> -> tab: status ops mibs p99 bytes errors syncs groups fsync_per_group verify_ok
json_validate() {
    python3 - "$1" "$2" <<'PY' 2>/dev/null || printf 'missing\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1\n'
import json, sys
path, batch = sys.argv[1], int(sys.argv[2])
try:
    with open(path, "r", encoding="utf-8") as fh:
        d = json.load(fh)
except Exception:
    print("missing\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1")
    raise SystemExit(0)
problems = []
if d.get("status") != "ok":
    problems.append("status=%s error=%s" % (d.get("status"), d.get("error")))
if "b6-durability-1" not in (d.get("schema_extensions") or []):
    problems.append("missing schema_extensions b6-durability-1")
if d.get("schema_version") != "1.0":
    problems.append("schema_version=%r" % (d.get("schema_version"),))
runs = d.get("runs") or []
run = runs[0] if runs else {}
if run.get("batch_size") != batch:
    problems.append("batch_size=%r expected %d" % (run.get("batch_size"), batch))
groups = int(run.get("groups_total") or 0)
writes = int(run.get("committed_writes") or 0)
syncs = int(run.get("syncs_completed") or 0)
fpg = run.get("fsyncs_per_group")
verify = run.get("restart_verify") or {}
if groups <= 0:
    problems.append("no committed groups")
if run.get("durability_mode") != "fdatasync":
    problems.append("durability_mode=%r expected fdatasync" % (run.get("durability_mode"),))
if fpg is None or abs(float(fpg) - 1.0) > 1e-9:
    problems.append("fsyncs_per_group=%r != 1.0" % (fpg,))
if syncs != groups:
    problems.append("syncs_completed=%d != groups_total=%d" % (syncs, groups))
if writes != groups * batch:
    problems.append("committed_writes=%d != groups*batch=%d" % (writes, groups * batch))
if run.get("syncs_failed") not in (None, 0):
    problems.append("syncs_failed=%r" % (run.get("syncs_failed"),))
if not verify.get("ok"):
    problems.append("restart_verify.ok=%r" % (verify.get("ok"),))
agg = d.get("aggregate") or {}
mibs = (agg.get("mibs") or {}).get("mean", run.get("mibs"))
p99 = (agg.get("group_latency_us") or {}).get("p99", run.get("p99_group_latency_us"))
print("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
    "ok" if not problems else "fail",
    agg.get("committed_writes", writes), mibs if mibs is not None else -1,
    p99 if p99 is not None else -1,
    (agg.get("committed_bytes")), syncs, groups,
    fpg, verify.get("ok"), verify.get("verified_bytes"),
    agg.get("errors_total", run.get("errors_total", 0))))
if problems:
    sys.stderr.write("json_validate: %s: %s\n" % (path, "; ".join(problems)))
PY
}

json_ok() {
    local path=$1
    [[ -s $path ]] || return 1
    python3 - "$path" <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(open(sys.argv[1], "r", encoding="utf-8"))
except Exception:
    print("fail"); raise SystemExit(0)
run = (d.get("runs") or [{}])[0]
ok = (d.get("status") == "ok"
      and "b6-durability-1" in (d.get("schema_extensions") or [])
      and abs(float(run.get("fsyncs_per_group") or 0.0) - 1.0) <= 1e-9
      and (run.get("restart_verify") or {}).get("ok") is True)
print("ok" if ok else "fail")
PY
}

# ---------------------------------------------------------------------------
# preflight
# ---------------------------------------------------------------------------

preflight() {
    local strict=$1 fail=0 govs la1 foreign io_disabled dir avail
    check() {
        if (( $1 )); then
            printf 'preflight: PASS  %s\n' "$2"
        else
            printf 'preflight: FAIL  %s\n' "${3:-$2}"
            fail=1
        fi
    }
    dir=$(dirname "$GC_FILE")
    check $([[ -d $dir && -w $dir ]] && echo 1 || echo 0) "group-commit file directory writable: $dir" \
        "group-commit file directory missing/not writable: $dir"
    avail=$(df -B1 --output=avail "$dir" 2>/dev/null | tail -n 1 | tr -d ' ' || echo 0)
    check $(( ${avail:-0} >= 2 * 1024 * 1024 * 1024 )) \
        "free space for the group-commit file: $(size_human "${avail:-0}")" \
        "less than 2 GiB free on the group-commit file filesystem (${avail:-0} B)"
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
        printf 'preflight: NOTE  1-min loadavg %s >= 1.0 (ALLOW_RESIDUAL_LOAD=1, residual)\n' "$la1"
    else
        printf 'preflight: FAIL  1-min loadavg %s >= 1.0\n' "$la1"
        fail=1
    fi
    foreign=$(ps -eo pcpu=,pid=,comm= --sort=-pcpu |
        awk -v self="$$" '$2 != self && $1 > 20 {printf "%s(pid %s, %.0f%%) ", $3, $2, $1}')
    check $([[ -z $foreign ]] && echo 1 || echo 0) "no foreign process >20% CPU" \
        "foreign process >20% CPU: $foreign"
    check $([[ -f $JURING_CLASSES/com/davidvlijmincx/lio/api/GroupCommit.class ]] && echo 1 || echo 0) \
        "group-commit API present in --classes: $JURING_CLASSES" \
        "group-commit API missing from $JURING_CLASSES (GroupCommit.class not found; wait for the Lead build)"
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
    local govs seastar_version
    govs=$(for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort | uniq -c | tr '\n' ' ')
    seastar_version=$(git -C "$REPO_ROOT/SeaStarRef" describe --tags --always --dirty 2>/dev/null ||
        echo 'seastar-25.05.0-1392-g04b1c04d')
    mkdir -p "$CELLS_DIR" "$LOGS_DIR" "$AGG_DIR"
    {
        echo "# Group-commit micro-benchmark session manifest (JUring-only)"
        echo "# generated: $(date -Iseconds)  host: $(hostname)"
        echo "# workspace: $REPO_ROOT"
        echo "# protocol: frozen before the campaign (rest times recorded below)"
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
        echo "## Target file / mount"
        echo "group-commit file: $GC_FILE (preallocated/required size $GC_FILE_SIZE; sparse setLength)"
        findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$(dirname "$GC_FILE")" 2>/dev/null || true
        df -h "$(dirname "$GC_FILE")" 2>/dev/null || true
        lsblk -o NAME,MODEL,SERIAL,SIZE,MOUNTPOINTS | grep -E 'nvme|NAME' || true
        echo
        echo "## Kernel I/O controls"
        echo "kernel.io_uring_disabled = $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?')"
        echo
        echo "## Tool versions"
        echo "java: $(java -version 2>&1 | head -1)"
        echo "seastar (reference only; groupcommit is JUring-only): $seastar_version"
        echo
        echo "## Repo state"
        echo "git commit: $(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "git describe: $(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
        echo "git status (porcelain):"
        git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -20 || true
        echo
        echo "## Harness (sha256)"
        sha256sum "$REPO_ROOT/bench/juring/JUringDurabilityBench.java" \
            "$HERE/run-group-commit-bench.sh" "$ANALYZER" 2>/dev/null || true
        echo
        echo "## Protocol (frozen; rest times below)"
        echo "cells: $(for b in $BATCHES; do printf '%s ' "$(cell_name "$b")"; done)"
        echo "cell: groupcommit-4k-batchB = GroupCommit batch B (maxBatchSize), bs=$BS, durability=$DURABILITY"
        echo "protocol: R=$REPS; warmup=${WARMUP}s measured=${DURATION}s; one fresh process per arm;"
        echo "          fresh JVM --verify-only restart check after every measured run; taskset -c $CPU"
        echo "rests: ${REP_PAUSE}s pause between reps inside a cell; ${REST}s idle rest between cells"
        echo "       (rest-between=$REST_BETWEEN; rest-time decision: rests are between cells, not before"
        echo "       every rep; total session ≈8–12 min incl. the fresh-process read-back)"
        echo "durability: fdatasync; acceptance identities: fsyncs_per_group == 1.0," 
        echo "            groups_total * batch_size == committed_writes, restart_verify.ok == true"
        echo
        echo "## Disclosures"
        echo "- groupcommit is JUring-only: seastar has no group-commit primitive. The nearest seastar"
        echo "  equivalent is the durability seastar-fdatasync arm (one fstream flush per batch, which also"
        echo "  drains write-behind) and is an upper bound on the cost, not a matched comparison."
        echo "- 'one fsync per batch' is proven by GroupCommitStats counters + the JSON identity,"
        echo "  not by a syscall trace; 'visibility after restart' is a fresh-process re-read of the"
        echo "  deterministic pattern (process-restart visibility, NOT a power-loss test)."
        echo "- sync_latency_us is the commit() call duration (upper bound: it includes waiting for"
        echo "  the batch writes; the public API exposes no sync-only hook)."
        echo "- the file is preallocated with setLength (sparse); offsets wrap modulo the file"
        echo "  capacity (wrap_arounds in the JSON) and the pattern is position-indexed, so the"
        echo "  verified prefix stays deterministic."
        echo
        echo "started: $(now_utc)"
    } >"$MANIFEST"
}

init_meta() {
    if [[ ! -s $META ]]; then
        printf 'cell\tengine\tvariant\tworkload\tbs\tqd\trep\tstart_utc\tend_utc\twall_s\tloadavg_start\tloadavg_end\tstatus\texit\tops\tmibs\tp99_us\tbytes\terrors\targv\n' >"$META"
    fi
}

# ---------------------------------------------------------------------------
# one invocation
# ---------------------------------------------------------------------------

run_row() {
    local batch=$1 rep=$2
    local id json log cmd_str t0 t1 start end wall la0 la1 rc status
    local summary jstatus ops mibs p99 bytes errors syncs groups fpg vok vbytes
    id=$(row_id "$batch" "$rep")
    json=$(row_json "$batch" "$rep")
    log=$(row_log "$batch" "$rep")
    run_argv "$batch" "$rep"
    cmd_str="taskset -c $CPU $(argv_str "${ARGV[@]}")"

    t0=$(date +%s.%N)
    start=$(now_utc)
    la0=$(loadavg)
    rc=0
    taskset -c "$CPU" "${ARGV[@]}" >"$log" 2>&1 || rc=$?
    t1=$(date +%s.%N)
    end=$(now_utc)
    la1=$(loadavg)
    wall=$(wall_sec "$t0" "$t1")

    status=ok
    if (( rc != 0 )); then status=fail; fi
    if [[ ! -s $json ]]; then
        status=fail
    else
        summary=$(json_validate "$json" "$batch")
        IFS=$'\t' read -r jstatus ops mibs p99 bytes syncs groups fpg vok vbytes errors <<<"$summary"
        if [[ $jstatus != ok ]]; then status=fail; fi
    fi
    (( rc == 0 )) || echo "warn: $id exited $rc (see $log)" >&2
    if [[ $status != ok ]]; then
        tail -n 4 "$log" 2>/dev/null | sed 's/^/    log: /' >&2 || true
    fi

    printf '%-42s batch=%-4s rep=%d status=%-4s wall=%-7ss mibs=%-9s p99_group_us=%-10s groups=%-6s fsync/group=%s verify=%s\n' \
        "$id" "$batch" "$rep" "$status" "$wall" "$mibs" "$p99" "$groups" "$fpg" "$vok" >&2
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$(cell_name "$batch")" juring groupcommit groupcommit "$BS" "$batch" "$rep" \
        "$start" "$end" "$wall" "$la0" "$la1" "$status" "$rc" \
        "$ops" "$mibs" "$p99" "$bytes" "$errors" "$cmd_str" >>"$META"
    [[ $status == ok ]]
}

# ---------------------------------------------------------------------------
# plan printing (--dry-run)
# ---------------------------------------------------------------------------

print_plan() {
    local rows=0 batch rep est
    echo
    echo "================ GROUP-COMMIT PLAN (--dry-run; nothing executed) ================="
    echo "session dir : $RESULTS"
    echo "cells       : $(for b in $BATCHES; do printf '%s ' "$(cell_name "$b")"; done)"
    echo "file        : $GC_FILE (preallocate/require $GC_FILE_SIZE)"
    echo "protocol    : R=$REPS warmup=${WARMUP}s measured=${DURATION}s cpu=$CPU seed=$SEED"
    echo "rests       : ${REP_PAUSE}s between reps; ${REST}s between cells (rest-between=$REST_BETWEEN)"
    echo "durability  : $DURABILITY; bs=$BS; fresh-process --verify-only after every run"
    echo "classpath   : $JURING_CLASSES:$JURING_OUT (javac into $JURING_OUT unless --skip-compile)"
    echo
    for batch in $BATCHES; do
        echo "--- $(cell_name "$batch"): batch=$batch"
        for rep in $(seq 1 "$REPS"); do
            run_argv "$batch" "$rep"
            rows=$((rows + 1))
            echo "    [$rows] taskset -c $CPU $(argv_str "${ARGV[@]}")"
            if (( rep < REPS )); then echo "        pause ${REP_PAUSE}s"; fi
        done
        echo "    rest ${REST}s before the next cell (rest-between=$REST_BETWEEN)"
    done
    echo
    echo "total process invocations: $rows"
    est=$(awk -v c="$(wc -w <<<"$BATCHES")" -v r="$REPS" -v w="$WARMUP" -v s="$DURATION" \
        -v p="$REP_PAUSE" -v rest="$REST" -v rb="$REST_BETWEEN" \
        'BEGIN{ bench = c*(r*(w+s) + (r-1)*p); if (rb=="cells") bench += (c-1)*rest; else if (rb=="reps") bench += c*(r-1)*rest; printf "%.0f", bench }')
    echo "estimated wall (bench only; the fresh-process verify adds ~0.2–2 s/run): ${est}s (~$((est / 60)) min)"
    echo "after a real run the driver invokes: python3 $ANALYZER --session $SESSION"
    echo "=========================================================================================="
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
JAVA_BIN=${JAVA_BIN:-java}
export JURING_GIT_COMMIT=${JURING_GIT_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}
if [[ -z "${JURING_GIT_DIRTY:-}" ]]; then
    if [[ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -n 1)" ]]; then
        export JURING_GIT_DIRTY=true
    else
        export JURING_GIT_DIRTY=false
    fi
fi
export JURING_GIT_VERSION=${JURING_GIT_VERSION:-$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)}

echo "[group-commit] group-commit micro-benchmark (protocol frozen; rest times in manifest)"
echo "[group-commit] session=$SESSION batches='$BATCHES' R=$REPS warmup=${WARMUP}s measured=${DURATION}s cpu=$CPU"

if [[ $DRY_RUN == 1 ]]; then
    preflight -1 || true
    print_plan
    exit 0
fi

if [[ $RESUME != 1 ]] && [[ -d $CELLS_DIR ]] && compgen -G "$CELLS_DIR/*.json" >/dev/null && (( FORCE != 1 )); then
    echo "error: session '$SESSION' already contains cell JSONs under $CELLS_DIR" >&2
    echo "       use --resume to continue it, --session NAME for a new one, or --force to append." >&2
    exit 1
fi

preflight 1 || {
    echo "error: preflight failed; refusing to run the campaign (see FAIL lines above)" >&2
    exit 1
}

mkdir -p "$CELLS_DIR" "$LOGS_DIR" "$AGG_DIR"
write_manifest
init_meta

if (( SKIP_COMPILE == 0 )); then
    echo "[group-commit] compiling bench/juring/JUringDurabilityBench.java (javac; no Maven) ..."
    mkdir -p "$JURING_OUT"
    if ! javac -cp "$JURING_CLASSES" -d "$JURING_OUT" --release 25 \
        bench/juring/JUringDurabilityBench.java; then
        echo "error: javac failed; is the group-commit API compiled into $JURING_CLASSES?" >&2
        exit 1
    fi
fi
echo "[group-commit] bench ready; manifest: $MANIFEST"
echo "[group-commit] started: $(now_utc)"

FAILS=0
CI=0
CELLS_LEFT=$(wc -w <<<"$BATCHES")
for batch in $BATCHES; do
    CI=$((CI + 1))
    if [[ $REST_BETWEEN == cells ]] && (( CI > 1 )); then
        echo "[group-commit] rest ${REST}s between cells (loadavg=$(loadavg))"
        sleep "$REST"
    fi
    for rep in $(seq 1 "$REPS"); do
        if (( RESUME == 1 )) && [[ $(json_ok "$(row_json "$batch" "$rep")") == ok ]]; then
            echo "[group-commit] $(cell_name "$batch") rep $rep/$REPS already complete (--resume); skipping"
            continue
        fi
        if (( rep > 1 )); then
            if [[ $REST_BETWEEN == reps ]]; then
                echo "[group-commit] rest ${REST}s before rep $rep (rest-between=reps)"
                sleep "$REST"
            else
                sleep "$REP_PAUSE"
            fi
        fi
        run_row "$batch" "$rep" || FAILS=$((FAILS + 1))
    done
done

echo "[group-commit] campaign done: $(now_utc) failures=$FAILS"
echo "[group-commit] cell-meta: $META"

if (( FAILS > 0 )); then
    echo "error: $FAILS invocation(s) failed; inspect cell-meta.tsv and logs/" >&2
fi

if (( NO_ANALYZE == 0 )); then
    if [[ -f $ANALYZER ]]; then
        echo "[group-commit] analyzing: $ANALYZER --session $SESSION"
        if python3 "$ANALYZER" --session "$SESSION" >"$AGG_DIR/analyze.log" 2>&1; then
            echo "[group-commit] aggregate: $AGG_DIR/group-commit.json, $AGG_DIR/group-commit.md"
        else
            echo "error: analyzer failed (contract violation?); see $AGG_DIR/analyze.log" >&2
            exit 1
        fi
    else
        echo "error: analyzer missing: $ANALYZER" >&2
        exit 1
    fi
fi

if (( FAILS > 0 )); then
    exit 1
fi
echo "[group-commit] ok"
