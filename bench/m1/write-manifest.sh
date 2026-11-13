#!/usr/bin/env bash
#
# bench/m1/write-manifest.sh -- capture the M1 session environment manifest.
#
# Writes bench/results/m1-2026-11-13/manifest.txt: host/kernel/CPU, device and
# mount options, dataset stat, tool versions, engine binary + harness script
# SHA256 (the frozen methodology), engine differences, and the phase list.
# Every cell in the session is citable only together with this file
# (docs/benchmark-methodology.md §8 reproducibility gate).
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/m1/lib.sh
source "$HERE/lib.sh"
cd "$M1_REPO_ROOT"

OUT=$M1_RESULTS/manifest.txt
{
    echo "# M1 read-path session manifest -- JUring vs seastar"
    echo "# generated: $(date -Iseconds)  host: $(hostname)"
    echo "# workspace: $M1_REPO_ROOT"
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
    findmnt -no TARGET,SOURCE,FSTYPE,OPTIONS "$M1_REPO_ROOT/nvme-data" || true
    df -h "$M1_REPO_ROOT/nvme-data" || true
    lsblk -o NAME,MODEL,SERIAL,SIZE,MOUNTPOINTS | grep -E 'nvme|NAME' || true
    echo
    echo "## Read dataset"
    stat -c '%n size=%s blocks=%b blksize=%B mtime=%y' "$M1_RD_FILE"
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
    echo "git commit: $(git -C "$M1_REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo "git describe: $(git -C "$M1_REPO_ROOT" describe --tags --always --dirty 2>/dev/null || echo unknown)"
    echo "git status (porcelain):"
    git -C "$M1_REPO_ROOT" status --porcelain 2>/dev/null | head -20 || true
    echo "seastar ref: $(git -C "$M1_REPO_ROOT/SeaStarRef" rev-parse HEAD 2>/dev/null || echo unknown)"
    echo
    echo "## Engine binaries (sha256)"
    sha256sum "$M1_SEASTAR_BIN" "$M1_IOTESTER_BIN" 2>/dev/null || true
    echo
    echo "## Harness scripts (frozen for this session; sha256)"
    sha256sum "$M1_LIB_DIR"/lib.sh "$M1_LIB_DIR"/json_summary.py "$M1_LIB_DIR"/perf_summary.py \
        "$M1_LIB_DIR"/run-read-matrix.sh "$M1_LIB_DIR"/run-latency-matrix.sh \
        "$M1_LIB_DIR"/run-fio-brackets.sh "$M1_LIB_DIR"/run-perf-cells.sh \
        "$M1_LIB_DIR"/run-iotester-check.sh 2>/dev/null || true
    echo
    echo "## Engine configurations and disclosed asymmetries (F0)"
    echo "juring   : io_uring; SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN; registered file;"
    echo "           qd registered fixed buffers (memlock fallback recorded per cell)."
    echo "seastar  : default io_uring reactor backend; stock 200-entry ring; no ring flags,"
    echo "           no registered files/buffers; io_queue + futures in the path;"
    echo "           --smp 1 --cpuset $M1_CPU --memory 1G --lock-memory 0 (no hugepages)."
    echo "fio      : io_uring; 'matched' = --registerfiles=1 --fixedbufs=1 (M1 reference);"
    echo "           brackets: default (no registration, batch_submit=1), batch8/16/32,"
    echo "           sqthread_poll, libaio. fio forked job process (one job)."
    echo "pinning  : every engine pinned with taskset -c $M1_CPU, one thread/shard."
    echo "cache    : O_DIRECT everywhere; no cold-cache drop (no root)."
    echo "hugepages: $(grep HugePages_Total /proc/meminfo)"
    echo
    echo "## Session phases (order recorded for D6)"
    echo "1. run-read-matrix.sh      W1-W4 4K randread, 10 s warmup + 30 s, R=5 (juring/seastar/fio-matched)"
    echo "2. run-fio-brackets.sh     fio config brackets at QD64 (+QD128 default/libaio), R=3"
    echo "3. run-latency-matrix.sh   W15 (QD1) + W16 (QD64), 10 s warmup + 120 s, R=5, three-way"
    echo "4. run-perf-cells.sh       cycles/instructions/syscalls per op at QD1+QD64, R=3,"
    echo "                           fio comparator default + registration-matched"
    echo "5. run-seqread-matrix.sh   W9-W11 1 MiB seqread, 10 s warmup + 30 s, R=5, three-way"
    echo "6. run-iotester-check.sh   io_tester cross-check (writes 2x10 GiB setup fills on nvme-data)"
    echo
    echo "## Session start / end"
    echo "started: $(now_utc)"
} >"$OUT"
echo "manifest: $OUT"
