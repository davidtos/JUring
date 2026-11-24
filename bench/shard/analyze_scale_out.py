#!/usr/bin/env python3
"""Multi-shard scaling campaign analyzer -- protocol frozen before the campaign.

Reads a ``bench/shard/run-scale-out.sh`` session (cell-meta.tsv, cells/*.json,
manifest.txt) and writes ``aggregate/scale-out.{json,md}``: the frozen bars (a)-(e) with
one-sided 95 % t CIs (df = R-1), per-ring queue depth + runtime-completion p99,
state-gate disclosure and a PASS / FAIL / INCONCLUSIVE verdict.  Every meta row
is reported; gated reps stay in every mean/CI and are only labelled.

Usage:
    python3 bench/shard/analyze_scale_out.py --session scale-out
    python3 bench/shard/analyze_scale_out.py --results-dir bench/results/<dir>
"""

import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import _cells  # noqa: E402
from analysis import cells as cellmap  # noqa: E402
from analysis import cli, jsonio, md, stats  # noqa: E402

BASELINE_ENGINES = ["juring-group", "juring-manual", "fio", "seastar"]
BAR_ENGINES = ["juring-group", "fio", "seastar"]
N_ALL = [1, 2, 4, 8]
R_ENV = 5

# manifest.txt fields this campaign cites; jsonio.read_manifest keeps the raw
# "key: value" spelling, so map it to the names the report/payload use.
MANIFEST_FIELDS = {
    "live src/main/java content sha1": "src_main_sha1",
    "live src/test/java content sha1": "src_test_sha1",
    "git HEAD": "head",
    "invocations expected": "invocations_expected",
}


def one_sided_ci(values):
    """``(mean, sd, lb, ub)`` one-sided 95 % t CI (df = n-1).

    The frozen campaign table is 4 dp (:data:`stats.T95_ONE_SIDED_4DP`); the shared
    :func:`stats.one_sided_ci` uses the 3 dp table, which moves the published
    CI digits, so this campaign keeps this tuple form on the 4 dp table.
    """
    if not values:
        return None, None, None, None
    m = stats.mean(values)
    if len(values) < 2:
        return m, 0.0, m, m
    s = stats.sd(values)
    half = stats.T95_ONE_SIDED_4DP.get(len(values) - 1, 1.645) * s / math.sqrt(len(values))
    return m, s, m - half, m + half


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
    """Mean/max in-flight and mean sq/cq from a metrics-on ring_series (schema 1.1)."""
    series = entry.get("ring_series") or []
    inflight = [v for s in series if (v := jsonio.as_float(s.get("in_flight"))) is not None]
    sq = [v for s in series if (v := jsonio.as_float(s.get("sq_queued"))) is not None]
    cq = [v for s in series if (v := jsonio.as_float(s.get("cq_ready"))) is not None]
    return {
        "series_points": len(series),
        "mean_in_flight": stats.mean(inflight),
        "max_in_flight": max(inflight) if inflight else None,
        "mean_sq_queued": stats.mean(sq),
        "mean_cq_ready": stats.mean(cq),
    }


def ring_metrics(entry):
    rm = entry.get("ring_metrics") or {}
    return {
        "failed": jsonio.as_float(rm.get("failed")),
        "cancelled": jsonio.as_float(rm.get("cancelled")),
        "timed_out": jsonio.as_float(rm.get("timed_out")),
        "submitted": jsonio.as_float(rm.get("submitted")),
        "completed": jsonio.as_float(rm.get("completed")),
        "in_flight": jsonio.as_float(rm.get("in_flight")),
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
        "wall_s": jsonio.as_float(meta.get("wall_s")),
        "iops": jsonio.as_float(meta.get("iops")),
        "p50_us": jsonio.as_float(meta.get("p50_us")),
        "p99_us": jsonio.as_float(meta.get("p99_us")),
        "ops": jsonio.as_float(meta.get("ops")),
        "raw_ok": raw is not None,
        "per_shard": [],
        "per_ring": [],
        "metrics_available": False,
        "workers": 0,
        "aggregate_ring": None,
        "aggregate_dispatch_p99": None,
        "manifest": (raw or {}).get("manifest") or {},
        "config": (raw or {}).get("config") or {},
    }
    if engine == "fio":
        jobs = (raw or {}).get("jobs") or []
        total = sum(jsonio.as_float((j.get("read") or {}).get("iops")) or 0.0 for j in jobs)
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
            "iops": jsonio.as_float(w.get("iops")),
            "p50_us": jsonio.as_float((w.get("latency_us") or {}).get("p50")),
            "p99_us": jsonio.as_float((w.get("latency_us") or {}).get("p99")),
            "dispatch_p99_us": jsonio.as_float((w.get("dispatch_latency_us") or {}).get("p99")),
        })
        depth = series_depth(w)
        rm = ring_metrics(w)
        has_metrics = bool(w.get("ring_metrics")) or bool(w.get("ring_series")) or bool(w.get("dispatch_latency_us"))
        if has_metrics:
            rec["metrics_available"] = True
        rec["per_ring"].append({
            "worker": wid,
            "depth": depth,
            "ring_metrics": rm,
            "dispatch_p99_us": jsonio.as_float((w.get("dispatch_latency_us") or {}).get("p99")),
            "external_p99_us": jsonio.as_float((w.get("latency_us") or {}).get("p99")),
            "iops": jsonio.as_float(w.get("iops")),
        })
    rec["aggregate_ring"] = run.get("ring_queue_depth") or (raw or {}).get("aggregate", {}).get("ring_queue_depth")
    rec["aggregate_dispatch_p99"] = run.get("dispatch_latency_us") or (raw or {}).get("aggregate", {}).get("dispatch_latency_us")
    return rec


def load_session(results_dir):
    meta = jsonio.load_csv_rows(os.path.join(results_dir, "cell-meta.tsv"))
    recs = []
    for m in meta:
        engine = m.get("engine", "?")
        # cell-meta.tsv `cell` is the canonical "shards-<n>" slug; the raw file is
        # cells/<cell>__<engine>__r<rep>.json (same convention as the driver).
        cell = cellmap.slug(m.get("cell", ""))
        path = os.path.join(results_dir, "cells", "%s__%s__r%s.json"
                            % (cell, engine, m.get("rep", "")))
        raw = jsonio.load_json(path)
        rec = normalize(engine, raw, m)
        rec["cell"] = cell
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
    if _cells.gate_violation(rec["gate_start"]):
        flags.append(f"gate-start:{rec['gate_start']}")
    if _cells.gate_violation(rec["gate_end"]):
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
            "verdict": stats.classify_ge(m, lb, 0.85),
        }

    # ---- bar (b): SE and p99 vs fio at the same N (no N*IOPS(1)) -----------
    # Reporting/comparison bar: the protocol attaches no numeric
    # threshold); PASS iff both ratios are present with R reps at every N.
    expected_r = max([r["rep"] for r in recs] + [1])
    bar_b = {}
    for n in N_ALL:
        juring_n = getter("juring-group", n)
        juring_1 = getter("juring-group", 1)
        fio_n = getter("fio", n)
        fio_1 = getter("fio", 1)
        se_ratio = []
        se_j = []
        se_f = []
        p99_ratio = []
        for rep in rep_range:
            if all(rep in d for d in (juring_n, juring_1, fio_n, fio_1)):
                try:
                    sej = juring_n[rep]["iops"] / (n * juring_1[rep]["iops"])
                    sef = fio_n[rep]["iops"] / (n * fio_1[rep]["iops"])
                    se_j.append(sej)
                    se_f.append(sef)
                    if sef:
                        se_ratio.append((rep, sej / sef))
                    if fio_n[rep]["p99_us"] and juring_n[rep]["p99_us"]:
                        p99_ratio.append((rep, juring_n[rep]["p99_us"] / fio_n[rep]["p99_us"]))
                except (ZeroDivisionError, TypeError):
                    continue
        se_vals = [v for _, v in se_ratio]
        p_vals = [v for _, v in p99_ratio]
        sm, ss, slb, sub = one_sided_ci(se_vals)
        pm, ps, plb, pub = one_sided_ci(p_vals)
        bar_b[n] = {
            "present": bool(se_vals) and bool(p_vals),
            "se_juring": stats.mean(se_j),
            "se_fio": stats.mean(se_f),
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
            m = stats.mean(iops)
            if m:
                sp = min(iops) / m
                spreads.append(sp)
                spread_reps[str(r["rep"])] = sp
    cm, cs, clb, cub = one_sided_ci(spreads)
    bar_c = {
        "present": bool(spreads),
        "spread": {"mean": cm, "sd": cs, "lb": clb, "ub": cub, "n": len(spreads),
                   "reps": spread_reps},
        "verdict": stats.classify_ge(cm, clb, 0.85),
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
        # Frozen decision: literal-bar direction seastar/JUring >= 0.90.
        "verdict": stats.classify_ge(rmm, rlb, 0.90),
    }

    # ---- bar (e): per-ring queue depth + runtime-completion p99 coverage ---
    per_ring = []
    ring_cells = []
    for n in N_ALL:
        rs = good(by.get(("juring-group", n), []))
        covered = 0
        for r in rs:
            if r["metrics_available"] and r["per_ring"]:
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
    """The frozen-identity/protocol fields the scaling report cites."""
    out = {}
    for key, value in jsonio.read_manifest(path).items():
        name = MANIFEST_FIELDS.get(key)
        if not name:
            continue
        out[name] = jsonio.as_float(value) if name == "invocations_expected" else value
    return out


# ---------------------------------------------------------------------------
# markdown
# ---------------------------------------------------------------------------

def render_markdown(session, analysis):
    L = []
    a = L.append
    verdict = analysis["verdict"]
    bars = analysis["bars"]
    a(f"# Multi-shard scaling campaign analysis — `{session}`")
    a("")
    a(f"**Verdict: {verdict}** (the campaign is signed only if every frozen bar passes; this "
      "analyzer is generated from raw JSON and never trims a rep).")
    a("")
    if not analysis.get("signing"):
        a(f"**This session cannot sign the campaign**: R={analysis.get('expected_reps')} / incomplete "
          "engine-N coverage (pilot or partial session). Only the official R=5, all-cells session "
          "can sign it.")
        a("")
    a("Generated by `bench/shard/analyze_scale_out.py` from `cell-meta.tsv` + `cells/*.json`. "
      "Bar rules are the frozen ones listed below.")
    a("")
    a("## 0. Frozen bars")
    a("")
    a("| bar | rule | result |")
    a("|---|---|---|")
    a(f"| (a) throughput vs fio at same N | one-sided 95 % LB of JUring/fio ≥ 0.85 for N=1,2,4,8 | **{bars['(a)']}** |")
    a(f"| (b) SE + p99 vs fio at same N | reporting bar (literal reading, no numeric threshold): both ratios present per rep with one-sided 95 % CIs at every N | **{bars['(b)']}** |")
    a(f"| (c) per-shard min/mean (juring-group N=8) | one-sided 95 % LB ≥ 0.85 | **{bars['(c)']}** |")
    a(f"| (d) seastar io_uring N=8 | literal reading: LB(seastar/JUring) ≥ 0.90 (JUring/seastar direction also reported) | **{bars['(d)']}** |")
    a(f"| (e) per-ring queue depth + runtime p99 | present for all juring-group cells | **{bars['(e)']}** |")
    a("")
    a("The metrics <1 % overhead A/B is already PASS (`bench/results/metrics-overhead/`) "
      "and is not re-run by this campaign.")
    a("")
    a("## 1. Session / identity / protocol")
    a("")
    man = analysis.get("manifest") or {}
    a(f"- invocations in meta: **{analysis['invocations']}**; statuses: "
      + ", ".join(f"`{k}`={v}" for k, v in analysis["statuses"].items()))
    if analysis.get("missing_raw"):
        a(f"- **WARNING: {len(analysis['missing_raw'])} raw JSON file(s) missing/unparseable**; "
          "bars needing raw fields (c/e) are INCONCLUSIVE. " + ", ".join(analysis["missing_raw"][:5]))
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
            a(f"| {engine} | {n} | {c['n']} | {md.stat_ci(i, 0)} | {i['sd']:,.0f} | "
              f"{i['mean']:,.0f} [{i['lb']:,.0f}, {i['ub']:,.0f}] | {i['min']:,.0f} | "
              f"{i['max']:,.0f} | {i['cv_pct']:.1f} % | {md.thousands(p['mean'])} |")
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
        a(f"| {n} | {md.thousands(b['juring_iops'], 0)} | {md.thousands(b['fio_iops'], 0)} | "
          f"{md.thousands(r['mean'], 3)} | {md.thousands(r['sd'], 3)} | {md.thousands(r['lb'], 3)} | {reps or 'n/a'} | **{b['verdict']}** |")
    a("")
    a("### (b) SE(N) and p99(N) vs fio at the same N (no N·IOPS(1) cross-engine denominator)")
    a("")
    a("| N | SE juring | SE fio | SE_j/SE_f mean | 95 % LB | p99_j/p99_f mean | 95 % UB | result |")
    a("|---|---|---|---|---|---|---|---|")
    for n in N_ALL:
        b = analysis["bar_b"][n]
        sr, pr = b["se_ratio"], b["p99_ratio"]
        disp = b["verdict"] if n in (4, 8) else "reported (bracket)"
        a(f"| {n} | {md.thousands(b['se_juring'], 3)} | {md.thousands(b['se_fio'], 3)} | {md.thousands(sr['mean'], 3)} | "
          f"{md.thousands(sr['lb'], 3)} | {md.thousands(pr['mean'], 3)} | {md.thousands(pr['ub'], 3)} | **{disp}** |")
    a("")
    a("SE(N) = IOPS(N)/(N·IOPS(1)) computed per engine from this campaign's cells; the ratio is "
      "paired by rep index. Bar (b) is the reporting/comparison bar: it has "
      "no numeric threshold; it PASSES when both ratios are present per rep with one-sided 95 % CIs "
      "at every N. N=1,2 are bracket rows, N=4,8 the reported comparison. The p99 ratio compares "
      "engine-native latency definitions (see §7) and is disclosed, never gate-tuned.")
    a("")
    a("## 4. Barriers (c)–(e)")
    a("")
    c = analysis["bar_c"]
    a(f"- **(c) per-shard min/mean at N=8 (juring-group):** mean {md.thousands(c['spread']['mean'], 3)}, "
      f"sd {md.thousands(c['spread']['sd'], 3)}, one-sided 95 % LB {md.thousands(c['spread']['lb'], 3)}; "
      f"per-rep " + ", ".join(f"r{k}={v:.3f}" for k, v in c["spread"]["reps"].items()) + f" ⇒ **{c['verdict']}**")
    d = analysis["bar_d"]
    a(f"- **(d) seastar io_uring at N=8:** juring mean {md.thousands(d['juring_iops'], 0)}, seastar mean "
      f"{md.thousands(d['seastar_iops'], 0)}. Literal-bar ratio seastar/JUring mean "
      f"{md.thousands(d['seastar_over_juring']['mean'], 3)} (one-sided 95 % LB "
      f"{md.thousands(d['seastar_over_juring']['lb'], 3)} ⇒ **{d['verdict']}**); reverse-direction "
      f"JUring/seastar mean {md.thousands(d['juring_over_seastar']['mean'], 3)} (LB "
      f"{md.thousands(d['juring_over_seastar']['lb'], 3)}) is reported for transparency.")
    e = analysis["bar_e"]
    a("- **(e) per-ring coverage:** " + ", ".join(
        f"N={cc['N']} {cc['covered_reps']}/{cc['good_reps']} reps" for cc in e["cells"]) + f" ⇒ **{e['verdict']}**")
    a("")
    a("## 5. Per-ring queue depth + runtime-completion p99 (juring-group, schema 1.1)")
    a("")
    if analysis["per_ring"]:
        a("| N | rep | ring | mean/max in-flight | mean sq_queued | mean cq_ready | "
          "runtime p99 µs (registration→dispatch) | external p99 µs | failed/cancelled/timedout |")
        a("|---|---|---|---|---|---|---|---|---|")
        for row in analysis["per_ring"]:
            a(f"| {row['N']} | {row['rep']} | {row['worker']} | "
              f"{md.thousands(row['mean_in_flight'])}/{md.thousands(row['max_in_flight'], 0)} | "
              f"{md.thousands(row['mean_sq_queued'], 2)} | {md.thousands(row['mean_cq_ready'], 2)} | "
              f"{md.thousands(row['dispatch_p99_us'])} | {md.thousands(row['external_p99_us'])} | "
              f"{md.thousands(row['failed'], 0)}/{md.thousands(row['cancelled'], 0)}/{md.thousands(row['timed_out'], 0)} |")
        a("")
        a("`runtime p99` is the metrics-on `dispatch_latency_us.p99` (registration→dispatch; includes "
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
            a(f"| {cellmap.label(g['cell'])} | {g['engine']} | {g['N']} | {g['rep']} | "
              f"{g['status']} | "
              f"{md.thousands(g['iops'], 0)} | {md.thousands(g['p99_us'])} | {', '.join(g['flags'])} |")
        a("")
        a("Gated reps stay in every mean/CI above; the flags are disclosure, not trimming.")
    else:
        a("No rep tripped the IOPS-anomaly / cell-adjusted-p99 / load gate.")
    a("")
    a("## 7. Reading notes / residual risks")
    a("")
    a("- This is a single-device, constant-QD-per-shard protocol: N·IOPS(1) is capacity-bounded "
      "by the SSD, which is exactly why the original `SE(8)≥0.75` and `p99(8)≤1.15·p99(1)` "
      "were restated vs `fio` at the same N.")
    a("- Latency definitions are engine-native and not identical: JUring external = before "
      "prepare/submit → completion; fio = `clat` (submission→completion); seastar = before "
      "submit → completion. Cross-engine p99 is only used as the frozen same-N ratio, never as "
      "an absolute ranking; the metrics-on runtime-completion p99 is a separate per-ring metric.")
    a("- `fio` was run with `--group_reporting=1` (frozen template), so fio per-job spread is "
      "`n/a`; bar (c) is evaluated on `juring-group` shards.")
    a("- N=8 placement: hctx CPU set for fio/seastar; `juring-group` unpinned inside the same "
      "taskset mask (frozen placement rule). `mq/*/cpu_list` is in `manifest.txt`.")
    a("- All engine asymmetries (seastar 200-entry ring, no registered files/fixed buffers; "
      "JUring registered file+buffers and DEFER_TASKRUN) are disclosed, not equalised.")
    a("")
    a(f"Raw session: `{analysis['results_dir']}` (gitignored); `aggregate/scale-out.json` carries the "
      "same data machine-readably.")
    a("")
    return "\n".join(L)


def main(argv=None):
    ap = argparse.ArgumentParser(description="multi-shard scaling campaign analyzer")
    ap.add_argument("--session", help="session name under bench/results/")
    ap.add_argument("--results-dir", help="explicit session directory")
    ap.add_argument("--out-dir", help="output directory (default <session>/aggregate)")
    args = ap.parse_args(argv)
    if args.results_dir:
        results_dir = os.path.abspath(args.results_dir)
        session = os.path.basename(results_dir.rstrip("/"))
    elif args.session:
        results_dir = os.path.join(jsonio.REPO_ROOT, "bench", "results", args.session)
        session = args.session
    else:
        ap.error("one of --session or --results-dir is required")
    if not os.path.isdir(results_dir):
        sys.exit(f"error: session directory not found: {results_dir}")
    out_dir = args.out_dir or os.path.join(results_dir, "aggregate")
    cli.ensure_dir(out_dir)

    analysis = analyze(results_dir)
    md_path = os.path.join(out_dir, "scale-out.md")
    cli.write_text(md_path, render_markdown(session, analysis))
    # the JSON file ends at the closing brace (no trailing newline)
    cli.write_json(os.path.join(out_dir, "scale-out.json"), analysis, indent=2,
                   newline=False, default=str)
    print(f"[analyze_scale_out] {md_path}")
    print(f"[analyze_scale_out] verdict: {analysis['verdict']}  bars: "
          + ", ".join(f"{k}={v}" for k, v in analysis["bars"].items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
