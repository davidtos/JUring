#!/usr/bin/env python3
"""analyze_m3.py -- M3 W17-W20 campaign analyzer (docs/m3-design.md).

Reads a `bench/shard/run-m3-campaign.sh` session (cell-meta.tsv + raw JSONs +
manifest.txt), computes the FROZEN M3 bars (a)-(e) with one-sided 95 % t CIs
(df = R-1), per-ring queue depth + runtime-completion p99, state-gate flags and
an honest PASS / FAIL / INCONCLUSIVE verdict. Writes aggregate/m3.{json,md}.

This analyzer never trims a rep: every meta row is reported; gated reps stay in
every mean/CI and are only labelled. It fails soft (prints what it can) on
partial/pilot sessions.

Usage:
    python3 bench/shard/analyze_m3.py --session m3-2026-11-15
    python3 bench/shard/analyze_m3.py --results-dir bench/results/<dir>
"""

import argparse
import json
import math
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BASELINE_ENGINES = ["juring-group", "juring-manual", "fio", "seastar"]
BAR_ENGINES = ["juring-group", "fio", "seastar"]
N_ALL = [1, 2, 4, 8]
R_ENV = 5

# one-sided 95 % t quantiles t_{0.95, df} (df = R-1); df>=30 -> 1.645
T95 = {
    1: 6.3138, 2: 2.9200, 3: 2.3534, 4: 2.1318, 5: 2.0150, 6: 1.9432,
    7: 1.8946, 8: 1.8595, 9: 1.8331, 10: 1.8125, 11: 1.7959, 12: 1.7823,
    13: 1.7709, 14: 1.7613, 15: 1.7531, 16: 1.7459, 17: 1.7396, 18: 1.7341,
    19: 1.7291, 20: 1.7247, 21: 1.7207, 22: 1.7171, 23: 1.7139, 24: 1.7109,
    25: 1.7081, 26: 1.7056, 27: 1.7033, 28: 1.7011, 29: 1.6991, 30: 1.6973,
}


def t95(df):
    return T95.get(df, 1.645)


def mean(v):
    return sum(v) / len(v) if v else None


def stdev(v):
    if len(v) < 2:
        return 0.0
    m = mean(v)
    return math.sqrt(sum((x - m) ** 2 for x in v) / (len(v) - 1))


def one_sided_ci(v):
    """Return (mean, sd, lb, ub) for one-sided 95 % t CI (df = n-1)."""
    if not v:
        return None, None, None, None
    m = mean(v)
    if len(v) < 2:
        return m, 0.0, m, m
    s = stdev(v)
    half = t95(len(v) - 1) * s / math.sqrt(len(v))
    return m, s, m - half, m + half


def classify_ge(m, lb, thr):
    """Bar 'mean/ratio >= thr', decided on the one-sided 95 % lower bound."""
    if m is None:
        return "INCONCLUSIVE"
    if lb is not None and lb >= thr:
        return "PASS"
    if m < thr:
        return "FAIL"
    return "INCONCLUSIVE"


def classify_le(m, ub, thr):
    if m is None:
        return "INCONCLUSIVE"
    if ub is not None and ub <= thr:
        return "PASS"
    if m > thr:
        return "FAIL"
    return "INCONCLUSIVE"


def fnum(x, digits=1, dash="n/a"):
    if x is None:
        return dash
    try:
        return f"{x:,.{digits}f}"
    except (TypeError, ValueError):
        return dash


def pct(x, digits=1):
    return "n/a" if x is None else f"{100.0 * x:.{digits}f} %"


def load_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def read_meta(path):
    rows = []
    if not os.path.isfile(path):
        return rows
    with open(path, encoding="utf-8") as f:
        lines = f.read().splitlines()
    if not lines:
        return rows
    head = lines[0].split("\t")
    for line in lines[1:]:
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) != len(head):
            continue
        rows.append(dict(zip(head, parts)))
    return rows


def num(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def worker_entries(raw, engine):
    """Return per-worker entries (rings/shards) for a raw JSON record."""
    runs = (raw or {}).get("runs") or []
    run = runs[0] if runs else {}
    if engine == "juring-group":
        ws = run.get("shards") or run.get("threads") or []
    elif engine == "juring-manual":
        ws = run.get("threads") or []
    elif engine == "seastar":
        ws = run.get("shards") or run.get("threads") or []
    else:
        ws = []
    return [w for w in ws if isinstance(w, dict)]


def series_depth(entry):
    """Mean/max in-flight and mean sq/cq from a B5 ring_series (schema 1.1)."""
    series = entry.get("ring_series") or []
    inflight = [num(s.get("in_flight")) for s in series if num(s.get("in_flight")) is not None]
    sq = [num(s.get("sq_queued")) for s in series if num(s.get("sq_queued")) is not None]
    cq = [num(s.get("cq_ready")) for s in series if num(s.get("cq_ready")) is not None]
    return {
        "series_points": len(series),
        "mean_in_flight": mean(inflight),
        "max_in_flight": max(inflight) if inflight else None,
        "mean_sq_queued": mean(sq),
        "mean_cq_ready": mean(cq),
    }


def ring_metrics(entry):
    rm = entry.get("ring_metrics") or {}
    return {
        "failed": num(rm.get("failed")),
        "cancelled": num(rm.get("cancelled")),
        "timed_out": num(rm.get("timed_out")),
        "submitted": num(rm.get("submitted")),
        "completed": num(rm.get("completed")),
        "in_flight": num(rm.get("in_flight")),
    }


def normalize(engine, raw, meta):
    """Normalize one invocation into the fields the analyzer needs."""
    rec = {
        "engine": engine,
        "N": int(meta["N"].lstrip("N")),
        "rep": int(meta["rep"]),
        "status": meta.get("status", "?"),
        "cpus": meta.get("cpus", ""),
        "gate_start": meta.get("gate_start", ""),
        "gate_end": meta.get("gate_end", ""),
        "wall_s": num(meta.get("wall_s")),
        "iops": num(meta.get("iops")),
        "p50_us": num(meta.get("p50_us")),
        "p99_us": num(meta.get("p99_us")),
        "ops": num(meta.get("ops")),
        "raw_ok": raw is not None,
        "per_shard": [],
        "per_ring": [],
        "b5_available": False,
        "workers": 0,
        "aggregate_ring": None,
        "aggregate_dispatch_p99": None,
        "manifest": (raw or {}).get("manifest") or {},
        "config": (raw or {}).get("config") or {},
    }
    if engine == "fio":
        jobs = (raw or {}).get("jobs") or []
        total = sum(num((j.get("read") or {}).get("iops")) or 0.0 for j in jobs)
        rec["iops"] = total if total > 0 else rec["iops"]
        p99s = []
        for j in jobs:
            pct = ((j.get("read") or {}).get("clat_ns") or {}).get("percentile") or {}
            if pct.get("99.000000") is not None:
                p99s.append(float(pct["99.000000"]) / 1000.0)
        if p99s:
            rec["p99_us"] = sum(p99s) / len(p99s)
        return rec

    runs = (raw or {}).get("runs") or []
    run = runs[0] if runs else {}
    lat = run.get("latency_us") or {}
    if run.get("iops") is not None:
        rec["iops"] = float(run["iops"])
    if lat.get("p50") is not None:
        rec["p50_us"] = float(lat["p50"])
    if lat.get("p99") is not None:
        rec["p99_us"] = float(lat["p99"])
    if run.get("errors") is not None:
        rec["errors"] = int(run["errors"])
    elif run.get("errors_total") is not None:
        rec["errors"] = int(run["errors_total"])

    ws = worker_entries(raw, engine)
    rec["workers"] = len(ws)
    for i, w in enumerate(ws):
        wid = w.get("shard", w.get("thread", i))
        rec["per_shard"].append({
            "id": wid,
            "iops": num(w.get("iops")),
            "p50_us": num((w.get("latency_us") or {}).get("p50")),
            "p99_us": num((w.get("latency_us") or {}).get("p99")),
            "dispatch_p99_us": num((w.get("dispatch_latency_us") or {}).get("p99")),
        })
        depth = series_depth(w)
        rm = ring_metrics(w)
        has_b5 = bool(w.get("ring_metrics")) or bool(w.get("ring_series")) or bool(w.get("dispatch_latency_us"))
        if has_b5:
            rec["b5_available"] = True
        rec["per_ring"].append({
            "worker": wid,
            "depth": depth,
            "ring_metrics": rm,
            "dispatch_p99_us": num((w.get("dispatch_latency_us") or {}).get("p99")),
            "external_p99_us": num((w.get("latency_us") or {}).get("p99")),
            "iops": num(w.get("iops")),
        })
    rec["aggregate_ring"] = run.get("ring_queue_depth") or (raw or {}).get("aggregate", {}).get("ring_queue_depth")
    rec["aggregate_dispatch_p99"] = run.get("dispatch_latency_us") or (raw or {}).get("aggregate", {}).get("dispatch_latency_us")
    return rec


def load_session(results_dir):
    meta = read_meta(os.path.join(results_dir, "cell-meta.tsv"))
    recs = []
    for m in meta:
        engine = m.get("engine", "?")
        # cell-meta.tsv `cell` is "N<n>"; the raw file is
        # cells/N<n>__<engine>__r<rep>.json (same convention as the driver).
        raw_name = "%s__%s__r%s.json" % (m.get("cell", ""), engine, m.get("rep", ""))
        path = os.path.join(results_dir, "cells", raw_name)
        raw = load_json(path)
        rec = normalize(engine, raw, m)
        rec["cell"] = raw_name[:-5] if raw is not None else m.get("cell", "")
        rec["raw_path"] = path
        recs.append(rec)
    return recs


def cells_by(recs):
    d = {}
    for r in recs:
        d.setdefault((r["engine"], r["N"]), []).append(r)
    return d


def good(recs):
    return [r for r in recs if r["status"] in ("ok", "gate") and (r["iops"] or 0) > 0]


def cell_stat(recs):
    g = good(recs)
    iops = [r["iops"] for r in g]
    m, s, lb, ub = one_sided_ci(iops)
    p99 = [r["p99_us"] for r in g if r["p99_us"] is not None]
    pm, ps, plb, pub = one_sided_ci(p99)
    return {
        "n": len(iops),
        "statuses": {st: sum(1 for r in recs if r["status"] == st) for st in sorted({r["status"] for r in recs})},
        "iops": {"mean": m, "sd": s, "lb": lb, "ub": ub,
                 "min": min(iops) if iops else None, "max": max(iops) if iops else None,
                 "cv_pct": (100.0 * s / m) if (m and s is not None) else None},
        "p99_us": {"mean": pm, "sd": ps, "lb": plb, "ub": pub},
        "all": g,
    }


def per_rep_map(recs):
    return {(r["rep"]): r for r in good(recs)}


def ratio_series(a_by_rep, b_by_rep, reps):
    """Per-rep a/b ratios for reps present in both, using the given getters."""
    out = []
    for rep in reps:
        a = a_by_rep.get(rep)
        b = b_by_rep.get(rep)
        if a is None or b is None:
            continue
        try:
            out.append((rep, a / b))
        except ZeroDivisionError:
            continue
    return out


def gate_label(rec, cell_max):
    flags = []
    if cell_max and rec["iops"] and rec["iops"] < 0.92 * cell_max:
        flags.append("iops-anomaly")
    if rec["p99_us"] is not None and rec["p99_us"] >= 600.0 * rec["N"]:
        flags.append("slow-state-p99")
    if rec["gate_start"] not in ("", "ok") and not str(rec["gate_start"]).startswith("own-residual"):
        flags.append(f"gate-start:{rec['gate_start']}")
    if rec["gate_end"] not in ("", "ok") and not str(rec["gate_end"]).startswith("own-residual"):
        flags.append(f"gate-end:{rec['gate_end']}")
    return flags


def analyze(results_dir):
    recs = load_session(results_dir)
    by = cells_by(recs)
    engines = [e for e in BASELINE_ENGINES if any(r["engine"] == e for r in recs)]
    ns = sorted({r["N"] for r in recs})

    # cell statistics
    cells = {}
    for (engine, n), rs in by.items():
        cells[(engine, n)] = cell_stat(rs)

    # per-cell max for the anomaly rule
    cell_max = {}
    for (engine, n), rs in by.items():
        iops = [r["iops"] for r in good(rs)]
        cell_max[(engine, n)] = max(iops) if iops else None

    # per-rep state gate labels (all reps, never trimmed)
    gate_flags = {}
    for r in recs:
        gate_flags[id(r)] = gate_label(r, cell_max.get((r["engine"], r["N"])))

    reps = list(range(1, max([r["rep"] for r in recs] + [R_ENV]) + 1))
    rep_range = [rep for rep in reps if any(r["rep"] == rep for r in recs)]

    def getter(engine, n):
        return per_rep_map(by.get((engine, n), []))

    # ---- bar (a): N-shard throughput vs fio at the same N ------------------
    bar_a = {}
    for n in N_ALL:
        j = getter("juring-group", n)
        f = getter("fio", n)
        series = ratio_series({k: v["iops"] for k, v in j.items()},
                              {k: v["iops"] for k, v in f.items()}, rep_range)
        vals = [v for _, v in series]
        m, s, lb, ub = one_sided_ci(vals)
        cj = cells.get(("juring-group", n), {})
        cf = cells.get(("fio", n), {})
        bar_a[n] = {
            "present": bool(vals),
            "reps": {str(k): v for k, v in series},
            "ratio": {"mean": m, "sd": s, "lb": lb, "ub": ub, "n": len(vals)},
            "juring_iops": (cj.get("iops") or {}).get("mean"),
            "fio_iops": (cf.get("iops") or {}).get("mean"),
            "verdict": classify_ge(m, lb, 0.85),
        }

    # ---- bar (b): SE and p99 vs fio at the same N (no N*IOPS(1)) -----------
    # A1: this is a reporting/comparison bar (the brief attaches no numeric
    # threshold); PASS iff both ratios are present with R reps at every N.
    expected_r = max([r["rep"] for r in recs] + [1])
    bar_b = {}
    for n in N_ALL:
        jn, j1 = getter("juring-group", n), getter("juring-group", 1)
        fn, f1 = getter("fio", n), getter("fio", 1)
        se_ratio = []
        se_j = []
        se_f = []
        p99_ratio = []
        for rep in rep_range:
            if all(rep in d for d in (jn, j1, fn, f1)):
                try:
                    sej = jn[rep]["iops"] / (n * j1[rep]["iops"])
                    sef = fn[rep]["iops"] / (n * f1[rep]["iops"])
                    se_j.append(sej)
                    se_f.append(sef)
                    if sef:
                        se_ratio.append((rep, sej / sef))
                    if fn[rep]["p99_us"] and jn[rep]["p99_us"]:
                        p99_ratio.append((rep, jn[rep]["p99_us"] / fn[rep]["p99_us"]))
                except (ZeroDivisionError, TypeError):
                    continue
        se_vals = [v for _, v in se_ratio]
        p_vals = [v for _, v in p99_ratio]
        sm, ss, slb, sub = one_sided_ci(se_vals)
        pm, ps, plb, pub = one_sided_ci(p_vals)
        bar_b[n] = {
            "present": bool(se_vals) and bool(p_vals),
            "se_juring": mean(se_j),
            "se_fio": mean(se_f),
            "se_ratio": {"mean": sm, "sd": ss, "lb": slb, "ub": sub, "n": len(se_vals),
                         "reps": {str(k): v for k, v in se_ratio}},
            "p99_ratio": {"mean": pm, "sd": ps, "lb": plb, "ub": pub, "n": len(p_vals),
                          "reps": {str(k): v for k, v in p99_ratio}},
        }
        complete = len(se_vals) >= expected_r and len(p_vals) >= expected_r
        bar_b[n]["verdict"] = "PASS" if complete else "INCONCLUSIVE"

    # ---- bar (c): per-shard balance at N=8 --------------------------------
    spreads = []
    spread_reps = {}
    for r in good(by.get(("juring-group", 8), [])):
        iops = [s["iops"] for s in r["per_shard"] if s["iops"]]
        if iops:
            m = mean(iops)
            if m:
                sp = min(iops) / m
                spreads.append(sp)
                spread_reps[str(r["rep"])] = sp
    cm, cs, clb, cub = one_sided_ci(spreads)
    bar_c = {
        "present": bool(spreads),
        "spread": {"mean": cm, "sd": cs, "lb": clb, "ub": cub, "n": len(spreads),
                   "reps": spread_reps},
        "verdict": classify_ge(cm, clb, 0.85),
    }

    # ---- bar (d): seastar io_uring total IOPS at N=8 ----------------------
    j8, s8 = getter("juring-group", 8), getter("seastar", 8)
    fwd = ratio_series({k: v["iops"] for k, v in j8.items()},
                       {k: v["iops"] for k, v in s8.items()}, rep_range)
    rev = ratio_series({k: v["iops"] for k, v in s8.items()},
                       {k: v["iops"] for k, v in j8.items()}, rep_range)
    fvals = [v for _, v in fwd]
    rvals = [v for _, v in rev]
    fmm, fsd, flb, fub = one_sided_ci(fvals)
    rmm, rsd, rlb, rub = one_sided_ci(rvals)
    bar_d = {
        "present": bool(fvals),
        "juring_over_seastar": {"mean": fmm, "sd": fsd, "lb": flb, "ub": fub, "n": len(fvals),
                                "reps": {str(k): v for k, v in fwd}},
        "seastar_over_juring": {"mean": rmm, "sd": rsd, "lb": rlb, "ub": rub,
                                "reps": {str(k): v for k, v in rev}},
        "juring_iops": (cells.get(("juring-group", 8), {}).get("iops") or {}).get("mean"),
        "seastar_iops": (cells.get(("seastar", 8), {}).get("iops") or {}).get("mean"),
        # A1 frozen decision: brief-literal direction seastar/JUring >= 0.90.
        "verdict": classify_ge(rmm, rlb, 0.90),
    }

    # ---- bar (e): per-ring queue depth + runtime-completion p99 coverage ---
    per_ring = []
    ring_cells = []
    for n in N_ALL:
        rs = good(by.get(("juring-group", n), []))
        covered = 0
        for r in rs:
            if r["b5_available"] and r["per_ring"]:
                covered += 1
                for w in r["per_ring"]:
                    per_ring.append({
                        "N": n,
                        "rep": r["rep"],
                        "worker": w["worker"],
                        "mean_in_flight": (w["depth"] or {}).get("mean_in_flight"),
                        "max_in_flight": (w["depth"] or {}).get("max_in_flight"),
                        "mean_sq_queued": (w["depth"] or {}).get("mean_sq_queued"),
                        "mean_cq_ready": (w["depth"] or {}).get("mean_cq_ready"),
                        "dispatch_p99_us": w.get("dispatch_p99_us"),
                        "external_p99_us": w.get("external_p99_us"),
                        "failed": (w["ring_metrics"] or {}).get("failed"),
                        "cancelled": (w["ring_metrics"] or {}).get("cancelled"),
                        "timed_out": (w["ring_metrics"] or {}).get("timed_out"),
                    })
        ring_cells.append({"N": n, "good_reps": len(rs), "covered_reps": covered})
    ring_ok_cells = [c for c in ring_cells if c["covered_reps"] == c["good_reps"] and c["good_reps"] > 0]
    if len(ring_ok_cells) == len([c for c in ring_cells if c["good_reps"] > 0]) and ring_ok_cells:
        bar_e_verdict = "PASS"
    else:
        bar_e_verdict = "INCONCLUSIVE"
    bar_e = {"present": bool(per_ring), "cells": ring_cells, "verdict": bar_e_verdict}

    bars = {"(a)": None, "(b)": None, "(c)": bar_c["verdict"], "(d)": bar_d["verdict"],
            "(e)": bar_e_verdict}
    # aggregate (a)/(b) over their required N values
    a_verdicts = [bar_a[n]["verdict"] for n in N_ALL]
    if "FAIL" in a_verdicts:
        bars["(a)"] = "FAIL"
    elif all(v == "PASS" for v in a_verdicts):
        bars["(a)"] = "PASS"
    else:
        bars["(a)"] = "INCONCLUSIVE"
    b_verdicts = [bar_b[n]["verdict"] for n in N_ALL]
    bars["(b)"] = "PASS" if all(v == "PASS" for v in b_verdicts) else "INCONCLUSIVE"

    signing = True
    for engine in BAR_ENGINES:
        for n in N_ALL:
            c = cells.get((engine, n))
            if not c or c["n"] < expected_r:
                signing = False
    signing = bool(signing and expected_r >= 5)
    if any(v == "FAIL" for v in bars.values()):
        verdict = "FAIL"
    elif any(v == "INCONCLUSIVE" for v in bars.values()):
        verdict = "INCONCLUSIVE"
    else:
        verdict = "PASS"

    missing_raw = [r["raw_path"] for r in recs if not r["raw_ok"]]
    return {
        "results_dir": results_dir,
        "missing_raw": missing_raw,
        "engines": engines,
        "n_values": ns,
        "reps_seen": rep_range,
        "invocations": len(recs),
        "statuses": {st: sum(1 for r in recs if r["status"] == st) for st in sorted({r["status"] for r in recs})},
        "bars": bars,
        "verdict": verdict,
        "signing": signing,
        "expected_reps": expected_r,
        "bar_a": bar_a,
        "bar_b": bar_b,
        "bar_c": bar_c,
        "bar_d": bar_d,
        "bar_e": bar_e,
        "cells": {f"{e}|N{n}": v for (e, n), v in cells.items()},
        "per_ring": per_ring,
        "gated_reps": [
            {"cell": r["cell"], "engine": r["engine"], "N": r["N"], "rep": r["rep"],
             "status": r["status"], "iops": r["iops"], "p99_us": r["p99_us"],
             "flags": gate_label(r, cell_max.get((r["engine"], r["N"])))}
            for r in recs
        ],
        "manifest": read_manifest(os.path.join(results_dir, "manifest.txt")),
    }


def read_manifest(path):
    out = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if line.startswith("design sha1 (expected):"):
                    out["design_sha1"] = line.split(":", 1)[1].strip()
                elif line.startswith("live src/main/java content sha1:"):
                    out["src_main_sha1"] = line.split(":", 1)[1].strip()
                elif line.startswith("live src/test/java content sha1:"):
                    out["src_test_sha1"] = line.split(":", 1)[1].strip()
                elif line.startswith("git HEAD:"):
                    out["head"] = line.split(":", 1)[1].strip()
                elif line.startswith("design freeze commit:"):
                    out["design_commit"] = line.split(":", 1)[1].strip()
                elif line.startswith("invocations expected:"):
                    out["invocations_expected"] = num(line.split(":", 1)[1])
    except OSError:
        pass
    return out


# ---------------------------------------------------------------------------
# markdown
# ---------------------------------------------------------------------------

def fmt_ci(stat, digits=0):
    if not stat or stat.get("mean") is None:
        return "n/a"
    return (f"{stat['mean']:,.{digits}f} "
            f"[{stat.get('lb', 0):,.{digits}f}, {stat.get('ub', 0):,.{digits}f}]")


def render_markdown(session, analysis):
    L = []
    a = L.append
    verdict = analysis["verdict"]
    bars = analysis["bars"]
    a(f"# M3 W17–W20 campaign analysis — `{session}`")
    a("")
    a(f"**Verdict: {verdict}** (M3 is signed only if every frozen bar passes; this "
      "analyzer is generated from raw JSON and never trims a rep).")
    a("")
    if not analysis.get("signing"):
        a(f"**This session is NOT M3-signing**: R={analysis.get('expected_reps')} / incomplete "
          "engine-N coverage (pilot or partial session). Only the official R=5, all-cells session "
          "can sign M3.")
        a("")
    a("Generated by `bench/shard/analyze_m3.py` from `cell-meta.tsv` + `cells/*.json`. "
      "Bar rules are the frozen ones in `docs/m3-design.md` §1.")
    a("")
    a("## 0. Frozen bars")
    a("")
    a("| bar | rule | result |")
    a("|---|---|---|")
    a(f"| (a) throughput vs fio at same N | one-sided 95 % LB of JUring/fio ≥ 0.85 for N=1,2,4,8 | **{bars['(a)']}** |")
    a(f"| (b) SE + p99 vs fio at same N | reporting bar (brief-literal, no numeric threshold): both ratios present per rep with one-sided 95 % CIs at every N | **{bars['(b)']}** |")
    a(f"| (c) per-shard min/mean (juring-group N=8) | one-sided 95 % LB ≥ 0.85 | **{bars['(c)']}** |")
    a(f"| (d) seastar io_uring N=8 | brief-literal: LB(seastar/JUring) ≥ 0.90 (plan-direction JUring/seastar also reported) | **{bars['(d)']}** |")
    a(f"| (e) per-ring queue depth + runtime p99 | present for all juring-group cells | **{bars['(e)']}** |")
    a("")
    a("B5 metrics <1 % overhead is already PASS (`bench/results/b5-overhead-2026-11-14/`) "
      "and is not re-run by M3.")
    a("")
    a("## 1. Session / identity / protocol")
    a("")
    man = analysis.get("manifest") or {}
    a(f"- invocations in meta: **{analysis['invocations']}**; statuses: "
      + ", ".join(f"`{k}`={v}" for k, v in analysis["statuses"].items()))
    if analysis.get("missing_raw"):
        a(f"- **WARNING: {len(analysis['missing_raw'])} raw JSON file(s) missing/unparseable**; "
          "bars needing raw fields (c/e) are INCONCLUSIVE. " + ", ".join(analysis["missing_raw"][:5]))
    a(f"- design sha1: `{man.get('design_sha1', 'n/a')}`; frozen commit: `{man.get('design_commit', 'n/a')}`")
    a(f"- src/main sha1: `{man.get('src_main_sha1', 'n/a')}`; src/test sha1: `{man.get('src_test_sha1', 'n/a')}`")
    a(f"- git HEAD at session: `{man.get('head', 'n/a')}`")
    a("- Protocol: 4K randread, QD 64 per worker/shard, 10 s warmup + 30 s measured, R=5, "
      "300 s rest per invocation, fresh process/JVM, engines interleaved with order alternating "
      "per rep block, ALL reps kept. DS-S shared 10 GiB read-only file; no DS-M.")
    a("")
    a("## 2. Cell summary (all reps, nothing trimmed)")
    a("")
    a("| engine | N | n | mean IOPS | sd | one-sided 95 % CI (mean [lb, ub]) | min | max | CV | mean p99 µs |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for engine in analysis["engines"]:
        for n in analysis["n_values"]:
            c = analysis["cells"].get(f"{engine}|N{n}")
            if not c or not c.get("n"):
                continue
            i, p = c["iops"], c["p99_us"]
            a(f"| {engine} | {n} | {c['n']} | {fmt_ci(i, 0)} | {i['sd']:,.0f} | "
              f"{i['mean']:,.0f} [{i['lb']:,.0f}, {i['ub']:,.0f}] | {i['min']:,.0f} | "
              f"{i['max']:,.0f} | {i['cv_pct']:.1f} % | {fnum(p['mean'])} |")
    a("")
    a("## 3. Bars (a)–(b): per-rep ratios, one-sided 95 % t CI (df=R−1)")
    a("")
    a("### (a) JUring-group IOPS / fio IOPS at the same N")
    a("")
    a("| N | juring mean IOPS | fio mean IOPS | ratio mean | sd | 95 % LB | reps (ratio) | result |")
    a("|---|---|---|---|---|---|---|---|")
    for n in N_ALL:
        b = analysis["bar_a"][n]
        r = b["ratio"]
        reps = " ".join(f"r{k}={v:.3f}" for k, v in b["reps"].items())
        a(f"| {n} | {fnum(b['juring_iops'], 0)} | {fnum(b['fio_iops'], 0)} | "
          f"{fnum(r['mean'], 3)} | {fnum(r['sd'], 3)} | {fnum(r['lb'], 3)} | {reps or 'n/a'} | **{b['verdict']}** |")
    a("")
    a("### (b) SE(N) and p99(N) vs fio at the same N (no N·IOPS(1) cross-engine denominator)")
    a("")
    a("| N | SE juring | SE fio | SE_j/SE_f mean | 95 % LB | p99_j/p99_f mean | 95 % UB | result |")
    a("|---|---|---|---|---|---|---|---|")
    for n in N_ALL:
        b = analysis["bar_b"][n]
        sr, pr = b["se_ratio"], b["p99_ratio"]
        disp = b["verdict"] if n in (4, 8) else "reported (bracket)"
        a(f"| {n} | {fnum(b['se_juring'], 3)} | {fnum(b['se_fio'], 3)} | {fnum(sr['mean'], 3)} | "
          f"{fnum(sr['lb'], 3)} | {fnum(pr['mean'], 3)} | {fnum(pr['ub'], 3)} | **{disp}** |")
    a("")
    a("SE(N) = IOPS(N)/(N·IOPS(1)) computed per engine from this campaign's cells; the ratio is "
      "paired by rep index. Bar (b) is the brief's reporting/comparison bar (Amendment A1): it has "
      "no numeric threshold; it PASSES when both ratios are present per rep with one-sided 95 % CIs "
      "at every N. N=1,2 are bracket rows, N=4,8 the reported comparison. The p99 ratio compares "
      "engine-native latency definitions (see §7) and is disclosed, never gate-tuned.")
    a("")
    a("## 4. Barriers (c)–(e)")
    a("")
    c = analysis["bar_c"]
    a(f"- **(c) per-shard min/mean at N=8 (juring-group):** mean {fnum(c['spread']['mean'], 3)}, "
      f"sd {fnum(c['spread']['sd'], 3)}, one-sided 95 % LB {fnum(c['spread']['lb'], 3)}; "
      f"per-rep " + ", ".join(f"r{k}={v:.3f}" for k, v in c["spread"]["reps"].items()) + f" ⇒ **{c['verdict']}**")
    d = analysis["bar_d"]
    a(f"- **(d) seastar io_uring at N=8:** juring mean {fnum(d['juring_iops'], 0)}, seastar mean "
      f"{fnum(d['seastar_iops'], 0)}. Brief-literal bar ratio seastar/JUring mean "
      f"{fnum(d['seastar_over_juring']['mean'], 3)} (one-sided 95 % LB "
      f"{fnum(d['seastar_over_juring']['lb'], 3)} ⇒ **{d['verdict']}**); plan-direction "
      f"JUring/seastar mean {fnum(d['juring_over_seastar']['mean'], 3)} (LB "
      f"{fnum(d['juring_over_seastar']['lb'], 3)}) is reported for transparency.")
    e = analysis["bar_e"]
    a(f"- **(e) per-ring coverage:** " + ", ".join(
        f"N={cc['N']} {cc['covered_reps']}/{cc['good_reps']} reps" for cc in e["cells"]) + f" ⇒ **{e['verdict']}**")
    a("")
    a("## 5. Per-ring queue depth + runtime-completion p99 (juring-group, B5 schema 1.1)")
    a("")
    if analysis["per_ring"]:
        a("| N | rep | ring | mean/max in-flight | mean sq_queued | mean cq_ready | "
          "runtime p99 µs (registration→dispatch) | external p99 µs | failed/cancelled/timedout |")
        a("|---|---|---|---|---|---|---|---|---|")
        for row in analysis["per_ring"]:
            a(f"| {row['N']} | {row['rep']} | {row['worker']} | "
              f"{fnum(row['mean_in_flight'])}/{fnum(row['max_in_flight'], 0)} | "
              f"{fnum(row['mean_sq_queued'], 2)} | {fnum(row['mean_cq_ready'], 2)} | "
              f"{fnum(row['dispatch_p99_us'])} | {fnum(row['external_p99_us'])} | "
              f"{fnum(row['failed'], 0)}/{fnum(row['cancelled'], 0)}/{fnum(row['timed_out'], 0)} |")
        a("")
        a("`runtime p99` is the B5 `dispatch_latency_us.p99` (registration→dispatch; includes "
          "kernel/device completion; **not** `fio clat` and **not** the external `latency_us`). "
          "External p99 starts before SQE preparation and is the value used in bar (b).")
    else:
        a("No schema-1.1 per-ring data found (old session or `--metrics off`).")
    a("")
    a("## 6. State-gate disclosure (never trimmed)")
    a("")
    gated = [g for g in analysis["gated_reps"] if g["flags"]]
    if gated:
        a("| cell | engine | N | rep | status | IOPS | external p99 µs | flags |")
        a("|---|---|---|---|---|---|---|---|")
        for g in gated:
            a(f"| {g['cell']} | {g['engine']} | {g['N']} | {g['rep']} | {g['status']} | "
              f"{fnum(g['iops'], 0)} | {fnum(g['p99_us'])} | {', '.join(g['flags'])} |")
        a("")
        a("Gated reps stay in every mean/CI above; the flags are disclosure, not trimming.")
    else:
        a("No rep tripped the IOPS-anomaly / cell-adjusted-p99 / load gate.")
    a("")
    a("## 7. Reading notes / residual risks")
    a("")
    a("- This is a single-device, constant-QD-per-shard protocol: N·IOPS(1) is capacity-bounded "
      "by the SSD, which is exactly why the plan's original `SE(8)≥0.75` and `p99(8)≤1.15·p99(1)` "
      "were restated vs `fio` at the same N (B4 §6/§8).")
    a("- Latency definitions are engine-native and not identical: JUring external = before "
      "prepare/submit → completion; fio = `clat` (submission→completion); seastar = before "
      "submit → completion. Cross-engine p99 is only used as the frozen same-N ratio, never as "
      "an absolute ranking; the B5 runtime-completion p99 is a separate per-ring metric.")
    a("- `fio` was run with `--group_reporting=1` (frozen template), so fio per-job spread is "
      "`n/a`; bar (c) is evaluated on `juring-group` shards.")
    a("- N=8 placement: hctx CPU set for fio/seastar; `juring-group` unpinned inside the same "
      "taskset mask (frozen design §3). `mq/*/cpu_list` is in `manifest.txt`.")
    a("- All engine asymmetries (seastar 200-entry ring, no registered files/fixed buffers; "
      "JUring registered file+buffers and DEFER_TASKRUN) are disclosed, not equalised.")
    a("")
    a(f"Raw session: `{analysis['results_dir']}` (gitignored); `aggregate/m3.json` carries the "
      "same data machine-readably.")
    a("")
    return "\n".join(L)


def main(argv=None):
    ap = argparse.ArgumentParser(description="M3 campaign analyzer")
    ap.add_argument("--session", help="session name under bench/results/")
    ap.add_argument("--results-dir", help="explicit session directory")
    ap.add_argument("--out-dir", help="output directory (default <session>/aggregate)")
    args = ap.parse_args(argv)
    if args.results_dir:
        results_dir = os.path.abspath(args.results_dir)
        session = os.path.basename(results_dir.rstrip("/"))
    elif args.session:
        results_dir = os.path.join(REPO, "bench", "results", args.session)
        session = args.session
    else:
        ap.error("one of --session or --results-dir is required")
    if not os.path.isdir(results_dir):
        sys.exit(f"error: session directory not found: {results_dir}")
    out_dir = args.out_dir or os.path.join(results_dir, "aggregate")
    os.makedirs(out_dir, exist_ok=True)

    analysis = analyze(results_dir)
    md = render_markdown(session, analysis)
    with open(os.path.join(out_dir, "m3.md"), "w", encoding="utf-8") as f:
        f.write(md + "\n")
    with open(os.path.join(out_dir, "m3.json"), "w", encoding="utf-8") as f:
        json.dump(analysis, f, indent=2, default=str)
    print(f"[analyze_m3] {os.path.join(out_dir, 'm3.md')}")
    print(f"[analyze_m3] verdict: {analysis['verdict']}  bars: "
          + ", ".join(f"{k}={v}" for k, v in analysis["bars"].items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
