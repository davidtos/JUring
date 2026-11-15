#!/usr/bin/env python3
"""Write-state protocol analyzer -- FROZEN with docs/m1-write-state-design.md.

Modes:
  --gen-dir DIR                     classify one preconditioned generation (W0/W1/W2)
                                    prints one line per planned run + "PILOT_PASS 0|1"
  --session DIR --gens g1,g2,... --out DIR
                                    gate analysis over generations (W2)
  --survey DIR                      classify every run of a write session (validation
                                    against the executed Step A campaign; not used
                                    by the unattended chain)

Frozen classifier (methodology D2/D7 + state-controlled protocol):
  * measured window = the last min(60, n) 1 s device samples of the run's D2 series
    (measured_sec=60 in the protocol; warmup precedes it);
  * base = median of the last 30 measured samples;
  * in-band fraction = share of the last 30 samples within +/-20 % of base;
  * CV = stdev/mean of the last 30 samples;
  * cliff = any consecutive measured-window step with x[i] < 0.7 * x[i-1];
  * a run is SINGLE-STATE iff its measured window shows no sustained >30 % step
    (10 s median comparison); CV/in-band are reported but are not gates;
A generation is VALID iff all six planned runs are ok and single-state AND,
per cell, the three arms' IOPS are within a 1.25x band (no burst-vs-steady
mixing inside the generation).
"""
import argparse
import csv
import glob
import json
import math
import os
import statistics
import sys

MEASURED_SEC = 60
BAND = 0.20
CV_MAX = 0.10
INBAND_MIN = 0.90
CLIFF_RATIO = 0.70
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365}

# Planned generation runs: (cell, engine, variant, bs, qd)
PLANNED = [
    ("W7", "juring", "std", "4k", "64"),
    ("W7", "fio-io_uring", "matched", "4k", "64"),
    ("W7", "fio-io_uring", "matchedbatch", "4k", "64"),
    ("W8", "juring", "std", "4k", "128"),
    ("W8", "fio-io_uring", "matched", "4k", "128"),
    ("W8", "fio-io_uring", "matchedbatch", "4k", "128"),
]
COMPARATORS = ("matched", "matchedbatch")
GATE_LB = 0.95
MIN_VALID_GENS = 4          # pre-registered: <4 valid generations => no gate


def stem(cell, engine, variant, bs, qd, rep=1):
    return f"{cell}__{engine}__{variant}__bs{bs}__qd{qd}__t1__r{rep}"


def read_series(path):
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
    path = os.path.join(d, "cell-meta.tsv")
    if not os.path.exists(path):
        return {}
    out = {}
    with open(path, newline="") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            out[(r["case"], r["engine"], r["variant"], r["bs"], r["qd"], r["rep"])] = r
    return out


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
        ser = read_series(os.path.join(d, "series", stem(cell, engine, variant, bs, qd) + ".tsv"))
        c = classify(ser)
        if r["status"] != "ok" or iops <= 0:
            c = {"steady": False, "reason": "run-failed", "n": len(ser) if ser else 0}
        rows.append({"cell": cell, "engine": engine, "variant": variant,
                     "status": r["status"], "iops": iops, **c})
        if not c["steady"]:
            all_ok = False
    # Homogeneity: within each cell the three arms must be in the same device
    # state (no burst-vs-steady mixing). Frozen bound: max/min IOPS <= 1.25.
    for cell in ("W7", "W8"):
        levels = [r["iops"] for r in rows if r["cell"] == cell and r["iops"] > 0]
        if len(levels) == 3:
            if max(levels) / min(levels) > 1.25:
                all_ok = False
        else:
            all_ok = False
    return {"pass": all_ok, "rows": rows}


def mean_ci(xs):
    n = len(xs)
    if n < 2:
        return None
    m = sum(xs) / n
    se = math.sqrt(sum((x - m) ** 2 for x in xs) / (n - 1) / n)
    t = T95.get(n - 1)
    if t is None:
        return None
    return m, m - t * se, m + t * se


def gate_analysis(session, gens):
    data = {}
    valid = []
    for g in gens:
        d = os.path.join(session, g)
        v = gen_verdict(d)
        meta = read_meta(d)
        for (cell, engine, variant, bs, qd, rep), r in meta.items():
            try:
                iops = float(r["iops"]) if r["status"] == "ok" else 0.0
            except ValueError:
                iops = 0.0
            data.setdefault((g, cell, engine, variant), iops)
        if v["pass"]:
            valid.append(g)
    out = {"valid_generations": valid, "n_valid": len(valid),
           "generations": {g: gen_verdict(os.path.join(session, g)) for g in gens},
           "cells": {}}
    for cell in ("W7", "W8"):
        qd = "64" if cell == "W7" else "128"
        for comp in COMPARATORS:
            ratios = []
            for g in valid:
                j = data.get((g, cell, "juring", "std"), 0.0)
                f = data.get((g, cell, "fio-io_uring", comp), 0.0)
                if j > 0 and f > 0:
                    ratios.append(j / f)
            st = mean_ci(ratios)
            out["cells"].setdefault(cell, {})[comp] = {
                "n": len(ratios), "ratios": ratios,
                "mean": st[0] if st else None,
                "lo": st[1] if st else None,
                "hi": st[2] if st else None,
            }
    # gate verdict
    if len(valid) < MIN_VALID_GENS:
        verdict = "INCONCLUSIVE"
        reason = f"only {len(valid)} valid generation(s) (< {MIN_VALID_GENS})"
    else:
        lows, ubs = [], []
        for cell in ("W7", "W8"):
            for comp in COMPARATORS:
                st = out["cells"][cell][comp]
                if st["n"] < MIN_VALID_GENS or st["lo"] is None:
                    lows.append(None); ubs.append(None); continue
                lows.append(st["lo"]); ubs.append(st["hi"])
        if any(x is None for x in lows):
            verdict, reason = "INCONCLUSIVE", "missing paired generations"
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
    os.makedirs(outdir, exist_ok=True)
    lines = ["# State-controlled write protocol — gate analysis (frozen)", ""]
    lines.append(f"- valid generations: {res['n_valid']} ({', '.join(res['valid_generations']) or 'none'})")
    lines.append(f"- **verdict: {res['verdict']}** — {res['reason']}")
    lines.append("")
    lines.append("| cell | comparator | n | mean J/fio | 95% CI |")
    lines.append("|---|---|---|---|---|")
    for cell in ("W7", "W8"):
        for comp, st in res["cells"][cell].items():
            m = f"{st['mean']:.4f}" if st["mean"] is not None else "n/a"
            lo = f"{st['lo']:.4f}" if st["lo"] is not None else "n/a"
            hi = f"{st['hi']:.4f}" if st["hi"] is not None else "n/a"
            lines.append(f"| {cell} | {comp} | {st['n']} | {m} | [{lo}, {hi}] |")
    lines.append("")
    lines.append("## Per-generation run classification")
    lines.append("")
    lines.append("| generation | cell | engine | variant | iops | steady | reason | cv |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for g, v in res["generations"].items():
        for r in v["rows"]:
            cv = f"{r.get('cv', 0):.3f}" if "cv" in r else "-"
            lines.append(f"| {g} | {r['cell']} | {r['engine']} | {r['variant']} | "
                         f"{r['iops']:,.0f} | {r['steady']} | {r['reason']} | {cv} |")
    md = os.path.join(outdir, "write-state-summary.md")
    with open(md, "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(outdir, "write-state-verdict.json"), "w") as f:
        json.dump(res, f, indent=2)
    csvf = os.path.join(outdir, "write-state-ratios.csv")
    with open(csvf, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["generation", "cell", "comparator", "ratio"])
        gens = res["valid_generations"]
        for cell in ("W7", "W8"):
            for comp in COMPARATORS:
                ratios = res["cells"][cell][comp]["ratios"]
                for g, r in zip(gens, ratios):
                    w.writerow([g, cell, comp, f"{r:.6f}"])
    return md


def survey(d):
    meta = read_meta(d)
    total = steady = 0
    rows = []
    for (cell, engine, variant, bs, qd, rep), r in sorted(meta.items()):
        if r["status"] != "ok":
            continue
        st = stem(cell, engine, variant, bs, qd, rep)
        c = classify(read_series(os.path.join(d, "series", st + ".tsv")))
        total += 1
        if c["steady"]:
            steady += 1
        rows.append((cell, engine, variant, qd, rep, c["steady"], c["reason"], c.get("cv")))
    print(f"survey {d}: {steady}/{total} runs classified steady")
    for r in rows:
        cv = f"{r[7]:.3f}" if r[7] is not None else "-"
        print(f"  {r[0]:4s} {r[1]:12s} {r[2]:12s} qd{r[3]:3s} r{r[4]} steady={str(r[5]):5s} {r[6]:14s} cv={cv}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gen-dir")
    ap.add_argument("--session")
    ap.add_argument("--gens")
    ap.add_argument("--out")
    ap.add_argument("--survey")
    args = ap.parse_args()
    if args.survey:
        survey(args.survey)
        return
    if args.gen_dir:
        v = gen_verdict(args.gen_dir)
        for r in v["rows"]:
            cv = f"{r.get('cv', 0):.3f}" if "cv" in r else "-"
            print(f"{r['cell']} {r['engine']} {r['variant']} status={r['status']} "
                  f"iops={r['iops']:.0f} steady={r['steady']} reason={r['reason']} cv={cv}")
        print(f"PILOT_PASS {1 if v['pass'] else 0}")
        return
    if args.session and args.gens:
        gens = args.gens.split(",")
        res = gate_analysis(args.session, gens)
        if args.out:
            md = write_report(args.out, res)
            print(f"wrote {md}")
            print(f"gate verdict: {res['verdict']} ({res['reason']})")
        else:
            print(json.dumps(res, indent=2))
        return
    ap.error("need --gen-dir, or --session/--gens, or --survey")


if __name__ == "__main__":
    main()
