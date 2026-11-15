#!/usr/bin/env bash
# run-write-chain.sh -- pre-registered state-controlled write protocol (W0 -> W1 -> W2).
#
# FROZEN PROTOCOL: docs/m1-write-state-design.md (v1.0). Unattended.
#   W0  one preconditioned generation, Step-A-like timing (precondition + 600 s rest,
#       no inter-run rest because the generation is one rep): is the generation
#       single-state and homogeneous across the three arms of both gate cells?
#   W1  only if W0 fails: two pre-registered state-control variants, one generation
#       each:  A = no rest after preconditioning / no inter-run rest ("continuous");
#              B = no rest after preconditioning, 120 s idle between runs.
#   W2  only if W0 or a W1 variant passes: 5 generations with that variant; frozen
#       gate = PASS iff JUring/fio `matched` and JUring/fio `matchedbatch` one-sided
#       95 % LBs are >= 0.95 at BOTH W7 (4K randwrite QD64) and W8 (QD128), using
#       only valid generations (n>=4). Otherwise INCONCLUSIVE / FAIL as defined.
#
# Usage (as user david, from repo root):
#   bench/stepd/run-write-chain.sh --session-dir bench/results/stepd-write-2026-11-16
#   bench/stepd/run-write-chain.sh --dry-run
#
# Exit: 0 = chain completed (whatever the verdict), 1 = infrastructure failure.
set -uo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
cd "$REPO"

SESSION=${STEPD_WRITE_SESSION:-$REPO/bench/results/stepd-write-2026-11-16}
DRY=0
DEADLINE=${STEPD_WRITE_DEADLINE:-0}   # absolute epoch; 0 = no guard
while (( $# )); do
    case $1 in
        --session-dir) SESSION=$2; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        --deadline-epoch) DEADLINE=$2; shift 2 ;;
        -h|--help) sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

WRITE_FILE=$REPO/nvme-data/write_files/single_file_bench_w.bin
READ_FILE=/mnt/TestData/read_files/single_file_bench.bin
JAVA_BIN=${JAVA_BIN:-/home/david/.jdks/openjdk-26.0.2/bin/java}
WRC=$REPO/bench/write/run-write-cells.sh
ANALYZER=$HERE/write_state_analysis.py
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

run_gen() { # run_gen <name> <precond_rest> <rest_sec> <rest_scope> <variant_label>
    local name=$1 pr=$2 rs=$3 scope=$4 label=$5 rc=0
    local dir=$SESSION/$name
    if (( DRY == 1 )); then
        echo "DRY gen $name variant=$label precond_rest=${pr}s rest=${rs}s scope=$scope"
        echo "    $WRC --cells W7,W8 --engines juring,fio --fio-variants matched,matchedbatch --no-w7-sweep \\"
        echo "        --reps-by-cell W7=1,W8=1 --precondition session-once --precond-rest-sec $pr \\"
        echo "        --rest-sec $rs --rest-scope $scope --session-dir $dir --loadavg-max 1.6"
        return 0
    fi
    say "GEN $name start (variant=$label precond_rest=${pr}s rest=${rs}s scope=$scope)"
    timeout 2400 bash "$WRC" --cells W7,W8 --engines juring,fio \
        --fio-variants matched,matchedbatch --no-w7-sweep \
        --reps-by-cell W7=1,W8=1 --precondition session-once \
        --precond-rest-sec "$pr" --rest-sec "$rs" --rest-scope "$scope" \
        --session-dir "$dir" --loadavg-max 1.6 --loadavg-action abort \
        >"$LOGS/$name.log" 2>&1 || rc=$?
    python3 "$ANALYZER" --gen-dir "$dir" >"$LOGS/$name.classify.txt" 2>&1 || true
    local pass
    pass=$(awk '/^PILOT_PASS/{print $2}' "$LOGS/$name.classify.txt" 2>/dev/null | tail -1)
    [[ -z $pass ]] && pass=0
    say "GEN $name done rc=$rc PILOT_PASS=$pass"
    say "  $(tail -1 "$LOGS/$name.classify.txt" 2>/dev/null)"
    (( rc == 0 )) || say "  WARN: harness exit $rc (see $LOGS/$name.log)"
    GEN_PASS=$pass
}

# ---------------------------------------------------------------- chain
if [[ ! -s $STATUS ]]; then
    {
        echo "# State-controlled write protocol chain (docs/m1-write-state-design.md v1.0)"
        echo "# started: $(now)  host: $(hostname)  user: $(id -un)"
        echo "# session: $SESSION"
        echo "# java: $("$JAVA_BIN" -version 2>&1 | head -1)"
        echo "# hugepages-free: $(awk '/HugePages_Free/{print $2}' /proc/meminfo 2>/dev/null || echo n/a)"
        echo "# remaining_guard: deadline=$DEADLINE ($(date -u -d "@$DEADLINE" 2>/dev/null || echo n/a))"
    } >"$STATUS"
fi

if (( DRY == 1 )); then
    echo "Step D write chain dry-run: session=$SESSION"
    run_gen w0-control 600 0 runs control
    gen_variant=""
    echo "--- would then evaluate W0 and (only if it fails) run W1 variants A/B, then W2 x5 ---"
    exit 0
fi

preflight
: >"$SESSION/state-decisions.txt"

# ---- W0: control generation
run_gen w0-control 600 0 runs control
W0=$GEN_PASS
echo "W0 PILOT_PASS=$W0" >>"$SESSION/state-decisions.txt"
VARIANT=""; PR=0; RS=0; SCOPE=reps
if [[ $W0 == 1 ]]; then
    VARIANT=control; PR=600; RS=0; SCOPE=runs
    say "DECISION: W0 passed -> gate campaign uses control variant (precond+600 s rest)"
else
    say "DECISION: W0 failed -> W1-A (continuous: no post-precondition rest, no inter-run rest)"

    # ---- W1-A
    run_gen w1a-continuous 0 0 runs continuous
    W1A=$GEN_PASS
    echo "W1A PILOT_PASS=$W1A" >>"$SESSION/state-decisions.txt"
    if [[ $W1A == 1 ]]; then
        VARIANT=continuous; PR=0; RS=0; SCOPE=runs
        say "DECISION: W1-A passed -> gate campaign uses continuous variant"
    else
        say "DECISION: W1-A failed -> W1-B (no post-precondition rest, 120 s idle between runs)"
        run_gen w1b-briefidle 0 120 runs briefidle
        W1B=$GEN_PASS
        echo "W1B PILOT_PASS=$W1B" >>"$SESSION/state-decisions.txt"
        if [[ $W1B == 1 ]]; then
            VARIANT=briefidle; PR=0; RS=120; SCOPE=runs
            say "DECISION: W1-B passed -> gate campaign uses brief-idle variant"
        else
            say "DECISION: no variant produced a valid generation -> UNRESOLVED"
        fi
    fi
fi

if [[ -z $VARIANT ]]; then
    {
        echo "# State-controlled write protocol: UNRESOLVED"
        echo "# No preconditioned generation was both single-state and homogeneous across"
        echo "# the three arms of W7/W8 under W0 or the two pre-registered W1 variants."
        echo "# This is the pre-registered negative outcome: the write half stays INCONCLUSIVE"
        echo "# for v1 with a *documented cause*, not for lack of protocol."
        echo "# Evidence: $LOGS/w0-control.classify.txt"
        echo "#           $LOGS/w1a-continuous.classify.txt"
        echo "#           $LOGS/w1b-briefidle.classify.txt"
        echo "# sessions: $SESSION/w0-control $SESSION/w1a-continuous $SESSION/w1b-briefidle"
    } >"$SESSION/WRITE-STATE-VERDICT.md"
    say "CHAIN END (UNRESOLVED). See $SESSION/WRITE-STATE-VERDICT.md"
    exit 0
fi

# ---- W2: gate campaign (5 generations)
REMAIN=$(remaining)
NEED=$(( 5 * (1080 + PR + 5 * RS) + 300 ))
if (( REMAIN < NEED )); then
    say "TIMEOUT GUARD: remaining ${REMAIN}s < needed ${NEED}s -> skip W2 (pilot data disclosed)"
    echo "W2 SKIPPED: time guard (remaining ${REMAIN}s < ${NEED}s)" >>"$SESSION/state-decisions.txt"
    {
        echo "# State-controlled write protocol: pilot passed (variant=$VARIANT) but the"
        echo "# 5-generation gate campaign did not fit in the unattended window."
        echo "# Pilot evidence: $LOGS/w0-control.classify.txt"
        echo "# Variant: $VARIANT (precond_rest=${PR}s rest=${RS}s scope=$SCOPE)"
        echo "# => write rule 1 still INCONCLUSIVE; a follow-up window can run W2 only."
    } >"$SESSION/WRITE-STATE-VERDICT.md"
    exit 0
fi

say "DECISION: starting W2 gate campaign (5 generations, variant=$VARIANT)"
gens=""
for g in 1 2 3 4 5; do
    name="w2-g$g"
    if (( $(remaining) < 900 )); then
        say "TIMEOUT GUARD: stopping W2 before $name"
        break
    fi
    run_gen "$name" "$PR" "$RS" "$SCOPE" "$VARIANT"
    gens="${gens:+$gens,}$name"
    if (( GEN_PASS == 1 )); then
        say "GEN $name valid"
    else
        say "GEN $name INVALID (excluded from the gate; disclosed)"
    fi
done

python3 "$ANALYZER" --session "$SESSION" --gens "$gens" --out "$SESSION/analysis" \
    >"$LOGS/w2-analysis.log" 2>&1 || true
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
    echo "**Generations attempted:** $gens"
    echo "**Verdict:** $VERDICT"
    echo
    echo "See analysis/write-state-summary.md, analysis/write-state-verdict.json,"
    echo "     logs/w2-analysis.log, and STATUS.txt."
} >"$SESSION/WRITE-STATE-VERDICT.md"
say "CHAIN END: verdict=$VERDICT (variant=$VARIANT, gens=$gens)"
say "Artifacts: $SESSION/WRITE-STATE-VERDICT.md $SESSION/analysis/"
exit 0
