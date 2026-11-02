#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

DATE="2026-11-02"
RESULTS_DIR="results/$DATE"
mkdir -p "$RESULTS_DIR"

cooldown() {
    local sec="${1:-10}"
    echo "[Cooldown] Sleeping ${sec}s for NVMe SLC buffer and thermal recovery..."
    sleep "$sec"
}

echo "=== Starting VirtualThreadBenchmark (Single File) ==="
./bench.sh --virtual --param distribution=SINGLE_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-single.json"
cooldown 5

echo "=== Starting VirtualThreadBenchmark (Multi File) ==="
./bench.sh --virtual --param distribution=MULTI_FILE --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-multi.json"
cooldown 5

echo "=== Starting VirtualThreadBenchmark (MaxPool 1024) ==="
./bench.sh --virtual --param distribution=SINGLE_FILE --param virtualThreads=1000 --max-pool-size --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/virtual-threads-maxpool1024.json"
cooldown 5

echo "=== Starting VirtualThreadBenchmark (Syscalls) ==="
./bench.sh --virtual --param distribution=SINGLE_FILE --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/virtual-threads-syscalls.json"
cooldown 5

echo "=== Starting VirtualThreadBenchmark (PerfNorm) ==="
./bench.sh --virtual --param distribution=SINGLE_FILE --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/virtual-threads-perfnorm.json"
cooldown 10

echo "=== Starting Buffered I/O Benchmarks (Throughput) ==="
./bench.sh --buffered --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/buffered.json"
cooldown 5

echo "=== Starting Buffered I/O Benchmarks (Syscalls) ==="
./bench.sh --buffered --syscalls --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/buffered-syscalls.json"
cooldown 5

echo "=== Starting Buffered I/O Benchmarks (PerfNorm) ==="
./bench.sh --buffered --perfnorm --f 1 --wi 1 --i 2 --w 2 --r 3 --rff "$RESULTS_DIR/buffered-perfnorm.json"
cooldown 10

echo "=== Starting DirectLatencyBenchmark (Latency) ==="
./bench.sh --latency --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/latency.json"
cooldown 5

echo "=== Starting FfmInvocationBenchmark (Panama) ==="
./bench.sh --include 'FfmInvocationBenchmark' --f 3 --wi 2 --i 3 --w 3 --r 3 --rff "$RESULTS_DIR/ffm.json"

echo "=== ALL REMAINING BENCHMARKS COMPLETE ==="
