"""Helpers for the shard-family analyzers.

The three analyzers under ``bench/shard/`` read the same driver outputs:
``cell-meta.tsv`` (via :func:`analysis.jsonio.load_csv_rows`) and, for
schema-1.1 sessions, the per-worker ``ring_series`` / ``ring_metrics`` /
``dispatch_latency_us`` fields embedded in each cell JSON.  Only the
byte-identical parts live here; each campaign keeps its own record shaping,
statistics, thresholds and rendering.
"""


def as_dict(value):
    """``value`` when it is a dict, else None (tolerating schema-1.0 JSON)."""
    return value if isinstance(value, dict) else None


def series_depth(series):
    """Per-ring queue depth from one worker's ``ring_series`` (measured phase)."""
    points = [p for p in (series or []) if isinstance(p, dict)]
    if not points:
        return None
    n = len(points)
    in_flight = [float(p.get("in_flight") or 0.0) for p in points]
    sq = [float(p.get("sq_queued") or 0.0) for p in points]
    cq = [float(p.get("cq_ready") or 0.0) for p in points]
    return {
        "series_points": n,
        "mean_in_flight": sum(in_flight) / n,
        "max_in_flight": max(in_flight),
        "mean_sq_queued": sum(sq) / n,
        "mean_cq_ready": sum(cq) / n,
    }


def ring_counters(rm):
    """Integer ``ring_metrics`` counters, or None when ``rm`` is not a dict.

    ``ring_metrics`` counts runtime-internal ops too (wakeup READ, shutdown
    cancel), so callers never sum ring-level and shard-level counters as if both
    were user ops.
    """
    if not isinstance(rm, dict):
        return None
    return {
        "failed": int(rm.get("failed") or 0),
        "cancelled": int(rm.get("cancelled") or 0),
        "timed_out": int(rm.get("timed_out") or 0),
        "in_flight": int(rm.get("in_flight") or 0),
        "sq_queued": int(rm.get("sq_queued") or 0),
        "cq_ready": int(rm.get("cq_ready") or 0),
    }


def gate_violation(state):
    """F14 gate states: ``ok`` and our own decaying loadavg residual are not violations."""
    if state in (None, "", "ok"):
        return False
    return not str(state).startswith("own-residual")


def r3(value):
    """Round a metric to the 3 dp the aggregate JSON/reports carry."""
    return None if value is None else round(float(value), 3)
