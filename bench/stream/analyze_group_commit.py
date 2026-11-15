#!/usr/bin/env python3
"""analyze_group_commit.py -- B6 W25 group-commit micro-bench aggregator
(docs/b6-design.md §6.4 + A2 rest adjudication).

Reads the raw JSON of the JUring-only group-commit runner

    bench/juring/JUringDurabilityBench.java

from ``bench/results/<session>/cells/<cell>__juring__groupcommit__r<rep>.json`` and writes

    bench/results/<session>/aggregate/group-commit.json
    bench/results/<session>/aggregate/group-commit.md

The analyzer enforces the machine-checkable W25 acceptance identities and fails loudly
(exit 3) if any cell JSON violates them:

  * ``fsyncs_per_group == 1.0`` (successful FDATASYNC/FSYNC batch => exactly one sync);
  * ``groups_total * batch_size == committed_writes`` (records-mode short last group is
    accepted only if ``committed_writes == records`` and the shortfall is < one batch);
  * ``syncs_completed == syncs_issued == groups_total``, ``syncs_failed == 0``;
  * ``restart_verify.ok == true`` from the fresh-process re-read (process-restart
    visibility, NOT a power-loss test).

It requires ``schema_extensions`` to contain ``b6-durability-1`` and does not touch the
M2/M3 artifacts or ``analyze_stream.py``.

Per cell (batch size B) it reports: MiB/s mean/sd/95% CI/CV, writes/s, groups/s,
group-latency p50/p99 (ms), sync-latency p50/p99, syncs/s, MiB/sync, sync_wait_share,
fsyncs_per_group, wrap-arounds and the identity checks.  Every rep is kept and any
failed/missing rep is listed explicitly.

Usage:
    python3 bench/stream/analyze_group_commit.py [--session NAME] [--results DIR] [--out DIR]

Exit codes: 0 = aggregate written and all identities hold; 1 = no cell JSONs found;
            2 = usage error; 3 = schema/contract violation (aggregate still written with
                the violations listed, but the session is NOT valid W25 evidence).
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
DEFAULT_SESSION = "b6-group-commit-2026-11-15"
CELL_ORDER = ["W25-4k-b1", "W25-4k-b8", "W25-4k-b32", "W25-4k-b128"]
FPG_TOLERANCE = 1e-9

_T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
         2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
         2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042]


def _mean(values):
    return sum(values) / len(values) if values else None


def _sd(values):
    n = len(values)
    if n < 2:
        return None
    m = _mean(values)
    return math.sqrt(sum((x - m) ** 2 for x in values) / (n - 1))


def _ci95(values):
    n = len(values)
    if n < 2:
        return None
    df = n - 1
    t = _T975[df - 1] if df <= len(_T975) else 1.96
    return t * _sd(values) / math.sqrt(n)


def _cv_pct(values):
    m = _mean(values)
    if not m or len(values) < 2:
        return None
    return 100.0 * _sd(values) / abs(m)


def _r6(x):
    return None if x is None else round(float(x), 6)


def _fmt(v, digits=2, dash="n/a"):
    if v is None:
        return dash
    return ("%." + str(digits) + "f") % float(v)


def parse_name(stem):
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
    cfg = data.get("config") or {}
    agg = data.get("aggregate") or {}
    runs = data.get("runs") or []
    run = runs[0] if runs else {}
    group_lat = run.get("group_latency_us") or {}
    sync_lat = run.get("sync_latency_us") or {}
    verify = run.get("restart_verify") or {}
    return {
        "status": data.get("status"),
        "error": data.get("error"),
        "schema_version": data.get("schema_version"),
        "schema_extensions": data.get("schema_extensions"),
        "engine": data.get("engine"),
        "label": data.get("label"),
        "manifest": data.get("manifest") or {},
        "file": cfg.get("file"),
        "bs": cfg.get("bs"),
        "batch_size": cfg.get("batch_size", run.get("batch_size")),
        "durability": cfg.get("durability"),
        "seconds": cfg.get("seconds"),
        "warmup_sec": cfg.get("warmup_sec"),
        "runs": cfg.get("runs"),
        "seed": cfg.get("seed"),
        "run_seconds": run.get("seconds"),
        "durability_mode": run.get("durability_mode"),
        "groups_total": int(run.get("groups_total") or 0),
        "committed_writes": int(run.get("committed_writes") or 0),
        "committed_bytes": int(run.get("committed_bytes") or 0),
        "max_observed_batch": run.get("max_observed_batch"),
        "writes_per_group": run.get("writes_per_group") or {},
        "syncs_issued": int(run.get("syncs_issued") or 0),
        "syncs_completed": int(run.get("syncs_completed") or 0),
        "syncs_failed": int(run.get("syncs_failed") or 0),
        "fsyncs_per_group": run.get("fsyncs_per_group"),
        "bytes_synced": int(run.get("bytes_synced") or 0),
        "syncs_per_sec": run.get("syncs_per_sec"),
        "mib_per_sync": run.get("mib_per_sync"),
        "sync_wait_share": run.get("sync_wait_share"),
        "sync_p50_us": sync_lat.get("p50"),
        "sync_p99_us": sync_lat.get("p99"),
        "group_p50_us": group_lat.get("p50"),
        "group_p99_us": group_lat.get("p99"),
        "p99_group_latency_us": run.get("p99_group_latency_us"),
        "mibs": (agg.get("mibs") or {}).get("mean", run.get("mibs")),
        "writes_per_sec": run.get("writes_per_sec"),
        "wrap_arounds": run.get("wrap_arounds"),
        "restart_verify": verify,
        "errors_total": (agg.get("errors_total")
                         if agg.get("errors_total") is not None else run.get("errors_total")),
    }


def load_session(results_dir):
    records, unparsed, schema_errors = [], [], []
    for path in sorted(glob.glob(os.path.join(results_dir, "cells", "*.json"))):
        stem = os.path.basename(path)[:-len(".json")]
        parsed = parse_name(stem)
        if parsed is None:
            unparsed.append({"file": path, "reason": "unrecognised cell filename "
                             "(expected <cell>__juring__groupcommit__r<rep>.json)"})
            continue
        cell, engine, variant, rep = parsed
        rec = {"cell": cell, "engine": engine, "variant": variant, "rep": rep,
               "path": path, "data": None, "error": None, "extracted": None}
        try:
            with open(path, "r", encoding="utf-8") as fh:
                rec["data"] = json.load(fh)
        except Exception as exc:
            rec["error"] = "unreadable/invalid JSON: %s" % exc
            schema_errors.append({"file": path, "reason": rec["error"]})
            continue
        ext = rec["data"].get("schema_extensions")
        if not isinstance(ext, list) or REQUIRED_EXTENSION not in ext:
            schema_errors.append({
                "file": path,
                "reason": "missing schema_extensions [%r] (schema_extensions=%r); refusing "
                          "to aggregate a mixed/old session" % (REQUIRED_EXTENSION, ext)})
            continue
        if rec["data"].get("schema_version") != SCHEMA_VERSION:
            schema_errors.append({"file": path, "reason": "schema_version %r != %r"
                                  % (rec["data"].get("schema_version"), SCHEMA_VERSION)})
            continue
        rec["extracted"] = extract(rec["data"])
        records.append(rec)
    return records, unparsed, schema_errors


def check_identities(rec):
    """Return a list of human-readable violations for one W25 rep (empty = pass)."""
    d = rec["extracted"]
    if rec["data"] is None:
        return [rec["error"] or "missing data"]
    v = []
    if d["status"] != "ok":
        v.append("status=%s error=%s" % (d["status"], d["error"]))
    batch = int(d["batch_size"] or 0)
    groups = d["groups_total"]
    writes = d["committed_writes"]
    fpg = d["fsyncs_per_group"]
    if batch <= 0:
        v.append("batch_size=%r" % (d["batch_size"],))
    if groups <= 0:
        v.append("groups_total=0")
    if d["durability_mode"] != "fdatasync" and d["durability"] != "fdatasync":
        v.append("durability_mode=%r (W25 cells are fdatasync)" % (d["durability_mode"],))
    if fpg is None or abs(float(fpg) - 1.0) > FPG_TOLERANCE:
        v.append("fsyncs_per_group=%r != 1.0" % (fpg,))
    if d["syncs_issued"] != groups:
        v.append("syncs_issued=%d != groups_total=%d" % (d["syncs_issued"], groups))
    if d["syncs_completed"] != groups:
        v.append("syncs_completed=%d != groups_total=%d" % (d["syncs_completed"], groups))
    if d["syncs_failed"] != 0:
        v.append("syncs_failed=%d" % d["syncs_failed"])
    expected = groups * batch
    if writes != expected:
        # records-mode short last group is allowed only if writes == records and the
        # shortfall is smaller than one batch.
        records = (rec["data"].get("config") or {}).get("records")
        if not (records and writes == records and 0 <= expected - writes < batch):
            v.append("committed_writes=%d != groups_total*batch_size=%d (and not a valid "
                     "short last group)" % (writes, expected))
    if d["errors_total"] not in (None, 0):
        v.append("errors_total=%r" % (d["errors_total"],))
    verify = d["restart_verify"] or {}
    if verify.get("ok") is not True:
        v.append("restart_verify.ok=%r (fresh-process re-read failed; first_mismatch=%r)"
                 % (verify.get("ok"), verify.get("first_mismatch_offset")))
    return v


def stats_block(values):
    vals = [v for v in values if v is not None]
    return {"n": len(vals), "mean": _r6(_mean(vals)), "sd": _r6(_sd(vals)),
            "ci95": _r6(_ci95(vals)), "cv_pct": _r6(_cv_pct(vals)),
            "min": _r6(min(vals)) if vals else None,
            "max": _r6(max(vals)) if vals else None}


def analyze_cell(cell, recs):
    rows = []
    violations = []
    for rec in recs:
        d = rec["extracted"]
        problems = check_identities(rec)
        if problems:
            violations.append({"file": rec["path"], "rep": rec["rep"],
                               "problems": problems})
            continue
        rows.append({
            "rep": rec["rep"],
            "mibs": d["mibs"],
            "writes_per_sec": d["writes_per_sec"],
            "groups_total": d["groups_total"],
            "syncs_per_sec": d["syncs_per_sec"],
            "mib_per_sync": d["mib_per_sync"],
            "sync_wait_share": d["sync_wait_share"],
            "fsyncs_per_group": d["fsyncs_per_group"],
            "group_p50_ms": (d["group_p50_us"] / 1000.0
                             if d["group_p50_us"] is not None else None),
            "group_p99_ms": (d["group_p99_us"] / 1000.0
                             if d["group_p99_us"] is not None else None),
            "sync_p50_us": d["sync_p50_us"],
            "sync_p99_us": d["sync_p99_us"],
            "verified_bytes": (d["restart_verify"] or {}).get("verified_bytes"),
            "wrap_arounds": d["wrap_arounds"],
            "batch_size": d["batch_size"],
            "committed_writes": d["committed_writes"],
            "committed_bytes": d["committed_bytes"],
            "run_seconds": d["run_seconds"],
            "file": d["file"],
            "seconds": d["seconds"],
            "warmup_sec": d["warmup_sec"],
            "durability": d["durability"],
            "json": rec["data"],
        })
    rows.sort(key=lambda r: r["rep"])
    batch0 = recs[0]["extracted"]["batch_size"] if recs else None
    mibs = stats_block([r["mibs"] for r in rows])
    fsync_vals = [r["fsyncs_per_group"] for r in rows]
    return {
        "cell": cell,
        "batch_size": rows[0]["batch_size"] if rows else batch0,
        "n": len(rows),
        "mibs": mibs,
        "writes_per_sec": stats_block([r["writes_per_sec"] for r in rows]),
        "groups_per_sec": stats_block([(r["groups_total"] / r["run_seconds"]
                                        if r["run_seconds"] else None) for r in rows]),
        "group_latency_ms": {"p50": stats_block([r["group_p50_ms"] for r in rows]),
                             "p99": stats_block([r["group_p99_ms"] for r in rows])},
        "sync_latency_us": {"p50": stats_block([r["sync_p50_us"] for r in rows]),
                            "p99": stats_block([r["sync_p99_us"] for r in rows])},
        "syncs_per_sec": stats_block([r["syncs_per_sec"] for r in rows]),
        "mib_per_sync": stats_block([r["mib_per_sync"] for r in rows]),
        "sync_wait_share": stats_block([r["sync_wait_share"] for r in rows]),
        "fsyncs_per_group": stats_block(fsync_vals),
        "fsyncs_per_group_ok": bool(fsync_vals) and all(
            abs(float(v) - 1.0) <= FPG_TOLERANCE for v in fsync_vals),
        "identity_groups_batch_writes": bool(rows) and all(
            r["committed_writes"] == r["groups_total"] * r["batch_size"] for r in rows),
        "identity_syncs_groups": bool(rows) and all(
            r["fsyncs_per_group"] is not None for r in rows),
        "restart_verify_ok": bool(rows) and all(
            (r["json"].get("runs") or [{}])[0].get("restart_verify", {}).get("ok") is True
            for r in rows),
        "verified_bytes_total": sum(int(r["verified_bytes"] or 0) for r in rows),
        "wrap_arounds_total": sum(int(r["wrap_arounds"] or 0) for r in rows),
        "committed_bytes_total": sum(int(r["committed_bytes"] or 0) for r in rows),
        "violations": violations,
        "reps": rows,
    }


def render_markdown(session, results_dir, aggregate):
    lines = []
    a = lines.append
    a("# B6 W25 group-commit micro-benchmark -- aggregate (JUring-only)")
    a("")
    a("Session: `%s`  " % session)
    a("Source: `%s`  " % results_dir)
    a("Generated: %s  "
      % datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    a("Contract: `docs/b6-design.md` §6.4 + A2 rest adjudication. "
      "Raw protocol: `%s/manifest.txt`." % results_dir)
    a("")
    p = aggregate["protocol"]
    a("Protocol observed in the cell JSONs: warmup %ss + measured %ss, bs %s B, "
      "durability %s, seed %s; R=%s."
      % (_fmt(p.get("warmup_sec"), 0), _fmt(p.get("seconds"), 0), p.get("bs"),
         p.get("durability"), p.get("seed"), p.get("reps")))
    a("")
    a("## Contract identities (machine-checkable acceptance)")
    a("")
    a("| cell | batch | n | fsyncs_per_group mean | == 1.0 | "
      "groups*batch == committed_writes | syncs_failed | restart_verify.ok | "
      "wrap-arounds |")
    a("|---|---|---|---|---|---|---|---|---|")
    for c in aggregate["cells"]:
        f = c["fsyncs_per_group"]
        a("| %s | %s | %d | %s | %s | %s | 0 | %s | %d |"
          % (c["cell"], c["batch_size"], c["n"], _fmt(f["mean"], 6),
             "PASS" if c["fsyncs_per_group_ok"] else "FAIL",
             "PASS" if c["identity_groups_batch_writes"] else "FAIL",
             "PASS" if c["restart_verify_ok"] else "FAIL",
             c["wrap_arounds_total"]))
    a("")
    a(aggregate["identity_verdict"])
    a("")
    a("## Cost / latency per cell (all reps kept)")
    a("")
    a("| cell | batch | n | groups/s | writes/s | MiB/s mean | sd | 95% CI ± | CV% | "
      "group p50 (ms) | group p99 (ms) | sync p50 (us) | sync p99 (us) | syncs/s | "
      "MiB/sync | sync_wait_share | fsyncs/group |")
    a("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for c in aggregate["cells"]:
        m = c["mibs"]
        f = c["fsyncs_per_group"]
        a("| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |"
          % (c["cell"], c["batch_size"], c["n"],
             _fmt(c["groups_per_sec"]["mean"]), _fmt(c["writes_per_sec"]["mean"], 0),
             _fmt(m["mean"], 1), _fmt(m["sd"], 2), _fmt(m["ci95"], 2),
             _fmt(m["cv_pct"], 2), _fmt(c["group_latency_ms"]["p50"]["mean"], 3),
             _fmt(c["group_latency_ms"]["p99"]["mean"], 3),
             _fmt(c["sync_latency_us"]["p50"]["mean"], 1),
             _fmt(c["sync_latency_us"]["p99"]["mean"], 1),
             _fmt(c["syncs_per_sec"]["mean"], 1), _fmt(c["mib_per_sync"]["mean"], 3),
             _fmt(c["sync_wait_share"]["mean"], 3), _fmt(f["mean"], 6)))
    a("")
    a("Per-rep detail (exactly one fdatasync per committed batch; the fresh-process "
      "re-read verifies the deterministic pattern):")
    a("")
    a("| cell | rep | groups | committed writes | MiB/s | group p50 (ms) | group p99 (ms) | "
      "sync p50 (us) | fsyncs/group | verified bytes | wrap-arounds |")
    a("|---|---|---|---|---|---|---|---|---|---|---|")
    for c in aggregate["cells"]:
        for r in c["reps"]:
            a("| %s | %d | %d | %d | %s | %s | %s | %s | %s | %s | %s |"
              % (c["cell"], r["rep"], r["groups_total"], r["committed_writes"],
                 _fmt(r["mibs"], 1), _fmt(r["group_p50_ms"], 3), _fmt(r["group_p99_ms"], 3),
                 _fmt(r["sync_p50_us"], 1), _fmt(r["fsyncs_per_group"], 6),
                 r["verified_bytes"], r["wrap_arounds"]))
    a("")
    a("## Data quality / contract violations (no rep silently dropped)")
    a("")
    bad = aggregate["data_quality"]
    if not bad["excluded"] and not bad["missing"] and not bad["unparsed"] \
            and not aggregate["violations"]:
        a("Every cell JSON parsed, carried the `b6-durability-1` extension, satisfied the "
          "W25 identities and passed the fresh-process restart check.")
    else:
        for e in bad["excluded"]:
            a("- EXCLUDED %s rep %s: %s (`%s`)"
              % (e["cell"], e["rep"], e["reason"], e["file"]))
        for m in bad["missing"]:
            a("- MISSING %s rep %s (no JSON)" % (m["cell"], m["rep"]))
        for u in bad["unparsed"]:
            a("- UNPARSED `%s`: %s" % (u["file"], u["reason"]))
        for v in aggregate["violations"]:
            a("- CONTRACT VIOLATION `%s` rep %s: %s"
              % (v["file"], v["rep"], "; ".join(v["problems"])))
    a("")
    a("## Disclosures")
    a("")
    for note in aggregate["disclosures"]:
        a("- %s" % note)
    a("")
    return "\n".join(lines)


def main(argv):
    here = os.path.dirname(os.path.abspath(__file__))
    repo_root = os.path.abspath(os.path.join(here, "..", ".."))
    ap = argparse.ArgumentParser(
        description="Aggregate B6 W25 group-commit cells into aggregate/group-commit.{json,md}.")
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
    cells_out = []
    excluded = []
    missing = []
    violations = []
    expected_reps = sorted({r["rep"] for r in records})
    for cell in cells:
        recs = [r for r in records if r["cell"] == cell]
        readable = {r["rep"] for r in recs if r["data"] is not None}
        for rep in expected_reps:
            if rep not in readable:
                missing.append({"cell": cell, "rep": rep})
        result = analyze_cell(cell, recs)
        violations.extend(result.pop("violations"))
        cells_out.append(result)

    # Protocol observed.
    protocol = {"reps": len(expected_reps), "seconds": None, "warmup_sec": None,
                "bs": None, "durability": None, "seed": None}
    for rec in records:
        d = rec["extracted"]
        for key in ("seconds", "warmup_sec", "bs", "durability", "seed"):
            if protocol[key] is None and d.get(key) is not None:
                protocol[key] = d[key]

    identities_ok = (not violations and not missing
                     and all(c["fsyncs_per_group_ok"] and c["identity_groups_batch_writes"]
                             and c["restart_verify_ok"] for c in cells_out))
    identity_verdict = (
        "All identities hold: every measured batch issued exactly one fdatasync "
        "(`fsyncs_per_group == 1.0`), `groups_total * batch_size == committed_writes`, "
        "and every fresh-process re-read verified the deterministic pattern.")
    if not identities_ok:
        identity_verdict = (
            "IDENTITY/SCHEMA VIOLATIONS PRESENT - this session is NOT valid W25 evidence; "
            "see the violations below and aggregate/group-commit.json.")

    disclosures = [
        "W25 is JUring-only: seastar has no group-commit primitive. The nearest seastar "
        "equivalent is the W24 seastar-fdatasync arm (one fstream flush per batch, which "
        "also drains write-behind); that is an upper bound on the cost, not a matched "
        "comparison (docs/b6-design.md §6.4/§9.7).",
        "'One fdatasync per batch' is proven by GroupCommitStats counters + the JSON "
        "identity (fsyncs_per_group == 1.0), not by a syscall trace; 'visibility after "
        "restart' is a fresh-process re-read of the deterministic pattern "
        "(process-restart visibility, NOT a power-loss test; §9.1).",
        "sync_latency_us is the commit() call duration and is therefore an UPPER BOUND on "
        "the sync-only cost (it includes waiting for the batch writes; the public API "
        "exposes no sync-only hook).",
        "The W25 file is preallocated with setLength (sparse) and offsets wrap modulo the "
        "file capacity; the pattern is position-indexed, so the verified prefix is "
        "deterministic across wraps (wrap_arounds reported).",
        "Rests (A2 adjudication): 5 s pause between reps inside a cell, 120 s idle rest "
        "between cells - this is a count/latency micro-benchmark, not a throughput "
        "campaign.",
        "Configuration/durability: durability fdatasync (never fsync), bs 4096 B, "
        "cells b1/b8/b32/b128; the W25 measured window is 15 s after a 5 s warmup.",
    ]

    aggregate = {
        "schema_version": SCHEMA_VERSION,
        "schema_extensions": [REQUIRED_EXTENSION],
        "kind": "b6-w25-group-commit",
        "session": args.session,
        "source_dir": results_dir,
        "generated_utc": datetime.datetime.now(datetime.timezone.utc)
        .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "contract": "docs/b6-design.md §6.4 (frozen) + A2 rest adjudication",
        "protocol": protocol,
        "identity_verdict": identity_verdict,
        "identities_ok": bool(identities_ok),
        "cells": cells_out,
        "violations": violations,
        "data_quality": {"excluded": excluded, "missing": missing, "unparsed": unparsed},
        "disclosures": disclosures,
    }

    os.makedirs(out_dir, exist_ok=True)
    json_path = os.path.join(out_dir, "group-commit.json")
    md_path = os.path.join(out_dir, "group-commit.md")
    with open(json_path, "w", encoding="utf-8") as fh:
        json.dump(aggregate, fh, indent=2, sort_keys=False)
        fh.write("\n")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write(render_markdown(args.session, results_dir, aggregate))
        fh.write("\n")

    print("wrote %s" % json_path)
    print("wrote %s" % md_path)
    for c in cells_out:
        print("  %-12s batch=%-4s n=%d MiB/s=%s group_p99=%s ms syncs/group=%s verify=%s"
              % (c["cell"], c["batch_size"], c["n"], _fmt(c["mibs"]["mean"], 1),
                 _fmt(c["group_latency_ms"]["p99"]["mean"], 2),
                 _fmt(c["fsyncs_per_group"]["mean"], 6),
                 "ok" if c["restart_verify_ok"] else "FAIL"))
    if violations:
        print("  CONTRACT VIOLATIONS: %d (see aggregate/group-commit.md/json)"
              % len(violations))
        for v in violations:
            print("    - %s rep %s: %s" % (v["file"], v["rep"], "; ".join(v["problems"])))
    if missing:
        print("  missing reps: %d (see aggregate/group-commit.md)" % len(missing))
    return 0 if identities_ok else 3


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
