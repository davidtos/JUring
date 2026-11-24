#!/usr/bin/env bash
# bench/write/probe-write-modes.sh -- show that the drive's write throughput mode
# flips independently of fio's submission batching.
#
# On the near-empty second NVMe, 4 KiB random write throughput alternates between
# ~200-220k and ~300-325k IOPS on a tens-of-seconds timescale. Running the SAME fio
# command with --iodepth_batch_submit from 1 to 128 shows the flip is independent of
# batch size (and of --fixedbufs / --iodepth_batch_complete): it is the drive's
# write-cache/GC mode, not an engine or configuration advantage. This is the evidence
# behind the write-mode note in BENCHMARK.md.
#
# Usage:
#   bash bench/write/probe-write-modes.sh
#   WRITE_FILE=... OUTPUT_DIR=... ROUNDS=2 RUNTIME=15 RAMP=5 QD=128 CPU=2 \
#       bash bench/write/probe-write-modes.sh
set -uo pipefail
cd -- "$(dirname -- "$0")/../.."
FILE=${WRITE_FILE:-nvme-write/write_files/single_file_bench_w.bin}
OUT=${OUTPUT_DIR:-bench/results/write-mode-probe-$(date -u +%Y%m%d-%H%M%S)}
BATCHES=${BATCHES:-"1 8 16 32 64 128"}
ROUNDS=${ROUNDS:-2}
RUNTIME=${RUNTIME:-15}
RAMP=${RAMP:-5}
QD=${QD:-128}
CPU=${CPU:-2}
mkdir -p "$OUT"
run() { # name batch extra...
  local name=$1 batch=$2; shift 2
  local json=$OUT/$name.json
  taskset -c "$CPU" fio --name="$name" --filename="$FILE" --ioengine=io_uring --direct=1 \
    --registerfiles=1 --fixedbufs=1 --rw=randwrite --bs=4k --iodepth="$QD" --numjobs=1 \
    --runtime="$RUNTIME" --ramp_time="$RAMP" --time_based --group_reporting=1 --randrepeat=1 \
    --clocksource=clock_gettime --iodepth_batch_submit="$batch" "$@" \
    --output-format=json --output="$json" >/dev/null 2>"$OUT/$name.err"
  python3 - "$json" "$name" <<'PY'
import json,sys
d=json.load(open(sys.argv[1])); j=d['jobs'][0]
print(f"{sys.argv[2]:28s} iops={j['write']['iops']:12,.0f} bw_mibs={j['write']['bw_bytes']/1048576:9,.1f} err={j.get('error',0)}")
PY
}
for round in $(seq 1 "$ROUNDS"); do
  echo "== round $round =="
  for b in $BATCHES; do run "r${round}_batch${b}" "$b"; done
  run "r${round}_batch128_regfile_only" 128 --fixedbufs=0
  run "r${round}_batch128_batchcomplete128" 128 --iodepth_batch_complete=128
done
