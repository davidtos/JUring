#!/usr/bin/env bash
#
# bench/read-write/write-manifest.sh -- capture the read/write session environment manifest.
#
# Writes $MATRIX_RESULTS/manifest.txt (MATRIX_RESULTS defaults to
# bench/results/read-write): host/kernel/CPU, device and
# mount options, dataset stat, tool versions, engine binary + harness script
# SHA256 (the frozen methodology), engine differences, and the phase list.
# Every cell in the session is citable only together with this file
# (the reproducibility gate in docs/benchmark-methodology.md).
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/read-write/lib.sh
source "$HERE/lib.sh"
cd "$MATRIX_REPO_ROOT"

OUT=$MATRIX_RESULTS/manifest.txt
{
    echo "# Read/write read-path session manifest -- JUring vs seastar"
    echo "# generated: $(date -Iseconds)  host: $(hostname)"
    echo "# workspace: $MATRIX_REPO_ROOT"
    echo
    echo "## Host / kernel"
    uname -a
    echo
    echo "## CPU"
    lscpu | grep -E 'Model name|^CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket|Socket|NUMA node|MHz' || true
    echo
    echo "## CPU governor (unique values over all CPUs)"
    for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do cat "$f"; done | sort | uniq -c
    cat /sys/devices/system/cpu/cpu0/cpufreq/energy_performance_preference 2>/dev/null || true
    echo
    echo "## Memory / hugepages"
    grep -E 'MemTotal|MemAvailable|HugePages_Total|Hugepagesize' /proc/meminfo
    echo
    echo "## Locked-memory limit (fixed-buffer fast path gate)"
    echo "ulimit -l = $(ulimit -l) KiB"
    echo
    echo "## Data device and mounts"
    echo "data device (read dataset):"
    findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS /mnt/TestData || true
    echo "writable benchmark path:"
    findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$MATRIX_REPO_ROOT/nvme-data" || true
    df -h "$MATRIX_REPO_ROOT/nvme-data" || true
    lsblk -o NAME,MODEL,SERIAL,SIZE,MOUNTPOINTS | grep -E 'nvme|NAME' || true
    echo
    echo "## Read dataset"
    stat -c '%n size=%s blocks=%b blksize=%B mtime=%y' "$MATRIX_RD_FILE"
    echo
    echo "## Kernel I/O controls"
    echo "kernel.io_uring_disabled = $(cat /proc/sys/kernel/io_uring_disabled 2>/dev/null || echo '?')"
    echo "perf_event_paranoid      = $(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo '?')"
    echo
    echo "## Tool versions"
    echo "fio: $(fio --version 2>/dev/null || echo '?')"
    echo "perf: $(perf --version 2>/dev/null | head -1 || echo '?')"
    java -version 2>&1 | head -3 || true
    echo "seastar: $(grep -m1 -o 'VERSION = .*' SeaStarRef/build/release/build.ninja 2>/dev/null || echo 'seastar-25.05.0-1392-g04b1c04d')"
    echo
    echo "## Repo state"
    echo "git commit: $(git -C "$MATRIX_REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "git describe: $(git -C "$MATRIX_REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
    echo "git status (porcelain):"
    git -C "$MATRIX_REPO_ROOT" status --porcelain 2>/dev/null | head -20 || true
    echo "seastar ref: $(git -C "$MATRIX_REPO_ROOT/SeaStarRef" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo
    echo "## Engine binaries (sha256)"
    sha256sum "$MATRIX_SEASTAR_BIN" "$MATRIX_IOTESTER_BIN" 2>/dev/null || true
    echo
    echo "## Harness scripts (frozen for this session; sha256)"
    sha256sum "$MATRIX_LIB_DIR"/lib.sh "$MATRIX_LIB_DIR"/json_summary.py "$MATRIX_LIB_DIR"/perf_summary.py \
        "$MATRIX_LIB_DIR"/run-read-matrix.sh "$MATRIX_LIB_DIR"/run-latency-matrix.sh \
        "$MATRIX_LIB_DIR"/run-fio-brackets.sh "$MATRIX_LIB_DIR"/run-perf-cells.sh \
        "$MATRIX_LIB_DIR"/run-iotester-check.sh 2>/dev/null || true
    echo
    echo "## Engine configurations and disclosed asymmetries (best documented configuration, fully disclosed)"
    echo "juring   : io_uring; SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN; registered file;"
    echo "           qd registered fixed buffers (memlock fallback recorded per cell)."
    echo "seastar  : default io_uring reactor backend; stock 200-entry ring; no ring flags,"
    echo "           no registered files/buffers; io_queue + futures in the path;"
    echo "           --smp 1 --cpuset $MATRIX_CPU --memory 1G --lock-memory 0 (no hugepages)."
    echo "fio      : io_uring; 'matched' = --registerfiles=1 --fixedbufs=1 (read/write reference);"
    echo "           brackets: default (no registration, batch_submit=1), batch8/16/32,"
    echo "           sqthread_poll, libaio. fio forked job process (one job)."
    echo "pinning  : every engine pinned with taskset -c $MATRIX_CPU, one thread/shard."
    echo "cache    : O_DIRECT everywhere; no cold-cache drop (no root)."
    echo "hugepages: $(grep HugePages_Total /proc/meminfo)"
    echo
    echo "## Session phases (the recorded phase order)"
    echo "1. run-read-matrix.sh      4 KiB random read QD 1/16/64/128, 10 s warmup + 30 s, R=5 (juring/seastar/fio-matched)"
    echo "2. run-fio-brackets.sh     fio config brackets at QD64 (+QD128 default/libaio), R=3"
    echo "3. run-latency-matrix.sh   randread-4k-qd1-latency (QD1) + randread-4k-qd64-latency (QD64), 10 s warmup + 120 s, R=5, three-way"
    echo "4. run-perf-cells.sh       cycles/instructions/syscalls per op at QD1+QD64, R=3,"
    echo "                           fio comparator default + registration-matched"
    echo "5. run-seqread-matrix.sh   1 MiB sequential read QD 1/8/32, 10 s warmup + 30 s, R=5, three-way"
    echo "6. run-iotester-check.sh   io_tester cross-check (writes 2x10 GiB setup fills on nvme-data)"
    echo
    echo "## Session start / end"
    echo "started: $(now_utc)"
} >"$OUT"
echo "manifest: $OUT"
