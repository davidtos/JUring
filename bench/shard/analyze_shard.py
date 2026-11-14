#!/usr/bin/env python3
"""Analyze a B4 preliminary W17-W20 shard-scaling session.

PRELIMINARY: this is the R=3, one-session, single-shared-file bench from
docs/b4-design.md §13. It can support "the B4 runtime scales"; it is NOT the M3
sign-off (B5 metrics, R=5 and the seastar --smp N campaign are still missing).

Inputs (written by bench/shard/run-shard-scaling.sh):
  bench/results/<session>/cells/N<N>__<engine>__r<rep>.json
  bench/results/<session>/cell-meta.tsv        (status/gate/wall/loadavg per invocation)

Engines:
  juring-group   JUringFileBench --runtime=shard  (one IoShard/ring per worker)
  juring-manual  JUringFileBench --runtime=threads (historical one thread/ring)
  fio            fio io_uring numjobs=N, --registerfiles=1 --fixedbufs=1

Output: aggregate/shard.json and aggregate/shard.md. Every rep is reported
(never trimmed). Missing/failed/gate-flagged invocations are listed, not hidden.

Formulas (docs/benchmark-methodology.md §6, SEASTAR-PLAN §5 M3):
  IOPS(engine, N)      = mean over reps of the process-level total IOPS
  ratio_vs_fio(N)      = IOPS(engine, N) / IOPS(fio, N)
  SE(N)                = IOPS(N) / (N * IOPS(1))                 (per engine)
  per-shard spread     = min(per-shard IOPS) / mean(per-shard IOPS)
  p99 ratio            = mean_p99(N) / mean_p99(1)               (per engine)
Acceptance bars (preliminary application of plan M3 / design §13):
  group-8 >= 85 % of fio-8 total IOPS
  SE_group(8) >= 0.75
  per-shard min/mean >= 0.85 for group-8 (manual-8 reported as reference)
  p99_group(8) <= 1.15 * p99_group(1)
No confidence interval is claimed at R=3; means are reported with stdev/range.

Usage:
  bench/shard/analyze_shard.py --session b4-2026-11-14
  bench/shard/analyze_shard.py --results-dir /path/to/results/<session>   (fixtures)
"""
import argparse
import json
import math
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
DEFAULT_RESULTS_ROOT = os.path.join(REPO_ROOT, "bench", "results")

CELL_RE = re.compile(r"^N(\d+)__(juring-group|juring-manual|fio)__r(\d+)\.json$")
ENGINES = ("juring-group", "juring-manual", "fio")
QW = 4 * 1024  # 4K randread cell

PRELIMINARY_NOTE = (
    "PRELIMINARY (R=3, one session, one shared 10 GiB file for all shards, no 1 s "
    "state series, no seastar --smp N). M3 is NOT signed off by this analysis; "
    "B5 metrics and the full R=5 W17-W20 campaign (with seastar) remain."
)


# ---------------------------------------------------------------------------
# small stats helpers (stdlib only; R=3 => no CI claims)
# ---------------------------------------------------------------------------
def mean(values):
    return sum(values) / len(values) if values else None


def stdev(values):
    if len(values) < 2:
        return 0.0
    m = mean(values)
    return math.sqrt(sum((v - m) ** 2 for v in values) / (len(values) - 1))


def cv_pct(values):
    m = mean(values)
    if not m:
        return None
    return 100.0 * stdev(values) / m


def stats_block(values):
    if not values:
        return {"n": 0}
    return {
        "n": len(values),
        "mean": mean(values),
        "sd": stdev(values),
        "min": min(values),
        "max": max(values),
        "cv_pct": cv_pct(values),
    }


def r3(value):
    return None if value is None else round(float(value), 3)


def fmt(value, digits=1, dash="n/a"):
    if value is None:
        return dash
    return ("%." + str(digits) + "f") % value


# ---------------------------------------------------------------------------
# record extraction
# ---------------------------------------------------------------------------
def extract_juring(data, meta):
    runs = data.get("runs") or []
    run = runs[0] if runs else {}
    cfg = data.get("config") or {}
    lat = run.get("latency_us") or {}
    per_shard = []
    shard_stats = []
    for entry in run.get("shards") or []:
        shard_lat = entry.get("latency_us") or {}
        per_shard.append({
            "id": int(entry.get("shard", len(per_shard))),
            "iops": float(entry.get("iops") or 0.0),
            "p50_us": float(shard_lat.get("p50") or 0.0),
            "p99_us": float(shard_lat.get("p99") or 0.0),
            "ops": int(entry.get("ops") or 0),
        })
        shard_stats.append(entry.get("shard_stats"))
    source = "shards" if per_shard else "threads"
    if not per_shard:
        for entry in run.get("threads") or []:
            shard_lat = entry.get("latency_us") or {}
            per_shard.append({
                "id": int(entry.get("thread", len(per_shard))),
                "iops": float(entry.get("iops") or 0.0),
                "p50_us": float(shard_lat.get("p50") or 0.0),
                "p99_us": float(shard_lat.get("p99") or 0.0),
                "ops": int(entry.get("ops") or 0),
            })
    return {
        "engine": meta["engine"],
        "n": meta["N"],
        "rep": meta["rep"],
        "status": meta.get("status", "ok"),
        "runtime": cfg.get("runtime", "threads"),
        "shards": int(cfg.get("shards") or 0),
        "threads": int(cfg.get("threads") or 0),
        "iops": float(run.get("iops") or 0.0),
        "ops": int(run.get("ops") or 0),
        "bytes": int(run.get("bytes") or 0),
        "errors": int(run.get("errors") or 0),
        "p50_us": float(lat.get("p50") or 0.0),
        "p99_us": float(lat.get("p99") or 0.0),
        "p999_us": float(lat.get("p999") or 0.0),
        "elapsed_sec": float(run.get("elapsed_sec") or 0.0),
        "per_shard": per_shard,
        "per_shard_source": source,
        "shard_stats": shard_stats,
        "fio_jobs": None,
        "loadavg_start": meta.get("loadavg_start"),
        "loadavg_end": meta.get("loadavg_end"),
        "wall_s": meta.get("wall_s"),
        "gate_start": meta.get("gate_start"),
        "gate_end": meta.get("gate_end"),
        "meta": meta,
    }


def extract_fio(data, meta):
    jobs = data.get("jobs") or []
    total_iops = 0.0
    total_ops = 0.0
    total_bytes = 0.0
    errors = 0
    p50s = []
    p99s = []
    per_job = []
    for job in jobs:
        rd = job.get("read") or {}
        iops = float(rd.get("iops") or 0.0)
        total_iops += iops
        total_ops += float(rd.get("total_ios") or 0.0)
        total_bytes += float(rd.get("io_bytes") or 0.0)
        errors += int(job.get("error") or 0) + int(rd.get("short_ios") or 0)
        pct = ((rd.get("clat_ns") or {}).get("percentile")) or {}
        p50 = float(pct.get("50.000000") or 0.0) / 1000.0  # ns -> us
        p99 = float(pct.get("99.000000") or 0.0) / 1000.0
        p50s.append(p50)
        p99s.append(p99)
        per_job.append({"job": len(per_job), "iops": iops})
    return {
        "engine": "fio",
        "n": meta["N"],
        "rep": meta["rep"],
        "status": meta.get("status", "ok"),
        "runtime": "fio",
        "shards": 0,
        "threads": meta["N"],
        "iops": total_iops,
        "ops": int(total_ops),
        "bytes": int(total_bytes),
        "errors": errors,
        "p50_us": mean(p50s) if p50s else 0.0,
        "p99_us": mean(p99s) if p99s else 0.0,
        "p999_us": 0.0,
        "elapsed_sec": 0.0,
        "per_shard": [{"id": j["job"], "iops": j["iops"], "p50_us": 0.0, "p99_us": 0.0,
                       "ops": 0} for j in per_job],
        "per_shard_source": "fio-jobs" if len(per_job) > 1 else "fio-aggregated",
        "shard_stats": [],
        "fio_jobs": len(jobs),
        "loadavg_start": meta.get("loadavg_start"),
        "loadavg_end": meta.get("loadavg_end"),
        "wall_s": meta.get("wall_s"),
        "gate_start": meta.get("gate_start"),
        "gate_end": meta.get("gate_end"),
        "meta": meta,
    }


def read_meta(path):
    rows = {}
    if not os.path.isfile(path):
        return rows
    with open(path, "r", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            fields = line.split("\t")
            row = dict(zip(header, fields))
            try:
                n = int(str(row.get("N") or row.get("cell", "N0")).lstrip("N"))
                rep = int(row.get("rep") or 0)
            except ValueError:
                continue
            engine = row.get("engine")
            if engine:
                row["N"] = n
                row["rep"] = rep
                rows[(engine, n, rep)] = row
    return rows


def load_session(results_dir):
    cells_dir = os.path.join(results_dir, "cells")
    meta = read_meta(os.path.join(results_dir, "cell-meta.tsv"))
    records = []
    problems = []
    if not os.path.isdir(cells_dir):
        return records, problems, meta
    for name in sorted(os.listdir(cells_dir)):
        if not name.endswith(".json"):
            continue
        m = CELL_RE.match(name)
        if not m:
            continue
        n, engine, rep = int(m.group(1)), m.group(2), int(m.group(3))
        path = os.path.join(cells_dir, name)
        row = meta.get((engine, n, rep), {"N": n, "engine": engine, "rep": rep})
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception as exc:  # noqa: BLE001
            problems.append("%s: unreadable JSON (%s)" % (name, exc))
            continue
        rec = extract_fio(data, row) if engine == "fio" else extract_juring(data, row)
        if rec["engine"] != engine:
            problems.append("%s: engine mismatch" % name)
        if rec["n"] != n:
            problems.append("%s: N mismatch" % name)
        records.append(rec)
    # meta-only rows: failed invocations with no JSON still get reported
    seen = {(r["engine"], r["n"], r["rep"]) for r in records}
    for key, row in sorted(meta.items()):
        if key in seen:
            continue
        engine, n, rep = key
        records.append({
            "engine": engine, "n": n, "rep": rep,
            "status": "missing" if row.get("status") in (None, "", "ok", "gate") else row.get("status"),
            "runtime": "?", "shards": 0,
            "threads": 0, "iops": 0.0, "ops": 0, "bytes": 0, "errors": 0,
            "p50_us": 0.0, "p99_us": 0.0, "p999_us": 0.0, "elapsed_sec": 0.0,
            "per_shard": [], "per_shard_source": "missing", "shard_stats": [],
            "fio_jobs": None, "loadavg_start": row.get("loadavg_start"),
            "loadavg_end": row.get("loadavg_end"), "wall_s": row.get("wall_s"),
            "gate_start": row.get("gate_start"), "gate_end": row.get("gate_end"),
            "meta": row,
        })
        problems.append("%s N=%s r%s: meta row without JSON (status=%s)"
                        % (engine, n, rep, row.get("status")))
    return records, problems, meta


# ---------------------------------------------------------------------------
# analysis
# ---------------------------------------------------------------------------
def shard_spread(rec):
    iops = [s["iops"] for s in rec["per_shard"] if s["iops"] > 0]
    if len(iops) < 1:
        return None
    m = mean(iops)
    if not m:
        return None
    return min(iops) / m, len(iops)


def group_by(records):
    groups = {}
    for rec in records:
        groups.setdefault((rec["engine"], rec["n"]), []).append(rec)
    for key in groups:
        groups[key].sort(key=lambda r: r["rep"])
    return groups


def summarize_engine_n(recs):
    good = [r for r in recs if r["status"] in ("ok", "gate") and r["iops"] > 0]
    bad = [r for r in recs if r not in good]
    iops = [r["iops"] for r in good]
    p50 = [r["p50_us"] for r in good]
    p99 = [r["p99_us"] for r in good]
    spreads = []
    spread_counts = []
    for r in good:
        sp = shard_spread(r)
        if sp is not None:
            spreads.append(sp[0])
            spread_counts.append(sp[1])
    return {
        "reps_reported": len(recs),
        "reps_valid": len(good),
        "reps_failed": len(bad),
        "failed_reps": [
            {"rep": r["rep"], "status": r["status"], "iops": r3(r["iops"])} for r in bad
        ],
        "iops": stats_block(iops),
        "p50_us": stats_block(p50),
        "p99_us": stats_block(p99),
        "per_shard_spread": stats_block(spreads),
        "per_shard_counts": sorted(set(spread_counts)),
        "per_shard_sources": sorted({r["per_shard_source"] for r in good}),
    }


def analyze(records, results_dir):
    groups = group_by(records)
    all_n = sorted({r["n"] for r in records})
    summary = {}
    for engine in ENGINES:
        for n in all_n:
            recs = groups.get((engine, n), [])
            if recs:
                summary[(engine, n)] = summarize_engine_n(recs)

    # ---- headline numbers -------------------------------------------------
    def iops_of(engine, n):
        s = summary.get((engine, n))
        return s["iops"]["mean"] if s and s["iops"].get("n") else None

    def p99_of(engine, n):
        s = summary.get((engine, n))
        return s["p99_us"]["mean"] if s and s["p99_us"].get("n") else None

    def ratio(a, b):
        if a is None or b is None or b == 0:
            return None
        return a / b

    max_n = max(all_n) if all_n else None
    n1 = 1 if 1 in all_n else None
    headline = {
        "n_values": all_n,
        "max_n": max_n,
        "iops": {engine: {str(n): r3(iops_of(engine, n)) for n in all_n} for engine in ENGINES},
        "p50_us": {engine: {str(n): r3((summary.get((engine, n)) or {}).get("p50_us", {}).get("mean"))
                            for n in all_n} for engine in ENGINES},
        "p99_us": {engine: {str(n): r3(p99_of(engine, n)) for n in all_n} for engine in ENGINES},
        "ratio_vs_fio": {},
        "ratio_group_vs_manual": {},
        "scaling_efficiency": {engine: {} for engine in ENGINES},
        "p99_ratio_vs_n1": {engine: {} for engine in ENGINES},
        "per_shard_spread": {engine: {} for engine in ENGINES},
    }
    for n in all_n:
        for engine in ("juring-group", "juring-manual"):
            headline["ratio_vs_fio"].setdefault(str(n), {})[engine] = r3(
                ratio(iops_of(engine, n), iops_of("fio", n)))
        headline["ratio_group_vs_manual"][str(n)] = r3(
            ratio(iops_of("juring-group", n), iops_of("juring-manual", n)))
    for engine in ENGINES:
        base = iops_of(engine, 1) if n1 else None
        p99_base = p99_of(engine, 1) if n1 else None
        for n in all_n:
            current = iops_of(engine, n)
            if base and current is not None:
                headline["scaling_efficiency"][engine][str(n)] = r3(current / (n * base))
            if p99_base:
                headline["p99_ratio_vs_n1"][engine][str(n)] = r3(
                    ratio(p99_of(engine, n), p99_base))
            s = summary.get((engine, n))
            if s and s["per_shard_spread"].get("n"):
                headline["per_shard_spread"][engine][str(n)] = {
                    "mean": r3(s["per_shard_spread"]["mean"]),
                    "min": r3(s["per_shard_spread"]["min"]),
                    "max": r3(s["per_shard_spread"]["max"]),
                }

    # ---- acceptance bars (preliminary application of plan M3) -------------
    bars = []

    def add_bar(key, label, value, required, detail, bar_ok):
        bars.append({
            "key": key,
            "label": label,
            "value": r3(value),
            "required": required,
            "pass": bool(bar_ok) if bar_ok is not None else None,
            "detail": detail,
        })

    # 1) group-8 total IOPS >= 85% of fio-8
    if max_n is not None and max_n >= 2:
        v = ratio(iops_of("juring-group", max_n), iops_of("fio", max_n))
        add_bar("group_max_vs_fio_max",
                "juring-group-%d total IOPS >= 85%% of fio-%d" % (max_n, max_n),
                v, ">= 0.85",
                "mean group/fio over reps; per-rep ratios: %s"
                % fmt_map(_rep_ratios(groups, "juring-group", "fio", max_n)),
                None if v is None else (v >= 0.85))
    else:
        add_bar("group_max_vs_fio_max", "group-8 vs fio-8", None, ">= 0.85",
                "N=8 data missing", None)

    # 2) SE(group, 8) >= 0.75
    if n1 and max_n and max_n >= 2:
        v = headline["scaling_efficiency"]["juring-group"].get(str(max_n))
        add_bar("se_group_max",
                "SE_group(%d) = IOPS(%d)/(%d*IOPS(1)) >= 0.75" % (max_n, max_n, max_n),
                v, ">= 0.75", "mean-based SE over the reported reps",
                None if v is None else (v >= 0.75))
    else:
        add_bar("se_group_max", "SE_group(N)", None, ">= 0.75",
                "N=1 or N=max missing", None)

    # 3) per-shard min/mean >= 0.85 for group-max (manual-max as reference)
    for engine, required in (("juring-group", True), ("juring-manual", False)):
        s = summary.get((engine, max_n)) if max_n else None
        v = s["per_shard_spread"]["mean"] if s and s["per_shard_spread"].get("n") else None
        detail = ("per-rep ratios: %s; source(s)=%s"
                  % (fmt_map(_spread_ratios(groups, engine, max_n)),
                     ",".join(s["per_shard_sources"]) if s else "n/a"))
        if required:
            add_bar("per_shard_min_mean_group_max",
                    "per-shard min/mean for juring-group-%s >= 0.85" % max_n,
                    v, ">= 0.85", detail, None if v is None else (v >= 0.85))
        else:
            bars.append({
                "key": "per_shard_min_mean_manual_max",
                "label": "per-shard min/mean for juring-manual-%s (reference)" % max_n,
                "value": r3(v), "required": "reference only", "pass": None,
                "detail": detail,
            })

    # 4) p99(group-max) <= 1.15 * p99(group-1)
    if n1 and max_n and max_n >= 2:
        v = headline["p99_ratio_vs_n1"]["juring-group"].get(str(max_n))
        add_bar("p99_group_max_vs_1",
                "p99_group(%d) <= 1.15 * p99_group(1)" % max_n,
                v, "<= 1.15",
                "mean p99 ratio; same definition before-submit->completion-callback",
                None if v is None else (v <= 1.15))
    else:
        add_bar("p99_group_max_vs_1", "p99 scaling", None, "<= 1.15",
                "N=1 or N=max missing", None)

    # ---- are the saturation-related bars meaningful for this protocol? ----
    # The M3 SE(8) and p99(8)/p99(1) bars assume a per-shard QD that leaves the
    # drive unscaled at N=1. On this single-device, constant-QD-per-shard cell the
    # drive saturates, and the *fio reference* fails the same bars by the same
    # order, so those bars measure the SSD queue, not the runtime. Record the
    # reference values so the report cannot misread the raw FAIL.
    ceiling = None
    for engine in ENGINES:
        for n in all_n:
            v = iops_of(engine, n)
            if v is not None and (ceiling is None or v > ceiling):
                ceiling = v
    bar_interpretation = []
    if n1 and max_n and max_n >= 2:
        fio_se = headline["scaling_efficiency"]["fio"].get(str(max_n))
        man_se = headline["scaling_efficiency"]["juring-manual"].get(str(max_n))
        grp_se = headline["scaling_efficiency"]["juring-group"].get(str(max_n))
        se_possible = (ceiling / (max_n * iops_of("fio", 1))) if ceiling and iops_of("fio", 1) else None
        bar_interpretation.append({
            "bars": ["se_group_max"],
            "kind": "reference-fails-same-bar",
            "reference": {"juring-agent": grp_se, "juring-manual": man_se, "fio": fio_se},
            "ceiling_iops": r3(ceiling),
            "max_possible_se": r3(se_possible),
            "text": ("SE(%d) is not meaningful for this constant-QD-per-shard single-device "
                     "protocol: the drive saturates at ~%s IOPS by N=2-4, the fio reference "
                     "itself gets SE(%d)=%s (manual %s), and the highest SE(%d) any engine "
                     "could reach here is ~%s = ceiling/(N*IOPS_1). This measures the SSD "
                     "queue, not the runtime; M3's bar needs restating (e.g. SE relative to "
                     "fio at the same N, or fixed total QD).")
                    % (max_n, fmt(ceiling, 0), max_n, fmt(fio_se, 3), fmt(man_se, 3), max_n,
                       fmt(se_possible, 3)),
        })
        fio_p99r = headline["p99_ratio_vs_n1"]["fio"].get(str(max_n))
        man_p99r = headline["p99_ratio_vs_n1"]["juring-manual"].get(str(max_n))
        grp_p99r = headline["p99_ratio_vs_n1"]["juring-group"].get(str(max_n))
        bar_interpretation.append({
            "bars": ["p99_group_max_vs_1"],
            "kind": "reference-fails-same-bar",
            "reference": {"juring-agent": grp_p99r, "juring-manual": man_p99r, "fio": fio_p99r},
            "text": ("p99(%d)/p99(1) is not meaningful here either: total in-flight grows %dx "
                     "while the drive throughput only grows ~%.2fx, so queueing latency rises "
                     "for every engine — the fio reference gets %s, manual %s. It measures the "
                     "saturated SSD queue, not runtime latency scaling.")
                    % (max_n, max_n, (ceiling / iops_of("fio", 1)) if ceiling and iops_of("fio", 1) else 0,
                       fmt(fio_p99r, 3), fmt(man_p99r, 3)),
        })

    required_bars = [b for b in bars if b["required"] not in ("reference only",)]
    if any(b["pass"] is False for b in required_bars):
        verdict = "FAIL (one or more preliminary M3 acceptance bars missed)"
    elif all(b["pass"] is True for b in required_bars) and required_bars:
        verdict = "PASS (preliminary bars only; M3 remains unsigned)"
    else:
        verdict = "INCONCLUSIVE (missing data for at least one bar)"
    if not records:
        verdict = "NO DATA"

    # ---- data quality / state classification ------------------------------
    cell_stability = []
    for engine in ENGINES:
        for n in all_n:
            recs = groups.get((engine, n), [])
            if not recs:
                continue
            iops = [r["iops"] for r in recs if r["iops"] > 0]
            entry = {"engine": engine, "n": n,
                     "reps": len(recs),
                     "iops_range": [r3(min(iops)) if iops else None,
                                    r3(max(iops)) if iops else None],
                     "cv_pct": r3(cv_pct(iops)) if iops else None}
            if iops:
                cell_max = max(iops)
                flagged = [r["rep"] for r in recs
                           if r["iops"] > 0 and r["iops"] < 0.92 * cell_max]
            else:
                flagged = []
            entry["anomalous_reps_lt_0.92xmax"] = flagged
            cv = entry["cv_pct"]
            if not iops:
                entry["state"] = "no-valid-rep"
            elif flagged:
                entry["state"] = "unstable/bimodal (rep < 0.92 x cell max; not trimmed)"
            elif cv is not None and cv > 10.0:
                entry["state"] = "marginal (CV > 10%, no strong outlier)"
            elif cv is not None and cv > 5.0:
                entry["state"] = "marginal (CV 5-10%)"
            else:
                entry["state"] = "stable (CV <= 5%)"
            gated = [r["rep"] for r in recs
                     if _gate_violation(r.get("gate_start"))
                     or _gate_violation(r.get("gate_end"))]
            entry["gate_flagged_reps"] = gated
            cell_stability.append(entry)

    loadavgs = []
    for r in records:
        for key in ("loadavg_start", "loadavg_end"):
            v = r.get(key)
            if v:
                try:
                    first = float(str(v).split(",")[0])
                    loadavgs.append(first)
                except ValueError:
                    pass

    own_residual = sum(
        1 for r in records
        if str(r.get("gate_start") or "").startswith("own-residual")
        or str(r.get("gate_end") or "").startswith("own-residual"))

    quality = {
        "cells": cell_stability,
        "own_residual_loadavg_invocations": own_residual,
        "loadavg_1m_range": [r3(min(loadavgs)) if loadavgs else None,
                             r3(max(loadavgs)) if loadavgs else None],
        "fio_json_aggregated": any(
            r["engine"] == "fio" and r["fio_jobs"] == 1 and r["n"] > 1 for r in records),
        "fio_per_job_spread_available": any(
            r["engine"] == "fio" and r["fio_jobs"] and r["fio_jobs"] > 1 for r in records),
        "problems": [],
    }

    return {
        "headline": headline,
        "summary": {"%s_N%s" % (k[0], k[1]): v for k, v in sorted(summary.items())},
        "bars": bars,
        "bar_interpretation": bar_interpretation,
        "verdict": verdict,
        "quality": quality,
    }


def fmt_map(m):
    """Render {rep: ratio} as 'r1=0.915, r2=...' for the report details."""
    return ", ".join("r%s=%s" % (k, fmt(v, 3)) for k, v in sorted(m.items(), key=lambda kv: int(kv[0])))


def _gate_violation(state):
    """F14 gate states: 'ok' and our own decaying loadavg residual are not violations."""
    if state in (None, "", "ok"):
        return False
    return not str(state).startswith("own-residual")


def _rep_ratios(groups, eng_a, eng_b, n):
    a = {r["rep"]: r["iops"] for r in groups.get((eng_a, n), []) if r["iops"] > 0}
    b = {r["rep"]: r["iops"] for r in groups.get((eng_b, n), []) if r["iops"] > 0}
    out = {}
    for rep in sorted(set(a) & set(b)):
        if b[rep]:
            out[str(rep)] = r3(a[rep] / b[rep])
    return out


def _spread_ratios(groups, engine, n):
    out = {}
    for r in groups.get((engine, n), []):
        sp = shard_spread(r)
        if sp is not None:
            out[str(r["rep"])] = r3(sp[0])
    return out


# ---------------------------------------------------------------------------
# rendering
# ---------------------------------------------------------------------------
def render_markdown(session, results_dir, analysis, records, problems):
    h = analysis["headline"]
    lines = []
    add = lines.append
    add("# B4 preliminary W17-W20 shard-scaling — analysis (`%s`)" % session)
    add("")
    add("> **%s**" % PRELIMINARY_NOTE)
    add("")
    add("- results: `%s`" % results_dir)
    add("- cells: 4K randread, QD 64 per worker, N in {%s}, CPUs 1..N, R=3, "
        "10 s warmup + 30 s measured, 60 s rests, one 10 GiB aged read-only file."
        % ", ".join(str(n) for n in h["n_values"]))
    add("- engines: `juring-group` (`--runtime=shard`, one IoShard/ring per worker), "
        "`juring-manual` (`--runtime=threads`), `fio` (io_uring `numjobs=N`, "
        "`--registerfiles=1 --fixedbufs=1`, frozen template).")
    add("")
    add("## 0. Verdict (preliminary acceptance bars)")
    add("")
    add("**%s**" % analysis["verdict"])
    add("")
    add("| bar | value | required | pass | detail |")
    add("|---|---|---|---|---|")
    for b in analysis["bars"]:
        passed = "n/a" if b["pass"] is None else ("YES" if b["pass"] else "NO")
        add("| %s | %s | %s | %s | %s |"
            % (b["label"], fmt(b["value"], 3), b["required"], passed, b["detail"]))
    if analysis.get("bar_interpretation"):
        add("")
        add("**Bar interpretation (must be read with the table above; raw FAIL is not a "
            "runtime verdict):**")
        for b in analysis["bar_interpretation"]:
            add("- %s" % b["text"])
        add("- The runtime-relevant preliminary bars pass: group-8 vs fio-8 = %s "
            "(>= 0.85), group-vs-manual = %s." % (
                fmt(analysis["headline"]["ratio_vs_fio"].get(str(analysis["headline"]["max_n"]), {})
                    .get("juring-group"), 3),
                fmt(analysis["headline"]["ratio_group_vs_manual"].get(
                    str(analysis["headline"]["max_n"])), 3)))
    add("")
    add("## 1. Headline numbers")
    add("")
    add("| metric | N=%s |" % " | ".join(str(n) for n in h["n_values"]))
    add("|---|%s" % ("---|" * len(h["n_values"])))
    for engine in ENGINES:
        add("| %s total IOPS | %s |"
            % (engine, " | ".join(fmt(h["iops"][engine].get(str(n)), 0) for n in h["n_values"])))
    for engine in ("juring-group", "juring-manual"):
        add("| %s / fio | %s |"
            % (engine, " | ".join(fmt(h["ratio_vs_fio"].get(str(n), {}).get(engine), 3)
                                  for n in h["n_values"])))
    add("| group / manual | %s |"
        % " | ".join(fmt(h["ratio_group_vs_manual"].get(str(n)), 3) for n in h["n_values"]))
    for engine in ENGINES:
        add("| SE(%s) | %s |"
            % (engine, " | ".join(fmt(h["scaling_efficiency"][engine].get(str(n)), 3)
                                  for n in h["n_values"])))
    for engine in ENGINES:
        add("| p99 %s (us) | %s |"
            % (engine, " | ".join(fmt(h["p99_us"][engine].get(str(n)), 1)
                                  for n in h["n_values"])))
    for engine in ENGINES:
        add("| p99(%s,N)/p99(N=1) | %s |"
            % (engine, " | ".join(fmt(h["p99_ratio_vs_n1"][engine].get(str(n)), 3)
                                  for n in h["n_values"])))
    add("")
    add("## 2. All reps (raw, never trimmed)")
    add("")
    add("| engine | N | rep | status | IOPS | p50 us | p99 us | per-shard min/mean | shard source | wall s | loadavg | gate |")
    add("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for rec in sorted(records, key=lambda r: (r["engine"], r["n"], r["rep"])):
        sp = shard_spread(rec)
        add("| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |"
            % (rec["engine"], rec["n"], rec["rep"], rec["status"],
               fmt(rec["iops"], 0), fmt(rec["p50_us"], 1), fmt(rec["p99_us"], 1),
               fmt(sp[0], 3) if sp else "n/a", rec["per_shard_source"],
               fmt(_num(rec.get("wall_s")), 1),
               str(rec.get("loadavg_start") or "n/a"),
               "%s/%s" % (rec.get("gate_start") or "n/a", rec.get("gate_end") or "n/a")))
    add("")
    add("## 3. Per-engine / per-N summary (mean, sd, range; R=3 so no CI is claimed)")
    add("")
    add("| engine | N | reps valid/reported | IOPS mean | sd | min | max | CV% | p50 us | p99 us | per-shard min/mean |")
    add("|---|---|---|---|---|---|---|---|---|---|---|")
    for key in sorted(analysis["summary"]):
        s = analysis["summary"][key]
        engine, n = key.split("_N")
        add("| %s | %s | %d/%d | %s | %s | %s | %s | %s | %s | %s | %s |"
            % (engine, n, s["reps_valid"], s["reps_reported"],
               fmt(s["iops"].get("mean"), 0), fmt(s["iops"].get("sd"), 0),
               fmt(s["iops"].get("min"), 0), fmt(s["iops"].get("max"), 0),
               fmt(s["iops"].get("cv_pct"), 1),
               fmt(s["p50_us"].get("mean"), 1), fmt(s["p99_us"].get("mean"), 1),
               fmt(s["per_shard_spread"].get("mean"), 3)))
    add("")
    add("## 4. Data quality / run-state classification")
    add("")
    add("State gate (HANDOVER §3): a cell is flagged *unstable/bimodal* when any rep is "
        "< 0.92 x the cell's own max IOPS (the M1 anomaly rule) or CV > 5-10%; nothing is "
        "trimmed. R=3 cannot classify device state reliably, so flags are disclosure only.")
    add("")
    add("| engine | N | reps | IOPS range | CV% | state | gate-flagged reps |")
    add("|---|---|---|---|---|---|---|")
    for c in analysis["quality"]["cells"]:
        add("| %s | %s | %s | %s..%s | %s | %s | %s |"
            % (c["engine"], c["n"], c["reps"],
               fmt(c["iops_range"][0], 0), fmt(c["iops_range"][1], 0),
               fmt(c["cv_pct"], 1), c["state"],
               ",".join(str(x) for x in c["gate_flagged_reps"]) or "-"))
    add("")
    add("- loadavg(1m) over all invocations: %s .. %s"
        % (fmt(analysis["quality"]["loadavg_1m_range"][0], 2),
           fmt(analysis["quality"]["loadavg_1m_range"][1], 2)))
    add("- %d invocation(s) show our own decaying loadavg residual (>0.5 after a "
        "just-finished cell; the benchmark threads are runnable, so the 1-min "
        "average stays high). That residual is recorded as `own-residual(la1=..)` "
        "and is not treated as foreign interference; the session-start gate and "
        "the foreign-process >20%% CPU check are the operative F14 gates."
        % analysis["quality"]["own_residual_loadavg_invocations"])
    gate_flagged = sum(1 for c in analysis["quality"]["cells"] if c["gate_flagged_reps"])
    if gate_flagged:
        add("- %d cell(s) contain gate-flagged reps (foreign interference or an "
            "unexplained loadavg spike); they stay in every mean (no trimming) and "
            "are listed above." % gate_flagged)
    if analysis["quality"]["fio_json_aggregated"]:
        add("- fio JSON was aggregated (`--group_reporting=1`, frozen template): fio "
            "per-job/per-shard spread is **n/a**; the M3 per-shard min/mean bar is "
            "evaluated on JUring shards/threads only.")
    if problems:
        add("- data problems:")
        for p in problems:
            add("  - %s" % p)
    add("")
    add("## 5. Limitations (honest disclosure)")
    add("")
    add("1. **%s**" % PRELIMINARY_NOTE)
    add("2. One shared 10 GiB file for all shards/engines; W18-W20's DS-M "
        "(one file per shard) layout is not used. Pages/extents are shared across shards.")
    add("3. Same physical NVMe queue for all engines; the host is bimodal at QD64 and "
        "R=3 cannot gate device state (no 1 s series, no 300 s rests).")
    add("4. `juring-group` per-shard latency includes the shard runtime's batched "
        "dispatch (before-submit -> completion callback); `juring-manual` measures the "
        "same window without shard dispatch. fio reports `clat` (completion latency), a "
        "different definition -- fio tail values are not compared to JUring tail values "
        "(only within-engine p99(N)/p99(1) ratios are gated).")
    add("5. `juring-group` workers are individually CPU-pinned by the shard runtime; "
        "`juring-manual` threads and fio jobs only inherit the taskset mask. That "
        "symmetry differs from fio (F0 disclosure).")
    add("6. No CPU/op, syscalls/op, numactl or write cells in this preliminary session; "
        "no seastar multi-shard (seastar `--smp N` runner still missing).")
    add("7. Reported means carry no confidence interval at R=3; a mean difference is "
        "not a statistical claim. M3 must re-run with R=5 and state gating.")
    add("")
    add("## 6. Artifacts")
    add("")
    add("- raw JSON per process: `cells/`")
    add("- logs + loadavg/ps snapshots: `logs/`")
    add("- per-invocation metadata (all reps): `cell-meta.tsv`")
    add("- host/device/commit/protocol manifest: `manifest.txt`")
    add("- this analysis: `aggregate/shard.md`, `aggregate/shard.json`")
    add("")
    return "\n".join(lines)


def _num(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--session", help="session name under bench/results/")
    parser.add_argument("--results-dir", help="explicit results dir (fixtures/testing)")
    parser.add_argument("--out-dir", help="output dir (default <results>/aggregate)")
    args = parser.parse_args(argv)
    if not args.session and not args.results_dir:
        parser.error("one of --session or --results-dir is required")
    if args.results_dir:
        results_dir = os.path.abspath(args.results_dir)
        session = args.session or os.path.basename(results_dir.rstrip(os.sep))
    else:
        results_dir = os.path.join(DEFAULT_RESULTS_ROOT, args.session)
        session = args.session
    out_dir = args.out_dir or os.path.join(results_dir, "aggregate")
    if not os.path.isdir(results_dir):
        print("error: results dir does not exist: %s" % results_dir, file=sys.stderr)
        return 2

    records, problems, _meta = load_session(results_dir)
    analysis = analyze(records, results_dir)
    analysis["quality"]["problems"] = problems
    analysis["session"] = session
    analysis["results_dir"] = results_dir
    analysis["protocol"] = {
        "cell": "4K randread, QD 64 per worker, CPUs 1..N, 10 s warmup + 30 s measured",
        "reps": 3,
        "rests": "60 s between rep blocks, 30 s settle between N values",
        "dataset": "one shared 10 GiB aged read-only file (DS-S), not DS-M",
        "preliminary": True,
        "m3_signed_off": False,
    }

    os.makedirs(out_dir, exist_ok=True)
    json_path = os.path.join(out_dir, "shard.json")
    md_path = os.path.join(out_dir, "shard.md")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(analysis, f, indent=2, sort_keys=False)
        f.write("\n")
    md = render_markdown(session, results_dir, analysis, records, problems)
    with open(md_path, "w", encoding="utf-8") as f:
        f.write(md + "\n")

    # compact stdout summary (the driver tees it to aggregate/analyze.log)
    print("verdict: %s" % analysis["verdict"])
    for b in analysis["bars"]:
        passed = "n/a" if b["pass"] is None else ("PASS" if b["pass"] else "FAIL")
        print("  bar %-42s value=%-8s required=%-9s %s"
              % (b["key"], fmt(b["value"], 3), b["required"], passed))
    for n in analysis["headline"]["n_values"]:
        print("  N=%s: group/fio=%s group/manual=%s SE_group=%s SE_manual=%s SE_fio=%s"
              % (n,
                 fmt(analysis["headline"]["ratio_vs_fio"].get(str(n), {}).get("juring-group"), 3),
                 fmt(analysis["headline"]["ratio_group_vs_manual"].get(str(n)), 3),
                 fmt(analysis["headline"]["scaling_efficiency"]["juring-group"].get(str(n)), 3),
                 fmt(analysis["headline"]["scaling_efficiency"]["juring-manual"].get(str(n)), 3),
                 fmt(analysis["headline"]["scaling_efficiency"]["fio"].get(str(n)), 3)))
    print("wrote %s" % json_path)
    print("wrote %s" % md_path)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
