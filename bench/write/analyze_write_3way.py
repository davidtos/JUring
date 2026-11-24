#!/usr/bin/env python3
"""Pre-registered 3-way write table for a state-controlled write rerun.

Protocol: docs/write-state-3way-protocol.md (frozen before the first run).
Reads <session>/pass*/cell-meta.tsv, <session>/pass*/series/*.tsv and
<session>/pass*/cells/*.json. The frozen gate (bench/host-setup/analyze_write_state.py)
decides which passes are single-state; this script only adds the third and
fourth arms and the table formatting. It never edits the frozen analyzer.

Passes are pass1..pass8 and the CLI flag is --passes.

Usage:
    python3 bench/write/analyze_write_3way.py --session DIR --passes pass1,pass2 --out DIR
"""
import argparse
import csv
import importlib.util
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "bench"))
from analysis import cells, stats  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "frozen_write_state", os.path.join(REPO, "bench", "host-setup", "analyze_write_state.py"))
aw = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(aw)

CELLS = ("randwrite-4k-qd64", "randwrite-4k-qd128")
CELL_QD = {cell: str(cells.lookup(cell).qd) for cell in CELLS}
BS = 4096
ARMS = [
    ("juring", "std", "JUring"),
    ("fio-io_uring", "matched", "fio matched"),
    ("fio-io_uring", "matchedbatch", "fio matchedbatch"),
    ("seastar", "std", "Seastar"),
]
COMPARATORS = [
    (("juring", "std"), ("fio-io_uring", "matched"), "JUring/fio-matched"),
    (("juring", "std"), ("fio-io_uring", "matchedbatch"), "JUring/fio-matchedbatch"),
    (("juring", "std"), ("seastar", "std"), "JUring/Seastar"),
    (("fio-io_uring", "matched"), ("seastar", "std"), "fio-matched/Seastar"),
]
MIN_3WAY_PASSES = 3


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def load_meta(d):
    """cell-meta.tsv rows keyed by canonical cell slug."""
    rows = {}
    with open(os.path.join(d, "cell-meta.tsv"), encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            rows[(cells.slug(r["case"]), r["engine"], r["variant"], r["bs"],
                  r["qd"], r["rep"])] = r
    return rows


def series_path(d, cell, engine, variant, bs, qd, rep=1):
    return os.path.join(d, "series", aw.stem(cell, engine, variant, bs, qd, rep) + ".tsv")


def cell_json_path(d, cell, engine, variant, bs, qd, rep=1):
    return os.path.join(d, "cells", aw.stem(cell, engine, variant, bs, qd, rep) + ".json")


def run_errors(path, engine):
    try:
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
    except (OSError, ValueError):
        return None
    if engine.startswith("fio"):
        try:
            return int(doc["jobs"][0].get("error", 0) or 0)
        except (KeyError, IndexError, TypeError, ValueError):
            return None
    agg = doc.get("aggregate") or {}
    return agg.get("errors_total")


def collect(session, passes):
    per = {}
    info = {}
    for name in passes:
        d = os.path.join(session, name)
        meta = load_meta(d)
        frozen = aw.gen_verdict(d)
        info[name] = {"frozen_pass": bool(frozen["pass"]), "spread": {},
                      "all_arms_ok": True}
        for cell in CELLS:
            qd = CELL_QD[cell]
            for engine, variant, _label in ARMS:
                key = (cell, engine, variant, "4k", qd, "1")
                m = meta.get(key)
                series = aw.read_series(series_path(d, cell, engine, variant, "4k", qd))
                c = aw.classify(series)
                iops = fnum(m["iops"]) if m and m["status"] == "ok" else None
                if iops is not None and iops <= 0:
                    iops = None
                err = (run_errors(cell_json_path(d, cell, engine, variant, "4k", qd), engine)
                       if m else None)
                ok = (m is not None and m["status"] == "ok" and iops is not None
                      and c["steady"] and (err in (0, None)))
                per[(name, cell, engine, variant)] = {
                    "status": m["status"] if m else "missing", "iops": iops,
                    "p99": fnum(m["p99_us"]) if m else None, "steady": c["steady"],
                    "reason": c["reason"], "cv": c.get("cv"), "errors": err, "ok": ok,
                }
                if not ok:
                    info[name]["all_arms_ok"] = False
            levels = [per[(name, cell, e, v)]["iops"] for e, v, _ in ARMS]
            levels = [x for x in levels if x]
            info[name]["spread"][cell] = ((max(levels) / min(levels))
                                          if len(levels) == len(ARMS) else None)
        spreads = [info[name]["spread"][c] for c in CELLS]
        homogeneous = all(s is not None and s <= 1.25 for s in spreads)
        info[name]["valid_3way"] = (info[name]["frozen_pass"]
                                     and info[name]["all_arms_ok"] and homogeneous)
    return per, info


def ratio_for(per, valid, cell, left, right):
    left_reps, right_reps = [], []
    for i, g in enumerate(valid):
        a = per[(g, cell, *left)]["iops"]
        b = per[(g, cell, *right)]["iops"]
        if a and b:
            left_reps.append({"rep_index": i, "iops": a})
            right_reps.append({"rep_index": i, "iops": b})
    return stats.ratio_stats(left_reps, right_reps)


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


def fmt_ci(desc):
    if desc["mean"] is None:
        return "-"
    if desc["ci95_low"] is None:
        return f"{desc['mean']:,.0f}"
    return f"{desc['mean']:,.0f} ± {desc['ci95_half']:,.0f}"


def write_report(outdir, session, passes, per, info):
    os.makedirs(outdir, exist_ok=True)
    valid = [p for p in passes if info[p]["valid_3way"]]
    frozen_valid = [p for p in passes if info[p]["frozen_pass"]]
    lines = ["# State-controlled 3-way write rerun — pre-registered analysis", ""]
    lines.append(f"- session: `{session}`")
    lines.append("- passes attempted: "
                 + ", ".join(cells.pass_label(p) for p in passes))
    lines.append(f"- frozen-gate valid: {len(frozen_valid)} "
                 f"({', '.join(cells.pass_label(p) for p in frozen_valid) or 'none'})")
    lines.append(f"- 3-way table valid: {len(valid)} "
                 f"({', '.join(cells.pass_label(p) for p in valid) or 'none'})")
    lines.append("- cells: " + "; ".join(cells.label(c) for c in CELLS))
    lines.append("- windows: 60 s warmup + 60 s measured, one fresh process per run, "
                 "continuous state-controlled pass (no rests), seed 20261113, CPU 2")
    lines.append("- Seastar is a bracket: stock 200-entry ring, no taskrun flags, no "
                 "registered file, no fixed buffers. No fsync anywhere.")
    lines.append("")

    lines.append("## Pass validity")
    lines.append("")
    lines.append("| pass | frozen PILOT_PASS | 4-arm max/min, 64 in flight | "
                 "4-arm max/min, 128 in flight | all arms steady/ok | 3-way valid |")
    lines.append("|---|---|---|---|---|---|")
    for p in passes:
        sp7 = info[p]["spread"][CELLS[0]]
        sp8 = info[p]["spread"][CELLS[1]]
        lines.append(
            f"| {cells.pass_label(p)} | {info[p]['frozen_pass']} | "
            f"{'-' if sp7 is None else f'{sp7:.4f}'} | {'-' if sp8 is None else f'{sp8:.4f}'} | "
            f"{info[p]['all_arms_ok']} | {info[p]['valid_3way']} |")
    lines.append("")
    lines.append("Frozen gate (unchanged): `bench/host-setup/analyze_write_state.py` over the six "
                 "PLANNED JUring/fio runs; `max/min <= 1.25` per cell, single-state 60 s windows.")
    lines.append("")

    lines.append("## Per-run data")
    lines.append("")
    lines.append("| pass | cell | engine | variant | IOPS | MiB/s | p99 µs | steady | errors | status |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for p in passes:
        for cell in CELLS:
            for engine, variant, _label in ARMS:
                r = per[(p, cell, engine, variant)]
                mibs = (r["iops"] * BS / 1048576.0) if r["iops"] else None
                iops_s = f"{r['iops']:,.0f}" if r["iops"] is not None else "-"
                mibs_s = f"{mibs:,.1f}" if mibs is not None else "-"
                p99_s = f"{r['p99']:.1f}" if r["p99"] is not None else "-"
                err_s = "-" if r["errors"] is None else str(r["errors"])
                lines.append(
                    f"| {cells.pass_label(p)} | {cells.label(cell)} | {engine} | "
                    f"{variant} | {iops_s} | {mibs_s} | "
                    f"{p99_s} | {r['steady']} | {err_s} | {r['status']} |")
    lines.append("")

    lines.append("## Summary over 3-way-valid passes")
    lines.append("")
    if len(valid) < MIN_3WAY_PASSES:
        lines.append(f"**Only {len(valid)} valid pass(es); the pre-registered minimum for a "
                     f"3-way summary is {MIN_3WAY_PASSES}. Per-run data above is the evidence; no "
                     "ranking is claimed.**")
        lines.append("")
    lines.append("| cell | engine | n | IOPS mean ± 95% CI | MiB/s | p99 µs (mean) | CV% | min | max |")
    lines.append("|---|---|---|---|---|---|---|---|---|")
    summary = {}
    for cell in CELLS:
        for engine, variant, label in ARMS:
            vals = [per[(p, cell, engine, variant)]["iops"] for p in valid
                    if per[(p, cell, engine, variant)]["iops"]]
            desc = stats.describe(vals)
            p99s = [per[(p, cell, engine, variant)]["p99"] for p in valid
                    if per[(p, cell, engine, variant)]["p99"] is not None]
            summary[(cell, engine, variant)] = desc
            p99 = sum(p99s) / len(p99s) if p99s else None
            mibs_mean = f"{desc['mean'] * BS / 1048576.0:,.1f}" if desc["mean"] is not None else "-"
            p99_mean = f"{p99:,.1f}" if p99 is not None else "-"
            cv_s = f"{desc['cv_pct']:.2f}" if desc["cv_pct"] is not None else "-"
            min_s = f"{desc['min']:,.0f}" if desc["min"] is not None else "-"
            max_s = f"{desc['max']:,.0f}" if desc["max"] is not None else "-"
            lines.append(
                f"| {cells.label(cell)} | {label} | {desc['n']} | {fmt_ci(desc)} | {mibs_mean} | "
                f"{p99_mean} | {cv_s} | {min_s} | {max_s} |")
    lines.append("")

    lines.append("## Paired ratios (same pass = same device state)")
    lines.append("")
    lines.append("Parity at ±5%: two-sided 90% CI inside [0.95, 1.05]. Faster by >=5%: "
                 "one-sided 95% lower bound > 1.05. Slower by >=5%: 90% CI upper < 0.95.")
    lines.append("")
    lines.append("| cell | ratio | n | mean | 90% CI | 95% CI | verdict |")
    lines.append("|---|---|---|---|---|---|---|")
    ratios = {}
    for cell in CELLS:
        for left, right, name in COMPARATORS:
            st = ratio_for(per, valid, cell, left, right)
            ratios[(cell, name)] = st
            ci90 = (f"[{st['ci90_low']:.4f}, {st['ci90_high']:.4f}]"
                    if st.get("ci90_low") is not None else "-")
            ci95 = (f"[{st['ci95_low']:.4f}, {st['ci95_high']:.4f}]"
                    if st.get("ci95_low") is not None else "-")
            ratio_mean_s = f"{st['mean']:.4f}" if st.get("mean") is not None else "-"
            lines.append(f"| {cells.label(cell)} | {name} | {st.get('n_pairs', 0)} | "
                         f"{ratio_mean_s} | "
                         f"{ci90} | {ci95} | {verdict_for(st)} |")
    lines.append("")
    lines.append("## Caveats carried with these numbers")
    lines.append("")
    lines.append("- One host, one consumer NVMe, one kernel; absolute IOPS are device-state "
                 "evidence, not a specification.")
    lines.append("- Continuous post-SLC-burst steady state by construction: no rests after "
                 "preconditioning or between runs (the frozen continuous-variant deviation). Not a "
                 "fresh-drive "
                 "or burst-mode number.")
    lines.append("- 60 s warmup / 60 s measured instead of the frozen 120/60; documented in the "
                 "protocol and allowed by bench/write/README.md.")
    lines.append("- No durability claim: every engine issues plain O_DIRECT writes without fsync.")
    lines.append("- Seastar has no SINGLE_ISSUER/COOP_TASKRUN/DEFER_TASKRUN, no registered file "
                 "and no fixed buffers; the comparison is a capability bracket.")
    lines.append("")

    md_path = os.path.join(outdir, "write-3way-summary.md")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    payload = {
        "session": session, "passes": passes, "frozen_valid": frozen_valid,
        "valid_3way": valid, "min_3way_passes": MIN_3WAY_PASSES,
        "runs": {f"{p}|{c}|{e}|{v}": per[(p, c, e, v)]
                 for p in passes for c in CELLS for e, v, _ in ARMS},
        "summary": {f"{c}|{e}|{v}": d for (c, e, v), d in summary.items()},
        "ratios": {f"{c}|{n}": st for (c, n), st in ratios.items()},
    }
    with open(os.path.join(outdir, "write-3way-summary.json"), "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)

    with open(os.path.join(outdir, "write-3way-runs.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["pass", "cell", "engine", "variant", "iops", "mibs", "p99_us",
                    "steady", "errors", "status", "valid_3way_pass"])
        for p in passes:
            for cell in CELLS:
                for engine, variant, _l in ARMS:
                    r = per[(p, cell, engine, variant)]
                    mibs = (r["iops"] * BS / 1048576.0) if r["iops"] else ""
                    w.writerow([p, cell, engine, variant, r["iops"] or "", mibs,
                                r["p99"] if r["p99"] is not None else "", r["steady"],
                                r["errors"], r["status"], info[p]["valid_3way"]])
    return md_path, valid, frozen_valid


def parse_passes(text):
    """Comma/space-separated passN values; gN forms are rejected."""
    tokens = [t for t in text.replace(",", " ").split() if t]
    if not tokens:
        raise argparse.ArgumentTypeError("at least one pass is required")
    for token in tokens:
        if cells.pass_number(token) is None or cells.pass_name(token) != token:
            raise argparse.ArgumentTypeError(
                "pass must be pass1..pass8 (got %r)" % (token,))
    return tokens


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", required=True)
    ap.add_argument("--passes", required=True, type=parse_passes,
                    help="comma/space-separated passes, pass1..pass8")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    passes = args.passes
    per, info = collect(args.session, passes)
    md_path, valid, frozen_valid = write_report(args.out, args.session, passes, per, info)
    print(f"wrote {md_path}")
    print(f"frozen-gate valid: {len(frozen_valid)}/{len(passes)} "
          f"({', '.join(cells.pass_label(p) for p in frozen_valid) or 'none'})")
    print(f"3-way valid: {len(valid)}/{len(passes)} "
          f"({', '.join(cells.pass_label(p) for p in valid) or 'none'})")
    for cell in CELLS:
        print(f"== {cells.label(cell)} ==")
        for engine, variant, label in ARMS:
            vals = [per[(g, cell, engine, variant)]["iops"] for g in valid
                    if per[(g, cell, engine, variant)]["iops"]]
            d = stats.describe(vals)
            if d["mean"] is not None:
                ci = f"±{d['ci95_half']:,.0f}" if d["ci95_half"] is not None else ""
                print(f"  {label:18s} n={d['n']} mean={d['mean']:,.0f} {ci} CV={d['cv_pct']:.2f}%")
        for left, right, name in COMPARATORS:
            st = ratio_for(per, valid, cell, left, right)
            if st.get("mean") is not None:
                print(f"  {name:24s} n={st['n_pairs']} ratio={st['mean']:.4f} -> {verdict_for(st)}")


if __name__ == "__main__":
    main()
