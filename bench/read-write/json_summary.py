#!/usr/bin/env python3
"""One-line numeric summary of a benchmark JSON report.

Usage: json_summary.py <engine> <path>

Prints "<ops> <iops> <p99_us> <bytes>" (-1 for missing values).  The engine is
"juring", "seastar" or anything starting with "fio" (fio JSON).  Reads the raw
engine JSON; used by bench/read-write/lib.sh to fill cell-meta.tsv.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from analysis import jsonio  # noqa: E402


def num(value):
    """float(value), or -1.0 when absent/unparsable."""
    v = jsonio.as_float(value)
    return -1.0 if v is None else v


def fio_summary(d):
    jobs = d.get("jobs") or []
    if not jobs:
        return -1, -1.0, -1.0, -1
    j = jobs[0]
    ops = 0.0
    iops = 0.0
    nbytes = 0.0
    p99 = -1.0
    for rw in ("read", "write"):
        b = j.get(rw) or {}
        nbytes += max(0.0, num(b.get("io_bytes")))
        i = num(b.get("iops"))
        if i > 0:
            iops += i
            ops += max(0.0, num(b.get("total_ios")))
            pct = (b.get("clat_ns") or {}).get("percentile") or {}
            # fio clat_ns is in nanoseconds
            v = num(pct.get("99.000000"))
            if v > 0:
                p99 = max(p99, v / 1000.0)
    return int(ops), iops, p99, int(nbytes)


def engine_summary(d):
    runs = d.get("runs") or []
    agg = d.get("aggregate") or {}
    r = runs[0] if runs else {}
    ops = num(r.get("ops"))
    if ops < 0:
        ops = num(r.get("ops_total"))
    if ops < 0:
        ops = num(agg.get("ops_total"))
    iops = num(r.get("iops"))
    if iops < 0:
        iops = num((agg.get("iops") or {}).get("mean"))
    lat = r.get("latency_us") or {}
    if not isinstance(lat, dict):
        lat = {}
    p99 = num(lat.get("p99"))
    if p99 < 0:
        la = agg.get("latency_us") or {}
        p99 = num(la.get("p99")) if isinstance(la, dict) else -1.0
    nbytes = num(r.get("bytes"))
    if nbytes < 0:
        nbytes = num(r.get("bytes_total"))
    if nbytes < 0:
        nbytes = num(agg.get("bytes_total"))
    return int(ops), iops, p99, int(nbytes)


def main():
    if len(sys.argv) != 3:
        print("usage: json_summary.py <engine> <json-path>", file=sys.stderr)
        return 2
    engine, path = sys.argv[1], sys.argv[2]
    d = jsonio.load_json(path)
    if d is None:
        print("-1 -1 -1 -1")
        return 0
    if engine.startswith("fio"):
        ops, iops, p99, nbytes = fio_summary(d)
    else:
        ops, iops, p99, nbytes = engine_summary(d)
    print(f"{ops} {iops:.3f} {p99:.3f} {nbytes}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
