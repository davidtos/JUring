#!/usr/bin/env bash
# run-unattended.sh -- one-command unattended campaign (root; no interaction needed).
#
# Phases (all frozen before the run; see docs/stepd-design.md and
# docs/m1-write-state-design.md):
#   0  host prep: record state, ensure /mnt/hugepages-juring + hugepage pool,
#      nvme smart-log before
#   1  Step D: seastar THP-vs-hugetlbfs bracket + memlock best-config read cells
#      (as user david under `prlimit --memlock=unlimited:unlimited`)
#   2  Write-state protocol chain W0 -> W1 -> W2 (as user david)
#   3  analyses, smart-log after, cleanup (hugepage pool restored, mount removed),
#      RESULTS-SUMMARY.md + DONE marker
#
# Usage (on the host, root):
#   sudo bash bench/stepd/run-unattended.sh
#   sudo bash bench/stepd/run-unattended.sh --root bench/results/unattended-2026-11-16
#   sudo bash bench/stepd/run-unattended.sh --dry-run
#
# Exit status: 0 = the chain completed (any scientific verdict), non-zero = infra issue.
set -uo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
cd "$REPO"

ROOT=${UNATTENDED_ROOT:-$REPO/bench/results/unattended-2026-11-16}
DRY=0
WINDOW_HOURS=${WINDOW_HOURS:-6.0}
HP_MOUNT=/mnt/hugepages-juring
JDK_HOME=/home/david/.jdks/openjdk-26.0.2
JAVA_BIN=$JDK_HOME/bin/java
while (( $# )); do
    case $1 in
        --root) ROOT=$2; shift 2 ;;
        --dry-run) DRY=1; shift ;;
        --window-hours) WINDOW_HOURS=$2; shift 2 ;;
        -h|--help) sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

LOGS=$ROOT/logs
STATE=$ROOT/state
STATUS=$ROOT/STATUS.txt
START_EPOCH=$(date +%s)
DEADLINE=$(( START_EPOCH + ${WINDOW_HOURS%.*} * 3600 - 600 ))   # 10 min safety
STEPD_RESULTS=$ROOT/stepd
WRITE_SESSION=$ROOT/write-state

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '%s | %s\n' "$(now)" "$*" >>"$STATUS"; }
say() { printf '%s\n' "$*"; log "$*"; }
die() { say "FATAL: $*"; exit 1; }

if [[ $(id -u) -ne 0 ]]; then
    echo "ERROR: run as root: sudo bash bench/stepd/run-unattended.sh" >&2; exit 1
fi
[[ -d $REPO/nvme-data/write_files ]] || die "nvme-data mount missing"
[[ -x $JAVA_BIN ]] || die "campaign JDK missing: $JAVA_BIN"

if (( DRY == 1 )); then
    echo "Unattended chain dry-run:"
    echo "  root=$ROOT  logs=$LOGS"
    echo "  phase 0: ensure $HP_MOUNT, pool 4608, smart-log before, record state"
    echo "  phase 1: prlimit --memlock=unlimited -- runuser -u david -- env JAVA_BIN=$JAVA_BIN ... bench/stepd/run-host-setup.sh --block all --results $STEPD_RESULTS"
    echo "  phase 2: runuser -u david -- bench/stepd/run-write-chain.sh --session-dir $WRITE_SESSION --deadline-epoch $DEADLINE"
    echo "  phase 3: analyses, smart-log after, cleanup pool/mount, RESULTS-SUMMARY.md"
    echo "  window=${WINDOW_HOURS}h deadline=$(date -u -d "@$DEADLINE" 2>/dev/null)"
    exit 0
fi

mkdir -p "$LOGS" "$STATE" "$STEPD_RESULTS"
chown -R david:david "$ROOT" 2>/dev/null || true
if [[ ! -s $STATUS ]]; then
    {
        echo "# Unattended Step D + write-state chain"
        echo "# started: $(now)  host: $(hostname)  window=${WINDOW_HOURS}h  deadline=$(date -u -d "@$DEADLINE")"
        echo "# repo: $(git rev-parse HEAD 2>/dev/null || echo unknown) dirty=$([[ -n $(git status --porcelain 2>/dev/null | head -1) ]] && echo true || echo false)"
        echo "# java: $("$JAVA_BIN" -version 2>&1 | head -1)"
        echo "# artifacts: run-host-setup.sh=$(sha256sum "$HERE/run-host-setup.sh" | cut -d' ' -f1)"
        echo "#            run-write-chain.sh=$(sha256sum "$HERE/run-write-chain.sh" | cut -d' ' -f1)"
        echo "#            analyze_write_state.py=$(sha256sum "$HERE/analyze_write_state.py" | cut -d' ' -f1)"
        echo "#            run-unattended.sh=$(sha256sum "$HERE/run-unattended.sh" | cut -d' ' -f1)"
    } >"$STATUS"
fi

PRE_NR=$(cat /proc/sys/vm/nr_hugepages 2>/dev/null || echo 0)
echo "$PRE_NR" >"$STATE/pre-nr-hugepages.txt"

as_david() { runuser -u david -- env JAVA_BIN="$JAVA_BIN" JAVA_HOME="$JDK_HOME" "$@"; }
smart() { nvme smart-log /dev/nvme1n1 2>/dev/null || echo "smart-log unavailable"; }

write_summary() {
    local rc=$?
    local sd_verdict="" ws_verdict=""
    [[ -f $ROOT/write-state/WRITE-STATE-VERDICT.md ]] && ws_verdict=$(grep -m1 '\*\*Verdict:\*\*' "$ROOT/write-state/WRITE-STATE-VERDICT.md" 2>/dev/null || true)
    if [[ -f $STEPD_RESULTS/analysis/stepd-summary.md ]]; then
        sd_verdict="see $STEPD_RESULTS/analysis/stepd-summary.md"
    fi
    {
        echo "# Unattended campaign — results summary"
        echo
        echo "Finished: $(now)  (exit=$rc)"
        echo "Window: ${WINDOW_HOURS}h from $(date -u -d "@$START_EPOCH")"
        echo
        echo "## Phase results"
        echo
        echo "- Step D (read): $sd_verdict"
        echo "- Write-state chain: ${ws_verdict:-see $WRITE_SESSION/WRITE-STATE-VERDICT.md}"
        echo
        echo "## Key files"
        echo
        echo "- \`$STATUS\` — phase log with timestamps"
        echo "- \`$STEPD_RESULTS/analysis/stepd-summary.md\` — Block A/B tables (paired ratios + CIs)"
        echo "- \`$WRITE_SESSION/WRITE-STATE-VERDICT.md\` — write verdict (PASS/FAIL/INCONCLUSIVE/UNRESOLVED)"
        echo "- \`$WRITE_SESSION/analysis/write-state-summary.md\` — gate tables + per-run classification"
        echo "- \`$STEPD_RESULTS/cell-meta.tsv\`, \`$WRITE_SESSION/**/cell-meta.tsv\` — raw rows"
        echo "- \`$LOGS/*.log\` — every command's output"
        echo
        echo "## Cleanup"
        echo
        echo "- hugepage pool restored to pre-run value ($PRE_NR) and \`$HP_MOUNT\` unmounted (best effort)."
        echo "- nvme smart-log before/after: \`$STATE/smart-before.txt\`, \`$STATE/smart-after.txt\`."
    } >"$ROOT/RESULTS-SUMMARY.md"
    chown -R david:david "$ROOT" 2>/dev/null || true
    echo "DONE $(now) exit=$rc" >>"$STATUS"
    umount "$HP_MOUNT" 2>/dev/null || true
    sysctl -w "vm.nr_hugepages=$PRE_NR" >/dev/null 2>&1 || true
    say "CLEANUP: nr_hugepages restored to $PRE_NR; $HP_MOUNT unmounted (best effort)"
    say "RESULTS: $ROOT/RESULTS-SUMMARY.md"
}
trap write_summary EXIT

# ---------------------------------------------------------------- phase 0
say "PHASE 0: host prep"
{
    echo "kernel=$(uname -r) host=$(hostname)"
    echo "governor=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
    echo "hugepages_total_pre=$(awk '/HugePages_Total/{print $2}' /proc/meminfo)"
    findmnt -no TARGET,FSTYPE,OPTIONS "$HP_MOUNT" 2>/dev/null || echo "$HP_MOUNT not mounted"
} >"$STATE/host-pre.txt"
smart >"$STATE/smart-before.txt"
if ! mountpoint -q "$HP_MOUNT"; then
    mkdir -p "$HP_MOUNT"
    mount -t hugetlbfs -o pagesize=2M,mode=1770,gid=1000 none "$HP_MOUNT" \
        || die "cannot mount $HP_MOUNT"
fi
sysctl -w vm.nr_hugepages=4608 >/dev/null || die "cannot set vm.nr_hugepages=4608"
chown root:1000 "$HP_MOUNT" 2>/dev/null || true
say "PHASE 0 done: huge_free=$(awk '/HugePages_Free/{print $2}' /proc/meminfo) mount=$(findmnt -no OPTIONS "$HP_MOUNT" 2>/dev/null)"

# ---------------------------------------------------------------- phase 1: Step D
say "PHASE 1: Step D campaign (seastar hugetlbfs bracket + memlock cells)"
timeout 6000 prlimit --memlock=unlimited:unlimited -- runuser -u david -- env \
    JAVA_BIN="$JAVA_BIN" JAVA_HOME="$JDK_HOME" \
    bash -lc "cd '$REPO' && bench/stepd/run-host-setup.sh --block all --results '$STEPD_RESULTS'" \
    >"$LOGS/stepd.log" 2>&1
RC_STEPD=$?
say "PHASE 1 done: rc=$RC_STEPD log=$LOGS/stepd.log"
as_david bash -lc "cd '$REPO' && python3 bench/stepd/analyze_host_setup.py --dir '$STEPD_RESULTS'" \
    >"$LOGS/stepd-analysis.log" 2>&1
say "PHASE 1 analysis: rc=$? log=$LOGS/stepd-analysis.log"
smart >"$STATE/smart-mid.txt"

# ---------------------------------------------------------------- phase 2: write chain
say "PHASE 2: state-controlled write chain (W0 -> W1 -> W2)"
timeout 18000 prlimit --memlock=unlimited:unlimited -- runuser -u david -- env \
    JAVA_BIN="$JAVA_BIN" JAVA_HOME="$JDK_HOME" STEPD_WRITE_DEADLINE="$DEADLINE" \
    bash -lc "cd '$REPO' && bench/stepd/run-write-chain.sh --session-dir '$WRITE_SESSION' --deadline-epoch '$DEADLINE'" \
    >"$LOGS/write-chain.log" 2>&1
RC_WRITE=$?
say "PHASE 2 done: rc=$RC_WRITE log=$LOGS/write-chain.log"
smart >"$STATE/smart-after.txt"

# ---------------------------------------------------------------- phase 3
say "PHASE 3: collect + cleanup"
{
    echo "stepd_rc=$RC_STEPD"
    echo "write_chain_rc=$RC_WRITE"
    echo "stepd_cells=$(find "$STEPD_RESULTS/cells" -name '*.json' 2>/dev/null | wc -l)"
    echo "write_generations=$(ls -d "$WRITE_SESSION"/w0-* "$WRITE_SESSION"/w1* "$WRITE_SESSION"/w2-* 2>/dev/null | wc -l)"
} >"$STATE/phase3.txt"
say "PHASE 3 done"
exit 0
