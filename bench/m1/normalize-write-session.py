#!/usr/bin/env python3
"""Normalize the M1 write block's per-cell JSON into one CSV with consistent units.

Why this exists: `bench/write/run-write-cells.sh` records fio's clat percentiles
in NANOSECONDS in its `cell-meta.tsv` p99_us column (fio clat_ns is ns; the
script only converts for juring/seastar). This script re-reads the raw engine
JSON so every latency is in microseconds and every quantity has one definition:

  juring / seastar : runs[0].latency_us (already us)
  fio              : jobs[].{read,write}.clat_ns.percentile[...] / 1000 (ns -> us)

Usage:
  python3 bench/m1/normalize-write-session.py [--dir bench/results/m1-2026-11-13-write]
Writes <dir>/aggregate/cells-normalized.csv (+ prints a per-cell table).
Stdlib only.
"""
import argparse
import csv
import glob
import json
import os
import sys


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return -1.0


def from_engine_json(d, engine):
    runs = d.get("runs") or []
    if not runs:
        return None
    r = runs[0]
    lat = r.get("latency_us") or {}
    return {
        "iops": num(r.get("iops")),
        "ops": num(r.get("ops", r.get("ops_total"))),
        "bytes": num(r.get("bytes", r.get("bytes_total"))),
        "errors": num(r.get("errors_total", r.get("errors", 0))),
        "p50_us": num(lat.get("p50")),
        "p95_us": num(lat.get("p95")),
        "p99_us": num(lat.get("p99")),
        "p999_us": num(lat.get("p999")),
        "max_us": num(lat.get("max")),
        "lat_source": "engine before-submit->completion",
    }


def from_fio_json(d):
    jobs = d.get("jobs") or []
    if not jobs:
        return None
    j = jobs[0]
    iops = 0.0
    ops = 0.0
    nbytes = 0.0
    best = None
    for rw in ("read", "write"):
        b = j.get(rw) or {}
        i = num(b.get("iops"))
        if i <= 0:
            continue
        iops += i
        ops += num(b.get("total_ios"))
        nbytes += num(b.get("io_bytes"))
        cl = b.get("clat_ns") or {}
        pct = cl.get("percentile") or {}

        def us(key):
            v = num(pct.get(key))
            return v / 1000.0 if v > 0 else -1.0

        cand = {
            "p50_us": us("50.000000"),
            "p95_us": us("95.000000"),
            "p99_us": us("99.000000"),
            "p999_us": us("99.900000"),
            "max_us": num(cl.get("max")) / 1000.0,
        }
        if best is None or (cand["p99_us"] >= 0 and best["p99_us"] < 0):
            best = cand
    if best is None:
        return None
    return {
        "iops": iops,
        "ops": ops,
        "bytes": nbytes,
        "errors": num(j.get("error", 0)),
        **best,
        "lat_source": "fio clat (completion latency; ns->us)",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="bench/results/m1-2026-11-13-write")
    args = ap.parse_args()
    cells_dir = os.path.join(args.dir, "cells")
    out_dir = os.path.join(args.dir, "aggregate")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "cells-normalized.csv")

    rows = []
    for p in sorted(glob.glob(os.path.join(cells_dir, "*.json"))):
        stem = os.path.basename(p)[:-5]
        parts = stem.split("__")
        if len(parts) < 7:
            continue
        case, engine, variant = parts[0], parts[1], parts[2]
        qd = parts[4].replace("qd", "")
        rep = parts[6].replace("r", "")
        try:
            with open(p) as f:
                d = json.load(f)
        except Exception as e:  # noqa: BLE001
            print(f"skip (unparsable): {p}: {e}", file=sys.stderr)
            continue
        rec = from_fio_json(d) if engine.startswith("fio") else from_engine_json(d, engine)
        if rec is None:
            print(f"skip (no runs/jobs): {p}", file=sys.stderr)
            continue
        rows.append({"case": case, "engine": engine, "variant": variant, "qd": qd,
                     "rep": rep, "source_json": os.path.relpath(p, args.dir), **rec})

    fields = ["case", "engine", "variant", "qd", "rep", "iops", "ops", "bytes",
              "errors", "p50_us", "p95_us", "p99_us", "p999_us", "max_us",
              "lat_source", "source_json"]
    with open(out, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fields})

    print(f"{'case':4} {'engine':14} {'variant':8} {'rep':>3} {'iops':>10} "
          f"{'p50us':>8} {'p99us':>9} {'p999us':>10} {'maxus':>10}")
    for r in rows:
        print(f"{r['case']:4} {r['engine']:14} {r['variant']:8} {r['rep']:>3} "
              f"{r['iops']:>10.0f} {r['p50_us']:>8.1f} {r['p99_us']:>9.1f} "
              f"{r['p999_us']:>10.1f} {r['max_us']:>10.1f}")
    print(f"\nwrote {out} ({len(rows)} rows)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
