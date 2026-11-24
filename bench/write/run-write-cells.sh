#!/usr/bin/env bash
#
# run-write-cells.sh - execute the write matrix (4 KiB random
# write at QD 1/16/64/128, then 1 MiB sequential write at QD 1/8/32).
#
# Cells (docs/benchmark-methodology.md):
#   randwrite-4k-qd1    randwrite 4K  QD1    60 s warmup / 60 s measured
#   randwrite-4k-qd16   randwrite 4K  QD16  120/60
#   randwrite-4k-qd64   randwrite 4K  QD64  120/60  (+ fio batching sweep b=1,8,16,32)
#   randwrite-4k-qd128  randwrite 4K  QD128 120/60
#   seqwrite-1m-qd1     seqwrite  1M  QD1    60/60
#   seqwrite-1m-qd8     seqwrite  1M  QD8    60/60
#   seqwrite-1m-qd32    seqwrite  1M  QD32   60/60
#
# Cell IDs are the canonical workload names from bench/analysis/cells.py; the
# old W5..W8/W12..W14 aliases are still accepted by --cells and --reps-by-cell.
# Engines: juring (bench/juring/run-juring.sh, JURING_SKIP_COMPILE=1),
#          fio (io_uring; variant default / matched registration / batching sweep),
#          seastar (bench/seastar/seastar_file_bench; write path smoke-validated on
#          this host 2026-11-23 - status ok, zero errors, in-place write, no
#          truncate/fsync - but kept explicit opt-in because the binary is built
#          for this host and the arm is a bracket, not an equal opponent).
# Dataset: ONE dedicated 10 GiB non-sparse file on the data NVMe
#          (<repo>/nvme-data/write_files/single_file_bench_w.bin, see
#          create-write-file.sh).  The read dataset is never opened for writing.
#
# Device-state controls encoded here:
#   preconditioning before every measured block: calls precondition.sh
#       (default condition=target: stop as soon as 3x file size = 32,212,254,720 B
#       of 4K randwrite QD64 is written, then fsync), records the pass number;
#       --precondition=per-cell|session-once|none.
#   the 1 s device I/O series: /sys/block/nvme1n1/stat series per measured run via
#       sample-device-iops.sh (background); --no-sampler to disable.
#   the between-run rests: 300 s idle rest between measured runs (--rest-sec), 600 s
#       after a preconditioning burst (--precond-rest-sec); recorded in
#       state/rests.tsv.  --rest-scope=reps rests only between rep groups.
#   the no-discard rule: records the mount options and warns if `discard`
#       is present; never issues blkdiscard/TRIM.
#   the thermal record: records thermal state before/after every run when readable
#       (nvme smart-log needs root; hwmon is used when available) and marks it
#       unavailable otherwise - the host-root step is in bench/write/README.md.
#   the rotated engine order: monotonic cell order (4 KiB random write QD 1 -> 128, then 1 MiB
#       sequential write QD 1 -> 32); engine order within a rep is
#       rotated by rep (A,B / B,A / A,B) and the exact order is written to
#       state/order.tsv.  Preconditioning stays monotonic.
#   the variance rule: emits per-run RESULT lines and per-cell CELL_SUMMARY lines
#       with n, mean, sd, CV%; CV>10% is flagged unstable (no ranking).
#   the background-load gate: loadavg(1m) > --loadavg-max aborts (or warns) before a run.
#
# Multi-invocation support: --resume lets a later invocation re-use a session
# dir (run-granular skip of cells/<stem>.json that parses with iops>0; a cell
# with any missing run is preconditioned once more, a fully present cell is
# skipped with its preconditioning).  Sequence numbers stay monotonic across
# invocations (offset from the max seq in state/order.tsv + state/runs.tsv);
# manifest.txt is never truncated (later invocations write manifest-run<N>.txt);
# every invocation appends one row to state/invocations.tsv.  p99 units are
# microseconds for every engine in RESULT / runs.tsv / cell-meta.tsv (fio
# clat_ns is divided by 1000; manifest line cell_meta_p99_units=us).
#
# Outputs (all below <session-dir>, default bench/results/write-2026-11-13):
#   cells/<case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>.json
#   logs/<same stem>.log
#   series/<same stem>.tsv                  (1 s device I/O series)
#   state/order.tsv, runs.tsv, rests.tsv, cell-summaries.txt,
#   state/resumed.tsv, state/invocations.tsv,
#   state/write-passes.tsv, state/precondition-results.txt
#   manifest.txt (first invocation) / manifest-run<N>.txt (later invocations)
#
# Usage:
#   bench/write/run-write-cells.sh --dry-run            # print the whole plan
#   bench/write/run-write-cells.sh --cells randwrite-4k-qd1 --reps 1  # smoke subset
#   bench/write/run-write-cells.sh --resume             # continue a session
#   bench/write/run-write-cells.sh                      # full protocol (R=3)
#   bench/write/run-write-campaign.sh                   # the 3-block write-matrix campaign
#
# Exit status: 0 all executed runs ok, 1 at least one failure, 2 usage error.
#
set -euo pipefail

ORIG_ARGV=("$@")   # preserved verbatim for state/invocations.tsv

PROG=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)
# shellcheck source=bench/cells.sh
source "$REPO_ROOT/bench/cells.sh"

READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
WRITE_FILE=${WRITE_FILE:-$REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin}
SESSION_DIR=${SESSION_DIR:-$REPO_ROOT/bench/results/write-2026-11-13}
DEVICE_STAT=${DEVICE_STAT:-/sys/block/nvme1n1/stat}

CELLS_RAW="randwrite-4k-qd1 randwrite-4k-qd16 randwrite-4k-qd64 randwrite-4k-qd128 seqwrite-1m-qd1 seqwrite-1m-qd8 seqwrite-1m-qd32"
ENGINES_RAW="juring,fio"
FIO_VARIANTS_RAW="default,matched"
BATCHING_SWEEP=1
CPU=2
SEED=20261113
MEASURED_SEC=60
REPS=3
REPS_BY_CELL_RAW=""
RESUME=0
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
ALLOW_ANY_PATH=0
DRY_RUN=0

usage() {
    cat <<'EOF'
run-write-cells.sh - execute the write matrix (4 KiB random write QD 1-128,
1 MiB sequential write QD 1-32)

Usage:
  bench/write/run-write-cells.sh [options]

Selection:
  --cells LIST           comma/space list of cell slugs, e.g.
                         randwrite-4k-qd64,randwrite-4k-qd128 (default: all seven
                         write cells). Legacy W5/W6/W7/W8/W12/W13/W14 accepted.
  --engines LIST         subset of juring,fio,seastar (default juring,fio)
  --fio-variants LIST    subset of default,matched,regfile,matchedbatch,batch1,
                         batch8,batch16,batch32,sqpoll,libaio (default default,matched;
                         the batching sweep batch1/batch8/batch16/batch32 is added
                         for randwrite-4k-qd64 unless --no-batching-sweep, and
                         matchedbatch is added for randwrite-4k-qd64 and
                         randwrite-4k-qd128 as the write-gate comparator)
  --no-batching-sweep    do not add the fio batching sweep on randwrite-4k-qd64
                         or the extra matchedbatch comparator (legacy alias:
                         --no-w7-sweep, still accepted)
  --allow-seastar-write  enable the seastar write path (smoke-validated, opt-in)

Protocol:
  --reps N               default repetitions per cell (default 3, the variance rule)
  --reps-by-cell LIST    per-cell repetition override, e.g.
                         randwrite-4k-qd64=5,randwrite-4k-qd128=5 (legacy W7=5 also
                         accepted); a cell not listed falls back to --reps
  --resume               run-granular resume into an existing --session-dir:
                         a planned run whose cells/<stem>.json exists and parses
                         with iops>0 is skipped (logged to state/resumed.tsv); a
                         cell with any missing run is preconditioned once more,
                         a fully present cell skips cell+preconditioning
  --measured-sec S       measured window (default 60)
  --warmup-sec S         warmup override for every cell; default per cell
                         from the methodology table (4 KiB random write QD 1 and
                         all 1 MiB sequential-write cells = 60 s; 4 KiB random
                         write QD 16/64/128 = 120 s)
  --rest-sec S           idle rest between measured runs (between-run rest, default 300)
  --rest-scope runs|reps rest after every run (default) or only between rep
                         groups (identical engine order within a rep, no rest
                         between engines - cheaper, documented deviation)
  --precond-rest-sec S   idle rest after a preconditioning burst (between-run rest, default 600)
  --precondition MODE    per-cell (precondition before every cell, default),
                         session-once (before the first cell only), or none
  --allow-capped-precondition
                         continue if precondition.sh stops capped (target not
                         reached within --min-sec/--max-sec); default: abort

Paths / safety:
  --write-file PATH      dedicated write dataset
                         (default <repo>/nvme-data/write_files/single_file_bench_w.bin)
  --allow-any-path       allow a --write-file outside <repo>/nvme-data, e.g. a
                         second dedicated NVMe bind-mounted under the repo; the
                         read dataset is still refused. Pair with --device-stat
  --expect-size SIZE     expected file size (default 10g); refuse otherwise
  --session-dir DIR      result root
                         (default <repo>/bench/results/write-2026-11-13)
  --device-stat PATH     sysfs stat file (default /sys/block/nvme1n1/stat)
  --cpu N                taskset CPU (default 2)
  --seed N               PRNG seed (default 20261113)
  --loadavg-max F        background-load gate threshold for loadavg(1m) (default 0.5)
  --loadavg-action abort|warn   (default abort)
  --allow-concurrent     do not abort when another fio/seastar/JUring bench
                         process is already running
  --sampler              enable the 1 s device I/O series (default; accepted
                         explicitly so wrappers can state the whole protocol)
  --no-sampler           disable the 1 s device I/O series
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
        --no-batching-sweep)        BATCHING_SWEEP=0; shift ;;
        --no-w7-sweep)              BATCHING_SWEEP=0; shift ;;  # legacy alias
        --allow-seastar-write)      ALLOW_SEASTAR_WRITE=1; shift ;;
        --reps)                     [[ $# -ge 2 ]] || die "--reps needs a value"; REPS=$2; shift 2 ;;
        --reps-by-cell)             [[ $# -ge 2 ]] || die "--reps-by-cell needs a value"; REPS_BY_CELL_RAW=$2; shift 2 ;;
        --resume)                   RESUME=1; shift ;;
        --measured-sec)             [[ $# -ge 2 ]] || die "--measured-sec needs a value"; MEASURED_SEC=$2; shift 2 ;;
        --warmup-sec)               [[ $# -ge 2 ]] || die "--warmup-sec needs a value"; WARMUP_OVERRIDE=$2; shift 2 ;;
        --rest-sec)                 [[ $# -ge 2 ]] || die "--rest-sec needs a value"; REST_SEC=$2; shift 2 ;;
        --rest-scope)               [[ $# -ge 2 ]] || die "--rest-scope needs a value"; REST_SCOPE=$2; shift 2 ;;
        --precond-rest-sec)         [[ $# -ge 2 ]] || die "--precond-rest-sec needs a value"; PRECOND_REST_SEC=$2; shift 2 ;;
        --precondition)             [[ $# -ge 2 ]] || die "--precondition needs a value"; PRECOND_MODE=$2; shift 2 ;;
        --allow-capped-precondition) ALLOW_CAPPED_PRECOND=1; shift ;;
        --allow-any-path)           ALLOW_ANY_PATH=1; shift ;;
        --write-file)               [[ $# -ge 2 ]] || die "--write-file needs a value"; WRITE_FILE=$2; shift 2 ;;
        --expect-size)              [[ $# -ge 2 ]] || die "--expect-size needs a value"; EXPECT_SIZE_SPEC=$2; shift 2 ;;
        --session-dir)              [[ $# -ge 2 ]] || die "--session-dir needs a value"; SESSION_DIR=$2; shift 2 ;;
        --device-stat)              [[ $# -ge 2 ]] || die "--device-stat needs a value"; DEVICE_STAT=$2; shift 2 ;;
        --cpu)                      [[ $# -ge 2 ]] || die "--cpu needs a value"; CPU=$2; shift 2 ;;
        --seed)                     [[ $# -ge 2 ]] || die "--seed needs a value"; SEED=$2; shift 2 ;;
        --loadavg-max)              [[ $# -ge 2 ]] || die "--loadavg-max needs a value"; LOADAVG_MAX=$2; shift 2 ;;
        --loadavg-action)           [[ $# -ge 2 ]] || die "--loadavg-action needs a value"; LOADAVG_ACTION=$2; shift 2 ;;
        --allow-concurrent)         ALLOW_CONCURRENT=1; shift ;;
        --sampler)                  SAMPLER=1; shift ;;
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

# NVMe controller for the thermal probe, derived from --device-stat
# (/sys/block/nvme2n1/stat -> nvme2n1 -> nvme2).
DEVNAME=$(basename "$(dirname "$DEVICE_STAT")" 2>/dev/null || echo nvme1n1)
DEVCTRL=${DEVNAME%n[0-9]*}
[[ -n "$DEVCTRL" ]] || DEVCTRL=nvme1

canon() { realpath -m -- "$1"; }
WF=$(canon "$WRITE_FILE")
RF=$(canon "$READ_FILE")
ND=$(canon "$REPO_ROOT/nvme-data")
[[ "$WF" == "$RF" ]] && die "refusing: write file resolves to the read dataset ($RF)"
case "$WF" in
    /mnt/TestData/read_files/*) die "refusing: write file is below the read dataset ($WF)" ;;
esac
if (( ! ALLOW_ANY_PATH )) && [[ "$WF" != "$ND"/* ]]; then
    die "refusing: write file must be below $ND (got $WF); use --write-file with a path on the writable NVMe mount, or --allow-any-path for a second dedicated device"
fi

CELLS=$(printf '%s' "$CELLS_RAW" | tr ',' ' ')
ENGINES=$(printf '%s' "$ENGINES_RAW" | tr ',' ' ')
FIO_VARIANTS=$(printf '%s' "$FIO_VARIANTS_RAW" | tr ',' ' ')

# ------------------------------------------------------------------ cell table
# cell_meta CASE -> "<workload> <bs_label> <bs_bytes> <qd> <default_warmup_sec>"
cell_meta() {
    case "$1" in
        randwrite-4k-qd1)  echo "randwrite 4k 4096 1 60" ;;
        randwrite-4k-qd16)  echo "randwrite 4k 4096 16 120" ;;
        randwrite-4k-qd64)  echo "randwrite 4k 4096 64 120" ;;
        randwrite-4k-qd128)  echo "randwrite 4k 4096 128 120" ;;
        seqwrite-1m-qd1) echo "seqwrite 1m 1048576 1 60" ;;
        seqwrite-1m-qd8) echo "seqwrite 1m 1048576 8 60" ;;
        seqwrite-1m-qd32) echo "seqwrite 1m 1048576 32 60" ;;
        *) return 1 ;;
    esac
}

# Canonicalize every requested cell: a legacy W# alias is accepted, the plan and
# every output file use the canonical slug from bench/analysis/cells.py.
CELL_CHOICES="randwrite-4k-qd1 randwrite-4k-qd16 randwrite-4k-qd64 randwrite-4k-qd128 seqwrite-1m-qd1 seqwrite-1m-qd8 seqwrite-1m-qd32"
CELLS_CANON=""
for cell in $CELLS; do
    canonical=$(cell_slug "$cell")
    cell_meta "$canonical" >/dev/null || die "unknown --cells entry '$cell' (canonical slugs: $CELL_CHOICES)"
    CELLS_CANON="${CELLS_CANON:+$CELLS_CANON }$canonical"
done
CELLS=$CELLS_CANON
for eng in $ENGINES; do
    case "$eng" in juring|fio|seastar) : ;; *) die "unknown --engines entry '$eng' (juring|fio|seastar)" ;; esac
done
if [[ " $ENGINES " == *" seastar "* ]] && (( ! ALLOW_SEASTAR_WRITE )); then
    die "seastar write path is opt-in: pass --allow-seastar-write explicitly"
fi
for v in $FIO_VARIANTS; do
    case "$v" in default|matched|regfile|matchedbatch|batch1|batch8|batch16|batch32|sqpoll|libaio) : ;; *) die "unknown --fio-variants entry '$v'" ;; esac
done

# --reps-by-cell randwrite-4k-qd64=5,randwrite-4k-qd128=5 (legacy W7=5 accepted;
# a cell not listed falls back to --reps)
declare -A CELL_REPS=()
if [[ -n "$REPS_BY_CELL_RAW" ]]; then
    for item in $(printf '%s' "$REPS_BY_CELL_RAW" | tr ',' ' '); do
        if [[ "$item" != *=* ]]; then
            die "--reps-by-cell entries must look like randwrite-4k-qd64=5 (got '$item')"
        fi
        rc_cell=$(cell_slug "${item%%=*}")
        rc_reps=${item#*=}
        cell_meta "$rc_cell" >/dev/null || die "--reps-by-cell: unknown cell '${item%%=*}' (canonical slugs: $CELL_CHOICES)"
        [[ "$rc_reps" =~ ^[1-9][0-9]*$ ]] || die "--reps-by-cell: '$rc_cell' needs a positive integer reps value (got '$rc_reps')"
        [[ -z "${CELL_REPS[$rc_cell]:-}" ]] || die "--reps-by-cell: cell '$rc_cell' given more than once"
        CELL_REPS[$rc_cell]=$rc_reps
    done
fi
cell_reps() { # $1 = cell -> effective repetitions
    printf '%s' "${CELL_REPS[$1]:-$REPS}"
}

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
                if (( BATCHING_SWEEP )); then
                    if [[ "$cell" == "randwrite-4k-qd64" ]]; then
                        for extra in batch1 batch8 batch16 batch32 matchedbatch; do
                            case " $variants " in *" $extra "*) : ;; *) variants="$variants $extra" ;; esac
                        done
                    elif [[ "$cell" == "randwrite-4k-qd128" ]]; then
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
    nreps=$(cell_reps "$cell")
    build_specs "$cell"
    nspec=${#SPEC_TOKEN[@]}
    for rep in $(seq 1 "$nreps"); do
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
    for f in /sys/class/nvme/$DEVCTRL/device/hwmon/hwmon*/temp1_input \
             /sys/class/nvme/$DEVCTRL/hwmon*/temp1_input \
             /sys/block/$DEVNAME/device/hwmon/hwmon*/temp1_input; do
        if [[ -r "$f" ]]; then
            t=$(cat "$f" 2>/dev/null || true)
            if [[ -n "$t" ]]; then
                printf 'hwmon_mc=%s' "$t"
                return 0
            fi
        fi
    done
    if command -v nvme >/dev/null 2>&1; then
        t=$(timeout 2 nvme smart-log "/dev/$DEVNAME" 2>/dev/null | awk -F: '/temperature/ {gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit}' || true)
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
    try:
        # fio reports clat percentiles in NANOSECONDS (clat_ns); every
        # cell-meta.tsv / runs.tsv / RESULT p99_us must be MICROSECONDS.
        p99_us = round(float(p99) / 1000.0, 3)
    except Exception:
        p99_us = "NA"
    emit(num(wr.get("iops")),
         num(float(wr.get("bw_bytes", 0) or 0) / 1048576.0),
         p99_us,
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
                regfile)      CMD+=(--registerfiles=1) ;;  # 1 MiB cells: fixedbufs > RLIMIT_MEMLOCK, so use a registered file only
                matchedbatch) CMD+=(--registerfiles=1 --fixedbufs=1 --iodepth_batch_submit="$qd") ;;
                batch1)  CMD+=(--iodepth_batch_submit=1) ;;
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

# ------------------------------------------------------------------ resume helpers
json_run_ok() { # $1 = cell JSON -> rc 0 iff the JSON parses and reports iops > 0
    [[ -f "$1" ]] || return 1
    python3 - "$1" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], "r", encoding="utf-8") as fh:
        doc = json.load(fh)
except Exception:
    sys.exit(1)
iops = 0.0
try:
    jobs = doc.get("jobs")
    if isinstance(jobs, list) and jobs:
        wr = jobs[0].get("write") or {}
        iops = float(wr.get("iops") or 0)
    else:
        agg = doc.get("aggregate") or {}
        iops = float((agg.get("iops") or {}).get("mean") or 0)
except Exception:
    iops = 0.0
sys.exit(0 if iops > 0 else 1)
PY
}

max_state_seq() { # $1 = state dir -> max seq in order.tsv/runs.tsv (0 when absent)
    local dir=$1 f m best=0
    for f in "$dir/order.tsv" "$dir/runs.tsv"; do
        [[ -s "$f" ]] || continue
        m=$(awk -F'\t' 'NR > 1 && $1 ~ /^[0-9]+$/ { if ($1 + 0 > m) m = $1 + 0 } END { print m + 0 }' "$f")
        [[ "$m" =~ ^[0-9]+$ ]] || m=0
        (( m > best )) && best=$m
    done
    printf '%s' "$best"
}

declare -A RESUME_SKIP=()        # stem -> 1 when an existing valid JSON covers it
declare -A RESUME_CELL_SKIP=()   # cell -> number of planned runs already present
declare -A RESUME_CELL_MISSING=() # cell -> number of planned runs missing
RESUME_EXISTING=0
resume_scan() { # $1 = cells dir; fills the RESUME_* maps from the current PLAN
    local cells_dir=$1 entry stem
    local seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after
    RESUME_EXISTING=0
    for entry in "${PLAN[@]}"; do
        IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
        stem="${cell}__${token}__${variant}__bs${bs_label}__qd${qd}__t1__r${rep}"
        if json_run_ok "$cells_dir/$stem.json"; then
            RESUME_SKIP[$stem]=1
            RESUME_CELL_SKIP[$cell]=$(( ${RESUME_CELL_SKIP[$cell]:-0} + 1 ))
            RESUME_EXISTING=$(( RESUME_EXISTING + 1 ))
        else
            RESUME_CELL_MISSING[$cell]=$(( ${RESUME_CELL_MISSING[$cell]:-0} + 1 ))
        fi
    done
}

offset_plan() { # $1 = seq base; shift every PLAN seq so invocations stay monotonic
    local base=$1 entry seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after
    local -a out=()
    for entry in "${PLAN[@]}"; do
        IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
        out+=("$(( seq + base ))|$cell|$token|$variant|$kind|$bs_label|$bs_bytes|$qd|$warmup|$rep|$nspec|$pos|$rest_after")
    done
    PLAN=("${out[@]}")
}

do_rest() { # seconds phase after_ref
    local secs=$1 phase=$2 after=$3
    (( secs > 0 )) || return 0
    local t0 t1 actual
    note "== [between-run rest] rest ${secs}s ($phase) =="
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
    note "== [preconditioning] before cell $cell (3x file size 4K randwrite QD64 + fsync) =="
    local -a pcmd=("$SCRIPT_DIR/precondition.sh"
        --file "$WF" --session-dir "$SESSION_DIR" --device-stat "$DEVICE_STAT"
        --cpu "$CPU" --bs 4k --qd 64)
    if (( ALLOW_ANY_PATH )); then pcmd+=(--allow-any-path); fi
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
            # ns -> us for ALL engines (fio clat is nanoseconds)
            p99 = float(pcts.get("99.000000", 0)) / 1000.0
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

    # ---- background-load gate
    local load1 top_proc gate=ok
    load1=$(awk '{print $1}' /proc/loadavg)
    top_proc=$(ps -eo pcpu=,comm= --sort=-pcpu 2>/dev/null | head -n1 | awk '{print $1"/"$2}' || true)
    [[ -n "$top_proc" ]] || top_proc="NA"
    if awk -v a="$load1" -v b="$LOADAVG_MAX" 'BEGIN{exit !(a+0 > b+0)}'; then
        gate="over($load1>$LOADAVG_MAX)"
        if [[ "$LOADAVG_ACTION" == "abort" ]]; then
            die "loadavg(1m)=$load1 > $LOADAVG_MAX before $stem (background-load gate); aborting. Wait for an idle machine or pass --loadavg-action warn"
        fi
        warn "loadavg(1m)=$load1 > $LOADAVG_MAX before $stem (background-load gate); continuing (--loadavg-action warn)"
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
        printf 'warmup_s=%s measured_s=%s reps=%s seed=%s cpu=%s\n' "$warmup" "$MEASURED_SEC" "$(cell_reps "$cell")" "$SEED" "$CPU"
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
            warn "device wrote ${dev_bytes} B but the engine reports ${bytes} B (10% device-write gate)"
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

    # bench/read-write/lib.sh-compatible row (same 17 columns as the read session)
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
    STATE_DIR_D="$SESSION_DIR/state"
    printf '# dry-run: run-write-cells.sh - NOTHING is executed (no I/O, no dirs, no sleeps)\n'
    printf '#   write_file=%s\n' "$WF"
    printf '#   read_file=%s (never touched)\n' "$RF"
    printf '#   session_dir=%s\n' "$SESSION_DIR"
    printf '#   cells=%s engines=%s fio_variants=%s batching_sweep=%s\n' \
        "$CELLS" "$ENGINES" "$FIO_VARIANTS" "$BATCHING_SWEEP"
    printf '#   protocol: reps_default=%s reps_by_cell=%s measured=%ss warmup=%s rest=%ss rest_scope=%s precond_rest=%ss precondition=%s resume=%s\n' \
        "$REPS" "${REPS_BY_CELL_RAW:-none}" "$MEASURED_SEC" "${WARMUP_OVERRIDE:-per-cell}" "$REST_SEC" "$REST_SCOPE" "$PRECOND_REST_SEC" "$PRECOND_MODE" "$RESUME"
    printf '#   cpu=%s seed=%s expect_size_bytes=%s sampler=%s\n' \
        "$CPU" "$SEED" "$EXPECT_BYTES" "$SAMPLER"
    if [[ ! -e "$WF" ]]; then
        printf '#   WARNING: write file does not exist yet; run bench/write/create-write-file.sh first\n'
    else
        read -r cur_size cur_blocks < <(stat -c '%s %b' -- "$WF")
        printf '#   write file exists: size=%s allocated=%s (expected %s)\n' "$cur_size" "$(( cur_blocks * 512 ))" "$EXPECT_BYTES"
    fi

    printf '#\n# per-cell plan (effective reps after --reps-by-cell; order preserved):\n'
    for cell in $CELLS; do
        read -r wl0 bs_label0 _ qd0 _ <<<"$(cell_meta "$cell")"
        build_specs "$cell"
        nspec0=${#SPEC_TOKEN[@]}
        nreps0=$(cell_reps "$cell")
        nc=0
        for entry in "${PLAN[@]}"; do
            IFS='|' read -r _ c0 _ _ _ _ _ _ _ _ _ _ _ <<<"$entry"
            [[ "$c0" == "$cell" ]] && nc=$(( nc + 1 ))
        done
        printf '#   cell=%s workload=%s bs=%s qd=%s reps=%s engine_specs=%s planned_runs=%s\n' \
            "$cell" "$wl0" "$bs_label0" "$qd0" "$nreps0" "$nspec0" "$nc"
    done

    if (( RESUME )); then
        resume_scan "$SESSION_DIR/cells"
        printf '#\n# --resume scan of %s/cells: %s of %s planned runs already present (parsed with iops>0)\n' \
            "$SESSION_DIR" "$RESUME_EXISTING" "$TOTAL"
        for cell in $CELLS; do
            rs=${RESUME_CELL_SKIP[$cell]:-0}
            rm=${RESUME_CELL_MISSING[$cell]:-0}
            if (( rm == 0 )); then
                printf '#   cell=%s fully present (%s/%s) -> skip cell + preconditioning\n' "$cell" "$rs" "$(( rs + rm ))"
            elif (( rs > 0 )); then
                printf '#   cell=%s partial (%s/%s present) -> re-precondition once, then run %s missing\n' \
                    "$cell" "$rs" "$(( rs + rm ))" "$rm"
            else
                printf '#   cell=%s nothing present -> precondition + all %s runs\n' "$cell" "$rm"
            fi
        done
    fi

    seq_base=$(max_state_seq "$STATE_DIR_D")
    if (( seq_base > 0 )); then
        printf '#\n# session state present: seq offset %s (next seq=%s); order.tsv/runs.tsv are appended and manifest.txt is kept, a later invocation writes manifest-run<N>.txt\n' \
            "$seq_base" "$(( seq_base + 1 ))"
        offset_plan "$seq_base"
    fi
    # read-only decision: which manifest file would this invocation write?
    dry_invocation=1
    dry_manifest="manifest.txt"
    if [[ -e "$SESSION_DIR/manifest.txt" ]]; then
        dry_invocation=2
        while [[ -e "$SESSION_DIR/manifest-run${dry_invocation}.txt" ]]; do
            dry_invocation=$(( dry_invocation + 1 ))
        done
        dry_manifest="manifest-run${dry_invocation}.txt"
    fi
    printf '# manifest: invocation %s would write %s (manifest.txt is never truncated)\n' \
        "$dry_invocation" "$dry_manifest"

    printf '#\n# planned runs: %s\n#\n' "$TOTAL"
    prev_cell=""
    pd_seen=0
    for entry in "${PLAN[@]}"; do
        IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
        stem="${cell}__${token}__${variant}__bs${bs_label}__qd${qd}__t1__r${rep}"
        if [[ "$cell" != "$prev_cell" ]]; then
            need_precond=0
            case "$PRECOND_MODE" in
                per-cell) need_precond=1 ;;
                session-once) (( pd_seen == 0 )) && need_precond=1 ;;
            esac
            if (( RESUME )) && (( ${RESUME_CELL_MISSING[$cell]:-0} == 0 )); then
                printf '# precondition before cell %s: SKIP (cell fully present under --resume)\n' "$cell"
                need_precond=0
            elif (( need_precond )); then
                printf '# precondition before cell %s: %s/precondition.sh --file %s --session-dir %s --device-stat %s --cpu %s --bs 4k --qd 64 (condition=target%s)\n' \
                    "$cell" "$SCRIPT_DIR" "$WF" "$SESSION_DIR" "$DEVICE_STAT" "$CPU"
                printf '# rest %ss after preconditioning (between-run rest)\n' "$PRECOND_REST_SEC"
                pd_seen=1
            fi
            prev_cell=$cell
        fi
        local_json="$SESSION_DIR/cells/${stem}.json"
        read -r wl0 _ _ _ _ <<<"$(cell_meta "$cell")"
        build_engine_cmd "$kind" "$variant" "$wl0" "$bs_label" "$qd" "$warmup" "$stem" "$local_json"
        printf '# seq=%s cell=%s rep=%s warmup=%ss measured=%ss\n' "$seq" "$cell" "$rep" "$warmup" "$MEASURED_SEC"
        printf '#   cmd: %s\n' "$(fmt_cmd)"
        printf '#   json: %s\n' "$local_json"
        if (( RESUME )) && [[ -n "${RESUME_SKIP[$stem]:-}" ]]; then
            printf '#   resume: SKIP (existing valid JSON with iops>0)\n'
        fi
        if (( SAMPLER )); then
            printf '#   sampler: %s/sample-device-iops.sh --out %s/series/%s.tsv --device-stat %s --interval 1 --label %s\n' \
                "$SCRIPT_DIR" "$SESSION_DIR" "$stem" "$DEVICE_STAT" "$stem"
        fi
        if (( rest_after )); then
            printf '#   rest: %ss after this run (between-run rest)\n' "$REST_SEC"
        fi
    done

    # wall-time estimate: run = warmup+measured+~3 s startup; 90 s precondition +
    # precond rest per burst (the write-matrix target-driven burst, not the old 120 s AND).
    PRECOND_COST=$(( 90 + PRECOND_REST_SEC ))
    est=0
    est_exec=0
    for entry in "${PLAN[@]}"; do
        IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
        stem="${cell}__${token}__${variant}__bs${bs_label}__qd${qd}__t1__r${rep}"
        cost=$(( warmup + MEASURED_SEC + 3 ))
        est=$(( est + cost ))
        if (( rest_after )); then est=$(( est + REST_SEC )); fi
        if (( RESUME )) && [[ -n "${RESUME_SKIP[$stem]:-}" ]]; then
            :
        else
            est_exec=$(( est_exec + cost ))
            if (( rest_after )); then est_exec=$(( est_exec + REST_SEC )); fi
        fi
    done
    # simulate the real per-cell precondition decisions
    precond_all=0
    precond_exec=0
    case "$PRECOND_MODE" in
        per-cell)
            for cell in $CELLS; do
                precond_all=$(( precond_all + 1 ))
                if (( RESUME )) && (( ${RESUME_CELL_MISSING[$cell]:-0} == 0 )); then :; else precond_exec=$(( precond_exec + 1 )); fi
            done ;;
        session-once)
            precond_all=1
            if (( RESUME )) && (( RESUME_EXISTING == TOTAL )); then :; else precond_exec=1; fi ;;
        none) : ;;
    esac
    est=$(( est + precond_all * PRECOND_COST ))
    est_exec=$(( est_exec + precond_exec * PRECOND_COST ))
    printf '#\n# estimated wall time (full plan): ~%ss (~%s min ~%s h); %s precondition burst(s) x (%ss + %ss rest)\n' \
        "$est" "$(( est / 60 ))" "$(( est / 3600 ))" "$precond_all" 90 "$PRECOND_REST_SEC"
    if (( RESUME )); then
        printf '# estimated wall time under --resume (skipping %s present run(s) and their rests): ~%ss (~%s min ~%s h)\n' \
            "$RESUME_EXISTING" "$est_exec" "$(( est_exec / 60 ))" "$(( est_exec / 3600 ))"
    fi
    printf 'SESSION_PLAN cells=%s engines=%s runs=%s est_sec=%s session_dir=%s reps_default=%s reps_by_cell=%s precondition=%s resume=%s runs_existing=%s est_resume_sec=%s status=dry-run\n' \
        "$(printf '%s' "$CELLS")" "$(printf '%s' "$ENGINES")" "$TOTAL" "$est" "$SESSION_DIR" \
        "$REPS" "${REPS_BY_CELL_RAW:-none}" "$PRECOND_MODE" "$RESUME" "$( (( RESUME )) && echo "$RESUME_EXISTING" || echo 0 )" "$est_exec"
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
    warn "CPU governor is '$GOVERNOR', not 'performance' (the performance-governor rule); results will not be comparable"
fi
WRITE_DIR=$(dirname -- "$WF")
while [[ ! -e "$WRITE_DIR" ]]; do WRITE_DIR=$(dirname -- "$WRITE_DIR"); done
FSTYPE=$(findmnt -no FSTYPE -T "$WRITE_DIR" 2>/dev/null | tail -n1 || true)
SOURCE=$(findmnt -no SOURCE -T "$WRITE_DIR" 2>/dev/null | tail -n1 || true)
MOUNT_OPTS=$(findmnt -no OPTIONS -T "$WRITE_DIR" 2>/dev/null | tail -n1 || true)
if [[ "$MOUNT_OPTS" == *discard* ]]; then
    warn "filesystem mount options contain 'discard' ($MOUNT_OPTS); the no-discard rule says do not compare with discard active"
fi
if [[ "$FSTYPE" != "ext4" && "$FSTYPE" != "xfs" ]]; then
    warn "write filesystem is '$FSTYPE', not ext4/xfs (record it; the host/filesystem checks)"
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

# ------------------------------------------------------------------ multi-invocation state
# Seq numbers must stay monotonic when this script is invoked again into an
# existing session dir (campaign blocks and --resume).  Offset the whole plan
# by the max seq already present in order.tsv/runs.tsv.
SEQ_BASE=$(max_state_seq "$STATE_DIR")
if (( SEQ_BASE > 0 )); then
    note "== resuming into existing session state: seq offset $SEQ_BASE (next seq $(( SEQ_BASE + 1 ))) =="
    offset_plan "$SEQ_BASE"
fi
if (( RESUME )); then
    resume_scan "$CELLS_DIR"
    note "== --resume: $RESUME_EXISTING of $TOTAL planned runs already present (iops>0) =="
fi

# state file headers
[[ -s "$STATE_DIR/order.tsv" ]] || printf '%s\n' $'seq\tcell\tengine\tvariant\tbs\tqd\trep\twarmup_s\tmeasured_s\trest_after_s' >"$STATE_DIR/order.tsv"
[[ -s "$STATE_DIR/runs.tsv" ]] || printf '%s\n' $'seq\tcell\tengine\tvariant\tbs\tqd\trep\twarmup_s\tmeasured_s\tstatus\texit\tjson\tlog\tseries\tiops\tmibs\tp99_us\tops\tbytes\terrors\tbuffer_mode\tdev_write_sectors_d\tdev_write_mib\tloadavg_start\tloadavg_end\tgovernor\tthermal_start\tthermal_end\tstarted_utc' >"$STATE_DIR/runs.tsv"
[[ -s "$STATE_DIR/rests.tsv" ]] || printf '%s\n' $'phase\tafter_run_seq\trequested_s\tactual_s\tstart_utc\tend_utc' >"$STATE_DIR/rests.tsv"
[[ -s "$STATE_DIR/resumed.tsv" ]] || printf '%s\n' $'utc\tsession_dir\tcell\tstem\trep\taction' >"$STATE_DIR/resumed.tsv"
[[ -s "$STATE_DIR/invocations.tsv" ]] || printf '%s\n' $'utc\tmanifest\targv\truns_planned\truns_executed\truns_skipped\truns_ok\truns_failed\tsession_elapsed_s' >"$STATE_DIR/invocations.tsv"
# bench/read-write/lib.sh-compatible meta file so the read/write analyzer can consume this session too
CELL_META=$SESSION_DIR/cell-meta.tsv
[[ -s "$CELL_META" ]] || printf '%s\n' $'case\tengine\tvariant\tbs\tqd\trep\tts_utc\tloadavg_start\tloadavg_end\tread_sectors_delta\twrite_sectors_delta\tops\tiops\tp99_us\twall_s\tstatus\tbytes' >"$CELL_META"

# manifest: the first invocation writes manifest.txt; later invocations must not
# truncate it, they write manifest-run<N>.txt instead.
MANIFEST_FILE="$SESSION_DIR/manifest.txt"
INVOCATION=1
if [[ -e "$MANIFEST_FILE" ]]; then
    INVOCATION=2
    while [[ -e "$SESSION_DIR/manifest-run${INVOCATION}.txt" ]]; do
        INVOCATION=$(( INVOCATION + 1 ))
    done
    MANIFEST_FILE="$SESSION_DIR/manifest-run${INVOCATION}.txt"
fi

# manifest
{
    printf '# write-session manifest (device-state controls)\n'
    printf '# generated=%s host=%s kernel=%s\n' "$(now_utc)" "$(cat /proc/sys/kernel/hostname 2>/dev/null || uname -n)" "$(uname -r)"
    printf 'invocation=%s manifest=%s\n' "$INVOCATION" "$(basename -- "$MANIFEST_FILE")"
    printf 'argv=%s\n' "$(printf '%q ' "${ORIG_ARGV[@]}")"
    printf 'resume=%s seq_base=%s\n' "$RESUME" "$SEQ_BASE"
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
    printf 'batching_sweep=%s\n' "$BATCHING_SWEEP"
    printf 'reps_default=%s reps_by_cell=%s\n' "$REPS" "${REPS_BY_CELL_RAW:-none}"
    printf 'cell_reps=%s\n' "$(for c in $CELLS; do printf '%s=%s ' "$c" "$(cell_reps "$c")"; done)"
    printf 'measured_s=%s warmup=%s rest_s=%s rest_scope=%s precond_rest_s=%s precondition=%s\n' \
        "$MEASURED_SEC" "${WARMUP_OVERRIDE:-per-cell}" "$REST_SEC" "$REST_SCOPE" "$PRECOND_REST_SEC" "$PRECOND_MODE"
    printf 'cell_meta_p99_units=us\n'
    printf 'loadavg_at_start=%s\n' "$(cat /proc/loadavg)"
    printf 'run_plan_runs=%s\n' "$TOTAL"
} >"$MANIFEST_FILE"

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
note "== write session =="
note "   write file: $WF"
note "   session dir: $SESSION_DIR"
note "   manifest: $MANIFEST_FILE (invocation $INVOCATION)"
note "   plan: $TOTAL measured runs, reps_default=$REPS reps_by_cell=${REPS_BY_CELL_RAW:-none}, measured=${MEASURED_SEC}s, rest=${REST_SEC}s ($REST_SCOPE), precondition=$PRECOND_MODE, resume=$RESUME"
if (( SEQ_BASE > 0 )); then
    note "   seq offset: $SEQ_BASE (appending to existing state)"
fi
if (( RESUME )); then
    note "   resume: $RESUME_EXISTING/$TOTAL planned runs already present"
fi
note "   engines: $ENGINES (fio variants: $FIO_VARIANTS)"

SAMPLER_PID=""
cleanup() {
    if [[ -n "${SAMPLER_PID:-}" ]]; then
        kill -TERM "$SAMPLER_PID" 2>/dev/null || true
        wait "$SAMPLER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT INT TERM

log_resume() { # action cell stem rep
    local action=$1 cell=$2 stem=$3 rep=$4
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$(now_utc)" "$SESSION_DIR" "$cell" "$stem" "$rep" "$action" >>"$STATE_DIR/resumed.tsv"
}

OK=0
FAILED=0
EXECUTED=0
SKIPPED_RUNS=0
SKIPPED_CELLS=0
PREV_CELL=""
PRECOND_DONE=0

for entry in "${PLAN[@]}"; do
    IFS='|' read -r seq cell token variant kind bs_label bs_bytes qd warmup rep nspec pos rest_after <<<"$entry"
    stem="${cell}__${token}__${variant}__bs${bs_label}__qd${qd}__t1__r${rep}"

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
        if (( RESUME )); then
            cell_missing=${RESUME_CELL_MISSING[$cell]:-0}
            cell_present=${RESUME_CELL_SKIP[$cell]:-0}
            if (( cell_missing == 0 )); then
                need_precond=0
                SKIPPED_CELLS=$(( SKIPPED_CELLS + 1 ))
                log_resume "skip-cell" "$cell" "-" "-"
                note "== [resume] cell $cell fully present ($cell_present/$(( cell_present + cell_missing ))); skipping cell + preconditioning =="
            elif (( cell_present > 0 )); then
                log_resume "reprecondition" "$cell" "-" "-"
                note "== [resume] cell $cell partial ($cell_present present, $cell_missing missing); re-preconditioning once =="
            fi
        fi
        if (( need_precond )); then
            run_precondition "$cell"
            PRECOND_DONE=1
            do_rest "$PRECOND_REST_SEC" "after-preconditioning" "0"
        fi
        PREV_CELL=$cell
    fi

    if (( RESUME )) && [[ -n "${RESUME_SKIP[$stem]:-}" ]]; then
        log_resume "skip-run" "$cell" "$stem" "$rep"
        SKIPPED_RUNS=$(( SKIPPED_RUNS + 1 ))
        continue
    fi

    LAST_STATUS=""
    run_one "$seq" "$cell" "$token" "$variant" "$kind" "$bs_label" "$bs_bytes" "$qd" "$warmup" "$rep" "$rest_after"
    EXECUTED=$(( EXECUTED + 1 ))
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
    printf 'runs_planned=%s\n' "$TOTAL"
    printf 'runs_executed=%s\n' "$EXECUTED"
    printf 'runs_skipped=%s\n' "$SKIPPED_RUNS"
    printf 'cells_skipped=%s\n' "$SKIPPED_CELLS"
    printf 'runs_ok=%s\n' "$OK"
    printf 'runs_failed=%s\n' "$FAILED"
} >>"$MANIFEST_FILE"

# one row per invocation, so later invocations are auditable side by side
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(now_utc)" "$(basename -- "$MANIFEST_FILE")" "$(printf '%q ' "${ORIG_ARGV[@]}")" \
    "$TOTAL" "$EXECUTED" "$SKIPPED_RUNS" "$OK" "$FAILED" "$SESSION_ELAPSED" \
    >>"$STATE_DIR/invocations.tsv"

printf 'SESSION_RESULT session_dir=%s runs_planned=%s runs_executed=%s runs_skipped=%s cells_skipped=%s runs_ok=%s runs_failed=%s elapsed_s=%s\n' \
    "$SESSION_DIR" "$TOTAL" "$EXECUTED" "$SKIPPED_RUNS" "$SKIPPED_CELLS" "$OK" "$FAILED" "$SESSION_ELAPSED"

if (( FAILED > 0 )); then
    warn "$FAILED of $TOTAL runs failed; see $SESSION_DIR/state/runs.tsv and logs/"
    exit 1
fi
if (( EXECUTED == 0 )); then
    note "done: nothing to execute (all $TOTAL planned runs already present under --resume)"
    exit 0
fi
note "done: $OK/$EXECUTED executed write runs ok ($SKIPPED_RUNS skipped as already present)"
exit 0
