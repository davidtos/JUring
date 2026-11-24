#!/usr/bin/env bash
# run-write-chain.sh -- pre-registered state-controlled write protocol.
#
# FROZEN PROTOCOL: fixed before the campaign (see docs/write-state-3way-protocol.md). Unattended.
#   control pass  one preconditioned pass with the same timing as the write-matrix
#                 pass (precondition + 600 s rest, no inter-run rest because the pass
#                 is one rep): is the pass
#                 single-state and homogeneous across the three arms of both gate cells?
#   variants      only if the control pass fails: two pre-registered state-control
#                 variants, one pass each: A = no rest after preconditioning / no
#                 inter-run rest ("continuous"); B = no rest after preconditioning,
#                 120 s idle between runs.
#   gate campaign only if the control pass or a variant passes: 5 passes with that
#                 variant; frozen gate = PASS iff JUring/fio `matched` and
#                 JUring/fio `matchedbatch` one-sided 95 % LBs are >= 0.95 at BOTH
#                 4 KiB random-write cells (QD 64 and QD 128), using only valid
#                 passes (n>=4). Otherwise INCONCLUSIVE / FAIL as defined.
#
# Passes are named pass1..pass8; the analyzer flags are --passes and --pass-dir.
#
# Usage (as user david, from repo root):
#   bench/host-setup/run-write-chain.sh --session-dir bench/results/write-state
#   bench/host-setup/run-write-chain.sh --dry-run
#
# Exit: 0 = chain completed (whatever the verdict), 1 = infrastructure failure.
set -uo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
cd "$REPO"

SESSION=${HOST_SETUP_WRITE_SESSION:-$REPO/bench/results/write-state}
DRY=0
DEADLINE=${HOST_SETUP_WRITE_DEADLINE:-0}   # absolute epoch; 0 = no guard
while (( $# )); do
    case $1 in
        --session-dir) SESSION=$2; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        --deadline-epoch) DEADLINE=$2; shift 2 ;;
        -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

WRITE_FILE=$REPO/nvme-data/write_files/single_file_bench_w.bin
READ_FILE=/mnt/TestData/read_files/single_file_bench.bin
JAVA_BIN=${JAVA_BIN:-/home/david/.jdks/openjdk-26.0.2/bin/java}
WRC=$REPO/bench/write/run-write-cells.sh
ANALYZER=$HERE/analyze_write_state.py
LOGS=$SESSION/logs
STATUS=$SESSION/STATUS.txt
mkdir -p "$LOGS"

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '%s | %s\n' "$(now)" "$*" >>"$STATUS"; }
say() { printf '%s\n' "$*"; log "$*"; }
remaining() { if (( DEADLINE > 0 )); then echo $(( DEADLINE - $(date +%s) )); else echo 999999; fi; }
die() { say "FATAL: $*"; exit 1; }

preflight() {
    local fail=0 la
    [[ -f $WRITE_FILE ]] || { say "preflight: missing write file $WRITE_FILE"; fail=1; }
    [[ $(stat -c%s "$WRITE_FILE" 2>/dev/null) == 10737418240 ]] || { say "preflight: write file is not 10 GiB"; fail=1; }
    [[ -f $READ_FILE ]] || { say "preflight: missing read dataset"; fail=1; }
    [[ $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor) == performance ]] || { say "preflight: governor not performance"; fail=1; }
    if ! "$JAVA_BIN" -version 2>&1 | grep -q 'version "26\.0\.2"'; then say "preflight: wrong JDK ($JAVA_BIN)"; fail=1; fi
    la=$(awk '{print $1}' /proc/loadavg)
    awk -v x="$la" 'BEGIN{exit !(x < 1.6)}' || { say "preflight: loadavg(1m)=$la >= 1.6"; fail=1; }
    if pgrep -f 'seastar_file_bench|JUringFileBench|fio .*single_file_bench' >/dev/null 2>&1; then
        say "preflight: another benchmark process is running"; fail=1
    fi
    (( fail == 0 )) || die "preflight failed"
    say "preflight ok: user=$(id -un) java=$("$JAVA_BIN" -version 2>&1 | head -1) loadavg=$la write_file=$(stat -c%s "$WRITE_FILE")"
}

run_pass() { # run_pass <name> <precond_rest> <rest_sec> <rest_scope> <variant_label>
    local name=$1 pr=$2 rs=$3 scope=$4 label=$5 rc=0
    local dir=$SESSION/$name
    if (( DRY == 1 )); then
        echo "DRY pass $name variant=$label precond_rest=${pr}s rest=${rs}s scope=$scope"
        echo "    $WRC --cells randwrite-4k-qd64,randwrite-4k-qd128 --engines juring,fio --fio-variants matched,matchedbatch --no-batching-sweep \\"
        echo "        --reps-by-cell randwrite-4k-qd64=1,randwrite-4k-qd128=1 --precondition session-once --precond-rest-sec $pr \\"
        echo "        --rest-sec $rs --rest-scope $scope --session-dir $dir --loadavg-max 1.6"
        return 0
    fi
    say "PASS $name start (variant=$label precond_rest=${pr}s rest=${rs}s scope=$scope)"
    timeout 2400 bash "$WRC" --cells randwrite-4k-qd64,randwrite-4k-qd128 --engines juring,fio \
        --fio-variants matched,matchedbatch --no-batching-sweep \
        --reps-by-cell randwrite-4k-qd64=1,randwrite-4k-qd128=1 --precondition session-once \
        --precond-rest-sec "$pr" --rest-sec "$rs" --rest-scope "$scope" \
        --session-dir "$dir" --loadavg-max 1.6 --loadavg-action abort \
        >"$LOGS/$name.log" 2>&1 || rc=$?
    python3 "$ANALYZER" --pass-dir "$dir" >"$LOGS/$name.classify.txt" 2>&1 || true
    local pass
    pass=$(awk '/^PILOT_PASS/{print $2}' "$LOGS/$name.classify.txt" 2>/dev/null | tail -1)
    [[ -z $pass ]] && pass=0
    say "PASS $name done rc=$rc PILOT_PASS=$pass"
    say "  $(tail -1 "$LOGS/$name.classify.txt" 2>/dev/null)"
    (( rc == 0 )) || say "  WARN: harness exit $rc (see $LOGS/$name.log)"
    PASS_OK=$pass
}

# ---------------------------------------------------------------- chain
if [[ ! -s $STATUS ]]; then
    {
        echo "# State-controlled write protocol chain (docs/write-state-3way-protocol.md)"
        echo "# started: $(now)  host: $(hostname)  user: $(id -un)"
        echo "# session: $SESSION"
        echo "# java: $("$JAVA_BIN" -version 2>&1 | head -1)"
        echo "# hugepages-free: $(awk '/HugePages_Free/{print $2}' /proc/meminfo 2>/dev/null || echo n/a)"
        echo "# remaining_guard: deadline=$DEADLINE ($(date -u -d "@$DEADLINE" 2>/dev/null || echo n/a))"
    } >"$STATUS"
fi

if (( DRY == 1 )); then
    echo "Write-chain dry-run: session=$SESSION"
    run_pass control-pass 600 0 runs control
    echo "--- would then evaluate control pass and (only if it fails) run the two state-control variants A/B, then the gate campaign x5 ---"
    exit 0
fi

preflight
: >"$SESSION/state-decisions.txt"

# ---- control pass
run_pass control-pass 600 0 runs control
CONTROL=$PASS_OK
echo "control pass PILOT_PASS=$CONTROL" >>"$SESSION/state-decisions.txt"
VARIANT=""; PR=0; RS=0; SCOPE=reps
if [[ $CONTROL == 1 ]]; then
    VARIANT=control; PR=600; RS=0; SCOPE=runs
    say "DECISION: control pass passed -> gate campaign uses control variant (precond+600 s rest)"
else
    say "DECISION: control pass failed -> variant A (continuous: no post-precondition rest, no inter-run rest)"

    # ---- variant A (continuous)
    run_pass variant-a-continuous 0 0 runs continuous
    VARIANT_A=$PASS_OK
    echo "variant-a-continuous PILOT_PASS=$VARIANT_A" >>"$SESSION/state-decisions.txt"
    if [[ $VARIANT_A == 1 ]]; then
        VARIANT=continuous; PR=0; RS=0; SCOPE=runs
        say "DECISION: variant A (continuous) passed -> gate campaign uses continuous variant"
    else
        say "DECISION: variant A (continuous) failed -> variant B (brief idle: no post-precondition rest, 120 s idle between runs)"
        run_pass variant-a-briefidle 0 120 runs briefidle
        VARIANT_B=$PASS_OK
        echo "variant-a-briefidle PILOT_PASS=$VARIANT_B" >>"$SESSION/state-decisions.txt"
        if [[ $VARIANT_B == 1 ]]; then
            VARIANT=briefidle; PR=0; RS=120; SCOPE=runs
            say "DECISION: variant B (brief idle) passed -> gate campaign uses brief-idle variant"
        else
            say "DECISION: no variant produced a valid pass -> UNRESOLVED"
        fi
    fi
fi

if [[ -z $VARIANT ]]; then
    {
        echo "# State-controlled write protocol: UNRESOLVED"
        echo "# No preconditioned pass was both single-state and homogeneous across"
        echo "# the three arms of the two 4 KiB random-write gate cells under the control pass or the two pre-registered variants."
        echo "# This is the pre-registered negative outcome: the write half stays INCONCLUSIVE"
        echo "# for v1 with a *documented cause*, not for lack of protocol."
        echo "# Evidence: $LOGS/control-pass.classify.txt"
        echo "#           $LOGS/variant-a-continuous.classify.txt"
        echo "#           $LOGS/variant-a-briefidle.classify.txt"
        echo "# sessions: $SESSION/control-pass $SESSION/variant-a-continuous $SESSION/variant-a-briefidle"
    } >"$SESSION/WRITE-STATE-VERDICT.md"
    say "CHAIN END (UNRESOLVED). See $SESSION/WRITE-STATE-VERDICT.md"
    exit 0
fi

# ---- gate campaign (5 passes)
REMAIN=$(remaining)
NEED=$(( 5 * (1080 + PR + 5 * RS) + 300 ))
if (( REMAIN < NEED )); then
    say "TIMEOUT GUARD: remaining ${REMAIN}s < needed ${NEED}s -> skip the gate campaign (pilot data disclosed)"
    echo "gate-campaign SKIPPED: time guard (remaining ${REMAIN}s < ${NEED}s)" >>"$SESSION/state-decisions.txt"
    {
        echo "# State-controlled write protocol: pilot passed (variant=$VARIANT) but the"
        echo "# 5-pass gate campaign did not fit in the unattended window."
        echo "# Pilot evidence: $LOGS/control-pass.classify.txt"
        echo "# Variant: $VARIANT (precond_rest=${PR}s rest=${RS}s scope=$SCOPE)"
        echo "# => write rule 1 still INCONCLUSIVE; a follow-up window can run the gate campaign only."
    } >"$SESSION/WRITE-STATE-VERDICT.md"
    exit 0
fi

say "DECISION: starting the gate campaign (5 passes, variant=$VARIANT)"
passes=""
for g in 1 2 3 4 5; do
    name="pass$g"
    if (( $(remaining) < 900 )); then
        say "TIMEOUT GUARD: stopping the gate campaign before $name"
        break
    fi
    run_pass "$name" "$PR" "$RS" "$SCOPE" "$VARIANT"
    passes="${passes:+$passes,}$name"
    if (( PASS_OK == 1 )); then
        say "pass $name valid"
    else
        say "pass $name INVALID (excluded from the gate; disclosed)"
    fi
done

python3 "$ANALYZER" --session "$SESSION" --passes "$passes" --out "$SESSION/analysis" \
    >"$LOGS/gate-analysis.log" 2>&1 || true
VERDICT=$(python3 - <<EOF
import json
try:
    print(json.load(open("$SESSION/analysis/write-state-verdict.json"))["verdict"])
except Exception:
    print("INCONCLUSIVE")
EOF
)
{
    echo "# State-controlled write protocol — gate result"
    echo
    echo "**Variant:** $VARIANT (precond_rest=${PR}s, inter-run rest=${RS}s, scope=$SCOPE)"
    echo "**Passes attempted:** $passes"
    echo "**Verdict:** $VERDICT"
    echo
    echo "See analysis/write-state-summary.md, analysis/write-state-verdict.json,"
    echo "     logs/gate-analysis.log, and STATUS.txt."
} >"$SESSION/WRITE-STATE-VERDICT.md"
say "CHAIN END: verdict=$VERDICT (variant=$VARIANT, passes=$passes)"
say "Artifacts: $SESSION/WRITE-STATE-VERDICT.md $SESSION/analysis/"
exit 0
