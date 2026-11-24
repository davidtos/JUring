#!/usr/bin/env python3
"""Group-commit micro-benchmark aggregator (JUring-only).

Reads the JSON written by the JUring group-commit runner from
``<session>/cells/<cell>__juring__groupcommit__r<rep>.json`` and writes
``<session>/aggregate/group-commit.{json,md}``: per-cell cost/latency statistics
and the machine-checkable groupcommit acceptance identities (one fdatasync per batch,
``groups_total * batch_size == committed_writes``, sync/group counters, and the
fresh-process restart re-read).  Every rep is kept; failed/missing reps and
contract violations are listed explicitly.

Usage:
    python3 bench/stream/analyze_group_commit.py [--session NAME] [--results DIR] [--out DIR]

Exit codes: 0 = aggregate written and all identities hold; 1 = no cell JSONs found;
            2 = usage error; 3 = schema/contract violation (aggregate still written with
                the violations listed, but the session is NOT valid groupcommit evidence).
"""

import argparse
import datetime
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from _cells import load_session, sort_cells  # noqa: E402
from analysis import cells as cellmap  # noqa: E402
from analysis import cli, jsonio, md, stats  # noqa: E402

SCHEMA_VERSION = "1.0"
REQUIRED_EXTENSION = "b6-durability-1"
DEFAULT_SESSION = "b6-group-commit-2026-11-15"
CELL_ORDER = ["groupcommit-4k-batch1", "groupcommit-4k-batch8",
              "groupcommit-4k-batch32", "groupcommit-4k-batch128"]
FPG_TOLERANCE = 1e-9


def _r6(x):
    return None if x is None else round(float(x), 6)


def check_identities(rec):
    """Return a list of human-readable violations for one groupcommit rep (empty = pass)."""
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
        v.append("durability_mode=%r (groupcommit cells are fdatasync)" % (d["durability_mode"],))
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
    return {"n": len(vals), "mean": _r6(stats.mean(vals)), "sd": _r6(stats.sd(vals)),
            "ci95": _r6(stats.ci95(vals)), "cv_pct": _r6(stats.cv_pct(vals)),
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
    a("# Group-commit micro-benchmark -- aggregate (JUring-only)")
    a("")
    a("Session: `%s`  " % session)
    a("Source: `%s`  " % results_dir)
    a("Generated: %s  "
      % datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    a("Contract: frozen before the campaign (rest times recorded in the manifest). "
      "Raw protocol: `%s/manifest.txt`." % results_dir)
    a("")
    p = aggregate["protocol"]
    a("Protocol observed in the cell JSONs: warmup %ss + measured %ss, bs %s B, "
      "durability %s, seed %s; R=%s."
      % (md.fixed(p.get("warmup_sec"), 0), md.fixed(p.get("seconds"), 0), p.get("bs"),
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
          % (cellmap.label(c["cell"]), c["batch_size"], c["n"], md.fixed(f["mean"], 6),
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
          % (cellmap.label(c["cell"]), c["batch_size"], c["n"],
             md.fixed(c["groups_per_sec"]["mean"], 2), md.fixed(c["writes_per_sec"]["mean"], 0),
             md.fixed(m["mean"], 1), md.fixed(m["sd"], 2), md.fixed(m["ci95"], 2),
             md.fixed(m["cv_pct"], 2), md.fixed(c["group_latency_ms"]["p50"]["mean"], 3),
             md.fixed(c["group_latency_ms"]["p99"]["mean"], 3),
             md.fixed(c["sync_latency_us"]["p50"]["mean"], 1),
             md.fixed(c["sync_latency_us"]["p99"]["mean"], 1),
             md.fixed(c["syncs_per_sec"]["mean"], 1), md.fixed(c["mib_per_sync"]["mean"], 3),
             md.fixed(c["sync_wait_share"]["mean"], 3), md.fixed(f["mean"], 6)))
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
              % (cellmap.label(c["cell"]), r["rep"], r["groups_total"], r["committed_writes"],
                 md.fixed(r["mibs"], 1), md.fixed(r["group_p50_ms"], 3),
                 md.fixed(r["group_p99_ms"], 3),
                 md.fixed(r["sync_p50_us"], 1), md.fixed(r["fsyncs_per_group"], 6),
                 r["verified_bytes"], r["wrap_arounds"]))
    a("")
    a("## Data quality / contract violations (no rep silently dropped)")
    a("")
    bad = aggregate["data_quality"]
    if not bad["excluded"] and not bad["missing"] and not bad["unparsed"] \
            and not aggregate["violations"]:
        a("Every cell JSON parsed, carried the `b6-durability-1` extension, satisfied the "
          "groupcommit identities and passed the fresh-process restart check.")
    else:
        for e in bad["excluded"]:
            a("- EXCLUDED %s rep %s: %s (`%s`)"
              % (cellmap.label(e["cell"]), e["rep"], e["reason"], e["file"]))
        for m in bad["missing"]:
            a("- MISSING %s rep %s (no JSON)" % (cellmap.label(m["cell"]), m["rep"]))
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
    ap = argparse.ArgumentParser(
        description="Aggregate group-commit cells into aggregate/group-commit.{json,md}.")
    ap.add_argument("--session", default=DEFAULT_SESSION,
                    help="session name under bench/results/ (default %(default)s)")
    ap.add_argument("--results", default=None,
                    help="explicit session directory (default bench/results/<session>)")
    ap.add_argument("--out", default=None,
                    help="output directory (default <session>/aggregate)")
    args = ap.parse_args(argv)

    results_dir = os.path.abspath(args.results) if args.results else os.path.join(
        jsonio.REPO_ROOT, "bench", "results", args.session)
    out_dir = os.path.abspath(args.out) if args.out else os.path.join(results_dir, "aggregate")

    records, unparsed, schema_errors = load_session(
        results_dir,
        expected_hint="<cell>__juring__groupcommit__r<rep>.json",
        schema_version=SCHEMA_VERSION, required_extension=REQUIRED_EXTENSION)
    if schema_errors:
        sys.stderr.write("error: durability schema check FAILED for %d cell JSON(s); "
                         "refusing to aggregate a mixed/old session\n" % len(schema_errors))
        for e in schema_errors:
            sys.stderr.write("  - %s: %s\n" % (e["file"], e["reason"]))
        return 3
    if not records:
        sys.stderr.write("error: no cell JSON files found under %s/cells\n" % results_dir)
        return 1

    cells = sort_cells({r["cell"] for r in records}, CELL_ORDER)
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
            "IDENTITY/SCHEMA VIOLATIONS PRESENT - this session is NOT valid groupcommit evidence; "
            "see the violations below and aggregate/group-commit.json.")

    disclosures = [
        "groupcommit is JUring-only: seastar has no group-commit primitive. The nearest seastar "
        "equivalent is the durability-write-128k seastar-fdatasync arm (one fstream flush per batch, which "
        "also drains write-behind); that is an upper bound on the cost, not a matched "
        "comparison.",
        "'One fdatasync per batch' is proven by GroupCommitStats counters + the JSON "
        "identity (fsyncs_per_group == 1.0), not by a syscall trace; 'visibility after "
        "restart' is a fresh-process re-read of the deterministic pattern "
        "(process-restart visibility, NOT a power-loss test).",
        "sync_latency_us is the commit() call duration and is therefore an UPPER BOUND on "
        "the sync-only cost (it includes waiting for the batch writes; the public API "
        "exposes no sync-only hook).",
        "The groupcommit file is preallocated with setLength (sparse) and offsets wrap modulo the "
        "file capacity; the pattern is position-indexed, so the verified prefix is "
        "deterministic across wraps (wrap_arounds reported).",
        "Rests (rest-time decision): 5 s pause between reps inside a cell, 120 s idle rest "
        "between cells - this is a count/latency micro-benchmark, not a throughput "
        "campaign.",
        "Configuration/durability: durability fdatasync (never fsync), bs 4096 B, "
        "cells b1/b8/b32/b128; the groupcommit measured window is 15 s after a 5 s warmup.",
    ]

    aggregate = {
        "schema_version": SCHEMA_VERSION,
        "schema_extensions": [REQUIRED_EXTENSION],
        "kind": "b6-w25-group-commit",
        "session": args.session,
        "source_dir": results_dir,
        "generated_utc": datetime.datetime.now(datetime.timezone.utc)
        .strftime("%Y-%m-%dT%H:%M:%SZ"),
        "contract": "protocol frozen before the campaign (rest times recorded in the manifest)",
        "protocol": protocol,
        "identity_verdict": identity_verdict,
        "identities_ok": bool(identities_ok),
        "cells": cells_out,
        "violations": violations,
        "data_quality": {"excluded": excluded, "missing": missing, "unparsed": unparsed},
        "disclosures": disclosures,
    }

    json_path = os.path.join(out_dir, "group-commit.json")
    md_path = os.path.join(out_dir, "group-commit.md")
    cli.write_json(json_path, aggregate)
    cli.write_text(md_path, render_markdown(args.session, results_dir, aggregate))

    print("wrote %s" % json_path)
    print("wrote %s" % md_path)
    for c in cells_out:
        print("  %-12s batch=%-4s n=%d MiB/s=%s group_p99=%s ms syncs/group=%s verify=%s"
              % (cellmap.label(c["cell"]), c["batch_size"], c["n"], md.fixed(c["mibs"]["mean"], 1),
                 md.fixed(c["group_latency_ms"]["p99"]["mean"], 2),
                 md.fixed(c["fsyncs_per_group"]["mean"], 6),
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
