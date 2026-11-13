#!/usr/bin/env bash
#
# run-write-cells.sh - execute the M1 write matrix (methodology W5-W8, W12-W14).
#
# Cells (docs/benchmark-methodology.md section 2):
#   W5  randwrite 4K  QD1    60 s warmup / 60 s measured
#   W6  randwrite 4K  QD16  120/60
#   W7  randwrite 4K  QD64  120/60  (+ fio batching sweep b=1,8,16,32)
#   W8  randwrite 4K  QD128 120/60
#   W12 seqwrite  1M  QD1    60/60
#   W13 seqwrite  1M  QD8    60/60
#   W14 seqwrite  1M  QD32   60/60
# Engines: juring (bench/juring/run-juring.sh, JURING_SKIP_COMPILE=1),
#          fio (io_uring; variant default / matched registration / batching sweep),
#          seastar (bench/seastar/seastar_file_bench; WRITE PATH IS UNTESTED, so it
#          must be explicitly enabled with --allow-seastar-write).
# Dataset: ONE dedicated 10 GiB non-sparse file on the data NVMe
#          (<repo>/nvme-data/write_files/single_file_bench_w.bin, see
#          create-write-file.sh).  The read dataset is never opened for writing.
#
# Device-state controls encoded here:
#   D1  preconditioning before every measured block: calls precondition.sh
#       (3x file size of 4K randwrite QD64 AND >=120 s, then fsync), records the
#       write generation; --precondition=per-cell|session-once|none.
#   D2  1 s /sys/block/nvme1n1/stat series per measured run via
#       sample-device-iops.sh (background); --no-sampler to disable.
#   D3  300 s idle rest between measured runs (--rest-sec), 600 s after a
#       preconditioning burst (--precond-rest-sec); recorded in
#       state/rests.tsv.  --rest-scope=reps rests only between rep groups.
#   D4  refuses silent discard: records the mount options and warns if `discard`
#       is present; never issues blkdiscard/TRIM.
#   D5  records thermal state before/after every run when readable (nvme
#       smart-log needs root; hwmon is used when available) and marks it
#       unavailable otherwise - the host-root step is in bench/write/README.md.
#   D6  monotonic cell order W5->W8, W12->W14; engine order within a rep is
#       rotated by rep (A,B / B,A / A,B) and the exact order is written to
#       state/order.tsv.  Preconditioning stays monotonic.
#   D7  emits per-run RESULT lines and per-cell CELL_SUMMARY lines with n,
#       mean, sd, CV%; CV>10% is flagged unstable (no ranking).
#   F14 gate: loadavg(1m) > --loadavg-max aborts (or warns) before a run.
#
# Outputs (all below <session-dir>, default bench/results/m1-2026-11-13-write):
#   cells/<case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>.json
#   logs/<same stem>.log
#   series/<same stem>.tsv                  (D2 device time series)
#   state/order.tsv, runs.tsv, rests.tsv, cell-summaries.txt,
#   state/write-generations.tsv, state/precondition-results.txt
#   manifest.txt
#
# Usage:
#   bench/write/run-write-cells.sh --dry-run            # print the whole plan
#   bench/write/run-write-cells.sh --cells W5 --reps 1  # smoke subset
#   bench/write/run-write-cells.sh                      # full protocol (R=3)
#
# Exit status: 0 all runs ok, 1 at least one failure, 2 usage error.
#
set -euo pipefail

PROG=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)

READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
WRITE_FILE=${WRITE_FILE:-$REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin}
SESSION_DIR=${SESSION_DIR:-$REPO_ROOT/bench/results/m1-2026-11-13-write}
DEVICE_STAT=${DEVICE_STAT:-/sys/block/nvme1n1/stat}

CELLS_RAW="W5 W6 W7 W8 W12 W13 W14"
ENGINES_RAW="juring,fio"
FIO_VARIANTS_RAW="default,matched"
W7_SWEEP=1
CPU=2
SEED=20261113
MEASURED_SEC=60
REPS=3
REST_SEC=300
PRECOND_REST_SEC=600
REST_SCOPE=runs
WARMUP_OVERRIDE=""
PRECOND_MODE=per-cell
EXPECT_SIZE_SPEC=10g
LOADAVG_MAX=0.5
LOADAVG_ACTION=abort
SAMPLER=1
ALLOW_SEASTAR_WRITE=0
ALLOW_CONCURRENT=0
ALLOW_CAPPED_PRECOND=0
DRY_RUN=0

usage() {
    cat <<'EOF'
run-write-cells.sh - execute the M1 write matrix (W5-W8, W12-W14)

Usage:
  bench/write/run-write-cells.sh [options]

Selection:
  --cells LIST           comma/space list from W5,W6,W7,W8,W12,W13,W14
                         (default all, monotonic order preserved)
  --engines LIST         subset of juring,fio,seastar (default juring,fio)
  --fio-variants LIST    subset of default,matched,matchedbatch,batch8,batch16,
                         batch32,sqpoll,libaio (default default,matched; the
                         batching sweep batch8/batch16/batch32 is added for W7
                         unless --no-w7-sweep, and matchedbatch is added for
                         W7/W8 as the M1 gate comparator)
  --no-w7-sweep          do not add the W7 fio batching sweep / W7-W8
                         matchedbatch comparator
  --allow-seastar-write  enable the seastar write path (untested; opt-in)

Protocol:
  --reps N               repetitions per cell (default 3, methodology D7)
  --measured-sec S       measured window (default 60)
  --warmup-sec S         warmup override for every cell; default per cell
                         from the methodology table (W5/W12/W13/W14=60,
                         W6/W7/W8=120)
  --rest-sec S           idle rest between measured runs (D3, default 300)
  --rest-scope runs|reps rest after every run (default) or only between rep
                         groups (identical engine order within a rep, no rest
                         between engines - cheaper, documented deviation)
  --precond-rest-sec S   idle rest after a preconditioning burst (D3, default 600)
  --precondition MODE    per-cell (D1 before every cell, default),
                         session-once (before the first cell only), or none
  --allow-capped-precondition
                         continue if precondition.sh stops at --max-sec without
                         meeting D1 (recorded as capped); default: abort

Paths / safety:
  --write-file PATH      dedicated write dataset
                         (default <repo>/nvme-data/write_files/single_file_bench_w.bin)
  --expect-size SIZE     expected file size (default 10g); refuse otherwise
  --session-dir DIR      result root
                         (default <repo>/bench/results/m1-2026-11-13-write)
  --device-stat PATH     sysfs stat file (default /sys/block/nvme1n1/stat)
  --cpu N                taskset CPU (default 2)
  --seed N               PRNG seed (default 20261113)
  --loadavg-max F        F14 gate threshold for loadavg(1m) (default 0.5)
  --loadavg-action abort|warn   (default abort)
  --allow-concurrent     do not abort when another fio/seastar/JUring bench
                         process is already running
  --no-sampler           disable the D2 device time series
  --dry-run              print the complete plan + every command; execute
                         NOTHING (no I/O, no mkdir, no sleeps)
  -h, --help             this text

Machine-readable output while running:
  RESULT ...       one line per measured run (status, iops, mibs, p99, bytes,
                   device write sectors, json/log/series paths)
  CELL_SUMMARY ... one line per (cell, engine, variant) with n, mean, sd, CV%,
                   unstable flag
  SESSION_RESULT  final line with run/failure counts

The script refuses to start unless the write file exists, matches --expect-size,
is >=99% allocated (non-sparse), is on the data NVMe ext4 filesystem, and the
free-space/concurrency checks pass.  It never writes the read dataset.
EOF
}

die()  { printf '%s: error: %s\n' "$PROG" "$*" >&2; exit 1; }
warn() { printf '%s: warning: %s\n' "$PROG" "$*" >&2; }
note() { printf '%s\n' "$*"; }

parse_size() {
    local raw=$1 lower num mult
    lower=$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')
    num=${raw%%[a-zA-Z]*}
    [[ "$num" =~ ^[0-9]+$ ]] || return 1
    case "$lower" in
        *t|*tb|*tib) mult=1099511627776 ;;
        *g|*gb|*gib) mult=1073741824 ;;
        *m|*mb|*mib) mult=1048576 ;;
        *k|*kb|*kib) mult=1024 ;;
        *)           mult=1 ;;
    esac
    echo $(( num * mult ))
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --cells)                    [[ $# -ge 2 ]] || die "--cells needs a value"; CELLS_RAW=$2; shift 2 ;;
        --engines)                  [[ $# -ge 2 ]] || die "--engines needs a value"; ENGINES_RAW=$2; shift 2 ;;
        --fio-variants)             [[ $# -ge 2 ]] || die "--fio-variants needs a value"; FIO_VARIANTS_RAW=$2; shift 2 ;;
        --no-w7-sweep)              W7_SWEEP=0; shift ;;
        --allow-seastar-write)      ALLOW_SEASTAR_WRITE=1; shift ;;
        --reps)                     [[ $# -ge 2 ]] || die "--reps needs a value"; REPS=$2; shift 2 ;;
        --measured-sec)             [[ $# -ge 2 ]] || die "--measured-sec needs a value"; MEASURED_SEC=$2; shift 2 ;;
        --warmup-sec)               [[ $# -ge 2 ]] || die "--warmup-sec needs a value"; WARMUP_OVERRIDE=$2; shift 2 ;;
        --rest-sec)                 [[ $# -ge 2 ]] || die "--rest-sec needs a value"; REST_SEC=$2; shift 2 ;;
        --rest-scope)               [[ $# -ge 2 ]] || die "--rest-scope needs a value"; REST_SCOPE=$2; shift 2 ;;
        --precond-rest-sec)         [[ $# -ge 2 ]] || die "--precond-rest-sec needs a value"; PRECOND_REST_SEC=$2; shift 2 ;;
        --precondition)             [[ $# -ge 2 ]] || die "--precondition needs a value"; PRECOND_MODE=$2; shift 2 ;;
        --allow-capped-precondition) ALLOW_CAPPED_PRECOND=1; shift ;;
        --write-file)               [[ $# -ge 2 ]] || die "--write-file needs a value"; WRITE_FILE=$2; shift 2 ;;
        --expect-size)              [[ $# -ge 2 ]] || die "--expect-size needs a value"; EXPECT_SIZE_SPEC=$2; shift 2 ;;
        --session-dir)              [[ $# -ge 2 ]] || die "--session-dir needs a value"; SESSION_DIR=$2; shift 2 ;;
        --device-stat)              [[ $# -ge 2 ]] || die "--device-stat needs a value"; DEVICE_STAT=$2; shift 2 ;;
        --cpu)                      [[ $# -ge 2 ]] || die "--cpu needs a value"; CPU=$2; shift 2 ;;
        --seed)                     [[ $# -ge 2 ]] || die "--seed needs a value"; SEED=$2; shift 2 ;;
        --loadavg-max)              [[ $# -ge 2 ]] || die "--loadavg-max needs a value"; LOADAVG_MAX=$2; shift 2 ;;
        --loadavg-action)           [[ $# -ge 2 ]] || die "--loadavg-action needs a value"; LOADAVG_ACTION=$2; shift 2 ;;
        --allow-concurrent)         ALLOW_CONCURRENT=1; shift ;;
        --no-sampler)               SAMPLER=0; shift ;;
        --dry-run)                  DRY_RUN=1; shift ;;
        -h|--help)                  usage; exit 0 ;;
        *) printf '%s: error: unknown option %s (try --help)\n' "$PROG" "$1" >&2; exit 2 ;;
    esac
done

[[ "$CPU" =~ ^[0-9]+$ ]] || die "--cpu must be a CPU number"
[[ "$SEED" =~ ^[0-9]+$ ]] || die "--seed must be an integer"
[[ "$REPS" =~ ^[1-9][0-9]*$ ]] || die "--reps must be >= 1"
[[ "$MEASURED_SEC" =~ ^[1-9][0-9]*$ ]] || die "--measured-sec must be >= 1"
[[ "$REST_SEC" =~ ^[0-9]+$ ]] || die "--rest-sec must be >= 0"
[[ "$PRECOND_REST_SEC" =~ ^[0-9]+$ ]] || die "--precond-rest-sec must be >= 0"
[[ "$LOADAVG_MAX" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--loadavg-max must be a number"
[[ "$LOADAVG_ACTION" == "abort" || "$LOADAVG_ACTION" == "warn" ]] || die "--loadavg-action must be abort or warn"
[[ "$REST_SCOPE" == "runs" || "$REST_SCOPE" == "reps" ]] || die "--rest-scope must be runs or reps"
case "$PRECOND_MODE" in per-cell|session-once|none) : ;; *) die "--precondition must be per-cell, session-once or none" ;; esac
if [[ -n "$WARMUP_OVERRIDE" ]]; then
    [[ "$WARMUP_OVERRIDE" =~ ^[0-9]+$ ]] || die "--warmup-sec must be >= 0"
fi
[[ -r "$DEVICE_STAT" ]] || die "cannot read device stat file: $DEVICE_STAT"
[[ -x "$SCRIPT_DIR/precondition.sh" ]] || die "missing helper: $SCRIPT_DIR/precondition.sh"
if (( SAMPLER )); then
    [[ -x "$SCRIPT_DIR/sample-device-iops.sh" ]] || die "missing helper: $SCRIPT_DIR/sample-device-iops.sh"
fi

EXPECT_BYTES=$(parse_size "$EXPECT_SIZE_SPEC") || die "cannot parse --expect-size '$EXPECT_SIZE_SPEC'"

canon() { realpath -m -- "$1"; }
WF=$(canon "$WRITE_FILE")
RF=$(canon "$READ_FILE")
ND=$(canon "$REPO_ROOT/nvme-data")
[[ "$WF" == "$RF" ]] && die "refusing: write file resolves to the read dataset ($RF)"
case "$WF" in
    /mnt/TestData/read_files/*) die "refusing: write file is below the read dataset ($WF)" ;;
esac
if [[ "$WF" != "$ND"/* ]]; then
    die "refusing: write file must be below $ND (got $WF); use --write-file with a path on the writable NVMe mount"
fi

CELLS=$(printf '%s' "$CELLS_RAW" | tr ',' ' ')
ENGINES=$(printf '%s' "$ENGINES_RAW" | tr ',' ' ')
FIO_VARIANTS=$(printf '%s' "$FIO_VARIANTS_RAW" | tr ',' ' ')

# ------------------------------------------------------------------ cell table
# cell_meta CASE -> "<workload> <bs_label> <bs_bytes> <qd> <default_warmup_sec>"
cell_meta() {
    case "$1" in
        W5)  echo "randwrite 4k 4096 1 60" ;;
        W6)  echo "randwrite 4k 4096 16 120" ;;
        W7)  echo "randwrite 4k 4096 64 120" ;;
        W8)  echo "randwrite 4k 4096 128 120" ;;
        W12) echo "seqwrite 1m 1048576 1 60" ;;
        W13) echo "seqwrite 1m 1048576 8 60" ;;
        W14) echo "seqwrite 1m 1048576 32 60" ;;
        *) return 1 ;;
    esac
}

for cell in $CELLS; do
    cell_meta "$cell" >/dev/null || die "unknown --cells entry '$cell' (W5 W6 W7 W8 W12 W13 W14)"
done
for eng in $ENGINES; do
    case "$eng" in juring|fio|seastar) : ;; *) die "unknown --engines entry '$eng' (juring|fio|seastar)" ;; esac
done
if [[ " $ENGINES " == *" seastar "* ]] && (( ! ALLOW_SEASTAR_WRITE )); then
    die "seastar write path is unvalidated: pass --allow-seastar-write to opt in explicitly"
fi
for v in $FIO_VARIANTS; do
    case "$v" in default|matched|matchedbatch|batch8|batch16|batch32|sqpoll|libaio) : ;; *) die "unknown --fio-variants entry '$v'" ;; esac
done

# ------------------------------------------------------------------ run plan
SPEC_TOKEN=()
SPEC_VARIANT=()
SPEC_KIND=()

build_specs() { # $1 = cell
    local cell=$1 v
    SPEC_TOKEN=(); SPEC_VARIANT=(); SPEC_KIND=()
    for eng in $ENGINES; do
        case "$eng" in
            juring)
                SPEC_TOKEN+=("juring"); SPEC_VARIANT+=("std"); SPEC_KIND+=("juring") ;;
            seastar)
                SPEC_TOKEN+=("seastar"); SPEC_VARIANT+=("std"); SPEC_KIND+=("seastar") ;;
            fio)
                local variants=$FIO_VARIANTS
                if (( W7_SWEEP )); then
                    if [[ "$cell" == "W7" ]]; then
                        for extra in batch8 batch16 batch32 matchedbatch; do
                            case " $variants " in *" $extra "*) : ;; *) variants="$variants $extra" ;; esac
                        done
                    elif [[ "$cell" == "W8" ]]; then
                        case " $variants " in *" matchedbatch "*) : ;; *) variants="$variants matchedbatch" ;; esac
                    fi
                fi
                for v in $variants; do
                    case "$v" in
                        libaio) SPEC_TOKEN+=("fio-libaio"); SPEC_VARIANT+=("std"); SPEC_KIND+=("fio-libaio") ;;
                        *)      SPEC_TOKEN+=("fio-io_uring"); SPEC_VARIANT+=("$v"); SPEC_KIND+=("fio") ;;
                    esac
                done ;;
        esac
    done
    (( ${#SPEC_TOKEN[@]} > 0 )) || die "no engine variants selected"
}

PLAN=()
SEQ=0
for cell in $CELLS; do
    read -r wl bs_label bs_bytes qd def_warmup <<<"$(cell_meta "$cell")"
    warmup=${WARMUP_OVERRIDE:-$def_warmup}
    build_specs "$cell"
    nspec=${#SPEC_TOKEN[@]}
    for rep in $(seq 1 "$REPS"); do
        for i in $(seq 0 $(( nspec - 1 ))); do
            idx=$(( (i + rep - 1) % nspec ))
            SEQ=$(( SEQ + 1 ))
            PLAN+=("$SEQ|$cell|${SPEC_TOKEN[$idx]}|${SPEC_VARIANT[$idx]}|${SPEC_KIND[$idx]}|$bs_label|$bs_bytes|$qd|$warmup|$rep|$nspec|$i")
        done
    done
done
TOTAL=${#PLAN[@]}
(( TOTAL > 0 )) || die "empty run plan"

# rest_after flag per plan entry (13th field)
PLAN_WITH_RESTS=()
for i in "${!PLAN[@]}"; do
    IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos <<<"${PLAN[$i]}"
    rest=0
    if (( i < TOTAL - 1 )); then
        if [[ "$REST_SCOPE" == "runs" ]]; then
            rest=1
        elif (( pos == nspec - 1 )); then
            rest=1
        fi
    fi
    PLAN_WITH_RESTS+=("${PLAN[$i]}|$rest")
done
PLAN=("${PLAN_WITH_RESTS[@]}")

# ------------------------------------------------------------------ helpers
now_s() { date +%s; }
now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
devstat() {
    awk '
        {
            r_ios = $1 + 0; r_sec = $3 + 0
            w_ios = $5 + 0; w_sec = $7 + 0
            in_flight = $9 + 0
        }
        END { print r_ios, r_sec, w_ios, w_sec, in_flight }
    ' "$DEVICE_STAT"
}
read_file_state() {
    stat -c 'size=%s blocks=%b mtime=%y' -- "$READ_FILE" 2>/dev/null || printf 'unavailable'
}
thermal_state() {
    local t
    for f in /sys/class/nvme/nvme1/device/hwmon/hwmon*/temp1_input \
             /sys/class/nvme/nvme1/hwmon*/temp1_input \
             /sys/block/nvme1n1/device/hwmon/hwmon*/temp1_input; do
        if [[ -r "$f" ]]; then
            t=$(cat "$f" 2>/dev/null || true)
            if [[ -n "$t" ]]; then
                printf 'hwmon_mc=%s' "$t"
                return 0
            fi
        fi
    done
    if command -v nvme >/dev/null 2>&1; then
        t=$(timeout 2 nvme smart-log /dev/nvme1n1 2>/dev/null | awk -F: '/temperature/ {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' || true)
        if [[ -n "$t" ]]; then
            printf 'smart_log=%s' "$t"
            return 0
        fi
    fi
    printf 'unavailable(no-root)'
}
json_summary() { # $1 = kind, $2 = json path -> tab-separated: iops mibs p99 ops bytes errors status buffer_mode
    python3 - "$1" "$2" <<'PY'
import json
import sys

kind, path = sys.argv[1], sys.argv[2]

def emit(*vals):
    print("\t".join(str(v) for v in vals))

try:
    with open(path, "r", encoding="utf-8") as fh:
        doc = json.load(fh)
except Exception:
    emit("NA", "NA", "NA", "NA", "NA", "NA", "missing", "NA")
    sys.exit(0)

def num(x, nd=3):
    try:
        return round(float(x), nd)
    except Exception:
        return "NA"

if kind == "fio":
    jobs = doc.get("jobs") or []
    if not jobs:
        emit("NA", "NA", "NA", "NA", "NA", "NA", "error", "NA")
        sys.exit(0)
    job = jobs[0]
    wr = job.get("write") or {}
    pcts = (wr.get("clat_ns") or {}).get("percentile") or {}
    p99 = pcts.get("99.000000", pcts.get("99.0", "NA"))
    emit(num(wr.get("iops")),
         num(float(wr.get("bw_bytes", 0) or 0) / 1048576.0),
         num(p99),
         wr.get("total_ios", 0),
         wr.get("io_bytes", 0),
         job.get("error", 0),
         "ok",
         "fio")
else:
    agg = doc.get("aggregate") or {}
    iops = (agg.get("iops") or {}).get("mean")
    mibs = (agg.get("mibs") or {}).get("mean")
    lat = agg.get("latency_us") or {}
    runs = doc.get("runs") or [{}]
    emit(num(iops), num(mibs), num(lat.get("p99")),
         agg.get("ops_total", runs[0].get("ops_total")),
         agg.get("bytes_total", runs[0].get("bytes_total")),
         agg.get("errors_total", runs[0].get("errors_total", 0)),
         doc.get("status", "ok"),
         (doc.get("config") or {}).get("io_buffer_mode", "NA"))
PY
}

build_engine_cmd() { # kind variant workload bs_label qd warmup stem out_json
    local kind=$1 variant=$2 wl=$3 bs_label=$4 qd=$5 warmup=$6 stem=$7 out_json=$8
    CMD=()
    case "$kind" in
        juring)
            CMD=(env JURING_SKIP_COMPILE=1 taskset -c "$CPU"
                "$REPO_ROOT/bench/juring/run-juring.sh"
                --workload "$wl" --file "$WF" --bs "$bs_label" --qd "$qd" --threads 1
                --seconds "$MEASURED_SEC" --warmup-sec "$warmup" --runs 1 --seed "$SEED"
                --label "$stem" --json "$out_json") ;;
        seastar)
            CMD=(env LD_LIBRARY_PATH="$REPO_ROOT/.sysroot/root/usr/lib64" taskset -c "$CPU"
                "$REPO_ROOT/bench/seastar/seastar_file_bench"
                --file "$WF" --workload "$wl" --bs "$bs_label" --qd "$qd"
                --seconds "$MEASURED_SEC" --warmup-sec "$warmup" --seed "$SEED"
                --label "$stem" --smp 1 --cpuset "$CPU" --memory 1G --lock-memory 0
                --reactor-backend io_uring --json "$out_json") ;;
        fio)
            local fio_rw="randwrite"
            [[ "$wl" == "seqwrite" ]] && fio_rw="write"
            CMD=(taskset -c "$CPU" fio
                --name="$stem" --filename="$WF" --ioengine=io_uring --direct=1
                --rw="$fio_rw" --bs="$bs_label" --iodepth="$qd" --numjobs=1
                --runtime="$MEASURED_SEC" --ramp_time="$warmup" --time_based
                --group_reporting=1 --randrepeat=1 --clocksource=clock_gettime
                --percentile_list=50:95:99:99.9 --clat_percentiles=1 --lat_percentiles=0
                --output-format=json --output="$out_json")
            case "$variant" in
                default) : ;;
                matched)      CMD+=(--registerfiles=1 --fixedbufs=1) ;;
                matchedbatch) CMD+=(--registerfiles=1 --fixedbufs=1 --iodepth_batch_submit="$qd") ;;
                batch8)  CMD+=(--iodepth_batch_submit=8) ;;
                batch16) CMD+=(--iodepth_batch_submit=16) ;;
                batch32) CMD+=(--iodepth_batch_submit=32) ;;
                sqpoll)  CMD+=(--sqthread_poll=1) ;;
                *) die "unsupported fio variant '$variant'" ;;
            esac ;;
        fio-libaio)
            local libaio_rw="randwrite"
            [[ "$wl" == "seqwrite" ]] && libaio_rw="write"
            CMD=(taskset -c "$CPU" fio
                --name="$stem" --filename="$WF" --ioengine=libaio --direct=1
                --rw="$libaio_rw" --bs="$bs_label" --iodepth="$qd" --numjobs=1
                --runtime="$MEASURED_SEC" --ramp_time="$warmup" --time_based
                --group_reporting=1 --randrepeat=1 --clocksource=clock_gettime
                --percentile_list=50:95:99:99.9 --clat_percentiles=1 --lat_percentiles=0
                --output-format=json --output="$out_json") ;;
        *) die "unsupported engine kind '$kind'" ;;
    esac
}

fmt_cmd() { printf '%q ' "${CMD[@]}" | sed 's/ $//'; }

do_rest() { # seconds phase after_ref
    local secs=$1 phase=$2 after=$3
    (( secs > 0 )) || return 0
    local t0 t1 actual
    note "== [D3] rest ${secs}s ($phase) =="
    t0=$(now_s)
    sleep "$secs"
    t1=$(now_s)
    actual=$(( t1 - t0 ))
    if (( ! DRY_RUN )); then
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
            "$phase" "$after" "$secs" "$actual" "$(date -u -d "@$t0" +%Y-%m-%dT%H:%M:%SZ)" "$(date -u -d "@$t1" +%Y-%m-%dT%H:%M:%SZ)" \
            >>"$SESSION_DIR/state/rests.tsv"
    fi
}

run_precondition() { # cell
    local cell=$1
    local plog="$SESSION_DIR/logs/precondition-${cell}-$(date -u +%Y%m%dT%H%M%SZ).log"
    note "== [D1] preconditioning before cell $cell (3x file size 4K randwrite QD64 + fsync) =="
    local -a pcmd=("$SCRIPT_DIR/precondition.sh"
        --file "$WF" --session-dir "$SESSION_DIR" --device-stat "$DEVICE_STAT"
        --cpu "$CPU" --bs 4k --qd 64)
    local rc=0
    set +e
    "${pcmd[@]}" >"$plog" 2>&1
    rc=$?
    set -e
    grep -h 'PRECONDITION_RESULT' "$plog" >>"$SESSION_DIR/state/precondition-results.txt" 2>/dev/null || true
    local summary
    summary=$(grep -h 'PRECONDITION_RESULT' "$plog" | tail -n1 || true)
    if (( rc != 0 )); then
        if (( ALLOW_CAPPED_PRECOND )); then
            warn "preconditioning returned $rc (capped/failed); continuing because --allow-capped-precondition (see $plog)"
        else
            die "preconditioning failed/not satisfied (rc=$rc); see $plog (use --allow-capped-precondition to continue anyway)"
        fi
    fi
    note "   ${summary:-PRECONDITION_RESULT missing (see $plog)}"
    note "   log: $plog"
}

summarize_cell() { # cell
    local cell=$1
    local out
    out=$(python3 - "$CELLS_DIR" "$cell" <<'PY'
import glob
import json
import os
import statistics
import sys

cells_dir, cell = sys.argv[1], sys.argv[2]
groups = {}
for path in sorted(glob.glob(os.path.join(cells_dir, cell + "__*.json"))):
    base = os.path.basename(path)[:-5]
    parts = base.split("__")
    if len(parts) < 7:
        continue
    token, variant = parts[1], parts[2]
    bs = parts[3][2:] if parts[3].startswith("bs") else parts[3]
    qd = parts[4][2:] if parts[4].startswith("qd") else parts[4]
    key = (token, variant, bs, qd)
    try:
        with open(path, "r", encoding="utf-8") as fh:
            doc = json.load(fh)
    except Exception:
        continue
    if "jobs" in doc:
        jobs = doc.get("jobs") or []
        if not jobs:
            continue
        wr = jobs[0].get("write") or {}
        try:
            iops = float(wr.get("iops", 0) or 0)
            mibs = float(wr.get("bw_bytes", 0) or 0) / 1048576.0
        except Exception:
            continue
        pcts = (wr.get("clat_ns") or {}).get("percentile") or {}
        try:
            p99 = float(pcts.get("99.000000", 0))
        except Exception:
            p99 = 0.0
        ok = (jobs[0].get("error", 0) == 0)
    else:
        agg = doc.get("aggregate") or {}
        iops = float((agg.get("iops") or {}).get("mean", 0) or 0)
        mibs = float((agg.get("mibs") or {}).get("mean", 0) or 0)
        p99 = float((agg.get("latency_us") or {}).get("p99", 0) or 0)
        ok = (doc.get("status", "ok") == "ok")
    groups.setdefault(key, []).append((iops, mibs, p99, ok))

for (token, variant, bs, qd), rows in sorted(groups.items()):
    n = len(rows)
    ok_n = sum(1 for r in rows if r[3])
    iops = [r[0] for r in rows]
    mibs = [r[1] for r in rows]
    p99s = [r[2] for r in rows]
    mean = statistics.fmean(iops) if iops else 0.0
    sd = statistics.stdev(iops) if len(iops) > 1 else 0.0
    cv = (sd / mean * 100.0) if mean > 0 else 0.0
    unstable = 1 if cv > 10.0 else 0
    print("CELL_SUMMARY case={} engine={} variant={} bs={} qd={} n={} ok={} "
          "iops_mean={:.1f} iops_sd={:.1f} cv_pct={:.2f} mibs_mean={:.1f} p99_mean_us={:.1f} unstable={}".format(
              cell, token, variant, bs, qd, n, ok_n, mean, sd, cv,
              statistics.fmean(mibs) if mibs else 0.0,
              statistics.fmean(p99s) if p99s else 0.0, unstable))
PY
)
    if [[ -n "$out" ]]; then
        printf '%s\n' "$out"
        if (( ! DRY_RUN )); then
            printf '%s\n' "$out" >>"$SESSION_DIR/state/cell-summaries.txt"
        fi
    fi
}

run_one() { # seq cell token variant kind bs_label bs_bytes qd warmup rep rest_after
    local seq=$1 cell=$2 token=$3 variant=$4 kind=$5 bs_label=$6 bs_bytes=$7 qd=$8 warmup=$9 rep=${10} rest_after=${11}
    local stem="${cell}__${token}__${variant}__bs${bs_label}__qd${qd}__t1__r${rep}"
    local json="$CELLS_DIR/$stem.json"
    local log="$LOGS_DIR/$stem.log"
    local series="$SERIES_DIR/$stem.tsv"

    # ---- F14 loadavg gate
    local load1 top_proc gate=ok
    load1=$(awk '{print $1}' /proc/loadavg)
    top_proc=$(ps -eo pcpu=,comm= --sort=-pcpu 2>/dev/null | head -n1 | awk '{print $1"/"$2}' || true)
    [[ -n "$top_proc" ]] || top_proc="NA"
    if awk -v a="$load1" -v b="$LOADAVG_MAX" 'BEGIN{exit !(a+0 > b+0)}'; then
        gate="over($load1>$LOADAVG_MAX)"
        if [[ "$LOADAVG_ACTION" == "abort" ]]; then
            die "loadavg(1m)=$load1 > $LOADAVG_MAX before $stem (F14); aborting. Wait for an idle machine or pass --loadavg-action warn"
        fi
        warn "loadavg(1m)=$load1 > $LOADAVG_MAX before $stem (F14); continuing (--loadavg-action warn)"
    fi

    local wl
    read -r wl _ _ _ _ <<<"$(cell_meta "$cell")"

    note "== [cell=$cell] $token/$variant rep=$rep qd=$qd bs=$bs_label warmup=${warmup}s measured=${MEASURED_SEC}s =="
    note "   json=$json"

    build_engine_cmd "$kind" "$variant" "$wl" "$bs_label" "$qd" "$warmup" "$stem" "$json"
    local started_utc finished_utc t0 t1 elapsed rc=0
    local thermal_start thermal_end loadavg_start_full
    loadavg_start_full=$(awk '{print $1","$2","$3}' /proc/loadavg)
    local DR_IOS DR_SEC DW_IOS DW_SEC _
    read -r DR_IOS DR_SEC DW_IOS DW_SEC _ <<<"$(devstat)"
    thermal_start=$(thermal_state)
    started_utc=$(now_utc)

    {
        printf '=== run %s %s\n' "$stem" "$started_utc"
        printf 'CMD: %s\n' "$(fmt_cmd)"
        printf 'warmup_s=%s measured_s=%s reps=%s seed=%s cpu=%s\n' "$warmup" "$MEASURED_SEC" "$REPS" "$SEED" "$CPU"
        printf 'loadavg_start=%s top_proc=%s governor=%s thermal_start=%s\n' \
            "$(awk '{print $1"/"$2"/"$3}' /proc/loadavg)" "$top_proc" "$GOVERNOR" "$thermal_start"
        printf 'device_write_ios_before=%s device_write_sectors_before=%s\n' "$DW_IOS" "$DW_SEC"
    } >"$log"

    SAMPLER_PID=""
    if (( SAMPLER )); then
        "$SCRIPT_DIR/sample-device-iops.sh" --out "$series" --device-stat "$DEVICE_STAT" \
            --interval 1 --label "$stem" >>"$log" 2>&1 &
        SAMPLER_PID=$!
    fi

    t0=$(now_s)
    set +e
    "${CMD[@]}" >>"$log" 2>&1
    rc=$?
    set -e
    t1=$(now_s)
    elapsed=$(( t1 - t0 ))
    finished_utc=$(now_utc)

    if [[ -n "$SAMPLER_PID" ]]; then
        kill -TERM "$SAMPLER_PID" 2>/dev/null || true
        wait "$SAMPLER_PID" 2>/dev/null || true
        SAMPLER_PID=""
    fi

    local loadavg_end_full
    loadavg_end_full=$(awk '{print $1"/"$2"/"$3}' /proc/loadavg)
    thermal_end=$(thermal_state)
    local DR_IOS2 DR_SEC2 DW_IOS2 DW_SEC2 _
    read -r DR_IOS2 DR_SEC2 DW_IOS2 DW_SEC2 _ <<<"$(devstat)"
    local w_ios_d=$(( DW_IOS2 - DW_IOS ))
    local w_sec_d=$(( DW_SEC2 - DW_SEC ))
    (( w_ios_d < 0 )) && w_ios_d=0
    (( w_sec_d < 0 )) && w_sec_d=0
    local dev_write_mib=$(( w_sec_d / 2048 ))

    # ---- result
    local js jstatus status
    js=$(json_summary "$kind" "$json")
    local iops mibs p99 ops bytes errors bmode
    IFS=$'\t' read -r iops mibs p99 ops bytes errors jstatus bmode <<<"$js"
    status=ok
    if (( rc != 0 )); then status=fail; fi
    if [[ "$jstatus" != "ok" ]]; then status=fail; fi
    if [[ "$status" == "ok" && "$bytes" =~ ^[0-9]+$ ]] && (( bytes == 0 )); then
        status=fail
    fi
    if [[ "$status" == "ok" && "$w_sec_d" -le 0 ]]; then
        warn "no device write sectors moved during $stem; result suspicious"
        status=fail
    fi
    if [[ "$status" == "ok" && "$bytes" =~ ^[0-9]+$ ]] && (( bytes > 0 )); then
        local dev_bytes=$(( w_sec_d * 512 ))
        if (( dev_bytes < bytes * 9 / 10 )); then
            warn "device wrote ${dev_bytes} B but the engine reports ${bytes} B (10% gate, methodology section 6)"
        fi
    fi

    {
        printf '=== done %s rc=%s status=%s elapsed_s=%s finished_utc=%s loadavg_end=%s thermal_end=%s dev_write_sectors_d=%s\n' \
            "$stem" "$rc" "$status" "$elapsed" "$finished_utc" "$loadavg_end_full" "$thermal_end" "$w_sec_d"
        printf 'RESULT seq=%s cell=%s engine=%s variant=%s bs=%s qd=%s rep=%s status=%s exit=%s iops=%s mibs=%s p99_us=%s bytes=%s errors=%s buffer_mode=%s dev_write_mib=%s elapsed_s=%s\n' \
            "$seq" "$cell" "$token" "$variant" "$bs_label" "$qd" "$rep" "$status" "$rc" \
            "$iops" "$mibs" "$p99" "$bytes" "$errors" "$bmode" "$dev_write_mib" "$elapsed"
    } >>"$log"

    printf 'RESULT seq=%s cell=%s engine=%s variant=%s bs=%s qd=%s rep=%s status=%s exit=%s iops=%s mibs=%s p99_us=%s bytes=%s errors=%s buffer_mode=%s dev_write_mib=%s elapsed_s=%s json=%s log=%s series=%s\n' \
        "$seq" "$cell" "$token" "$variant" "$bs_label" "$qd" "$rep" "$status" "$rc" \
        "$iops" "$mibs" "$p99" "$bytes" "$errors" "$bmode" "$dev_write_mib" "$elapsed" \
        "$json" "$log" "$series"

    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$seq" "$cell" "$token" "$variant" "$bs_label" "$qd" "$rep" "$warmup" "$MEASURED_SEC" \
        "$status" "$rc" "$json" "$log" "$series" "$iops" "$mibs" "$p99" "$ops" "$bytes" "$errors" \
        "$bmode" "$w_sec_d" "$dev_write_mib" "$load1" "$loadavg_end_full" "$GOVERNOR" \
        "$thermal_start" "$thermal_end" "$started_utc" >>"$SESSION_DIR/state/runs.tsv"

    # bench/m1/lib.sh-compatible row (same 17 columns as the read session)
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$cell" "$token" "$variant" "$bs_label" "$qd" "$rep" "$(now_utc)" \
        "$loadavg_start_full" "$loadavg_end_full" "$(( DR_SEC2 - DR_SEC ))" "$w_sec_d" \
        "$ops" "$iops" "$p99" "$elapsed" "$status" "$bytes" >>"$CELL_META"

    if (( rest_after )); then
        do_rest "$REST_SEC" "between-measured-runs" "$seq"
    fi
    LAST_STATUS=$status
    return 0
}

# ------------------------------------------------------------------ dry run
if (( DRY_RUN )); then
    printf '# dry-run: run-write-cells.sh - NOTHING is executed (no I/O, no dirs, no sleeps)\n'
    printf '#   write_file=%s\n' "$WF"
    printf '#   read_file=%s (never touched)\n' "$RF"
    printf '#   session_dir=%s\n' "$SESSION_DIR"
    printf '#   cells=%s engines=%s fio_variants=%s w7_sweep=%s\n' \
        "$CELLS" "$ENGINES" "$FIO_VARIANTS" "$W7_SWEEP"
    printf '#   protocol: reps=%s measured=%ss warmup=%s rest=%ss rest_scope=%s precond_rest=%ss precondition=%s\n' \
        "$REPS" "$MEASURED_SEC" "${WARMUP_OVERRIDE:-per-cell}" "$REST_SEC" "$REST_SCOPE" "$PRECOND_REST_SEC" "$PRECOND_MODE"
    printf '#   cpu=%s seed=%s expect_size_bytes=%s sampler=%s\n' \
        "$CPU" "$SEED" "$EXPECT_BYTES" "$SAMPLER"
    if [[ ! -e "$WF" ]]; then
        printf '#   WARNING: write file does not exist yet; run bench/write/create-write-file.sh first\n'
    else
        read -r cur_size cur_blocks < <(stat -c '%s %b' -- "$WF")
        printf '#   write file exists: size=%s allocated=%s (expected %s)\n' "$cur_size" "$(( cur_blocks * 512 ))" "$EXPECT_BYTES"
    fi
    printf '#\n# planned runs: %s\n#\n' "$TOTAL"
    prev_cell=""
    for entry in "${PLAN[@]}"; do
        IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
        if [[ "$cell" != "$prev_cell" ]]; then
            if [[ "$PRECOND_MODE" == "per-cell" ]] || { [[ "$PRECOND_MODE" == "session-once" ]] && [[ -z "$prev_cell" ]]; }; then
                printf '# precondition (D1) before cell %s: %s/precondition.sh --file %s --session-dir %s --device-stat %s --cpu %s --bs 4k --qd 64\n' \
                    "$cell" "$SCRIPT_DIR" "$WF" "$SESSION_DIR" "$DEVICE_STAT" "$CPU"
                printf '# rest %ss after preconditioning (D3)\n' "$PRECOND_REST_SEC"
            fi
            prev_cell=$cell
        fi
        local_json="$SESSION_DIR/cells/${cell}__${token}__${variant}__bs${bs_label}__qd${qd}__t1__r${rep}.json"
        stem="${cell}__${token}__${variant}__bs${bs_label}__qd${qd}__t1__r${rep}"
        read -r wl0 _ _ _ _ <<<"$(cell_meta "$cell")"
        build_engine_cmd "$kind" "$variant" "$wl0" "$bs_label" "$qd" "$warmup" "$stem" "$local_json"
        printf '# seq=%s cell=%s rep=%s warmup=%ss measured=%ss\n' "$seq" "$cell" "$rep" "$warmup" "$MEASURED_SEC"
        printf '#   cmd: %s\n' "$(fmt_cmd)"
        printf '#   json: %s\n' "$local_json"
        if (( SAMPLER )); then
            printf '#   sampler: %s/sample-device-iops.sh --out %s/series/%s.tsv --device-stat %s --interval 1 --label %s\n' \
                "$SCRIPT_DIR" "$SESSION_DIR" "$stem" "$DEVICE_STAT" "$stem"
        fi
        if (( rest_after )); then
            printf '#   rest: %ss after this run (D3)\n' "$REST_SEC"
        fi
    done
    precond_count=0
    case "$PRECOND_MODE" in per-cell) precond_count=0; for c in $CELLS; do precond_count=$(( precond_count + 1 )); done ;; session-once) precond_count=1 ;; none) precond_count=0 ;; esac
    est=0
    for entry in "${PLAN[@]}"; do
        IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
        est=$(( est + warmup + MEASURED_SEC + 3 ))
        if (( rest_after )); then est=$(( est + REST_SEC )); fi
    done
    est=$(( est + precond_count * (150 + PRECOND_REST_SEC) ))
    printf '#\n# estimated wall time: ~%ss (~%s min ~%s h), excluding JVM/fio startup variance and any precondition overrun\n' \
        "$est" "$(( est / 60 ))" "$(( est / 3600 ))"
    printf 'SESSION_PLAN cells=%s engines=%s runs=%s est_sec=%s session_dir=%s status=dry-run\n' \
        "$(printf '%s' "$CELLS")" "$(printf '%s' "$ENGINES")" "$TOTAL" "$est" "$SESSION_DIR"
    exit 0
fi

# ------------------------------------------------------------------ real run: safety checks
check_write_file() {
    [[ -e "$WF" ]] || die "write file missing: $WF (run bench/write/create-write-file.sh first)"
    [[ -f "$WF" ]] || die "not a regular file: $WF"
    [[ -w "$WF" ]] || die "write file is not writable: $WF"
    local size blocks alloc
    size=$(stat -c '%s' -- "$WF")
    blocks=$(stat -c '%b' -- "$WF")
    alloc=$(( blocks * 512 ))
    (( size == EXPECT_BYTES )) || die "write file size $size != expected $EXPECT_BYTES; recreate with create-write-file.sh"
    (( alloc >= EXPECT_BYTES * 99 / 100 )) || die "write file looks sparse (allocated $alloc of $EXPECT_BYTES); recreate with create-write-file.sh --force"
    note "write file: $WF size=$size allocated=$alloc"
}

check_free_space() {
    local dir free
    dir=$(dirname -- "$WF")
    while [[ ! -e "$dir" ]]; do dir=$(dirname -- "$dir"); done
    free=$(df -B1 --output=avail "$dir" 2>/dev/null | tail -n1 | tr -d ' ' || true)
    [[ -n "$free" ]] || die "cannot determine free space on $dir"
    (( free >= 2 * 1073741824 )) || die "less than 2 GiB free on $dir ($free bytes); refusing to write"
    note "free space on write filesystem: $free bytes"
}

check_concurrency() {
    local hits=""
    hits+=$(pgrep -x fio 2>/dev/null || true)
    hits+=$(printf '\n%s' "$(pgrep -f 'seastar_file_bench' 2>/dev/null || true)")
    hits+=$(printf '\n%s' "$(pgrep -f 'bench.juring.JUringFileBench' 2>/dev/null || true)")
    hits=$(printf '%s\n' "$hits" | sed '/^[[:space:]]*$/d')
    if [[ -n "$hits" ]]; then
        if (( ALLOW_CONCURRENT )); then
            warn "another benchmark-looking process is running; continuing because --allow-concurrent:"
            printf '%s\n' "$hits" >&2
        else
            printf '%s\n' "$hits" >&2
            die "another fio/seastar/JUring benchmark process is running; wait for it or pass --allow-concurrent"
        fi
    fi
}

if (( SAMPLER )); then
    [[ -x "$SCRIPT_DIR/sample-device-iops.sh" ]] || die "sampler missing: $SCRIPT_DIR/sample-device-iops.sh"
fi
command -v flock >/dev/null 2>&1 || die "flock not found (util-linux); cannot take the single-instance lock"
check_write_file
check_free_space
check_concurrency

GOVERNOR=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo unknown)
if [[ "$GOVERNOR" != "performance" ]]; then
    warn "CPU governor is '$GOVERNOR', not 'performance' (F7); results will not be comparable"
fi
WRITE_DIR=$(dirname -- "$WF")
while [[ ! -e "$WRITE_DIR" ]]; do WRITE_DIR=$(dirname -- "$WRITE_DIR"); done
FSTYPE=$(findmnt -no FSTYPE -T "$WRITE_DIR" 2>/dev/null | tail -n1 || true)
SOURCE=$(findmnt -no SOURCE -T "$WRITE_DIR" 2>/dev/null | tail -n1 || true)
MOUNT_OPTS=$(findmnt -no OPTIONS -T "$WRITE_DIR" 2>/dev/null | tail -n1 || true)
if [[ "$MOUNT_OPTS" == *discard* ]]; then
    warn "filesystem mount options contain 'discard' ($MOUNT_OPTS); methodology D4 says do not compare with discard active"
fi
if [[ "$FSTYPE" != "ext4" && "$FSTYPE" != "xfs" ]]; then
    warn "write filesystem is '$FSTYPE', not ext4/xfs (record it; F12/F13)"
fi
case "$SOURCE" in
    *nvme1n1p1*) : ;;
    *) warn "write filesystem source '$SOURCE' does not look like /dev/nvme1n1p1; is this the data NVMe?" ;;
esac

CELLS_DIR="$SESSION_DIR/cells"
LOGS_DIR="$SESSION_DIR/logs"
SERIES_DIR="$SESSION_DIR/series"
STATE_DIR="$SESSION_DIR/state"
mkdir -p -- "$CELLS_DIR" "$LOGS_DIR" "$SERIES_DIR" "$STATE_DIR"

# single-instance lock
exec 9>"$SESSION_DIR/.run-write-cells.lock"
if ! flock -n 9; then
    die "another run-write-cells.sh is already running (lock: $SESSION_DIR/.run-write-cells.lock)"
fi

READ_STATE_START=$(read_file_state)

# state file headers
[[ -s "$STATE_DIR/order.tsv" ]] || printf '%s\n' $'seq\tcell\tengine\tvariant\tbs\tqd\trep\twarmup_s\tmeasured_s\trest_after_s' >"$STATE_DIR/order.tsv"
[[ -s "$STATE_DIR/runs.tsv" ]] || printf '%s\n' $'seq\tcell\tengine\tvariant\tbs\tqd\trep\twarmup_s\tmeasured_s\tstatus\texit\tjson\tlog\tseries\tiops\tmibs\tp99_us\tops\tbytes\terrors\tbuffer_mode\tdev_write_sectors_d\tdev_write_mib\tloadavg_start\tloadavg_end\tgovernor\tthermal_start\tthermal_end\tstarted_utc' >"$STATE_DIR/runs.tsv"
[[ -s "$STATE_DIR/rests.tsv" ]] || printf '%s\n' $'phase\tafter_run_seq\trequested_s\tactual_s\tstart_utc\tend_utc' >"$STATE_DIR/rests.tsv"
# bench/m1/lib.sh-compatible meta file so the M1 analyzer can consume this session too
CELL_META=$SESSION_DIR/cell-meta.tsv
[[ -s "$CELL_META" ]] || printf '%s\n' $'case\tengine\tvariant\tbs\tqd\trep\tts_utc\tloadavg_start\tloadavg_end\tread_sectors_delta\twrite_sectors_delta\tops\tiops\tp99_us\twall_s\tstatus\tbytes' >"$CELL_META"

# manifest
{
    printf '# M1 write-session manifest (methodology D1-D8)\n'
    printf '# generated=%s host=%s kernel=%s\n' "$(now_utc)" "$(cat /proc/sys/kernel/hostname 2>/dev/null || uname -n)" "$(uname -r)"
    printf 'cpu=%s\n' "$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')"
    printf 'logical_cpus=%s\n' "$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN)"
    printf 'juring_git_commit=%s\n' "$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    printf 'juring_git_dirty=%s\n' "$(if [[ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null | head -n1)" ]]; then echo true; else echo false; fi)"
    printf 'seastar_git=%s\n' "$(git -C "$REPO_ROOT/SeaStarRef" describe --tags --always 2>/dev/null || echo unknown)"
    printf 'jdk=%s\n' "$(java -version 2>&1 | head -n1 || echo unknown)"
    printf 'fio=%s\n' "$(fio --version 2>/dev/null || echo unknown)"
    printf 'python=%s\n' "$(python3 --version 2>&1 || echo unknown)"
    printf 'governor=%s\n' "$GOVERNOR"
    printf 'hugepages_total=%s\n' "$(awk '/HugePages_Total/ {print $2}' /proc/meminfo 2>/dev/null || echo unknown)"
    printf 'memlock_kib=%s\n' "$(ulimit -l)"
    printf 'uid=%s user=%s\n' "$(id -u)" "$(id -un)"
    printf 'device_stat=%s\n' "$DEVICE_STAT"
    printf 'device_stat_start=%s\n' "$(devstat)"
    printf 'write_file=%s\n' "$WF"
    printf 'write_file_stat=%s\n' "$(stat -c 'size=%s blocks=%b mtime=%y' -- "$WF" 2>/dev/null || echo unavailable)"
    printf 'write_filefrag=%s\n' "$(filefrag -- "$WF" 2>/dev/null | sed 's/.*: //' || echo unavailable)"
    printf 'write_fstype=%s\n' "$FSTYPE"
    printf 'write_source=%s\n' "$SOURCE"
    printf 'write_mount_options=%s\n' "$MOUNT_OPTS"
    printf 'read_file=%s\n' "$RF"
    printf 'read_file_stat_start=%s\n' "$READ_STATE_START"
    printf 'session_dir=%s\n' "$SESSION_DIR"
    printf 'cpu_pin=%s\n' "$CPU"
    printf 'seed=%s\n' "$SEED"
    printf 'cells=%s\n' "$(printf '%s' "$CELLS")"
    printf 'engines=%s\n' "$(printf '%s' "$ENGINES")"
    printf 'fio_variants=%s\n' "$(printf '%s' "$FIO_VARIANTS")"
    printf 'w7_sweep=%s\n' "$W7_SWEEP"
    printf 'reps=%s measured_s=%s warmup=%s rest_s=%s rest_scope=%s precond_rest_s=%s precondition=%s\n' \
        "$REPS" "$MEASURED_SEC" "${WARMUP_OVERRIDE:-per-cell}" "$REST_SEC" "$REST_SCOPE" "$PRECOND_REST_SEC" "$PRECOND_MODE"
    printf 'loadavg_at_start=%s\n' "$(cat /proc/loadavg)"
    printf 'run_plan_runs=%s\n' "$TOTAL"
} >"$SESSION_DIR/manifest.txt"

# planned order
for entry in "${PLAN[@]}"; do
    IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
    rest_for_row=0
    if (( rest_after )); then rest_for_row=$REST_SEC; fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$seq" "$cell" "$token" "$variant" "$bs_label" "$qd" "$rep" "$warmup" "$MEASURED_SEC" "$rest_for_row" \
        >>"$STATE_DIR/order.tsv"
done

SESSION_START_S=$(now_s)
note "== M1 write session =="
note "   write file: $WF"
note "   session dir: $SESSION_DIR"
note "   plan: $TOTAL measured runs, reps=$REPS, measured=${MEASURED_SEC}s, rest=${REST_SEC}s ($REST_SCOPE), precondition=$PRECOND_MODE"
note "   engines: $ENGINES (fio variants: $FIO_VARIANTS)"

SAMPLER_PID=""
cleanup() {
    if [[ -n "${SAMPLER_PID:-}" ]]; then
        kill -TERM "$SAMPLER_PID" 2>/dev/null || true
        wait "$SAMPLER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

OK=0
FAILED=0
PREV_CELL=""
PRECOND_DONE=0

for entry in "${PLAN[@]}"; do
    IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"

    if [[ "$cell" != "$PREV_CELL" ]]; then
        if [[ -n "$PREV_CELL" ]]; then
            summarize_cell "$PREV_CELL"
        fi
        need_precond=0
        case "$PRECOND_MODE" in
            per-cell) need_precond=1 ;;
            session-once) (( PRECOND_DONE == 0 )) && need_precond=1 ;;
            none) need_precond=0 ;;
        esac
        if (( need_precond )); then
            run_precondition "$cell"
            PRECOND_DONE=1
            do_rest "$PRECOND_REST_SEC" "after-preconditioning" "0"
        fi
        PREV_CELL=$cell
    fi

    LAST_STATUS=""
    run_one "$seq" "$cell" "$token" "$variant" "$kind" "$bs_label" "$bs_bytes" "$qd" "$warmup" "$rep" "$rest_after"
    # run_one returns 0 even on a failed engine run; the status is in LAST_STATUS.
    if [[ "$LAST_STATUS" == "ok" ]]; then
        OK=$(( OK + 1 ))
    else
        FAILED=$(( FAILED + 1 ))
    fi
done
if [[ -n "$PREV_CELL" ]]; then
    summarize_cell "$PREV_CELL"
fi

SESSION_END_S=$(now_s)
SESSION_ELAPSED=$(( SESSION_END_S - SESSION_START_S ))
READ_STATE_END=$(read_file_state)
if [[ "$READ_STATE_START" != "$READ_STATE_END" ]]; then
    warn "read dataset metadata changed during the write session (before: $READ_STATE_START / after: $READ_STATE_END)"
fi
{
    printf 'loadavg_at_end=%s\n' "$(cat /proc/loadavg)"
    printf 'device_stat_end=%s\n' "$(devstat)"
    printf 'read_file_stat_end=%s\n' "$READ_STATE_END"
    printf 'read_file_unchanged=%s\n' "$(if [[ "$READ_STATE_START" == "$READ_STATE_END" ]]; then echo true; else echo false; fi)"
    printf 'session_elapsed_s=%s\n' "$SESSION_ELAPSED"
    printf 'runs_ok=%s\n' "$OK"
    printf 'runs_failed=%s\n' "$FAILED"
} >>"$SESSION_DIR/manifest.txt"

printf 'SESSION_RESULT session_dir=%s runs_planned=%s runs_ok=%s runs_failed=%s elapsed_s=%s\n' \
    "$SESSION_DIR" "$TOTAL" "$OK" "$FAILED" "$SESSION_ELAPSED"

if (( FAILED > 0 )); then
    warn "$FAILED of $TOTAL runs failed; see $SESSION_DIR/state/runs.tsv and logs/"
    exit 1
fi
note "done: $OK/$TOTAL write runs ok"
exit 0
