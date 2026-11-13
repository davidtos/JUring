#!/usr/bin/env bash
#
# bench/m1/run-locality-check.sh -- device-data-state control experiment.
#
# Question: the io_tester cross-check read its own FRESHLY-WRITTEN 10 GiB shard
# file and measured QD1 ~15.5k / QD64 ~374k IOPS, while seastar_file_bench on the
# AGED 10-day-old read file measures 11.6k / 318k. Is that difference data state
# (freshly written blocks still SLC-resident / freshly mapped) or a runner defect?
#
# Method: rest the device, write a NEW 10 GiB file on the same NVMe, then run the
# same read cells (QD1, QD64, juring/seastar/fio-matched) against
# (a) the aged read file and (b) the fresh file, back-to-back in the same session
# moment, into a separate results dir. Compare like-for-like: if the fresh file
# reproduces io_tester's numbers for all engines, the cross-check difference is
# data state, not the custom runner.
#
# Env: REST_SEC (300), WARMUP (2), DURATION (10), REPS (2), LOC_RESULTS.
# Results: bench/results/m1-2026-11-13-locality/{aged,fresh}/ + locality.log
set -euo pipefail
HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=bench/m1/lib.sh
source "$HERE/lib.sh"
cd "$M1_REPO_ROOT"

LOC=${LOC_RESULTS:-$M1_REPO_ROOT/bench/results/m1-2026-11-13-locality}
FRESH_DIR=$M1_REPO_ROOT/nvme-data/fresh-fill
FRESH=$FRESH_DIR/fresh_read.bin
REST_SEC=${REST_SEC:-300}
WARMUP=${WARMUP:-2}
DURATION=${DURATION:-10}
REPS=${REPS:-2}
LOG=$LOC/locality.log
mkdir -p "$LOC"

phase() { echo "[$(now_utc)] === $* ===" | tee -a "$LOG"; }

preflight
phase "rest ${REST_SEC}s so post-write background activity settles (D3)"
sleep "$REST_SEC"

phase "create fresh 10 GiB file on the data NVMe"
mkdir -p "$FRESH_DIR"
if [[ ! -f $FRESH ]]; then
    fallocate -l 10G -- "$FRESH"
    dd if=/dev/zero of="$FRESH" bs=1M count=10240 oflag=direct conv=notrunc status=none
    sync -f "$FRESH"
fi
stat -c '%n size=%s blocks=%b' "$FRESH" | tee -a "$LOG"

phase "aged-file cells (reference, same moment)"
ALLOW_RESIDUAL_LOAD=1 M1_RESULTS=$LOC/aged M1_RD_FILE="$M1_RD_FILE" \
    WARMUP=$WARMUP DURATION=$DURATION REPS=$REPS QDS="1 64" \
    "$HERE/run-read-matrix.sh" 2>&1 | tee -a "$LOG" | tail -8

phase "fresh-file cells (same protocol, fresh dataset)"
ALLOW_RESIDUAL_LOAD=1 M1_RESULTS=$LOC/fresh M1_RD_FILE="$FRESH" \
    WARMUP=$WARMUP DURATION=$DURATION REPS=$REPS QDS="1 64" \
    "$HERE/run-read-matrix.sh" 2>&1 | tee -a "$LOG" | tail -8

phase "summary (mean IOPS; n=reps)"
python3 - "$LOC" <<'PY' | tee -a "$LOG"
import csv
import glob
import statistics as st
import sys
from collections import defaultdict

loc = sys.argv[1]
res = defaultdict(list)
for p in glob.glob(f"{loc}/*/cell-meta.tsv"):
    arm = p.split("/")[-2]
    for r in csv.DictReader(open(p), delimiter="\t"):
        if r["status"] != "ok":
            continue
        res[(arm, r["qd"], r["engine"])].append(float(r["iops"]))
print(f"{'dataset':8} {'qd':>3} {'engine':14} {'n':>2} {'iops':>10}")
for k, v in sorted(res.items()):
    print(f"{k[0]:8} {k[1]:>3} {k[2]:14} {len(v):>2} {st.mean(v):>10.0f}")
print()
print("aged-vs-fresh ratio per (qd, engine):")
for (arm, qd, eng), v in sorted(res.items()):
    if arm != "aged":
        continue
    fv = res.get(("fresh", qd, eng))
    if fv and st.mean(v) > 0:
        print(f"  qd{qd:<4} {eng:14} fresh/aged = {st.mean(fv) / st.mean(v):.3f}")
PY

phase "locality check done"
