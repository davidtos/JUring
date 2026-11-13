#!/usr/bin/env python3
"""analyze_stream.py -- B2 W21/W22 stream-matrix aggregator (docs/b2-design.md §3.3).

Reads the raw per-process JSON emitted by bench/juring/JUringStreamBench.java and
bench/seastar/seastar_stream_bench into
``bench/results/<session>/cells/<cell>__<engine>__<variant>__r<rep>.json`` and writes

    bench/results/<session>/aggregate/stream.json
    bench/results/<session>/aggregate/stream.md

with:

  * per cell, per engine row: MiB/s mean / sample sd / two-sided 95% CI (Student t),
    coefficient of variation, mean chunk-latency p50/p99, errors and n;
  * per-rep paired ratio ``juring MiB/s / seastar MiB/s`` for the primary rows:
    mean, two-sided 95% CI and the one-sided 95% lower bound;
  * a verdict against the plan-M2 bar ``juring MiB/s >= 0.85 * seastar MiB/s``
    (point estimate and the stricter one-sided-lower-bound reading), plus a parity
    note;
  * every rejected/failed/missing rep listed explicitly -- no rep is silently
    dropped (json ``excluded`` / ``missing`` lists, md "Data quality" section).

Only the Python standard library is used. The exploratory JUring ``--buffered``
rows are reported but never used for the verdict (disclosed asymmetry, §3.3).

Usage:
    python3 bench/stream/analyze_stream.py [--session NAME] [--results DIR]
        [--out DIR] [--bar 0.85]

Exit codes: 0 = aggregate written; 1 = no cell JSONs found; 2 = usage error.
"""

import argparse
import datetime
import glob
import json
import math
import os
import sys

SCHEMA_VERSION = "1.0"
DEFAULT_SESSION = "b2-2026-11-13"
PRIMARY_ENGINES = ("juring", "seastar")
EXPLORATORY = "juring-buffered"
CELL_ORDER = ["W21-128k", "W22-128k", "W21-1M", "W22-1M"]

# Two-sided 95% Student-t critical values (df = 1..30) and one-sided 95%.
_T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
         2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
         2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042]
_T95_ONE_SIDED = [6.314, 2.920, 2.353, 2.132, 2.015, 1.943, 1.895, 1.860, 1.833, 1.812,
                  1.796, 1.782, 1.771, 1.761, 1.753, 1.746, 1.740, 1.734, 1.729, 1.725,
                  1.721, 1.717, 1.714, 1.711, 1.708, 1.706, 1.703, 1.701, 1.699, 1.697]


# ---------------------------------------------------------------------------
# small statistics helpers (stdlib only)
# ---------------------------------------------------------------------------

def _mean(values):
    return sum(values) / len(values) if values else None


def _sd(values):
    """Sample standard deviation (n-1); None for n < 2."""
    n = len(values)
    if n < 2:
        return None
    m = _mean(values)
    return math.sqrt(sum((x - m) ** 2 for x in values) / (n - 1))


def _t975(df):
    if df < 1:
        return None
    return _T975[df - 1] if df <= len(_T975) else 1.96


def _t95_one_sided(df):
    if df < 1:
        return None
    return _T95_ONE_SIDED[df - 1] if df <= len(_T95_ONE_SIDED) else 1.645


def _ci95(values):
    """Two-sided 95% CI half-width (Student t); None for n < 2."""
    n = len(values)
    if n < 2:
        return None
    return _t975(n - 1) * _sd(values) / math.sqrt(n)


def _one_sided_lower(values):
    """One-sided 95% lower confidence bound of the mean (Student t)."""
    n = len(values)
    if n < 2:
        return None
    return _mean(values) - _t95_one_sided(n - 1) * _sd(values) / math.sqrt(n)


def _cv_pct(values):
    """Coefficient of variation in percent; None when mean is 0."""
    m = _mean(values)
    if not m or len(values) < 2:
        return None
    return 100.0 * _sd(values) / abs(m)


def _r3(x):
    return None if x is None else round(float(x), 6)


# ---------------------------------------------------------------------------
# loading
# ---------------------------------------------------------------------------

def parse_name(stem):
    """`<cell>__<engine>__<variant>__r<rep>` -> (cell, engine, variant, rep)."""
    parts = stem.split("__")
    if len(parts) != 4:
        return None
    cell, engine, variant, repstr = parts
    if not repstr.startswith("r"):
        return None
    try:
        rep = int(repstr[1:])
    except ValueError:
        return None
    if not cell or not engine or not variant or rep < 1:
        return None
    return cell, engine, variant, rep


def engine_row_name(engine, variant):
    if engine == "juring" and variant == "buffered":
        return EXPLORATORY
    return engine


def extract(data):
    """Pull the fields the aggregator needs out of one bench JSON object."""
    out = {"status": data.get("status"), "error": data.get("error")}
    cfg = data.get("config") or {}
    agg = data.get("aggregate") or {}
    runs = data.get("runs") or []

    mibs = agg.get("mibs") or {}
    mibs_mean = mibs.get("mean")
    if mibs_mean is None and runs:
        mibs_mean = _mean([float(r.get("mibs", 0.0)) for r in runs])
    out["mibs"] = mibs_mean

    iops = agg.get("iops") or {}
    iops_mean = iops.get("mean")
    if iops_mean is None and runs:
        iops_mean = _mean([float(r.get("iops", 0.0)) for r in runs])
    out["iops"] = iops_mean

    lat = agg.get("latency_us") or {}
    p50 = lat.get("p50")
    p99 = lat.get("p99")
    if p50 is None and runs:
        p50 = _mean([float((r.get("latency_us") or {}).get("p50", 0.0)) for r in runs])
    if p99 is None and runs:
        p99 = _mean([float((r.get("latency_us") or {}).get("p99", 0.0)) for r in runs])
    out["p50_us"] = p50
    out["p99_us"] = p99

    out["errors"] = agg.get("errors_total")
    if out["errors"] is None:
        out["errors"] = sum(int(r.get("errors_total", 0)) for r in runs)
    out["bytes"] = agg.get("bytes_total")
    out["ops"] = agg.get("ops_total")

    out["workload"] = cfg.get("workload")
    out["bs"] = cfg.get("bs")
    out["qd"] = cfg.get("qd")
    out["dataset_bytes"] = cfg.get("dataset_bytes")
    out["seconds"] = cfg.get("seconds")
    out["warmup_sec"] = cfg.get("warmup_sec")
    out["seed"] = cfg.get("seed")
    out["direct_io"] = cfg.get("direct_io")
    out["registered_files"] = cfg.get("registered_files")
    out["manifest"] = data.get("manifest") or {}
    return out


def load_session(results_dir):
    """Returns (records, unparsed) where records are dicts for every parsed file."""
    pattern = os.path.join(results_dir, "cells", "*.json")
    records = []
    unparsed = []
    for path in sorted(glob.glob(pattern)):
        stem = os.path.basename(path)[:-len(".json")]
        parsed = parse_name(stem)
        if parsed is None:
            unparsed.append({"file": path, "reason": "unrecognised cell filename"})
            continue
        cell, engine, variant, rep = parsed
        rec = {"cell": cell, "engine": engine, "variant": variant, "rep": rep,
               "row": engine_row_name(engine, variant), "path": path, "data": None,
               "error": None, "extracted": None}
        try:
            with open(path, "r", encoding="utf-8") as fh:
                rec["data"] = json.load(fh)
        except Exception as exc:                      # malformed JSON
            rec["error"] = "unreadable/invalid JSON: %s" % exc
        else:
            rec["extracted"] = extract(rec["data"])
        records.append(rec)
    return records, unparsed


# ---------------------------------------------------------------------------
# aggregation
# ---------------------------------------------------------------------------

def stats_block(values_by_rep):
    """values_by_rep: {rep(int): value} -> summary dict (values sorted by rep)."""
    reps = sorted(values_by_rep)
    values = [float(values_by_rep[r]) for r in reps]
    n = len(values)
    block = {
        "n": n,
        "reps": reps,
        "values_by_rep": {str(r): _r3(values_by_rep[r]) for r in reps},
        "mean": _r3(_mean(values)),
        "sd": _r3(_sd(values)),
        "ci95": _r3(_ci95(values)),
        "cv_pct": _r3(_cv_pct(values)),
        "min": _r3(min(values)) if values else None,
        "max": _r3(max(values)) if values else None,
    }
    return block


def analyze_cell(cell, records, expected_reps):
    cell_rows = [r for r in records if r["cell"] == cell]
    cfg = {}
    for rec in cell_rows:
        if rec["extracted"]:
            for key in ("workload", "bs", "qd", "dataset_bytes", "seconds", "warmup_sec", "seed"):
                if cfg.get(key) is None and rec["extracted"].get(key) is not None:
                    cfg[key] = rec["extracted"][key]

    rows = {}
    for name in PRIMARY_ENGINES + (EXPLORATORY,):
        rows[name] = {"variant": "buffered" if name == EXPLORATORY else "std",
                      "mibs_by_rep": {}, "iops_by_rep": {}, "p50_by_rep": {},
                      "p99_by_rep": {}, "errors_total": 0,
                      "excluded": [], "missing": [], "direct_io": None,
                      "registered_files": None, "workload": None}
    excluded_global = []
    missing_global = []

    for rec in cell_rows:
        row = rows.get(rec["row"])
        if row is None:
            excluded_global.append({"file": rec["path"], "reason":
                                    "unknown engine/variant row %r" % (rec["row"],)})
            continue
        if rec["data"] is None:
            row["excluded"].append({"rep": rec["rep"], "file": rec["path"],
                                    "reason": rec["error"] or "missing data"})
            excluded_global.append({"file": rec["path"], "reason": rec["error"] or "missing data"})
            continue
        d = rec["extracted"]
        row["workload"] = row["workload"] or d.get("workload")
        if row["direct_io"] is None:
            row["direct_io"] = d.get("direct_io")
        if row["registered_files"] is None:
            row["registered_files"] = d.get("registered_files")
        reason = None
        if d.get("status") != "ok":
            reason = "status=%s error=%s" % (d.get("status"), d.get("error"))
        elif d.get("errors") not in (None, 0):
            reason = "errors_total=%s" % (d.get("errors"),)
        elif d.get("mibs") is None or float(d["mibs"]) <= 0:
            reason = "non-positive MiB/s"
        if reason is not None:
            row["excluded"].append({"rep": rec["rep"], "file": rec["path"], "reason": reason})
            excluded_global.append({"file": rec["path"], "reason": reason})
            continue
        row["errors_total"] += int(d.get("errors") or 0)
        row["mibs_by_rep"][rec["rep"]] = float(d["mibs"])
        if d.get("iops") is not None:
            row["iops_by_rep"][rec["rep"]] = float(d["iops"])
        if d.get("p50_us") is not None:
            row["p50_by_rep"][rec["rep"]] = float(d["p50_us"])
        if d.get("p99_us") is not None:
            row["p99_by_rep"][rec["rep"]] = float(d["p99_us"])

    engines = {}
    for name, row in rows.items():
        included = set(row["mibs_by_rep"])
        for rep in expected_reps:
            if rep not in included and not any(e["rep"] == rep for e in row["excluded"]):
                row["missing"].append({"rep": rep, "reason": "no JSON for this rep"})
                missing_global.append({"engine": name, "rep": rep,
                                       "cell": cell, "reason": "no JSON for this rep"})
        mibs = stats_block(row["mibs_by_rep"])
        engines[name] = {
            "variant": row["variant"],
            "n": mibs["n"],
            "mibs": mibs,
            "iops": stats_block(row["iops_by_rep"]),
            "latency_us": {
                "p50": stats_block(row["p50_by_rep"]),
                "p99": stats_block(row["p99_by_rep"]),
            },
            "errors_total": row["errors_total"],
            "direct_io": row["direct_io"],
            "registered_files": row["registered_files"],
            "workload": row["workload"],
            "excluded": sorted(row["excluded"], key=lambda e: e["rep"]),
            "missing": sorted(row["missing"], key=lambda e: e["rep"]),
        }
        # convenience scalar means used by the markdown tables
        engines[name]["latency_us"]["p50_mean"] = engines[name]["latency_us"]["p50"]["mean"]
        engines[name]["latency_us"]["p99_mean"] = engines[name]["latency_us"]["p99"]["mean"]

    return {
        "cell": cell,
        "config": cfg,
        "expected_reps": sorted(expected_reps),
        "engines": engines,
        "excluded": sorted(excluded_global, key=lambda e: e["file"]),
        "missing": sorted(missing_global, key=lambda e: (e["engine"], e["rep"])),
        "_row_data": rows,
    }


def paired_block(cell_result, bar):
    """Per-rep paired juring/seastar ratios for the primary O_DIRECT rows."""
    rows = cell_result["_row_data"]
    j = rows["juring"]["mibs_by_rep"]
    s = rows["seastar"]["mibs_by_rep"]
    ratios = {}
    dropped = []
    for rep in cell_result["expected_reps"]:
        if rep in j and rep in s and s[rep] > 0:
            ratios[rep] = j[rep] / s[rep]
            continue
        reasons = []
        if rep not in j:
            reasons.append("juring rep missing/failed")
        if rep not in s:
            reasons.append("seastar rep missing/failed")
        if rep in s and s[rep] <= 0:
            reasons.append("seastar MiB/s <= 0")
        dropped.append({"rep": rep, "reason": "; ".join(reasons)})
    block = stats_block(ratios)
    n = block["n"]
    lower_one = _one_sided_lower(list(ratios[r] for r in sorted(ratios)))
    point_pass = block["mean"] is not None and block["mean"] >= bar
    strict_pass = lower_one is not None and lower_one >= bar
    if n < 2:
        verdict = "insufficient-n"
    elif not point_pass:
        verdict = "below-bar"
    elif strict_pass:
        verdict = "pass"
    else:
        verdict = "inconclusive"
    return {
        "n": n,
        "ratios_by_rep": {str(r): _r3(ratios[r]) for r in sorted(ratios)},
        "ratio_mean": block["mean"],
        "ratio_sd": block["sd"],
        "ratio_ci95": [None if block["ci95"] is None else _r3(block["mean"] - block["ci95"]),
                       None if block["ci95"] is None else _r3(block["mean"] + block["ci95"])],
        "ratio_one_sided_95_lower": _r3(lower_one),
        "bar": bar,
        "point_estimate_pass": bool(point_pass),
        "lower_bound_pass": bool(strict_pass),
        "verdict": verdict,
        "dropped_reps": dropped,
    }


# ---------------------------------------------------------------------------
# markdown rendering
# ---------------------------------------------------------------------------

def _fmt(v, digits=1, dash="n/a"):
    if v is None:
        return dash
    return ("%." + str(digits) + "f") % float(v)


def render_markdown(session, results_dir, bar, cells, overall, unparsed, protocol):
    lines = []
    a = lines.append
    a("# B2 W21/W22 stream matrix -- aggregate")
    a("")
    a("Session: `%s`  " % session)
    a("Source: `%s`  " % results_dir)
    a("Generated: %s  " % datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    a("Verdict bar (plan M2): `juring MiB/s >= %.2f * seastar MiB/s`  " % bar)
    a("Contract: `docs/b2-design.md` §3.3. Raw protocol: `%s/manifest.txt`." % results_dir)
    a("")
    if protocol.get("seconds") is not None:
        a("Protocol observed in the cell JSONs: warmup %ss + measured %ss per process, "
          "R=%s reps per cell (preliminary; the full M2 campaign uses longer cells/rests)."
          % (_fmt(protocol.get("warmup_sec"), 0), _fmt(protocol.get("seconds"), 0),
             protocol.get("reps")))
        a("")
    a("## Verdict")
    a("")
    a("| cell | workload | n (paired) | juring MiB/s | seastar MiB/s | ratio mean | ratio 95% CI | one-sided 95% LB | verdict |")
    a("|---|---|---|---|---|---|---|---|---|")
    for c in cells:
        e = c["engines"]
        j = e["juring"]["mibs"]["mean"]
        s = e["seastar"]["mibs"]["mean"]
        p = c["paired"]
        ci = p["ratio_ci95"]
        ci_s = "n/a" if ci[0] is None else "[%.3f, %.3f]" % (ci[0], ci[1])
        a("| %s | %s | %d | %s | %s | %s | %s | %s | %s |" % (
            c["cell"], c["config"].get("workload", "?"), p["n"],
            _fmt(j, 1), _fmt(s, 1), _fmt(p["ratio_mean"], 3), ci_s,
            _fmt(p["ratio_one_sided_95_lower"], 3), p["verdict"]))
    a("")
    a("Overall: point-estimate %s; strict one-sided-95%%-lower-bound %s."
      % ("PASS" if overall["point_estimate_pass"] else "FAIL",
         "PASS" if overall["lower_bound_pass"] else "NOT ESTABLISHED"))
    a("")
    if overall.get("parity_note"):
        a(overall["parity_note"])
        a("")
    a("## Model-MiB/s detail (mean ± 95% CI, sample sd, CV)")
    a("")
    a("| cell | engine | n | MiB/s mean | sd | 95% CI ± | CV% | errors | direct_io | registered_files |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for c in cells:
        for name in ("juring", "seastar", EXPLORATORY):
            r = c["engines"].get(name)
            if r is None or r["n"] == 0:
                a("| %s | %s | 0 | n/a | n/a | n/a | n/a | %s | %s | %s |" % (
                    c["cell"], name, r["errors_total"] if r else "n/a",
                    r["direct_io"] if r else "?", r["registered_files"] if r else "?"))
                continue
            m = r["mibs"]
            a("| %s | %s%s | %d | %s | %s | %s | %s | %d | %s | %s |" % (
                c["cell"], name, " (exploratory)" if name == EXPLORATORY else "",
                m["n"], _fmt(m["mean"], 1), _fmt(m["sd"], 2), _fmt(m["ci95"], 2),
                _fmt(m["cv_pct"], 2), r["errors_total"], r["direct_io"],
                r["registered_files"]))
    a("")
    a("## Chunk latency (mean of per-run p50/p99, microseconds)")
    a("")
    a("| cell | engine | n | p50 mean | p99 mean |")
    a("|---|---|---|---|---|")
    for c in cells:
        for name in ("juring", "seastar", EXPLORATORY):
            r = c["engines"].get(name)
            if r is None or r["n"] == 0:
                a("| %s | %s | 0 | n/a | n/a |" % (c["cell"], name))
                continue
            lat = r["latency_us"]
            a("| %s | %s | %d | %s | %s |" % (
                c["cell"], name, r["n"],
                _fmt(lat["p50"]["mean"], 1), _fmt(lat["p99"]["mean"], 1)))
    a("")
    a("## Data quality (no rep silently dropped)")
    a("")
    excluded = [(c["cell"], name, e) for c in cells
                for name in ("juring", "seastar", EXPLORATORY)
                for e in c["engines"][name]["excluded"]]
    missing = [(c["cell"], name, e) for c in cells
               for name in ("juring", "seastar", EXPLORATORY)
               for e in c["engines"][name]["missing"]]
    paired_dropped = [(c["cell"], d) for c in cells for d in c["paired"]["dropped_reps"]]
    if not excluded and not missing and not paired_dropped and not unparsed:
        a("Every rep file parsed, status ok, zero errors, and every expected rep was paired.")
    else:
        for cell, name, e in excluded:
            a("- EXCLUDED %s / %s rep %s: %s (`%s`)" % (cell, name, e["rep"], e["reason"], e["file"]))
        for cell, name, e in missing:
            a("- MISSING %s / %s rep %s: %s" % (cell, name, e["rep"], e["reason"]))
        for cell, d in paired_dropped:
            a("- UNPAIRED %s rep %s: %s" % (cell, d["rep"], d["reason"]))
        for u in unparsed:
            a("- UNPARSED `%s`: %s" % (u["file"], u["reason"]))
    a("")
    a("Notes: the exploratory `juring-buffered` row is disclosed only and is never used for the")
    a("verdict (seastar fstream is O_DIRECT/DMA here, so the matched-primary rows are O_DIRECT on")
    a("both sides). 128K cells are the methodology's W21/W22 wording; 1M cells address the plan's")
    a("\"seq 1M\" wording. The overall verdict requires all primary cells listed above to clear the")
    a("bar; the strict reading additionally requires the one-sided 95% lower bound of the paired")
    a("ratio to clear the bar. This is a preliminary measurement (durations/rests in manifest.txt).")
    a("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(here, "..", ".."))

    ap = argparse.ArgumentParser(
        description="Aggregate B2 W21/W22 stream cells into aggregate/stream.{json,md}.")
    ap.add_argument("--session", default=DEFAULT_SESSION,
                    help="session name under bench/results/ (default %(default)s)")
    ap.add_argument("--results", default=None,
                    help="explicit session directory (default bench/results/<session>)")
    ap.add_argument("--out", default=None,
                    help="output directory (default <session>/aggregate)")
    ap.add_argument("--bar", type=float, default=0.85,
                    help="plan-M2 bar juring/seastar (default %(default)s)")
    args = ap.parse_args(argv)

    results_dir = os.path.abspath(args.results) if args.results else os.path.join(
        repo_root, "bench", "results", args.session)
    out_dir = os.path.abspath(args.out) if args.out else os.path.join(results_dir, "aggregate")

    records, unparsed = load_session(results_dir)
    if not records:
        sys.stderr.write("error: no cell JSON files found under %s/cells\n" % results_dir)
        return 1
    os.makedirs(out_dir, exist_ok=True)

    parsed_cells = sorted({r["cell"] for r in records},
                          key=lambda c: (CELL_ORDER.index(c) if c in CELL_ORDER else len(CELL_ORDER), c))
    cells_out = []
    protocol = {"reps": None, "seconds": None, "warmup_sec": None}
    seconds_seen = None
    warmup_seen = None
    valid_reps = 0
    for cell in parsed_cells:
        cell_records = [r for r in records if r["cell"] == cell]
        reps = sorted({r["rep"] for r in cell_records})
        expected_reps = set(reps)
        # Displayed R only counts reps whose JSON was actually readable; a corrupt
        # file still marks the rep as observed (and is reported under Data quality).
        readable = {r["rep"] for r in cell_records if r["data"] is not None}
        if readable:
            valid_reps = max(valid_reps, len(readable))
        for rec in cell_records:
            if rec["extracted"]:
                if rec["extracted"].get("seconds") is not None:
                    seconds_seen = rec["extracted"]["seconds"]
                if rec["extracted"].get("warmup_sec") is not None:
                    warmup_seen = rec["extracted"]["warmup_sec"]
        result = analyze_cell(cell, cell_records, expected_reps)
        result["paired"] = paired_block(result, args.bar)
        result.pop("_row_data", None)
        cells_out.append(result)

    protocol["reps"] = valid_reps or max((len(c["expected_reps"]) for c in cells_out), default=None)
    protocol["seconds"] = seconds_seen
    protocol["warmup_sec"] = warmup_seen

    primary = [c for c in cells_out
               if c["engines"]["juring"]["n"] > 0 and c["engines"]["seastar"]["n"] > 0]
    point_pass = bool(primary) and all(c["paired"]["point_estimate_pass"] for c in primary)
    strict_pass = bool(primary) and all(c["paired"]["lower_bound_pass"] for c in primary)
    verdicts = {c["cell"]: c["paired"]["verdict"] for c in cells_out}

    ratio_means = [c["paired"]["ratio_mean"] for c in primary if c["paired"]["ratio_mean"] is not None]
    best = max(primary, key=lambda c: (c["paired"]["ratio_mean"] if c["paired"]["ratio_mean"] is not None else -1),
               default=None)
    parity_note = None
    if ratio_means:
        parity_note = ("Parity note: mean paired ratio over the primary cells ranges %.3f..%.3f; "
                       "the best cell is %s at %.3f. A ratio >= 1.0 means JUring matched or beat "
                       "seastar fstream on that cell."
                       % (min(ratio_means), max(ratio_means), best["cell"] if best else "n/a",
                          max(ratio_means)))
    overall = {
        "bar": args.bar,
        "primary_cells": [c["cell"] for c in primary],
        "point_estimate_pass": point_pass,
        "lower_bound_pass": strict_pass,
        "verdict": "pass" if strict_pass else ("below-bar" if not point_pass else "inconclusive"),
        "cell_verdicts": verdicts,
        "ratio_mean_range": [min(ratio_means), max(ratio_means)] if ratio_means else None,
        "parity_note": parity_note,
    }

    out = {
        "schema_version": SCHEMA_VERSION,
        "session": args.session,
        "source_dir": results_dir,
        "generated_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "bar": args.bar,
        "protocol": protocol,
        "cells": cells_out,
        "overall": overall,
        "declared_protocol": {
            "reps_default": 3,
            "warmup_sec_default": 10,
            "seconds_default": 30,
            "rest_sec_default": 60,
            "cpu": 2,
            "note": "durations/rests are preliminary (docs/b2-design.md §3.3); see manifest.txt for the "
                    "exact run protocol",
        },
        "unparsed_files": unparsed,
        "exploratory_note": "juring-buffered rows are disclosed exploratory only; never used for the verdict",
    }

    json_path = os.path.join(out_dir, "stream.json")
    md_path = os.path.join(out_dir, "stream.md")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=2, sort_keys=False)
        fh.write("\n")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(render_markdown(args.session, results_dir, args.bar, cells_out, overall,
                                 unparsed, protocol))
        fh.write("\n")

    print("wrote %s" % json_path)
    print("wrote %s" % md_path)
    print("overall: point_estimate_pass=%s lower_bound_pass=%s verdict=%s"
          % (point_pass, strict_pass, overall["verdict"]))
    for c in cells_out:
        p = c["paired"]
        print("  %-10s %-14s juring=%.1f seastar=%.1f ratio=%s (%s)"
              % (c["cell"], c["config"].get("workload", "?"),
                 c["engines"]["juring"]["mibs"]["mean"] or 0.0,
                 c["engines"]["seastar"]["mibs"]["mean"] or 0.0,
                 "n/a" if p["ratio_mean"] is None else "%.3f" % p["ratio_mean"],
                 p["verdict"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
