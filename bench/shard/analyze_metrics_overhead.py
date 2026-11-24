#!/usr/bin/env python3
"""Observability overhead (metrics on vs off) A/B analyzer.  Protocol frozen before the session.

Reads a ``bench/shard/run-metrics-overhead.sh`` session (cell-meta.tsv plus the paired
``<cell>__b4|b5__r<rep>.json`` cell JSONs) and writes ``aggregate/overhead.{json,md}``.
The frozen metric is the paired per-rep throughput ratio metrics-on/baseline: mean >= 0.99 in
every cell and no undisclosed rep below 0.95.  State-gated reps are disclosed,
never trimmed.

Exit: 0 = PASS, 1 = FAIL, 2 = INCONCLUSIVE, 3 = NO DATA.

Usage:
    python3 bench/shard/analyze_metrics_overhead.py --session b5-overhead-2026-11-14
    python3 bench/shard/analyze_metrics_overhead.py --results-dir <dir> [--out-dir <dir>]
"""
import argparse
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import _cells  # noqa: E402
from analysis import cli, jsonio, md, stats  # noqa: E402

DEFAULT_RESULTS_ROOT = jsonio.REPO_ROOT / "bench" / "results"

CELL_RE = re.compile(r"^(.+?)__(b4|b5)__r(\d+)\.json$")
CELL_ORDER = ["shard1", "shard8", "threads1"]
FAST_P99_US = 600.0          # 4K randread QD64 fast-state threshold (single ring)
# Cell-adjusted fast-state threshold (Lead-approved, analysis-only): the methodology's
# 600 us calibration is for ONE QD64 ring. A cell with W workers has W x 64 requests in
# flight, so its normal p99 scales with W (the shard-runtime report measured p99(8)/p99(1) = 5.1-5.9 for
# every engine incl. fio at constant QD64/worker). The approved gate is therefore
# 600 us x W; the literal 600 us gate is still reported side by side.
FAST_P99_US_PER_WORKER = 600.0
IOPS_ANOMALY_FACTOR = 0.92   # rep < 0.92 x max IOPS of its (cell, arm) => slow-state
MEAN_RATIO_BAR = 0.99        # < 1 % throughput cost
REP_RATIO_FLOOR = 0.95       # no undisclosed rep below this


def t_crit_one_sided_95(df):
    """One-sided 95 % t multiplier (df = n-1), on the frozen 4 dp table.

    The shared :func:`stats.t95_one_sided` is 3 dp and would move the published
    campaign CI digits; ``stats.T95_ONE_SIDED_4DP`` is the table this campaign used.
    """
    if df <= 0:
        return 0.0
    return stats.T95_ONE_SIDED_4DP.get(df, 1.645)


def p99_threshold_literal(rec):
    return FAST_P99_US


def p99_threshold_cell_adjusted(rec):
    return FAST_P99_US_PER_WORKER * max(1, int(rec.get("workers") or 1))


def extract_invocation(arm, cell, rep, path, meta):
    rec = {
        "cell": cell, "arm": arm, "rep": rep, "path": path,
        "valid": False, "status": "missing", "error": None,
        "iops": 0.0, "p50_us": None, "p99_us": None, "ops": 0, "errors": 0,
        "schema": "?", "metrics_enabled": False, "series_points": 0,
        "dispatch_samples": 0, "ring_metrics_present": False,
        "runtime": "?", "workers": 1,
        "ring_workers": [],
        "gate_start": meta.get("gate_start") if meta else None,
        "gate_end": meta.get("gate_end") if meta else None,
        "p99_state": meta.get("p99_state") if meta else None,
        "meta_status": meta.get("status") if meta else None,
        "wall_s": meta.get("wall_s") if meta else None,
        "iops_anomaly": False,
    }
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as exc:  # noqa: BLE001
        rec["status"] = "unreadable"
        rec["error"] = str(exc)
        return rec
    runs = data.get("runs") or []
    run = runs[0] if runs else {}
    cfg = data.get("config") or {}
    lat = run.get("latency_us") or {}
    run_metrics = run.get("metrics") or {}
    schema = str(data.get("schema_version") or "?")
    rec.update({
        "status": str(data.get("status") or "missing"),
        "schema": schema,
        "iops": float(run.get("iops") or 0.0),
        "p50_us": float(lat.get("p50") or 0.0),
        "p99_us": float(lat.get("p99") or 0.0),
        "ops": int(run.get("ops") or 0),
        "errors": int(run.get("errors") or 0),
        "runtime": str(cfg.get("runtime", "threads")),
        "workers": max(1, int(cfg.get("shards") or 0)) if cfg.get("runtime") == "shard"
                   else max(1, int(cfg.get("threads") or 1)),
        "metrics_enabled": bool(run_metrics.get("enabled")),
        "series_points": int(run_metrics.get("ring_series_points") or 0),
    })
    worker_entries = (run.get("shards") or []) + (run.get("threads") or [])
    rec["ring_metrics_present"] = any(isinstance(w.get("ring_metrics"), dict) for w in worker_entries)
    if arm == "b5" and rec["ring_metrics_present"]:
        # shards[] and threads[] hold the same workers in shard mode: count one of them.
        keys = "shards" if run.get("shards") else "threads"
        entry_key = "shard" if keys == "shards" else "thread"
        for w in run.get(keys) or []:
            depth = _cells.series_depth(w.get("ring_series") or [])
            dispatch = w.get("dispatch_latency_us") or {}
            counters = _cells.ring_counters(w.get("ring_metrics") or {})
            rec["ring_workers"].append({
                "ring": int(w.get(entry_key, len(rec["ring_workers"]))),
                "mean_in_flight": depth["mean_in_flight"] if depth else None,
                "max_in_flight": depth["max_in_flight"] if depth else None,
                "mean_sq_queued": depth["mean_sq_queued"] if depth else None,
                "mean_cq_ready": depth["mean_cq_ready"] if depth else None,
                "series_points": depth["series_points"] if depth else 0,
                "dispatch_p99_us": jsonio.as_float(dispatch.get("p99")),
                "external_p99_us": float((w.get("latency_us") or {}).get("p99") or 0.0),
                "failed": counters["failed"],
                "cancelled": counters["cancelled"],
            })
    unique_workers = (run.get("shards") or []) if run.get("shards") else (
        run.get("threads") or [])
    rec["series_points"] = sum(
        len(w.get("ring_series") or []) for w in unique_workers) or rec["series_points"]
    dispatch = run.get("dispatch_latency_us") or {}
    rec["dispatch_samples"] = int(dispatch.get("samples") or 0)
    # Arm identity: baseline = schema 1.0 / no metrics; metrics-on = schema 1.1 / metrics on.
    reason = None
    if rec["status"] != "ok":
        reason = "status=%s" % rec["status"]
    elif rec["iops"] <= 0:
        reason = "iops<=0"
    elif rec["errors"] != 0:
        reason = "errors=%d" % rec["errors"]
    elif arm == "b4" and schema != "1.0":
        reason = "baseline arm JSON schema %s != 1.0 (wrong build?)" % schema
    elif arm == "b5" and (schema != "1.1" or not rec["metrics_enabled"]):
        reason = "metrics-on arm schema=%s metrics=%s (expected 1.1/on)" % (
            schema, rec["metrics_enabled"])
    if reason is None:
        rec["valid"] = True
    else:
        rec["error"] = reason
        rec["status"] = "invalid" if rec["status"] == "ok" else rec["status"]
    if meta and meta.get("status") in ("fail", "gate"):
        rec["valid"] = False
        rec["status"] = str(meta.get("status"))
        rec["error"] = rec["error"] or "meta status=%s" % meta.get("status")
    return rec


def load_session(results_dir):
    cells_dir = os.path.join(results_dir, "cells")
    meta = {row["id"]: row for row in
            jsonio.load_csv_rows(os.path.join(results_dir, "cell-meta.tsv"))
            if row.get("id")}
    records = []
    if not os.path.isdir(cells_dir):
        return records
    for name in sorted(os.listdir(cells_dir)):
        if not name.endswith(".json"):
            continue
        match = CELL_RE.match(name)
        if not match:
            continue
        cell, arm, rep = match.group(1), match.group(2), int(match.group(3))
        path = os.path.join(cells_dir, name)
        inv_id = "%s__%s__r%d" % (cell, arm, rep)
        records.append(extract_invocation(arm, cell, rep, path, meta.get(inv_id)))
    return records


def classify_states(records):
    """Mark the IOPS-anomaly slow-state reps per (cell, arm), keeping all reps."""
    groups = {}
    for rec in records:
        if rec["valid"]:
            groups.setdefault((rec["cell"], rec["arm"]), []).append(rec)
    for recs in groups.values():
        iops = [r["iops"] for r in recs]
        cell_max = max(iops) if iops else 0.0
        for rec in recs:
            rec["iops_anomaly"] = bool(
                cell_max > 0 and rec["iops"] < IOPS_ANOMALY_FACTOR * cell_max)
            # Two disclosures side by side: the literal methodology 600 us gate and the
            # Lead-approved cell-adjusted 600 us x total-workers gate (analysis-only).
            rec["p99_literal_slow"] = (rec["p99_us"] or 0.0) >= p99_threshold_literal(rec)
            rec["p99_adjusted_slow"] = (rec["p99_us"] or 0.0) >= p99_threshold_cell_adjusted(rec)
    return groups


def pair_gate(b4, b5, adjusted):
    """Return (gated, reasons) for one paired rep under a given p99 gate labelling."""
    threshold_b4 = (p99_threshold_cell_adjusted(b4) if adjusted
                    else p99_threshold_literal(b4))
    threshold_b5 = (p99_threshold_cell_adjusted(b5) if adjusted
                    else p99_threshold_literal(b5))
    reasons = []
    if b4["iops_anomaly"]:
        reasons.append("baseline arm IOPS anomaly (< 0.92 x max of its cell/arm)")
    if b5["iops_anomaly"]:
        reasons.append("metrics-on arm IOPS anomaly (< 0.92 x max of its cell/arm)")
    if (b4["p99_us"] or 0.0) >= threshold_b4:
        reasons.append("baseline arm slow-state p99 >= %.0f us" % threshold_b4)
    if (b5["p99_us"] or 0.0) >= threshold_b5:
        reasons.append("metrics-on arm slow-state p99 >= %.0f us" % threshold_b5)
    if _cells.gate_violation(b4["gate_start"]) or _cells.gate_violation(b4["gate_end"]):
        reasons.append("baseline arm background-load gate")
    if _cells.gate_violation(b5["gate_start"]) or _cells.gate_violation(b5["gate_end"]):
        reasons.append("metrics-on arm background-load gate")
    return bool(reasons), reasons


def analyze(records):
    by_key = {}
    for rec in records:
        by_key[(rec["cell"], rec["arm"], rec["rep"])] = rec
    cells = []
    seen = set()
    for rec in records:
        if rec["cell"] not in seen:
            seen.add(rec["cell"])
            cells.append(rec["cell"])
    cells.sort(key=lambda c: (CELL_ORDER.index(c) if c in CELL_ORDER else len(CELL_ORDER), c))

    cell_results = {}
    for cell in cells:
        reps = sorted({rep for (c, _a, rep) in by_key if c == cell})
        pairs = []
        skipped = []
        for rep in reps:
            b4 = by_key.get((cell, "b4", rep))
            b5 = by_key.get((cell, "b5", rep))
            if b4 is None or b5 is None:
                skipped.append({"rep": rep, "reason": "missing arm invocation"})
                continue
            if not b4["valid"] or not b5["valid"]:
                skipped.append({
                    "rep": rep,
                    "reason": "invalid invocation (baseline: %s; metrics-on: %s)"
                              % (b4["error"] or "n/a", b5["error"] or "n/a"),
                })
                continue
            ratio = b5["iops"] / b4["iops"] if b4["iops"] > 0 else None
            if ratio is None:
                skipped.append({"rep": rep, "reason": "non-positive baseline IOPS"})
                continue
            gated_literal, reasons_literal = pair_gate(b4, b5, adjusted=False)
            gated_adjusted, reasons_adjusted = pair_gate(b4, b5, adjusted=True)
            below = ratio < REP_RATIO_FLOOR
            pairs.append({
                "rep": rep,
                "b4": b4, "b5": b5,
                "ratio": ratio,
                "state_gated": gated_adjusted,           # approved (primary) labelling
                "state_reasons": reasons_adjusted,
                "state_gated_literal": gated_literal,
                "state_reasons_literal": reasons_literal,
                "below_floor": below,
                "undisclosed_below_floor": below and not gated_adjusted,
                "undisclosed_below_floor_literal": below and not gated_literal,
            })
        ratios = [p["ratio"] for p in pairs]
        n = len(ratios)
        if n == 0:
            cell_results[cell] = {
                "n_pairs": 0, "reduced_reps": True, "ratios": [], "pairs": pairs,
                "skipped": skipped, "mean_ratio": None, "sd": None, "se": None,
                "ci_lower_one_sided_95": None, "min_ratio": None, "max_ratio": None,
                "b4_mean_iops": None, "b5_mean_iops": None,
                "overhead_pct": None, "mean_pass": None, "rep_floor_violations": [],
                "rep_floor_violations_literal": [],
                "state_gated_reps": [], "state_gated_reps_literal": [],
                "threshold_literal_us": None, "threshold_cell_adjusted_us": None,
                "state_clean_n": 0, "state_clean_mean_ratio": None,
                "state_clean_mean_ratio_literal": None,
                "verdict": "NO DATA", "verdict_literal": "NO DATA",
            }
            continue
        m = stats.mean(ratios)
        sd = stats.sd(ratios) if n > 1 else 0.0
        se = sd / math.sqrt(n) if n > 1 else 0.0
        tcrit = t_crit_one_sided_95(n - 1)
        ci_lower = m - tcrit * se
        # Disclosure-only: the same statistics over the state-clean subset. The frozen
        # bar uses all reps (nothing is trimmed); this secondary view shows whether a
        # FAIL is driven by disclosed slow-state reps.
        clean = [p["ratio"] for p in pairs if not p["state_gated"]]
        cmean = stats.mean(clean) if clean else None
        csd = stats.sd(clean) if clean else None
        cse = csd / math.sqrt(len(clean)) if clean and len(clean) > 1 else 0.0
        cci = (cmean - t_crit_one_sided_95(len(clean) - 1) * cse) if clean else None
        b4_iops = [p["b4"]["iops"] for p in pairs]
        b5_iops = [p["b5"]["iops"] for p in pairs]
        below = [p for p in pairs if p["undisclosed_below_floor"]]
        gated = [p for p in pairs if p["state_gated"]]
        below_literal = [p for p in pairs if p["undisclosed_below_floor_literal"]]
        gated_literal = [p for p in pairs if p["state_gated_literal"]]
        clean_literal = [p["ratio"] for p in pairs if not p["state_gated_literal"]]
        cmean_literal = stats.mean(clean_literal) if clean_literal else None
        mean_pass = m >= MEAN_RATIO_BAR
        verdict = "PASS" if (mean_pass and not below) else "FAIL"
        verdict_literal = "PASS" if (mean_pass and not below_literal) else "FAIL"
        workers = max([int(p["b4"].get("workers") or 1) for p in pairs]
                      + [int(p["b5"].get("workers") or 1) for p in pairs] + [1])
        cell_results[cell] = {
            "n_pairs": n,
            "reduced_reps": n < 5,
            "ratios": r3_list(ratios),
            "pairs": pairs,
            "skipped": skipped,
            "mean_ratio": _cells.r3(m),
            "sd": _cells.r3(sd),
            "se": _cells.r3(se),
            "t_crit_one_sided_95": tcrit,
            "df": n - 1,
            "ci_lower_one_sided_95": _cells.r3(ci_lower),
            "min_ratio": _cells.r3(min(ratios)),
            "max_ratio": _cells.r3(max(ratios)),
            "b4_mean_iops": _cells.r3(stats.mean(b4_iops)),
            "b5_mean_iops": _cells.r3(stats.mean(b5_iops)),
            "overhead_pct": _cells.r3((1.0 - m) * 100.0),
            "mean_pass": bool(mean_pass),
            "rep_floor_violations": [p["rep"] for p in below],
            "state_gated_reps": [p["rep"] for p in gated],
            "state_clean_n": len(clean),
            "state_clean_mean_ratio": _cells.r3(cmean),
            "state_clean_ci_lower_one_sided_95": _cells.r3(cci),
            "verdict": verdict,
            "workers": workers,
            "threshold_literal_us": FAST_P99_US,
            "threshold_cell_adjusted_us": FAST_P99_US_PER_WORKER * workers,
            "rep_floor_violations_literal": [p["rep"] for p in below_literal],
            "state_gated_reps_literal": [p["rep"] for p in gated_literal],
            "state_clean_mean_ratio_literal": _cells.r3(cmean_literal),
            "verdict_literal": verdict_literal,
        }

    all_skipped = sum(len(v["skipped"]) for v in cell_results.values())
    missing_cells = [c for c in CELL_ORDER if c not in cell_results]
    total_pairs = sum(v["n_pairs"] for v in cell_results.values())

    def overall_verdict(key):
        if total_pairs == 0:
            return "NO DATA", False
        if missing_cells:
            return ("INCONCLUSIVE (missing cell data: %s)" % ", ".join(missing_cells), False)
        if any(v["n_pairs"] == 0 for v in cell_results.values()):
            return "INCONCLUSIVE (a cell has no valid pair)", False
        if any(v[key] == "FAIL" for v in cell_results.values()):
            return "FAIL (overhead bar missed)", False
        return "PASS", True

    # Approved (primary) labelling: cell-adjusted fast-state p99 gate.
    verdict, pass_flag = overall_verdict("verdict")
    # Literal methodology labelling: 600 us flat gate (reported side by side).
    verdict_literal, pass_literal = overall_verdict("verdict_literal")
    reduced = any(v["reduced_reps"] for v in cell_results.values())
    if pass_flag and reduced:
        verdict = "PASS (reduced reps, disclosed)"
    if pass_literal and reduced:
        verdict_literal = "PASS (reduced reps, disclosed)"
    return {
        "cells": cell_results,
        "cell_order": cells,
        "missing_cells": missing_cells,
        "total_pairs": total_pairs,
        "skipped_pairs": all_skipped,
        "reduced_reps": reduced,
        "pass": bool(pass_flag),
        "verdict": verdict,
        "pass_literal": bool(pass_literal),
        "verdict_literal": verdict_literal,
        "labelling": {
            "primary": "cell-adjusted fast-state p99 = %.0f us x total workers"
                       % FAST_P99_US_PER_WORKER,
            "literal": "methodology literal flat %.0f us" % FAST_P99_US,
            "iops_anomaly": "rep < %.2f x max IOPS of its own (cell, arm) for both"
                            % IOPS_ANOMALY_FACTOR,
            "f14": "same for both",
        },
    }


def per_ring_evidence(records):
    """Per-ring queue-depth/p99 evidence for the metrics-on arm, aggregated over the valid reps.

    This is the multi-shard scaling report's "per-ring queue depth and p99" data from schema 1.1
    (`ring_series` + `ring_metrics` + `dispatch_latency_us` per worker).
    """
    out = {}
    cells = sorted({r["cell"] for r in records})
    for cell in cells:
        reps = [r for r in records
                if r["cell"] == cell and r["arm"] == "b5" and r["valid"]
                and r.get("ring_workers")]
        if not reps:
            continue
        rows = {}
        for rec in reps:
            for w in rec["ring_workers"]:
                row = rows.setdefault(w["ring"], {
                    "ring": w["ring"], "reps": 0,
                    "mean_in_flight": [], "max_in_flight": [],
                    "mean_sq_queued": [], "mean_cq_ready": [],
                    "dispatch_p99_us": [], "external_p99_us": [],
                    "series_points": [], "failed": 0, "cancelled": 0,
                })
                row["reps"] += 1
                for key in ("mean_in_flight", "max_in_flight", "mean_sq_queued",
                            "mean_cq_ready", "dispatch_p99_us"):
                    if w.get(key) is not None:
                        row[key].append(float(w[key]))
                if w.get("external_p99_us"):
                    row["external_p99_us"].append(float(w["external_p99_us"]))
                if w.get("series_points"):
                    row["series_points"].append(int(w["series_points"]))
                row["failed"] += int(w.get("failed") or 0)
                row["cancelled"] += int(w.get("cancelled") or 0)
        entries = []
        for ring in sorted(rows):
            row = rows[ring]
            entries.append({
                "ring": ring,
                "reps": row["reps"],
                "mean_in_flight": _cells.r3(stats.mean(row["mean_in_flight"])) if row["mean_in_flight"] else None,
                "max_in_flight": max(row["max_in_flight"]) if row["max_in_flight"] else None,
                "mean_sq_queued": _cells.r3(stats.mean(row["mean_sq_queued"])) if row["mean_sq_queued"] else None,
                "mean_cq_ready": _cells.r3(stats.mean(row["mean_cq_ready"])) if row["mean_cq_ready"] else None,
                "dispatch_p99_us": _cells.r3(stats.mean(row["dispatch_p99_us"])) if row["dispatch_p99_us"] else None,
                "external_p99_us": _cells.r3(stats.mean(row["external_p99_us"])) if row["external_p99_us"] else None,
                "series_points": round(stats.mean(row["series_points"])) if row["series_points"] else None,
                "failed": row["failed"],
                "cancelled": row["cancelled"],
            })
        out[cell] = entries
    return out


def r3_list(values):
    return [round(float(v), 3) for v in values]


def render_markdown(session, results_dir, analysis, records, b4_commit, frozen):
    add = []
    cells = analysis["cells"]
    add.append("# Observability overhead A/B — analysis (`%s`)" % session)
    add.append("")
    add.append("> **Protocol (frozen before the session).** Paired per-rep ratio "
               "`throughput(metrics-on)/throughput(baseline)` for the same cell/rep/position; arms "
               "interleaved with the arm order alternating per rep; baseline = commit "
               "`%s` (no metrics) built from a `git archive` scratch tree; metrics-on = frozen "
               "tree with metrics **on** (`ring_metrics`, 1 Hz `ring_series`, shard "
               "`withLatencySampleEvery(1)`). PASS = mean paired ratio >= %.2f in every "
               "cell and no individual rep below %.2f unless state-gated and disclosed. "
               "Every rep is reported; nothing is trimmed."
               % (b4_commit, MEAN_RATIO_BAR, REP_RATIO_FLOOR))
    add.append("")
    add.append("- results: `%s`" % results_dir)
    add.append("- cells: `shard1` (shard N=1), `shard8` (shard N=8), `threads1` "
               "(threads N=1) — 4K randread QD 64 per worker, CPUs 1..N, "
               "10 s warmup + 30 s measured, R=5, 30 s rests, fresh JVM/process per "
               "invocation.")
    add.append("- frozen-tree identity at session: HEAD=`%s` src=`%s` test=`%s`"
               % (frozen.get("head") or "n/a", frozen.get("src_main_sha1") or "n/a",
                  frozen.get("src_test_sha1") or "n/a"))
    add.append("")
    add.append("## 0. Verdict")
    add.append("")
    add.append("**%s**" % analysis["verdict"])
    add.append("")
    add.append("The primary verdict uses the Lead-approved **cell-adjusted** fast-state "
               "gate (p99 < %.0f us × total workers in the cell: %.0f us at N=1, %.0f us "
               "at N=8); under the literal methodology flat %.0f us gate the verdict is "
               "**%s**. The PRIMARY mean bar (mean paired ratio >= %.2f in every cell) is "
               "identical under both labellings because every rep enters every mean — the "
               "labelling only decides which sub-%.2f individual reps may be disclosed as "
               "state-gated. No rep is trimmed under either labelling."
               % (FAST_P99_US_PER_WORKER, FAST_P99_US, FAST_P99_US_PER_WORKER * 8,
                  FAST_P99_US, analysis["verdict_literal"], MEAN_RATIO_BAR,
                  REP_RATIO_FLOOR))
    add.append("")
    add.append("Metric: mean paired ratio per cell (lower is worse for metrics-on); "
               "`overhead` = `(1 - ratio) * 100 %`.")
    add.append("")
    add.append("| cell | workers | pairs (R) | baseline mean IOPS | metrics-on mean IOPS | "
               "mean paired ratio | stdev | one-sided 95 % CI lower | min rep ratio | "
               "overhead % | state-gated reps (adjusted) | verdict (adjusted) |")
    add.append("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for cell in analysis["cell_order"]:
        c = cells[cell]
        add.append("| %s | %d | %d%s | %s | %s | %s | %s | %s | %s | %s | %s | %s |"
                   % (cell, c.get("workers", 1), c["n_pairs"],
                      " (reduced)" if c["reduced_reps"] else "",
                      md.fixed(c["b4_mean_iops"], 0), md.fixed(c["b5_mean_iops"], 0),
                      md.fixed(c["mean_ratio"], 4), md.fixed(c["sd"], 4),
                      md.fixed(c["ci_lower_one_sided_95"], 4), md.fixed(c["min_ratio"], 4),
                      md.fixed(c["overhead_pct"], 2),
                      ",".join(str(x) for x in c["state_gated_reps"]) or "-",
                      c["verdict"]))
    add.append("")
    add.append("Pass conditions: every cell mean ratio >= %.2f; no non-state-gated rep "
               "ratio < %.2f. The one-sided 95 %% CI uses Student's t with df = R-1 "
               "(t=%.4f at R=5); with R=5 the lower bound is the mean minus %.2f x "
               "(stdev/sqrt(5))."
               % (MEAN_RATIO_BAR, REP_RATIO_FLOOR,
                  t_crit_one_sided_95(4), t_crit_one_sided_95(4)))
    add.append("")
    add.append("## 1. All reps (paired, raw, never trimmed)")
    add.append("")
    add.append("| cell | rep | baseline IOPS | metrics-on IOPS | ratio metrics-on/baseline | baseline p99 us | metrics-on p99 us | "
               "state | gated? (adjusted) | gated? (literal) | reason (adjusted) |")
    add.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for cell in analysis["cell_order"]:
        c = cells[cell]
        for pair in c["pairs"]:
            b4, b5 = pair["b4"], pair["b5"]
            if b4["iops_anomaly"] or b5["iops_anomaly"]:
                state = "slow(IOPS anomaly)"
            elif pair["state_gated_literal"]:
                state = "slow(p99 literal)"
            elif pair["state_gated"]:
                state = "slow(p99 adjusted)"
            else:
                state = "fast"
            add.append("| %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s |"
                       % (cell, pair["rep"], md.fixed(b4["iops"], 0), md.fixed(b5["iops"], 0),
                          md.fixed(pair["ratio"], 4), md.fixed(b4["p99_us"], 1),
                          md.fixed(b5["p99_us"], 1), state,
                          "YES" if pair["state_gated"] else "no",
                          "YES" if pair["state_gated_literal"] else "no",
                          "; ".join(pair["state_reasons"]) or "clean"))
    add.append("")
    add.append("### 1.1 Per-cell state and excluded pairs")
    add.append("")
    add.append("| cell | workers | p99 gate literal us | p99 gate adjusted us | "
               "valid pairs | state-gated reps (adjusted) | non-gated reps < %.2f "
               "(adjusted) | state-gated reps (literal) | non-gated reps < %.2f "
               "(literal) | skipped/missing |" % (REP_RATIO_FLOOR, REP_RATIO_FLOOR))
    add.append("|---|---|---|---|---|---|---|---|---|---|")
    for cell in analysis["cell_order"]:
        c = cells[cell]
        add.append("| %s | %d | %.0f | %.0f | %d | %s | %s | %s | %s | %s |"
                   % (cell, c.get("workers", 1),
                      c["threshold_literal_us"] or FAST_P99_US,
                      c["threshold_cell_adjusted_us"] or FAST_P99_US,
                      c["n_pairs"],
                      ",".join(str(x) for x in c["state_gated_reps"]) or "-",
                      ",".join(str(x) for x in c["rep_floor_violations"]) or "-",
                      ",".join(str(x) for x in c["state_gated_reps_literal"]) or "-",
                      ",".join(str(x) for x in c["rep_floor_violations_literal"]) or "-",
                      "; ".join("r%s: %s" % (s["rep"], s["reason"])
                                for s in c["skipped"]) or "-"))
    add.append("")
    add.append("### 1.2 State-gate labelling variants (same reps, same primary mean bar)")
    add.append("")
    add.append("| cell | p99 threshold literal | p99 threshold cell-adjusted | "
               "gated reps literal | gated reps adjusted | non-gated < %.2f literal | "
               "non-gated < %.2f adjusted | verdict literal | verdict adjusted |"
               % (REP_RATIO_FLOOR, REP_RATIO_FLOOR))
    add.append("|---|---|---|---|---|---|---|---|---|")
    for cell in analysis["cell_order"]:
        c = cells[cell]
        add.append("| %s | %.0f us | %.0f us | %s | %s | %s | %s | %s | %s |"
                   % (cell, c["threshold_literal_us"] or FAST_P99_US,
                      c["threshold_cell_adjusted_us"] or FAST_P99_US,
                      ",".join(str(x) for x in c["state_gated_reps_literal"]) or "-",
                      ",".join(str(x) for x in c["state_gated_reps"]) or "-",
                      ",".join(str(x) for x in c["rep_floor_violations_literal"]) or "-",
                      ",".join(str(x) for x in c["rep_floor_violations"]) or "-",
                      c["verdict_literal"], c["verdict"]))
    add.append("")
    add.append("The cell-adjusted gate is `600 us x total workers`: the methodology's "
               "600 us calibration is for one QD64 ring, while a cell with W workers has "
               "W x 64 requests in flight, so its normal p99 scales with W (the shard-runtime report "
               "measured p99(8)/p99(1) = 5.1-5.9 for every engine, including fio, at "
               "constant QD64/worker). Both labellings keep the IOPS-anomaly rule "
               "(rep < %.2f x the max IOPS of its own cell/arm) and the background-load gate."
               % IOPS_ANOMALY_FACTOR)
    add.append("")
    add.append("## 2. Metrics-on arm configuration")
    add.append("")
    add.append("The metrics-on arm must have metrics on in every invocation; this is what the "
               "measured overhead covers (per-ring counters + dispatch-latency sampling + "
               "one 1 Hz sampler thread). `dispatch_latency_us` is the **runtime "
               "completion latency (registration->dispatch)**: it includes kernel/device "
               "completion time and excludes SQE preparation + the handler body; it is "
               "the multi-shard scaling report's per-ring p99 and is NOT comparable to fio `clat` or the external "
               "`latency_us`. Sampling counts every successful registration (the sampling-count rule).")
    add.append("")
    add.append("| cell | metrics on (metrics-on reps) | total ring_series points per run | per-ring "
               "ring_metrics | dispatch samples (mean) |")
    add.append("|---|---|---|---|---|")
    for cell in analysis["cell_order"]:
        b5_reps = [r for r in records if r["cell"] == cell and r["arm"] == "b5"]
        valid = [r for r in b5_reps if r["valid"]]
        add.append("| %s | %d/%d | %s | %s | %s |"
                   % (cell, sum(1 for r in valid if r["metrics_enabled"]), len(b5_reps),
                      md.fixed(stats.mean([r["series_points"] for r in valid]), 1),
                      "%d/%d" % (sum(1 for r in valid if r["ring_metrics_present"]),
                                 len(valid)),
                      md.fixed(stats.mean([r["dispatch_samples"] for r in valid]), 0)))
    add.append("")
    add.append("## 3. State gate, disclosures, reproducibility")
    add.append("")
    add.append("- **State gate (disclosed, never trimmed):** fast-state p99 thresholds as "
               "in §1.2; IOPS anomaly rule = a rep below %.2f x the maximum IOPS of its "
               "own (cell, arm); background-load gate from cell-meta.tsv (foreign process "
               "> 20 %% CPU; own decaying loadavg residual is recorded as "
               "`own-residual` and is not foreign interference). A pair is state-gated "
               "when either arm is slow-state or gate-flagged; it stays in every mean."
               % IOPS_ANOMALY_FACTOR)
    add.append("- Baseline arm identity: commit `%s`, built by `git archive` into a "
               "scratch tree + `mvn -o -q -DskipTests compile` + its own `javac` build "
               "(workspace never edited for the baseline). Schema 1.0, no metrics."
               % b4_commit)
    add.append("- Metrics-on arm identity: frozen workspace tree (see manifest.txt for the "
               "expected/live hashes), schema 1.1, metrics on.")
    add.append("- Arms are interleaved with the order alternating each rep (rep odd: "
               "baseline->metrics-on; rep even: metrics-on->baseline); every invocation is a fresh process/JVM under "
               "`taskset -c`; identical JVM flags for both arms; 30 s rest between "
               "invocations; every rep kept.")
    add.append("- This session proves the metrics-overhead bar only; **the multi-shard scaling campaign is not "
               "signed off** by it.")
    skipped = [r for r in records if not r["valid"]]
    if skipped:
        add.append("")
        add.append("Invalid / missing invocations (reported, not hidden):")
        for rec in sorted(skipped, key=lambda r: (r["cell"], r["arm"], r["rep"])):
            add.append("- `%s__%s__r%d`: %s (%s)"
                       % (rec["cell"], rec["arm"], rec["rep"],
                          rec["error"] or rec["status"], rec["path"]))
    add.append("")
    add.append("## 4. Per-ring queue depth / p99 evidence (metrics-on arm, schema 1.1; "
               "multi-shard scaling report data source)")
    add.append("")
    per_ring = analysis.get("b5_per_ring") or {}
    if not per_ring:
        add.append("No per-ring metrics data available in this session.")
    else:
        for cell in analysis["cell_order"]:
            rows = per_ring.get(cell)
            if not rows:
                continue
            add.append("### 4.%d `%s` per-ring values (mean over the 5 metrics-on reps; "
                       "queue depth from the 1 Hz `ring_series`, dispatch p99 from "
                       "`dispatch_latency_us.p99`)" % (analysis["cell_order"].index(cell) + 1,
                                                       cell))
            add.append("")
            add.append("| ring | mean in-flight | max in-flight | mean sq_queued | "
                       "mean cq_ready | runtime completion p99 us | mean external p99 us | "
                       "failed | cancelled | series points |")
            add.append("|---|---|---|---|---|---|---|---|---|---|")
            for row in rows:
                add.append("| %s | %s | %s | %s | %s | %s | %s | %d | %d | %s |"
                           % (row["ring"], md.fixed(row["mean_in_flight"], 2),
                              md.fixed(row["max_in_flight"], 2),
                              md.fixed(row["mean_sq_queued"], 2),
                              md.fixed(row["mean_cq_ready"], 2),
                              md.fixed(row["dispatch_p99_us"], 1),
                              md.fixed(row["external_p99_us"], 1),
                              row["failed"], row["cancelled"],
                              row["series_points"] if row["series_points"] else "n/a"))
            add.append("")
        add.append("This is the schema-1.1 evidence for the multi-shard scaling report requirement "
                   "\"per-ring queue depth and p99\": queue depth comes from the "
                   "measured-phase `ring_series`; the p99 is the runtime completion "
                   "latency (registration->dispatch, includes kernel/device completion; "
                   "NOT fio clat / NOT the external `latency_us`). Counters/occupancy "
                   "are best-effort snapshots (ring counters include runtime-internal "
                   "wakeup/cancel ops).")
    add.append("")
    add.append("## 5. Method")
    add.append("")
    add.append("For each cell and rep `i`, `ratio_i = IOPS_metrics-on,i / IOPS_baseline,i` (paired at "
               "the same position). Reported: mean, sample stdev, one-sided 95 %% lower "
               "confidence bound `mean - t_{0.95,n-1} * stdev/sqrt(n)`, min/max. "
               "PASS = mean ratio >= %.2f in every cell AND no non-state-gated ratio < "
               "%.2f. `INCONCLUSIVE` means missing pairs/cells, not a pass. Raw data: "
               "`cells/`, `cell-meta.tsv`, `manifest.txt`; raw aggregates: "
               "`aggregate/overhead.json`."
               % (MEAN_RATIO_BAR, REP_RATIO_FLOOR))
    add.append("")
    return "\n".join(add)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--session", help="session name under bench/results/")
    parser.add_argument("--results-dir", help="explicit results dir (fixtures/testing)")
    parser.add_argument("--out-dir", help="output dir (default <results>/aggregate)")
    parser.add_argument("--baseline-commit", default="6cc1a75",
                        help="baseline commit label for the report (default 6cc1a75)")
    parser.add_argument("--frozen-head", default="")
    parser.add_argument("--frozen-src-sha1", default="")
    parser.add_argument("--frozen-test-sha1", default="")
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
        return 3

    records = load_session(results_dir)
    classify_states(records)
    analysis = analyze(records)
    analysis["b5_per_ring"] = per_ring_evidence(records)
    analysis["session"] = session
    analysis["results_dir"] = results_dir
    analysis["protocol"] = {
        "cells": ["shard1 (shard N=1)", "shard8 (shard N=8)", "threads1 (threads N=1)"],
        "workload": "4K randread, QD 64 per worker, CPUs 1..N",
        "warmup_s": 10, "measured_s": 30, "reps": 5, "rest_s": 30,
        "arms_interleaved": True, "arm_order_alternates_per_rep": True,
        "metric": "paired per-rep throughput ratio metrics-on/baseline",
        "primary_bar": "mean paired ratio >= %.2f in every cell" % MEAN_RATIO_BAR,
        "rep_floor": "no individual rep < %.2f unless state-gated and disclosed"
                     % REP_RATIO_FLOOR,
        "state_gate": "fast p99 < %.0f us + IOPS anomaly rep < %.2f x max (cell,arm) "
                      "+ background-load gate" % (FAST_P99_US, IOPS_ANOMALY_FACTOR),
        "baseline_commit": args.baseline_commit,
        "b5_metrics": "on: ring_metrics + 1 Hz ring_series + withLatencySampleEvery(1) (dispatch_latency_us = runtime completion latency registration->dispatch)",
        "m3_signed_off": False,
    }

    cli.ensure_dir(out_dir)
    json_path = os.path.join(out_dir, "overhead.json")
    md_path = os.path.join(out_dir, "overhead.md")
    payload = {
        "session": session,
        "results_dir": results_dir,
        "verdict": analysis["verdict"],
        "verdict_literal": analysis["verdict_literal"],
        "pass": analysis["pass"],
        "pass_literal": analysis["pass_literal"],
        "labelling": analysis["labelling"],
        "protocol": analysis["protocol"],
        "cells": {
            cell: {
                "n_pairs": c["n_pairs"],
                "reduced_reps": c["reduced_reps"],
                "ratios": c["ratios"],
                "mean_ratio": c["mean_ratio"],
                "sd": c["sd"],
                "se": c["se"],
                "df": c.get("df"),
                "t_crit_one_sided_95": c.get("t_crit_one_sided_95"),
                "ci_lower_one_sided_95": c["ci_lower_one_sided_95"],
                "min_ratio": c["min_ratio"],
                "max_ratio": c["max_ratio"],
                "b4_mean_iops": c["b4_mean_iops"],
                "b5_mean_iops": c["b5_mean_iops"],
                "overhead_pct": c["overhead_pct"],
                "mean_pass": c["mean_pass"],
                "rep_floor_violations": c["rep_floor_violations"],
                "workers": c.get("workers", 1),
                "threshold_literal_us": c.get("threshold_literal_us"),
                "threshold_cell_adjusted_us": c.get("threshold_cell_adjusted_us"),
                "state_gated_reps": c["state_gated_reps"],
                "state_gated_reps_literal": c["state_gated_reps_literal"],
                "state_clean_n": c["state_clean_n"],
                "state_clean_mean_ratio": c["state_clean_mean_ratio"],
                "state_clean_ci_lower_one_sided_95": c["state_clean_ci_lower_one_sided_95"],
                "state_clean_mean_ratio_literal": c["state_clean_mean_ratio_literal"],
                "verdict": c["verdict"],
                "verdict_literal": c["verdict_literal"],
                "pairs": [
                    {
                        "rep": p["rep"],
                        "b4_iops": _cells.r3(p["b4"]["iops"]),
                        "b5_iops": _cells.r3(p["b5"]["iops"]),
                        "ratio": _cells.r3(p["ratio"]),
                        "b4_p99_us": _cells.r3(p["b4"]["p99_us"]),
                        "b5_p99_us": _cells.r3(p["b5"]["p99_us"]),
                        "state_gated": p["state_gated"],
                        "state_reasons": p["state_reasons"],
                        "state_gated_literal": p["state_gated_literal"],
                        "state_reasons_literal": p["state_reasons_literal"],
                        "undisclosed_below_floor": p["undisclosed_below_floor"],
                        "undisclosed_below_floor_literal": p[
                            "undisclosed_below_floor_literal"],
                    }
                    for p in c["pairs"]
                ],
                "skipped": c["skipped"],
            }
            for cell, c in analysis["cells"].items()
        },
        "b5_per_ring": analysis.get("b5_per_ring") or {},
        "invalid_invocations": [
            {"cell": r["cell"], "arm": r["arm"], "rep": r["rep"],
             "status": r["status"], "error": r["error"]}
            for r in records if not r["valid"]
        ],
        "frozen_tree": {
            "head": args.frozen_head or None,
            "src_main_sha1": args.frozen_src_sha1 or None,
            "src_test_sha1": args.frozen_test_sha1 or None,
        },
    }
    cli.write_json(json_path, payload, indent=2)
    cli.write_text(md_path, render_markdown(session, results_dir, analysis, records,
                                            args.baseline_commit, payload["frozen_tree"]))

    print("verdict: %s" % analysis["verdict"])
    print("verdict_literal (flat %.0f us p99 gate): %s"
          % (FAST_P99_US, analysis["verdict_literal"]))
    print("labelling: primary=%s ; literal=%s"
          % (analysis["labelling"]["primary"], analysis["labelling"]["literal"]))
    for cell in analysis["cell_order"]:
        c = analysis["cells"][cell]
        print("  cell %-9s pairs=%d mean_ratio=%s sd=%s ci_lower_1s95=%s "
              "min=%s overhead=%s%% state_gated=%s -> %s"
              % (cell, c["n_pairs"], md.fixed(c["mean_ratio"], 4), md.fixed(c["sd"], 4),
                 md.fixed(c["ci_lower_one_sided_95"], 4), md.fixed(c["min_ratio"], 4),
                 md.fixed(c["overhead_pct"], 2),
                 ",".join(str(x) for x in c["state_gated_reps"]) or "-",
                 c["verdict"]))
    if analysis["skipped_pairs"]:
        print("  skipped pairs (missing/invalid): %d" % analysis["skipped_pairs"])
    for cell in analysis["cell_order"]:
        rows = analysis.get("b5_per_ring", {}).get(cell) or []
        if rows:
            print("  metrics-on per-ring %-9s rings=%d mean_in_flight=%.2f max_in_flight=%s "
                  "runtime_completion_p99_us=%s"
                  % (cell, len(rows),
                     stats.mean([r["mean_in_flight"] for r in rows if r["mean_in_flight"] is not None]),
                     max([r["max_in_flight"] for r in rows if r["max_in_flight"] is not None]),
                     md.fixed(stats.mean([r["dispatch_p99_us"] for r in rows
                               if r["dispatch_p99_us"] is not None]), 1)))
    print("wrote %s" % json_path)
    print("wrote %s" % md_path)
    if analysis["verdict"] == "NO DATA":
        return 3
    if analysis["verdict"].startswith("PASS"):
        return 0
    if analysis["verdict"].startswith("FAIL"):
        return 1
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
