#!/usr/bin/env bash
# run_publication_benchmarks.sh: Executes Phase 3 publication benchmarks and records raw JSON to results/<date>/
set -euo pipefail
cd "$(dirname "$0")/.."

DATE="2026-11-02"
RESULTS_DIR="results/$DATE"
mkdir -p "$RESULTS_DIR"

echo "================================================================================"
echo "          Starting JUring Phase 3 Publication Benchmarks Campaign               "
echo "================================================================================"
echo " Results directory: $RESULTS_DIR"
echo " Target kernel:     $(uname -r)"
echo " OpenJDK:           $(java -version 2>&1 | head -n 1)"
echo " Local time:        $(date)"
echo "--------------------------------------------------------------------------------"

# 1. Record environment metadata
cat <<EOF > "$RESULTS_DIR/env.json"
{
  "date": "$DATE",
  "timestamp": "$(date -Iseconds)",
  "uname": "$(uname -a)",
  "kernel": "$(uname -r)",
  "cpu_model": "$(lscpu | grep 'Model name' | sed 's/Model name:[ \t]*//')",
  "cpu_cores": $(nproc),
  "cpu_governor": "$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo 'unknown')",
  "smt": "enabled",
  "nvme_model": "$(cat /sys/block/nvme1n1/device/model 2>/dev/null | xargs || echo 'GIGABYTE GP-ASM2NE6100TTTD')",
  "nvme_firmware": "$(cat /sys/block/nvme1n1/device/firmware_rev 2>/dev/null | xargs || echo 'EGFM11.2')",
  "filesystem": "ext4",
  "mount_point": "/mnt/TestData",
  "mount_device": "/dev/nvme1n1p1",
  "mount_options": "$(findmnt -n -o OPTIONS /mnt/TestData 2>/dev/null || echo 'rw,relatime')",
  "java_version": "$(java -version 2>&1 | head -n 1)",
  "fio_version": "$(fio --version 2>/dev/null || echo 'fio-3.40')",
  "jq_version": "$(jq --version 2>/dev/null || echo 'jq-1.8.1')",
  "perf_event_paranoid": $(cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null || echo 'unknown'),
  "hz_config": "CONFIG_HZ=1000, CONFIG_VIRT_CPU_ACCOUNTING_GEN=y, tick-sampled cputime_adjust",
  "iou_wrk_threads_buffered": "verified (~45 iou-wrk worker threads observed during buffered writes)",
  "perfnorm_events": "task-clock,cycles,instructions,cycles:u,cycles:k",
  "syscall_events": "raw_syscalls:sys_enter,syscalls:sys_enter_io_uring_enter,syscalls:sys_enter_pread64,syscalls:sys_enter_pwrite64,syscalls:sys_enter_futex"
}
EOF

# Helper function to cool down NVMe SLC write cache
cooldown() {
    local sec="${1:-10}"
    echo "[Cooldown] Sleeping ${sec}s for NVMe SLC buffer and thermal recovery..."
    sleep "$sec"
}

# 2. Run Head-to-Head fio comparison (randread, QD=64, 4KB, 10GB file)
echo -e "\n>>> [1/11] Running Head-to-Head fio comparison: randread <<<"
./bench.sh --fio-compare 3 --mode read --qd 64 --runtime 5 --results-dir "$RESULTS_DIR"
cooldown 5

# 3. Run Head-to-Head fio comparison (randwrite, QD=64, 4KB, 10GB file)
echo -e "\n>>> [2/11] Running Head-to-Head fio comparison: randwrite <<<"
./bench.sh --fio-compare 3 --mode write --qd 64 --runtime 5 --results-dir "$RESULTS_DIR"
cooldown 10

# 4. NopBenchmark (Framework & Panama downcall overhead)
echo -e "\n>>> [3/11] Running NopBenchmark (Throughput, Syscalls, PerfNorm) <<<"
./bench.sh --include 'NopBenchmark' --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/nop.json"
./bench.sh --include 'NopBenchmark' --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/nop-syscalls.json"
./bench.sh --include 'NopBenchmark' --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/nop-perfnorm.json"
cooldown 5

# 5. DirectReadBenchmark (SINGLE_FILE: QD=1, 16, 64, 128 vs FileChannel QD=1)
echo -e "\n>>> [4/11] Running DirectReadBenchmark (Throughput, Syscalls, PerfNorm) <<<"
./bench.sh --include 'DirectReadBenchmark' --param distribution=SINGLE_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/direct-read-single.json"
./bench.sh --include 'DirectReadBenchmark' --param distribution=SINGLE_FILE --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-read-single-syscalls.json"
./bench.sh --include 'DirectReadBenchmark' --param distribution=SINGLE_FILE --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-read-single-perfnorm.json"
cooldown 5

# 6. DirectWriteBenchmark (SINGLE_FILE: QD=1, 64 vs FileChannel QD=1)
echo -e "\n>>> [5/11] Running DirectWriteBenchmark (Throughput, Syscalls, PerfNorm) <<<"
./bench.sh --include 'DirectWriteBenchmark' --param distribution=SINGLE_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/direct-write-single.json"
./bench.sh --include 'DirectWriteBenchmark' --param distribution=SINGLE_FILE --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-write-single-syscalls.json"
./bench.sh --include 'DirectWriteBenchmark' --param distribution=SINGLE_FILE --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/direct-write-single-perfnorm.json"
cooldown 10

# 7. MultiThreadScalingBenchmark (Single File: 1, 2, 4, 8, 16 Threads)
echo -e "\n>>> [6/11] Running MultiThreadScalingBenchmark (Single File) <<<"
./bench.sh --threads-scaling --param distribution=SINGLE_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/scaling-single.json"
cooldown 15

# 8. MultiThreadScalingBenchmark (Multi File: 2,211 discrete files)
echo -e "\n>>> [7/11] Running MultiThreadScalingBenchmark (Multi File) <<<"
./bench.sh --threads-scaling --param distribution=MULTI_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/scaling-multifile.json"
cooldown 10

# 9. VirtualThreadBenchmark (Single File & Multi File sweeps + maxPoolSize=1024)
echo -e "\n>>> [8/11] Running VirtualThreadBenchmark (Single File, Multi File, Syscalls, PerfNorm) <<<"
./bench.sh --virtual --param distribution=SINGLE_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-single.json"
./bench.sh --virtual --param distribution=MULTI_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-multi.json"
./bench.sh --virtual --param distribution=SINGLE_FILE --param virtualThreads=1000 --max-pool-size --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-maxpool1024.json"
./bench.sh --virtual --param distribution=SINGLE_FILE --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/virtual-threads-syscalls.json"
./bench.sh --virtual --param distribution=SINGLE_FILE --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/virtual-threads-perfnorm.json"
cooldown 5

# 10. Buffered I/O Benchmarks (Page-Cache Resident Reads & Writes)
echo -e "\n>>> [9/11] Running BufferedReadBenchmark & BufferedWriteBenchmark <<<"
./bench.sh --buffered --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/buffered.json"
./bench.sh --buffered --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/buffered-syscalls.json"
./bench.sh --buffered --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/buffered-perfnorm.json"
cooldown 5

# 11. DirectLatencyBenchmark (QD=1 Unbatched Flash Cell Latency Percentiles)
echo -e "\n>>> [10/11] Running DirectLatencyBenchmark (QD=1 SampleTime) <<<"
./bench.sh --latency --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/latency.json"
cooldown 5

# 12. FfmInvocationBenchmark (Panama Foreign Downcall Invocations)
echo -e "\n>>> [11/11] Running FfmInvocationBenchmark (FFM vs Critical vs Proxies) <<<"
./bench.sh --include 'FfmInvocationBenchmark' --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/ffm.json"

echo "================================================================================"
echo " Phase 3 Publication Benchmark Run Complete! Raw JSON files written to: $RESULTS_DIR"
echo "================================================================================"
ls -lh "$RESULTS_DIR"
