#!/usr/bin/env python3
"""M1 campaign analyzer: aggregate one measurement session, apply the M1 gate.

Usage:
    python3 bench/m1/analyze_read_write.py --dir bench/results/m1-2026-11-13 [--out-prefix PATH]

Reads a session directory written by bench/m1/lib.sh: ``cells/*.json`` (JUring /
seastar harness JSON or fio JSON), optional ``perf/*.txt``, ``iotester/*.yaml``,
``cell-meta.tsv``, ``perf-cells.tsv``, ``manifest*.txt`` and -- for write
sessions -- ``state/runs.tsv`` and ``series/*.tsv``.  Emits markdown tables, CSV
tables and a JSON aggregate under ``--out-prefix`` (default
``<dir>/aggregate/m1``, and only for the canonical M1 read session: every other
directory is raw evidence and is never written to without an explicit prefix).

Statistics: per-cell n/mean/SD/95% CI and paired-by-rep ratios (Student t; the
two-sided 90% bounds equal the one-sided 95% bounds the gates use).  The write
half (rule 1 against both registration-matched fio comparators, D2 device
series) is campaign policy; shared maths and formatting live in bench/analysis/.

Frozen contracts: docs/benchmark-methodology.md 6/7 (read gate) and
docs/m1-write-design.md (2026-11-15, write half).  Stdlib only.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from analysis import cli, jsonio, md, stats  # noqa: E402

VERSION = "1.0"
DEFAULT_DIR = jsonio.REPO_ROOT / "bench/results/m1-2026-11-13"

# --- policy constants (docs/benchmark-methodology.md §6/§7) ----------------
# Fast-state p99 discriminator for 4 KiB randread, per QD (Lead calibration
# 2026-11-13: p99 scales ~1.7x from QD64 to QD128, so a fixed 600 us threshold
# would mark every QD128 fast-throughput rep as "slow").
FAST_P99_US_QD64 = 600.0
FAST_P99_US_QD128 = 1200.0


def fast_p99_threshold(qd):
    """p99 fast-state threshold (us) for a 4K randread cell at this QD."""
    return FAST_P99_US_QD128 if (qd or 0) >= 128 else FAST_P99_US_QD64


# A rep whose IOPS is below this fraction of its cell's (case,engine,variant) max
# is flagged anomalous/slow; catches the QD64 255k-vs-325k bimodal state and
# low-throughput transients without trimming them.
IOPS_ANOMALY_FACTOR = 0.92
STATE_QDS = (64, 128)
SATURATION_CASES_READ = ("W3", "W4")
SATURATION_CASES_WRITE = ("W7", "W8")
LATENCY_CASES = ("W15", "W16")
PARITY_CASES = ("W1", "W2", "W3", "W4", "W9", "W10", "W11")
# Registration-matched fio comparator variants: "matched" = --registerfiles=1
# --fixedbufs=1; "regfile" = --registerfiles=1 only (used for 1 MiB cells where
# qd*1 MiB of fixed buffers exceeds the 8 MiB RLIMIT_MEMLOCK, so fio and JUring
# both have to drop fixed buffers -- still registration-matched on files).
MATCHED_FIO_VARIANTS = ("matched", "regfile")
UNSTABLE_CV_PCT = 10.0
# `perf` cells are separate instrumentation runs (40 s measured + attached perf);
# setup/devnull are io_tester housekeeping/probe variants. They are parsed and
# reported, but never used for throughput ratios/brackets.
NON_THROUGHPUT_VARIANTS = ("setup", "devnull")


def is_non_throughput_variant(variant):
    """True for instrumentation/housekeeping variants excluded from throughput."""
    return (variant in NON_THROUGHPUT_VARIANTS or
            str(variant).startswith("perf"))
IOPS_SPREAD_LIMIT = 0.10
THROUGHPUT_LB = 0.95         # rule 1: one-sided 95% lower bound of ratio
CPU_BAND = (0.85, 1.15)      # literal two-sided "within ±15 %" band (reported)
CPU_NONINFERIOR_LIMIT = 1.15  # gate: JUring cycles/op <= 1.15 x fio-matched
TAIL_FACTOR = 2.0            # rule 3: p99 <= 2x fio
P99_PARITY = 1.10
P999_PARITY = 1.15

# --- write-session policy (docs/m1-write-design.md, frozen 2026-11-15) ------
# M1 write cells (methodology §2): W5-W8 4K randwrite, W12-W14 1M seqwrite.
# case -> (workload, bs bytes, qd)
WRITE_CASE_SPEC = {
    "W5": ("randwrite", 4096, 1),
    "W6": ("randwrite", 4096, 16),
    "W7": ("randwrite", 4096, 64),
    "W8": ("randwrite", 4096, 128),
    "W12": ("seqwrite", 1048576, 1),
    "W13": ("seqwrite", 1048576, 8),
    "W14": ("seqwrite", 1048576, 32),
}
WRITE_GATE_CASES = ("W7", "W8")
WRITE_CASE_ORDER = ("W5", "W6", "W7", "W8", "W12", "W13", "W14")
# M1 write rule 1 is evaluated against BOTH registration-matched fio configs
# (design §4/§6): matchedbatch is primary (batching matched to JUring's fused
# submit), matched is the read-gate comparator (sensitivity). PASS needs the
# one-sided 95% lower bound >= 0.95 against BOTH; FAIL if either 95% upper
# bound < 0.95; otherwise INCONCLUSIVE. No comparator is ever selected by data.
WRITE_PRIMARY_VARIANT = "matchedbatch"
WRITE_SENSITIVITY_VARIANT = "matched"
WRITE_SWEEP_VARIANTS = ("batch1", "batch8", "batch16", "batch32")
FIO_SWEEP_ORDER = {"default": 0, "matched": 1, "matchedbatch": 2,
                   "batch1": 3, "batch8": 4, "batch16": 5, "batch32": 6}
# D2 analysis (methodology §5 D2/D7, design §5): burst vs steady state is the
# first 10 s of the measured window vs the remainder.
SERIES_BURST_S = 10
CLIFF_STEP_DROP = 0.30        # >30% one-step drop = cliff
CLIFF_MEAN_FRACTION = 0.70    # a sample <70% of the run mean = cliff sample
LOADAVG_FLAG = 1.6            # F14 disclosure threshold (design §11-D6)

ENGINE_ORDER = {"juring": 0, "fio-io_uring": 1, "fio-libaio": 2,
                "seastar": 3, "seastar-io_tester": 4}
# Existing read-session variants keep their original ranks: the read aggregate
# (cell/ratio row order) must stay byte-identical. Only variants that never
# appear in the read session (matchedbatch, batch1) are appended.
VARIANT_ORDER = {"std": 0, "matched": 1, "uring": 2, "b1": 3, "default": 4,
                 "libaio": 5, "matchedbatch": 6, "batch1": 7}

# legacy harness filenames -> methodology workload IDs
LEGACY_HARNESS_WF = {
    "rr4k": ("randread", 4096),
    "sq1m": ("seqread", 1048576),
    "nop": ("nop", 4096),
}
LEGACY_CASE = {
    ("randread", 4096, 1): "W1",
    ("randread", 4096, 16): "W2",
    ("randread", 4096, 64): "W3",
    ("randread", 4096, 128): "W4",
    ("seqread", 1048576, 1): "W9",
    ("seqread", 1048576, 8): "W10",
    ("seqread", 1048576, 32): "W11",
    ("nop", 4096, 64): "W24",
}


# ---------------------------------------------------------------------------
# filename / workload parsing
# ---------------------------------------------------------------------------

_BS_RE = re.compile(r"^bs(\d+[kKmMgG]?)$")
_QD_RE = re.compile(r"^qd(\d+)$")
_T_RE = re.compile(r"^t(\d+)$")
_R_RE = re.compile(r"^r(\d+)$")


def parse_size(text):
    """Parse '4k' / '1M' / '4096' / '4K' / '1g' into bytes. None if unparsable."""
    if text is None:
        return None
    s = str(text).strip()
    m = re.fullmatch(r"(\d+(?:\.\d+)?)\s*([kKmMgG]?)", s)
    if not m:
        return None
    value = float(m.group(1))
    unit = m.group(2).lower()
    mult = {"": 1, "k": 1024, "m": 1024 ** 2, "g": 1024 ** 3}[unit]
    out = value * mult
    return int(round(out))


def normalize_rw(rw):
    rw = (rw or "").strip().lower()
    if rw in ("randread",):
        return "randread"
    if rw in ("randwrite",):
        return "randwrite"
    if rw in ("read", "seqread"):
        return "seqread"
    if rw in ("write", "seqwrite"):
        return "seqwrite"
    if "randrw" in rw or rw == "mixed":
        return "mixed"
    if rw == "nop":
        return "nop"
    return rw or "?"


def parse_cell_stem(stem):
    """Parse `<case>__<engine>__<variant>__bs<bs>__qd<qd>__t<n>__r<rep>`.

    Optional tokens may be missing; None if this is not a cell id.
    """
    tokens = stem.split("__")
    if len(tokens) < 6:
        return None
    bs = qd = threads = rep = None
    first_bs = None
    for i, tok in enumerate(tokens):
        m = _BS_RE.match(tok)
        if m and bs is None:
            bs = parse_size(m.group(1))
            first_bs = i
            continue
        m = _QD_RE.match(tok)
        if m and qd is None:
            qd = int(m.group(1))
            continue
        m = _T_RE.match(tok)
        if m and threads is None:
            threads = int(m.group(1))
            continue
        m = _R_RE.match(tok)
        if m and rep is None:
            rep = int(m.group(1))
            continue
    if bs is None or qd is None or first_bs is None or first_bs < 3:
        return None
    case, engine, variant = tokens[0], tokens[1], tokens[2]
    return {
        "format": "session", "case": case, "engine": engine,
        "variant": variant, "bs": bs, "qd": qd,
        "threads": threads if threads is not None else 1,
        "rep": rep if rep is not None else 1,
        "workload_hint": None,
    }


_LEGACY_HARNESS_RE = re.compile(r"^(?:sr_)?(rr4k|sq1m|nop)_qd(\d+)_rep(\d+)$")


def parse_legacy_harness_stem(stem):
    m = _LEGACY_HARNESS_RE.match(stem)
    if not m:
        return None
    wf, qd, rep = m.group(1), int(m.group(2)), int(m.group(3))
    workload, bs = LEGACY_HARNESS_WF[wf]
    case = LEGACY_CASE.get((workload, bs, qd))
    if case is None:
        case = f"L-{wf}-qd{qd}"
    return {
        "format": "legacy-harness", "case": case, "engine": None,
        "variant": None, "bs": bs, "qd": qd, "threads": 1, "rep": rep,
        "workload_hint": workload,
    }


_LEGACY_FIO_RE = re.compile(
    r"^(?P<kind>[a-e])-(?P<wl>randread|seqread|read|write|randwrite)-"
    r"(?P<bs>\d+[kKmMgG])-(?P<io>uring|libaio)-qd(?P<qd>\d+)"
    r"(?:-(?P<extra>batch\d+|nj\d+))?(?P<rerun>-rerun)?_rep(?P<rep>\d+)$")


def parse_legacy_fio_stem(stem):
    m = _LEGACY_FIO_RE.match(stem)
    if not m:
        return None
    workload = normalize_rw(m.group("wl"))
    bs = parse_size(m.group("bs"))
    qd = int(m.group("qd"))
    extra = m.group("extra")
    threads = 1
    variant = "libaio" if m.group("io") == "libaio" else "b1"
    if extra:
        if extra.startswith("batch"):
            variant = extra
        elif extra.startswith("nj"):
            threads = int(extra[2:])
            variant = extra
    if m.group("rerun"):
        variant = variant + "-rerun"
    engine = "fio-libaio" if m.group("io") == "libaio" else "fio-io_uring"
    case = legacy_case_for(workload, bs, qd, threads)
    return {
        "format": "legacy-fio", "case": case, "engine": engine,
        "variant": variant, "bs": bs, "qd": qd, "threads": threads,
        "rep": int(m.group("rep")), "workload_hint": workload,
    }


def legacy_case_for(workload, bs, qd, threads):
    case = LEGACY_CASE.get((workload, bs, qd))
    if case is None:
        case = f"L-{workload}-{bs}-qd{qd}"
    if threads and threads > 1:
        case = case + f"-mj{threads}"
    return case


def strip_perf_suffixes(stem):
    for suf in (".perf", "-perf", "_perf", ".stat", "-stat", "_stat"):
        if stem.endswith(suf):
            stem = stem[: -len(suf)]
    return stem


def cell_sort_key(cell):
    m = re.match(r"^W(\d+)(.*)$", cell["case"])
    if m:
        return (0, int(m.group(1)), m.group(2), cell["case"])
    return (1, 0, cell["case"], cell["case"])


def rep_sort_key(label):
    m = re.match(r"^r?(\d+)(?:\.(\d+))?$", str(label))
    if not m:
        return (10 ** 9, 0)
    return (int(m.group(1)), int(m.group(2) or 0))


# ---------------------------------------------------------------------------
# raw artifact parsing
# ---------------------------------------------------------------------------

def make_rep(**kw):
    rep = {
        "path": None, "case": "?", "engine": "?", "variant": "?",
        "workload": "?", "bs": None, "qd": None, "threads": 1,
        "rep": "r1", "rep_index": 1,
        "iops": None, "mibs": None, "ops": None, "errors": None,
        "p50": None, "p90": None, "p95": None, "p99": None, "p999": None,
        "max": None, "lat_avg": None,
        "cpu_user_s": None, "cpu_sys_s": None, "ctxt": None,
        "buffer_mode": None, "ring_flags": None,
        "registered_files": None, "fixed_buffers": None,
        "iodepth_batch_submit": None, "sqpoll": None,
        "source_kind": None, "run_index": 0,
    }
    rep.update(kw)
    return rep


def _lat(lat, *keys):
    if not isinstance(lat, dict):
        return None
    for key in keys:
        if key in lat:
            value = jsonio.as_float(lat.get(key))
            if value is not None:
                return value
    return None


def parse_harness_json(path, key, data, warnings):
    """JUringFileBench / seastar_file_bench JSON -> list of Reps (one per run)."""
    engine = data.get("engine")
    if engine not in ("juring", "seastar"):
        return []
    cfg = data.get("config") or {}
    agg = data.get("aggregate") or {}
    workload = cfg.get("workload") or (key or {}).get("workload_hint") or "?"
    bs = jsonio.as_int(cfg.get("bs")) or (key or {}).get("bs")
    qd = jsonio.as_int(cfg.get("qd")) or (key or {}).get("qd")
    threads = jsonio.as_int(cfg.get("threads")) or (key or {}).get("threads") or 1
    variant = (key or {}).get("variant")
    if not variant:
        variant = "std" if engine == "juring" else "uring"
    case = (key or {}).get("case")
    base_rep = (key or {}).get("rep", 1)
    if not case:
        # try the label (e.g. legacy diagnostics "diag_rr4k_qd64_10s")
        label = str(data.get("label") or path.stem)
        hk = parse_legacy_harness_stem(label)
        if hk:
            case = hk["case"]
            bs = bs or hk["bs"]
            qd = qd or hk["qd"]
            base_rep = hk["rep"] if key is None else base_rep
        else:
            warnings.append(f"{jsonio.rel(path)}: not a matrix cell (no cell id, no "
                            f"legacy rr4k/sq1m/nop label); skipped")
            return []
    buffer_mode = cfg.get("io_buffer_mode")
    ring_flags = cfg.get("ring_flags")
    if isinstance(ring_flags, list):
        ring_flags = "+".join(ring_flags)
    reg_files = cfg.get("registered_files")
    fixed = None
    if buffer_mode == "registered-fixed":
        fixed = True
    elif buffer_mode in ("arena-unregistered", "aligned-unregistered"):
        fixed = False

    rows = data.get("runs")
    if not isinstance(rows, list) or not rows:
        rows = data.get("results")
    reps = []
    if isinstance(rows, list) and rows:
        multi = len(rows) > 1
        for idx, row in enumerate(rows):
            if not isinstance(row, dict):
                continue
            iops = jsonio.as_float(row.get("iops"))
            if iops is None:
                iops = jsonio.as_float(row.get("IOPS"))
            mibs = jsonio.as_float(row.get("mibs"))
            if mibs is None:
                mibs = jsonio.as_float(row.get("mib_s"))
            lat = row.get("latency_us")
            if not isinstance(lat, dict):
                lat = row.get("lat_us") or {}
            run_no = row.get("run")
            if run_no is not None and multi:
                rep_label = f"r{base_rep}.{run_no}"
            elif multi:
                rep_label = f"r{base_rep}.{idx + 1}"
            else:
                rep_label = f"r{base_rep}"
            reps.append(make_rep(
                path=path, case=case, engine=engine, variant=variant,
                workload=workload, bs=bs, qd=qd, threads=threads,
                rep=rep_label, rep_index=base_rep if not multi else base_rep * 1000 + idx,
                iops=iops, mibs=mibs,
                ops=jsonio.as_float(row.get("ops")) or jsonio.as_float(row.get("ops_total")),
                errors=jsonio.as_int(row.get("errors")) or jsonio.as_int(row.get("errors_total")),
                p50=_lat(lat, "p50"), p90=_lat(lat, "p90"), p95=_lat(lat, "p95"),
                p99=_lat(lat, "p99"), p999=_lat(lat, "p999"),
                max=_lat(lat, "max"), lat_avg=_lat(lat, "avg", "average"),
                buffer_mode=buffer_mode, ring_flags=ring_flags,
                registered_files=reg_files, fixed_buffers=fixed,
                source_kind="harness", run_index=idx,
            ))
        return reps
    iops = jsonio.as_float((agg.get("iops") or {}).get("mean"))
    mibs = jsonio.as_float((agg.get("mibs") or {}).get("mean"))
    lat = agg.get("latency_us") or {}
    if iops is None:
        return []
    reps.append(make_rep(
        path=path, case=case, engine=engine, variant=variant,
        workload=workload, bs=bs, qd=qd, threads=threads,
        rep=f"r{base_rep}", rep_index=base_rep,
        iops=iops, mibs=mibs, ops=jsonio.as_float(agg.get("ops_total")),
        errors=jsonio.as_int(agg.get("errors_total")),
        p50=_lat(lat, "p50"), p90=_lat(lat, "p90"), p95=_lat(lat, "p95"),
        p99=_lat(lat, "p99"), p999=_lat(lat, "p999"),
        max=_lat(lat, "max"), lat_avg=_lat(lat, "avg", "average"),
        buffer_mode=buffer_mode, ring_flags=ring_flags,
        registered_files=reg_files, fixed_buffers=fixed,
        source_kind="harness-aggregate",
    ))
    return reps


def fio_percentile(section, target):
    """fio clat_ns percentile -> microseconds; target 100 => max."""
    if not isinstance(section, dict):
        return None
    for key in ("clat_ns", "lat_ns"):
        block = section.get(key)
        if not isinstance(block, dict):
            continue
        if target >= 100.0:
            value = jsonio.as_float(block.get("max"))
            if value is not None:
                return value / 1000.0
            continue
        pct = block.get("percentile")
        if isinstance(pct, dict) and pct:
            best = None
            for k, v in pct.items():
                try:
                    kf = float(k)
                except (TypeError, ValueError):
                    continue
                if best is None or abs(kf - target) < abs(best[0] - target):
                    best = (kf, jsonio.as_float(v))
            if best is not None and best[1] is not None:
                return best[1] / 1000.0
    return None


def parse_fio_json(path, key, data, warnings):
    """fio JSON -> one Rep per file (multi-job files are summed into one cell)."""
    jobs = data.get("jobs") or []
    if not jobs:
        return []
    iops_total = 0.0
    mibs_total = 0.0
    ops_total = 0.0
    errors_total = 0
    p50s, p95s, p99s, p999s, maxs = [], [], [], [], []
    cpu_user_s = 0.0
    cpu_sys_s = 0.0
    ctxt = 0.0
    rw = ""
    bs = None
    qd = None
    numjobs = len(jobs)
    reg_files = fixed_bufs = batch = sqpoll = None
    for job in jobs:
        if not isinstance(job, dict):
            continue
        opts = job.get("job options") or {}
        if not rw and opts.get("rw"):
            rw = str(opts.get("rw"))
        if bs is None:
            bs = parse_size(opts.get("bs"))
        if qd is None:
            qd = jsonio.as_int(opts.get("iodepth"))
        nj = jsonio.as_int(opts.get("numjobs"))
        if nj:
            numjobs = nj
        for opt, name in (("registerfiles", "reg"), ("fixedbufs", "fixed"),
                          ("iodepth_batch_submit", "batch"),
                          ("sqthread_poll", "sqpoll")):
            if opt in opts:
                value = jsonio.as_int(opts.get(opt))
                truthy = bool(value) if value is not None else None
                if name == "reg":
                    reg_files = truthy
                elif name == "fixed":
                    fixed_bufs = truthy
                elif name == "batch":
                    batch = value
                else:
                    sqpoll = truthy
        read = job.get("read") or {}
        write = job.get("write") or {}
        read_bytes = jsonio.as_float(read.get("io_bytes")) or 0.0
        write_bytes = jsonio.as_float(write.get("io_bytes")) or 0.0
        sec = write if write_bytes > read_bytes else read
        iops_total += jsonio.as_float(sec.get("iops")) or 0.0
        mibs_total += (jsonio.as_float(sec.get("bw_bytes")) or 0.0) / (1024.0 * 1024.0)
        ops_total += jsonio.as_float(sec.get("total_ios")) or 0.0
        for vals, target in ((p50s, 50.0), (p95s, 95.0), (p99s, 99.0),
                             (p999s, 99.9), (maxs, 100.0)):
            v = fio_percentile(sec, target)
            if v is not None:
                vals.append(v)
        err = jsonio.as_int(job.get("error"))
        errors_total += err or 0
        job_cpu = job.get("cpu")
        if isinstance(job_cpu, dict):
            cpu_user_s += jsonio.as_float(job_cpu.get("user")) or 0.0
            cpu_sys_s += jsonio.as_float(job_cpu.get("sys")) or 0.0
        else:
            # fio 3.40 JSON: job-level usr_cpu/sys_cpu are percentages of wall
            usr = jsonio.as_float(job.get("usr_cpu"))
            sys_ = jsonio.as_float(job.get("sys_cpu"))
            runtime_ms = jsonio.as_float(job.get("job_runtime"))
            if runtime_ms is None:
                runtime_ms = (jsonio.as_float(job.get("elapsed")) or 0.0) * 1000.0
            if usr is not None and runtime_ms:
                cpu_user_s += usr / 100.0 * runtime_ms / 1000.0
            if sys_ is not None and runtime_ms:
                cpu_sys_s += sys_ / 100.0 * runtime_ms / 1000.0
        ctxt += jsonio.as_float(job.get("ctx")) or 0.0

    workload = normalize_rw(rw)
    if key:
        case, engine, variant = key["case"], key["engine"], key["variant"]
        rep_label = f"r{key.get('rep', 1)}"
        rep_index = key.get("rep", 1)
        bs = bs or key.get("bs")
        qd = qd or key.get("qd")
        numjobs = key.get("threads") or numjobs or 1
    else:
        engine = "fio-io_uring"
        variant = "unknown"
        case = legacy_case_for(workload, bs or 0, qd or 1, numjobs)
        rep_label = "r1"
        rep_index = 1
        warnings.append(f"{jsonio.rel(path)}: no cell id match; case named {case}")
    if workload in ("seqread", "seqwrite"):
        bs = bs or None
    reps = [make_rep(
        path=path, case=case, engine=engine, variant=variant,
        workload=workload, bs=bs, qd=qd, threads=numjobs,
        rep=rep_label, rep_index=rep_index,
        iops=iops_total if iops_total > 0 else None,
        mibs=mibs_total,
        ops=ops_total, errors=errors_total,
        p50=(statistics.fmean(p50s) if p50s else None),
        p95=(statistics.fmean(p95s) if p95s else None),
        p99=(max(p99s) if p99s else None),
        p999=(max(p999s) if p999s else None),
        max=(max(maxs) if maxs else None),
        cpu_user_s=cpu_user_s or None, cpu_sys_s=cpu_sys_s or None,
        ctxt=ctxt or None,
        registered_files=reg_files, fixed_buffers=fixed_bufs,
        iodepth_batch_submit=batch, sqpoll=sqpoll,
        source_kind="fio",
    )]
    return reps


# ---------------------------------------------------------------------------
# perf stat text parsing
# ---------------------------------------------------------------------------

_NUM_RE = re.compile(r"^[+-]?(?:\d[\d,]*)(?:\.\d+)?$")


def _num(text):
    try:
        return float(str(text).replace(",", "").strip())
    except (TypeError, ValueError):
        return None


def parse_perf_text(text):
    """Return ({event: value}, elapsed_seconds) from perf stat text / `-x,` / `-j`."""
    vals = {}
    elapsed = None
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "seconds time elapsed" in line:
            elapsed = _num(line.split("seconds")[0])
            continue
        if line.startswith("{"):
            try:
                obj = json.loads(line)
            except Exception:
                continue
            event = obj.get("event") or obj.get("event-name")
            value = _num(obj.get("counter-value"))
            if event and value is not None:
                vals[event] = vals.get(event, 0.0) + value
            continue
        # plain text: "<value> <event>" (value may use thousands separators)
        m = re.match(r"^([\d][\d,]*(?:\.\d+)?)\s+([A-Za-z0-9_:./\-]+)", line)
        if m:
            event = m.group(2)
            value = _num(m.group(1))
            if value is not None:
                vals[event] = vals.get(event, 0.0) + value
            continue
        # `perf stat -x,` CSV: value,unit,event,...
        if "," in line:
            parts = [p.strip() for p in line.split(",")]
            if (len(parts) >= 3 and
                    re.fullmatch(r"[A-Za-z][A-Za-z0-9_:./\-]*", parts[2] or "")):
                event = parts[2]
                value = _num(parts[0])
                if event and value is not None and not event.startswith("<"):
                    vals[event] = vals.get(event, 0.0) + value
    return vals, elapsed


def looks_like_perf(text):
    return ("seconds time elapsed" in text or
            re.search(r"\bcycles(:[uk])?\b", text) is not None or
            re.search(r"\braw_syscalls:sys_enter\b", text) is not None)


def load_perf_windows(session_dir, warnings):
    """Map (case, engine, variant, qd, rep) -> perf window seconds.

    The window is not in the `-x,` perf text; later rows win (a cell may be rerun).
    """
    path = session_dir / "perf-cells.tsv"
    if not path.is_file():
        return {}
    windows = {}
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as fh:
            for row in csv.DictReader(fh, delimiter="\t"):
                try:
                    key = ((row.get("case") or "").strip(),
                           (row.get("engine") or "").strip(),
                           (row.get("variant") or "").strip(),
                           jsonio.as_int((row.get("qd") or "").strip()),
                           jsonio.as_int((row.get("rep") or "").strip()))
                    window = jsonio.as_float((row.get("window_s") or "").strip())
                except Exception:
                    continue
                if window and window > 0:
                    windows[key] = window
    except Exception as exc:
        warnings.append(f"{jsonio.rel(path)}: could not read perf windows ({exc})")
        return {}
    return windows


def load_perf_cells_tsv(session_dir, warnings):
    """Load perf-cells.tsv: authoritative per-rep CPU/syscall numbers.

    fio `perf`/`perf-matched` shared a filename stem, so the default run's raw
    capture was overwritten; the computed rows for both variants survive here.
    """
    path = session_dir / "perf-cells.tsv"
    if not path.is_file():
        return []
    rows_out = []
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as fh:
            rows = list(csv.DictReader(fh, delimiter="\t"))
    except Exception as exc:
        warnings.append(f"{jsonio.rel(path)}: unreadable perf-cells.tsv ({exc})")
        return []
    for row in rows:
        def num(name, row=row):
            return jsonio.as_float((row.get(name) or "").strip())
        rep = jsonio.as_int((row.get("rep") or "").strip())
        qd = jsonio.as_int((row.get("qd") or "").strip())
        engine = (row.get("engine") or "").strip()
        variant = (row.get("variant") or "").strip()
        rows_out.append({
            "path": path,
            "case": (row.get("case") or "").strip(),
            "engine": engine, "variant": variant, "qd": qd, "rep": rep,
            "attach_s": num("attach_s"), "window": num("window_s"),
            "ops": num("ops_window"), "iops": num("iops"),
            "task_clock_s": num("task_clock_s"),
            "cycles_u": num("cycles_u"), "cycles_k": num("cycles_k"),
            "instr_u": num("instructions_u"), "instr_k": num("instructions_k"),
            "syscalls": num("syscalls"), "ctxt": num("ctxt_switches"),
            "cycles_per_op": num("cycles_per_op"),
            "instr_per_op": num("instructions_per_op"),
            "syscalls_per_op": num("syscalls_per_op"),
            "ctxt_per_op": num("ctxt_per_op"),
        })
    return rows_out


def build_perf_report(session_dir, perf_tsv_rows, perf_records, warnings):
    """Authoritative perf rows for the report + raw-capture provenance mapping."""
    if not perf_tsv_rows:
        return [dict(rec, provenance="raw perf capture", raw_source=jsonio.rel(rec["path"]))
                for rec in perf_records if rec.get("cycles_per_op") is not None]
    raw_index = {}
    for rec in perf_records:
        if rec.get("case") is None or rec.get("qd") is None:
            continue
        raw_index[(rec["case"], rec["engine"], rec["qd"],
                   rec.get("rep") if rec.get("rep") is not None else 1)] = rec
    out = []
    for row in perf_tsv_rows:
        raw = raw_index.get((row["case"], row["engine"], row["qd"], row["rep"]))
        if row["engine"] == "fio-io_uring" and row["variant"] == "perf":
            provenance = ("computed record only: raw capture overwritten by the "
                          "id collision (documented in notes.md); do not cite as "
                          "fully reproducible")
            raw_source = None
        elif row["engine"] == "fio-io_uring" and row["variant"] == "perf-matched":
            provenance = ("computed record; the surviving raw capture for this "
                          "rep is the matched run under the `__perf__` stem "
                          "(id collision, documented in notes.md)")
            raw_source = jsonio.rel(raw["path"]) if raw else None
        elif raw is not None:
            provenance = "raw perf capture"
            raw_source = jsonio.rel(raw["path"])
        else:
            provenance = "computed record only (raw capture not found)"
            raw_source = None
        entry = dict(row)
        entry["source"] = jsonio.rel(row["path"])
        entry["raw_source"] = raw_source
        entry["provenance"] = provenance
        out.append(entry)
    return out


def attach_perf(records, reps, cells, warnings, windows=None):
    """Attach window op estimate + per-op CPU numbers to each perf record."""
    windows = windows or {}
    rep_index = {}
    cell_ops = {}
    for rep in reps:
        k = (rep["case"], rep["engine"], rep["variant"], rep["bs"], rep["qd"],
             rep["threads"], rep["rep_index"])
        rep_index[k] = rep
        cell_ops.setdefault(
            (rep["case"], rep["engine"], rep["variant"], rep["bs"], rep["qd"],
             rep["threads"]), []).append(rep.get("ops"))
    cell_iops = {}
    for cell in cells:
        vals = [r.get("iops") for r in cell["reps"] if r.get("iops") is not None]
        if vals:
            cell_iops[(cell["case"], cell["engine"], cell["variant"], cell["bs"],
                       cell["qd"], cell["threads"])] = statistics.fmean(vals)

    for rec in records:
        if not rec.get("key"):
            continue
        k_rep = (rec["case"], rec["engine"], rec["variant"], rec["bs"], rec["qd"], 1,
                 rec["rep"] if rec["rep"] is not None else 1)
        k_cell = (rec["case"], rec["engine"], rec["variant"], rec["bs"], rec["qd"], 1)
        ops = None
        iops = cell_iops.get(k_cell)
        window = rec.get("elapsed")
        window_source = "perf text"
        if not window:
            rep_no = rec["rep"] if rec["rep"] is not None else 1
            # raw perf stems use variant "perf" for every engine, while
            # perf-cells.tsv may label the same run "std"/"perf-matched";
            # fall back through the known labels (window is 30 s for all here).
            for cand in (rec["variant"], "std", "perf", "perf-matched"):
                window = windows.get((rec["case"], rec["engine"], cand,
                                      rec["qd"], rep_no))
                if window:
                    break
            window_source = "perf-cells.tsv"
        rec["window"] = window
        if iops is not None and window:
            # ops in the perf window = engine IOPS x window (perf_summary.py rule;
            # the window covers the steady-state phase only, not the full run)
            ops = iops * window
        elif k_rep in rep_index:
            ops = rep_index[k_rep].get("ops")
            warnings.append(
                f"{jsonio.rel(rec['path'])}: perf window unknown (no {window_source} entry); "
                f"cycles/op assumes the full measured run window")
        if ops is None:
            cand = [v for v in cell_ops.get(k_cell, []) if v]
            ops = statistics.fmean(cand) if cand else None
        counters = rec["counters"]

        def get(*names, counters=counters):
            total = 0.0
            found = False
            for name in names:
                if name in counters:
                    total += counters[name]
                    found = True
            return total if found else None

        cycles = get("cycles", "cycles:u", "cycles:k")
        if "cycles:u" in counters or "cycles:k" in counters:
            cycles = (counters.get("cycles:u", 0.0) + counters.get("cycles:k", 0.0))
        instr = get("instructions", "instructions:u", "instructions:k")
        if "instructions:u" in counters or "instructions:k" in counters:
            instr = (counters.get("instructions:u", 0.0) +
                     counters.get("instructions:k", 0.0))
        rec["cycles"] = cycles
        rec["instr"] = instr
        rec["syscalls"] = get("raw_syscalls:sys_enter")
        rec["ctxt"] = get("context-switches", "cs")
        rec["ops"] = ops
        if ops:
            if cycles is not None:
                rec["cycles_per_op"] = cycles / ops
            if instr is not None:
                rec["instr_per_op"] = instr / ops
            if rec["syscalls"] is not None:
                rec["syscalls_per_op"] = rec["syscalls"] / ops
            if rec["ctxt"] is not None:
                rec["ctxt_per_op"] = rec["ctxt"] / ops
        if rec.get("cycles") is None:
            warnings.append(f"{jsonio.rel(rec['path'])}: no cycles counters found")


def load_session_manifest(session_dir, warnings):
    """Read manifest.txt (session environment manifest) for the aggregate payload."""
    path = session_dir / "manifest.txt"
    if not path.is_file():
        return None
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except Exception as exc:
        warnings.append(f"{jsonio.rel(path)}: unreadable manifest.txt ({exc})")
        return None
    return {"path": path, "text": text, "lines": text.splitlines()}


def manifest_value(manifest, key):
    """First `key=value` line in the manifest or any manifest-run<N>.txt.

    The write driver appends later blocks' flags to manifest-run files.
    """
    if not manifest:
        return None
    paths = [manifest] + list(manifest.get("extra", []))
    for entry in paths:
        for line in entry.get("lines") or []:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _sep, v = line.partition("=")
            if k.strip() == key:
                return v.strip()
    return None


def manifest_int(manifest, key, default=None):
    """First `<key>=<int>` token in the manifest, including combined lines.

    The write manifest stores `reps=3 measured_s=60 warmup=...` on one line.
    """
    if not manifest:
        return default
    pattern = re.compile(r"(?:^|\s)" + re.escape(key) + r"=(\d+)")
    for entry in [manifest] + list(manifest.get("extra", [])):
        for line in entry.get("lines") or []:
            m = pattern.search(line)
            if m:
                return int(m.group(1))
    return default


def load_session_manifests(session_dir, warnings):
    """Read manifest.txt + manifest-run*.txt (multi-invocation write sessions)."""
    main = load_session_manifest(session_dir, warnings)
    if main is None:
        return None
    extra = []
    for path in sorted(session_dir.glob("manifest-run*.txt")):
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except Exception as exc:
            warnings.append(f"{jsonio.rel(path)}: unreadable manifest ({exc})")
            continue
        extra.append({"path": path, "text": text, "lines": text.splitlines()})
    main["extra"] = extra
    return main


def cell_meta_p99_units(session_dir, manifest, warnings):
    """Return (units, flag_source) for the `cell-meta.tsv` p99_us column.

    Session-11 declares `cell_meta_p99_units=us`; the legacy 2026-11-13 write block
    wrote fio clat in nanoseconds (documented units defect).
    """
    raw = manifest_value(manifest, "cell_meta_p99_units")
    if raw:
        units = raw.strip().lower()
        source = "manifest cell_meta_p99_units"
        return units, source
    if (session_dir / "cell-meta.tsv").is_file():
        return None, "absent (legacy 2026-11-13 behaviour)"
    return None, "no cell-meta.tsv"



# ---------------------------------------------------------------------------
# seastar io_tester YAML parsing (stdlib only)
# ---------------------------------------------------------------------------

_SHARD_RE = re.compile(r"^-\s+shard:\s*(\d+)\s*$")


def parse_iotester_yaml(text):
    """Return list of per-shard dicts (flattened dotted keys) from io_tester."""
    shards = []
    blocks = re.split(r"(?m)^-\s+shard:\s*", text)
    if len(blocks) <= 1:
        return shards
    for block in blocks[1:]:
        lines = block.splitlines()
        if not lines:
            continue
        fields = {}
        stack = []
        for line in lines[1:]:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            line = re.split(r"\s+#", line, maxsplit=1)[0].rstrip()
            m = re.match(r"^(\s*)([A-Za-z0-9_./\-]+):(?:\s*(.*))?$", line)
            if not m:
                continue
            indent = len(m.group(1))
            key = m.group(2)
            value = (m.group(3) or "").strip()
            while stack and stack[-1][0] >= indent:
                stack.pop()
            path = [k for _i, k in stack] + [key]
            if value != "":
                fields[".".join(path)] = value
            else:
                stack.append((indent, key))
        shards.append(fields)
    return shards


def _pick(fields, suffix):
    best = None
    for key, value in fields.items():
        if key == suffix or key.endswith("." + suffix):
            if best is None or len(key) < best[0]:
                best = (len(key), value)
    if best is None:
        return None
    return _num(best[1])


def load_iotester(session_dir, warnings):
    candidates = []
    ydir = session_dir / "iotester"
    if ydir.is_dir():
        candidates.extend(sorted(ydir.rglob("*.yaml")) + sorted(ydir.rglob("*.yml")))
    for path in sorted(session_dir.rglob("*.yaml")) + sorted(session_dir.rglob("*.yml")):
        if "accidental" in str(path):
            continue
        if path not in candidates and "iotester" in str(path).lower():
            candidates.append(path)
    out = []
    for path in candidates:
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except Exception as exc:
            warnings.append(f"{jsonio.rel(path)}: unreadable io_tester file ({exc})")
            continue
        if "IOPS" not in text:
            continue
        shards = parse_iotester_yaml(text)
        if not shards:
            warnings.append(f"{jsonio.rel(path)}: io_tester YAML had no '- shard:' block")
            continue
        def pick_any(keys, shards=shards):
            """io_tester emits quantile keys as p0.5/p0.95/p0.99/p0.999."""
            for key_name in keys:
                vals = [_pick(s, key_name) for s in shards]
                vals = [v for v in vals if v is not None]
                if vals:
                    return vals
            return []

        iops = sum((_pick(s, "IOPS") or 0.0) for s in shards)
        kbs = sum((_pick(s, "throughput") or 0.0) for s in shards)
        p50s = pick_any(("latencies.p0.5", "latencies.p50"))
        p95s = pick_any(("latencies.p0.95", "latencies.p95"))
        p99s = pick_any(("latencies.p0.99", "latencies.p99"))
        p999s = pick_any(("latencies.p0.999", "latencies.p999"))
        maxs = pick_any(("latencies.max",))
        avgs = pick_any(("latencies.average",))
        ops = sum((_pick(s, "stats.total_requests") or 0.0) for s in shards)
        stem = path.name[: -len(path.suffix)] if path.suffix else path.name
        key = parse_cell_stem(stem)
        if key is None:
            if stem.startswith("setup"):
                continue  # io_tester --keep-files setup run, not a measured cell
            warnings.append(f"{jsonio.rel(path)}: io_tester file name does not encode a cell id")
            continue
        out.append({
            "path": path, "key": key, "n_shards": len(shards),
            "iops": iops if iops > 0 else None,
            "mibs": (kbs / 1024.0) if kbs else None,
            "p50": (statistics.fmean(p50s) if p50s else None),
            "p95": (statistics.fmean(p95s) if p95s else None),
            "p99": max(p99s) if p99s else None,
            "p999": max(p999s) if p999s else None,
            "max": max(maxs) if maxs else None,
            "lat_avg": (statistics.fmean(avgs) if avgs else None),
            "ops": ops or None,
        })
    return out


# ---------------------------------------------------------------------------
# discovery
# ---------------------------------------------------------------------------

def discover(session_dir, warnings):
    """Walk the session dir and return (harness/fio reps, perf records, iotester)."""
    reps = []
    perf_records = []
    iotester = []
    parsed = {"harness": 0, "fio": 0, "perf": 0, "iotester": 0, "skipped": 0}
    perf_paths = set()
    for path in sorted(session_dir.rglob("*")):
        if not path.is_file():
            continue
        try:
            parts = path.relative_to(session_dir).parts
        except ValueError:
            parts = path.parts
        if any(part in ("aggregate", "logs", "state", "series") for part in parts):
            continue
        if any("accidental" in part for part in parts):
            # archived pre-session io_tester run; ordering invalid, never use it
            continue
        suffix = path.suffix.lower()
        if suffix == ".json":
            data = jsonio.load_json(path)
            if data is None:
                warnings.append(f"{jsonio.rel(path)}: not valid JSON")
                continue
            if not isinstance(data, dict):
                parsed["skipped"] += 1
                continue
            stem = path.name[: -len(path.suffix)]
            if "jobs" in data and isinstance(data.get("jobs"), list):
                key = parse_cell_stem(stem) or parse_legacy_fio_stem(stem)
                rows = parse_fio_json(path, key, data, warnings)
                if rows:
                    reps.extend(rows)
                    parsed["fio"] += 1
                else:
                    parsed["skipped"] += 1
            elif data.get("engine") in ("juring", "seastar") and (
                    "runs" in data or "aggregate" in data or "results" in data):
                key = parse_cell_stem(stem) or parse_legacy_harness_stem(stem)
                n_warn = len(warnings)
                rows = parse_harness_json(path, key, data, warnings)
                if rows:
                    reps.extend(rows)
                    parsed["harness"] += 1
                elif len(warnings) == n_warn:
                    warnings.append(f"{jsonio.rel(path)}: harness JSON without usable runs")
            else:
                parsed["skipped"] += 1
        elif suffix in (".txt",):
            low = path.name.lower()
            if ("perf" in low or "perf" in str(path.parent).lower()):
                text = None
                try:
                    text = path.read_text(encoding="utf-8", errors="replace")
                except Exception:
                    continue
                if looks_like_perf(text):
                    perf_paths.add(path)
    iotester = load_iotester(session_dir, warnings)
    parsed["iotester"] = len(iotester)

    if perf_paths:
        records = []
        for path in sorted(perf_paths):
            text = path.read_text(encoding="utf-8", errors="replace")
            counters, elapsed = parse_perf_text(text)
            if not counters:
                warnings.append(f"{jsonio.rel(path)}: no perf counters parsed")
                continue
            stem = strip_perf_suffixes(path.name[: -len(path.suffix)] if path.suffix else path.name)
            key = parse_cell_stem(stem)
            if key is None:
                warnings.append(f"{jsonio.rel(path)}: perf file name does not encode a cell id")
            records.append({
                "path": path, "key": key, "counters": counters, "elapsed": elapsed,
                "rep": key.get("rep") if key else None,
                "case": key.get("case") if key else None,
                "engine": key.get("engine") if key else None,
                "variant": key.get("variant") if key else None,
                "bs": key.get("bs") if key else None,
                "qd": key.get("qd") if key else None,
                "ops": None, "cycles": None, "instr": None, "syscalls": None,
                "ctxt": None, "cycles_per_op": None, "instr_per_op": None,
                "syscalls_per_op": None, "ctxt_per_op": None,
            })
        perf_records = records
    parsed["perf"] = len(perf_records)
    return reps, perf_records, iotester, parsed


# ---------------------------------------------------------------------------
# cells
# ---------------------------------------------------------------------------

def classify_state(cell):
    """Per-rep fast/slow classification + cell summary.

    Two symmetric signals are reported: the p99 criterion (single-thread 4 KiB
    randread QD64/QD128) and the IOPS < 0.92 x cell max criterion (all cells).
    The cell always keeps its all-reps statistics as primary; the fast-state
    subset is disclosed separately (gates use it only for unstable cells).
    """
    applies = (cell["workload"] == "randread" and cell["bs"] == 4096 and
               cell["qd"] in STATE_QDS and cell["threads"] == 1)
    cell["state_applies"] = applies
    threshold = fast_p99_threshold(cell["qd"]) if applies else None
    cell["p99_threshold"] = threshold
    reps = cell["reps"]
    iops_vals = [r["iops"] for r in reps if r.get("iops") is not None]
    max_iops = max(iops_vals) if iops_vals else None
    cell["cell_max_iops"] = max_iops
    fast, slow = [], []
    for rep in reps:
        reasons = []
        p99 = rep.get("p99")
        if applies:
            if p99 is None:
                reasons.append("no p99")
            elif p99 >= threshold:
                reasons.append(f"p99 {p99:.1f}us >= {threshold:.0f}us")
        ratio = None
        if max_iops and rep.get("iops") is not None:
            ratio = rep["iops"] / max_iops
            if ratio < IOPS_ANOMALY_FACTOR:
                reasons.append(
                    f"iops {rep['iops']:.0f} < 0.92*max (cell max "
                    f"{max_iops:.0f}, rep is {100.0 * ratio:.1f}%)")
        rep["iops_ratio_to_max"] = ratio
        rep["state_reasons"] = reasons
        rep["state"] = "slow" if reasons else "fast"
        (slow if reasons else fast).append(rep)
    cell["n_fast"] = len(fast)
    cell["n_slow"] = len(slow)
    cell["fast_reps"] = fast
    cell["slow_reps"] = slow
    cell["slow_ids"] = [r["rep"] for r in slow]
    cell["fast_stats"] = stats.describe([r["iops"] for r in fast])
    cell["fast_iops_mean"] = cell["fast_stats"]["mean"]
    cell["fast_ci95_half"] = cell["fast_stats"]["ci95_half"]
    cell["fast_ci95_low"] = cell["fast_stats"]["ci95_low"]
    cell["fast_ci95_high"] = cell["fast_stats"]["ci95_high"]
    cell["mean_fast"] = cell["fast_stats"]["mean"]
    cell["mean_slow"] = (statistics.fmean([r["iops"] for r in slow])
                         if slow else None)
    iops_spread = None
    if len(iops_vals) > 1 and min(iops_vals) > 0:
        iops_spread = max(iops_vals) / min(iops_vals) - 1.0
    cell["iops_spread"] = iops_spread
    cell["mixed_state"] = bool(fast) and bool(slow)
    cell["bimodal_p99"] = applies and bool(fast) and bool(slow)
    cell["bimodal_iops"] = iops_spread is not None and iops_spread > IOPS_SPREAD_LIMIT
    cell["cv_unstable"] = (cell["cv_pct"] is not None and
                           cell["cv_pct"] > UNSTABLE_CV_PCT)
    cell["unstable"] = bool(cell["mixed_state"] or cell["cv_unstable"] or
                            cell["bimodal_iops"])
    cell["state_valid"] = not cell["slow_reps"]
    if not slow:
        label = "fast" if fast else "unknown"
        cell["state"] = label
        cell["state_note"] = (f"all {len(fast)} rep(s) fast"
                              + (f" (p99 threshold {threshold:.0f}us)"
                                 if applies else " (IOPS criterion only)"))
    elif not fast:
        cell["state"] = "slow"
        ids = ", ".join(cell["slow_ids"])
        cell["state_note"] = f"all {len(slow)} rep(s) slow ({ids})"
    else:
        cell["state"] = "mixed"
        details = "; ".join(f"{r['rep']}: " + ", ".join(r["state_reasons"])
                            for r in slow)
        cell["state_note"] = (f"{len(fast)} fast / {len(slow)} slow "
                              f"(slow: {details})")


def build_cells(reps):
    groups = {}
    for rep in reps:
        key = (rep["case"], rep["engine"], rep["variant"], rep["bs"], rep["qd"],
               rep["threads"])
        groups.setdefault(key, []).append(rep)
    cells = []
    for key, rows in groups.items():
        rows = sorted(rows, key=lambda r: rep_sort_key(r["rep"]))
        iops_st = stats.describe([r.get("iops") for r in rows])
        mibs_st = stats.describe([r.get("mibs") for r in rows])
        cell = {
            "case": key[0], "engine": key[1], "variant": key[2], "bs": key[3],
            "qd": key[4], "threads": key[5],
            "workload": rows[0]["workload"],
            "reps": rows,
            "n": iops_st["n"],
            "iops_mean": iops_st["mean"], "iops_sd": iops_st["sd"],
            "ci95_low": iops_st["ci95_low"], "ci95_high": iops_st["ci95_high"],
            "ci95_half": iops_st["ci95_half"],
            "min": iops_st["min"], "median": iops_st["median"],
            "max": iops_st["max"], "cv_pct": iops_st["cv_pct"],
            "mibs_mean": mibs_st["mean"],
            "p50": statistics.fmean([r["p50"] for r in rows
                                     if r.get("p50") is not None]) if any(
                r.get("p50") is not None for r in rows) else None,
            "p95": statistics.fmean([r["p95"] for r in rows
                                     if r.get("p95") is not None]) if any(
                r.get("p95") is not None for r in rows) else None,
            "p99": statistics.fmean([r["p99"] for r in rows
                                     if r.get("p99") is not None]) if any(
                r.get("p99") is not None for r in rows) else None,
            "p999": statistics.fmean([r["p999"] for r in rows
                                      if r.get("p999") is not None]) if any(
                r.get("p999") is not None for r in rows) else None,
            "lat_max": statistics.fmean([r["max"] for r in rows
                                         if r.get("max") is not None]) if any(
                r.get("max") is not None for r in rows) else None,
            "errors": sum(r.get("errors") or 0 for r in rows),
            "sources": [jsonio.rel(r["path"]) for r in rows],
            "buffer_mode": rows[0].get("buffer_mode"),
            "ring_flags": rows[0].get("ring_flags"),
            "registered_files": rows[0].get("registered_files"),
            "fixed_buffers": rows[0].get("fixed_buffers"),
            "iodepth_batch_submit": rows[0].get("iodepth_batch_submit"),
            "sqpoll": rows[0].get("sqpoll"),
        }
        classify_state(cell)
        cells.append(cell)
    cells.sort(key=lambda c: (
        cell_sort_key(c), ENGINE_ORDER.get(c["engine"], 9),
        VARIANT_ORDER.get(c["variant"], 9), str(c["variant"]), c["bs"] or 0,
        c["qd"] or 0, c["threads"] or 0))
    return cells


def cell_flags(cell):
    bits = []
    if cell["engine"] == "juring":
        bits.append("ring=SI|COOP|DEFER")
        if cell.get("buffer_mode"):
            bits.append(str(cell["buffer_mode"]))
    elif cell["engine"] == "seastar":
        bits.append("seastar-default-ring")
        if cell.get("buffer_mode"):
            bits.append(str(cell["buffer_mode"]))
    elif (cell["engine"] or "").startswith("fio"):
        if cell.get("registered_files"):
            bits.append("regfiles")
        if cell.get("fixed_buffers"):
            bits.append("fixedbufs")
        if cell.get("iodepth_batch_submit") is not None:
            bits.append(f"batch={cell['iodepth_batch_submit']}")
        if cell.get("sqpoll"):
            bits.append("sqpoll")
        if cell["engine"] == "fio-libaio":
            bits.append("libaio")
    return ",".join(bits) if bits else "—"


SATURATION_SPEC = {
    # case -> (bs, qd): the methodology's saturation points (single thread, DS-S)
    "W3": (4096, 64), "W4": (4096, 128),
    "W7": (4096, 64), "W8": (4096, 128),
}
LATENCY_SPEC = {"W15": (4096, 1), "W16": (4096, 64)}


def pick_cell(cells, case, engine=None, variants=None, threads=1, bs=None, qd=None):
    cands = [c for c in cells if c["case"] == case and
             (engine is None or c["engine"] == engine) and
             (threads is None or c["threads"] == threads) and
             (bs is None or c["bs"] == bs) and
             (qd is None or c["qd"] == qd)]
    if variants:
        ordered = []
        for v in variants:
            ordered.extend([c for c in cands if c["variant"] == v])
        if ordered:
            return ordered[0]
        return None
    if not cands:
        return None
    cands.sort(key=lambda c: (VARIANT_ORDER.get(c["variant"], 9), -c["n"]))
    return cands[0]


def gate_juring_cell(cells, case):
    """Preferred JUring cell for a gated case (std variant first, correct bs/qd)."""
    spec = SATURATION_SPEC.get(case) or LATENCY_SPEC.get(case)
    bs, qd = spec if spec else (None, None)
    cell = pick_cell(cells, case, engine="juring", variants=["std"], bs=bs, qd=qd)
    if cell is None:
        cell = pick_cell(cells, case, engine="juring", bs=bs, qd=qd)
    return cell


def gate_fio_matched_cell(cells, case):
    """Registration-matched fio cell for a gated case (correct bs/qd)."""
    spec = SATURATION_SPEC.get(case) or LATENCY_SPEC.get(case)
    bs, qd = spec if spec else (None, None)
    return pick_cell(cells, case, engine="fio-io_uring",
                     variants=list(MATCHED_FIO_VARIANTS), bs=bs, qd=qd)


# ---------------------------------------------------------------------------
# ratios
# ---------------------------------------------------------------------------

def compute_ratios(cells):
    ratios = []
    juring_cells = [c for c in cells if c["engine"] == "juring" and
                    not is_non_throughput_variant(c["variant"])]
    for jc in juring_cells:
        for c in cells:
            if is_non_throughput_variant(c["variant"]):
                continue
            if c is jc:
                continue
            if (c["case"], c["bs"], c["qd"], c["threads"]) != (
                    jc["case"], jc["bs"], jc["qd"], jc["threads"]):
                continue
            if c["engine"] == "juring":
                continue
            if c["engine"] in ("seastar", "seastar-io_tester"):
                kind, matched = "seastar-bracket", False
            elif (c["engine"] == "fio-io_uring" and
                  c["variant"] == WRITE_PRIMARY_VARIANT):
                kind, matched = "fio-matchedbatch", True
            elif c["engine"] == "fio-io_uring" and c["variant"] in MATCHED_FIO_VARIANTS:
                kind, matched = "fio-matched", True
            elif (c["engine"] == "fio-io_uring" and
                  c["variant"] in WRITE_SWEEP_VARIANTS and
                  c["case"] in WRITE_CASE_SPEC):
                # Write-session W7 batching sweep: informational, never a gate
                # comparator. (Read-session W3 batch cells keep their original
                # "fio-unmatched-info" classification so the read aggregate is
                # byte-identical.)
                kind, matched = "fio-batch-sweep", False
            elif (c["engine"] or "").startswith("fio"):
                kind, matched = "fio-unmatched-info", False
            else:
                continue
            st = stats.ratio_stats(jc["reps"], c["reps"])
            st_fast = stats.ratio_stats(jc["fast_reps"], c["fast_reps"])
            left_slow = set(jc["slow_ids"])
            right_slow = set(c["slow_ids"])
            classification_differs = left_slow != right_slow
            note = []
            if jc["unstable"]:
                note.append("left unstable (CV/mixed)")
            if c["unstable"]:
                note.append("right unstable (CV/mixed)")
            if left_slow or right_slow:
                note.append(
                    f"fast subset excludes left [{', '.join(sorted(left_slow)) or '-'}]"
                    f" / right [{', '.join(sorted(right_slow)) or '-'}]")
            if classification_differs:
                note.append("state classification differs between engines")
            if kind == "fio-unmatched-info":
                note.append("fio flags not registration-matched: informational only")
            elif kind == "fio-matched" and c["variant"] == "regfile":
                note.append("registration-matched (--registerfiles=1; fixedbufs "
                            "unavailable at 1 MiB/8 MiB memlock)")
            ratios.append({
                "case": jc["case"], "bs": jc["bs"], "qd": jc["qd"],
                "left": jc, "right": c, "kind": kind, "matched": matched,
                "stats": st, "stats_fast": st_fast,
                "left_slow": sorted(left_slow), "right_slow": sorted(right_slow),
                "classification_differs": classification_differs,
                "note": "; ".join(note),
            })
    ratios.sort(key=lambda r: (cell_sort_key(r["left"]),
                               {"fio-matchedbatch": -1, "fio-matched": 0,
                                "fio-batch-sweep": 1,
                                "fio-unmatched-info": 2,
                                "seastar-bracket": 3}.get(r["kind"], 9),
                               VARIANT_ORDER.get(r["right"]["variant"], 9)))
    return ratios


# ---------------------------------------------------------------------------
# gates
# ---------------------------------------------------------------------------

def _ci_str(st, prefix):
    """'low..high' for a ratio-stats dict prefix in ('ci90','ci95'), or '—'."""
    low = st.get(prefix + "_low")
    high = st.get(prefix + "_high")
    if low is None or high is None:
        return "—"
    return f"{low:.4f}..{high:.4f}"


def _cell_pair_desc(cell):
    """'mean ± 95% CI (n)' for gate table cells."""
    if cell is None:
        return "—"
    if cell["n"] < 2 or cell["ci95_half"] is None:
        return f"{md.num(cell['iops_mean'], 0)} (n={cell['n']}, no CI)"
    return (f"{md.num(cell['iops_mean'], 0)} ± {md.num(cell['ci95_half'], 0)} "
            f"(n={cell['n']})")


def _ratio_line(st):
    if st is None or st.get("mean") is None:
        return "—"
    if st.get("n_pairs", 0) < 2:
        return f"{st['mean']:.4f} (pairs={st['n_pairs']}, no CI)"
    return (f"{st['mean']:.4f} [90% {st['ci90_low']:.4f}..{st['ci90_high']:.4f}; "
            f"95% {st['ci95_low']:.4f}..{st['ci95_high']:.4f}]")


def evaluate_throughput_gate(cells, warnings):
    """Rule 1 gate: read cells W3/W4 plus write cells W7/W8.

    Returns (rows, verdicts, saturation_cells_present, write_gate_detail).
    """
    rows = []
    verdicts = []
    sat_cases = list(SATURATION_CASES_READ) + list(SATURATION_CASES_WRITE)
    present = [c for c in sat_cases if any(x["case"] == c for x in cells)]
    read_cells_present = session_has_read(cells)
    write_cells_present = session_has_write(cells)
    if not present:
        rows.append({
            "rule": "1-throughput", "cells": "W3/W4 (W7/W8)",
            "juring": "—", "comparator": "—", "ratio": "—",
            "threshold": "1s95 LB >= 0.95", "verdict": "INCONCLUSIVE",
            "half_width": "—",
            "note": "no saturation cells in this directory",
        })
        return rows, ["INCONCLUSIVE"], present, None

    # ---- read saturation cells --------------------------------------------
    for case in SATURATION_CASES_READ:
        jc = gate_juring_cell(cells, case)
        fc = gate_fio_matched_cell(cells, case)
        fio_variants = sorted({c["variant"] for c in cells
                               if c["case"] == case and
                               c["engine"] == "fio-io_uring"})
        if jc is None and fc is None:
            if not read_cells_present and write_cells_present:
                # A write session has no read cells: rule 1 for the read path is
                # out of scope here, not failed (Step A is the write half).
                verdict = "N/A"
                note = ("read-path cell not in this write session; write half "
                        "only (M1 read path is recorded in the read session)")
            else:
                verdict = "INCONCLUSIVE"
                note = "case absent from this session"
                verdicts.append(verdict)
            rows.append({
                "rule": "1-throughput", "cells": case, "juring": "—",
                "comparator": "—", "ratio": "—",
                "threshold": "1s95 LB >= 0.95", "verdict": verdict,
                "half_width": "—", "note": note,
            })
            continue
        if jc is None:
            rows.append({
                "rule": "1-throughput", "cells": case, "juring": "—",
                "comparator": "fio-matched", "ratio": "—",
                "threshold": "1s95 LB >= 0.95", "verdict": "INCONCLUSIVE",
                "half_width": "—", "note": "no JUring cell for this case",
            })
            verdicts.append("INCONCLUSIVE")
            continue
        if fc is None:
            rows.append({
                "rule": "1-throughput", "cells": case,
                "juring": _cell_pair_desc(jc), "comparator": "—",
                "ratio": "—", "threshold": "1s95 LB >= 0.95",
                "verdict": "INCONCLUSIVE", "half_width": "—",
                "note": (f"no registration-matched fio cell (variants present: "
                         f"{', '.join(fio_variants) or 'none'}); gate requires "
                         f"--registerfiles=1 --fixedbufs=1 (or --registerfiles=1 "
                         f"alone where fixedbufs cannot fit)"),
            })
            verdicts.append("INCONCLUSIVE")
            continue
        # Primary statistic = all reps (never cherry-picked). Only when a cell
        # is flagged unstable (CV>10 %, mixed state, or >10 % IOPS spread) does
        # the gate fall back to the fast-state subset -- stated explicitly with
        # its CI and the excluded rep ids.
        stat = _paired_gate_stats(jc, fc)
        st_all = stat["st_all"]
        used_subset = stat["used_subset"]
        half = stat["half"]
        detail = stat["detail"]
        if used_subset:
            rule_note = (
                f"all-reps ratio {md.ratio(st_all.get('mean'))} "
                f"[90% {_ci_str(st_all, 'ci90')}]; "
                f"fast-state subset used ({'+'.join(stat['unstable_sides'])} "
                f"unstable: CV>10%/mixed state/spread; all-reps CV "
                f"{md.num(jc['cv_pct'], 1)}%/{md.num(fc['cv_pct'], 1)}%, "
                f"slow JUring [{', '.join(jc['slow_ids']) or '-'}], "
                f"slow fio [{', '.join(fc['slow_ids']) or '-'}], "
                f"classification differs: "
                f"{'YES' if set(jc['slow_ids']) != set(fc['slow_ids']) else 'no'}); ")
        else:
            rule_note = (
                f"all-reps primary (no instability: CV "
                f"{md.num(jc['cv_pct'], 1)}%/{md.num(fc['cv_pct'], 1)}%, "
                f"slow reps JUring [{', '.join(jc['slow_ids']) or '-'}] / "
                f"fio [{', '.join(fc['slow_ids']) or '-'}]); ")
        ratio_text = _ratio_line(st_all)
        if used_subset:
            ratio_text += " | fast subset: " + _ratio_line(stat["st_fast"])
        rows.append({
            "rule": "1-throughput", "cells": case,
            "juring": _cell_pair_desc(jc),
            "comparator": _cell_pair_desc(fc),
            "ratio": ratio_text,
            "threshold": "1s95 LB >= 0.95", "verdict": stat["verdict"],
            "half_width": half, "note": rule_note + detail,
        })
        verdicts.append(stat["verdict"])

    # ---- write saturation cells: both comparators --------------------------
    write_detail = None
    if write_cells_present:
        write_rows, write_verdicts, comparators, case_verdicts = \
            evaluate_write_gate(cells)
        rows.extend(write_rows)
        verdicts.extend(write_verdicts)
        write_detail = {"rows": write_rows, "comparators": comparators,
                        "case_verdicts": case_verdicts,
                        "verdict": stats.combine_verdicts(write_verdicts),
                        "gated_cases_present": [
                            c for c in WRITE_GATE_CASES
                            if any(x["case"] == c for x in cells)]}
    else:
        for case in SATURATION_CASES_WRITE:
            rows.append({
                "rule": "1-throughput", "cells": case, "juring": "—",
                "comparator": "—", "ratio": "—",
                "threshold": "1s95 LB >= 0.95", "verdict": "N/A",
                "half_width": "—",
                "note": ("no write cells in session (read-only dataset); write "
                         "half of M1 not assessed"),
            })
    return rows, verdicts, present, write_detail


def _perf_group(perf_records, case, engine, bs, qd):
    return [r for r in perf_records
            if r.get("case") == case and r.get("engine") == engine and
            (bs is None or r.get("bs") == bs) and (qd is None or r.get("qd") == qd) and
            r.get("cycles_per_op") is not None]


def _cell_for_perf(cells, rec):
    for cell in cells:
        if (cell["case"] == rec.get("case") and cell["engine"] == rec.get("engine") and
                cell["variant"] == rec.get("variant") and
                (rec.get("bs") is None or cell["bs"] == rec.get("bs")) and
                (rec.get("qd") is None or cell["qd"] == rec.get("qd")) and
                cell["threads"] == 1):
            return cell
    return None


def _prefer_fio_perf_group(cells, recs):
    """Prefer a registration-matched fio perf capture group, then `perf`."""
    if not recs:
        return []
    groups = {}
    for rec in recs:
        groups.setdefault((rec.get("variant"), rec.get("bs"), rec.get("qd")), []).append(rec)
    matched, other = [], []
    for _key, group in groups.items():
        cell = _cell_for_perf(cells, group[0])
        if cell is not None and cell.get("registered_files") and cell.get("fixed_buffers"):
            matched.append(group)
        else:
            other.append(group)
    pool = matched or other
    pool.sort(key=lambda g: (VARIANT_ORDER.get(g[0].get("variant"), 9), -len(g)))
    return pool[0]


def _tsv_series(perf_tsv_rows, case, engine, variants, qd):
    for variant in variants:
        series = [row for row in perf_tsv_rows
                  if row.get("case") == case and row.get("engine") == engine and
                  row.get("qd") == qd and row.get("variant") == variant and
                  row.get("cycles_per_op") is not None]
        if series:
            return series, variant
    return [], None


def _mean_cycles(rows):
    vals = [r["cycles_per_op"] for r in rows if r.get("cycles_per_op") is not None]
    return statistics.fmean(vals) if vals else None


def evaluate_cpu_gate(cells, perf_records, warnings, perf_tsv_rows=None):
    rows = []
    verdicts = []
    perf_tsv_rows = perf_tsv_rows or []
    if not perf_tsv_rows and not perf_records:
        rows.append({
            "rule": "2-cpu", "cells": "W3", "juring": "—",
            "comparator": "—",
            "ratio": "—", "threshold": "95% UB of ratio <= 1.15",
            "verdict": "INCONCLUSIVE", "half_width": "—",
            "note": "no perf captures / perf-cells.tsv rows",
        })
        return rows, ["INCONCLUSIVE"]
    gate_cases = [c for c in SATURATION_CASES_READ if any(x["case"] == c for x in cells)]
    for case in gate_cases:
        spec = SATURATION_SPEC.get(case) or (None, None)
        bs, qd = spec
        if perf_tsv_rows:
            jrows, jvar = _tsv_series(perf_tsv_rows, case, "juring", ["std", "perf"], qd)
            frows, fvar = _tsv_series(perf_tsv_rows, case, "fio-io_uring",
                                      ["perf-matched", "matched"], qd)
            fdraws, _ = _tsv_series(perf_tsv_rows, case, "fio-io_uring", ["perf"], qd)
            srows, _ = _tsv_series(perf_tsv_rows, case, "seastar", ["std"], qd)
            provenance = "perf-cells.tsv (raw captures cross-checked)"
        else:
            jrows = _perf_group(perf_records, case, "juring", bs, qd)
            frows = _prefer_fio_perf_group(
                cells, _perf_group(perf_records, case, "fio-io_uring", bs, qd))
            fdraws, srows = [], []
            provenance = "raw perf captures"
        if not jrows and not frows:
            rows.append({
                "rule": "2-cpu", "cells": case, "juring": "—",
                "comparator": "—", "ratio": "—",
                "threshold": "95% UB of ratio <= 1.15",
                "verdict": "N/A", "half_width": "—",
                "note": (f"no perf capture for {case}: the perf phase covered "
                         f"QD1+QD64 only; the CPU gate is evaluated at the "
                         f"saturation QD (W3/QD64) as in the session notes"),
            })
            continue
        jmap = {r.get("rep"): r["cycles_per_op"] for r in jrows
                if r.get("rep") is not None and r.get("cycles_per_op") is not None}
        fmap = {r.get("rep"): r["cycles_per_op"] for r in frows
                if r.get("rep") is not None and r.get("cycles_per_op") is not None}
        common = sorted(set(jmap) & set(fmap))
        if common:
            st = stats.ratio_stats(
                [make_rep(rep_index=r, iops=jmap[r]) for r in common],
                [make_rep(rep_index=r, iops=fmap[r]) for r in common])
            j_d = stats.describe([jmap[r] for r in common])
            f_d = stats.describe([fmap[r] for r in common])
        else:
            j_d = stats.describe([r["cycles_per_op"] for r in jrows])
            f_d = stats.describe([r["cycles_per_op"] for r in frows])
            st = None
        if j_d["n"] == 0 or f_d["n"] == 0:
            rows.append({
                "rule": "2-cpu", "cells": case, "juring": "—",
                "comparator": "—", "ratio": "—",
                "threshold": "95% UB of ratio <= 1.15",
                "verdict": "INCONCLUSIVE", "half_width": "—",
                "note": "perf rows missing on one side",
            })
            verdicts.append("INCONCLUSIVE")
            continue
        verdict, detail = stats.noninferior_verdict(st, CPU_NONINFERIOR_LIMIT)
        strict_v, strict_d = stats.band_verdict(st, CPU_BAND)
        half = "—"
        if st is not None and st.get("ci95_half") is not None:
            half = f"±{st['ci95_half']:.4f} ({st['ci95_half_pct']:.1f}% of mean)"
        seastar_mean = _mean_cycles(srows)
        fd_mean = _mean_cycles(fdraws)
        bracket_bits = []
        if seastar_mean and j_d["mean"]:
            bracket_bits.append(f"seastar bracket {md.num(seastar_mean, 0)} cyc/op "
                                f"(JUring/seastar {md.ratio(j_d['mean'] / seastar_mean)})")
        if fd_mean and j_d["mean"]:
            bracket_bits.append(f"fio default bracket {md.num(fd_mean, 0)} cyc/op "
                                f"(JUring/fio-default {md.ratio(j_d['mean'] / fd_mean)})")
        w1 = [r for r in perf_tsv_rows if r.get("case") == "W1" and
              r.get("engine") == "juring" and r.get("qd") == 1 and
              r.get("cycles_per_op") is not None]
        w1s = [r for r in perf_tsv_rows if r.get("case") == "W1" and
               r.get("engine") == "seastar" and r.get("qd") == 1 and
               r.get("cycles_per_op") is not None]
        qd1_bits = ""
        if w1 and w1s:
            qd1_bits = (f" QD1 context: JUring {md.num(_mean_cycles(w1), 0)} vs "
                        f"seastar {md.num(_mean_cycles(w1s), 0)} cyc/op (seastar "
                        f"busy-polls the reactor; QD1 CPU is reported as a "
                        f"caveat, the gate is at QD64).")
        note = (f"non-inferiority: JUring cycles/op must be <= "
                f"{CPU_NONINFERIOR_LIMIT:.2f}x fio-matched. Strict two-sided "
                f"±15% band: {strict_v} ({strict_d}). Comparator fio variant "
                f"`{fvar}` via {provenance}.")
        if fvar == "perf-matched":
            note += (" fio-matched raw capture survives under the `__perf__` "
                     "stem (id collision documented in notes.md).")
        if bracket_bits:
            note += " " + "; ".join(bracket_bits) + "."
        note += qd1_bits
        rows.append({
            "rule": "2-cpu", "cells": case,
            "juring": f"{md.ci(j_d['mean'], j_d['ci95_half'], 1)} cyc/op (n={j_d['n']})",
            "comparator": f"{md.ci(f_d['mean'], f_d['ci95_half'], 1)} cyc/op (n={f_d['n']})",
            "ratio": md.ratio(st.get("mean") if st else None) +
                     f" (strict band: {strict_v})",
            "threshold": "95% UB of ratio <= 1.15 (non-inferiority)",
            "verdict": verdict, "half_width": half, "note": note,
        })
        verdicts.append(verdict)
    if not rows:
        rows.append({
            "rule": "2-cpu", "cells": "W3", "juring": "—",
            "comparator": "—", "ratio": "—",
            "threshold": "95% UB of ratio <= 1.15",
            "verdict": "INCONCLUSIVE", "half_width": "—",
            "note": "no saturation cells available for the CPU gate",
        })
        verdicts.append("INCONCLUSIVE")
    return rows, verdicts


def evaluate_tail_gate(cells, perf_records=None):
    rows = []
    verdicts = []
    for case in LATENCY_CASES:
        jc = gate_juring_cell(cells, case)
        fc = gate_fio_matched_cell(cells, case)
        if jc is None or fc is None:
            rows.append({
                "rule": "3-tail", "cells": case,
                "juring": _cell_pair_desc(jc),
                "comparator": _cell_pair_desc(fc),
                "ratio": "—", "threshold": "p99 <= 2x fio p99",
                "verdict": "INCONCLUSIVE", "half_width": "—",
                "note": ("JUring cell absent" if jc is None else
                         "no registration-matched fio cell for this case"),
            })
            verdicts.append("INCONCLUSIVE")
            continue
        def tail_stats(left_reps, right_reps, field):
            return stats.ratio_stats(
                [make_rep(rep_index=r["rep_index"], iops=r.get(field))
                 for r in left_reps],
                [make_rep(rep_index=r["rep_index"], iops=r.get(field))
                 for r in right_reps])

        st99_all = tail_stats(jc["reps"], fc["reps"], "p99")
        st999_all = tail_stats(jc["reps"], fc["reps"], "p999")
        st99_fast = tail_stats(jc["fast_reps"], fc["fast_reps"], "p99")
        st999_fast = tail_stats(jc["fast_reps"], fc["fast_reps"], "p999")
        unstable_sides = []
        if jc["unstable"]:
            unstable_sides.append("JUring")
        if fc["unstable"]:
            unstable_sides.append("fio")
        used_subset = bool(unstable_sides)
        st99 = st99_fast if used_subset else st99_all
        st999 = st999_fast if used_subset else st999_all
        verdict, detail = stats.upper_bound_verdict(st99, TAIL_FACTOR)
        p99_parity, d99 = stats.upper_bound_verdict(st99, P99_PARITY)
        p999_parity, d999 = stats.upper_bound_verdict(st999, P999_PARITY)
        half = "—"
        if st99.get("ci95_half") is not None:
            half = (f"p99 ratio ±{st99['ci95_half']:.4f} "
                    f"({st99['ci95_half_pct']:.1f}% of mean)")
        note = (f"p99 parity bar 1.10x: {p99_parity} ({d99}); "
                f"p999 parity bar 1.15x: {p999_parity} ({d999})")
        if detail:
            note = detail + "; " + note
        if used_subset:
            note = (f"fast-state subset used ({'+'.join(unstable_sides)} unstable); "
                    f"all-reps p99 ratio {md.ratio(st99_all.get('mean'))}, "
                    f"slow JUring [{', '.join(jc['slow_ids']) or '-'}] / fio "
                    f"[{', '.join(fc['slow_ids']) or '-'}]; " + note)
        else:
            note = ("all-reps primary; " + note)
        ratio_text = f"p99 all {_ratio_line(st99_all)}; p999 all mean {md.ratio(st999_all['mean'])}"
        if used_subset:
            ratio_text += f" | fast p99 {_ratio_line(st99_fast)}"
        rows.append({
            "rule": "3-tail", "cells": case,
            "juring": f"p99 {md.num(jc['p99'], 1)} us / p999 {md.num(jc['p999'], 1)} us (n={jc['n']}, {jc['n_fast']} fast)",
            "comparator": f"p99 {md.num(fc['p99'], 1)} us / p999 {md.num(fc['p999'], 1)} us (n={fc['n']}, {fc['n_fast']} fast)",
            "ratio": ratio_text,
            "threshold": "p99 <= 2x fio p99",
            "verdict": verdict, "half_width": half, "note": note,
        })
        verdicts.append(verdict)
    if not rows:
        rows.append({
            "rule": "3-tail", "cells": "W15/W16", "juring": "—",
            "comparator": "—", "ratio": "—",
            "threshold": "p99 <= 2x fio p99", "verdict": "INCONCLUSIVE",
            "half_width": "—",
            "note": "W15/W16 latency cells absent from this session",
        })
        verdicts.append("INCONCLUSIVE")
    return rows, verdicts


def evaluate_seastar_bracket(cells):
    rows = []
    covered = []
    for jc in [c for c in cells if c["engine"] == "juring" and
               not is_non_throughput_variant(c["variant"])]:
        for sc in cells:
            if sc is jc or sc["engine"] not in ("seastar", "seastar-io_tester"):
                continue
            if is_non_throughput_variant(sc["variant"]):
                continue
            if (sc["case"], sc["bs"], sc["qd"], sc["threads"]) != (
                    jc["case"], jc["bs"], jc["qd"], jc["threads"]):
                continue
            st = stats.ratio_stats(jc["reps"], sc["reps"])
            rows.append({
                "case": jc["case"], "qd": jc["qd"], "left": jc, "right": sc,
                "stats": st,
            })
            covered.append(jc["case"])
    rows.sort(key=lambda r: cell_sort_key(r["left"]))
    return rows, sorted(set(covered), key=lambda s: cell_sort_key({"case": s}))


def evaluate_ci_rule(cells, cited_cells):
    missing = []
    seen = set()
    for cell in cited_cells:
        if cell is None:
            continue
        key = (cell["case"], cell["engine"], cell["variant"], cell["bs"],
               cell["qd"], cell["threads"])
        if key in seen:
            continue
        seen.add(key)
        if cell["n"] < 2 or cell["ci95_half"] is None:
            missing.append(f"{cell['case']}/{cell['engine']}/{cell['variant']} "
                           f"(n={cell['n']})")
    row = {
        "rule": "5-ci", "cells": f"{len(seen)} cited cells",
        "juring": "—", "comparator": "—", "ratio": "—",
        "threshold": "every cited number has a 95% CI",
        "verdict": "PASS" if not missing else "INCONCLUSIVE",
        "half_width": "—",
        "note": ("all cited cells have n>=2 and a 95% CI" if not missing else
                 "missing CI on: " + ", ".join(missing) +
                 " — inconclusive, more reps needed"),
    }
    return [row]


# ---------------------------------------------------------------------------
# cell-meta.tsv cross-check
# ---------------------------------------------------------------------------

def cross_check_meta(session_dir, reps, perf_tsv_rows=None, meta_p99_units=None):
    """Compare parsed JSON with cell-meta.tsv, tolerating documented collisions.

    cell-meta.tsv has no phase column, so perf-phase rows key-collide with the read
    matrix and are excluded as ambiguous rather than reported as errors;
    `meta_p99_units='us'` skips the legacy fio ns->us normalisation.
    """
    path = session_dir / "cell-meta.tsv"
    if not path.is_file():
        return None
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as fh:
            all_rows = list(csv.DictReader(fh, delimiter="\t"))
    except Exception:
        return None
    by_key = {}
    for row in all_rows:
        if (row.get("status") or "").strip() not in ("", "ok"):
            continue
        key = ((row.get("case") or "").strip(), (row.get("engine") or "").strip(),
               (row.get("variant") or "").strip(), (row.get("bs") or "").strip(),
               (row.get("qd") or "").strip(), (row.get("rep") or "").strip())
        by_key.setdefault(key, []).append(row)
    index = {}
    for rep in reps:
        try:
            rep_no = int(str(rep["rep"]).split(".")[0].lstrip("r"))
        except ValueError:
            rep_no = 1
        index[(rep["case"], rep["engine"], rep["variant"],
               parse_size(rep["bs"]), rep["qd"], rep_no)] = rep
    perf_keys = set()
    for row in (perf_tsv_rows or []):
        perf_keys.add((row.get("case"), row.get("engine"), row.get("qd"),
                       row.get("rep")))
    checked = 0
    max_iops = 0.0
    max_p99 = 0.0
    missing = []
    mismatches = []
    ambiguous = []
    collision_missing = []
    byte_rows = []
    unit_normalized_p99 = []
    confirmed_us_p99 = 0
    legacy_ns_p99 = 0
    for key, rows in by_key.items():
        case, engine, variant, bs_txt, qd_txt, rep_txt = key
        bs = parse_size(bs_txt)
        qd = jsonio.as_int(qd_txt)
        rep_no = jsonio.as_int(rep_txt)
        parsed_key = (case, engine, variant, bs, qd, rep_no)
        rec = index.get(parsed_key)
        if len(rows) > 1:
            if rec is not None:
                ambiguous.append(f"{case}/{engine}/{variant}/bs{bs}/qd{qd}/r{rep_no}")
            continue
        row = rows[0]
        if rec is None:
            if (case, engine, qd, rep_no) in perf_keys:
                collision_missing.append(
                    f"{case}/{engine}/{variant}/bs{bs}/qd{qd}/r{rep_no}")
            else:
                missing.append(f"{case}/{engine}/{variant}/bs{bs}/qd{qd}/r{rep_no}")
            continue
        if (engine == "fio-io_uring" and variant == "perf" and
                (case, engine, qd, rep_no) in perf_keys):
            # fio `perf` (default) meta row vs the JSON that survives under the
            # same stem (the matched run, documented id collision): the values
            # differ by design, and perf-cells.tsv holds both computed rows.
            ambiguous.append(f"{case}/{engine}/{variant}/bs{bs}/qd{qd}/r{rep_no} "
                             f"(fio perf stem collision; values from perf-cells.tsv)")
            continue
        checked += 1
        iops_meta = jsonio.as_float(row.get("iops"))
        wall = jsonio.as_float(row.get("wall_s"))
        if iops_meta and wall and iops_meta > 0 and wall > 0:
            expected_sectors = iops_meta * wall * (bs or 0) / 512
            read_sectors = jsonio.as_int((row.get("read_sectors_delta") or "").strip())
            write_sectors = jsonio.as_int((row.get("write_sectors_delta") or "").strip())
            is_write = (rec["workload"] in ("randwrite", "seqwrite") or
                        (write_sectors is not None and read_sectors is not None and
                         write_sectors > read_sectors))
            actual = write_sectors if is_write else read_sectors
            if expected_sectors > 0 and actual is not None:
                ratio = actual / expected_sectors
                byte_rows.append({
                    "case": case, "engine": engine, "variant": variant,
                    "qd": qd, "rep": rep_no,
                    "direction": "write" if is_write else "read",
                    "actual_sectors": actual,
                    "expected_sectors": expected_sectors,
                    "ratio": ratio,
                    "verdict": "PASS" if 0.90 <= ratio <= 1.10 else "WARN",
                })
        if iops_meta and rec.get("iops"):
            diff = abs(rec["iops"] - iops_meta) / iops_meta
            max_iops = max(max_iops, diff)
            if diff > 0.005:
                mismatches.append(f"{case}/{engine}/{variant}/r{rep_no} IOPS "
                                  f"json {rec['iops']:.1f} vs meta {iops_meta:.1f}")
        p99_meta = jsonio.as_float(row.get("p99_us"))
        if p99_meta and rec.get("p99"):
            # Session-11 write harness: the manifest declares
            # cell_meta_p99_units=us, so the cell-meta column is already
            # microseconds for EVERY engine -- no conversion. Legacy 2026-11-13
            # write block: fio rows carry fio clat in NANOSECONDS (e.g. 2211840
            # where the truth is 2211.84 us); normalise ns->us for the
            # CROSS-CHECK only. Cited values always come from the raw JSON (the
            # analyzer never cites the cell-meta p99 column).
            unit_normalized = None
            if meta_p99_units == "us":
                confirmed_us_p99 += 1
            elif (engine.startswith("fio") and p99_meta > 100.0 * rec["p99"]
                    and p99_meta > 100000.0):
                unit_normalized = p99_meta / 1000.0
                legacy_ns_p99 += 1
            meta_for_check = unit_normalized if unit_normalized else p99_meta
            diff = abs(rec["p99"] - meta_for_check) / meta_for_check
            max_p99 = max(max_p99, diff)
            if unit_normalized:
                unit_normalized_p99.append(
                    f"{case}/{engine}/{variant}/r{rep_no}")
            if diff > 0.005:
                mismatches.append(f"{case}/{engine}/{variant}/r{rep_no} p99 "
                                  f"json {rec['p99']:.2f} vs meta "
                                  f"{meta_for_check:.2f}")
                if unit_normalized:
                    mismatches[-1] += " (meta normalised from ns)"
    return {
        "path": path, "n_rows": len(all_rows),
        "n_cells": len(index), "n_checked": checked,
        "max_iops_rel_diff": max_iops, "max_p99_rel_diff": max_p99,
        "missing": missing, "mismatches": mismatches,
        "ambiguous": ambiguous, "collision_missing": collision_missing,
        "unit_normalized_p99": unit_normalized_p99,
        "meta_p99_units": meta_p99_units,
        "confirmed_us_p99": confirmed_us_p99,
        "legacy_ns_p99": legacy_ns_p99,
        "byte_rows": byte_rows,
        "n_byte_warn": sum(1 for b in byte_rows if b["verdict"] != "PASS"),
    }


def cross_check_perf_tsv(session_dir, perf_records, perf_tsv_rows=None):
    """Cross-check the raw-capture numbers against perf-cells.tsv.

    fio `perf` (default) rows are computed-only (raw capture overwritten by the
    documented stem collision) and are skipped from the comparison.
    """
    path = session_dir / "perf-cells.tsv"
    if not path.is_file():
        return None
    if not perf_tsv_rows:
        perf_tsv_rows = load_perf_cells_tsv(session_dir, [])
    if not perf_tsv_rows:
        return None
    raw_index = {}
    for rec in perf_records:
        if rec.get("cycles_per_op") is None or rec.get("case") is None:
            continue
        raw_index[(rec["case"], rec["engine"], rec.get("qd"),
                   rec.get("rep") if rec.get("rep") is not None else 1)] = rec
    checked = 0
    skipped_tsv_only = 0
    missing = []
    max_rel = 0.0
    mismatches = []
    for row in perf_tsv_rows:
        tsv = row.get("cycles_per_op")
        if tsv is None or tsv <= 0:
            continue
        if row.get("engine") == "fio-io_uring" and row.get("variant") == "perf":
            skipped_tsv_only += 1
            continue
        raw = raw_index.get((row["case"], row["engine"], row["qd"], row["rep"]))
        if raw is None:
            missing.append(f"{row['case']}/{row['engine']}/{row['variant']}/"
                           f"qd{row['qd']}/r{row['rep']}")
            continue
        checked += 1
        rel_diff = abs(raw["cycles_per_op"] - tsv) / tsv
        max_rel = max(max_rel, rel_diff)
        # 2% tolerance: the fio raw capture under the `__perf__` stem belongs to
        # the matched run (documented id collision), so a small systematic
        # difference vs the table row is expected; > 2% is a real mismatch.
        if rel_diff > 0.02:
            mismatches.append(
                f"{row['case']}/{row['engine']}/{row['variant']}/r{row['rep']} "
                f"cycles/op raw {raw['cycles_per_op']:.1f} vs table {tsv:.1f}")
    return {"path": path, "n_rows": len(perf_tsv_rows), "n_checked": checked,
            "max_rel_diff": max_rel, "missing": missing,
            "mismatches": mismatches, "n_tsv_only": skipped_tsv_only}


def load_iotester_check(session_dir, warnings):
    """Read the harness's iotester-check.tsv (last ok row per cell wins)."""
    path = session_dir / "iotester-check.tsv"
    if not path.is_file():
        return None
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as fh:
            all_rows = list(csv.DictReader(fh, delimiter="\t"))
    except Exception as exc:
        warnings.append(f"{jsonio.rel(path)}: unreadable iotester-check.tsv ({exc})")
        return None
    last = {}
    for row in all_rows:
        if (row.get("status") or "").strip() not in ("", "ok"):
            continue
        key = ((row.get("case") or "").strip(),
               (row.get("engine") or "").strip(),
               (row.get("variant") or "").strip(),
               jsonio.as_int((row.get("qd") or "").strip()),
               jsonio.as_int((row.get("rep") or "").strip()))
        last[key] = {
            "case": key[0], "engine": key[1], "variant": key[2],
            "qd": key[3], "rep": key[4],
            "iops": jsonio.as_float(row.get("iops")),
            "throughput_kbs": jsonio.as_float(row.get("throughput_kbs")),
            "p50": jsonio.as_float(row.get("p50_us")),
            "p99": jsonio.as_float(row.get("p99_us")),
            "p999": jsonio.as_float(row.get("p999_us")),
            "max": jsonio.as_float(row.get("max_us")),
            "wall_s": jsonio.as_float(row.get("wall_s")),
            "bs": parse_size((row.get("bs") or "").strip()) if row.get("bs") else None,
        }
    return {"path": path, "rows": list(last.values()), "n_rows": len(all_rows)}


def cross_check_iotester(iotester, iot_check):
    """Compare parsed io_tester YAML with the harness iotester-check.tsv."""
    if not iot_check or not iotester:
        return None
    tsv = {}
    for row in iot_check["rows"]:
        if row["engine"] != "seastar-io_tester":
            continue
        tsv[(row["case"], row["variant"], row["qd"], row["rep"])] = row
    checked = 0
    max_iops = 0.0
    max_p99 = 0.0
    mismatches = []
    for it in iotester:
        key = it["key"]
        rep = int(str(key.get("rep", 1)).split(".")[0]) if key.get("rep") else 1
        row = tsv.get((key["case"], key["variant"], key["qd"], rep))
        if row is None:
            continue
        checked += 1
        if row.get("iops") and it.get("iops"):
            diff = abs(it["iops"] - row["iops"]) / row["iops"]
            max_iops = max(max_iops, diff)
            if diff > 0.005:
                mismatches.append(
                    f"{key['case']}/{key['variant']}/qd{key['qd']}/r{rep} IOPS "
                    f"yaml {it['iops']:.2f} vs tsv {row['iops']:.2f}")
        if row.get("p99") and row["p99"] > 0 and it.get("p99"):
            diff = abs(it["p99"] - row["p99"]) / row["p99"]
            max_p99 = max(max_p99, diff)
            if diff > 0.005:
                mismatches.append(
                    f"{key['case']}/{key['variant']}/qd{key['qd']}/r{rep} p99 "
                    f"yaml {it['p99']:.2f} vs tsv {row['p99']:.2f}")
    return {"path": iot_check["path"], "n_checked": checked,
            "max_iops_rel_diff": max_iops, "max_p99_rel_diff": max_p99,
            "mismatches": mismatches}


# ---------------------------------------------------------------------------
# write-session analysis: rule-1 gate (both comparators), D2 device series
# ---------------------------------------------------------------------------

def workload_is_write(workload):
    return workload in ("randwrite", "seqwrite")


def session_has_write(cells):
    """True when the session contains any write throughput cell."""
    return any(workload_is_write(c["workload"]) for c in cells)


def session_has_read(cells):
    """True when the session contains any read throughput cell."""
    return any(c["workload"] in ("randread", "seqread") for c in cells)


def _write_cell_spec(case):
    return WRITE_CASE_SPEC.get(case)


def write_juring_cell(cells, case):
    """JUring std cell for a write case at the methodology's bs/QD."""
    spec = _write_cell_spec(case)
    if not spec:
        return None
    _workload, bs, qd = spec
    cell = pick_cell(cells, case, engine="juring", variants=["std"], bs=bs, qd=qd,
                     threads=1)
    if cell is None:
        cell = pick_cell(cells, case, engine="juring", bs=bs, qd=qd, threads=1)
    return cell


def write_fio_cell(cells, case, variant):
    """fio-io_uring cell for one write comparator variant at bs/QD."""
    spec = _write_cell_spec(case)
    if not spec:
        return None
    _workload, bs, qd = spec
    return pick_cell(cells, case, engine="fio-io_uring", variants=[variant],
                     bs=bs, qd=qd, threads=1)


def _paired_gate_stats(jc, fc):
    """Paired ratio statistics + the unstable-cell gate policy.

    All reps are primary; the fast-state subset is used only for a cell flagged
    unstable (CV>10%, mixed state or >10% IOPS spread), disclosed by rep id.
    """
    st_all = stats.ratio_stats(jc["reps"], fc["reps"])
    st_fast = stats.ratio_stats(jc["fast_reps"], fc["fast_reps"])
    unstable_sides = []
    if jc["unstable"]:
        unstable_sides.append("JUring")
    if fc["unstable"]:
        unstable_sides.append("fio")
    used_subset = bool(unstable_sides)
    st = st_fast if used_subset else st_all
    if used_subset and st.get("n_pairs", 0) < 2:
        verdict = "INCONCLUSIVE"
        detail = (f"fast-state subset has n_pairs={st.get('n_pairs', 0)} "
                  f"(need >=2); primary all-reps ratio "
                  f"{md.ratio(st_all.get('mean'))} is on an unstable cell")
    else:
        verdict, detail = stats.lower_bound_verdict(st, THROUGHPUT_LB)
    half = "—"
    if st.get("ci95_half") is not None:
        half = f"±{st['ci95_half']:.4f} ({st['ci95_half_pct']:.1f}% of mean)"
    note = []
    if used_subset:
        note.append(
            f"fast-state subset used ({'+'.join(unstable_sides)} unstable: "
            f"CV>10%/mixed state/spread; all-reps CV "
            f"{md.num(jc['cv_pct'], 1)}%/{md.num(fc['cv_pct'], 1)}%, slow JUring "
            f"[{', '.join(jc['slow_ids']) or '-'}], slow fio "
            f"[{', '.join(fc['slow_ids']) or '-'}], classification differs: "
            f"{'YES' if set(jc['slow_ids']) != set(fc['slow_ids']) else 'no'})")
    else:
        note.append(
            f"all-reps primary (CV {md.num(jc['cv_pct'], 1)}%/"
            f"{md.num(fc['cv_pct'], 1)}%, slow reps JUring "
            f"[{', '.join(jc['slow_ids']) or '-'}] / fio "
            f"[{', '.join(fc['slow_ids']) or '-'}])")
    if st.get("lb95_1s") is not None:
        note.append(f"1s95 LB {st['lb95_1s']:.4f} vs {THROUGHPUT_LB}")
    note.append(detail)
    return {
        "st_all": st_all, "st_fast": st_fast, "st": st,
        "used_subset": used_subset, "unstable_sides": unstable_sides,
        "verdict": verdict, "detail": detail, "half": half,
        "note": "; ".join(n for n in note if n),
    }


def evaluate_write_gate(cells):
    """M1 write rule 1: paired JUring/fio at W7/W8 vs BOTH fio comparators.

    PASS needs the one-sided 95% LB >= 0.95 against both; FAIL if either 95% UB is
    below; returns (rows, verdicts, comparators, case_verdicts).
    """
    rows = []
    verdicts = []
    comparators = []
    case_verdicts = {}
    for case in WRITE_GATE_CASES:
        jc = write_juring_cell(cells, case)
        fio_variants = sorted({c["variant"] for c in cells
                               if c["case"] == case and
                               c["engine"] == "fio-io_uring"})
        if jc is None and not any(write_fio_cell(cells, case, v)
                                  for v in (WRITE_PRIMARY_VARIANT,
                                            WRITE_SENSITIVITY_VARIANT)):
            rows.append({
                "rule": "1-throughput", "cells": case, "juring": "—",
                "comparator": "—", "ratio": "—",
                "threshold": "1s95 LB >= 0.95 (both comparators)",
                "verdict": "INCONCLUSIVE", "half_width": "—",
                "note": ("case absent from this write session; write rule 1 "
                         "needs both W7 and W8 with both fio comparators"),
            })
            verdicts.append("INCONCLUSIVE")
            case_verdicts[case] = "INCONCLUSIVE"
            continue
        case_v = []
        for variant, role in ((WRITE_PRIMARY_VARIANT, "primary"),
                              (WRITE_SENSITIVITY_VARIANT, "sensitivity")):
            fc = write_fio_cell(cells, case, variant)
            comparator_cell = (
                f"{_cell_pair_desc(fc)} [fio {variant} ({role})]"
                if fc is not None else f"— (fio {variant} ({role}) absent)")
            if jc is None:
                rows.append({
                    "rule": "1-throughput", "cells": case, "juring": "—",
                    "comparator": comparator_cell, "ratio": "—",
                    "threshold": "1s95 LB >= 0.95 (both comparators)",
                    "verdict": "INCONCLUSIVE", "half_width": "—",
                    "note": f"no JUring cell for {case}; cannot evaluate",
                })
                verdicts.append("INCONCLUSIVE")
                case_v.append("INCONCLUSIVE")
                continue
            if fc is None:
                rows.append({
                    "rule": "1-throughput", "cells": case,
                    "juring": _cell_pair_desc(jc),
                    "comparator": comparator_cell, "ratio": "—",
                    "threshold": "1s95 LB >= 0.95 (both comparators)",
                    "verdict": "INCONCLUSIVE", "half_width": "—",
                    "note": (f"no fio `{variant}` comparator cell (variants "
                             f"present: {', '.join(fio_variants) or 'none'}); "
                             f"write rule 1 requires BOTH matchedbatch and "
                             f"matched — cannot PASS"),
                })
                verdicts.append("INCONCLUSIVE")
                case_v.append("INCONCLUSIVE")
                continue
            stat = _paired_gate_stats(jc, fc)
            ratio_text = _ratio_line(stat["st_all"])
            if stat["used_subset"]:
                ratio_text += " | fast subset: " + _ratio_line(stat["st_fast"])
            note = (f"write rule 1 vs {role} comparator fio/{variant}; both "
                    f"comparators required (PASS iff both 1s95 LBs >= "
                    f"{THROUGHPUT_LB}, FAIL iff either 95% UB < "
                    f"{THROUGHPUT_LB}); " + stat["note"])
            rows.append({
                "rule": "1-throughput", "cells": case,
                "juring": _cell_pair_desc(jc),
                "comparator": comparator_cell,
                "ratio": ratio_text,
                "threshold": "1s95 LB >= 0.95 (both comparators)",
                "verdict": stat["verdict"], "half_width": stat["half"],
                "note": note,
            })
            verdicts.append(stat["verdict"])
            case_v.append(stat["verdict"])
            comparators.append({
                "case": case, "comparator_role": role,
                "comparator_variant": variant,
                "juring": jc, "fio": fc,
                "stats": stat["st"], "stats_all": stat["st_all"],
                "stats_fast": stat["st_fast"],
                "used_subset": stat["used_subset"],
                "unstable_sides": stat["unstable_sides"],
                "excluded_juring": list(jc["slow_ids"]),
                "excluded_fio": list(fc["slow_ids"]),
                "verdict": stat["verdict"], "half": stat["half"],
                "note": stat["note"], "detail": stat["detail"],
            })
        case_verdicts[case] = stats.combine_verdicts(case_v)
    return rows, verdicts, comparators, case_verdicts


# --- D2 device 1 s series / write amplification ----------------------------

def load_runs_tsv(session_dir, warnings):
    """`state/runs.tsv` -> {(case, engine, variant, bs_bytes, qd, rep): row}.

    Last row wins (a resumed/re-run cell may append a row).
    """
    path = session_dir / "state" / "runs.tsv"
    if not path.is_file():
        return {}, path
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as fh:
            raw_rows = list(csv.DictReader(fh, delimiter="\t"))
    except Exception as exc:
        warnings.append(f"{jsonio.rel(path)}: unreadable state/runs.tsv ({exc})")
        return {}, path
    rows = {}
    for row in raw_rows:
        # bench/write/run-write-cells.sh names the case column `cell` in
        # state/runs.tsv and `case` in cell-meta.tsv; accept both.
        case = (row.get("case") or row.get("cell") or "").strip()
        engine = (row.get("engine") or "").strip()
        variant = (row.get("variant") or "").strip()
        qd = jsonio.as_int((row.get("qd") or "").strip())
        rep = jsonio.as_int((row.get("rep") or "").strip())
        bs = parse_size((row.get("bs") or "").strip())
        if not case or not engine or bs is None or qd is None or rep is None:
            continue
        rows[(case, engine, variant, bs, qd, rep)] = {
            "case": case, "engine": engine, "variant": variant, "bs": bs,
            "qd": qd, "rep": rep,
            "status": (row.get("status") or "").strip(),
            "measured_s": jsonio.as_float(row.get("measured_s")),
            "warmup_s": jsonio.as_float(row.get("warmup_s")),
            "iops": jsonio.as_float(row.get("iops")),
            "mibs": jsonio.as_float(row.get("mibs")),
            "bytes": jsonio.as_float(row.get("bytes")),
            "dev_write_sectors": jsonio.as_float(row.get("dev_write_sectors_d")),
            "dev_write_mib": jsonio.as_float(row.get("dev_write_mib")),
            "loadavg_start": (row.get("loadavg_start") or "").strip(),
            "loadavg_end": (row.get("loadavg_end") or "").strip(),
            "governor": (row.get("governor") or "").strip(),
            "thermal_start": (row.get("thermal_start") or "").strip(),
            "thermal_end": (row.get("thermal_end") or "").strip(),
            "started_utc": (row.get("started_utc") or "").strip(),
            "source": jsonio.rel(path),
        }
    return rows, path


def load_cell_meta_rows(session_dir, warnings):
    """`cell-meta.tsv` -> {(case, engine, variant, bs_bytes, qd, rep): row}.

    Fallback provenance for engine bytes / device sectors; last `ok` row wins.
    """
    path = session_dir / "cell-meta.tsv"
    if not path.is_file():
        return {}, path
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as fh:
            raw_rows = list(csv.DictReader(fh, delimiter="\t"))
    except Exception as exc:
        warnings.append(f"{jsonio.rel(path)}: unreadable cell-meta.tsv ({exc})")
        return {}, path
    rows = {}
    for row in raw_rows:
        if (row.get("status") or "").strip() not in ("", "ok"):
            continue
        case = (row.get("case") or "").strip()
        engine = (row.get("engine") or "").strip()
        variant = (row.get("variant") or "").strip()
        qd = jsonio.as_int((row.get("qd") or "").strip())
        rep = jsonio.as_int((row.get("rep") or "").strip())
        bs = parse_size((row.get("bs") or "").strip())
        if not case or not engine or bs is None or qd is None or rep is None:
            continue
        rows[(case, engine, variant, bs, qd, rep)] = {
            "bytes": jsonio.as_float((row.get("bytes") or "").strip()),
            "dev_write_sectors": jsonio.as_float(
                (row.get("write_sectors_delta") or "").strip()),
            "wall_s": jsonio.as_float((row.get("wall_s") or "").strip()),
            "loadavg_start": (row.get("loadavg_start") or "").strip(),
            "source": jsonio.rel(path),
        }
    return rows, path


def parse_thermal_c(text):
    """Driver thermal token -> degrees C (hwmon is milli-degC; smart-log is C)."""
    if not text:
        return None
    text = str(text).strip()
    if text.startswith("unavailable"):
        return None
    value = None
    if "=" in text:
        token = text.split("=", 1)[1].strip()
        value = jsonio.as_float(token)
        if value is None:
            value = jsonio.as_float(re.split(r"[\s]", token)[0])
        if value is None:
            return None
        if text.startswith("hwmon_mc") or value > 1000.0:
            value = value / 1000.0
        return value
    value = jsonio.as_float(text.split()[0]) if text.split() else None
    if value is None:
        return None
    return value / 1000.0 if value > 1000.0 else value


def parse_loadavg_1m(text):
    """Loadavg token -> the 1-minute value (accepts '0.9' or '0.9/0.8/0.7')."""
    if not text:
        return None
    first = str(text).strip().split("/")[0].strip()
    return jsonio.as_float(first)


def load_series_file(path, warnings):
    """Parse one D2 sampler TSV (`series/<stem>.tsv`).

    Rows shorter than the declared column count are a partially written live file
    and are skipped (never crash).
    """
    columns = None
    header = {}
    samples = []
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for raw in fh:
                line = raw.rstrip("\n")
                if not line.strip():
                    continue
                if line.startswith("#"):
                    body = line.lstrip("#").strip()
                    if body.startswith("columns="):
                        columns = body[len("columns="):].split("\t")
                    elif "=" in body:
                        key, _sep, value = body.partition("=")
                        header[key.strip()] = value.strip()
                    continue
                if not columns:
                    continue
                parts = line.split("\t")
                if len(parts) < len(columns):
                    continue
                sample = {}
                ok = True
                # no strict= on Python 3.9; parts is pre-checked to cover columns
                for name, value in zip(columns, parts):  # noqa: B905
                    value = value.strip()
                    if name in ("iso_utc", "label", "device_stat"):
                        sample[name] = value
                        continue
                    num = jsonio.as_float(value)
                    if num is None:
                        ok = False
                        break
                    sample[name] = num
                if ok:
                    samples.append(sample)
    except Exception as exc:
        warnings.append(f"{jsonio.rel(path)}: unreadable device series ({exc})")
        return None
    return {"path": path, "header": header, "columns": columns,
            "samples": samples}


def _series_iops(sample, derived_count):
    """Write IOPS for one 1 s sample; derive from deltas if the column is absent."""
    value = sample.get("write_iops")
    if value is not None:
        return value
    ios = sample.get("write_ios_d")
    dt_ms = sample.get("dt_ms")
    if ios is not None and dt_ms:
        derived_count[0] += 1
        return ios / (dt_ms / 1000.0)
    return None


def _series_window(samples, measured_s, path, warnings):
    """Measured-window samples (the run-end transition sample is dropped)."""
    n = len(samples)
    if n == 0:
        warnings.append(f"{jsonio.rel(path)}: device series has no parseable samples")
        return [], "empty"
    if n >= measured_s + 2:
        return samples[-(measured_s + 1):-1], (
            f"last sample dropped as the run-end transition; {measured_s} "
            f"full 1 s samples")
    if n > measured_s:
        warnings.append(
            f"{jsonio.rel(path)}: series has {n} samples (< measured+2 = "
            f"{measured_s + 2}); used the last {measured_s} samples without "
            f"confirming the run-end transition")
        return samples[-measured_s:], (
            f"series shorter than measured+2; last {measured_s} samples used")
    warnings.append(
        f"{jsonio.rel(path)}: series has only {n} samples for a {measured_s} s "
        f"measured window (sampler stopped early or run still in progress)")
    return samples, f"series shorter than the measured window ({n} samples)"


def analyse_write_series(session_dir, cells, manifest, warnings):
    """D2/D7 write analysis from `series/*.tsv` (+ `state/runs.tsv`).

    Per run: measured-window IOPS stats, cliff detection, burst-vs-steady split,
    write amplification, thermal range and loadavg flag.
    """
    series_dir = session_dir / "series"
    rows = []
    n_derived = 0
    measured_default = manifest_int(manifest, "measured_s", 60) or 60
    runs, runs_path = load_runs_tsv(session_dir, warnings)
    meta, meta_path = load_cell_meta_rows(session_dir, warnings)
    if not runs and not meta:
        warnings.append("no state/runs.tsv or cell-meta.tsv: write amplification "
                        "and thermal/loadavg context are unavailable")
    paths = sorted(series_dir.glob("*.tsv")) if series_dir.is_dir() else []
    seen = set()
    for path in paths:
        stem = path.name[: -len(path.suffix)]
        key = parse_cell_stem(stem)
        if key is None:
            warnings.append(f"{jsonio.rel(path)}: series file name does not encode a "
                            f"cell id; skipped")
            continue
        case = key["case"]
        if case not in WRITE_CASE_SPEC:
            warnings.append(f"{jsonio.rel(path)}: series for non-write case {case}; "
                            f"skipped")
            continue
        series = load_series_file(path, warnings)
        if series is None:
            continue
        run_key = (case, key["engine"], key["variant"], key["bs"], key["qd"],
                   key["rep"])
        ledger = runs.get(run_key)
        fallback = meta.get(run_key)
        measured_s = (jsonio.as_int(ledger.get("measured_s")) if ledger and
                      ledger.get("measured_s") else None) or measured_default
        window, window_note = _series_window(series["samples"], measured_s, path,
                                             warnings)
        derived = [0]
        values = [v for v in (_series_iops(s, derived) for s in window)
                  if v is not None]
        n_derived += derived[0]
        if not values:
            warnings.append(f"{jsonio.rel(path)}: no write-IOPS samples in the "
                            f"measured window; run not analysable")
            continue
        st = stats.describe(values)
        mean = st["mean"]
        below = sum(1 for v in values
                    if mean and v < CLIFF_MEAN_FRACTION * mean)
        drops = []
        for left, right in zip(values, values[1:]):  # noqa: B905 (adjacent pairs)
            if left and left > 0 and right is not None:
                drops.append(max(0.0, 1.0 - right / left))
        max_drop = max(drops) if drops else 0.0
        burst = values[:SERIES_BURST_S]
        steady = values[SERIES_BURST_S:]
        burst_mean = statistics.fmean(burst) if burst else None
        steady_mean = statistics.fmean(steady) if steady else None
        burst_ratio = (burst_mean / steady_mean
                       if burst_mean is not None and steady_mean else None)
        whole_vals = [v for v in (_series_iops(s, [0]) for s in series["samples"])
                      if v is not None]
        whole = stats.describe(whole_vals)
        whole_below = sum(1 for v in whole_vals
                          if whole["mean"] and v < CLIFF_MEAN_FRACTION * whole["mean"])
        engine_bytes = None
        dev_sectors = None
        bytes_source = None
        if ledger and ledger.get("bytes"):
            engine_bytes = ledger["bytes"]
            bytes_source = jsonio.rel(runs_path)
        elif fallback and fallback.get("bytes"):
            engine_bytes = fallback["bytes"]
            bytes_source = fallback.get("source")
        if ledger and ledger.get("dev_write_sectors") is not None:
            dev_sectors = ledger["dev_write_sectors"]
        elif fallback and fallback.get("dev_write_sectors") is not None:
            dev_sectors = fallback["dev_write_sectors"]
        amp = None
        if dev_sectors is not None and engine_bytes:
            amp = dev_sectors * 512.0 / engine_bytes
        thermal_start = parse_thermal_c(ledger.get("thermal_start")) if ledger else None
        thermal_end = parse_thermal_c(ledger.get("thermal_end")) if ledger else None
        loadavg_start = None
        if ledger:
            loadavg_start = parse_loadavg_1m(ledger.get("loadavg_start"))
        elif fallback:
            loadavg_start = parse_loadavg_1m(fallback.get("loadavg_start"))
        if loadavg_start is None:
            first = series["samples"][0].get("loadavg_1m") if series["samples"] else None
            loadavg_start = first
        if not ledger:
            warnings.append(f"{jsonio.rel(path)}: series has no state/runs.tsv ledger row "
                            f"(run in progress or ledger not written yet); "
                            f"amplification/thermal unavailable")
        seen.add(run_key)
        rows.append({
            "case": case, "engine": key["engine"], "variant": key["variant"],
            "rep": key["rep"], "qd": key["qd"],
            "workload": WRITE_CASE_SPEC[case][0],
            "measured_s": measured_s,
            "window_note": window_note,
            "n": st["n"], "mean": st["mean"], "sd": st["sd"],
            "cv_pct": st["cv_pct"], "min": st["min"], "max": st["max"],
            "below70": below, "max_step_drop_pct": 100.0 * max_drop,
            "cliff_low": below > 0, "cliff_step": max_drop > CLIFF_STEP_DROP,
            "cliff": (below > 0) or (max_drop > CLIFF_STEP_DROP),
            "burst_mean": burst_mean, "steady_mean": steady_mean,
            "burst_steady_ratio": burst_ratio,
            "whole_n": whole["n"], "whole_cv_pct": whole["cv_pct"],
            "whole_below70": whole_below,
            "engine_bytes": engine_bytes, "dev_write_sectors": dev_sectors,
            "write_amp": amp, "bytes_source": bytes_source,
            "thermal_start_c": thermal_start, "thermal_end_c": thermal_end,
            "loadavg_start": loadavg_start,
            "loadavg_flag": (loadavg_start is not None and
                             loadavg_start > LOADAVG_FLAG),
            "series_path": jsonio.rel(path),
        })
    # expected write runs from the parsed cell JSON (warn on missing series; a
    # live session may legitimately have the newest run still being sampled)
    write_reps = [r for c in cells if workload_is_write(c["workload"])
                  for r in c["reps"]]
    missing = []
    for rep in write_reps:
        key = (rep["case"], rep["engine"], rep["variant"], rep["bs"],
               rep["qd"], rep["rep_index"])
        if key not in seen:
            missing.append(f"{rep['case']}/{rep['engine']}/{rep['variant']}/"
                           f"r{rep['rep_index']}")
    if missing:
        warnings.append(f"{len(missing)} measured write run(s) have no "
                        f"series/*.tsv sample file (sampler disabled, run in "
                        f"progress, or file not yet flushed): "
                        f"{', '.join(missing[:6])}"
                        + (" ..." if len(missing) > 6 else ""))
    if n_derived:
        warnings.append(f"{n_derived} series sample(s) lacked the write_iops "
                        f"column; IOPS derived from write_ios_d/dt_ms")
    rows.sort(key=lambda r: (cell_sort_key({"case": r["case"]}),
                             ENGINE_ORDER.get(r["engine"], 9),
                             VARIANT_ORDER.get(r["variant"], 9),
                             str(r["variant"]), r["rep"]))
    thermals = [t for r in rows for t in (r["thermal_start_c"],
                                          r["thermal_end_c"]) if t is not None]
    session = {
        "thermal_min_c": min(thermals) if thermals else None,
        "thermal_max_c": max(thermals) if thermals else None,
        "n_thermal_values": len(thermals),
        "n_loadavg_flagged": sum(1 for r in rows if r["loadavg_flag"]),
        "n_cliff": sum(1 for r in rows if r["cliff"]),
    }
    return {"rows": rows, "runs_path": runs_path, "meta_path": meta_path,
            "measured_default": measured_default, "session": session}


# ---------------------------------------------------------------------------
# rendering

# ---------------------------------------------------------------------------

def render_cells_md(cells):
    headers = ["case", "engine", "variant", "workload", "bs", "qd", "n",
               "IOPS mean ± 95% CI", "sd", "min", "median", "max", "CV%",
               "state", "flags"]
    rows = []
    for c in cells:
        state = "—"
        if c["state_applies"]:
            state = f"{c['state']} {c['n_fast']}f/{c['n_slow']}s"
            if not c["state_valid"]:
                state += " (invalid)"
        rows.append([
            c["case"], c["engine"], c["variant"], c["workload"],
            md.num(c["bs"], 0), c["qd"], c["n"],
            md.ci(c["iops_mean"], c["ci95_half"], 0),
            md.num(c["iops_sd"], 0) if c["iops_sd"] is not None else "—",
            md.num(c["min"], 0), md.num(c["median"], 0), md.num(c["max"], 0),
            md.num(c["cv_pct"], 1) + (" !" if c["cv_unstable"] else "") if c["cv_pct"] is not None else "—",
            state, cell_flags(c),
        ])
    return md.table(headers, rows)


def render_latency_md(cells):
    headers = ["case", "engine", "variant", "qd", "n", "p50 us", "p95 us",
               "p99 us", "p999 us", "max us", "source (first rep)"]
    rows = []
    for c in cells:
        rows.append([
            c["case"], c["engine"], c["variant"], c["qd"], c["n"],
            md.num(c["p50"], 1), md.num(c["p95"], 1), md.num(c["p99"], 1),
            md.num(c["p999"], 1), md.num(c["lat_max"], 1), c["sources"][0],
        ])
    return md.table(headers, rows)


def render_state_md(cells):
    headers = ["case", "engine", "variant", "qd", "n", "p99 threshold",
               "fast (n)", "slow (n)", "mean fast IOPS ± 95% CI",
               "mean slow IOPS", "excluded (slow) reps", "IOPS spread",
               "CV%", "flags", "note"]
    rows = []
    for c in cells:
        if not c["state_applies"] and not c["slow_reps"] and c["cv_pct"] is not None:
            continue  # stable, not a state-gated cell: nothing to explain here
        threshold = (f"{c['p99_threshold']:.0f} us"
                     if c["p99_threshold"] is not None else "n/a (IOPS rule only)")
        fast_mean = (f"{md.num(c['fast_iops_mean'], 0)}"
                     if c["fast_iops_mean"] is not None else "—")
        if c["fast_ci95_half"] is not None:
            fast_mean += f" ± {md.num(c['fast_ci95_half'], 0)}"
        flags = []
        if c["cv_unstable"]:
            flags.append("CV>10%")
        if c["mixed_state"]:
            flags.append("mixed state")
        if c["bimodal_iops"] and not c["mixed_state"]:
            flags.append("IOPS spread>10%")
        rows.append([
            c["case"], c["engine"], c["variant"], c["qd"], c["n"], threshold,
            c["n_fast"], c["n_slow"], fast_mean, md.num(c["mean_slow"], 0),
            ", ".join(c["slow_ids"]) or "—",
            (f"{100.0 * c['iops_spread']:.1f}%" if c["iops_spread"] is not None else "—"),
            md.num(c["cv_pct"], 1) if c["cv_pct"] is not None else "—",
            ", ".join(flags) or "stable",
            c["state_note"],
        ])
    if not rows:
        return "_(no cells with per-rep state information in this session)_"
    return md.table(headers, rows)


def render_ratios_md(ratios):
    headers = ["case", "qd", "kind", "left", "right", "n pairs",
               "all-reps ratio", "paired SD", "90% CI", "95% CI",
               "1s95 LB", "1s95 UB", "half-width",
               "fast-subset ratio [CI] (n)", "note"]
    rows = []
    for r in ratios:
        st = r["stats"]
        stf = r["stats_fast"]
        left = f"{r['left']['engine']}/{r['left']['variant']}"
        right = f"{r['right']['engine']}/{r['right']['variant']}"
        ci90 = (f"{st['ci90_low']:.4f}..{st['ci90_high']:.4f}"
                if st.get("ci90_low") is not None else "—")
        ci95 = (f"{st['ci95_low']:.4f}..{st['ci95_high']:.4f}"
                if st.get("ci95_low") is not None else "—")
        half = (f"±{st['ci95_half']:.4f} ({st['ci95_half_pct']:.1f}%)"
                if st.get("ci95_half") is not None else "—")
        if stf.get("n_pairs", 0) > 0 and stf.get("mean") is not None:
            if stf.get("ci90_low") is not None:
                fast = (f"{stf['mean']:.4f} [{stf['ci90_low']:.4f}.."
                        f"{stf['ci90_high']:.4f}] (n={stf['n_pairs']})")
            else:
                fast = f"{stf['mean']:.4f} (n={stf['n_pairs']}, no CI)"
        else:
            fast = "—"
        rows.append([
            r["case"], r["qd"], r["kind"], left, right, st["n_pairs"],
            md.ratio(st["mean"]),
            f"{st['sd']:.4f}" if st.get("sd") is not None else "—",
            ci90, ci95,
            f"{st['lb95_1s']:.4f}" if st.get("lb95_1s") is not None else "—",
            f"{st['ub95_1s']:.4f}" if st.get("ub95_1s") is not None else "—",
            half, fast, r["note"],
        ])
    return md.table(headers, rows)


def render_state_ratios_md(ratios):
    """Paired ratios on the fast-state subset, disclosing excluded reps."""
    headers = ["case", "qd", "kind", "left", "right", "n pairs (fast)",
               "fast-subset ratio", "paired SD", "90% CI", "95% CI",
               "excluded left", "excluded right", "classification differs", "note"]
    rows = []
    for r in ratios:
        stf = r["stats_fast"]
        st = r["stats"]
        if st["n_pairs"] == stf["n_pairs"] and not r["left_slow"] and not r["right_slow"]:
            continue  # subset == all reps: nothing to disclose
        if stf.get("mean") is None:
            continue
        cia = (f"{stf['ci90_low']:.4f}..{stf['ci90_high']:.4f}"
               if stf.get("ci90_low") is not None else "—")
        cib = (f"{stf['ci95_low']:.4f}..{stf['ci95_high']:.4f}"
               if stf.get("ci95_low") is not None else "—")
        rows.append([
            r["case"], r["qd"], r["kind"],
            f"{r['left']['engine']}/{r['left']['variant']}",
            f"{r['right']['engine']}/{r['right']['variant']}",
            stf["n_pairs"], md.ratio(stf["mean"]),
            f"{stf['sd']:.4f}" if stf.get("sd") is not None else "—",
            cia, cib,
            ", ".join(r["left_slow"]) or "—",
            ", ".join(r["right_slow"]) or "—",
            "YES" if r["classification_differs"] else "no",
            r["note"],
        ])
    if not rows:
        return "_(no fast-state subset differs from the all-reps set: every cell is stable, or subsets are identical)_"
    return md.table(headers, rows)


def render_perf_md(perf_report):
    headers = ["case", "engine", "variant", "qd", "rep", "window s",
               "ops (window)", "cycles u+k", "cyc/op", "instr/op",
               "syscalls/op", "ctxt/op", "raw capture / provenance"]
    rows = []
    for rec in sorted(perf_report, key=lambda r: (
            cell_sort_key({"case": r.get("case") or "?"}),
            ENGINE_ORDER.get(r.get("engine"), 9), r.get("variant") or "",
            r.get("qd") or 0, r.get("rep") or 0)):
        cyc_u = rec.get("cycles_u")
        cyc_k = rec.get("cycles_k")
        cyc = (f"{md.num(cyc_u, 0)} + {md.num(cyc_k, 0)}"
               if cyc_u is not None or cyc_k is not None else "—")
        if rec.get("raw_source"):
            provenance = rec["raw_source"]
        else:
            provenance = rec.get("provenance") or "—"
        rows.append([
            rec.get("case") or "?", rec.get("engine") or "?",
            rec.get("variant") or "?", rec.get("qd") or "?",
            rec.get("rep") if rec.get("rep") is not None else "?",
            f"{rec['window']:.3f}" if rec.get("window") else "—",
            md.num(rec.get("ops"), 0), cyc,
            md.num(rec.get("cycles_per_op"), 1),
            md.num(rec.get("instr_per_op"), 1),
            md.num(rec.get("syscalls_per_op"), 3),
            md.num(rec.get("ctxt_per_op"), 4),
            provenance,
        ])
    if not rows:
        return "_(no perf stat captures / perf-cells.tsv rows)_"
    return md.table(headers, rows)


def render_iotester_md(iotester, cells):
    headers = ["case", "variant", "qd", "shards", "IOPS", "MiB/s", "p99 us",
               "seastar_file_bench IOPS (same cell)", "ratio it/sfb", "source"]
    rows = []
    for it in sorted(iotester, key=lambda r: cell_sort_key(r["key"])):
        key = it["key"]
        sfb = None
        for c in cells:
            if (c["engine"] == "seastar" and c["case"] == key["case"] and
                    c["bs"] == key["bs"] and c["qd"] == key["qd"]):
                sfb = c
                break
        ratio = (it["iops"] / sfb["iops_mean"]
                 if sfb and sfb["iops_mean"] and it.get("iops") else None)
        rows.append([
            key["case"], key["variant"], key["qd"], it["n_shards"],
            md.num(it.get("iops"), 0), md.num(it.get("mibs"), 1),
            md.num(it.get("p99"), 1),
            _cell_pair_desc(sfb),
            md.ratio(ratio) if ratio is not None else "—",
            jsonio.rel(it["path"]),
        ])
    if not rows:
        return "_(no io_tester YAML parsed; io_tester cross-check pending writable storage)_"
    return md.table(headers, rows)


def render_gates_md(gate_rows):
    headers = ["rule", "cell(s)", "JUring", "fio-matched comparator", "ratio",
               "threshold", "verdict", "achieved CI half-width", "note"]
    rows = []
    for r in gate_rows:
        rows.append([r["rule"], r["cells"], r["juring"], r["comparator"],
                     r["ratio"], r["threshold"], r["verdict"],
                     r["half_width"], r["note"]])
    return md.table(headers, rows)


def render_seastar_md(bracket):
    headers = ["case", "qd", "JUring IOPS", "seastar IOPS",
               "JUring/seastar", "90% CI", "95% CI", "paired SD"]
    rows = []
    for b in bracket:
        st = b["stats"]
        rows.append([
            b["case"], b["qd"], _cell_pair_desc(b["left"]),
            _cell_pair_desc(b["right"]), md.ratio(st["mean"]),
            (f"{st['ci90_low']:.4f}..{st['ci90_high']:.4f}"
             if st.get("ci90_low") is not None else "—"),
            (f"{st['ci95_low']:.4f}..{st['ci95_high']:.4f}"
             if st.get("ci95_low") is not None else "—"),
            f"{st['sd']:.4f}" if st.get("sd") is not None else "—",
        ])
    if not rows:
        return "_(no seastar cells)_"
    return md.table(headers, rows)


# --- write-half tables -----------------------------------------------------

def _write_cell_sort(cell):
    case = cell["case"]
    if case in WRITE_CASE_ORDER:
        rank = WRITE_CASE_ORDER.index(case)
    else:
        rank = len(WRITE_CASE_ORDER)
    return (rank, ENGINE_ORDER.get(cell["engine"], 9),
            FIO_SWEEP_ORDER.get(cell["variant"],
                                VARIANT_ORDER.get(cell["variant"], 9)),
            str(cell["variant"]))


def render_write_cells_md(cells):
    """Write-cell throughput table: raw-JSON IOPS/MiB/s and p99 (us)."""
    headers = ["case", "workload", "bs", "qd", "engine", "variant", "n",
               "IOPS mean ± 95% CI", "MiB/s", "CV%", "p50 us", "p99 us",
               "flags", "state"]
    rows = []
    for c in sorted(cells, key=_write_cell_sort):
        state = "—"
        if c["cv_unstable"]:
            state = "unstable (CV>10%), no ranking"
        elif c["slow_reps"]:
            state = f"slow reps excluded from fast subset: {', '.join(c['slow_ids'])}"
        else:
            state = "stable"
        rows.append([
            c["case"], c["workload"], md.num(c["bs"], 0), c["qd"], c["engine"],
            c["variant"], c["n"], md.ci(c["iops_mean"], c["ci95_half"], 0),
            md.num(c["mibs_mean"], 1), md.num(c["cv_pct"], 1) +
            (" !" if c["cv_unstable"] else "") if c["cv_pct"] is not None else "—",
            md.num(c["p50"], 1), md.num(c["p99"], 1), cell_flags(c), state,
        ])
    if not rows:
        return "_(no write cells parsed)_"
    return md.table(headers, rows)


def render_write_ratios_md(ratios):
    """Paired JUring/comparator ratios for write cells (informational rows)."""
    headers = ["case", "qd", "kind", "comparator", "n pairs", "ratio",
               "paired SD", "90% CI", "95% CI", "1s95 LB", "half-width",
               "fast-subset ratio [CI]", "note"]
    rows = []
    for r in ratios:
        st = r["stats"]
        stf = r["stats_fast"]
        fast = "—"
        if stf.get("mean") is not None and stf.get("n_pairs", 0) > 0:
            if stf.get("ci90_low") is not None:
                fast = (f"{stf['mean']:.4f} [{stf['ci90_low']:.4f}.."
                        f"{stf['ci90_high']:.4f}] (n={stf['n_pairs']})")
            else:
                fast = f"{stf['mean']:.4f} (n={stf['n_pairs']}, no CI)"
        rows.append([
            r["case"], r["qd"], r["kind"],
            f"{r['right']['engine']}/{r['right']['variant']}",
            st["n_pairs"], md.ratio(st["mean"]),
            f"{st['sd']:.4f}" if st.get("sd") is not None else "—",
            f"{st['ci90_low']:.4f}..{st['ci90_high']:.4f}"
            if st.get("ci90_low") is not None else "—",
            f"{st['ci95_low']:.4f}..{st['ci95_high']:.4f}"
            if st.get("ci95_low") is not None else "—",
            f"{st['lb95_1s']:.4f}" if st.get("lb95_1s") is not None else "—",
            f"±{st['ci95_half']:.4f} ({st['ci95_half_pct']:.1f}%)"
            if st.get("ci95_half") is not None else "—",
            fast, r["note"],
        ])
    if not rows:
        return "_(no write-cell paired ratios)_"
    return md.table(headers, rows)


def render_write_gate_md(write_gate):
    """Rule-1 gate detail: both comparator rows per gated case + case verdict."""
    headers = ["case", "comparator", "JUring IOPS (n)",
               "fio IOPS (n)", "n pairs", "ratio [90% CI; 95% CI]",
               "1s95 LB", "half-width", "verdict"]
    rows = []
    for comp in write_gate.get("comparators") or []:
        st = comp["stats"]
        role = comp["comparator_role"]
        rows.append([
            comp["case"], f"fio/{comp['comparator_variant']} ({role})",
            _cell_pair_desc(comp["juring"]), _cell_pair_desc(comp["fio"]),
            st["n_pairs"], _ratio_line(st),
            f"{st['lb95_1s']:.4f}" if st.get("lb95_1s") is not None else "—",
            comp["half"], comp["verdict"],
        ])
    if not rows:
        return "_(no W7/W8 write-gate comparator cells in this session)_"
    table = md.table(headers, rows)
    verdict_rows = []
    for case in WRITE_GATE_CASES:
        by_role = {c["comparator_role"]: c for c in
                   (write_gate.get("comparators") or []) if c["case"] == case}
        verdict_rows.append([
            case,
            by_role.get("primary", {}).get("verdict", "absent"),
            by_role.get("sensitivity", {}).get("verdict", "absent"),
            write_gate.get("case_verdicts", {}).get(case, "INCONCLUSIVE"),
        ])
    table += "\n\n" + md.table(
        ["case", "matchedbatch (primary)", "matched (sensitivity)",
         "case verdict (PASS iff both PASS; FAIL iff either FAIL)"],
        verdict_rows)
    return table


def render_write_sweep_md(cells):
    """W7 fio batching sweep (informational) with juring/seastar context rows."""
    by_key = {}
    for c in cells:
        if c["case"] == "W7":
            by_key[(c["engine"], c["variant"])] = c
    jc = by_key.get(("juring", "std"))
    order = [("juring", "std"), ("seastar", "std"),
             ("fio-io_uring", "default"), ("fio-io_uring", "matched"),
             ("fio-io_uring", "matchedbatch"),
             ("fio-io_uring", "batch1"), ("fio-io_uring", "batch8"),
             ("fio-io_uring", "batch16"), ("fio-io_uring", "batch32")]
    seen = set()
    headers = ["role", "engine/variant", "flags", "n",
               "IOPS mean ± 95% CI", "CV%", "p99 us", "vs JUring ratio",
               "1s95 LB"]
    rows = []
    for engine, variant in order:
        cell = by_key.get((engine, variant))
        if cell is None:
            continue
        seen.add((engine, variant))
        ratio_text = lb = "—"
        if jc is not None and cell is not jc:
            st = stats.ratio_stats(jc["reps"], cell["reps"])
            ratio_text = md.ratio(st["mean"])
            if st.get("lb95_1s") is not None:
                lb = f"{st['lb95_1s']:.4f}"
        role = "JUring" if engine == "juring" else (
            "seastar bracket" if engine == "seastar" else "fio bracket")
        rows.append([
            role, f"{engine}/{variant}", cell_flags(cell), cell["n"],
            md.ci(cell["iops_mean"], cell["ci95_half"], 0),
            md.num(cell["cv_pct"], 1) if cell["cv_pct"] is not None else "—",
            md.num(cell["p99"], 1), ratio_text, lb,
        ])
    for (engine, variant), cell in sorted(by_key.items()):
        if (engine, variant) in seen:
            continue
        rows.append(["other", f"{engine}/{variant}", cell_flags(cell),
                     cell["n"], md.ci(cell["iops_mean"], cell["ci95_half"], 0),
                     md.num(cell["cv_pct"], 1) if cell["cv_pct"] is not None else "—",
                     md.num(cell["p99"], 1), "—", "—"])
    if not rows:
        return "_(no W7 cells)_"
    return md.table(headers, rows)


def render_write_seastar_md(bracket, write_cases):
    """seastar write bracket (no threshold; asymmetric engine configuration)."""
    headers = ["case", "qd", "JUring IOPS", "seastar IOPS", "JUring/seastar",
               "90% CI", "95% CI", "paired SD"]
    rows = []
    for b in bracket:
        if b["case"] not in write_cases:
            continue
        st = b["stats"]
        rows.append([
            b["case"], b["qd"], _cell_pair_desc(b["left"]),
            _cell_pair_desc(b["right"]), md.ratio(st["mean"]),
            f"{st['ci90_low']:.4f}..{st['ci90_high']:.4f}"
            if st.get("ci90_low") is not None else "—",
            f"{st['ci95_low']:.4f}..{st['ci95_high']:.4f}"
            if st.get("ci95_low") is not None else "—",
            f"{st['sd']:.4f}" if st.get("sd") is not None else "—",
        ])
    if not rows:
        return "_(no seastar write cells)_"
    return md.table(headers, rows)


def _fmt_c(value):
    return f"{value:.1f}" if value is not None else "—"


def render_series_md(series):
    """D2/D7 device 1 s series table: stats, cliff detection, burst vs steady."""
    rows = series.get("rows") or []
    if not rows:
        return "_(no device series parsed)_"
    headers = ["case", "engine/variant/rep", "meas n", "IOPS mean", "CV%",
               "min", "max", "samples <70% mean", "max step drop %", "cliff",
               "burst IOPS (first 10 s)", "steady IOPS", "burst/steady",
               "whole-run CV% (warmup+meas)"]
    out = []
    for r in rows:
        out.append([
            r["case"], f"{r['engine']}/{r['variant']}/r{r['rep']}",
            r["n"], md.num(r["mean"], 0), md.num(r["cv_pct"], 1),
            md.num(r["min"], 0), md.num(r["max"], 0), r["below70"],
            md.num(r["max_step_drop_pct"], 1),
            "YES" if r["cliff"] else "no",
            md.num(r["burst_mean"], 0), md.num(r["steady_mean"], 0),
            md.num(r["burst_steady_ratio"], 3), md.num(r["whole_cv_pct"], 1),
        ])
    table = md.table(headers, out)
    amp_headers = ["case", "engine/variant/rep", "engine measured bytes",
                   "device write sectors delta", "write amplification",
                   "thermal start..end C", "loadavg_start", "source"]
    amp_rows = []
    for r in rows:
        amp_rows.append([
            r["case"], f"{r['engine']}/{r['variant']}/r{r['rep']}",
            md.num(r["engine_bytes"], 0),
            md.num(r["dev_write_sectors"], 0),
            f"{r['write_amp']:.3f}x" if r["write_amp"] is not None else "—",
            (f"{_fmt_c(r['thermal_start_c'])}..{_fmt_c(r['thermal_end_c'])}"
             if (r["thermal_start_c"] is not None or
                 r["thermal_end_c"] is not None) else "—"),
            (md.num(r["loadavg_start"], 2) +
             (" !>1.6" if r["loadavg_flag"] else ""))
            if r["loadavg_start"] is not None else "—",
            r["series_path"],
        ])
    return table + "\n\n" + md.table(amp_headers, amp_rows)


# ---------------------------------------------------------------------------
# serialisation
# ---------------------------------------------------------------------------

def rep_brief(rep):
    return {
        "rep": rep["rep"], "iops": rep.get("iops"), "mibs": rep.get("mibs"),
        "p50": rep.get("p50"), "p99": rep.get("p99"),
        "p999": rep.get("p999"), "max": rep.get("max"),
        "ops": rep.get("ops"), "errors": rep.get("errors"),
        "source": jsonio.rel(rep["path"]),
    }


def cell_brief(cell):
    return {
        "case": cell["case"], "engine": cell["engine"],
        "variant": cell["variant"], "workload": cell["workload"],
        "bs": cell["bs"], "qd": cell["qd"], "threads": cell["threads"],
        "n": cell["n"],
        "iops_mean": cell["iops_mean"], "iops_sd": cell["iops_sd"],
        "iops_ci95": [cell["ci95_low"], cell["ci95_high"]],
        "iops_ci95_half": cell["ci95_half"],
        "min": cell["min"], "median": cell["median"], "max": cell["max"],
        "cv_pct": cell["cv_pct"],
        "p50": cell["p50"], "p95": cell["p95"], "p99": cell["p99"],
        "p999": cell["p999"], "lat_max": cell["lat_max"],
        "state": cell["state"], "state_applies": cell["state_applies"],
        "state_valid": cell["state_valid"], "state_note": cell["state_note"],
        "p99_threshold_us": cell["p99_threshold"],
        "n_fast": cell["n_fast"], "n_slow": cell["n_slow"],
        "fast_reps": [r["rep"] for r in cell["fast_reps"]],
        "slow_reps": cell["slow_ids"],
        "fast_iops_mean": cell["fast_iops_mean"],
        "fast_iops_ci95": [cell["fast_ci95_low"], cell["fast_ci95_high"]],
        "fast_iops_ci95_half": cell["fast_ci95_half"],
        "mean_fast": cell["mean_fast"], "mean_slow": cell["mean_slow"],
        "cell_max_iops": cell["cell_max_iops"],
        "iops_spread": cell["iops_spread"],
        "mixed_state": cell["mixed_state"],
        "bimodal_p99": cell["bimodal_p99"],
        "bimodal_iops": cell["bimodal_iops"],
        "cv_unstable": cell["cv_unstable"],
        "unstable": cell["unstable"],
        "flags": {"buffer_mode": cell["buffer_mode"],
                  "ring_flags": cell["ring_flags"],
                  "registered_files": cell["registered_files"],
                  "fixed_buffers": cell["fixed_buffers"],
                  "iodepth_batch_submit": cell["iodepth_batch_submit"],
                  "sqpoll": cell["sqpoll"]},
        "sources": cell["sources"],
        "reps": [rep_brief(r) for r in cell["reps"]],
    }


def ratio_brief(r):
    st = r["stats"]
    return {
        "case": r["case"], "qd": r["qd"], "kind": r["kind"],
        "matched": r["matched"],
        "left": f"{r['left']['engine']}/{r['left']['variant']}",
        "right": f"{r['right']['engine']}/{r['right']['variant']}",
        "n_pairs": st["n_pairs"], "mean": st["mean"], "sd": st["sd"],
        "ci90": [st["ci90_low"], st["ci90_high"]],
        "ci95": [st["ci95_low"], st["ci95_high"]],
        "lb95_1s": st["lb95_1s"], "ub95_1s": st["ub95_1s"],
        "ci95_half": st["ci95_half"], "ci95_half_pct": st["ci95_half_pct"],
        "fast_subset": {
            "n_pairs": r["stats_fast"]["n_pairs"],
            "mean": r["stats_fast"]["mean"],
            "sd": r["stats_fast"]["sd"],
            "ci90": [r["stats_fast"]["ci90_low"], r["stats_fast"]["ci90_high"]],
            "ci95": [r["stats_fast"]["ci95_low"], r["stats_fast"]["ci95_high"]],
        },
        "excluded_left_reps": r["left_slow"],
        "excluded_right_reps": r["right_slow"],
        "classification_differs": r["classification_differs"],
        "note": r["note"],
    }


def perf_report_brief(rec):
    return {
        "case": rec.get("case"), "engine": rec.get("engine"),
        "variant": rec.get("variant"), "qd": rec.get("qd"),
        "rep": rec.get("rep"), "attach_s": rec.get("attach_s"),
        "window_s": rec.get("window"), "ops_window": rec.get("ops"),
        "iops": rec.get("iops"), "task_clock_s": rec.get("task_clock_s"),
        "cycles_u": rec.get("cycles_u"), "cycles_k": rec.get("cycles_k"),
        "instructions_u": rec.get("instr_u"),
        "instructions_k": rec.get("instr_k"),
        "syscalls": rec.get("syscalls"), "ctxt_switches": rec.get("ctxt"),
        "cycles_per_op": rec.get("cycles_per_op"),
        "instr_per_op": rec.get("instr_per_op"),
        "syscalls_per_op": rec.get("syscalls_per_op"),
        "ctxt_per_op": rec.get("ctxt_per_op"),
        "source": rec.get("source") or jsonio.rel(rec.get("path") or ""),
        "raw_source": rec.get("raw_source"),
        "provenance": rec.get("provenance"),
    }


def comparator_brief(comparator):
    """Compact per-(case, comparator) row for the write-gate aggregate payload."""
    st = comparator["stats"]
    jc = comparator["juring"]
    fc = comparator["fio"]
    return {
        "case": comparator["case"],
        "comparator_role": comparator["comparator_role"],
        "comparator_variant": comparator["comparator_variant"],
        "juring": {"engine": jc["engine"], "variant": jc["variant"],
                   "n": jc["n"], "iops_mean": jc["iops_mean"],
                   "ci95_half": jc["ci95_half"], "cv_pct": jc["cv_pct"]},
        "fio": {"engine": fc["engine"], "variant": fc["variant"],
                "n": fc["n"], "iops_mean": fc["iops_mean"],
                "ci95_half": fc["ci95_half"], "cv_pct": fc["cv_pct"]},
        "n_pairs": st["n_pairs"], "mean": st["mean"], "sd": st["sd"],
        "ci90": [st["ci90_low"], st["ci90_high"]],
        "ci95": [st["ci95_low"], st["ci95_high"]],
        "lb95_1s": st["lb95_1s"], "ub95_1s": st["ub95_1s"],
        "ci95_half": st["ci95_half"], "ci95_half_pct": st["ci95_half_pct"],
        "verdict": comparator["verdict"],
        "used_fast_subset": comparator["used_subset"],
        "unstable_sides": comparator["unstable_sides"],
        "excluded_juring_reps": comparator["excluded_juring"],
        "excluded_fio_reps": comparator["excluded_fio"],
        "detail": comparator["detail"],
        "note": comparator["note"],
    }


def series_brief(row):
    """Compact D2 per-run row for the write aggregate payload."""
    keys = ("case", "engine", "variant", "rep", "qd", "workload", "measured_s",
            "n", "mean", "sd", "cv_pct", "min", "max", "below70",
            "max_step_drop_pct", "cliff_low", "cliff_step", "cliff",
            "burst_mean", "steady_mean", "burst_steady_ratio", "whole_n",
            "whole_cv_pct", "whole_below70", "engine_bytes",
            "dev_write_sectors", "write_amp", "thermal_start_c",
            "thermal_end_c", "loadavg_start", "loadavg_flag", "series_path",
            "window_note")
    return {key: row.get(key) for key in keys}


def _write_csv_tables(write_analysis, write_gate):
    """Extra CSV tables emitted only for write sessions (D2 series + gate detail)."""
    series = (write_analysis.get("series") or {}).get("rows") or []
    series_rows = [[
        r["case"], r["engine"], r["variant"], r["rep"], r["workload"],
        r["qd"], r["measured_s"], r["n"], r["mean"], r["sd"], r["cv_pct"],
        r["min"], r["max"], r["below70"], r["max_step_drop_pct"],
        int(r["cliff_low"]), int(r["cliff_step"]), int(r["cliff"]),
        r["burst_mean"], r["steady_mean"], r["burst_steady_ratio"],
        r["whole_n"], r["whole_cv_pct"], r["whole_below70"],
        r["engine_bytes"], r["dev_write_sectors"], r["write_amp"],
        r["thermal_start_c"], r["thermal_end_c"], r["loadavg_start"],
        int(r["loadavg_flag"]), r["series_path"]]
        for r in series]
    tables = [("series_csv", series_rows,
               ["case", "engine", "variant", "rep", "workload", "qd",
                "measured_s", "n_samples_window", "iops_mean", "iops_sd",
                "cv_pct", "iops_min", "iops_max", "samples_below_70pct_mean",
                "max_step_drop_pct", "cliff_low", "cliff_step", "cliff",
                "burst_mean_first_10s", "steady_mean_remainder",
                "burst_steady_ratio", "whole_n_samples", "whole_cv_pct",
                "whole_below70", "engine_bytes", "dev_write_sectors",
                "write_amplification", "thermal_start_c", "thermal_end_c",
                "loadavg_start", "loadavg_flag_gt_1.6", "series_source"])]
    comparator_rows = []
    for c in (write_gate or {}).get("comparators", []):
        st = c["stats"]
        comparator_rows.append([
            c["case"], c["comparator_role"], c["comparator_variant"],
            c["juring"]["n"], c["juring"]["iops_mean"],
            c["juring"]["ci95_half"], c["juring"]["cv_pct"],
            c["fio"]["n"], c["fio"]["iops_mean"], c["fio"]["ci95_half"],
            c["fio"]["cv_pct"], st["n_pairs"], st["mean"], st["sd"],
            st["ci90_low"], st["ci90_high"], st["ci95_low"], st["ci95_high"],
            st["lb95_1s"], st["ub95_1s"], st["ci95_half"],
            st["ci95_half_pct"], c["verdict"], int(c["used_subset"]),
            ";".join(c["excluded_juring"]), ";".join(c["excluded_fio"]),
            c["note"]])
    tables.append(("comparators_csv", comparator_rows,
                   ["case", "comparator_role", "comparator_variant",
                    "juring_n", "juring_iops_mean", "juring_ci95_half",
                    "juring_cv_pct", "fio_n", "fio_iops_mean", "fio_ci95_half",
                    "fio_cv_pct", "n_pairs", "ratio_mean", "paired_sd",
                    "ci90_low", "ci90_high", "ci95_low", "ci95_high",
                    "lb95_one_sided", "ub95_one_sided", "ci95_half",
                    "ci95_half_pct", "verdict", "used_fast_subset",
                    "excluded_juring_reps", "excluded_fio_reps", "note"]))
    return tables


def write_outputs(prefix, summary_md, tables, payload):
    prefix = Path(prefix)
    cli.ensure_dir(prefix.parent)
    out = {}
    files = {
        "summary_md": prefix.with_name(prefix.name + ".summary.md"),
        "cells_csv": prefix.with_name(prefix.name + ".cells.csv"),
        "reps_csv": prefix.with_name(prefix.name + ".reps.csv"),
        "ratios_csv": prefix.with_name(prefix.name + ".ratios.csv"),
        "perf_csv": prefix.with_name(prefix.name + ".perf.csv"),
        "gates_csv": prefix.with_name(prefix.name + ".gates.csv"),
        "iotester_csv": prefix.with_name(prefix.name + ".iotester.csv"),
        # write-session-only artifacts (never emitted for the read session)
        "series_csv": prefix.with_name(prefix.name + ".series.csv"),
        "comparators_csv": prefix.with_name(prefix.name + ".comparators.csv"),
        "aggregate_json": prefix.with_name(prefix.name + ".aggregate.json"),
    }
    cli.write_text(files["summary_md"], summary_md)
    out["summary_md"] = str(files["summary_md"])
    for key, rows, header in tables:
        target = files[key]
        with open(target, "w", newline="", encoding="utf-8") as fh:
            writer = csv.writer(fh)
            writer.writerow(header)
            writer.writerows(rows)
        out[key] = str(target)
    cli.write_json(files["aggregate_json"], payload, indent=1)
    out["aggregate_json"] = str(files["aggregate_json"])
    return out


# ---------------------------------------------------------------------------
# write-half summary section (only emitted for sessions with write cells)
# ---------------------------------------------------------------------------

def _render_write_summary(session_dir, write_analysis):
    cells = write_analysis.get("cells") or []
    ratios = write_analysis.get("ratios") or []
    gate = write_analysis.get("gate")
    series = write_analysis.get("series") or {}
    units = write_analysis.get("units") or {}
    lines = []
    lines.append("## 9b. Write half — M1 write matrix (write session)")
    lines.append("")
    write_cases = ", ".join(sorted(
        {c["case"] for c in cells},
        key=lambda c: (WRITE_CASE_ORDER.index(c) if c in WRITE_CASE_ORDER else 99)))
    lines.append(
        "This session contains " + str(len(cells)) + " write-throughput cell(s) "
        f"({write_cases or 'none'}); "
        "the write half of M1 is analysed here and the read-path gates are not "
        "part of this session (docs/m1-write-design.md §9). Every number below "
        "is derived from the raw per-run files cited in the tables.")
    lines.append("")
    lines.append("### 9b.1 Write-cell throughput (mean ± two-sided 95% CI of "
                 "the mean; p50/p99 in us from the raw engine JSON)")
    lines.append("")
    lines.append(render_write_cells_md(cells))
    lines.append("")
    lines.append("p99 is the mean of the per-rep percentiles from the raw JSON "
                 "(fio `clat_ns` / 1000 -> us; JUring/seastar `latency_us` "
                 "already us). Write latency is descriptive here: Step A has no "
                 "write tail gate (design §9). A cell with CV > 10% is reported "
                 "'unstable, no ranking' (methodology §5 D7).")
    lines.append("")
    lines.append("### 9b.2 Paired ratios — JUring/comparator, paired by rep "
                 "index (90% CI = one-sided 95% bounds)")
    lines.append("")
    lines.append(render_write_ratios_md(ratios))
    lines.append("")
    lines.append("### 9b.3 M1 write rule 1 — W7/W8 against BOTH fio "
                 "comparators (matchedbatch primary; matched sensitivity)")
    lines.append("")
    lines.append(
        "Decision rule, frozen before measurement (design §6): **PASS** iff the "
        "one-sided 95% lower bound of the paired JUring/fio ratio is >= 0.95 "
        "against BOTH `matchedbatch` and `matched` at BOTH W7 and W8; **FAIL** "
        "iff either comparator's 95% upper bound is < 0.95; otherwise "
        "**INCONCLUSIVE**. All reps are primary; the fast-state subset is used "
        "only when a cell is unstable (CV>10%, mixed state or >10% IOPS spread), "
        "with the excluded rep ids disclosed. `default` and the batching sweep "
        "are informational only and never gate.")
    lines.append("")
    if gate is None:
        lines.append("_(write rule 1 not evaluated: no W7/W8 saturation cells "
                     "in this session yet)_")
    else:
        lines.append(render_write_gate_md(gate))
        lines.append("")
        case_v = gate.get("case_verdicts") or {}
        lines.append(
            "**Write-half rule-1 verdict: " + str(gate.get("verdict")) + "** — "
            "W7 " + str(case_v.get("W7", "INCONCLUSIVE")) + ", W8 " +
            str(case_v.get("W8", "INCONCLUSIVE")) + ". The write half is PASS "
            "only if both gated cells are PASS; FAIL if either is FAIL; "
            "otherwise INCONCLUSIVE (no write-parity claim). No speedup is "
            "claimed from overlapping CIs.")
    lines.append("")
    lines.append("### 9b.4 W7 fio batching sweep (methodology §2 W7 row; "
                 "informational — not a gate)")
    lines.append("")
    lines.append(render_write_sweep_md([c for c in cells if c["case"] == "W7"]))
    lines.append("")
    lines.append("### 9b.5 seastar write bracket (no pass threshold; seastar "
                 "is asymmetric by construction — no JUring ring flags, no "
                 "registered files/fixed buffers, io_queue path; methodology "
                 "§3.5)")
    lines.append("")
    lines.append(render_write_seastar_md(write_analysis.get("bracket") or [],
                                         set(WRITE_CASE_SPEC)))
    lines.append("")
    lines.append("### 9b.6 D2 device 1 s series, write cliff, burst vs steady "
                 "state, write amplification")
    lines.append("")
    lines.append(
        "Definitions (design §5 D2/D7): the **measured window** is the "
        "`measured_s` full 1 s samples before the final run-end transition "
        "sample (the last sampler interval is cut short by process exit and is "
        "dropped). **Cliff** = any measured-window sample below 70% of the run "
        "mean, or a one-step drop >30%. **Burst vs steady** = the first 10 s of "
        "the measured window vs the remainder; they are never pooled in a "
        "claim. **Write amplification** = device write-sector delta x 512 / "
        "engine measured-phase bytes from `state/runs.tsv` (fallback "
        "`cell-meta.tsv`); the sector delta spans the whole engine process "
        "(warmup+measured), so a 3:1 window yields ~3x. The sampler's "
        "`io_busy_pct` column is NOT used (sampler v1 units bug, ~100x off); "
        "only per-interval device counters are used. Thermal is the hwmon "
        "temperature in Celsius; `loadavg_start > 1.6` is flagged (F14 "
        "disclosure threshold).")
    lines.append("")
    lines.append(render_series_md(series))
    lines.append("")
    session = series.get("session") or {}
    summary_bits = []
    if session.get("thermal_max_c") is not None:
        summary_bits.append(
            f"session thermal range **{session['thermal_min_c']:.1f} - "
            f"{session['thermal_max_c']:.1f} C** "
            f"({session['n_thermal_values']} readings)")
    else:
        summary_bits.append("thermal readings unavailable (no root/smart-log)")
    summary_bits.append(f"{session.get('n_cliff', 0)} run(s) flagged with a "
                        f"write cliff")
    flagged = session.get("n_loadavg_flagged", 0)
    summary_bits.append(f"{flagged} run(s) with loadavg_start > "
                        f"{LOADAVG_FLAG}")
    lines.append("Session-level: " + "; ".join(summary_bits) + ".")
    lines.append("")
    lines.append("### 9b.7 Write provenance")
    lines.append("")
    provenance = []
    if units:
        used = units.get("units")
        if used == "us":
            provenance.append(
                f"- cell-meta p99 units: **microseconds** (manifest "
                f"`cell_meta_p99_units=us`); no ns->us normalisation was "
                f"applied to the cell-meta cross-check "
                f"({units.get('confirmed_us', 0)} row(s) verified as us).")
        elif units.get("legacy_ns", 0):
            provenance.append(
                f"- cell-meta p99 units: **nanoseconds for fio rows** "
                f"(legacy 2026-11-13 write block, no `cell_meta_p99_units` "
                f"flag); {units['legacy_ns']} value(s) normalised ns->us for "
                f"the cross-check only. Cited latencies come from the raw "
                f"JSON; the write cell-meta fio p99 is never cited.")
        else:
            provenance.append(
                f"- cell-meta p99 units: not declared "
                f"({units.get('source')}); no ns->us normalisation was needed "
                f"({units.get('legacy_ns', 0)} ns-like fio value(s)).")
    if series.get("runs_path"):
        provenance.append(f"- device/state ledger: `{jsonio.rel(series['runs_path'])}` "
                          f"(engine bytes, device sector delta, thermal, "
                          f"loadavg); fallback `cell-meta.tsv` "
                          f"`{jsonio.rel(series['meta_path'])}`.")
    provenance.append(f"- raw cells: `{jsonio.rel(session_dir / 'cells')}/"
                      f"{'<case>'}__*.json`; raw D2 series: "
                      f"`{jsonio.rel(session_dir / 'series')}/*.tsv`.")
    manifest_path = write_analysis.get("manifest_path")
    if manifest_path:
        provenance.append(f"- session manifest: `{jsonio.rel(manifest_path)}` "
                          f"(protocol/units provenance).")
    prov_path = session_dir / "state" / "precondition-results.txt"
    if prov_path.is_file():
        provenance.append(f"- D1 preconditioning record: `{jsonio.rel(prov_path)}` "
                          f"(bursts, condition/stop_reason, fsync, capped).")
    provenance.append("- every table above regenerates from the same raw files "
                      "by re-running the reproduce command; no number is "
                      "hand-typed. Reps are never trimmed.")
    lines.extend(provenance)
    lines.append("")
    return lines


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def build_summary(session_dir, cells, ratios, perf_report, iotester, bracket,
                  gate_rows, rule_verdicts, parsed, warnings, meta_check,
                  overall, argv_text, perf_check=None, iot_check=None,
                  manifest=None, write_analysis=None, analyzer_version=VERSION):
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    lines = []
    lines.append(f"# M1 aggregate — `{jsonio.rel(session_dir)}`")
    lines.append("")
    lines.append(f"Generated {now} by `bench/m1/analyze_read_write.py` "
                 f"(v{analyzer_version}). Stdlib only; every number below is "
                 f"derived from the raw files cited in the tables.")
    lines.append("")
    lines.append(f"- parsed: {parsed.get('harness', 0)} harness JSON, "
                 f"{parsed.get('fio', 0)} fio JSON, "
                 f"{parsed.get('perf', 0)} perf captures, "
                 f"{parsed.get('iotester', 0)} io_tester YAML, "
                 f"{parsed.get('skipped', 0)} other JSON skipped")
    engines = sorted({c["engine"] for c in cells},
                     key=lambda e: ENGINE_ORDER.get(e, 9))
    lines.append(f"- cells: {len(cells)}; engines: {', '.join(engines) or 'none'}")
    if manifest and manifest.get("lines"):
        lines.append(f"- session manifest: `{jsonio.rel(manifest['path'])}` "
                     f"({len(manifest['lines'])} lines)")
    lines.append("")
    if meta_check:
        extra = []
        if meta_check.get("ambiguous"):
            extra.append(f"{len(meta_check['ambiguous'])} ambiguous key(s) skipped "
                         f"(perf/read-matrix id collision)")
        if meta_check.get("collision_missing"):
            extra.append(f"{len(meta_check['collision_missing'])} fio perf-matched "
                         f"JSON absent (documented perf/perf-matched stem collision)")
        if meta_check["missing"]:
            extra.append(f"{len(meta_check['missing'])} cell(s) with no JSON")
        lines.append(
            f"- validation vs `cell-meta.tsv`: {meta_check['n_checked']}/"
            f"{meta_check.get('n_cells', meta_check['n_rows'])} current cell(s) "
            f"compared, max |ΔIOPS| = "
            f"{100.0 * meta_check['max_iops_rel_diff']:.3f}%, max |Δp99| = "
            f"{100.0 * meta_check['max_p99_rel_diff']:.3f}%"
            + (f"; {', '.join(extra)}" if extra else ""))
    if perf_check:
        extra = ""
        if perf_check.get("n_tsv_only"):
            extra += (f"; {perf_check['n_tsv_only']} fio-default row(s) are "
                      f"computed-only (raw capture overwritten by the documented "
                      f"id collision)")
        if perf_check["missing"]:
            extra += (f"; {len(perf_check['missing'])} capture(s) without a "
                      f"matching raw file")
        lines.append(
            f"- validation vs `perf-cells.tsv`: {perf_check['n_checked']} raw "
            f"capture(s) compared, max |Δcycles/op| = "
            f"{100.0 * perf_check['max_rel_diff']:.3f}%{extra}")
    if iot_check:
        lines.append(
            f"- validation vs `iotester-check.tsv`: {iot_check['n_checked']} "
            f"io_tester cell(s) compared, max |ΔIOPS| = "
            f"{100.0 * iot_check['max_iops_rel_diff']:.3f}%, max |Δp99| = "
            f"{100.0 * iot_check['max_p99_rel_diff']:.3f}%")
    if meta_check and meta_check.get("byte_rows"):
        lines.append(
            f"- device-side byte check (process-window gate, `cell-meta.tsv`): "
            f"{len(meta_check['byte_rows'])} cell(s), "
            f"{meta_check.get('n_byte_warn', 0)} outside ±10 % "
            f"(expected = iops*wall_s*sector_bytes, because sector deltas cover "
            f"startup+warmup+measured, not measured bytes alone)")
    lines.append("")
    lines.append("## 1. Throughput cells (mean ± two-sided 95% CI of the mean, "
                 "Student t, df=n-1)")
    lines.append("")
    lines.append(render_cells_md(cells))
    lines.append("")
    lines.append("## 2. Latency percentiles (mean of per-rep percentiles)")
    lines.append("")
    lines.append(render_latency_md(cells))
    lines.append("")
    lines.append("## 3. State gating — 4K randread QD64/QD128 "
                 f"(QD64 fast iff p99 < {FAST_P99_US_QD64:.0f} us, QD128 < "
                 f"{FAST_P99_US_QD128:.0f} us, plus IOPS < "
                 f"{IOPS_ANOMALY_FACTOR:.2f}× cell max = anomalous; reps never dropped)")
    lines.append("")
    lines.append("All-reps means are the primary statistics; the fast-state "
                 "subset is reported next to them (n and excluded rep ids) and "
                 "is applied symmetrically per cell. A rep is slow if either "
                 "signal fires. Gates use all reps unless the cell is unstable "
                 "(CV>10 %, mixed state, or >10 % IOPS spread), in which case "
                 "the fast-state subset is used and stated in the gate note.")
    lines.append("")
    lines.append(render_state_md(cells))
    lines.append("")
    lines.append("## 4. Paired ratios — all reps primary (paired by rep index; "
                 "90% CI = one-sided 95% bounds)")
    lines.append("")
    lines.append(render_ratios_md(ratios))
    lines.append("")
    lines.append("### 4b. State-gated paired ratios — fast-state subset "
                 "(excluded reps and any engine-classification difference disclosed)")
    lines.append("")
    lines.append(render_state_ratios_md(ratios))
    lines.append("")
    lines.append("## 5. seastar bracket (no pass threshold: ring "
                 "flags/registration are asymmetric, methodology §3.5)")
    lines.append("")
    lines.append(render_seastar_md(bracket))
    lines.append("")
    lines.append("## 6. CPU / syscalls per op (perf stat)")
    lines.append("")
    lines.append(render_perf_md(perf_report))
    lines.append("")
    lines.append("## 7. Device-side byte check — process-window gate "
                 "(cell-meta.tsv; expected = iops*wall_s*sector_bytes)")
    lines.append("")
    if meta_check and meta_check.get("byte_rows"):
        headers = ["case", "engine", "variant", "qd", "rep", "direction",
                   "actual sectors", "expected sectors (iops*wall_s)",
                   "ratio", "verdict (±10%)"]
        rows = [[b["case"], b["engine"], b["variant"], b["qd"], b["rep"],
                 b["direction"], f"{b['actual_sectors']:,}",
                 f"{b['expected_sectors']:,.0f}", f"{b['ratio']:.3f}",
                 b["verdict"]] for b in meta_check["byte_rows"]]
        lines.append(md.table(headers, rows))
        lines.append("")
        lines.append("Sector deltas cover the whole engine process, so the "
                     "comparison uses the process window (`iops*wall_s`), not "
                     "the measured bytes alone — a cell whose sector delta is "
                     "far from this is suspicious (stale/nonexistent file, "
                     "wrong device, buffered I/O, or a failed run).")
    else:
        lines.append("_(no cell-meta.tsv device-sector rows matched)_")
    lines.append("")
    lines.append("## 8. io_tester cross-check (seastar framework overhead probe)")
    lines.append("")
    lines.append(render_iotester_md(iotester, cells))
    lines.append("")
    lines.append("## 9. M1 gate table (methodology §7)")
    lines.append("")
    lines.append(render_gates_md(gate_rows))
    lines.append("")
    lines.append("**Overall M1 gate verdict: " + overall["verdict"] + "** — "
                 + overall["reason"])
    lines.append("")
    lines.append("| rule | verdict |")
    lines.append("|---|---|")
    for rule, verdict in rule_verdicts.items():
        lines.append(f"| {rule} | {verdict} |")
    lines.append("")
    if write_analysis is not None:
        lines.extend(_render_write_summary(session_dir, write_analysis))
    lines.append("## 10. Warnings / provenance")
    lines.append("")
    if warnings:
        for warning in warnings:
            lines.append(f"- {warning}")
    else:
        lines.append("- none")
    lines.append("")
    lines.append("## 11. Reproduce")
    lines.append("")
    lines.append("```bash")
    lines.append(argv_text)
    lines.append("```")
    lines.append("")
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Aggregate and gate one M1 measurement session (stdlib only).",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument("--dir", default=str(DEFAULT_DIR),
                        help="session directory with raw per-cell artifacts")
    parser.add_argument("--out-prefix", default=None,
                        help="write aggregate artifacts as <prefix>.summary.md, "
                             "<prefix>.cells.csv, ... (default: "
                             "bench/results/m1-2026-11-13/aggregate/m1 when "
                             "--dir is the M1 session; never writes into legacy dirs)")
    args = parser.parse_args(argv)

    session_dir = Path(args.dir).expanduser()
    if not session_dir.is_dir():
        print(f"error: --dir is not a directory: {session_dir}", file=sys.stderr)
        return 2

    argv_text = (f"python3 bench/m1/analyze_read_write.py --dir {jsonio.rel(session_dir)}"
                 + (f" --out-prefix {args.out_prefix}" if args.out_prefix else ""))
    warnings = []

    reps, perf_records, iotester, parsed = discover(session_dir, warnings)
    # drop failed/zero-IOPS measurement files (e.g. an engine that failed to start);
    # they are recorded as warnings, never silently kept as a 0-IOPS data point
    good_reps, dropped_reps = [], []
    for rep in reps:
        if rep.get("iops") is None or rep["iops"] <= 0:
            dropped_reps.append(rep)
        else:
            good_reps.append(rep)
    if dropped_reps:
        warnings.append(
            f"{len(dropped_reps)} cell file(s)/run(s) with no or zero IOPS dropped "
            f"from the statistics (failed engine runs): "
            + ", ".join(sorted({jsonio.rel(r["path"]) for r in dropped_reps})[:6])
            + (" ..." if len(dropped_reps) > 6 else ""))
    reps = good_reps
    perf_windows = load_perf_windows(session_dir, warnings)
    perf_tsv_rows = load_perf_cells_tsv(session_dir, warnings)
    manifest = load_session_manifests(session_dir, warnings)
    live_session = (manifest is not None or (session_dir / "cells").is_dir() or
                    (session_dir / "series").is_dir() or
                    (session_dir / "state").is_dir())
    if not reps and not perf_records and not iotester:
        if not live_session:
            print(f"error: no parseable artifacts found under {session_dir}",
                  file=sys.stderr)
            return 2
        warnings.append(
            "no per-cell JSON parsed yet (live/incomplete session?); the tables "
            "below are empty — re-run after the cells/*.json files are written")

    cells = build_cells(reps)
    if cells:
        attach_perf(perf_records, reps, cells, warnings, perf_windows)
    perf_report = build_perf_report(session_dir, perf_tsv_rows, perf_records, warnings)
    if perf_tsv_rows and not any(r.get("cycles_per_op") for r in perf_report):
        warnings.append("perf-cells.tsv rows parsed but no cycles/op values found")
    ratios = compute_ratios(cells)
    bracket, bracket_cases = evaluate_seastar_bracket(cells)
    write_cells_present = session_has_write(cells)
    read_cells_present = session_has_read(cells)
    write_only = write_cells_present and not read_cells_present

    gate1, v1, sat_present, write_gate = evaluate_throughput_gate(cells, warnings)
    gate2, v2 = evaluate_cpu_gate(cells, perf_records, warnings, perf_tsv_rows)
    gate3, v3 = evaluate_tail_gate(cells)
    if write_only:
        # Step A write half: the CPU and tail gates are read-path gates and are
        # explicitly out of scope for this session (docs/m1-write-design.md §9).
        gate2 = [{
            "rule": "2-cpu", "cells": "—", "juring": "—", "comparator": "—",
            "ratio": "—", "threshold": "95% UB of ratio <= 1.15",
            "verdict": "N/A", "half_width": "—",
            "note": ("read-path CPU gate not part of the Step A write design "
                     "(docs/m1-write-design.md §9)"),
        }]
        gate3 = [{
            "rule": "3-tail", "cells": "—", "juring": "—",
            "comparator": "—", "ratio": "—",
            "threshold": "p99 <= 2x fio p99", "verdict": "N/A",
            "half_width": "—",
            "note": ("write tail is descriptive only: no write tail gate in "
                     "Step A (docs/m1-write-design.md §9)"),
        }]
        v2, v3 = [], []
    gate5 = evaluate_ci_rule(cells, [])

    gate_rows = gate1 + gate2 + gate3 + gate5
    rule_verdicts = {
        "1 throughput (1s95 LB >= 0.95 at W3/W4, W7/W8)": stats.combine_verdicts(v1),
        "2 CPU (cycles/op within 15% of fio-matched)": stats.combine_verdicts(v2),
        "3 tails (W15/W16 p99 <= 2x fio p99)": stats.combine_verdicts(v3),
        "4 seastar bracket (no threshold)":
            (f"reported for {len(bracket_cases)} cell(s)"
             if bracket_cases else "no seastar cells"),
        "5 every cited number has a CI": gate5[0]["verdict"],
    }
    if write_only:
        rule_verdicts["2 CPU (cycles/op within 15% of fio-matched)"] = (
            "N/A (read-path gate; not part of the Step A write design)")
        rule_verdicts["3 tails (W15/W16 p99 <= 2x fio p99)"] = (
            "N/A (write tail is descriptive only)")
    # rule 5 over the cells cited by rules 1-3 (both write comparators included)
    cited = []
    for rat in ratios:
        if rat["kind"] in ("fio-matched", "fio-matchedbatch"):
            cited.append(rat["left"])
            cited.append(rat["right"])
    for case in list(SATURATION_CASES_READ) + list(SATURATION_CASES_WRITE):
        jc = gate_juring_cell(cells, case)
        fc = gate_fio_matched_cell(cells, case)
        cited.extend([x for x in (jc, fc) if x is not None])
    if write_cells_present:
        for case in WRITE_GATE_CASES:
            jc = write_juring_cell(cells, case)
            if jc is not None:
                cited.append(jc)
            for variant in (WRITE_PRIMARY_VARIANT,
                            WRITE_SENSITIVITY_VARIANT):
                fc = write_fio_cell(cells, case, variant)
                if fc is not None:
                    cited.append(fc)
    for case in LATENCY_CASES:
        jc = gate_juring_cell(cells, case)
        fc = gate_fio_matched_cell(cells, case)
        cited.extend([x for x in (jc, fc) if x is not None])
    gate5 = evaluate_ci_rule(cells, cited)
    gate_rows = gate1 + gate2 + gate3 + gate5
    rule_verdicts["5 every cited number has a CI"] = gate5[0]["verdict"]

    core = [rule_verdicts["1 throughput (1s95 LB >= 0.95 at W3/W4, W7/W8)"],
            rule_verdicts["2 CPU (cycles/op within 15% of fio-matched)"],
            rule_verdicts["3 tails (W15/W16 p99 <= 2x fio p99)"],
            rule_verdicts["5 every cited number has a CI"]]
    read_only = not write_cells_present
    if write_only:
        core = [rule_verdicts["1 throughput (1s95 LB >= 0.95 at W3/W4, W7/W8)"],
                rule_verdicts["5 every cited number has a CI"]]
    if "FAIL" in core:
        overall = {
            "verdict": "FAIL (write half)" if write_only else "FAIL",
            "reason": ("write rule 1 failed against at least one fio comparator "
                       "(95% UB < 0.95)" if write_only else
                       "at least one M1 gate rule failed (see table)")}
    elif all(v == "PASS" for v in core):
        if write_only:
            overall = {
                "verdict": ("PASS (write half; rule 1 at W7/W8 vs BOTH fio "
                            "comparators)"),
                "reason": ("rule 1 passes at both saturation cells against both "
                           "matchedbatch and matched; rule 5 passes; seastar "
                           "reported as a bracket"),
            }
        else:
            overall = {
                "verdict": "PASS" + (
                    " (read path only; write half W7/W8 not assessed)"
                    if read_only else ""),
                "reason": ("rules 1-3 and 5 pass at the saturation/latency "
                           "cells; seastar reported as a bracket"),
            }
    else:
        pending = [name for name, v in zip(  # noqa: B905
            ("rule 1 throughput", "rule 2 CPU", "rule 3 tails",
             "rule 5 CI"), core) if v != "PASS"]
        if write_only:
            overall = {
                "verdict": "INCONCLUSIVE (write half)",
                "reason": ("write rule 1 is not decidable from this session: "
                           "one-sided 95% LBs / 95% UBs against both fio "
                           "comparators are in the gate table; more reps or the "
                           "missing W7/W8 cells are needed. " + ", ".join(pending))}
        else:
            overall = {"verdict": "INCONCLUSIVE",
                       "reason": ("pending/insufficient data: " +
                                  ", ".join(pending) +
                                  ". Achieved CI half-widths are in the gate "
                                  "table; more reps or a registration-matched "
                                  "fio cell and perf captures are needed to "
                                  "decide.")}
    if read_only:
        rule_verdicts["1 throughput (1s95 LB >= 0.95 at W3/W4, W7/W8)"] = (
            rule_verdicts["1 throughput (1s95 LB >= 0.95 at W3/W4, W7/W8)"]
            + " [read cells only; W7/W8 writes not in session]")

    iot_check = load_iotester_check(session_dir, warnings)
    if not iotester and iot_check:
        # no raw YAML reachable: fall back to the harness's own io_tester summary
        for row in iot_check["rows"]:
            if row["engine"] != "seastar-io_tester" or row["variant"] == "setup":
                continue
            if not row.get("iops"):
                continue
            iotester.append({
                "path": iot_check["path"],
                "key": {"case": row["case"], "engine": "seastar-io_tester",
                        "variant": row["variant"], "bs": 4096, "qd": row["qd"],
                        "threads": 1, "rep": row["rep"] or 1},
                "n_shards": 1, "iops": row["iops"],
                "mibs": (row["throughput_kbs"] / 1024.0
                         if row.get("throughput_kbs") else None),
                "p50": row.get("p50") if row.get("p50", -1) > 0 else None,
                "p95": None,
                "p99": row.get("p99") if row.get("p99", -1) > 0 else None,
                "p999": row.get("p999") if row.get("p999", -1) > 0 else None,
                "max": row.get("max") if row.get("max", -1) > 0 else None,
                "lat_avg": None, "ops": None,
                "source": "iotester-check.tsv",
            })
        warnings.append("no io_tester YAML parsed; io_tester table uses the harness "
                        "iotester-check.tsv summary (raw YAML not found)")
    iot_check_report = cross_check_iotester(iotester, iot_check)

    meta_p99_units, meta_units_source = cell_meta_p99_units(
        session_dir, manifest, warnings)
    if meta_p99_units is not None and meta_p99_units != "us":
        warnings.append(
            f"cell-meta.tsv p99 units flag has unexpected value "
            f"'{meta_p99_units}' (expected 'us'); the legacy fio ns->us "
            f"normalisation is used for the cross-check")
    meta_check = cross_check_meta(session_dir, reps, perf_tsv_rows,
                                  meta_p99_units=meta_p99_units)
    perf_check = cross_check_perf_tsv(session_dir, perf_records, perf_tsv_rows)
    if iot_check_report and iot_check_report["mismatches"]:
        for mismatch in iot_check_report["mismatches"][:10]:
            warnings.append("iotester-check.tsv mismatch: " + mismatch)
    if meta_check and meta_check.get("byte_rows"):
        for row in meta_check["byte_rows"]:
            if row["verdict"] != "PASS":
                warnings.append(
                    f"process-window byte gate WARN: {row['case']}/{row['engine']}/"
                    f"{row['variant']}/r{row['rep']} {row['direction']} sectors "
                    f"{row['actual_sectors']:,} vs expected "
                    f"{row['expected_sectors']:,.0f} (iops*wall_s) = "
                    f"{100.0 * row['ratio']:.1f}%")
    if perf_check and perf_check["mismatches"]:
        for mismatch in perf_check["mismatches"][:20]:
            warnings.append("perf-cells.tsv mismatch: " + mismatch)
        if len(perf_check["mismatches"]) > 20:
            warnings.append(f"perf-cells.tsv: {len(perf_check['mismatches']) - 20} "
                            f"more mismatch(es) not listed")
    if perf_check and perf_check["missing"]:
        warnings.append(
            f"perf-cells.tsv: {len(perf_check['missing'])} capture(s) have no raw "
            f"perf/*.txt the analyzer could match (e.g. "
            f"{', '.join(perf_check['missing'][:5])})")
    if meta_check and meta_check["mismatches"]:
        for mismatch in meta_check["mismatches"][:20]:
            warnings.append("cell-meta mismatch: " + mismatch)
        if len(meta_check["mismatches"]) > 20:
            warnings.append(f"cell-meta: {len(meta_check['mismatches']) - 20} more "
                            f"mismatch(es) not listed")
    if meta_check and meta_check["missing"]:
        warnings.append(
            f"cell-meta.tsv lists {len(meta_check['missing'])} ok cell(s) with no "
            f"parsed JSON (e.g. {', '.join(meta_check['missing'][:5])})")
    if meta_check and meta_check.get("ambiguous"):
        warnings.append(
            f"cell-meta.tsv: {len(meta_check['ambiguous'])} key(s) are ambiguous "
            f"(perf phase reused the same case/engine/variant/qd/rep ids as the "
            f"read matrix; cell-meta has no phase column) — excluded from the "
            f"value cross-check, perf cells taken from perf-cells.tsv instead")
    if meta_check and meta_check.get("unit_normalized_p99"):
        warnings.append(
            f"cell-meta.tsv: {len(meta_check['unit_normalized_p99'])} fio p99 "
            f"value(s) are in nanoseconds (legacy 2026-11-13 write block; no "
            f"cell_meta_p99_units manifest flag) and were normalised ns->us for "
            f"the cross-check only; cited latency values come from the raw JSON")
    if meta_check and meta_check.get("meta_p99_units") == "us":
        warnings.append(
            f"cell-meta.tsv p99 units: microseconds for ALL engines (manifest "
            f"cell_meta_p99_units=us); no ns->us normalisation applied to the "
            f"cell-meta cross-check "
            f"({meta_check.get('confirmed_us_p99', 0)} row(s) checked as us). "
            f"Cited latencies still come from the raw engine JSON")
    elif (write_cells_present and meta_check
          and not meta_check.get("unit_normalized_p99")):
        warnings.append(
            "cell-meta.tsv p99 units are not declared (no cell_meta_p99_units "
            "manifest flag) and no fio nanosecond-looking values were detected; "
            "no normalisation applied — verify the units before citing "
            "cell-meta p99")
    if meta_check and meta_check.get("collision_missing"):
        warnings.append(
            f"cell-meta.tsv: {len(meta_check['collision_missing'])} fio "
            f"perf-matched JSON file(s) absent because of the documented "
            f"perf/perf-matched filename-stem collision; their computed numbers "
            f"are in perf-cells.tsv (see notes.md)")

    write_analysis = None
    analyzer_version = VERSION
    if write_cells_present:
        series_analysis = analyse_write_series(session_dir, cells, manifest,
                                               warnings)
        units_info = {
            "units": meta_p99_units, "source": meta_units_source,
            "confirmed_us": (meta_check or {}).get("confirmed_us_p99", 0),
            "legacy_ns": (meta_check or {}).get("legacy_ns_p99", 0),
        }
        write_analysis = {
            "cells": [c for c in cells if workload_is_write(c["workload"])],
            "ratios": [r for r in ratios if r["case"] in WRITE_CASE_SPEC],
            "gate": write_gate,
            "series": series_analysis,
            "bracket": bracket,
            "units": units_info,
            "manifest_path": manifest["path"] if manifest else None,
            "write_only": write_only,
        }
        analyzer_version = VERSION + "+write"
        if write_gate is None:
            warnings.append("write rule 1 not evaluated: no W7/W8 saturation "
                            "cells in this session (yet)")

    summary_md = build_summary(
        session_dir, cells, ratios, perf_report, iotester, bracket, gate_rows,
        rule_verdicts, parsed, warnings, meta_check, overall, argv_text, perf_check,
        iot_check_report, manifest, write_analysis=write_analysis,
        analyzer_version=analyzer_version)

    # ---- write policy ------------------------------------------------------
    # Only the canonical M1 read session may write its aggregate/ tree by
    # default. Every other session dir (including the legacy write block, whose
    # name contains "m1-2026-11-13") is RAW EVIDENCE: aggregate artifacts are
    # written only when --out-prefix is given explicitly.
    prefix = None
    if args.out_prefix:
        prefix = Path(args.out_prefix).expanduser()
    else:
        resolved = session_dir.resolve()
        if resolved == DEFAULT_DIR.resolve():
            prefix = session_dir / "aggregate" / "m1"
    written = {}
    write_payload = None
    if write_analysis is not None:
        write_payload = {
            "session_kind": "write-only" if write_only else "mixed",
            "rule1_verdict": (write_gate or {}).get("verdict"),
            "case_verdicts": (write_gate or {}).get("case_verdicts") or {},
            "comparators": [comparator_brief(c) for c in
                            (write_gate or {}).get("comparators", [])],
            "series": [series_brief(r) for r in
                       (write_analysis.get("series") or {}).get("rows") or []],
            "cell_meta_p99_units": meta_p99_units,
            "cell_meta_p99_units_source": meta_units_source,
            "series_source": jsonio.rel(session_dir / "series"),
            "runs_source": (jsonio.rel(write_analysis["series"]["runs_path"])
                            if (write_analysis.get("series") or {}).get("runs_path")
                            else None),
        }
    payload = {
        "schema": "m1-aggregate-1.0",
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "analyzer": f"bench/m1/analyze_read_write.py v{analyzer_version}",
        "dir": str(session_dir),
        "argv": argv_text,
        "parsed": parsed,
        "manifest": ({"path": jsonio.rel(manifest["path"]),
                      "lines": manifest["lines"]} if manifest else None),
        "cells": [cell_brief(c) for c in cells],
        "ratios": [ratio_brief(r) for r in ratios],
        "seastar_bracket": [ratio_brief({
            "case": b["case"], "qd": b["qd"], "kind": "seastar-bracket",
            "matched": False, "left": b["left"], "right": b["right"],
            "stats": b["stats"], "stats_fast": b["stats"],
            "left_slow": b["left"]["slow_ids"], "right_slow": b["right"]["slow_ids"],
            "classification_differs": (set(b["left"]["slow_ids"]) !=
                                       set(b["right"]["slow_ids"])),
            "note": ""}) for b in bracket],
        "perf": [perf_report_brief(r) for r in perf_report],
        "perf_source": jsonio.rel(session_dir / "perf-cells.tsv") if perf_tsv_rows else "perf/*.txt",
        "iotester": [{
            "case": it["key"]["case"], "variant": it["key"]["variant"],
            "bs": it["key"]["bs"], "qd": it["key"]["qd"],
            "n_shards": it["n_shards"], "iops": it["iops"],
            "mibs": it["mibs"], "p50": it.get("p50"), "p95": it.get("p95"),
            "p99": it["p99"], "p999": it["p999"], "max": it.get("max"),
            "ops": it["ops"], "source": jsonio.rel(it["path"]),
        } for it in iotester],
        "gate_rows": gate_rows,
        "rule_verdicts": rule_verdicts,
        "overall": overall,
        "iotester_check": ({
            "path": jsonio.rel(iot_check_report["path"]),
            "n_checked": iot_check_report["n_checked"],
            "max_iops_rel_diff": iot_check_report["max_iops_rel_diff"],
            "max_p99_rel_diff": iot_check_report["max_p99_rel_diff"],
            "mismatches": iot_check_report["mismatches"],
        } if iot_check_report else None),
        "device_bytes_check": ([{
            "case": b["case"], "engine": b["engine"], "variant": b["variant"],
            "qd": b["qd"], "rep": b["rep"], "direction": b["direction"],
            "actual_sectors": b["actual_sectors"],
            "expected_sectors": b["expected_sectors"],
            "ratio": b["ratio"], "verdict": b["verdict"],
        } for b in (meta_check.get("byte_rows") or [])] if meta_check else []),
        "perf_cells_check": ({
            "path": jsonio.rel(perf_check["path"]),
            "n_rows": perf_check["n_rows"],
            "n_checked": perf_check["n_checked"],
            "max_rel_diff": perf_check["max_rel_diff"],
            "missing": perf_check["missing"],
            "mismatches": perf_check["mismatches"],
            "n_tsv_only": perf_check.get("n_tsv_only", 0),
        } if perf_check else None),
        "cell_meta_check": ({
            "path": jsonio.rel(meta_check["path"]),
            "n_rows": meta_check["n_rows"],
            "n_cells": meta_check.get("n_cells", meta_check["n_checked"]),
            "n_checked": meta_check["n_checked"],
            "max_iops_rel_diff": meta_check["max_iops_rel_diff"],
            "max_p99_rel_diff": meta_check["max_p99_rel_diff"],
            "missing": meta_check["missing"],
            "mismatches": meta_check["mismatches"],
            "ambiguous": meta_check.get("ambiguous", []),
            "unit_normalized_p99": meta_check.get("unit_normalized_p99", []),
            "collision_missing": meta_check.get("collision_missing", []),
            "n_byte_rows": len(meta_check.get("byte_rows") or []),
            "n_byte_warn": meta_check.get("n_byte_warn", 0),
        } if meta_check else None),
        **({"write": write_payload} if write_payload is not None else {}),
        "warnings": warnings,
    }
    csv_tables = [
        ("cells_csv", [
            [c["case"], c["engine"], c["variant"], c["workload"],
             c["bs"], c["qd"], c["threads"], c["n"],
             c["iops_mean"], c["iops_sd"], c["ci95_low"], c["ci95_high"],
             c["ci95_half"], c["min"], c["median"], c["max"], c["cv_pct"],
             c["p50"], c["p99"], c["p999"], c["lat_max"],
             c["n_fast"], c["n_slow"], c["state"],
             int(c["state_valid"]), int(c["unstable"]),
             ";".join(c["sources"])]
            for c in cells],
         ["case", "engine", "variant", "workload", "bs", "qd", "threads", "n",
          "iops_mean", "iops_sd", "ci95_low", "ci95_high", "ci95_half", "min",
          "median", "max", "cv_pct", "p50_us", "p99_us", "p999_us", "max_us",
          "n_fast", "n_slow", "state", "state_valid", "unstable", "sources"]),
        ("reps_csv", [
            [r["case"], r["engine"], r["variant"], r["bs"], r["qd"], r["threads"],
             r["rep"], r["iops"], r["mibs"], r["p50"], r["p99"], r["p999"],
             r["max"], r["ops"], r["errors"], jsonio.rel(r["path"])]
            for r in sorted(reps, key=lambda x: (
                cell_sort_key(x), ENGINE_ORDER.get(x["engine"], 9),
                str(x["variant"]), rep_sort_key(x["rep"])))],
         ["case", "engine", "variant", "bs", "qd", "threads", "rep", "iops",
          "mibs", "p50_us", "p99_us", "p999_us", "max_us", "ops", "errors",
          "source"]),
        ("ratios_csv", [
            [r["case"], r["qd"], r["kind"], r["left"]["engine"],
             r["left"]["variant"], r["right"]["engine"], r["right"]["variant"],
             r["stats"]["n_pairs"], r["stats"]["mean"], r["stats"]["sd"],
             r["stats"]["ci90_low"], r["stats"]["ci90_high"],
             r["stats"]["ci95_low"], r["stats"]["ci95_high"],
             r["stats"]["lb95_1s"], r["stats"]["ub95_1s"],
             r["stats"]["ci95_half"], r["stats"]["ci95_half_pct"],
             r["note"]]
            for r in ratios],
         ["case", "qd", "kind", "left_engine", "left_variant", "right_engine",
          "right_variant", "n_pairs", "mean_ratio", "paired_sd", "ci90_low",
          "ci90_high", "ci95_low", "ci95_high", "lb95_one_sided",
          "ub95_one_sided", "ci95_half", "ci95_half_pct", "note"]),
        ("perf_csv", [
            [r.get("case"), r.get("engine"), r.get("variant"), r.get("qd"),
             r.get("rep"), r.get("elapsed"), r.get("ops"),
             r.get("cycles_per_op"), r.get("instr_per_op"),
             r.get("syscalls_per_op"), r.get("ctxt_per_op"), jsonio.rel(r["path"])]
            for r in perf_records],
         ["case", "engine", "variant", "qd", "rep", "window_s", "ops_window",
          "cycles_per_op", "instr_per_op", "syscalls_per_op", "ctxt_per_op",
          "source"]),
        ("gates_csv", [
            [r["rule"], r["cells"], r["juring"], r["comparator"], r["ratio"],
             r["threshold"], r["verdict"], r["half_width"], r["note"]]
            for r in gate_rows],
         ["rule", "cells", "juring", "fio_matched", "ratio", "threshold",
          "verdict", "ci_half_width", "note"]),
        ("iotester_csv", [
            [it["key"]["case"], it["key"]["variant"], it["key"]["qd"],
             it["n_shards"], it["iops"], it["mibs"], it["p99"], it["p999"],
             jsonio.rel(it["path"])]
            for it in iotester],
         ["case", "variant", "qd", "shards", "iops", "mibs", "p99_us",
          "p999_us", "source"]),
    ]

    if write_analysis is not None:
        csv_tables.extend(_write_csv_tables(write_analysis, write_gate))

    if prefix is not None:
        written = write_outputs(prefix, summary_md, csv_tables, payload)
        print(summary_md)
        print()
        print("artifacts written:")
        for key, path in written.items():
            print(f"  {key:16s} {jsonio.rel(path)}")
    else:
        print(summary_md)
        print()
        print("note: aggregate artifacts NOT written. Only the canonical M1 read "
              "session (bench/results/m1-2026-11-13) writes its aggregate/ tree "
              "automatically; every other session dir is raw evidence and "
              "requires an explicit --out-prefix. Pass --out-prefix <path> to "
              "emit them.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
