"""Cell-JSON loading shared by the stream-campaign analyzers.

The stream-matrix, durability and group-commit analyzers all read
``<session>/cells/<cell>__<engine>__<variant>__r<rep>.json``.  The instruments and
the campaign policy differ, but the file naming, the per-file error tolerance and
the shape they read from each JSON do not.  Keeping that here means a corrupt or
misnamed cell is reported the same way by every campaign.

Frozen contract: the aggregates published from these sessions (BENCHMARK.md) are
byte-stable, and a failed/missing rep stays visible instead of being skipped.
"""

import json

from analysis import jsonio, stats


def parse_name(stem):
    """``<cell>__<engine>__<variant>__r<rep>`` -> tuple, or None when malformed."""
    parts = jsonio.split_stem(stem)
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
    """Aggregate row name; the exploratory ``juring --buffered`` row is renamed."""
    if engine == "juring" and variant == "buffered":
        return "juring-buffered"
    return engine


def extract(data):
    """Every field the three stream analyzers read from one cell JSON.

    The runners share the session envelope but publish different metrics, so
    this is the union of their slices.  The scalar fallbacks only matter for a
    session without an ``aggregate`` block; they follow the original
    analyze_stream convention (mean over ``runs``).
    """
    cfg = data.get("config") or {}
    agg = data.get("aggregate") or {}
    runs = data.get("runs") or []
    run = runs[0] if runs else {}
    run_lat = run.get("latency_us") or {}
    # The durability cells call the sync-wait latency sync_wait_us, the group-commit
    # cells call it sync_latency_us; each instrument emits exactly one of them.
    sync_lat = run.get("sync_wait_us") or run.get("sync_latency_us") or {}
    group_lat = run.get("group_latency_us") or {}
    agg_lat = agg.get("latency_us") or {}

    mibs = (agg.get("mibs") or {}).get("mean")
    if mibs is None and runs:
        mibs = stats.mean([float(r.get("mibs", 0.0)) for r in runs])
    iops = (agg.get("iops") or {}).get("mean")
    if iops is None and runs:
        iops = stats.mean([float(r.get("iops", 0.0)) for r in runs])
    p50 = agg_lat.get("p50")
    p99 = agg_lat.get("p99")
    if p50 is None and runs:
        p50 = stats.mean([float((r.get("latency_us") or {}).get("p50", 0.0)) for r in runs])
    if p99 is None and runs:
        p99 = stats.mean([float((r.get("latency_us") or {}).get("p99", 0.0)) for r in runs])
    errors_total = agg.get("errors_total")
    if errors_total is None:
        errors_total = sum(int(r.get("errors_total", 0)) for r in runs)

    return {
        "status": data.get("status"),
        "error": data.get("error"),
        "workload": cfg.get("workload"),
        "file": cfg.get("file"),
        "bs": cfg.get("bs"),
        "qd": cfg.get("qd"),
        "batch_size": cfg.get("batch_size", run.get("batch_size")),
        "durability": cfg.get("durability"),
        "seconds": cfg.get("seconds"),
        "warmup_sec": cfg.get("warmup_sec"),
        "seed": cfg.get("seed"),
        "dataset_bytes": cfg.get("dataset_bytes"),
        "direct_io": cfg.get("direct_io"),
        "registered_files": cfg.get("registered_files"),
        "sync_every_bytes": cfg.get("sync_every_bytes"),
        "run_seconds": run.get("seconds"),
        "durability_mode": run.get("durability_mode"),
        "mibs": mibs,
        "iops": iops,
        "errors_total": errors_total,
        "p50_us": p50,
        "p99_us": p99,
        "passes": run.get("passes"),
        "syncs_issued": int(run.get("syncs_issued") or 0),
        "syncs_completed": int(run.get("syncs_completed") or 0),
        "syncs_failed": int(run.get("syncs_failed") or 0),
        "durability_points": int(run.get("durability_points") or 0),
        "bytes_synced": int(run.get("bytes_synced") or 0),
        "sync_wait_share": run.get("sync_wait_share"),
        "chunk_p50_us": run_lat.get("p50"),
        "chunk_p99_us": run_lat.get("p99"),
        "sync_p50_us": sync_lat.get("p50"),
        "sync_p99_us": sync_lat.get("p99"),
        "groups_total": int(run.get("groups_total") or 0),
        "committed_writes": int(run.get("committed_writes") or 0),
        "committed_bytes": int(run.get("committed_bytes") or 0),
        "fsyncs_per_group": run.get("fsyncs_per_group"),
        "syncs_per_sec": run.get("syncs_per_sec"),
        "mib_per_sync": run.get("mib_per_sync"),
        "group_p50_us": group_lat.get("p50"),
        "group_p99_us": group_lat.get("p99"),
        "writes_per_sec": run.get("writes_per_sec"),
        "wrap_arounds": run.get("wrap_arounds"),
        "restart_verify": run.get("restart_verify") or {},
    }


def load_session(results_dir, expected_hint=None, schema_version=None,
                 required_extension=None, extension_note="", version_note=""):
    """Parse every ``cells/*.json``; a bad file is reported, never fatal.

    Returns ``(records, unparsed, schema_errors)``.  ``schema_version=None`` is
    analyze_stream's tolerant mode: an unreadable file is still a record with
    ``error`` set, so its rep is listed as excluded under Data quality.  The durability/group-commit
    analyzers pass their schema version instead: unreadable/schema-mismatched
    files become ``schema_errors`` and the caller refuses the session (exit 3).
    """
    records, unparsed, schema_errors = [], [], []
    for cell_path in jsonio.cell_files(results_dir):
        path = str(cell_path)
        parsed = parse_name(cell_path.stem)
        if parsed is None:
            reason = "unrecognised cell filename"
            if expected_hint:
                reason += " (expected %s)" % expected_hint
            unparsed.append({"file": path, "reason": reason})
            continue
        cell, engine, variant, rep = parsed
        rec = {"cell": cell, "engine": engine, "variant": variant, "rep": rep,
               "row": engine_row_name(engine, variant), "path": path, "data": None,
               "error": None, "extracted": None}
        try:
            with open(path, encoding="utf-8") as fh:
                rec["data"] = json.load(fh)
        except Exception as exc:              # malformed JSON: reported per file
            rec["error"] = "unreadable/invalid JSON: %s" % exc
            if schema_version is None:
                records.append(rec)
            else:
                schema_errors.append({"file": path, "reason": rec["error"]})
            continue
        if schema_version is not None:
            ext = rec["data"].get("schema_extensions")
            if not isinstance(ext, list) or required_extension not in ext:
                schema_errors.append({
                    "file": path,
                    "reason": "missing schema_extensions [%r]%s (schema_extensions=%r); "
                              "refusing to aggregate a mixed/old session"
                              % (required_extension, extension_note, ext)})
                continue
            if rec["data"].get("schema_version") != schema_version:
                schema_errors.append({
                    "file": path,
                    "reason": "schema_version %r != %r%s"
                              % (rec["data"].get("schema_version"), schema_version,
                                 version_note)})
                continue
        rec["extracted"] = extract(rec["data"])
        records.append(rec)
    return records, unparsed, schema_errors


def sort_cells(cells, cell_order):
    """Campaign-declared cell order first, then any unknown cell name."""
    return sorted(cells, key=lambda c: (
        cell_order.index(c) if c in cell_order else len(cell_order), c))
