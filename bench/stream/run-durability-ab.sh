#!/usr/bin/env bash
#
# run-durability-ab.sh -- B6 W24 durability A/B driver (docs/b6-design.md §6.3, frozen).
#
# SUPPORTING EVIDENCE ONLY -- not a milestone bar, no new cross-engine write win/loss
# claim.  One write cell, matched durability arms on both engines, writeback-vs-writeback
# control bracketing the block.
#
# Cell W24-128k: stream_write, bs 128 KiB, qd 8, --size 2g on the existing 10 GiB write
# file (the file is never resized; the 2 GiB dataset is a disclosed deviation from the
# frozen 10 GiB).
#
# Tier 1 (decisive, R=3):
#   arms: juring-writeback, juring-fdatasync, seastar-writeback, seastar-fdatasync,
#         all with --sync-every 4M (one durability point per 4 MiB:
#         writeback = drain only; fdatasync = drain + one fdatasync).
#   protocol: 300 s idle rest before each rep window; the 4 arms run back-to-back inside
#   that post-rest window in an order alternating every rep (rep 1 Jw,Jd,Sw,Sd;
#   rep 2 Sd,Sw,Jd,Jw; rep 3 Jw,Jd,Sw,Sd); per arm 5 s warmup + 15 s measured; one fresh
#   process per invocation; taskset -c 2.  The juring-writeback arm runs first overall
#   (rep 1) and last overall (an extra bracket run after rep 3) as the write-state control.
# Tier 2 (exploratory, R=2, 120 s rests):
#   juring-dsync / seastar-dsync (--durability dsync, --sync-every 4M; O_DSYNC writes),
#   optional juring-fsync (--durability fsync, --sync-every 4M) reported JUring-only
#   because seastar has no fsync(2) path.  --skip-fsync disables it.
#
# Engine rows use the existing W21/W22 conventions: JUring O_DIRECT + registered file via
# bench/juring/run-juring-stream.sh; seastar fstream via bench/seastar/seastar_stream_bench
# with --smp 1 --cpuset 2 --memory 1G --lock-memory 0 --reactor-backend io_uring and the
# pinned --aio-fsync 1 (so flush() is IORING_OP_FSYNC|IORING_FSYNC_DATASYNC, not a blocking
# thread-pool fdatasync; docs/b6-design.md §6.2).
#
# Session layout (gitignored): bench/results/<session>/ with
#   cells/<cell>__<engine>__<variant>__r<rep>.json   raw per-process JSON (schema 1.0 +
#                                                    schema_extensions ["b6-durability-1"])
#   logs/<same>.log                                  stdout+stderr of the process
#   cell-meta.tsv                                    one row per invocation (M2's 20 columns;
#                                                    sync data lives in the JSON, §6.5)
#   manifest.txt                                     host/device/tool/protocol manifest
#   aggregate/durability.{json,md}                   written by analyze_durability.py
#
# Variants: writeback | fdatasync | dsync | fsync (JUring only) | writeback-bracket
# (the last-overall JUring writeback state control).
#
# Usage:
#   bench/stream/run-durability-ab.sh [--dry-run] [--resume] [--session NAME]
#       [--reps N] [--warmup S] [--seconds S] [--rest S]
#       [--tier2-reps N] [--tier2-rest S] [--size SIZE]
#       [--skip-tier2] [--skip-fsync] [--no-analyze] [--force]
#
# --dry-run is side-effect free (preflight PASS/FAIL report + the full command plan).
# --resume skips rep windows whose cell JSONs already exist with status ok (partial
# windows re-run only their missing rows; the rest is re-applied for windows that run).
#
# Environment: CPU (default 2), WRITE_FILE, SEASTAR_BIN, JURING_RUN, SYSROOT_LIB,
# ALLOW_RESIDUAL_LOAD=1 (relax the 1-min loadavg gate for chained sessions).
set -euo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$HERE/../.." && pwd)
cd "$REPO_ROOT"

CPU=${CPU:-2}
SESSION=b6-durability-$(date +%F)
WRITE_FILE=${WRITE_FILE:-$REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin}
SEASTAR_BIN=${SEASTAR_BIN:-$REPO_ROOT/bench/seastar/seastar_stream_bench}
JURING_RUN=${JURING_RUN:-$REPO_ROOT/bench/juring/run-juring-stream.sh}
ANALYZER=$HERE/analyze_durability.py
SYSROOT_LIB=$REPO_ROOT/.sysroot/root/usr/lib64
SEED=${SEED:-20261113}

CELL=W24-128k
BS=128k
QD=8
SYNC_EVERY=4M
SIZE=2g

REPS=3
WARMUP=5
DURATION=15
REST=300
TIER2_REPS=2
TIER2_REST=120
SKIP_TIER2=0
SKIP_FSYNC=0
DRY_RUN=0
RESUME=0
NO_ANALYZE=0
FORCE=0

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

while (($#)); do
    case $1 in
        --dry-run) DRY_RUN=1 ;;
        --resume) RESUME=1 ;;
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
        --tier2-reps)
            TIER2_REPS=${2:?--tier2-reps needs a value}
            shift
            ;;
        --tier2-rest)
            TIER2_REST=${2:?--tier2-rest needs a value}
            shift
            ;;
        --size)
            SIZE=${2:?--size needs a value}
            shift
            ;;
        --skip-tier2) SKIP_TIER2=1 ;;
        --skip-fsync) SKIP_FSYNC=1 ;;
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
if ! [[ $TIER2_REPS =~ ^[1-9][0-9]*$ ]]; then
    echo "error: --tier2-reps must be a positive integer, got '$TIER2_REPS'" >&2
    exit 2
fi
for v in "$WARMUP" "$DURATION" "$REST" "$TIER2_REST"; do
    if ! [[ $v =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        echo "error: --warmup/--seconds/--rest/--tier2-rest must be numbers, got '$v'" >&2
        exit 2
    fi
done
if ! [[ $SESSION =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "error: --session may only contain [A-Za-z0-9._-], got '$SESSION'" >&2
    exit 2
fi
if ! [[ $SIZE =~ ^[0-9]+([kKmMgG][iI]?[bB]?)?$ ]]; then
    echo "error: --size must be a byte size (e.g. 2g), got '$SIZE'" >&2
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
size_bytes() { local v=${1^^}; numfmt --from=iec "$v" 2>/dev/null || echo "$1"; }
size_human() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1"; }

row_id() { echo "${CELL}__$1__$2__r$3"; }
row_json() { echo "$CELLS_DIR/$(row_id "$1" "$2" "$3").json"; }
row_log() { echo "$LOGS_DIR/$(row_id "$1" "$2" "$3").log"; }

durability_of() {
    case $1 in
        writeback | writeback-bracket) echo writeback ;;
        fdatasync | dsync | fsync) echo "$1" ;;
        *)
            echo "error: unknown variant '$1'" >&2
            return 1
            ;;
    esac
}

# tier1_order <rep>: 4 arms back-to-back, order alternating every rep (design §6.3).
tier1_order() {
    if (( $1 % 2 == 1 )); then
        echo "juring:writeback juring:fdatasync seastar:writeback seastar:fdatasync"
    else
        echo "seastar:fdatasync seastar:writeback juring:fdatasync juring:writeback"
    fi
}

# tier2_order <rep>: dsync pair alternating + optional JUring-only fsync last.
tier2_order() {
    local base fs=""
    if (( $1 % 2 == 1 )); then
        base="juring:dsync seastar:dsync"
    else
        base="seastar:dsync juring:dsync"
    fi
    if (( SKIP_FSYNC == 0 )); then
        fs=" juring:fsync"
    fi
    echo "$base$fs"
}

# run_argv <engine> <variant> <rep>: sets the global ARGV array (without taskset).
run_argv() {
    local engine=$1 variant=$2 rep=$3
    local id mode json
    id=$(row_id "$engine" "$variant" "$rep")
    json=$(row_json "$engine" "$variant" "$rep")
    mode=$(durability_of "$variant")
    ARGV=()
    if [[ $engine == juring ]]; then
        ARGV=(env JURING_SKIP_COMPILE=1 bash "$JURING_RUN"
            --workload stream_write --file "$WRITE_FILE" --bs "$BS" --qd "$QD"
            --size "$SIZE" --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1
            --seed "$SEED" --label "$id" --json "$json"
            --durability "$mode" --sync-every "$SYNC_EVERY")
    elif [[ $engine == seastar ]]; then
        ARGV=("$SEASTAR_BIN"
            --workload stream_write --file "$WRITE_FILE" --bs "$BS" --qd "$QD"
            --size "$SIZE" --seconds "$DURATION" --warmup-sec "$WARMUP" --runs 1
            --seed "$SEED" --label "$id" --json "$json"
            --durability "$mode" --sync-every "$SYNC_EVERY"
            --smp 1 --cpuset "$CPU" --memory 1G --lock-memory 0
            --reactor-backend io_uring --aio-fsync 1)
    else
        echo "error: unknown engine '$engine'" >&2
        return 1
    fi
}

argv_str() { printf '%q ' "$@"; }

# json_validate <path> <engine> <variant> <durability_mode> -> tab-separated summary
# status ops mibs p99 bytes errors syncs_issued durability_points sync_wait_share
json_validate() {
    python3 - "$1" "$2" "$3" "$4" "$SYNC_EVERY" <<'PY' 2>/dev/null || printf 'missing\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1\n'
import json, sys
path, engine, variant, mode, sync_every = sys.argv[1:6]
sync_every_bytes = {"4M": 4194304, "1M": 1048576, "0": 0}.get(sync_every, -1)
try:
    with open(path, "r", encoding="utf-8") as fh:
        d = json.load(fh)
except Exception:
    print("missing\t-1\t-1\t-1\t-1\t-1\t-1\t-1\t-1")
    raise SystemExit(0)
problems = []
if d.get("status") != "ok":
    problems.append("status=%s error=%s" % (d.get("status"), d.get("error")))
if "b6-durability-1" not in (d.get("schema_extensions") or []):
    problems.append("missing schema_extensions b6-durability-1")
if d.get("schema_version") != "1.0":
    problems.append("schema_version=%r" % (d.get("schema_version"),))
cfg = d.get("config") or {}
runs = d.get("runs") or []
run = runs[0] if runs else {}
agg = d.get("aggregate") or {}
if cfg.get("durability") != mode:
    problems.append("config.durability=%r expected %r" % (cfg.get("durability"), mode))
if cfg.get("sync_every_bytes") != sync_every_bytes:
    problems.append("config.sync_every_bytes=%r expected %r" % (cfg.get("sync_every_bytes"), sync_every_bytes))
if run.get("durability_mode") != mode:
    problems.append("runs[0].durability_mode=%r expected %r" % (run.get("durability_mode"), mode))
if agg.get("errors_total") not in (None, 0) or run.get("errors_total") not in (None, 0):
    problems.append("errors_total=%r/%r" % (agg.get("errors_total"), run.get("errors_total")))
syncs = int(run.get("syncs_issued") or 0)
points = int(run.get("durability_points") or 0)
if mode in ("writeback", "dsync"):
    if syncs != 0:
        problems.append("%s arm issued %d explicit sync(s); expected 0" % (mode, syncs))
    if points <= 0:
        problems.append("%s arm recorded no durability points (sync_wait reservoir empty)" % mode)
else:
    if syncs <= 0:
        problems.append("%s arm issued no syncs" % mode)
    if points <= 0:
        problems.append("%s arm recorded no durability points" % mode)
mibs = (agg.get("mibs") or {}).get("mean")
if mibs is None and runs:
    mibs = run.get("mibs")
lat = (agg.get("latency_us") or {})
p99 = lat.get("p99")
if p99 is None:
    p99 = (run.get("latency_us") or {}).get("p99", -1)
status = "ok" if not problems and d.get("status") == "ok" else "fail"
print("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s" % (
    status, agg.get("ops_total", run.get("ops_total", -1)),
    mibs if mibs is not None else -1, p99 if p99 is not None else -1,
    agg.get("bytes_total", run.get("bytes_total", -1)),
    agg.get("errors_total", -1), syncs, points,
    run.get("sync_wait_share", -1)))
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
print("ok" if d.get("status") == "ok" and "b6-durability-1" in (d.get("schema_extensions") or []) else "fail")
PY
}

# ---------------------------------------------------------------------------
# preflight
# ---------------------------------------------------------------------------

preflight() {
    local strict=$1 fail=0
    local wr_size avail govs la1 foreign io_disabled

    check() {
        if (( $1 )); then
            printf 'preflight: PASS  %s\n' "$2"
        else
            printf 'preflight: FAIL  %s\n' "${3:-$2}"
            fail=1
        fi
    }

    wr_size=$(stat -c%s "$WRITE_FILE" 2>/dev/null || echo 0)
    check $([[ -f $WRITE_FILE ]] && echo 1 || echo 0) "write dataset exists: $WRITE_FILE" \
        "missing write dataset: $WRITE_FILE"
    check $([[ -f $WRITE_FILE && -w $WRITE_FILE ]] && echo 1 || echo 0) "write dataset writable" \
        "write dataset not writable: $WRITE_FILE"
    check $(( wr_size >= $(size_bytes "$SIZE") )) \
        "write dataset $(size_human "$wr_size") >= --size $SIZE" \
        "write dataset is $(size_human "$wr_size") < --size $SIZE (the runner never extends it)"
    if (( wr_size % 1048576 != 0 )); then
        printf 'preflight: NOTE  write dataset size %s is not a multiple of 1 MiB\n' "$wr_size"
    fi

    avail=$(df -B1 --output=avail "$WRITE_FILE" 2>/dev/null | tail -n 1 | tr -d ' ' || echo 0)
    check $(( ${avail:-0} >= 2 * 1024 * 1024 * 1024 )) \
        "free space on write filesystem: $(size_human "${avail:-0}")" \
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
        printf 'preflight: NOTE  1-min loadavg %s >= 1.0 (ALLOW_RESIDUAL_LOAD=1, residual)\n' "$la1"
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
    check $([[ -x $SEASTAR_BIN ]] && echo 1 || echo 0) "seastar stream binary: $SEASTAR_BIN" \
        "seastar stream binary missing: $SEASTAR_BIN (rebuild with bench/seastar/build-stream-bench.sh)"
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
    local govs wr_size seastar_version
    govs=$(for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do cat "$f" 2>/dev/null; done |
        sort | uniq -c | tr '\n' ' ')
    wr_size=$(stat -c%s "$WRITE_FILE" 2>/dev/null || echo '?')
    seastar_version=$(git -C "$REPO_ROOT/SeaStarRef" describe --tags --always --dirty 2>/dev/null ||
        echo 'seastar-25.05.0-1392-g04b1c04d')
    mkdir -p "$CELLS_DIR" "$LOGS_DIR" "$AGG_DIR"
    {
        echo "# B6 W24 durability A/B session manifest -- matched durability arms, JUring vs seastar"
        echo "# generated: $(date -Iseconds)  host: $(hostname)"
        echo "# workspace: $REPO_ROOT"
        echo "# design contract: docs/b6-design.md §6.3 (frozen); SUPPORTING EVIDENCE ONLY"
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
        echo "write dataset: $WRITE_FILE size=$wr_size (never resized; --size $SIZE per pass)"
        echo "write mount:"
        findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$WRITE_FILE" 2>/dev/null || true
        df -h "$WRITE_FILE" 2>/dev/null || true
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
        sha256sum "$JURING_RUN" "$HERE/run-durability-ab.sh" "$ANALYZER" \
            "$REPO_ROOT/bench/juring/JUringStreamBench.java" \
            "$REPO_ROOT/bench/seastar/seastar_stream_bench.cc" 2>/dev/null || true
        echo
        echo "## Protocol (docs/b6-design.md §6.3, frozen)"
        echo "cell: $CELL (stream_write, bs=$BS, qd=$QD, --size $SIZE, --sync-every $SYNC_EVERY)"
        echo "tier1: R=$REPS reps; ${REST}s idle rest before each rep window; 4 arms back-to-back"
        echo "       per window in an order alternating every rep (rep1 Jw,Jd,Sw,Sd; rep2 Sd,Sw,Jd,Jw;"
        echo "       rep3 Jw,Jd,Sw,Sd); warmup=${WARMUP}s measured=${DURATION}s; fresh process per arm"
        echo "       taskset -c $CPU; seed=$SEED; juring-writeback brackets the block (rep1 first +"
        echo "       one extra last-overall bracket control)"
        echo "tier2: R=$TIER2_REPS, ${TIER2_REST}s rests; juring-dsync/seastar-dsync (exploratory);"
        echo "       juring-fsync JUring-only (seastar has no fsync(2) path) skipped=$SKIP_FSYNC"
        echo "seastar: --smp 1 --cpuset $CPU --memory 1G --lock-memory 0 --reactor-backend io_uring"
        echo "         --aio-fsync 1 (pinned: flush() = IORING_OP_FSYNC|IORING_FSYNC_DATASYNC)"
        echo "juring : O_DIRECT + registered file; stream bufferSize=$BS writeBehind=$QD"
        echo
        echo "## Disclosed asymmetries (docs/b6-design.md §6 preamble/§9.6-§9.8)"
        echo "- M2's W21 write arms issued 2 fdatasync/pass on seastar and 0 on JUring; W24 matches"
        echo "  durability on both sides and brackets the block with writeback-vs-writeback controls."
        echo "- durability points include a write-behind drain on both engines; writeback = drain only,"
        echo "  fdatasync = drain + one sync, so the paired ratio isolates the sync cost."
        echo "- seastar's pass-end path issues 2 fdatasync/pass (flush+close), JUring's 1; negligible"
        echo "  next to the --sync-every $SYNC_EVERY points and reported via syncs/s and MiB/sync."
        echo "- seastar has no fsync(2) path; juring-fsync is JUring-only, not compared across engines."
        echo "- dataset is $SIZE per pass on the existing file (deviation from the frozen 10 GiB)."
        echo "- no cache drop, no power-loss test; state classes derived from M1 §4 + M2 §6."
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
    local engine=$1 variant=$2 rep=$3
    local id json log cmd_str t0 t1 start end wall la0 la1 rc status
    local summary jstatus='missing' ops='-1' mibs='-1' p99='-1' bytes='-1' errors='-1'
    local syncs='-1' points='-1' share='-1' mode
    mode=$(durability_of "$variant")
    id=$(row_id "$engine" "$variant" "$rep")
    json=$(row_json "$engine" "$variant" "$rep")
    log=$(row_log "$engine" "$variant" "$rep")
    run_argv "$engine" "$variant" "$rep" || return 1
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
        summary=$(json_validate "$json" "$engine" "$variant" "$mode")
        IFS=$'\t' read -r jstatus ops mibs p99 bytes errors syncs points share <<<"$summary"
        if [[ $jstatus != ok ]]; then status=fail; fi
    fi
    (( rc == 0 )) || echo "warn: $id exited $rc (see $log)" >&2
    if [[ $status != ok ]]; then
        tail -n 3 "$log" 2>/dev/null | sed 's/^/    log: /' >&2 || true
    fi

    printf '%-46s %-9s %-10s status=%-4s wall=%-7ss mibs=%-9s p99_us=%-9s syncs=%-6s share=%s\n' \
        "$id" "$engine" "$variant" "$status" "$wall" "$mibs" "$p99" "$syncs" "$share" >&2
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$CELL" "$engine" "$variant" stream_write "$BS" "$QD" "$rep" "$start" "$end" "$wall" \
        "$la0" "$la1" "$status" "$rc" "$ops" "$mibs" "$p99" "$bytes" "$errors" "$cmd_str" >>"$META"
    [[ $status == ok ]]
}

# ---------------------------------------------------------------------------
# plan printing (--dry-run)
# ---------------------------------------------------------------------------

print_plan() {
    local rows=0 rep spec engine variant
    echo
    echo "================ B6 W24 DURABILITY A/B PLAN (--dry-run; nothing executed) =============="
    echo "session dir : $RESULTS"
    echo "cell        : $CELL (stream_write bs=$BS qd=$QD size=$SIZE sync-every=$SYNC_EVERY)"
    echo "write file  : $WRITE_FILE ($(size_human "$(stat -c%s "$WRITE_FILE" 2>/dev/null || echo 0)") )"
    echo "tier1       : R=$REPS rest=${REST}s warmup=${WARMUP}s measured=${DURATION}s cpu=$CPU seed=$SEED"
    echo "tier2       : R=$TIER2_REPS rest=${TIER2_REST}s skip=$SKIP_TIER2 fsync=$( ((SKIP_FSYNC)) && echo skipped || echo enabled )"
    echo "seastar     : LD_LIBRARY_PATH=$SYSROOT_LIB; --aio-fsync 1 pinned; --reactor-backend io_uring"
    echo "bracket     : JUring-writeback first overall (rep 1) and one extra last-overall control"
    echo
    if (( SKIP_TIER2 == 0 )); then
        echo "--- tier 1: $REPS rep windows of 4 arms"
    else
        echo "--- tier 1 only (tier 2 skipped)"
    fi
    for rep in $(seq 1 "$REPS"); do
        echo "  rep $rep/$REPS (rest ${REST}s first; order: $(tier1_order "$rep"))"
        for spec in $(tier1_order "$rep"); do
            engine=${spec%%:*}
            variant=${spec##*:}
            run_argv "$engine" "$variant" "$rep"
            rows=$((rows + 1))
            if [[ $engine == seastar ]]; then
                echo "    [$rows] LD_LIBRARY_PATH=$SYSROOT_LIB taskset -c $CPU $(argv_str "${ARGV[@]}")"
            else
                echo "    [$rows] taskset -c $CPU $(argv_str "${ARGV[@]}")"
            fi
        done
    done
    run_argv juring writeback-bracket 1
    rows=$((rows + 1))
    echo "  bracket (last overall, immediately after rep $REPS):"
    echo "    [$rows] taskset -c $CPU $(argv_str "${ARGV[@]}")"
    if (( SKIP_TIER2 == 0 )); then
        echo
        echo "--- tier 2: $TIER2_REPS rep windows, ${TIER2_REST}s rests"
        for rep in $(seq 1 "$TIER2_REPS"); do
            echo "  tier2 rep $rep/$TIER2_REPS (rest ${TIER2_REST}s first; order: $(tier2_order "$rep"))"
            for spec in $(tier2_order "$rep"); do
                engine=${spec%%:*}
                variant=${spec##*:}
                run_argv "$engine" "$variant" "$rep"
                rows=$((rows + 1))
                if [[ $engine == seastar ]]; then
                    echo "    [$rows] LD_LIBRARY_PATH=$SYSROOT_LIB taskset -c $CPU $(argv_str "${ARGV[@]}")"
                else
                    echo "    [$rows] taskset -c $CPU $(argv_str "${ARGV[@]}")"
                fi
            done
        done
    fi
    echo
    echo "total process invocations: $rows"
    local est_t1 est_t2
    est_t1=$(awk -v r="$REPS" -v rest="$REST" -v w="$WARMUP" -v s="$DURATION" \
        'BEGIN{printf "%.0f", r*(rest + 4*(w+s)) + (w+s)}')
    est_t2=0
    if (( SKIP_TIER2 == 0 )); then
        local arms=2
        (( SKIP_FSYNC == 0 )) && arms=3
        est_t2=$(awk -v r="$TIER2_REPS" -v rest="$TIER2_REST" -v w="$WARMUP" -v s="$DURATION" -v a="$arms" \
            'BEGIN{printf "%.0f", r*(rest + a*(w+s))}')
    fi
    echo "estimated wall (bench only): tier1=${est_t1}s tier2=${est_t2}s total=$((est_t1 + est_t2))s (~$(( (est_t1 + est_t2) / 60 )) min; preflight/analysis excluded)"
    echo "after a real run the driver invokes: python3 $ANALYZER --session $SESSION"
    echo "=========================================================================================="
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
echo "[durability-ab] B6 W24 durability A/B (docs/b6-design.md §6.3; supporting evidence only)"
echo "[durability-ab] session=$SESSION cell=$CELL size=$SIZE sync-every=$SYNC_EVERY R=$REPS rest=${REST}s warmup=${WARMUP}s measured=${DURATION}s cpu=$CPU"

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

echo "[durability-ab] compiling JUring stream runner once (JURING_SKIP_MVN=1, no Maven) ..."
if ! JURING_SKIP_MVN=1 bash "$JURING_RUN" --help >/dev/null 2>&1; then
    echo "error: JUring stream runner compile/--help failed; is the B6 stream API in target/classes?" >&2
    exit 1
fi
echo "[durability-ab] JUring stream runner ready; manifest: $MANIFEST"
echo "[durability-ab] started: $(now_utc)"

FAILS=0

# window_done <order>: every row of the window already has a valid JSON (for --resume).
window_done() {
    local spec engine variant rep=$1
    shift
    (( RESUME == 1 )) || return 1
    for spec in "$@"; do
        engine=${spec%%:*}
        variant=${spec##*:}
        [[ $(json_ok "$(row_json "$engine" "$variant" "$rep")") == ok ]] || return 1
    done
    return 0
}

# run_window <tier> <rep> <rest> <order...>
run_window() {
    local tier=$1 rep=$2 rest=$3
    shift 3
    local -a order=("$@")
    local spec engine variant
    if window_done "$rep" "${order[@]}"; then
        echo "[durability-ab] $tier rep $rep/$((tier == 1 ? REPS : TIER2_REPS)) already complete (--resume); skipping rest+window"
        return 0
    fi
    if awk -v r="$rest" 'BEGIN{exit !(r > 0)}'; then
        echo "[durability-ab] $tier rest ${rest}s before rep $rep (loadavg=$(loadavg))"
        sleep "$rest"
    fi
    for spec in "${order[@]}"; do
        engine=${spec%%:*}
        variant=${spec##*:}
        run_row "$engine" "$variant" "$rep" || FAILS=$((FAILS + 1))
    done
}

# Tier 1: R rep windows, 4 arms back-to-back per window.
for rep in $(seq 1 "$REPS"); do
    read -r -a ORDER <<<"$(tier1_order "$rep")"
    run_window 1 "$rep" "$REST" "${ORDER[@]}"
done

# Bracket control: the JUring writeback arm runs first overall (rep 1) and last overall.
if [[ $(json_ok "$(row_json juring writeback-bracket 1)") == ok ]]; then
    echo "[durability-ab] bracket control already complete (--resume); skipping"
else
    echo "[durability-ab] tier1 bracket control (last overall, immediately after rep $REPS)"
    run_row juring writeback-bracket 1 || FAILS=$((FAILS + 1))
fi

# Tier 2: exploratory dsync pair (+ optional JUring-only fsync), 120 s rests.
if (( SKIP_TIER2 == 0 )); then
    for rep in $(seq 1 "$TIER2_REPS"); do
        read -r -a ORDER <<<"$(tier2_order "$rep")"
        run_window 2 "$rep" "$TIER2_REST" "${ORDER[@]}"
    done
fi

echo "[durability-ab] campaign done: $(now_utc) failures=$FAILS"
echo "[durability-ab] cell-meta: $META"

if (( FAILS > 0 )); then
    echo "error: $FAILS invocation(s) failed; inspect cell-meta.tsv and logs/" >&2
fi

if (( NO_ANALYZE == 0 )); then
    if [[ -f $ANALYZER ]]; then
        echo "[durability-ab] analyzing: $ANALYZER --session $SESSION"
        if python3 "$ANALYZER" --session "$SESSION" >"$AGG_DIR/analyze.log" 2>&1; then
            echo "[durability-ab] aggregate: $AGG_DIR/durability.json, $AGG_DIR/durability.md"
        else
            echo "error: analyzer failed; see $AGG_DIR/analyze.log" >&2
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
echo "[durability-ab] ok"
