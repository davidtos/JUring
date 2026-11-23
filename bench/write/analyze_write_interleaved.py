#!/usr/bin/env python3
"""Analyze the interleaved write bracket (docs/write-state-interleaved-protocol.md).

The bracket alternates JUring / fio-matched / Seastar in short 15 s warmup +
30 s measured cycles, so every engine samples the same drifting device state.
Paired ratios are computed per cycle. The frozen state-controlled gate
(bench/stepd/analyze_write_state.py) does not apply here and is not used.

Usage:
    python3 bench/write/analyze_write_interleaved.py --session DIR --out DIR
"""
import argparse
import csv
import json
import os
import statistics
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "bench"))
from analysis import stats  # noqa: E402

CELLS = ("W7", "W8")
CELL_QD = {"W7": "64", "W8": "128"}
BS = 4096
MEASURED_SEC = 30
ARMS = [
    ("juring", "std", "JUring"),
    ("fio-io_uring", "matched", "fio matched"),
    ("seastar", "std", "Seastar"),
]
COMPARATORS = [
    (("juring", "std"), ("fio-io_uring", "matched"), "JUring/fio-matched"),
    (("juring", "std"), ("seastar", "std"), "JUring/Seastar"),
    (("fio-io_uring", "matched"), ("seastar", "std"), "fio-matched/Seastar"),
]


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def load_meta(d):
    rows = []
    with open(os.path.join(d, "cell-meta.tsv"), encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            rows.append(r)
    return rows


def read_series(path):
    vals = []
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                if line.startswith("#") or not line.strip():
                    continue
                p = line.rstrip("\n").split("\t")
                if len(p) < 11:
                    continue
                try:
                    vals.append(float(p[10]))
                except ValueError:
                    continue
    except OSError:
        return None
    return vals


def classify(series):
    """Single-state disclosure over the 30 s measured window (frozen-style rule)."""
    if series is None:
        return {"steady": False, "reason": "missing-series", "cv_pct": None}
    m = series[-MEASURED_SEC:]
    if len(m) < 20:
        return {"steady": False, "reason": "series-too-short", "cv_pct": None}
    base = statistics.median(m)
    if base <= 0:
        return {"steady": False, "reason": "zero-iops", "cv_pct": None}
    mean = sum(m) / len(m)
    sd = statistics.pstdev(m)
    cv = 100.0 * sd / mean if mean else None
    cliff = False
    for k in range(10, len(m) - 9):
        a = statistics.median(m[k - 10:k])
        b = statistics.median(m[k:k + 10])
        if a > 0 and b < 0.70 * a:
            cliff = True
            break
    return {"steady": not cliff, "reason": "ok" if not cliff else "sustained-cliff",
            "cv_pct": cv}


def collect(session):
    meta = load_meta(session)
    per = {}
    reps = set()
    for cell in CELLS:
        for engine, variant, _label in ARMS:
            rows = [r for r in meta
                    if r["case"] == cell and r["engine"] == engine and r["variant"] == variant]
            for r in rows:
                rep = int(r["rep"])
                reps.add(rep)
                stem = f"{cell}__{engine}__{variant}__bs4k__qd{CELL_QD[cell]}__t1__r{rep}"
                st = classify(read_series(os.path.join(session, "series", stem + ".tsv")))
                iops = fnum(r["iops"]) if r["status"] == "ok" else None
                if iops is not None and iops <= 0:
                    iops = None
                per[(cell, engine, variant, rep)] = {
                    "status": r["status"], "iops": iops, "p99": fnum(r["p99_us"]),
                    "cliff": not st["steady"], "reason": st["reason"],
                    "series_cv_pct": st["cv_pct"],
                }
    return per, sorted(reps)


def summarize(per, reps, cell):
    out = {}
    for engine, variant, label in ARMS:
        vals = [per[(cell, engine, variant, r)]["iops"] for r in reps
                if per[(cell, engine, variant, r)]["iops"]]
        p99s = [per[(cell, engine, variant, r)]["p99"] for r in reps
                if per[(cell, engine, variant, r)]["p99"] is not None]
        cliffs = sum(1 for r in reps if per[(cell, engine, variant, r)]["cliff"])
        desc = stats.describe(vals)
        out[(engine, variant)] = {
            **desc, "label": label, "p99_mean": (sum(p99s) / len(p99s)) if p99s else None,
            "cliffs": cliffs,
        }
    return out


def ratio_for(per, reps, cell, left, right):
    lreps, rreps = [], []
    for r in reps:
        a = per[(cell, *left, r)]["iops"]
        b = per[(cell, *right, r)]["iops"]
        if a and b:
            lreps.append({"rep_index": r, "iops": a})
            rreps.append({"rep_index": r, "iops": b})
    return stats.ratio_stats(lreps, rreps)


def verdict_for(st):
    if not st or st.get("n_pairs", 0) < 2 or st.get("mean") is None:
        return "INCONCLUSIVE (n<2)"
    if st["lb95_1s"] is not None and st["lb95_1s"] > 1.05:
        return "FASTER >=5% (one-sided 95% LB > 1.05)"
    if st["ci90_high"] is not None and st["ci90_high"] < 0.95:
        return "SLOWER >=5% (90% CI upper < 0.95)"
    v, _msg = stats.band_verdict(st, (0.95, 1.05))
    if v == "PASS":
        return "PARITY (90% CI inside [0.95, 1.05])"
    return "INCONCLUSIVE (interval spans the 5% bar)"


def write_report(outdir, session, reps, per):
    os.makedirs(outdir, exist_ok=True)
    cells = {}
    ratios = {}
    spreads = {}
    for cell in CELLS:
        cells[cell] = summarize(per, reps, cell)
        for left, right, name in COMPARATORS:
            ratios[(cell, name)] = ratio_for(per, reps, cell, left, right)
        cycle_spread = {}
        for r in reps:
            levels = [per[(cell, e, v, r)]["iops"] for e, v, _l in ARMS
                      if per[(cell, e, v, r)]["iops"]]
            if len(levels) == len(ARMS):
                cycle_spread[r] = max(levels) / min(levels)
        spreads[cell] = cycle_spread

    lines = ["# Interleaved write bracket — 3-way descriptive table", ""]
    lines.append(f"- session: `{session}`")
    lines.append("- protocol: [docs/write-state-interleaved-protocol.md]"
                 "(../docs/write-state-interleaved-protocol.md) (fixed before the run)")
    lines.append(f"- cells: W7 = 4 KiB random write QD 64; W8 = QD 128; "
                 f"{len(reps)} cycles x 3 engines per cell")
    lines.append("- 15 s warmup + 30 s measured per run, fresh process, CPU 2, no preconditioning "
                 "(device already in a sustained post-burst state), no rests")
    lines.append("- why: the primary state-controlled campaign failed its pre-registered "
                 "homogeneity gate in both completed generations; this bracket measures all engines "
                 "over the same drifting state instead of claiming one state. It is descriptive, "
                 "not a frozen-gate PASS/FAIL, and it is not pooled with any other write session.")
    lines.append("- Seastar is a bracket: stock 200-entry ring, no taskrun flags, no registered "
                 "file, no fixed buffers. No engine issues fsync.")
    lines.append("")

    lines.append("## Per-run data")
    lines.append("")
    lines.append("| cell | cycle | engine | IOPS | MiB/s | p99 µs | single-state | status |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for cell in CELLS:
        for r in reps:
            for engine, variant, label in ARMS:
                x = per[(cell, engine, variant, r)]
                mibs = f"{x['iops'] * BS / 1048576.0:,.1f}" if x["iops"] else "-"
                iops = f"{x['iops']:,.0f}" if x["iops"] is not None else "-"
                p99 = f"{x['p99']:.1f}" if x["p99"] is not None else "-"
                lines.append(f"| {cell} | {r} | {label} | {iops} | {mibs} | {p99} | "
                             f"{not x['cliff']} ({x['reason']}) | {x['status']} |")
    lines.append("")

    lines.append("## Summary per cell and engine")
    lines.append("")
    lines.append("| cell | engine | n | IOPS mean ± 95% CI | MiB/s | p99 µs (mean) | CV% | min | median | max | cliff runs |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|---|")
    descriptive_only = {}
    for cell in CELLS:
        flag = False
        for engine, variant, _label in ARMS:
            d = cells[cell][(engine, variant)]
            mibs = (d["mean"] * BS / 1048576.0) if d["mean"] else None
            iops_s = f"{d['mean']:,.0f}" if d["mean"] is not None else "-"
            ci_s = f"{d['ci95_half']:,.0f}" if d["ci95_half"] is not None else ""
            mibs_s = f"{mibs:,.1f}" if mibs is not None else "-"
            p99_s = f"{d['p99_mean']:,.1f}" if d["p99_mean"] is not None else "-"
            cv_s = f"{d['cv_pct']:.2f}" if d["cv_pct"] is not None else "-"
            min_s = f"{d['min']:,.0f}" if d["min"] is not None else "-"
            med_s = f"{d['median']:,.0f}" if d["median"] is not None else "-"
            max_s = f"{d['max']:,.0f}" if d["max"] is not None else "-"
            lines.append(
                f"| {cell} | {d['label']} | {d['n']} | {iops_s}"
                f"{'' if not ci_s else ' ± ' + ci_s} | {mibs_s} | {p99_s} | "
                f"{cv_s} | {min_s} | {med_s} | {max_s} | {d['cliffs']}/{d['n']} |")
            if (d["cv_pct"] is not None and d["cv_pct"] > 20) or \
               (d["ci95_half"] is not None and d["mean"] and d["ci95_half"] / d["mean"] > 0.15):
                flag = True
        descriptive_only[cell] = flag
    lines.append("")
    for cell in CELLS:
        if descriptive_only[cell]:
            lines.append(f"**{cell} is descriptive only:** at least one arm has CV > 20% or a 95% "
                         "CI half-width above 15%; the device moved too much to rank this cell.")
            lines.append("")

    lines.append("## Paired ratios (same cycle = same device state)")
    lines.append("")
    lines.append("Parity at ±5%: two-sided 90% CI inside [0.95, 1.05]. Faster by >=5%: "
                 "one-sided 95% lower bound > 1.05. Slower by >=5%: 90% CI upper < 0.95.")
    lines.append("")
    lines.append("| cell | ratio | n pairs | mean | 90% CI | 95% CI | verdict |")
    lines.append("|---|---|---|---|---|---|---|")
    for cell in CELLS:
        for _left, _right, name in COMPARATORS:
            st = ratios[(cell, name)]
            ci90 = (f"[{st['ci90_low']:.4f}, {st['ci90_high']:.4f}]"
                    if st.get("ci90_low") is not None else "-")
            ci95 = (f"[{st['ci95_low']:.4f}, {st['ci95_high']:.4f}]"
                    if st.get("ci95_low") is not None else "-")
            mean = f"{st['mean']:.4f}" if st.get("mean") is not None else "-"
            lines.append(f"| {cell} | {name} | {st.get('n_pairs', 0)} | {mean} | {ci90} | "
                         f"{ci95} | {verdict_for(st)} |")
    lines.append("")

    lines.append("## Per-cycle spread across the three arms")
    lines.append("")
    lines.append("`max/min` of the three arms inside one cycle. A large spread is the device moving "
                 "between engine slots (order is rotated, so this is disclosed, not hidden).")
    lines.append("")
    lines.append("| cell | cycle | max/min |")
    lines.append("|---|---|---|")
    for cell in CELLS:
        for r, sp in spreads[cell].items():
            lines.append(f"| {cell} | {r} | {sp:.4f} |")
    lines.append("")

    lines.append("## Caveats")
    lines.append("")
    lines.append("- One host, one consumer NVMe. Absolute IOPS are device-state evidence, not a "
                 "specification. The drive was in a low, drifting steady state (roughly 70k–300k "
                 "IOPS with multi-second GC cliffs) during this bracket.")
    lines.append("- Short 30 s measured windows and 5 cycles per cell; the intervals reflect the "
                 "moving device, not only engine differences.")
    lines.append("- Descriptive bracket, not the frozen state-controlled gate; it cannot be pooled "
                 "with session-12/Step-A or with the primary 2026-11-23 campaign.")
    lines.append("- Seastar asymmetry as above. No durability/fsync claim.")
    lines.append("")

    md_path = os.path.join(outdir, "write-interleaved-summary.md")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    payload = {
        "session": session, "cycles": reps,
        "runs": {f"{c}|{e}|{v}|{r}": per[(c, e, v, r)]
                 for c in CELLS for e, v, _l in ARMS for r in reps},
        "summary": {c: {f"{e}|{v}": d for (e, v), d in cells[c].items()} for c in CELLS},
        "ratios": {f"{c}|{n}": st for (c, n), st in ratios.items()},
        "descriptive_only": descriptive_only,
        "cycle_spread": spreads,
    }
    with open(os.path.join(outdir, "write-interleaved-summary.json"), "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)

    with open(os.path.join(outdir, "write-interleaved-runs.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["cell", "cycle", "engine", "variant", "iops", "mibs", "p99_us",
                    "single_state", "reason", "status"])
        for cell in CELLS:
            for r in reps:
                for engine, variant, _label in ARMS:
                    x = per[(cell, engine, variant, r)]
                    mibs = (x["iops"] * BS / 1048576.0) if x["iops"] else ""
                    w.writerow([cell, r, engine, variant, x["iops"] or "", mibs,
                                x["p99"] if x["p99"] is not None else "",
                                not x["cliff"], x["reason"], x["status"]])
    return md_path, cells, ratios, descriptive_only


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    per, reps = collect(args.session)
    md_path, cells, ratios, descriptive_only = write_report(args.out, args.session, reps, per)
    print(f"wrote {md_path}")
    for cell in CELLS:
        print(f"== {cell} ==" + (" [descriptive only]" if descriptive_only[cell] else ""))
        for engine, variant, _l in ARMS:
            d = cells[cell][(engine, variant)]
            if d["mean"] is not None:
                ci_s = f" ±{d['ci95_half']:,.0f}" if d["ci95_half"] is not None else ""
                print(f"  {d['label']:12s} n={d['n']} mean={d['mean']:,.0f}{ci_s} "
                      f"CV={d['cv_pct']:.2f}% cliffs={d['cliffs']}/{d['n']}")
        for _l, _r, name in COMPARATORS:
            st = ratios[(cell, name)]
            if st.get("mean") is not None:
                print(f"  {name:24s} n={st['n_pairs']} ratio={st['mean']:.4f} -> {verdict_for(st)}")


if __name__ == "__main__":
    main()
