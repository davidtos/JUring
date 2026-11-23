#!/usr/bin/env python3
"""Generate the write-path SVG figures used in README.md and BENCHMARK.md.

Stdlib only, like the analyzers: the figures are plain SVG so they diff, scale and
carry their numbers as text. Data comes from a state-controlled write session
(`cell-meta.tsv` + 1 s device series, same steady-state rule as
`analyze_write_steady.py`) and, for the mode probe, from the JSON files written by
`probe-write-modes.sh`.

Figures written to --out-dir (default docs/figures):
  write-steady-parity.svg   sustained-state means with 95% CI, QD 64 / QD 128
  write-operating-modes.svg per-pass samples against the drive's two bands (x axis is
                            the local start time of each back-to-back pass)
  write-mode-probe.svg      fio batch-size sweep: the fast/slow flip is not batching

Usage:
    python3 bench/write/plot_write_modes.py \
        --session bench/results/write-3way-20261123-212038 \
        --gens g1,g2,g3,g4,g5 \
        --probe-dir bench/results/write-mode-probe-20261124 \
        --out-dir docs/figures
"""
import argparse
import csv
import datetime
import importlib.util
import json
import os
import sys
import zoneinfo

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "bench"))
from analysis import stats  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "frozen_write_state", os.path.join(REPO, "bench", "stepd", "analyze_write_state.py"))
aw = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(aw)

# The session timestamps are UTC; the campaign ran on this host, so the figures label
# a run group by its local (CET) start time. That keeps the x axis readable without the
# internal generation names (g1..g5) that only make sense next to the session directories.
CAMPAIGN_TZ = zoneinfo.ZoneInfo("Europe/Amsterdam")
CELLS = ("W7", "W8")
CELL_LABEL = {"W7": "QD 64 (W7)", "W8": "QD 128 (W8)"}
CELL_QD = {"W7": "64", "W8": "128"}
BS = 4096
STEADY_MAX_IOPS = 250_000.0
STEADY_BAND = (185_000, 205_000)
FAST_BAND = (300_000, 480_000)
ARMS = [
    ("juring", "std", "JUring", "#1f6feb", "solid"),
    ("fio-io_uring", "matched", "fio matched", "#e3742a", "solid"),
    ("fio-io_uring", "matchedbatch", "fio matchedbatch", "#b45309", "dashed"),
    ("seastar", "std", "Seastar", "#2ea043", "solid"),
]
PARITY_ARMS = [
    ("juring", "std", "JUring", "#1f6feb"),
    ("fio-io_uring", "matched", "fio matched", "#e3742a"),
    ("seastar", "std", "Seastar", "#2ea043"),
]
# Shared SVG primitives (the figures are hand-built so the repository stays stdlib-only).
from analysis import svg as _svg  # noqa: E402

FONT = _svg.FONT
INK = _svg.INK
MUTED = _svg.MUTED
GRID = _svg.GRID
esc = _svg.esc
text = _svg.text
line = _svg.line
rect = _svg.rect
circle = _svg.circle
polyline = _svg.polyline
svg = _svg.document


def load_meta(d):
    rows = {}
    with open(os.path.join(d, "cell-meta.tsv"), encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            rows[(r["case"], r["engine"], r["variant"], r["rep"])] = r
    return rows


def collect(session, gens):
    """(gen, cell, engine) -> list of measured runs; steady flag per run."""
    per = {}
    for g in gens:
        d = os.path.join(session, g)
        meta = load_meta(d)
        for cell in CELLS:
            for engine, variant, _label, _color, _style in ARMS:
                r = meta.get((cell, engine, variant, "1"))
                if r is None:
                    continue
                iops = float(r["iops"]) if r["status"] == "ok" else None
                steady = False
                if iops is not None and iops > 0:
                    stem = aw.stem(cell, engine, variant, "4k", CELL_QD[cell], 1)
                    c = aw.classify(aw.read_series(os.path.join(d, "series", stem + ".tsv")))
                    steady = bool(c["steady"]) and iops < STEADY_MAX_IOPS
                per.setdefault((g, cell, engine), []).append(
                    {"iops": iops, "steady": steady, "p99": float(r["p99_us"] or 0)})
    return per


def cell_start_times(session, gens):
    """(generation, cell) -> datetime of that cell's first measured run, campaign local time."""
    raw = {}
    for g in gens:
        meta = load_meta(os.path.join(session, g))
        for r in meta.values():
            ts = r["ts_utc"]
            if not ts:
                continue
            key = (g, r["case"])
            if key not in raw or ts < raw[key]:
                raw[key] = ts
    out = {}
    for key, ts in raw.items():
        dt = datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))
        out[key] = dt.astimezone(CAMPAIGN_TZ)
    return out


def steady_summary(per, gens, cell, engine):
    vals = []
    for g in gens:
        for run in per.get((g, cell, engine), []):
            if run["steady"]:
                vals.append(run["iops"])
    return stats.describe(vals)


def figure_parity(session, gens, per):
    W, H = 920, 500
    L, R, T, B = 90, 880, 100, 405
    ymax = 220_000
    body = []
    body.append(text(W / 2, 32, "4 KiB random write — sustained state",
                      size=19, anchor="middle", weight="bold"))
    body.append(text(W / 2, 54,
                      "all three engines sit inside the same ~2–3% band (mean ± 95% CI)",
                      size=13, anchor="middle", fill=MUTED))

    def y(v):
        return B - (v / ymax) * (B - T)

    body.append(rect(L, y(STEADY_BAND[1]), R - L, y(STEADY_BAND[0]) - y(STEADY_BAND[1]),
                     "#2ea043", opacity="0.10"))
    for tick in range(0, 220_001, 50_000):
        body.append(line(L, y(tick), R, y(tick), stroke=GRID))
        body.append(text(L - 10, y(tick) + 4, f"{tick // 1000}k", size=11, anchor="end", fill=MUTED))
    body.append(line(L, B, R, B, stroke=INK, width=1.2))

    centers = (300, 670)
    bar_w, gap = 58, 18
    for idx, cell in enumerate(CELLS):
        for j, (engine, _variant, _label, color) in enumerate(PARITY_ARMS):
            d = steady_summary(per, gens, cell, engine)
            if d["mean"] is None:
                continue
            x = centers[idx] - (3 * bar_w + 2 * gap) / 2 + j * (bar_w + gap)
            body.append(rect(x, y(d["mean"]), bar_w, B - y(d["mean"]), color, opacity="0.88"))
            body.append(text(x + bar_w / 2, y(d["mean"]) - 8, f"{d['mean'] / 1000:.1f}k",
                             size=12, anchor="middle", weight="bold"))
            if d["ci95_half"] is not None:
                top, bot = y(d["mean"] + d["ci95_half"]), y(d["mean"] - d["ci95_half"])
                body.append(line(x + bar_w / 2, top, x + bar_w / 2, bot, stroke=INK, width=1.2))
                body.append(line(x + bar_w / 2 - 7, top, x + bar_w / 2 + 7, top, stroke=INK, width=1.2))
                body.append(line(x + bar_w / 2 - 7, bot, x + bar_w / 2 + 7, bot, stroke=INK, width=1.2))
            body.append(text(x + bar_w / 2, B + 18, f"n={d['n']}", size=10,
                             anchor="middle", fill=MUTED))
        body.append(text(centers[idx], B + 44, CELL_LABEL[cell], size=14,
                         anchor="middle", weight="bold"))

    body.append(text(W / 2, 470,
                      "Near-empty second NVMe (nvme2n1, 6% used), 60 s measured windows, "
                      "400 GiB burn-in;  fio matched = batch-submit 1.",
                      size=11, anchor="middle", fill=MUTED))
    body.append(text(W / 2, 487, "No ranking is claimed: the intervals overlap and the drive's "
                                 "fast mode is disclosed in BENCHMARK.md.",
                      size=11, anchor="middle", fill=MUTED))
    # legend
    x = 90
    for _engine, _variant, label, color in PARITY_ARMS:
        body.append(rect(x, 72, 12, 12, color))
        body.append(text(x + 18, 82, label, size=12))
        x += 150
    body.append(rect(x, 72, 12, 12, "#2ea043", opacity="0.18"))
    body.append(text(x + 18, 82, "sustained band (~185–205k IOPS)", size=12))
    return svg(W, H, body)


def figure_operating_modes(gens, per, starts):
    W, H = 1060, 668
    body = [text(W / 2, 30, "4 KiB random write — the margins all three engines operate in",
                 size=19, anchor="middle", weight="bold"),
            text(W / 2, 52, "one point per 60 s measured window; every engine moves between the "
                            "same sustained and cache-assisted bands",
                 size=13, anchor="middle", fill=MUTED)]
    panels = [(80, 520), (570, 1010)]
    T, B = 140, 570
    ymax = 500_000
    date_label = ""
    if starts:
        date_label = min(starts.values()).strftime("%d %b")
    for (pl, pr), cell in zip(panels, CELLS):
        def y(v):
            return B - (v / ymax) * (B - T)

        labels = [starts[(g, cell)].strftime("%H:%M") if (g, cell) in starts else g for g in gens]
        body.append(rect(pl, y(FAST_BAND[1]), pr - pl, y(FAST_BAND[0]) - y(FAST_BAND[1]),
                         "#e3742a", opacity="0.10"))
        body.append(rect(pl, y(STEADY_BAND[1]), pr - pl,
                         y(STEADY_BAND[0]) - y(STEADY_BAND[1]), "#2ea043", opacity="0.16"))
        body.append(text(pl + 6, y(STEADY_BAND[1]) - 5, "sustained", size=10, fill="#1a7f37"))
        body.append(text(pl + 6, y(FAST_BAND[0]) + 12, "cache-assisted", size=10, fill="#b45309"))
        for tick in range(0, 500_001, 100_000):
            body.append(line(pl, y(tick), pr, y(tick), stroke=GRID))
            body.append(text(pl - 10, y(tick) + 4, f"{tick // 1000}k", size=10,
                             anchor="end", fill=MUTED))
        body.append(line(pl, B, pr, B, stroke=INK, width=1.2))
        body.append(text((pl + pr) / 2, T - 28, CELL_LABEL[cell], size=14,
                         anchor="middle", weight="bold"))
        n = len(gens)
        pad = 42
        xs = [pl + pad + i * (pr - pl - 2 * pad) / max(1, n - 1) for i in range(n)]
        for i in range(n):
            body.append(text(xs[i], B + 18, labels[i], size=11, anchor="middle", fill=MUTED))
        for engine, _variant, _label, color, style in ARMS:
            points = []
            for i, g in enumerate(gens):
                runs = per.get((g, cell, engine))
                if not runs or runs[0]["iops"] is None:
                    continue
                points.append((xs[i], y(runs[0]["iops"])))
            if len(points) > 1:
                body.append(polyline(points, color, width=1.8,
                                     dash="5 4" if style == "dashed" else None,
                                     opacity="0.85"))
            for (px, py) in points:
                body.append(circle(px, py, 3.6, color,
                                   stroke="#ffffff", width=1.1))
    # legend
    x = 80
    for _engine, _variant, label, color, style in ARMS:
        body.append(line(x, 74, x + 22, 74, stroke=color, width=2.2,
                         dash="5 4" if style == "dashed" else None))
        body.append(circle(x + 11, 74, 3.4, color))
        body.append(text(x + 28, 78, label, size=12))
        x += 190
    body.append(text(W / 2, 608,
                      f"measurement start, {date_label} (CET) — one column per back-to-back "
                      "pass over all four arms, ~17 min apart",
                      size=11, anchor="middle", fill=MUTED))
    body.append(text(W / 2, 632,
                      "Same file and 60 s windows; engine order rotated so no engine always "
                      "runs first.",
                      size=11, anchor="middle", fill=MUTED))
    body.append(text(W / 2, 649,
                      "The bands are the drive's modes, not engine groups: a point above the "
                      "sustained band is the drive absorbing writes into SLC, and all three "
                      "engines show it.",
                      size=11, anchor="middle", fill=MUTED))
    return svg(W, H, body)


def load_probe(probe_dir, batches):
    rounds = {}
    for name in ("r1", "r2"):
        vals = []
        for b in batches:
            path = os.path.join(probe_dir, f"{name}_batch{b}.json")
            with open(path, encoding="utf-8") as fh:
                doc = json.load(fh)
            vals.append(float(doc["jobs"][0]["write"]["iops"]))
        rounds[name] = vals
    return rounds


def figure_probe(rounds, batches):
    W, H = 840, 455
    L, R, T, B = 80, 800, 100, 350
    ymax = 350_000
    body = [text(W / 2, 30, "The fast windows are the drive, not fio's batching",
                 size=18, anchor="middle", weight="bold"),
            text(W / 2, 52, "same fio command, same file, same queue depth; only "
                            "--iodepth_batch_submit changes",
                 size=12, anchor="middle", fill=MUTED)]

    def y(v):
        return B - (v / ymax) * (B - T)

    body.append(rect(L, y(245_000), R - L, y(195_000) - y(245_000), "#1f6feb", opacity="0.08"))
    body.append(rect(L, y(330_000), R - L, y(290_000) - y(330_000), "#e3742a", opacity="0.10"))
    body.append(text(L, B + 62, "shaded bands: slow cluster ~200–245k (blue), "
                         "fast cluster ~290–325k (orange)",
                     size=10, anchor="start", fill=MUTED))
    for tick in range(0, 350_001, 50_000):
        body.append(line(L, y(tick), R, y(tick), stroke=GRID))
        body.append(text(L - 10, y(tick) + 4, f"{tick // 1000}k", size=10,
                         anchor="end", fill=MUTED))
    body.append(line(L, B, R, B, stroke=INK, width=1.2))
    xs = [L + 55 + i * (R - L - 110) / (len(batches) - 1) for i in range(len(batches))]
    for i, b in enumerate(batches):
        body.append(text(xs[i], B + 20, str(b), size=11, anchor="middle"))
    body.append(text((L + R) / 2, B + 40, "fio --iodepth_batch_submit", size=11,
                     anchor="middle", fill=MUTED))
    colors = {"r1": "#1f6feb", "r2": "#e3742a"}
    offsets = {"r1": -7, "r2": 7}
    for name in ("r1", "r2"):
        for i, val in enumerate(rounds[name]):
            cx, cy = xs[i] + offsets[name], y(val)
            body.append(circle(cx, cy, 5.2, colors[name], stroke="#ffffff", width=1.2))
            body.append(text(cx, cy - 11, f"{val / 1000:.0f}k", size=9, anchor="middle",
                             fill=colors[name]))
    body.append(circle(90, 78, 5, colors["r1"]))
    body.append(text(100, 82, "round 1", size=11))
    body.append(circle(180, 78, 5, colors["r2"]))
    body.append(text(190, 82, "round 2", size=11))
    body.append(text(W / 2, 424,
                      "Both rounds flip between the clusters at every batch size, including "
                      "batch=1 and batch=128.  --fixedbufs=0 and",
                      size=11, anchor="middle", fill=MUTED))
    body.append(text(W / 2, 441,
                      "--iodepth_batch_complete=128 controls gave the same flip (219k/301k and "
                      "241k/221k).",
                      size=11, anchor="middle", fill=MUTED))
    return svg(W, H, body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", required=True)
    ap.add_argument("--gens", required=True)
    ap.add_argument("--probe-dir", default="")
    ap.add_argument("--out-dir", default="docs/figures")
    ap.add_argument("--batches", default="1,8,16,32,64,128")
    args = ap.parse_args()
    gens = [g for g in args.gens.split(",") if g]
    os.makedirs(args.out_dir, exist_ok=True)
    per = collect(args.session, gens)
    batches = [int(b) for b in args.batches.split(",")]

    figs = {
        "write-steady-parity.svg": figure_parity(args.session, gens, per),
        "write-operating-modes.svg": figure_operating_modes(
            gens, per, cell_start_times(args.session, gens)),
    }
    if args.probe_dir and all(os.path.exists(os.path.join(
            args.probe_dir, f"{n}_batch{b}.json")) for n in ("r1", "r2") for b in batches):
        figs["write-mode-probe.svg"] = figure_probe(load_probe(args.probe_dir, batches), batches)
    for name, content in figs.items():
        path = os.path.join(args.out_dir, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
