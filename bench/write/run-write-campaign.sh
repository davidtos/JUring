#!/usr/bin/env bash
#
# run-write-campaign.sh - the write-matrix campaign: three sequential blocks in
# ONE session dir (thin wrapper around run-write-cells.sh).
#
# Why a wrapper exists: the write matrix must be run as three monotonic,
# separately-preconditioned blocks (per the phasing, rests and rotated order in
# docs/benchmark-methodology.md), with a recorded 300 s idle rest between blocks,
# and each block's run count verified before any I/O starts. Doing that by hand
# invites a wrong --cells / --reps / rest flag on block 2 of an overnight run.
#
#   block 1  cells randwrite-4k-qd1,randwrite-4k-qd16         reps 3
#   block 2  cells randwrite-4k-qd64,randwrite-4k-qd128         reps-by-cell randwrite-4k-qd64=5,randwrite-4k-qd128=5 (extra reps for statistical power)
#   block 3  cells seqwrite-1m-qd1,seqwrite-1m-qd8,seqwrite-1m-qd32   reps 3
#
# Common flags for every block (exact frozen campaign protocol):
#   --engines juring,fio,seastar --allow-seastar-write
#   --rest-scope reps --rest-sec 300 --precond-rest-sec 600
#   --precondition per-cell --loadavg-max 1.6 --loadavg-action abort --sampler
#   --session-dir <one dir> --write-file/--device-stat/--cpu/--seed as given
#
# Between blocks the wrapper enforces a real 300 s idle rest and records it in
# state/rests.tsv with the same columns as the inner script:
#   phase=between-blocks, after_run_seq=<max seq of the block that just ended>,
#   requested_s=300, actual_s, start_utc, end_utc.
#
# Expected plans (verified by running each block once with --dry-run and parsing
# its SESSION_PLAN line BEFORE the real block): block 1 = 24, block 2 = 70,
# block 3 = 36 runs, 130 total. A mismatch, a failed run, or a non-zero block exit aborts the
# campaign (no silent continuation).
#
# Preflight (real runs only): scripts present/executable, write file present,
# free space in the session dir, no foreign fio/seastar/JUring/run-write-cells
# process (override --allow-concurrent), and loadavg(1m) <= --loadavg-max.
#
# Outputs below <session-dir>:
#   logs/campaign-block1.log, campaign-block2.log, campaign-block3.log
#   state/rests.tsv (extra phase=between-blocks rows)
#   everything run-write-cells.sh writes (cells/, state/, series/, manifests)
# Final stdout line:
#   CAMPAIGN_RESULT session_dir=.. blocks_ok=.. runs_planned=130 runs_ok=..
#     runs_failed=.. status=ok|failed|aborted|interrupted
#
# Usage:
#   bench/write/run-write-campaign.sh --dry-run \
#       --session-dir scratch/write-campaign-dryrun
#   bench/write/run-write-campaign.sh --session-dir bench/results/write-campaign-<date>
#   bench/write/run-write-campaign.sh --resume --session-dir <same dir>
#
# Exit status: 0 all blocks ok, 1 a block failed / mismatch / aborted,
# 2 usage error.  --dry-run performs NO I/O, no mkdir, no sleeps.
#
set -euo pipefail

PROG=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)

WRITE_FILE=${WRITE_FILE:-$REPO_ROOT/nvme-data/write_files/single_file_bench_w.bin}
DEVICE_STAT=${DEVICE_STAT:-/sys/block/nvme1n1/stat}
SESSION_DIR=${SESSION_DIR:-$REPO_ROOT/bench/results/write-campaign-$(date -u +%Y%m%d)}
CPU=2
SEED=20261113
LOADAVG_MAX=1.6
BETWEEN_REST_SEC=300
RESUME=0
DRY_RUN=0
ALLOW_CONCURRENT=0

BLOCK_COUNT=3
BLOCK_CELLS=("randwrite-4k-qd1,randwrite-4k-qd16" "randwrite-4k-qd64,randwrite-4k-qd128" "seqwrite-1m-qd1,seqwrite-1m-qd8,seqwrite-1m-qd32")
BLOCK_DESC=("randwrite-4k-qd1,randwrite-4k-qd16 reps=3" "randwrite-4k-qd64,randwrite-4k-qd128 reps-by-cell randwrite-4k-qd64=5,randwrite-4k-qd128=5" "seqwrite-1m-qd1,seqwrite-1m-qd8,seqwrite-1m-qd32 reps=3")
EXPECTED_BLOCK_RUNS=(24 70 36)
EXPECTED_TOTAL_RUNS=130

usage() {
    cat <<'EOF'
run-write-campaign.sh - write-matrix campaign (3 sequential blocks, one session)

Usage:
  bench/write/run-write-campaign.sh [options]

Blocks (fixed by the frozen campaign protocol):
  1. --cells randwrite-4k-qd1,randwrite-4k-qd16        --reps 3
  2. --cells randwrite-4k-qd64,randwrite-4k-qd128        --reps-by-cell randwrite-4k-qd64=5,randwrite-4k-qd128=5
  3. --cells seqwrite-1m-qd1,seqwrite-1m-qd8,seqwrite-1m-qd32  --reps 3
Common: --engines juring,fio,seastar --allow-seastar-write --rest-scope reps
        --rest-sec 300 --precond-rest-sec 600 --precondition per-cell
        --loadavg-max <F> --loadavg-action abort --sampler
The wrapper also enforces + records a 300 s idle rest between blocks
(phase=between-blocks in state/rests.tsv).

Options:
  --session-dir DIR      result root shared by all three blocks
                         (default <repo>/bench/results/write-campaign-<utc-date>)
  --write-file PATH      dedicated write dataset
  --device-stat PATH     sysfs stat file (default /sys/block/nvme1n1/stat)
  --cpu N                taskset CPU for every engine (default 2)
  --seed N               PRNG seed (default 20261113)
  --loadavg-max F        background-load gate passed to every block (default 1.6)
  --resume               pass --resume to each block (run-granular resume)
  --allow-concurrent     do not abort when a benchmark process is running
  --dry-run              print the 3-block plan (all block --dry-runs, expected
                         vs planned run counts, wall-time estimates) and exit;
                         executes NOTHING (no I/O, no mkdir, no sleeps)
  -h, --help             this text

Verification: before each real block the wrapper runs the block with --dry-run,
parses its SESSION_PLAN line and requires runs=24/70/36; it aborts on a mismatch,
a non-zero block exit, or any runs_failed. Final line: CAMPAIGN_RESULT ...
EOF
}

die()  { printf '%s: error: %s\n' "$PROG" "$*" >&2; exit 1; }
warn() { printf '%s: warning: %s\n' "$PROG" "$*" >&2; }
note() { printf '%s\n' "$*"; }

now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
now_s() { date +%s; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --session-dir)      [[ $# -ge 2 ]] || die "--session-dir needs a value"; SESSION_DIR=$2; shift 2 ;;
        --write-file)       [[ $# -ge 2 ]] || die "--write-file needs a value"; WRITE_FILE=$2; shift 2 ;;
        --device-stat)      [[ $# -ge 2 ]] || die "--device-stat needs a value"; DEVICE_STAT=$2; shift 2 ;;
        --cpu)              [[ $# -ge 2 ]] || die "--cpu needs a value"; CPU=$2; shift 2 ;;
        --seed)             [[ $# -ge 2 ]] || die "--seed needs a value"; SEED=$2; shift 2 ;;
        --loadavg-max)      [[ $# -ge 2 ]] || die "--loadavg-max needs a value"; LOADAVG_MAX=$2; shift 2 ;;
        --resume)           RESUME=1; shift ;;
        --allow-concurrent) ALLOW_CONCURRENT=1; shift ;;
        --dry-run)          DRY_RUN=1; shift ;;
        -h|--help)          usage; exit 0 ;;
        *) printf '%s: error: unknown option %s (try --help)\n' "$PROG" "$1" >&2; exit 2 ;;
    esac
done

[[ "$CPU" =~ ^[0-9]+$ ]] || die "--cpu must be a CPU number"
[[ "$SEED" =~ ^[0-9]+$ ]] || die "--seed must be an integer"
[[ "$LOADAVG_MAX" =~ ^[0-9]+([.][0-9]+)?$ ]] || die "--loadavg-max must be a number"
[[ -x "$SCRIPT_DIR/run-write-cells.sh" ]] || die "missing helper: $SCRIPT_DIR/run-write-cells.sh"
[[ -r "$DEVICE_STAT" ]] || die "cannot read device stat file: $DEVICE_STAT"

canon() { realpath -m -- "$1"; }
WF=$(canon "$WRITE_FILE")
RF=$(canon "${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}")
ND=$(canon "$REPO_ROOT/nvme-data")
[[ "$WF" == "$RF" ]] && die "refusing: write file resolves to the read dataset ($RF)"
case "$WF" in
    /mnt/TestData/read_files/*) die "refusing: write file is below the read dataset ($WF)" ;;
esac
[[ "$WF" == "$ND"/* ]] || die "refusing: write file must be below $ND (got $WF)"
SD=$(canon "$SESSION_DIR")
[[ "$SD" == "$ND"/* ]] && die "refusing: session dir must not be on the write dataset ($SD); keep manifests off the measured file's filesystem"

# ------------------------------------------------------------------ block commands
BLOCK_CMD=()
build_block_cmd() { # $1 = block number (1..3)
    local block=$1
    BLOCK_CMD=("$SCRIPT_DIR/run-write-cells.sh")
    case "$block" in
        1) BLOCK_CMD+=(--cells randwrite-4k-qd1,randwrite-4k-qd16 --reps 3) ;;
        2) BLOCK_CMD+=(--cells randwrite-4k-qd64,randwrite-4k-qd128 --reps-by-cell randwrite-4k-qd64=5,randwrite-4k-qd128=5) ;;
        3) BLOCK_CMD+=(--cells seqwrite-1m-qd1,seqwrite-1m-qd8,seqwrite-1m-qd32 --reps 3) ;;
        *) die "internal error: bad block number '$block'" ;;
    esac
    BLOCK_CMD+=(--engines juring,fio,seastar --allow-seastar-write
        --rest-scope reps --rest-sec 300 --precond-rest-sec 600
        --precondition per-cell --loadavg-max "$LOADAVG_MAX" --loadavg-action abort
        --sampler
        --session-dir "$SESSION_DIR"
        --write-file "$WF"
        --device-stat "$DEVICE_STAT"
        --cpu "$CPU" --seed "$SEED")
    if (( RESUME )); then
        BLOCK_CMD+=(--resume)
    fi
}

plan_field() { # $1 = SESSION_PLAN line, $2 = field name -> value (empty when absent)
    printf '%s\n' "$1" | grep -oE " $2=[^ ]*" | head -n1 | cut -d= -f2- || true
}

PLAN_RUNS=0
PLAN_EST=0
PLAN_EXISTING=0
PLAN_OUT=""
PLAN_LINE=""
parse_block_plan() { # $1 = block number; fills PLAN_* from the block's --dry-run
    local block=$1
    local out line
    out=$("${BLOCK_CMD[@]}" --dry-run 2>&1) || die "block $block --dry-run failed"
    line=$(printf '%s\n' "$out" | grep -m1 '^SESSION_PLAN ' || true)
    [[ -n "$line" ]] || die "block $block --dry-run emitted no SESSION_PLAN line"
    PLAN_LINE=$line
    PLAN_OUT=$out
    PLAN_RUNS=$(plan_field "$line" runs)
    PLAN_EST=$(plan_field "$line" est_sec)
    PLAN_EXISTING=$(plan_field "$line" runs_existing)
    [[ "$PLAN_RUNS" =~ ^[0-9]+$ ]] || die "block $block: cannot parse runs= from SESSION_PLAN"
    [[ "$PLAN_EST" =~ ^[0-9]+$ ]] || PLAN_EST=0
    [[ "$PLAN_EXISTING" =~ ^[0-9]+$ ]] || PLAN_EXISTING=0
}

# ------------------------------------------------------------------ campaign finish
CAMPAIGN_START_S=$(now_s)
BLOCKS_OK=0
TOTAL_PLANNED=0
TOTAL_EXECUTED=0
TOTAL_SKIPPED=0
TOTAL_OK=0
TOTAL_FAILED=0
RESTS_DONE=0
CAMPAIGN_DONE=0

campaign_result() { # $1 = status, optional $2 = extra fields
    local status=$1 extra=${2:-}
    printf 'CAMPAIGN_RESULT session_dir=%s blocks_planned=%s blocks_ok=%s runs_planned=%s runs_executed=%s runs_skipped=%s runs_ok=%s runs_failed=%s between_block_rests=%s elapsed_s=%s status=%s%s\n' \
        "$SESSION_DIR" "$BLOCK_COUNT" "$BLOCKS_OK" "$TOTAL_PLANNED" "$TOTAL_EXECUTED" \
        "$TOTAL_SKIPPED" "$TOTAL_OK" "$TOTAL_FAILED" "$RESTS_DONE" \
        "$(( $(now_s) - CAMPAIGN_START_S ))" "$status" "$extra"
}

abort_campaign() { # $1 = reason, $2 = extra fields
    warn "campaign aborted: $1"
    campaign_result aborted " reason=$(printf '%q' "$1")${2:-}"
    CAMPAIGN_DONE=1
    exit 1
}

on_exit() {
    local rc=$?
    if (( CAMPAIGN_DONE == 0 )); then
        campaign_result interrupted
    fi
    exit "$rc"
}
trap on_exit EXIT

# ------------------------------------------------------------------ dry run
if (( DRY_RUN )); then
    printf '# dry-run: run-write-campaign.sh - NOTHING is executed (no I/O, no mkdir, no sleeps)\n'
    printf '#   session_dir=%s (one dir, three blocks)\n' "$SESSION_DIR"
    printf '#   write_file=%s\n' "$WF"
    printf '#   common: engines=juring,fio,seastar allow_seastar_write=1 rest_scope=reps\n'
    printf '#           rest_sec=300 precond_rest_sec=600 precondition=per-cell\n'
    printf '#           loadavg_max=%s loadavg_action=abort sampler=1 resume=%s\n' "$LOADAVG_MAX" "$RESUME"
    printf '#   between-block rest=%ss (recorded phase=between-blocks)\n' "$BETWEEN_REST_SEC"
    printf '#\n'
    dry_total_est=0
    dry_total_runs=0
    for block in $(seq 1 "$BLOCK_COUNT"); do
        build_block_cmd "$block"
        parse_block_plan "$block"
        expected=${EXPECTED_BLOCK_RUNS[$(( block - 1 ))]}
        printf 'BLOCK %s/%s cells=%s expected_runs=%s planned_runs=%s resume_existing=%s est_sec=%s est_min=%s est_h=%s\n' \
            "$block" "$BLOCK_COUNT" "${BLOCK_CELLS[$(( block - 1 ))]}" "$expected" "$PLAN_RUNS" \
            "$PLAN_EXISTING" "$PLAN_EST" "$(( PLAN_EST / 60 ))" "$(( PLAN_EST / 3600 ))"
        printf '#   protocol: %s\n' "${BLOCK_DESC[$(( block - 1 ))]}"
        printf '#   command: %s\n' "$(printf '%q ' "${BLOCK_CMD[@]}")"
        # per-cell reps / planned runs from the block dry-run
        printf '%s\n' "$PLAN_OUT" | grep -E '^#   cell=' | sed 's/^#   /#     /'
        if (( PLAN_RUNS != expected )); then
            campaign_result aborted " block=$block expected_runs=$expected planned_runs=$PLAN_RUNS"
            CAMPAIGN_DONE=1
            exit 1
        fi
        dry_total_runs=$(( dry_total_runs + PLAN_RUNS ))
        dry_total_est=$(( dry_total_est + PLAN_EST ))
        if (( block < BLOCK_COUNT )); then
            printf '#   between-block rest: %ss enforced + recorded afterwards\n' "$BETWEEN_REST_SEC"
            dry_total_est=$(( dry_total_est + BETWEEN_REST_SEC ))
        fi
        printf '#\n'
    done
    if (( dry_total_runs != EXPECTED_TOTAL_RUNS )); then
        campaign_result aborted " total_planned=$dry_total_runs expected_total=$EXPECTED_TOTAL_RUNS"
        CAMPAIGN_DONE=1
        exit 1
    fi
    printf '# campaign plan: 3 blocks, %s runs, %s between-block rests x %ss\n' \
        "$dry_total_runs" "$(( BLOCK_COUNT - 1 ))" "$BETWEEN_REST_SEC"
    printf '# estimated campaign wall time: ~%ss (~%s min ~%s h), excluding engine startup variance and precondition overrun\n' \
        "$dry_total_est" "$(( dry_total_est / 60 ))" "$(( dry_total_est / 3600 ))"
    printf 'CAMPAIGN_PLAN session_dir=%s blocks=3 runs=%s expected_runs=%s est_sec=%s between_block_rests=%s resume=%s status=dry-run\n' \
        "$SESSION_DIR" "$dry_total_runs" "$EXPECTED_TOTAL_RUNS" "$dry_total_est" "$(( BLOCK_COUNT - 1 ))" "$RESUME"
    CAMPAIGN_DONE=1
    exit 0
fi

# ------------------------------------------------------------------ real preflight
free_bytes_for() { # $1 = path -> free bytes on nearest existing ancestor
    local dir=$1 free
    while [[ ! -e "$dir" ]]; do dir=$(dirname -- "$dir"); done
    free=$(df -B1 --output=avail "$dir" 2>/dev/null | tail -n1 | tr -d ' ' || true)
    [[ -n "$free" ]] || free=0
    printf '%s' "$free"
}

preflight() {
    local la1 free foreign scripts
    [[ -e "$WF" ]] || abort_campaign "write file missing: $WF (run bench/write/create-write-file.sh first)"
    [[ -w "$WF" ]] || abort_campaign "write file not writable: $WF"
    for scripts in precondition.sh run-write-cells.sh sample-device-iops.sh; do
        [[ -x "$SCRIPT_DIR/$scripts" ]] || abort_campaign "missing helper: $SCRIPT_DIR/$scripts"
    done
    free=$(free_bytes_for "$SESSION_DIR")
    (( free >= 2 * 1073741824 )) || abort_campaign "less than 2 GiB free for the session dir ($SESSION_DIR: $free bytes)"
    # session dir must be empty, new, or an existing write session continued
    # with --resume; never a random directory with unrelated data
    if [[ -d "$SESSION_DIR" && -n "$(ls -A -- "$SESSION_DIR" 2>/dev/null)" \
          && ! -f "$SESSION_DIR/manifest.txt" && ! -e "$SESSION_DIR/cells" ]]; then
        abort_campaign "session dir exists but does not look like a write session: $SESSION_DIR"
    fi
    if (( ! RESUME )) && { [[ -f "$SESSION_DIR/manifest.txt" ]] || [[ -e "$SESSION_DIR/cells" ]]; }; then
        abort_campaign "session dir already has state; pass --resume to continue it or choose a new --session-dir: $SESSION_DIR"
    fi
    la1=$(awk '{print $1}' /proc/loadavg)
    if awk -v a="$la1" -v b="$LOADAVG_MAX" 'BEGIN{exit !(a+0 > b+0)}'; then
        abort_campaign "loadavg(1m)=$la1 > --loadavg-max=$LOADAVG_MAX (background-load gate)"
    fi
    foreign=""
    foreign+=$(pgrep -x fio 2>/dev/null || true)
    foreign+=$(printf '\n%s' "$(pgrep -f 'seastar_file_bench' 2>/dev/null || true)")
    foreign+=$(printf '\n%s' "$(pgrep -f 'bench.juring.JUringFileBench' 2>/dev/null || true)")
    foreign+=$(printf '\n%s' "$(pgrep -f 'run-write-cells.sh' 2>/dev/null || true)")
    foreign=$(printf '%s\n' "$foreign" | sed '/^[[:space:]]*$/d')
    if [[ -n "$foreign" ]]; then
        if (( ALLOW_CONCURRENT )); then
            warn "a benchmark-looking process is running; continuing because --allow-concurrent:"
            printf '%s\n' "$foreign" >&2
        else
            printf '%s\n' "$foreign" >&2
            abort_campaign "another fio/seastar/JUring/run-write-cells process is running"
        fi
    fi
    note "preflight ok: loadavg=$la1 write_file=$WF session_dir=$SESSION_DIR free_session_fs=$free"
}

command -v flock >/dev/null 2>&1 || die "flock not found (util-linux); cannot take the single-instance lock"
preflight

mkdir -p -- "$SESSION_DIR/logs" "$SESSION_DIR/state"

# campaign-level single-writer lock (held for all three blocks + rests)
exec 7>"$SESSION_DIR/.run-write-campaign.lock"
if ! flock -n 7; then
    abort_campaign "another run-write-campaign.sh is already running (lock: $SESSION_DIR/.run-write-campaign.lock)"
fi

last_state_seq() { # max seq in state/order.tsv (0 when absent)
    local f="$SESSION_DIR/state/order.tsv" m=0
    if [[ -s "$f" ]]; then
        m=$(awk -F'\t' 'NR > 1 && $1 ~ /^[0-9]+$/ { if ($1 + 0 > m) m = $1 + 0 } END { print m + 0 }' "$f")
    fi
    [[ "$m" =~ ^[0-9]+$ ]] || m=0
    printf '%s' "$m"
}

record_between_rest() { # $1 = after_run_seq $2 = requested_s $3 = actual_s $4 = start_utc $5 = end_utc
    local rests="$SESSION_DIR/state/rests.tsv"
    if [[ ! -s "$rests" ]]; then
        printf '%s\n' $'phase\tafter_run_seq\trequested_s\tactual_s\tstart_utc\tend_utc' >"$rests"
    fi
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "between-blocks" "$1" "$2" "$3" "$4" "$5" >>"$rests"
}

note "== write-matrix campaign: 3 blocks, one session dir =="
note "   session dir: $SESSION_DIR"
note "   write file:  $WF"
note "   resume=$RESUME; between-block rest=${BETWEEN_REST_SEC}s; expected total runs=$EXPECTED_TOTAL_RUNS"

for block in $(seq 1 "$BLOCK_COUNT"); do
    expected=${EXPECTED_BLOCK_RUNS[$(( block - 1 ))]}
    build_block_cmd "$block"
    note ""
    note "== block $block/$BLOCK_COUNT (${BLOCK_DESC[$(( block - 1 ))]}) =="
    plan_log="$SESSION_DIR/logs/campaign-block${block}.log"

    # verify the plan BEFORE any I/O for this block
    parse_block_plan "$block"
    if (( PLAN_RUNS != expected )); then
        abort_campaign "block $block run-count mismatch: expected $expected, SESSION_PLAN says $PLAN_RUNS" " block=$block"
    fi
    note "   verified plan: runs=$PLAN_RUNS (expected $expected), est=${PLAN_EST}s, resume_existing=$PLAN_EXISTING"
    note "   command: $(printf '%q ' "${BLOCK_CMD[@]}")"
    note "   log: $plan_log"

    {
        printf '=== campaign block %s/%s %s %s\n' "$block" "$BLOCK_COUNT" "$(now_utc)" "${BLOCK_DESC[$(( block - 1 ))]}"
        printf 'CMD: %s\n' "$(printf '%q ' "${BLOCK_CMD[@]}")"
        printf 'expected_runs=%s planned_runs=%s est_sec=%s resume=%s\n' \
            "$expected" "$PLAN_RUNS" "$PLAN_EST" "$RESUME"
    } >"$plan_log"

    set +e
    "${BLOCK_CMD[@]}" >>"$plan_log" 2>&1
    rc=$?
    set -e
    printf 'BLOCK_EXIT block=%s rc=%s\n' "$block" "$rc" >>"$plan_log"

    block_result=$(grep '^SESSION_RESULT ' "$plan_log" | tail -n1 || true)
    b_planned=$(plan_field "$block_result" runs_planned)
    b_executed=$(plan_field "$block_result" runs_executed)
    b_skipped=$(plan_field "$block_result" runs_skipped)
    b_ok=$(plan_field "$block_result" runs_ok)
    b_failed=$(plan_field "$block_result" runs_failed)
    [[ "$b_planned" =~ ^[0-9]+$ ]] || b_planned=$PLAN_RUNS
    [[ "$b_executed" =~ ^[0-9]+$ ]] || b_executed=0
    [[ "$b_skipped" =~ ^[0-9]+$ ]] || b_skipped=0
    [[ "$b_ok" =~ ^[0-9]+$ ]] || b_ok=0
    [[ "$b_failed" =~ ^[0-9]+$ ]] || b_failed=0

    TOTAL_PLANNED=$(( TOTAL_PLANNED + b_planned ))
    TOTAL_EXECUTED=$(( TOTAL_EXECUTED + b_executed ))
    TOTAL_SKIPPED=$(( TOTAL_SKIPPED + b_skipped ))
    TOTAL_OK=$(( TOTAL_OK + b_ok ))
    TOTAL_FAILED=$(( TOTAL_FAILED + b_failed ))

    if (( rc != 0 )); then
        abort_campaign "block $block exited rc=$rc" " block=$block"
    fi
    if (( b_failed > 0 )); then
        abort_campaign "block $block had $b_failed failed run(s)" " block=$block"
    fi
    if [[ -z "$block_result" ]]; then
        abort_campaign "block $block emitted no SESSION_RESULT line" " block=$block"
    fi
    BLOCKS_OK=$(( BLOCKS_OK + 1 ))
    note "   block $block ok: planned=$b_planned executed=$b_executed skipped=$b_skipped ok=$b_ok failed=$b_failed"

    if (( block < BLOCK_COUNT )); then
        after_seq=$(last_state_seq)
        note "== between-block rest ${BETWEEN_REST_SEC}s after_run_seq=$after_seq (between-run rest) =="
        rest_start_utc=$(now_utc)
        t0=$(now_s)
        sleep "$BETWEEN_REST_SEC"
        t1=$(now_s)
        rest_end_utc=$(now_utc)
        rest_actual=$(( t1 - t0 ))
        record_between_rest "$after_seq" "$BETWEEN_REST_SEC" "$rest_actual" "$rest_start_utc" "$rest_end_utc"
        RESTS_DONE=$(( RESTS_DONE + 1 ))
        note "   rest recorded: actual=${rest_actual}s phase=between-blocks after_run_seq=$after_seq"
    fi
done

if (( TOTAL_PLANNED != EXPECTED_TOTAL_RUNS )); then
    abort_campaign "campaign planned $TOTAL_PLANNED runs, expected $EXPECTED_TOTAL_RUNS" " total=$TOTAL_PLANNED"
fi
if (( TOTAL_FAILED > 0 )); then
    abort_campaign "campaign had $TOTAL_FAILED failed run(s)"
fi

note ""
note "done: 3/3 blocks, $TOTAL_PLANNED planned runs, $TOTAL_EXECUTED executed, $TOTAL_SKIPPED skipped, $TOTAL_OK ok, $TOTAL_FAILED failed"
campaign_result ok
CAMPAIGN_DONE=1
exit 0
