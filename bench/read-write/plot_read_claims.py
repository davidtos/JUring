#!/usr/bin/env python3
"""Generate the read-path SVG figures used in README.md and BENCHMARK.md.

Stdlib only. Reads a read/write measurement session (`cell-meta.tsv` + per-rep JSON in `cells/`)
and writes:
  read-throughput.svg  4 KiB random read IOPS with 95% CI, QD 64 / QD 128
  read-latency.svg     p50 / p99 latency per engine and queue depth
  read-ratios.svg      paired JUring/fio and JUring/Seastar ratios with 90% CI

The session directory comes from the release archive; the same protocol (5
reps, 10 s warmup + 30 s measured, engine order rotated) is documented in
BENCHMARK.md.

Usage:
    python3 bench/read-write/plot_read_claims.py \
        --session bench/results/<read-session> \
        --out-dir docs/figures
"""
import argparse
import csv
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "bench"))
from analysis import cells as cellmap  # noqa: E402
from analysis import stats  # noqa: E402
from analysis import svg as _svg  # noqa: E402

text = _svg.text
line = _svg.line
rect = _svg.rect
circle = _svg.circle
svg = _svg.document
error_bar = _svg.error_bar
INK = _svg.INK
MUTED = _svg.MUTED
GRID = _svg.GRID
COLORS = dict(_svg.COLORS)
COLORS["fio-io_uring"] = COLORS["fio"]

# canonical cells plus a human label for the axis label (no W# codes in the SVG)
CASES = [
    ("randread-4k-qd64", "64", "4 KiB random read, 64 in flight"),
    ("randread-4k-qd128", "128", "4 KiB random read, 128 in flight"),
]
ENGINES = [
    ("juring", "std", "JUring"),
    ("fio-io_uring", "matched", "fio matched"),
    ("seastar", "std", "Seastar"),
]


def fnum(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def load_meta(session):
    with open(os.path.join(session, "cell-meta.tsv"), encoding="utf-8") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def latency_from_json(path):
    """p50/p99 in microseconds for a harness JSON or a fio JSON."""
    if path is None:
        return None, None
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    if "aggregate" in doc:
        lat = doc["aggregate"].get("latency_us") or {}
        return fnum(lat.get("p50")), fnum(lat.get("p99"))
    job = (doc.get("jobs") or [{}])[0]
    section = job.get("read") or {}
    pct = (section.get("clat_ns") or {}).get("percentile") or {}
    p50 = fnum(pct.get("50.000000") or pct.get("50.0"))
    p99 = fnum(pct.get("99.000000") or pct.get("99.0"))
    return ((p50 / 1000.0) if p50 is not None else None,
            (p99 / 1000.0) if p99 is not None else None)


def cell_json(session, case, engine, variant, bs, qd, rep):
    """JSON for one cell, accepting a legacy W# filename from an unmigrated session."""
    for name in cellmap.aliases(case):
        path = os.path.join(
            session, "cells",
            f"{name}__{engine}__{variant}__bs{bs}__qd{qd}__t1__r{rep}.json")
        if os.path.exists(path):
            return path
    return None


def collect(session):
    data = {}
    for row in load_meta(session):
        case = cellmap.slug(row["case"])
        if case not in {c for c, _qd, _l in CASES}:
            continue
        engine = row["engine"]
        variant = row["variant"]
        rep = int(row["rep"])
        path = cell_json(session, case, engine, variant, row["bs"], row["qd"], rep)
        p50, p99 = latency_from_json(path) if path else (None, None)
        iops = fnum(row["iops"]) if row["status"] == "ok" else None
        data.setdefault((case, engine, variant), []).append(
            {"rep": rep, "iops": iops, "p50": p50, "p99": p99})
    for rows in data.values():
        rows.sort(key=lambda r: r["rep"])
    return data


def describe(data, case, engine, variant, field):
    vals = [r[field] for r in data.get((case, engine, variant), [])
            if r.get(field) is not None]
    return stats.describe(vals)


def ratio(data, case, left, right):
    lrows = data.get((case, *left), [])
    rrows = data.get((case, *right), [])
    lmap = {r["rep"]: r for r in lrows}
    rmap = {r["rep"]: r for r in rrows}
    left_reps = [{"rep_index": rep, "iops": lmap[rep]["iops"]}
                 for rep in sorted(set(lmap) & set(rmap))
                 if lmap[rep]["iops"] and rmap[rep]["iops"]]
    right_reps = [{"rep_index": rep, "iops": rmap[rep]["iops"]}
                  for rep in sorted(set(lmap) & set(rmap))
                  if lmap[rep]["iops"] and rmap[rep]["iops"]]
    return stats.ratio_stats(left_reps, right_reps)


def fig_throughput(data):
    W, H = 920, 540
    L, R, T, B = 90, 880, 100, 390
    ymax = 450_000
    body = [text(W / 2, 32, "4 KiB random read — JUring vs fio vs Seastar",
                 size=19, anchor="middle", weight="bold"),
            text(W / 2, 54, "bars = mean IOPS, whiskers = 95% CI, 5 repetitions, "
                            "engine order rotated per repetition",
                 size=13, anchor="middle", fill=MUTED)]

    def y(v):
        return B - (v / ymax) * (B - T)

    for tick in range(0, 450_001, 100_000):
        body.append(line(L, y(tick), R, y(tick), stroke=GRID))
        body.append(text(L - 10, y(tick) + 4, f"{tick // 1000}k", size=11,
                         anchor="end", fill=MUTED))
    body.append(line(L, B, R, B, stroke=INK, width=1.2))
    centers = (300, 670)
    bar_w, gap = 58, 18
    for idx, (case, _qd, label) in enumerate(CASES):
        for j, (engine, variant, _engine_label) in enumerate(ENGINES):
            d = describe(data, case, engine, variant, "iops")
            if d["mean"] is None:
                continue
            x = centers[idx] - (3 * bar_w + 2 * gap) / 2 + j * (bar_w + gap)
            body.append(rect(x, y(d["mean"]), bar_w, B - y(d["mean"]),
                             COLORS[engine], opacity="0.88"))
            body.append(text(x + bar_w / 2, y(d["mean"]) - 8, f"{d['mean'] / 1000:.0f}k",
                             size=12, anchor="middle", weight="bold"))
            if d["ci95_half"] is not None:
                body += error_bar(x + bar_w / 2, y(d["mean"] + d["ci95_half"]),
                                  y(d["mean"] - d["ci95_half"]))
            body.append(text(x + bar_w / 2, B + 18, f"n={d['n']}", size=10,
                             anchor="middle", fill=MUTED))
        body.append(text(centers[idx], B + 44, label, size=13, anchor="middle",
                         weight="bold"))
        jf = ratio(data, case, ("juring", "std"), ("fio-io_uring", "matched"))
        js = ratio(data, case, ("juring", "std"), ("seastar", "std"))
        body.append(text(centers[idx], B + 68,
                         f"JUring/fio {jf['mean']:.3f}  [90% {jf['ci90_low']:.3f}–{jf['ci90_high']:.3f}]",
                         size=11, anchor="middle", fill=COLORS["fio"]))
        body.append(text(centers[idx], B + 86,
                         f"JUring/Seastar {js['mean']:.3f}  [90% {js['ci90_low']:.3f}–{js['ci90_high']:.3f}]",
                         size=11, anchor="middle", fill=COLORS["seastar"]))
    x = 90
    for engine, _variant, label in ENGINES:
        body.append(rect(x, 72, 12, 12, COLORS[engine]))
        body.append(text(x + 18, 82, label, size=12))
        x += 150
    body.append(text(W / 2, 508,
                     "Aged 10 GiB file, O_DIRECT, pinned CPU 2; fio is registration-matched "
                     "(fixed file + fixed buffers).",
                     size=11, anchor="middle", fill=MUTED))
    body.append(text(W / 2, 525,
                     "fio cannot set JUring's taskrun flags or fuse submits, so this is a "
                     "capability comparison, not an equal-config race.",
                     size=11, anchor="middle", fill=MUTED))
    return svg(W, H, body)


def fig_latency(data):
    W, H = 940, 500
    L, R, T, B = 90, 900, 100, 380
    ymax = 1000.0
    groups = [(CASES[0][0], "p50", "64 in flight p50"),
              (CASES[0][0], "p99", "64 in flight p99"),
              (CASES[1][0], "p50", "128 in flight p50"),
              (CASES[1][0], "p99", "128 in flight p99")]
    body = [text(W / 2, 32, "4 KiB random read latency — p50 and p99",
                 size=19, anchor="middle", weight="bold"),
            text(W / 2, 54, "mean of the per-repetition percentiles; lower is better",
                 size=13, anchor="middle", fill=MUTED)]

    def y(v):
        return B - (v / ymax) * (B - T)

    for tick in range(0, 1001, 200):
        body.append(line(L, y(tick), R, y(tick), stroke=GRID))
        body.append(text(L - 10, y(tick) + 4, f"{tick:.0f}", size=11,
                         anchor="end", fill=MUTED))
    body.append(line(L, B, R, B, stroke=INK, width=1.2))
    body.append(text(64, 70, "µs", size=11, fill=MUTED))
    bar_w, gap = 44, 12
    group_w = 3 * bar_w + 2 * gap
    usable = (R - L - 20 - group_w) / (len(groups) - 1)
    for i, (case, field, label) in enumerate(groups):
        cx = L + 10 + group_w / 2 + i * usable
        for j, (engine, variant, _engine_label) in enumerate(ENGINES):
            d = describe(data, case, engine, variant, field)
            if d["mean"] is None:
                continue
            x = cx - group_w / 2 + j * (bar_w + gap)
            body.append(rect(x, y(d["mean"]), bar_w, B - y(d["mean"]),
                             COLORS[engine], opacity="0.88"))
            body.append(text(x + bar_w / 2, y(d["mean"]) - 6, f"{d['mean']:.0f}",
                             size=10, anchor="middle", weight="bold"))
        body.append(text(cx, B + 20, label, size=11, anchor="middle"))
    x = 90
    for engine, _variant, label in ENGINES:
        body.append(rect(x, 72, 12, 12, COLORS[engine]))
        body.append(text(x + 18, 82, label, size=12))
        x += 150
    body.append(text(W / 2, 470,
                     "JUring has the lowest median at both depths; its QD 128 p99 is above "
                     "fio's, which is reported rather than smoothed away.",
                     size=11, anchor="middle", fill=MUTED))
    return svg(W, H, body)


def fig_ratios(data):
    W, H = 880, 460
    L, R, T, B = 250, 830, 110, 360
    xmin, xmax = 0.92, 1.32
    rows = [
        (CASES[0][0], ("juring", "std"), ("fio-io_uring", "matched"),
         "64 in flight  JUring/fio", COLORS["fio"]),
        (CASES[0][0], ("juring", "std"), ("seastar", "std"),
         "64 in flight  JUring/Seastar", COLORS["seastar"]),
        (CASES[1][0], ("juring", "std"), ("fio-io_uring", "matched"),
         "128 in flight  JUring/fio", COLORS["fio"]),
        (CASES[1][0], ("juring", "std"), ("seastar", "std"),
         "128 in flight  JUring/Seastar", COLORS["seastar"]),
    ]
    body = [text(W / 2, 32, "Paired read ratios — where the intervals sit",
                 size=19, anchor="middle", weight="bold"),
            text(W / 2, 54, "ratio > 1 means JUring faster; whiskers = 90% CI; "
                            "shaded band = ±5% parity",
                 size=13, anchor="middle", fill=MUTED)]

    def x(v):
        return L + (v - xmin) / (xmax - xmin) * (R - L)

    band_y0, band_y1 = T - 30, B + 10
    body.append(rect(x(0.95), band_y0, x(1.05) - x(0.95), band_y1 - band_y0,
                     "#2ea043", opacity="0.12"))
    body.append(line(x(1.0), band_y0, x(1.0), band_y1, stroke=MUTED, width=1.2,
                     dash="4 3"))
    body.append(text(x(1.0), T - 40, "parity", size=11, anchor="middle", fill="#1a7f37"))
    for tick in (0.95, 1.0, 1.05, 1.1, 1.2, 1.3):
        body.append(line(x(tick), B, x(tick), B + 5, stroke=INK))
        body.append(text(x(tick), B + 20, f"{tick:.2f}", size=10, anchor="middle",
                         fill=MUTED))
    for i, (case, left, right, label, color) in enumerate(rows):
        st = ratio(data, case, left, right)
        cy = T + 30 + i * 58
        body.append(text(L - 20, cy + 4, label, size=12, anchor="end"))
        body.append(line(L - 8, cy, R, cy, stroke=GRID))
        if st.get("ci90_low") is not None:
            body.append(line(x(st["ci90_low"]), cy, x(st["ci90_high"]), cy,
                             stroke=color, width=2.2))
            body.append(line(x(st["ci90_low"]), cy - 6, x(st["ci90_low"]), cy + 6,
                             stroke=color, width=1.4))
            body.append(line(x(st["ci90_high"]), cy - 6, x(st["ci90_high"]), cy + 6,
                             stroke=color, width=1.4))
        body.append(circle(x(st["mean"]), cy, 5.4, color, stroke="#ffffff", width=1.2))
        body.append(text(x(st["mean"]), cy - 14, f"{st['mean']:.3f}", size=10,
                         anchor="middle", fill=color, weight="bold"))
    body.append(text(W / 2, 432,
                     "The QD 128 JUring/fio interval sits entirely above the parity band; "
                     "the other intervals overlap it.",
                     size=11, anchor="middle", fill=MUTED))
    return svg(W, H, body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", required=True)
    ap.add_argument("--out-dir", default="docs/figures")
    args = ap.parse_args()
    data = collect(args.session)
    os.makedirs(args.out_dir, exist_ok=True)
    figures = {
        "read-throughput.svg": fig_throughput(data),
        "read-latency.svg": fig_latency(data),
        "read-ratios.svg": fig_ratios(data),
    }
    for name, content in figures.items():
        path = os.path.join(args.out_dir, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
