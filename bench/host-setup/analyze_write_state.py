#!/usr/bin/env python3
"""Write-state protocol analyzer -- protocol frozen (see docs/write-state-3way-protocol.md).

Usage:
    python3 bench/host-setup/analyze_write_state.py --pass-dir DIR
    python3 bench/host-setup/analyze_write_state.py --session DIR --passes pass1,pass2 --out DIR
    python3 bench/host-setup/analyze_write_state.py --survey DIR

Inputs: <dir>/cell-meta.tsv and <dir>/series/<stem>.tsv.  Outputs, in session
mode: <out>/write-state-summary.md, <out>/write-state-verdict.json and
<out>/write-state-ratios.csv; --pass-dir and --survey print to stdout instead.
The classifier and the 0.95 gate are frozen: see docs/write-state-3way-protocol.md.
The protocol's passes are pass1..pass8.
"""
import argparse
import csv
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from analysis import cells, cli, jsonio, md, stats  # noqa: E402

MEASURED_SEC = 60
BAND = 0.20
CV_MAX = 0.10
INBAND_MIN = 0.90
CLIFF_RATIO = 0.70

# Planned pass runs: (cell, engine, variant, bs, qd)
PLANNED = [
    ("randwrite-4k-qd64", "juring", "std", "4k", "64"),
    ("randwrite-4k-qd64", "fio-io_uring", "matched", "4k", "64"),
    ("randwrite-4k-qd64", "fio-io_uring", "matchedbatch", "4k", "64"),
    ("randwrite-4k-qd128", "juring", "std", "4k", "128"),
    ("randwrite-4k-qd128", "fio-io_uring", "matched", "4k", "128"),
    ("randwrite-4k-qd128", "fio-io_uring", "matchedbatch", "4k", "128"),
]
COMPARATORS = ("matched", "matchedbatch")
GATE_LB = 0.95
MIN_VALID_PASSES = 4        # pre-registered: <4 valid passes => no gate


def stem(cell, engine, variant, bs, qd, rep=1):
    return f"{cell}__{engine}__{variant}__bs{bs}__qd{qd}__t1__r{rep}"


def series_path(d, cell, engine, variant, bs, qd, rep=1):
    """Canonical series TSV path for one cell."""
    return os.path.join(d, "series",
                        stem(cells.slug(cell), engine, variant, bs, qd, rep) + ".tsv")


def read_series(path):
    """Per-second write IOPS column of a 1 s device-I/O series TSV; None when unreadable."""
    vals = []
    try:
        with open(path) as f:
            for line in f:
                if line.startswith("#") or not line.strip():
                    continue
                p = line.rstrip("\n").split("\t")
                if len(p) < 11:
                    continue
                try:
                    vals.append(float(p[10]))  # write_iops
                except ValueError:
                    continue
    except OSError:
        return None
    return vals


def classify(series):
    if series is None:
        return {"steady": False, "reason": "missing-series", "n": 0}
    if len(series) < 30:
        return {"steady": False, "reason": "series-too-short", "n": len(series)}
    measured = series[-min(MEASURED_SEC, len(series)):]
    last30 = measured[-30:]
    base = statistics.median(last30)
    if base <= 0:
        return {"steady": False, "reason": "zero-iops", "n": len(measured)}
    inband = sum(1 for x in last30 if abs(x - base) <= BAND * base) / len(last30)
    m = sum(last30) / len(last30)
    sd = statistics.pstdev(last30)
    cv = sd / m if m > 0 else 9.9
    # sustained cliff: any 10 s median after a split < 70 % of the 10 s median before it
    cliff = False
    for k in range(10, len(measured) - 9):
        a = statistics.median(measured[k - 10:k])
        b = statistics.median(measured[k:k + 10])
        if a > 0 and b < CLIFF_RATIO * a:
            cliff = True
            break
    # Frozen semantics: a run is SINGLE-STATE iff its measured window has no
    # sustained >30 % step (10 s medians). CV and in-band are disclosure metrics,
    # not gates: 1 s device IOPS is noisy even in a single device state.
    steady = not cliff and len(measured) >= 30
    return {"steady": steady, "reason": "ok" if steady else "sustained-cliff",
            "n": len(measured), "base": base, "cv": cv, "inband": inband}


def read_meta(d):
    """cell-meta.tsv rows keyed by (case, engine, variant, bs, qd, rep).

    ``case`` is normalized to the canonical slug.
    """
    meta = {}
    for r in jsonio.load_csv_rows(os.path.join(d, "cell-meta.tsv")):
        meta[(cells.slug(r["case"]), r["engine"], r["variant"], r["bs"], r["qd"],
              r["rep"])] = r
    return meta


def gen_verdict(d):
    meta = read_meta(d)
    rows = []
    all_ok = True
    for cell, engine, variant, bs, qd in PLANNED:
        key = (cell, engine, variant, bs, qd, "1")
        r = meta.get(key)
        if r is None:
            rows.append({"cell": cell, "engine": engine, "variant": variant,
                         "status": "missing", "iops": 0.0, "steady": False,
                         "reason": "missing-meta"})
            all_ok = False
            continue
        iops = float(r["iops"]) if r["status"] == "ok" and r["iops"] not in ("", "-1") else 0.0
        ser = read_series(series_path(d, cell, engine, variant, bs, qd))
        c = classify(ser)
        if r["status"] != "ok" or iops <= 0:
            c = {"steady": False, "reason": "run-failed", "n": len(ser) if ser else 0}
        rows.append({"cell": cell, "engine": engine, "variant": variant,
                     "status": r["status"], "iops": iops, **c})
        if not c["steady"]:
            all_ok = False
    # Homogeneity: within each cell the three arms must be in the same device
    # state (no burst-vs-steady mixing). Frozen bound: max/min IOPS <= 1.25.
    for cell in ("randwrite-4k-qd64", "randwrite-4k-qd128"):
        levels = [r["iops"] for r in rows if r["cell"] == cell and r["iops"] > 0]
        if len(levels) == 3:
            if max(levels) / min(levels) > 1.25:
                all_ok = False
        else:
            all_ok = False
    return {"pass": all_ok, "rows": rows}


def gate_analysis(session, passes):
    """Gate over the requested passes.

    ``passes`` holds the CLI values (``pass1``..``pass8``); each is resolved to
    its session directory and reported under its canonical ``passN`` name.
    """
    data = {}
    valid = []
    resolved = []
    for token in passes:
        name = cells.pass_name(token)
        d = cells.pass_dir(session, name)
        resolved.append((name, d))
        v = gen_verdict(d)
        meta = read_meta(d)
        for (cell, engine, variant, _bs, _qd, _rep), r in meta.items():
            try:
                iops = float(r["iops"]) if r["status"] == "ok" else 0.0
            except ValueError:
                iops = 0.0
            data.setdefault((name, cell, engine, variant), iops)
        if v["pass"]:
            valid.append(name)
    out = {"valid_passes": valid, "n_valid": len(valid),
           "passes": {name: gen_verdict(d) for name, d in resolved},
           "cells": {}}
    for cell in ("randwrite-4k-qd64", "randwrite-4k-qd128"):
        for comp in COMPARATORS:
            ratios = []
            for name in valid:
                j = data.get((name, cell, "juring", "std"), 0.0)
                f = data.get((name, cell, "fio-io_uring", comp), 0.0)
                if j > 0 and f > 0:
                    ratios.append(j / f)
            m, lo, hi = stats.mean_ci(ratios)
            out["cells"].setdefault(cell, {})[comp] = {
                "n": len(ratios), "ratios": ratios, "mean": m, "lo": lo, "hi": hi,
            }
    # gate verdict
    if len(valid) < MIN_VALID_PASSES:
        verdict = "INCONCLUSIVE"
        reason = f"only {len(valid)} valid pass(es) (< {MIN_VALID_PASSES})"
    else:
        lows, ubs = [], []
        for cell in ("randwrite-4k-qd64", "randwrite-4k-qd128"):
            for comp in COMPARATORS:
                st = out["cells"][cell][comp]
                if st["n"] < MIN_VALID_PASSES or st["lo"] is None:
                    lows.append(None)
                    ubs.append(None)
                    continue
                lows.append(st["lo"])
                ubs.append(st["hi"])
        if any(x is None for x in lows):
            verdict, reason = "INCONCLUSIVE", "missing paired passes"
        elif all(x >= GATE_LB for x in lows):
            verdict, reason = "PASS", "all four one-sided 95% LBs >= 0.95"
        elif any(x < GATE_LB for x in ubs):
            verdict, reason = "FAIL", "at least one 95% UB < 0.95"
        else:
            verdict, reason = "INCONCLUSIVE", "CIs overlap the 0.95 bar"
    out["verdict"] = verdict
    out["reason"] = reason
    return out


def write_report(outdir, res):
    out_dir = cli.ensure_dir(outdir)
    lines = ["# State-controlled write protocol — gate analysis (frozen)", ""]
    lines.append(f"- valid passes: {res['n_valid']} "
                 f"({', '.join(cells.pass_label(g) for g in res['valid_passes']) or 'none'})")
    lines.append(f"- **verdict: {res['verdict']}** — {res['reason']}")
    lines.append("")
    lines.append("| cell | comparator | n | mean J/fio | 95% CI |")
    lines.append("|---|---|---|---|---|")
    for cell in ("randwrite-4k-qd64", "randwrite-4k-qd128"):
        for comp, st in res["cells"][cell].items():
            lines.append(f"| {cells.label(cell)} | {comp} | {st['n']} | {md.fixed(st['mean'], 4)} | "
                         f"[{md.fixed(st['lo'], 4)}, {md.fixed(st['hi'], 4)}] |")
    lines.append("")
    lines.append("## Per-pass run classification")
    lines.append("")
    lines.append("| pass | cell | engine | variant | iops | steady | reason | cv |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for g, v in res["passes"].items():
        for r in v["rows"]:
            cv = f"{r.get('cv', 0):.3f}" if "cv" in r else "-"
            lines.append(f"| {cells.pass_label(g)} | {cells.label(r['cell'])} | "
                         f"{r['engine']} | {r['variant']} | "
                         f"{md.thousands(r['iops'], 0)} | {r['steady']} | {r['reason']} | {cv} |")
    md_path = os.path.join(out_dir, "write-state-summary.md")
    cli.write_text(md_path, "\n".join(lines))
    cli.write_json(os.path.join(out_dir, "write-state-verdict.json"), res,
                   indent=2, newline=False)
    csv_path = os.path.join(out_dir, "write-state-ratios.csv")
    with open(csv_path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["pass", "cell", "comparator", "ratio"])
        passes = res["valid_passes"]
        for cell in ("randwrite-4k-qd64", "randwrite-4k-qd128"):
            for comp in COMPARATORS:
                ratios = res["cells"][cell][comp]["ratios"]
                # no strict= on Python 3.9; every ratio comes from a valid pass
                for g, r in zip(passes, ratios):  # noqa: B905
                    w.writerow([g, cell, comp, f"{r:.6f}"])
    return md_path


def survey(d):
    meta = read_meta(d)
    total = steady = 0
    rows = []
    for (cell, engine, variant, bs, qd, rep), r in sorted(meta.items()):
        if r["status"] != "ok":
            continue
        c = classify(read_series(series_path(d, cell, engine, variant, bs, qd, rep)))
        total += 1
        if c["steady"]:
            steady += 1
        rows.append((cell, engine, variant, qd, rep, c["steady"], c["reason"], c.get("cv")))
    print(f"survey {d}: {steady}/{total} runs classified steady")
    for r in rows:
        cv = f"{r[7]:.3f}" if r[7] is not None else "-"
        print(f"  {r[0]:24s} {r[1]:12s} {r[2]:12s} qd{r[3]:3s} r{r[4]} "
              f"steady={str(r[5]):5s} {r[6]:14s} cv={cv}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pass-dir", dest="pass_dir",
                    help="one pass directory")
    ap.add_argument("--session")
    ap.add_argument("--passes", dest="passes",
                    help="comma-separated passes (pass1..pass8)")
    ap.add_argument("--out")
    ap.add_argument("--survey")
    args = ap.parse_args()
    if args.survey:
        survey(args.survey)
        return
    if args.pass_dir:
        v = gen_verdict(args.pass_dir)
        for r in v["rows"]:
            cv = f"{r.get('cv', 0):.3f}" if "cv" in r else "-"
            print(f"{cells.label(r['cell'])} {r['engine']} {r['variant']} "
                  f"status={r['status']} iops={r['iops']:.0f} steady={r['steady']} "
                  f"reason={r['reason']} cv={cv}")
        print(f"PILOT_PASS {1 if v['pass'] else 0}")
        return
    if args.session and args.passes:
        passes = [p for p in args.passes.replace(",", " ").split() if p]
        res = gate_analysis(args.session, passes)
        if args.out:
            md_path = write_report(args.out, res)
            print(f"wrote {md_path}")
            print(f"gate verdict: {res['verdict']} ({res['reason']})")
        else:
            print(json.dumps(res, indent=2))
        return
    ap.error("need --pass-dir, or --session/--passes, or --survey")


if __name__ == "__main__":
    main()
