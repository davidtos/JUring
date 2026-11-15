#!/usr/bin/env python3
"""analyze_durability.py -- B6 W24 durability A/B aggregator (docs/b6-design.md §6.3).

Reads the raw per-process JSON emitted by the B6-extended runners

    bench/juring/JUringStreamBench.java        (--durability writeback|fdatasync|fsync|dsync,
                                                --sync-every BYTES)
    bench/seastar/seastar_stream_bench.cc      (--durability writeback|fdatasync|dsync,
                                                --sync-every BYTES)

from ``bench/results/<session>/cells/<cell>__<engine>__<variant>__r<rep>.json`` and writes

    bench/results/<session>/aggregate/durability.json
    bench/results/<session>/aggregate/durability.md

This is the W24 *supporting evidence* analyzer -- not a milestone verdict.  It:

  * requires ``schema_extensions`` to contain ``b6-durability-1`` on every cell JSON and
    fails loudly (exit 3) on a mixed/old session.  It never modifies the M2/M3 artifacts
    and is a separate script from the byte-identical ``analyze_stream.py``;
  * reports per arm (engine x variant): MiB/s mean / sample sd / two-sided 95% CI
    (Student t) / CV%, syncs_issued/s, MiB per sync (bytes_synced/syncs_completed),
    sync_wait_share, durability-point (sync) latency p50/p99, passes and the per-rep
    write-state class;
  * computes the within-rep paired durability-cost ratio per engine
    (durable MiB/s / writeback MiB/s, same rep index) with a 95% CI over reps, plus the
    state-matched subset (both arms of the pair in the same class);
  * applies the frozen state-class rule (derived from M1 §4 + M2 §6, fixed before the
    campaign): a rep is ``fast`` iff MiB/s >= 550 and write p50 <= 250 us, ``slow`` iff
    MiB/s < 300 or write p50 > 600 us, else ``mixed``; the M1 0.92 same-arm rule also
    marks a rep slow (MiB/s < 0.92 x the arm's max).  Classes are computed *within* the
    arm, never across arms;
  * applies the disclosure rules: an arm pair is ranked only if both arms have >= 2 reps
    in the same class (else INCONCLUSIVE); an arm with CV > 10% is "unstable, no ranking"
    (methodology D7); the two writeback controls bracketing the Tier-1 block must agree
    within 10%, otherwise the session is state-confounded and only the raw table is
    reported;
  * keeps every rep (failed/missing reps are listed explicitly, never silently dropped).

Usage:
    python3 bench/stream/analyze_durability.py [--session NAME] [--results DIR] [--out DIR]

Exit codes: 0 = aggregate written; 1 = no cell JSONs found; 2 = usage error;
            3 = schema/contract violation (missing ``b6-durability-1`` extension or
                incompatible cell JSON) -- nothing is written in that case.
"""

import argparse
import datetime
import glob
import json
import math
import os
import sys

SCHEMA_VERSION = "1.0"
REQUIRED_EXTENSION = "b6-durability-1"
DEFAULT_SESSION = "b6-durability-2026-11-15"
CELL_ORDER = ["W24-128k"]

# Frozen state-class thresholds (docs/b6-design.md §6.3; derived from M1 §4 + M2 §6 and
# fixed in this analyzer before the campaign).  Write throughput in MiB/s, write p50 in us.
FAST_MIBS = 550.0
FAST_P50_US = 250.0
SLOW_MIBS = 300.0
SLOW_P50_US = 600.0
M1_RATIO = 0.92           # M1 §4 rule: a rep < 0.92 x same-arm max is slow
CV_UNSTABLE_PCT = 10.0    # methodology D7: CV > 10 % => unstable, no ranking
BRACKET_TOLERANCE_PCT = 10.0

# Two-sided 95% Student-t critical values (df = 1..30).
_T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
         2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
         2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042]

# Engine rows: Tier-1 decisive arms, Tier-2 exploratory arms.
TIER1_ARMS = ("writeback", "fdatasync")           # both engines
TIER2_DURABLE = ("dsync",)                        # both engines (exploratory)
JUring_ONLY = ("fsync",)                          # seastar has no fsync path
CONTROL_END = "writeback-bracket"                 # last-overall JUring writeback control
ALL_ENGINES = ("juring", "seastar")


# ---------------------------------------------------------------------------
# small statistics helpers (stdlib only; same conventions as analyze_stream.py)
# ---------------------------------------------------------------------------

def _mean(values):
    return sum(values) / len(values) if values else None


def _sd(values):
    n = len(values)
    if n < 2:
        return None
    m = _mean(values)
    return math.sqrt(sum((x - m) ** 2 for x in values) / (n - 1))


def _t975(df):
    if df < 1:
        return None
    return _T975[df - 1] if df <= len(_T975) else 1.96


def _ci95(values):
    n = len(values)
    if n < 2:
        return None
    return _t975(n - 1) * _sd(values) / math.sqrt(n)


def _cv_pct(values):
    m = _mean(values)
    if not m or len(values) < 2:
        return None
    return 100.0 * _sd(values) / abs(m)


def _r6(x):
    return None if x is None else round(float(x), 6)


def _fmt(v, digits=1, dash="n/a"):
    if v is None:
        return dash
    return ("%." + str(digits) + "f") % float(v)


def _fmt_signed(v, digits=2, dash="n/a"):
    if v is None:
        return dash
    return ("%+." + str(digits) + "f") % float(v)


# ---------------------------------------------------------------------------
# loading and schema validation
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


def extract(data):
    """Pull the fields this analyzer needs out of one bench JSON object."""
    cfg = data.get("config") or {}
    agg = data.get("aggregate") or {}
    runs = data.get("runs") or []
    run = runs[0] if runs else {}
    run_lat = run.get("latency_us") or {}
    run_sync = run.get("sync_wait_us") or {}

    def pick(*paths, default=None):
        for path in paths:
            cur = data
            for key in path:
                if not isinstance(cur, dict) or key not in cur:
                    cur = None
                    break
                cur = cur[key]
            if cur is not None:
                return cur
        return default

    out = {
        "status": data.get("status"),
        "error": data.get("error"),
        "schema_version": data.get("schema_version"),
        "schema_extensions": data.get("schema_extensions"),
        "engine": data.get("engine"),
        "label": data.get("label"),
        "manifest": data.get("manifest") or {},
        "workload": cfg.get("workload"),
        "bs": cfg.get("bs"),
        "qd": cfg.get("qd"),
        "dataset_bytes": cfg.get("dataset_bytes"),
        "seconds": cfg.get("seconds"),
        "warmup_sec": cfg.get("warmup_sec"),
        "seed": cfg.get("seed"),
        "direct_io": cfg.get("direct_io"),
        "registered_files": cfg.get("registered_files"),
        "config_durability": cfg.get("durability"),
        "sync_every_bytes": cfg.get("sync_every_bytes"),
        "aio_fsync": cfg.get("aio_fsync"),
        "run_durability_mode": run.get("durability_mode", cfg.get("durability")),
        "passes": run.get("passes"),
        "mibs": pick(("aggregate", "mibs", "mean"), default=run.get("mibs")),
        "run_seconds": run.get("seconds"),
        "errors_total": pick(("aggregate", "errors_total"), default=0),
        "syncs_issued": run.get("syncs_issued", 0),
        "syncs_completed": run.get("syncs_completed", 0),
        "syncs_failed": run.get("syncs_failed", 0),
        "durability_points": run.get("durability_points", 0),
        "bytes_synced": run.get("bytes_synced", 0),
        "sync_wait_share": run.get("sync_wait_share", 0.0),
        "chunk_p50_us": run_lat.get("p50"),
        "chunk_p99_us": run_lat.get("p99"),
        "sync_p50_us": run_sync.get("p50"),
        "sync_p99_us": run_sync.get("p99"),
        "sync_samples": run_sync.get("samples", 0),
    }
    return out


def load_session(results_dir):
    """Returns (records, unparsed, schema_errors)."""
    pattern = os.path.join(results_dir, "cells", "*.json")
    records = []
    unparsed = []
    schema_errors = []
    paths = sorted(glob.glob(pattern))
    for path in paths:
        stem = os.path.basename(path)[:-len(".json")]
        parsed = parse_name(stem)
        if parsed is None:
            unparsed.append({"file": path, "reason": "unrecognised cell filename "
                             "(expected <cell>__<engine>__<variant>__r<rep>.json)"})
            continue
        cell, engine, variant, rep = parsed
        rec = {"cell": cell, "engine": engine, "variant": variant, "rep": rep,
               "path": path, "data": None, "error": None, "extracted": None}
        try:
            with open(path, "r", encoding="utf-8") as fh:
                rec["data"] = json.load(fh)
        except Exception as exc:                      # malformed JSON
            rec["error"] = "unreadable/invalid JSON: %s" % exc
            schema_errors.append({"file": path, "reason": rec["error"]})
            continue
        ext = rec["data"].get("schema_extensions")
        if not isinstance(ext, list) or REQUIRED_EXTENSION not in ext:
            schema_errors.append({
                "file": path,
                "reason": "missing schema_extensions [%r]: this is not a B6 durability "
                          "JSON (schema_extensions=%r); refusing to aggregate a "
                          "mixed/old session" % (REQUIRED_EXTENSION, ext)})
            continue
        if rec["data"].get("schema_version") != SCHEMA_VERSION:
            schema_errors.append({"file": path,
                                  "reason": "schema_version %r != %r (the B6 extension "
                                            "must be additive on 1.0)"
                                            % (rec["data"].get("schema_version"),
                                               SCHEMA_VERSION)})
            continue
        rec["extracted"] = extract(rec["data"])
        records.append(rec)
    return records, unparsed, schema_errors


# ---------------------------------------------------------------------------
# state classification (frozen rule, docs/b6-design.md §6.3)
# ---------------------------------------------------------------------------

def classify_rep(mibs, p50_us, arm_max_mibs):
    """Return (class, reasons) for one rep within its own arm."""
    reasons = []
    if mibs is None or p50_us is None:
        return "unknown", ["missing MiB/s or write p50"]
    if mibs >= FAST_MIBS and p50_us <= FAST_P50_US:
        cls = "fast"
    elif mibs < SLOW_MIBS or p50_us > SLOW_P50_US:
        cls = "slow"
        if mibs < SLOW_MIBS:
            reasons.append("MiB/s < %.0f" % SLOW_MIBS)
        if p50_us > SLOW_P50_US:
            reasons.append("p50 > %.0f us" % SLOW_P50_US)
    else:
        cls = "mixed"
    # M1 0.92 same-arm rule also marks a rep slow.
    if arm_max_mibs and mibs < M1_RATIO * arm_max_mibs:
        if cls != "slow":
            reasons.append("MiB/s < %.2f x arm max (M1 rule)" % M1_RATIO)
        cls = "slow"
    return cls, reasons


def arm_key(engine, variant):
    """Logical arm name used in the tables."""
    if variant == CONTROL_END:
        return "%s-writeback-bracket" % engine
    return "%s-%s" % (engine, variant)


def arm_tier(engine, variant):
    if variant in TIER1_ARMS:
        return "tier1"
    if variant in TIER2_DURABLE:
        return "tier2"
    if variant in JUring_ONLY:
        return "tier2-juring-only"
    if variant == CONTROL_END:
        return "control"
    return "other"


# ---------------------------------------------------------------------------
# per-arm aggregation
# ---------------------------------------------------------------------------

def summarize_arm(engine, variant, recs):
    """recs: list of dicts with rep + extracted data (all kept, valid status only)."""
    rows = []
    for rec in recs:
        d = rec["extracted"]
        rows.append({
            "rep": rec["rep"],
            "mibs": float(d["mibs"]) if d.get("mibs") is not None else None,
            "p50_us": d.get("chunk_p50_us"),
            "p99_us": d.get("chunk_p99_us"),
            "sync_p50_us": d.get("sync_p50_us"),
            "sync_p99_us": d.get("sync_p99_us"),
            "passes": d.get("passes"),
            "seconds": d.get("run_seconds"),
            "syncs_issued": int(d.get("syncs_issued") or 0),
            "syncs_completed": int(d.get("syncs_completed") or 0),
            "syncs_failed": int(d.get("syncs_failed") or 0),
            "durability_points": int(d.get("durability_points") or 0),
            "bytes_synced": int(d.get("bytes_synced") or 0),
            "sync_wait_share": float(d.get("sync_wait_share") or 0.0),
            "json": rec["data"],
        })
    mibs_values = [r["mibs"] for r in rows if r["mibs"] is not None]
    arm_max_mibs = max(mibs_values) if mibs_values else None
    for r in rows:
        secs = r["seconds"] if r["seconds"] else None
        r["syncs_per_sec"] = (r["syncs_completed"] / secs) if secs else None
        r["mib_per_sync"] = ((r["bytes_synced"] / (1024.0 * 1024.0)) / r["syncs_completed"]
                             if r["syncs_completed"] > 0 else None)
        r["class"], r["class_reasons"] = classify_rep(r["mibs"], r["p50_us"], arm_max_mibs)
        r["syncs_expected_zero"] = variant in ("writeback", "dsync", CONTROL_END)
    class_counts = {"fast": 0, "mixed": 0, "slow": 0, "unknown": 0}
    for r in rows:
        class_counts[r["class"]] = class_counts.get(r["class"], 0) + 1
    def stats(key):
        vals = [r[key] for r in rows if r.get(key) is not None]
        return {"n": len(vals), "mean": _r6(_mean(vals)), "sd": _r6(_sd(vals)),
                "ci95": _r6(_ci95(vals)), "cv_pct": _r6(_cv_pct(vals)),
                "min": _r6(min(vals)) if vals else None,
                "max": _r6(max(vals)) if vals else None,
                "values_by_rep": {str(r["rep"]): _r6(r.get(key)) for r in rows
                                  if r.get(key) is not None}}
    payload = {
        "engine": engine,
        "variant": variant,
        "arm": arm_key(engine, variant),
        "tier": arm_tier(engine, variant),
        "n": len(rows),
        "mibs": stats("mibs"),
        "chunk_latency_us": {"p50": stats("p50_us"), "p99": stats("p99_us")},
        "sync_latency_us": {"p50": stats("sync_p50_us"), "p99": stats("sync_p99_us")},
        "syncs_issued_per_sec": stats("syncs_per_sec"),
        "mib_per_sync": stats("mib_per_sync"),
        "sync_wait_share": stats("sync_wait_share"),
        "passes": stats("passes"),
        "syncs_total": sum(r["syncs_completed"] for r in rows),
        "syncs_failed_total": sum(r["syncs_failed"] for r in rows),
        "bytes_synced_total": sum(r["bytes_synced"] for r in rows),
        "class_counts": class_counts,
        "cv_pct": _r6(_cv_pct(mibs_values)),
        "unstable": bool(_cv_pct(mibs_values) is not None
                         and _cv_pct(mibs_values) > CV_UNSTABLE_PCT),
        "reps": rows,
    }
    return payload


# ---------------------------------------------------------------------------
# paired durability cost (within rep, same engine)
# ---------------------------------------------------------------------------

def paired_cost(engine, control_arm, durable_arm, expected_reps):
    """Within-rep paired cost of durability: 1 - MiB/s(durable)/MiB/s(writeback)."""
    ctrl = {r["rep"]: r for r in control_arm["reps"]}
    dur = {r["rep"]: r for r in durable_arm["reps"]}
    pairs = {}
    dropped = []
    for rep in sorted(expected_reps):
        if rep in ctrl and rep in dur:
            c_mibs, d_mibs = ctrl[rep]["mibs"], dur[rep]["mibs"]
            if not c_mibs or c_mibs <= 0 or d_mibs is None:
                dropped.append({"rep": rep, "reason": "non-positive control MiB/s"})
                continue
            pairs[rep] = {
                "ratio": d_mibs / c_mibs,
                "cost": 1.0 - (d_mibs / c_mibs),
                "control_mibs": c_mibs,
                "durable_mibs": d_mibs,
                "control_class": ctrl[rep]["class"],
                "durable_class": dur[rep]["class"],
                "state_matched": ctrl[rep]["class"] == dur[rep]["class"],
            }
            continue
        reasons = []
        if rep not in ctrl:
            reasons.append("control rep missing/failed")
        if rep not in dur:
            reasons.append("durable rep missing/failed")
        dropped.append({"rep": rep, "reason": "; ".join(reasons)})
    all_ratios = [pairs[r]["ratio"] for r in sorted(pairs)]
    matched = [pairs[r]["ratio"] for r in sorted(pairs) if pairs[r]["state_matched"]]
    n_all = len(all_ratios)
    n_matched = len(matched)
    ranked = (n_matched >= 2
              and not control_arm["unstable"] and not durable_arm["unstable"])
    if control_arm["unstable"] or durable_arm["unstable"]:
        verdict = "unstable-no-ranking (CV > %.0f%%)" % CV_UNSTABLE_PCT
    elif n_matched < 2:
        verdict = "INCONCLUSIVE (state-matched n=%d < 2)" % n_matched
    else:
        verdict = "ranked (state-matched n=%d)" % n_matched
    return {
        "engine": engine,
        "control_arm": control_arm["arm"],
        "durable_arm": durable_arm["arm"],
        "n_all_pairs": n_all,
        "n_state_matched_pairs": n_matched,
        "pairs_by_rep": {str(r): {"ratio": _r6(pairs[r]["ratio"]),
                                  "cost": _r6(pairs[r]["cost"]),
                                  "control_mibs": _r6(pairs[r]["control_mibs"]),
                                  "durable_mibs": _r6(pairs[r]["durable_mibs"]),
                                  "control_class": pairs[r]["control_class"],
                                  "durable_class": pairs[r]["durable_class"],
                                  "state_matched": pairs[r]["state_matched"]}
                         for r in sorted(pairs)},
        "ratio_mean_all": _r6(_mean(all_ratios)),
        "ratio_ci95_all": _r6(_ci95(all_ratios)),
        "cost_mean_all": _r6(1.0 - _mean(all_ratios)) if all_ratios else None,
        "ratio_mean_state_matched": _r6(_mean(matched)),
        "ratio_ci95_state_matched": _r6(_ci95(matched)),
        "cost_mean_state_matched": _r6(1.0 - _mean(matched)) if matched else None,
        "control_arm_cv_pct": control_arm["cv_pct"],
        "durable_arm_cv_pct": durable_arm["cv_pct"],
        "ranking_allowed": bool(ranked),
        "verdict": verdict,
        "dropped_reps": dropped,
    }


# ---------------------------------------------------------------------------
# markdown rendering
# ---------------------------------------------------------------------------

def render_markdown(session, results_dir, aggregate):
    lines = []
    a = lines.append
    a("# B6 W24 durability A/B -- aggregate (supporting evidence, not a milestone bar)")
    a("")
    a("Session: `%s`  " % session)
    a("Source: `%s`  " % results_dir)
    a("Generated: %s  "
      % datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    a("Contract: `docs/b6-design.md` §6.3. Raw protocol: `%s/manifest.txt`." % results_dir)
    a("")
    protocol = aggregate.get("protocol") or {}
    a("Protocol observed in the cell JSONs: warmup %ss + measured %ss per process, "
      "sync-every %s B, seed %s; the driver protocol (R, 300 s rests, alternating arm "
      "order, writeback bracket control) is recorded in the session manifest."
      % (_fmt(protocol.get("warmup_sec"), 0), _fmt(protocol.get("seconds"), 0),
         protocol.get("sync_every_bytes", "n/a"), protocol.get("seed")))
    a("")
    a("## Tier-1 arms (decisive): per-arm summary (all reps kept)")
    a("")
    a("| engine | arm | n | MiB/s mean | sd | 95% CI ± | CV% | syncs/s | MiB/sync | "
      "sync_wait_share | sync p50 (us) | sync p99 (us) | write p50 (us) | passes | "
      "class f/m/s | unstable |")
    a("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for arm in aggregate["arms"]:
        if arm["tier"] != "tier1":
            continue
        m = arm["mibs"]
        cl = arm["class_counts"]
        a("| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %d/%d/%d | %s |"
          % (arm["engine"], arm["variant"], arm["n"], _fmt(m["mean"]), _fmt(m["sd"], 2),
             _fmt(m["ci95"], 2), _fmt(arm["cv_pct"], 2),
             _fmt(arm["syncs_issued_per_sec"]["mean"]),
             _fmt(arm["mib_per_sync"]["mean"], 3),
             _fmt(arm["sync_wait_share"]["mean"], 3),
             _fmt(arm["sync_latency_us"]["p50"]["mean"]),
             _fmt(arm["sync_latency_us"]["p99"]["mean"]),
             _fmt(arm["chunk_latency_us"]["p50"]["mean"]),
             _fmt(arm["passes"]["mean"]),
             cl.get("fast", 0), cl.get("mixed", 0), cl.get("slow", 0),
             "yes" if arm["unstable"] else "no"))
    a("")
    a("## Tier-2 / exploratory arms")
    a("")
    a("| engine | arm | n | MiB/s mean | CV% | syncs/s | sync_wait_share | sync p50 (us) | "
      "sync p99 (us) | class f/m/s | note |")
    a("|---|---|---|---|---|---|---|---|---|---|---|")
    for arm in aggregate["arms"]:
        if arm["tier"] == "tier1":
            continue
        m = arm["mibs"]
        cl = arm["class_counts"]
        note = "JUring-only (seastar has no fsync(2) path)" \
            if arm["tier"] == "tier2-juring-only" else (
                "exploratory (O_DSYNC per write; no explicit sync)" if arm["variant"] == "dsync"
                else "state-control / bracket")
        a("| %s | %s | %d | %s | %s | %s | %s | %s | %s | %d/%d/%d | %s |"
          % (arm["engine"], arm["variant"], arm["n"], _fmt(m["mean"]),
             _fmt(arm["cv_pct"], 2), _fmt(arm["syncs_issued_per_sec"]["mean"]),
             _fmt(arm["sync_wait_share"]["mean"], 3),
             _fmt(arm["sync_latency_us"]["p50"]["mean"]),
             _fmt(arm["sync_latency_us"]["p99"]["mean"]),
             cl.get("fast", 0), cl.get("mixed", 0), cl.get("slow", 0), note))
    a("")
    a("## Within-rep paired durability cost (durable MiB/s / writeback MiB/s, same rep)")
    a("")
    a("| engine | pair | n all | ratio mean (all) | 95% CI | cost mean (all) | "
      "n state-matched | ratio mean (matched) | cost mean (matched) | ranking |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for engine, p in sorted(aggregate["paired"].items()):
        a("| %s | %s vs %s | %d | %s | %s | %s | %d | %s | %s | %s |"
          % (engine, p["durable_arm"], p["control_arm"], p["n_all_pairs"],
             _fmt(p["ratio_mean_all"], 3), _fmt(p["ratio_ci95_all"], 3),
             _fmt(p["cost_mean_all"], 3), p["n_state_matched_pairs"],
             _fmt(p["ratio_mean_state_matched"], 3),
             _fmt(p["cost_mean_state_matched"], 3), p["verdict"]))
    a("")
    a("Per-rep paired detail (positions and state classes attached; every rep kept):")
    a("")
    a("| engine | rep | control MiB/s | durable MiB/s | ratio | cost % | control class | "
      "durable class | state matched |")
    a("|---|---|---|---|---|---|---|---|---|")
    for engine, p in sorted(aggregate["paired"].items()):
        for rep in sorted(p["pairs_by_rep"], key=lambda x: int(x)):
            pr = p["pairs_by_rep"][rep]
            a("| %s | %s | %s | %s | %s | %s | %s | %s | %s |"
              % (engine, rep, _fmt(pr["control_mibs"]), _fmt(pr["durable_mibs"]),
                 _fmt(pr["ratio"], 3), _fmt(None if pr["cost"] is None else 100.0 * pr["cost"], 1),
                 pr["control_class"], pr["durable_class"],
                 "yes" if pr["state_matched"] else "no"))
    a("")
    a("## Writeback state-control bracket (Tier-1 block)")
    a("")
    br = aggregate["bracket_control"]
    a("| start control (rep 1 JUring-writeback) | end control (last overall) | diff | "
      "within 10% |")
    a("|---|---|---|---|")
    a("| %s MiB/s (%s) | %s MiB/s (%s) | %s %% | %s |"
      % (_fmt(br["start_mibs"]), br.get("start_class", "n/a"),
         _fmt(br["end_mibs"]), br.get("end_class", "n/a"),
         _fmt(br["diff_pct"], 2), "yes" if br["agree_within_10pct"] else "NO"))
    a("")
    a(br["note"])
    a("")
    a("## Data quality (no rep silently dropped)")
    a("")
    bad = aggregate["data_quality"]
    if not bad["excluded"] and not bad["missing"] and not bad["unparsed"]:
        a("Every cell JSON parsed, carried the `b6-durability-1` extension, had status ok "
          "and zero errors; every expected rep was present.")
    else:
        for e in bad["excluded"]:
            a("- EXCLUDED %s / %s / %s rep %s: %s (`%s`)"
              % (e["cell"], e["engine"], e["variant"], e["rep"], e["reason"], e["file"]))
        for e in bad["missing"]:
            a("- MISSING %s / %s / %s rep %s" % (e["cell"], e["engine"], e["variant"], e["rep"]))
        for e in bad["unparsed"]:
            a("- UNPARSED `%s`: %s" % (e["file"], e["reason"]))
    a("")
    a("## Disclosures")
    a("")
    for note in aggregate["disclosures"]:
        a("- %s" % note)
    a("")
    a("State classes are computed **within each arm** (`fast` iff MiB/s >= 550 and write "
      "p50 <= 250 us; `slow` iff MiB/s < 300 or write p50 > 600 us; else `mixed`; the M1 "
      "0.92 same-arm rule also marks a rep slow). Arms are ranked only if both sides have "
      ">= 2 reps in the same class and neither arm has CV > 10% (methodology D7); the "
      "writeback controls bracket the Tier-1 block and must agree within 10%."
      )
    a("")
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(here, "..", ".."))

    ap = argparse.ArgumentParser(
        description="Aggregate B6 W24 durability cells into aggregate/durability.{json,md}.")
    ap.add_argument("--session", default=DEFAULT_SESSION,
                    help="session name under bench/results/ (default %(default)s)")
    ap.add_argument("--results", default=None,
                    help="explicit session directory (default bench/results/<session>)")
    ap.add_argument("--out", default=None,
                    help="output directory (default <session>/aggregate)")
    args = ap.parse_args(argv)

    results_dir = os.path.abspath(args.results) if args.results else os.path.join(
        repo_root, "bench", "results", args.session)
    out_dir = os.path.abspath(args.out) if args.out else os.path.join(results_dir, "aggregate")

    records, unparsed, schema_errors = load_session(results_dir)
    if schema_errors:
        sys.stderr.write("error: B6 durability schema check FAILED for %d cell JSON(s); "
                         "refusing to aggregate a mixed/old session\n" % len(schema_errors))
        for e in schema_errors:
            sys.stderr.write("  - %s: %s\n" % (e["file"], e["reason"]))
        return 3
    if not records:
        sys.stderr.write("error: no cell JSON files found under %s/cells\n" % results_dir)
        return 1

    cells = sorted({r["cell"] for r in records},
                   key=lambda c: (CELL_ORDER.index(c) if c in CELL_ORDER else len(CELL_ORDER), c))
    if len(cells) != 1:
        sys.stderr.write("warning: expected exactly one W24 cell, found %r; aggregating all\n"
                         % (cells,))

    # Arm inventory: one entry per (engine, variant) observed in the session.
    arm_recs = {}
    excluded = []
    for rec in records:
        d = rec["extracted"]
        if rec["data"] is None:
            excluded.append({"cell": rec["cell"], "engine": rec["engine"],
                             "variant": rec["variant"], "rep": rec["rep"],
                             "reason": rec["error"] or "missing data", "file": rec["path"]})
            continue
        if d.get("status") != "ok":
            excluded.append({"cell": rec["cell"], "engine": rec["engine"],
                             "variant": rec["variant"], "rep": rec["rep"],
                             "reason": "status=%s error=%s" % (d.get("status"), d.get("error")),
                             "file": rec["path"]})
            continue
        if d.get("errors_total") not in (None, 0):
            excluded.append({"cell": rec["cell"], "engine": rec["engine"],
                             "variant": rec["variant"], "rep": rec["rep"],
                             "reason": "errors_total=%s" % (d.get("errors_total"),),
                             "file": rec["path"]})
            continue
        if d.get("mibs") is None or float(d["mibs"]) <= 0:
            excluded.append({"cell": rec["cell"], "engine": rec["engine"],
                             "variant": rec["variant"], "rep": rec["rep"],
                             "reason": "non-positive MiB/s", "file": rec["path"]})
            continue
        arm_recs.setdefault((rec["engine"], rec["variant"]), []).append(rec)

    expected_reps = sorted({r["rep"] for r in records})
    arms = []
    for (engine, variant) in sorted(arm_recs):
        arms.append(summarize_arm(engine, variant, arm_recs[(engine, variant)]))

    # Missing reps per observed arm (explicit, never silent).
    missing = []
    for (engine, variant), recs in sorted(arm_recs.items()):
        seen = {r["rep"] for r in recs}
        for rep in expected_reps:
            if rep not in seen:
                missing.append({"cell": cells[0] if cells else "?", "engine": engine,
                                "variant": variant, "rep": rep})

    by_arm = {a["arm"]: a for a in arms}
    paired = {}
    for engine in ALL_ENGINES:
        ctrl = by_arm.get(arm_key(engine, "writeback"))
        dur = by_arm.get(arm_key(engine, "fdatasync"))
        if ctrl and dur:
            paired[engine] = paired_cost(engine, ctrl, dur, expected_reps)

    # Bracket: first-overall JUring writeback (rep 1) vs last-overall bracket control.
    start = None
    if "juring-writeback" in by_arm:
        for r in by_arm["juring-writeback"]["reps"]:
            if r["rep"] == min(expected_reps):
                start = r
                break
    end = None
    if "juring-writeback-bracket" in by_arm:
        end = by_arm["juring-writeback-bracket"]["reps"][0]
    bracket = {"start_mibs": None, "end_mibs": None, "diff_pct": None,
               "agree_within_10pct": False, "start_class": None, "end_class": None,
               "note": "bracket control missing (no %s run)" % CONTROL_END}
    if start and end:
        s_mibs, e_mibs = start["mibs"], end["mibs"]
        diff = abs(s_mibs - e_mibs) / ((s_mibs + e_mibs) / 2.0) * 100.0
        agree = diff <= BRACKET_TOLERANCE_PCT
        bracket = {
            "start_mibs": _r6(s_mibs), "end_mibs": _r6(e_mibs), "diff_pct": _r6(diff),
            "agree_within_10pct": bool(agree),
            "start_class": start["class"], "end_class": end["class"],
            "note": ("The JUring-writeback arm brackets the Tier-1 block (first overall and "
                     "last overall). The two controls agree within %.0f%% - the session write "
                     "state is stable." % BRACKET_TOLERANCE_PCT) if agree else
                    ("The two writeback controls differ by %.1f%% > %.0f%%: the session is "
                     "state-confounded; only the raw per-rep table is reported and no "
                     "durability-cost number may be quoted."
                     % (diff, BRACKET_TOLERANCE_PCT)),
        }
    state_confounded = bool(start and end and not bracket["agree_within_10pct"])

    # Protocol observed (from the JSONs; the driver manifest records the rests/order).
    protocol = {"reps": len(expected_reps),
                "seconds": None, "warmup_sec": None, "sync_every_bytes": None, "seed": None}
    for rec in records:
        d = rec["extracted"]
        for key, out_key in (("seconds", "seconds"), ("warmup_sec", "warmup_sec"),
                             ("sync_every_bytes", "sync_every_bytes"), ("seed", "seed")):
            if protocol[out_key] is None and d.get(key) is not None:
                protocol[out_key] = d[key]

    disclosures = [
        "Supporting evidence only: W24 is not a milestone bar and makes no new "
        "cross-engine write win/loss claim (docs/b6-design.md §6.3/§6.5; methodology D7).",
        "The M2 W21/W22 write comparison issued 2 fdatasync/pass on seastar and 0 on "
        "JUring (undisclosed in M2); the W24 arms match durability on both engines and "
        "run a writeback-vs-writeback control first/last, so the paired ratio here is a "
        "within-engine cost of durability, not an engine ranking.",
        "Durability points include a write-behind drain on both engines (B2 flush() and "
        "seastar fstream flush() both drain); the matched arms isolate the sync cost "
        "(writeback = drain only vs fdatasync = drain + one sync), but the absolute "
        "sync_wait_share is an upper bound on pure sync cost (§9.7).",
        "seastar's pass-end path issues two fdatasync per pass (output_stream flush() + "
        "close()); the JUring pass-end path issues one. With --sync-every 4 MiB this is "
        "<1 % of the durability points and is reported through syncs/s and MiB/sync "
        "instead of being hidden.",
        "seastar has no fsync(2) path (file::flush() is fdatasync only); the juring-fsync "
        "arm is JUring-only and is not compared across engines.",
        "State classes are derived (not pre-registered) from M1 §4 + M2 §6 and fixed in "
        "this analyzer before the campaign: fast iff MiB/s >= 550 and write p50 <= 250 us; "
        "slow iff MiB/s < 300 or write p50 > 600 us; else mixed; plus the M1 0.92 rule.",
        "The W24 dataset is 2 GiB per pass on the existing 10 GiB write file (the file is "
        "never resized): a disclosed deviation from the frozen 10 GiB dataset (§9.8).",
        "No cache drop and no power-loss test: durability is the fdatasync/fsync/O_DSYNC "
        "guarantee, not something W24 measures (§9.1).",
    ]

    aggregate = {
        "schema_version": SCHEMA_VERSION,
        "schema_extensions": [REQUIRED_EXTENSION],
        "kind": "b6-w24-durability-ab",
        "session": args.session,
        "source_dir": results_dir,
        "generated_utc": datetime.datetime.now(datetime.timezone.utc)
        .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "contract": "docs/b6-design.md §6.3 (frozen protocol); analyzer fixed before campaign",
        "protocol": protocol,
        "state_class_rule": {
            "fast": "MiB/s >= %.0f and write p50 <= %.0f us" % (FAST_MIBS, FAST_P50_US),
            "slow": "MiB/s < %.0f or write p50 > %.0f us" % (SLOW_MIBS, SLOW_P50_US),
            "mixed": "otherwise",
            "m1_rule": "also slow if MiB/s < %.2f x same-arm max" % M1_RATIO,
            "computed_within_arm": True,
        },
        "arms": arms,
        "paired": paired,
        "bracket_control": bracket,
        "overall": {
            "state_confounded": state_confounded,
            "ranking": {engine: {
                "allowed": bool(p.get("ranking_allowed")),
                "verdict": p.get("verdict"),
                "quote_cost": bool(p.get("ranking_allowed")) and not state_confounded,
            } for engine, p in sorted(paired.items())},
            "note": ("Supporting evidence only. Rank/quote a durability cost only for an "
                     "engine whose paired ranking is allowed and only when the session is "
                     "not state-confounded."),
        },
        "data_quality": {"excluded": excluded, "missing": missing, "unparsed": unparsed},
        "disclosures": disclosures,
    }

    os.makedirs(out_dir, exist_ok=True)
    json_path = os.path.join(out_dir, "durability.json")
    md_path = os.path.join(out_dir, "durability.md")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(aggregate, fh, indent=2, sort_keys=False)
        fh.write("\n")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(render_markdown(args.session, results_dir, aggregate))
        fh.write("\n")

    print("wrote %s" % json_path)
    print("wrote %s" % md_path)
    for engine, p in sorted(paired.items()):
        print("  paired %-7s durable/control ratio all=%s state-matched=%s (%s)"
              % (engine, _fmt(p["ratio_mean_all"], 3),
                 _fmt(p["ratio_mean_state_matched"], 3), p["verdict"]))
    print("  bracket control: start=%s end=%s diff=%s%% agree=%s"
          % (_fmt(bracket["start_mibs"]), _fmt(bracket["end_mibs"]),
             _fmt(bracket["diff_pct"], 2), bracket["agree_within_10pct"]))
    if excluded or missing or unparsed:
        print("  data quality: excluded=%d missing=%d unparsed=%d (see aggregate/durability.md)"
              % (len(excluded), len(missing), len(unparsed)))
    if state_confounded:
        print("  WARNING: session state-confounded (bracket controls differ > %.0f%%); "
              "no durability-cost number may be quoted." % BRACKET_TOLERANCE_PCT)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
