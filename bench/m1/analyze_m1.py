#!/usr/bin/env python3
"""M1 read-path analysis: aggregate + statistics + M1 gate verdicts.

Reads one measurement-session directory (default
``bench/results/m1-2026-11-13``), parses every per-cell raw artifact, and emits
markdown tables + CSV + a machine-readable aggregate under the aggregate dir.

Supported raw formats
---------------------
* JUring / seastar_file_bench JSON, `schema_version` 1.0 (``runs[]`` or
  ``results[]`` with ``iops``/``mibs``/``latency_us``; the methodology schema in
  docs/benchmark-methodology.md §8 uses ``results[]``, the harnesses emit
  ``runs[]`` -- both are accepted).
* fio JSON (``jobs[].read`` or ``.write``: ``iops``, ``bw_bytes``,
  ``clat_ns.percentile`` keys such as ``"99.000000"``; job-level CPU/ctx fields).
* Optional ``perf stat`` text files (``perf/*.txt`` or any ``*perf*.txt``),
  plain text, ``-x,`` CSV or ``-j`` JSON-lines output.
* Optional seastar ``io_tester`` YAML files (``iotester/*.yaml``).

Cell identity
-------------
Session layout (bench/m1/lib.sh)::

    <dir>/cells/<case>__<engine>__<variant>__bs<bs>__qd<qd>__t1__r<rep>.json
    <dir>/perf/<same id>[.perf].txt
    <dir>/iotester/<same id>.yaml
    <dir>/cell-meta.tsv                       (harness's own per-cell summary)

Legacy 2026-11-13 layout is also parsed so this tool can be validated against
docs/baseline-2026-11-13.md §9.2::

    juring/rr4k_qd64_rep1.json                -> W3 juring   randread 4K QD64
    seastar/sr_rr4k_qd64_rep1.json            -> W3 seastar  randread 4K QD64
    fio/a-randread-4k-uring-qd64_rep1.json    -> W3 fio-io_uring  (b1)
    fio/d-...-batch16_rep1.json               -> W3 fio-io_uring  batch16
    fio/e-...-nj2_rep1.json                   -> W3-mj2 (numjobs=2, not M1)

Statistics
----------
Per (case, engine, variant, bs, qd, threads): n, mean, sample SD, two-sided
95% CI of the mean (Student t, df=n-1), min/median/max, CV%.  Engines are
paired by rep index; the paired ratio JUring/comparator reports the mean, the
paired SD of per-rep ratios, and 90%/95% CIs (the two-sided 90% bounds equal the
one-sided 95% bounds used by the M1 gate).

State gating (§6 of the baseline doc / task-1): for 4K randread at QD64/QD128
each rep is classified fast-state (p99 < 600 us) or slow-state; per-state means
are always reported and reps are never dropped.  A cell with CV > 10 %, mixed
states, or > 10 % IOPS spread across reps is flagged unstable.

M1 gate (§7 of docs/benchmark-methodology.md)
----------------------------------------------
1. throughput at saturation QDs: two-sided 95% CI lower bound of the paired
   ratio JUring/fio-matched >= 0.95;
2. CPU: cycles/op (perf, user+kernel) within 15 % of fio-matched (90% CI of the
   ratio inside [0.85, 1.15]);
3. tails: W15/W16 p99 <= 2x fio p99 (parity bars 1.10x p99 / 1.15x p999 are
   reported separately);
4. seastar is reported as a bracket, no threshold (its ring flags/registration
   are asymmetric -- docs/benchmark-methodology.md §3.5);
5. every cited number has a CI (a cell with n < 2 is "inconclusive, more reps").

Verdicts are PASS / FAIL / INCONCLUSIVE, always with the achieved CI half-width.

Write policy
------------
Aggregate artifacts are written only when ``--out-prefix`` is given, or when
``--dir`` is the M1 session dir (then under ``<dir>/aggregate/``).  The legacy
2026-11-13 results directory is never written to.

Stdlib only (no numpy/scipy/PyYAML).
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import re
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

VERSION = "1.0"
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DIR = ROOT / "bench/results/m1-2026-11-13"

# --- policy constants (docs/benchmark-methodology.md §6/§7, task-1) --------
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
CPU_NONINFERIOR_LIMIT = 1.15  # gate: JUring cycles/op <= 1.15 x fio-matched      # rule 2: within 15 % of fio
TAIL_FACTOR = 2.0            # rule 3: p99 <= 2x fio
P99_PARITY = 1.10
P999_PARITY = 1.15

ENGINE_ORDER = {"juring": 0, "fio-io_uring": 1, "fio-libaio": 2,
                "seastar": 3, "seastar-io_tester": 4}
VARIANT_ORDER = {"std": 0, "matched": 1, "uring": 2, "b1": 3, "default": 4,
                 "libaio": 5}

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
# formatting helpers
# ---------------------------------------------------------------------------

def as_float(value):
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def as_int(value):
    v = as_float(value)
    return int(v) if v is not None else None


def fnum(value, nd=None):
    """Human number; no decimal scaling games, just thousand separators."""
    if value is None:
        return "—"
    if nd is None:
        a = abs(value)
        if a >= 1000:
            return f"{value:,.0f}"
        if a >= 100:
            return f"{value:,.1f}"
        return f"{value:,.2f}"
    return f"{value:,.{nd}f}"


def fci(mean, half, nd=None):
    if mean is None:
        return "—"
    if half is None:
        return f"{fnum(mean, nd)} (n<2)"
    return f"{fnum(mean, nd)} ± {fnum(half, nd)}"


def fratio(ratio):
    if ratio is None:
        return "—"
    return f"{ratio:.4f} ({100.0 * (ratio - 1.0):+.1f}%)"


def fci_ratio(mean, half):
    if mean is None:
        return "—"
    if half is None:
        return f"{mean:.4f} (n<2)"
    return f"{mean:.4f} ± {half:.4f}"


def rel(path):
    try:
        return str(Path(path).resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def md_table(headers, rows):
    out = ["| " + " | ".join(headers) + " |",
           "|" + "|".join("---" for _ in headers) + "|"]
    for row in rows:
        out.append("| " + " | ".join(str(c) for c in row) + " |")
    return "\n".join(out)


# ---------------------------------------------------------------------------
# Student t distribution (stdlib only, Numerical-Recipes style incomplete beta)
# ---------------------------------------------------------------------------

_T_CACHE = {}


def _betacf(a, b, x):
    maxit, eps, fpmin = 300, 3.0e-14, 1.0e-300
    qab, qap, qam = a + b, a + 1.0, a - 1.0
    c = 1.0
    d = 1.0 - qab * x / qap
    if abs(d) < fpmin:
        d = fpmin
    d = 1.0 / d
    h = d
    for m in range(1, maxit + 1):
        m2 = 2 * m
        aa = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + aa * d
        if abs(d) < fpmin:
            d = fpmin
        c = 1.0 + aa / c
        if abs(c) < fpmin:
            c = fpmin
        d = 1.0 / d
        h *= d * c
        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + aa * d
        if abs(d) < fpmin:
            d = fpmin
        c = 1.0 + aa / c
        if abs(c) < fpmin:
            c = fpmin
        d = 1.0 / d
        delta = d * c
        h *= delta
        if abs(delta - 1.0) < eps:
            break
    return h


def _betai(a, b, x):
    if x <= 0.0:
        return 0.0
    if x >= 1.0:
        return 1.0
    lbeta = math.lgamma(a + b) - math.lgamma(a) - math.lgamma(b)
    bt = math.exp(lbeta + a * math.log(x) + b * math.log(1.0 - x))
    if x < (a + 1.0) / (a + b + 2.0):
        return bt * _betacf(a, b, x) / a
    return 1.0 - bt * _betacf(b, a, 1.0 - x) / b


def t_cdf(t, df):
    x = df / (df + t * t)
    ib = _betai(df / 2.0, 0.5, x)
    return 1.0 - 0.5 * ib if t >= 0 else 0.5 * ib


def t_ppf(p, df):
    """Inverse Student-t CDF, p in (0, 1), df > 0."""
    if df <= 0:
        raise ValueError("df must be > 0")
    if not 0.0 < p < 1.0:
        raise ValueError("p must be in (0, 1)")
    key = (round(p, 10), int(df))
    if key in _T_CACHE:
        return _T_CACHE[key]
    lo, hi = -1.0e4, 1.0e4
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        if t_cdf(mid, df) < p:
            lo = mid
        else:
            hi = mid
    val = 0.5 * (lo + hi)
    _T_CACHE[key] = val
    return val


# ---------------------------------------------------------------------------
# descriptive statistics
# ---------------------------------------------------------------------------

def describe(values):
    """n / mean / sample SD / 95% CI of mean / min / median / max / CV%."""
    vals = [float(v) for v in values if v is not None]
    n = len(vals)
    if n == 0:
        return {"n": 0, "mean": None, "sd": None, "ci95_low": None,
                "ci95_high": None, "ci95_half": None, "min": None,
                "median": None, "max": None, "cv_pct": None}
    mean = statistics.fmean(vals)
    if n > 1:
        sd = statistics.stdev(vals)
        half = t_ppf(0.975, n - 1) * sd / math.sqrt(n)
        cv = 100.0 * sd / mean if mean else None
    else:
        sd = None
        half = None
        cv = None
    return {
        "n": n, "mean": mean, "sd": sd,
        "ci95_low": (mean - half) if half is not None else None,
        "ci95_high": (mean + half) if half is not None else None,
        "ci95_half": half,
        "min": min(vals), "median": statistics.median(vals), "max": max(vals),
        "cv_pct": cv,
    }


def ratio_stats(left_reps, right_reps):
    """Paired-by-rep-index ratio left/right with 90%/95% CIs of the mean."""
    lmap = {r["rep_index"]: r for r in left_reps}
    rmap = {r["rep_index"]: r for r in right_reps}
    common = sorted(set(lmap) & set(rmap))
    ratios = []
    for idx in common:
        a = lmap[idx].get("iops")
        b = rmap[idx].get("iops")
        if a and b:
            ratios.append(a / b)
    n = len(ratios)
    out = {
        "n_pairs": n, "mean": None, "sd": None, "ci90_low": None,
        "ci90_high": None, "ci95_low": None, "ci95_high": None,
        "lb95_1s": None, "ub95_1s": None, "ci95_half": None,
        "ci95_half_pct": None, "ratios": ratios, "paired_reps": common,
        "ratio_of_means": None, "n_left": len(left_reps), "n_right": len(right_reps),
    }
    lmean = describe([r.get("iops") for r in left_reps])["mean"]
    rmean = describe([r.get("iops") for r in right_reps])["mean"]
    if lmean and rmean:
        out["ratio_of_means"] = lmean / rmean
    if n == 0:
        return out
    mean = statistics.fmean(ratios)
    out["mean"] = mean
    if n > 1:
        sd = statistics.stdev(ratios)
        se = sd / math.sqrt(n)
        t90 = t_ppf(0.95, n - 1)
        t95 = t_ppf(0.975, n - 1)
        out.update({
            "sd": sd,
            "ci90_low": mean - t90 * se, "ci90_high": mean + t90 * se,
            "ci95_low": mean - t95 * se, "ci95_high": mean + t95 * se,
            # two-sided 90% bounds == one-sided 95% bounds
            "lb95_1s": mean - t90 * se, "ub95_1s": mean + t95 * se,
            "ci95_half": t95 * se,
            "ci95_half_pct": 100.0 * t95 * se / mean if mean else None,
        })
    return out


def combine_verdicts(verdicts):
    """Aggregate per-cell verdicts -> PASS / FAIL / INCONCLUSIVE."""
    vs = [v for v in verdicts if v]
    if not vs:
        return "INCONCLUSIVE"
    if any(v == "FAIL" for v in vs):
        return "FAIL"
    if all(v == "PASS" for v in vs):
        return "PASS"
    return "INCONCLUSIVE"


def lower_bound_verdict(st, threshold):
    """PASS iff one-sided 95% lower bound >= threshold; FAIL iff 95% upper
    bound < threshold (statistically below); else inconclusive."""
    if st is None or st.get("n_pairs", 0) < 2 or st.get("lb95_1s") is None:
        return "INCONCLUSIVE", "n<2 reps (no CI)"
    if st["lb95_1s"] >= threshold:
        return "PASS", f"1s95 LB {st['lb95_1s']:.4f} >= {threshold}"
    if st["ub95_1s"] is not None and st["ub95_1s"] < threshold:
        return "FAIL", f"95% UB {st['ub95_1s']:.4f} < {threshold}"
    return ("INCONCLUSIVE",
            f"CI [{st['ci95_low']:.4f}, {st['ci95_high']:.4f}] spans {threshold}; "
            f"half-width {st['ci95_half']:.4f} "
            f"({st['ci95_half_pct']:.1f}% of mean)")


def upper_bound_verdict(st, limit):
    """PASS iff 95% upper bound <= limit; FAIL iff one-sided 95% lower bound
    > limit; else inconclusive."""
    if st is None or st.get("n_pairs", 0) < 2 or st.get("ci95_high") is None:
        return "INCONCLUSIVE", "n<2 reps (no CI)"
    if st["ci95_high"] <= limit:
        return "PASS", f"95% UB {st['ci95_high']:.4f} <= {limit}"
    if st["lb95_1s"] is not None and st["lb95_1s"] > limit:
        return "FAIL", f"1s95 LB {st['lb95_1s']:.4f} > {limit}"
    return ("INCONCLUSIVE",
            f"CI [{st['ci95_low']:.4f}, {st['ci95_high']:.4f}] spans {limit}; "
            f"half-width {st['ci95_half']:.4f}")


def noninferior_verdict(st, limit):
    """Non-inferiority: PASS iff the 95% upper bound of the ratio <= limit.

    The M1 CPU gate compares JUring's cycles/op with fio's; a JUring CPU cost
    *below* fio's is an advantage, not a failure, so the gate is one-sided
    ("JUring not more than 15 % worse"). The literal two-sided ±15 % band is
    reported next to it for completeness.
    """
    if st is None or st.get("n_pairs", 0) < 2 or st.get("ci95_high") is None:
        return "INCONCLUSIVE", "n<2 reps (no CI)"
    if st["ci95_high"] <= limit:
        return "PASS", f"95% UB {st['ci95_high']:.4f} <= {limit} (non-inferior)"
    if st.get("lb95_1s") is not None and st["lb95_1s"] > limit:
        return "FAIL", f"1s95 LB {st['lb95_1s']:.4f} > {limit}"
    return ("INCONCLUSIVE",
            f"CI [{st['ci95_low']:.4f}, {st['ci95_high']:.4f}] spans {limit}")


def band_verdict(st, band):
    """PASS iff two-sided 90% CI of the ratio is inside `band` (TOST-style,
    same convention as the ±5% parity rule in methodology §7)."""
    lo, hi = band
    if st is None or st.get("n_pairs", 0) < 2 or st.get("ci90_low") is None:
        return "INCONCLUSIVE", "n<2 reps (no CI)"
    if st["ci90_low"] >= lo and st["ci90_high"] <= hi:
        return "PASS", f"90% CI [{st['ci90_low']:.4f}, {st['ci90_high']:.4f}] inside [{lo}, {hi}]"
    if st["ci90_high"] < lo or st["ci90_low"] > hi:
        return "FAIL", f"90% CI [{st['ci90_low']:.4f}, {st['ci90_high']:.4f}] outside [{lo}, {hi}]"
    return ("INCONCLUSIVE",
            f"90% CI [{st['ci90_low']:.4f}, {st['ci90_high']:.4f}] wider than [{lo}, {hi}]; "
            f"half-width {st['ci95_half']:.4f}")


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
    """Parse the session cell id `<case>__<engine>__<variant>__bs<bs>__qd<qd>__t<n>__r<rep>`.

    Tolerates extra/missing optional tokens; returns None if this does not look
    like a cell id.
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

def load_json(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return json.load(fh), None
    except Exception as exc:  # corrupt/half-written file: warn, do not crash
        return None, str(exc)


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
            value = as_float(lat.get(key))
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
    bs = as_int(cfg.get("bs")) or (key or {}).get("bs")
    qd = as_int(cfg.get("qd")) or (key or {}).get("qd")
    threads = as_int(cfg.get("threads")) or (key or {}).get("threads") or 1
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
            warnings.append(f"{rel(path)}: not a matrix cell (no cell id, no "
                            f"legacy rr4k/sq1m/nop label); skipped")
            return []
    buffer_mode = cfg.get("io_buffer_mode")
    ring_flags = cfg.get("ring_flags")
    if isinstance(ring_flags, list):
        ring_flags = "+".join(ring_flags)
    reg_files = cfg.get("registered_files")
    reg_buffers = cfg.get("registered_buffers")
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
            iops = as_float(row.get("iops"))
            if iops is None:
                iops = as_float(row.get("IOPS"))
            mibs = as_float(row.get("mibs"))
            if mibs is None:
                mibs = as_float(row.get("mib_s"))
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
                ops=as_float(row.get("ops")) or as_float(row.get("ops_total")),
                errors=as_int(row.get("errors")) or as_int(row.get("errors_total")),
                p50=_lat(lat, "p50"), p90=_lat(lat, "p90"), p95=_lat(lat, "p95"),
                p99=_lat(lat, "p99"), p999=_lat(lat, "p999"),
                max=_lat(lat, "max"), lat_avg=_lat(lat, "avg", "average"),
                buffer_mode=buffer_mode, ring_flags=ring_flags,
                registered_files=reg_files, fixed_buffers=fixed,
                source_kind="harness", run_index=idx,
            ))
        return reps
    # no runs[]: fall back to the aggregate block (single pseudo-rep)
    iops = as_float((agg.get("iops") or {}).get("mean"))
    mibs = as_float((agg.get("mibs") or {}).get("mean"))
    lat = agg.get("latency_us") or {}
    if iops is None:
        return []
    reps.append(make_rep(
        path=path, case=case, engine=engine, variant=variant,
        workload=workload, bs=bs, qd=qd, threads=threads,
        rep=f"r{base_rep}", rep_index=base_rep,
        iops=iops, mibs=mibs, ops=as_float(agg.get("ops_total")),
        errors=as_int(agg.get("errors_total")),
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
            value = as_float(block.get("max"))
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
                    best = (kf, as_float(v))
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
            qd = as_int(opts.get("iodepth"))
        nj = as_int(opts.get("numjobs"))
        if nj:
            numjobs = nj
        for opt, name in (("registerfiles", "reg"), ("fixedbufs", "fixed"),
                          ("iodepth_batch_submit", "batch"),
                          ("sqthread_poll", "sqpoll")):
            if opt in opts:
                value = as_int(opts.get(opt))
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
        read_bytes = as_float(read.get("io_bytes")) or 0.0
        write_bytes = as_float(write.get("io_bytes")) or 0.0
        sec = write if write_bytes > read_bytes else read
        iops_total += as_float(sec.get("iops")) or 0.0
        mibs_total += (as_float(sec.get("bw_bytes")) or 0.0) / (1024.0 * 1024.0)
        ops_total += as_float(sec.get("total_ios")) or 0.0
        for vals, target in ((p50s, 50.0), (p95s, 95.0), (p99s, 99.0),
                             (p999s, 99.9), (maxs, 100.0)):
            v = fio_percentile(sec, target)
            if v is not None:
                vals.append(v)
        err = as_int(job.get("error"))
        errors_total += err or 0
        job_cpu = job.get("cpu")
        if isinstance(job_cpu, dict):
            cpu_user_s += as_float(job_cpu.get("user")) or 0.0
            cpu_sys_s += as_float(job_cpu.get("sys")) or 0.0
        else:
            # fio 3.40 JSON: job-level usr_cpu/sys_cpu are percentages of wall
            usr = as_float(job.get("usr_cpu"))
            sys_ = as_float(job.get("sys_cpu"))
            runtime_ms = as_float(job.get("job_runtime"))
            if runtime_ms is None:
                runtime_ms = (as_float(job.get("elapsed")) or 0.0) * 1000.0
            if usr is not None and runtime_ms:
                cpu_user_s += usr / 100.0 * runtime_ms / 1000.0
            if sys_ is not None and runtime_ms:
                cpu_sys_s += sys_ / 100.0 * runtime_ms / 1000.0
        ctxt += as_float(job.get("ctx")) or 0.0

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
        warnings.append(f"{rel(path)}: no cell id match; case named {case}")
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
    """Return ({event: value}, elapsed_seconds or None).

    Accepts perf stat plain text, `-x,` CSV and `-j` JSON-lines output.
    """
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


def load_perf_files(session_dir, warnings):
    """Parse all perf text files under <dir>/perf (plus *perf*.txt elsewhere)."""
    perf_dir = session_dir / "perf"
    candidates = []
    if perf_dir.is_dir():
        candidates.extend(sorted(perf_dir.rglob("*")))
    for path in sorted(session_dir.rglob("*.txt")):
        if path in candidates:
            continue
        if "perf" in path.name.lower() or "perf" in str(path.parent).lower():
            candidates.append(path)
    records = []
    for path in candidates:
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except Exception as exc:
            warnings.append(f"{rel(path)}: unreadable perf file ({exc})")
            continue
        if not looks_like_perf(text):
            continue
        counters, elapsed = parse_perf_text(text)
        if not counters:
            warnings.append(f"{rel(path)}: no perf counters parsed")
            continue
        stem = strip_perf_suffixes(path.name[: -len(path.suffix)] if path.suffix else path.name)
        key = parse_cell_stem(stem)
        rec = {"path": path, "key": key, "counters": counters,
               "elapsed": elapsed, "rep": key.get("rep") if key else None,
               "case": key.get("case") if key else None,
               "engine": key.get("engine") if key else None,
               "variant": key.get("variant") if key else None,
               "bs": key.get("bs") if key else None,
               "qd": key.get("qd") if key else None,
               "ops": None, "cycles": None, "instr": None, "syscalls": None,
               "ctxt": None, "cycles_per_op": None, "instr_per_op": None,
               "syscalls_per_op": None, "ctxt_per_op": None}
        if key is None:
            warnings.append(f"{rel(path)}: perf file name does not encode a cell id")
        records.append(rec)
    return records


def load_perf_windows(session_dir, warnings):
    """Map (case, engine, variant, qd, rep) -> perf window seconds from perf-cells.tsv.

    run-perf-cells.sh attaches `perf stat -p PID ... -- sleep 30` inside the
    engine's measured phase; the window is NOT in the `-x,` perf text, so the
    cell id -> window mapping is read from the harness's perf-cells.tsv. Later
    rows win (a cell may be rerun).
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
                           as_int((row.get("qd") or "").strip()),
                           as_int((row.get("rep") or "").strip()))
                    window = as_float((row.get("window_s") or "").strip())
                except Exception:
                    continue
                if window and window > 0:
                    windows[key] = window
    except Exception as exc:
        warnings.append(f"{rel(path)}: could not read perf windows ({exc})")
        return {}
    return windows


def load_perf_cells_tsv(session_dir, warnings):
    """Load perf-cells.tsv: authoritative per-rep CPU/syscall numbers.

    run-perf-cells.sh computed these live from each raw `perf stat` capture and
    wrote them per rep. In this session the fio `perf` (default) and
    `perf-matched` runs shared the same filename stem, so the raw JSON/txt of
    the default run was overwritten (documented in notes.md); the *computed*
    rows for BOTH fio variants survive here. The analyzer therefore treats this
    table as authoritative for per-op CPU, with raw captures used only where
    they still exist (juring, seastar, fio-matched-via-`__perf__`-stem).
    """
    path = session_dir / "perf-cells.tsv"
    if not path.is_file():
        return []
    rows_out = []
    try:
        with open(path, newline="", encoding="utf-8", errors="replace") as fh:
            rows = list(csv.DictReader(fh, delimiter="\t"))
    except Exception as exc:
        warnings.append(f"{rel(path)}: unreadable perf-cells.tsv ({exc})")
        return []
    for row in rows:
        def num(name):
            return as_float((row.get(name) or "").strip())
        rep = as_int((row.get("rep") or "").strip())
        qd = as_int((row.get("qd") or "").strip())
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
        return [dict(rec, provenance="raw perf capture", raw_source=rel(rec["path"]))
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
            raw_source = rel(raw["path"]) if raw else None
        elif raw is not None:
            provenance = "raw perf capture"
            raw_source = rel(raw["path"])
        else:
            provenance = "computed record only (raw capture not found)"
            raw_source = None
        entry = dict(row)
        entry["source"] = rel(row["path"])
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
                f"{rel(rec['path'])}: perf window unknown (no {window_source} entry); "
                f"cycles/op assumes the full measured run window")
        if ops is None:
            cand = [v for v in cell_ops.get(k_cell, []) if v]
            ops = statistics.fmean(cand) if cand else None
        counters = rec["counters"]

        def get(*names):
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
            warnings.append(f"{rel(rec['path'])}: no cycles counters found")


def load_session_manifest(session_dir, warnings):
    """Read manifest.txt (session environment manifest) for the aggregate payload."""
    path = session_dir / "manifest.txt"
    if not path.is_file():
        return None
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except Exception as exc:
        warnings.append(f"{rel(path)}: unreadable manifest.txt ({exc})")
        return None
    return {"path": path, "text": text, "lines": text.splitlines()}


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
            warnings.append(f"{rel(path)}: unreadable io_tester file ({exc})")
            continue
        if "IOPS" not in text:
            continue
        shards = parse_iotester_yaml(text)
        if not shards:
            warnings.append(f"{rel(path)}: io_tester YAML had no '- shard:' block")
            continue
        def pick_any(keys):
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
            warnings.append(f"{rel(path)}: io_tester file name does not encode a cell id")
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
            data, err = load_json(path)
            if err is not None:
                warnings.append(f"{rel(path)}: not valid JSON ({err})")
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
                    warnings.append(f"{rel(path)}: harness JSON without usable runs")
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
    # io_tester YAML
    iotester = load_iotester(session_dir, warnings)
    parsed["iotester"] = len(iotester)

    if perf_paths:
        records = []
        for path in sorted(perf_paths):
            text = path.read_text(encoding="utf-8", errors="replace")
            counters, elapsed = parse_perf_text(text)
            if not counters:
                warnings.append(f"{rel(path)}: no perf counters parsed")
                continue
            stem = strip_perf_suffixes(path.name[: -len(path.suffix)] if path.suffix else path.name)
            key = parse_cell_stem(stem)
            if key is None:
                warnings.append(f"{rel(path)}: perf file name does not encode a cell id")
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

    Two symmetric signals are evaluated for every rep and both are reported:

    1. **p99 criterion**, only for single-thread 4 KiB randread QD64/QD128
       (the documented bimodal state cells):
         QD64  -> fast iff p99 < 600 us
         QD128 -> fast iff p99 < 1200 us   (2x QD scaling, Lead calibration)
    2. **IOPS criterion** (all cells): a rep is anomalous/slow if its IOPS is
       below 0.92 x the maximum IOPS of the same (case, engine, variant) cell.
       This catches the QD64 255k-vs-325k slow state and low-throughput
       transients (e.g. a QD1 fio-matched rep at ~88% of its cell max) without
       trimming anything.

    A rep with either signal is "slow". The cell always keeps its all-reps
    statistics (primary, never cherry-picked) and additionally exposes the
    fast-state subset (n, mean, 95% CI, excluded rep ids) for symmetric
    disclosure; gates use all reps unless the cell is flagged unstable, in which
    case the fast-state subset is used and stated explicitly with its CI.
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
    cell["fast_stats"] = describe([r["iops"] for r in fast])
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
        iops_st = describe([r.get("iops") for r in rows])
        mibs_st = describe([r.get("mibs") for r in rows])
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
            "sources": [rel(r["path"]) for r in rows],
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
            elif c["engine"] == "fio-io_uring" and c["variant"] in MATCHED_FIO_VARIANTS:
                kind, matched = "fio-matched", True
            elif (c["engine"] or "").startswith("fio"):
                kind, matched = "fio-unmatched-info", False
            else:
                continue
            st = ratio_stats(jc["reps"], c["reps"])
            st_fast = ratio_stats(jc["fast_reps"], c["fast_reps"])
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
                               {"fio-matched": 0, "fio-unmatched-info": 1,
                                "seastar-bracket": 2}.get(r["kind"], 9),
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
        return f"{fnum(cell['iops_mean'], 0)} (n={cell['n']}, no CI)"
    return (f"{fnum(cell['iops_mean'], 0)} ± {fnum(cell['ci95_half'], 0)} "
            f"(n={cell['n']})")


def _ratio_line(st):
    if st is None or st.get("mean") is None:
        return "—"
    if st.get("n_pairs", 0) < 2:
        return f"{st['mean']:.4f} (pairs={st['n_pairs']}, no CI)"
    return (f"{st['mean']:.4f} [90% {st['ci90_low']:.4f}..{st['ci90_high']:.4f}; "
            f"95% {st['ci95_low']:.4f}..{st['ci95_high']:.4f}]")


def evaluate_throughput_gate(cells, warnings):
    rows = []
    verdicts = []
    sat_cases = list(SATURATION_CASES_READ) + list(SATURATION_CASES_WRITE)
    present = [c for c in sat_cases if any(x["case"] == c for x in cells)]
    write_cells_present = any(c["workload"] in ("randwrite", "seqwrite")
                              for c in cells)
    if not present:
        rows.append({
            "rule": "1-throughput", "cells": "W3/W4 (W7/W8)",
            "juring": "—", "comparator": "—", "ratio": "—",
            "threshold": "1s95 LB >= 0.95", "verdict": "INCONCLUSIVE",
            "half_width": "—",
            "note": "no saturation cells in this directory",
        })
        return rows, ["INCONCLUSIVE"], present
    for case in sat_cases:
        jc = gate_juring_cell(cells, case)
        fc = gate_fio_matched_cell(cells, case)
        fio_variants = sorted({c["variant"] for c in cells
                               if c["case"] == case and
                               c["engine"] == "fio-io_uring"})
        is_write = case in SATURATION_CASES_WRITE
        if jc is None and fc is None:
            if is_write and not write_cells_present:
                verdict = "N/A"
                note = ("no write cells in session (read-only dataset); write "
                        "half of M1 not assessed")
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
        st_all = ratio_stats(jc["reps"], fc["reps"])
        st_fast = ratio_stats(jc["fast_reps"], fc["fast_reps"])
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
                      f"{fratio(st_all.get('mean'))} is on an unstable cell")
        else:
            verdict, detail = lower_bound_verdict(st, THROUGHPUT_LB)
        half = "—"
        if st.get("ci95_half") is not None:
            half = f"±{st['ci95_half']:.4f} ({st['ci95_half_pct']:.1f}% of mean)"
        if is_write:
            detail = (detail + " " if detail else "") + "write cell"
        if used_subset:
            rule_note = (
                f"all-reps ratio {fratio(st_all.get('mean'))} "
                f"[90% {_ci_str(st_all, 'ci90')}]; "
                f"fast-state subset used ({'+'.join(unstable_sides)} unstable: "
                f"CV>10%/mixed state/spread; all-reps CV "
                f"{fnum(jc['cv_pct'], 1)}%/{fnum(fc['cv_pct'], 1)}%, "
                f"slow JUring [{', '.join(jc['slow_ids']) or '-'}], "
                f"slow fio [{', '.join(fc['slow_ids']) or '-'}], "
                f"classification differs: "
                f"{'YES' if set(jc['slow_ids']) != set(fc['slow_ids']) else 'no'}); ")
        else:
            rule_note = (
                f"all-reps primary (no instability: CV "
                f"{fnum(jc['cv_pct'], 1)}%/{fnum(fc['cv_pct'], 1)}%, "
                f"slow reps JUring [{', '.join(jc['slow_ids']) or '-'}] / "
                f"fio [{', '.join(fc['slow_ids']) or '-'}]); ")
        ratio_text = _ratio_line(st_all)
        if used_subset:
            ratio_text += " | fast subset: " + _ratio_line(st_fast)
        rows.append({
            "rule": "1-throughput", "cells": case,
            "juring": _cell_pair_desc(jc),
            "comparator": _cell_pair_desc(fc),
            "ratio": ratio_text,
            "threshold": "1s95 LB >= 0.95", "verdict": verdict,
            "half_width": half, "note": rule_note + detail,
        })
        verdicts.append(verdict)
    return rows, verdicts, present


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


def _perf_config_desc(cells, recs):
    """Engine/variant + disclosed flags for a perf capture group."""
    if not recs:
        return "—"
    rec = recs[0]
    cell = _cell_for_perf(cells, rec)
    name = f"{rec.get('engine')}/{rec.get('variant')}"
    if cell is not None:
        name += f" ({cell_flags(cell)})"
    return name


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
            st = ratio_stats(
                [make_rep(rep_index=r, iops=jmap[r]) for r in common],
                [make_rep(rep_index=r, iops=fmap[r]) for r in common])
            j_d = describe([jmap[r] for r in common])
            f_d = describe([fmap[r] for r in common])
        else:
            j_d = describe([r["cycles_per_op"] for r in jrows])
            f_d = describe([r["cycles_per_op"] for r in frows])
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
        verdict, detail = noninferior_verdict(st, CPU_NONINFERIOR_LIMIT)
        strict_v, strict_d = band_verdict(st, CPU_BAND)
        half = "—"
        if st is not None and st.get("ci95_half") is not None:
            half = f"±{st['ci95_half']:.4f} ({st['ci95_half_pct']:.1f}% of mean)"
        seastar_mean = _mean_cycles(srows)
        fd_mean = _mean_cycles(fdraws)
        bracket_bits = []
        if seastar_mean and j_d["mean"]:
            bracket_bits.append(f"seastar bracket {fnum(seastar_mean, 0)} cyc/op "
                                f"(JUring/seastar {fratio(j_d['mean'] / seastar_mean)})")
        if fd_mean and j_d["mean"]:
            bracket_bits.append(f"fio default bracket {fnum(fd_mean, 0)} cyc/op "
                                f"(JUring/fio-default {fratio(j_d['mean'] / fd_mean)})")
        w1 = [r for r in perf_tsv_rows if r.get("case") == "W1" and
              r.get("engine") == "juring" and r.get("qd") == 1 and
              r.get("cycles_per_op") is not None]
        w1s = [r for r in perf_tsv_rows if r.get("case") == "W1" and
               r.get("engine") == "seastar" and r.get("qd") == 1 and
               r.get("cycles_per_op") is not None]
        qd1_bits = ""
        if w1 and w1s:
            qd1_bits = (f" QD1 context: JUring {fnum(_mean_cycles(w1), 0)} vs "
                        f"seastar {fnum(_mean_cycles(w1s), 0)} cyc/op (seastar "
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
            "juring": f"{fci(j_d['mean'], j_d['ci95_half'], 1)} cyc/op (n={j_d['n']})",
            "comparator": f"{fci(f_d['mean'], f_d['ci95_half'], 1)} cyc/op (n={f_d['n']})",
            "ratio": fratio(st.get("mean") if st else None) +
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
            return ratio_stats(
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
        verdict, detail = upper_bound_verdict(st99, TAIL_FACTOR)
        p99_parity, d99 = upper_bound_verdict(st99, P99_PARITY)
        p999_parity, d999 = upper_bound_verdict(st999, P999_PARITY)
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
                    f"all-reps p99 ratio {fratio(st99_all.get('mean'))}, "
                    f"slow JUring [{', '.join(jc['slow_ids']) or '-'}] / fio "
                    f"[{', '.join(fc['slow_ids']) or '-'}]; " + note)
        else:
            note = ("all-reps primary; " + note)
        ratio_text = f"p99 all {_ratio_line(st99_all)}; p999 all mean {fratio(st999_all['mean'])}"
        if used_subset:
            ratio_text += f" | fast p99 {_ratio_line(st99_fast)}"
        rows.append({
            "rule": "3-tail", "cells": case,
            "juring": f"p99 {fnum(jc['p99'], 1)} us / p999 {fnum(jc['p999'], 1)} us (n={jc['n']}, {jc['n_fast']} fast)",
            "comparator": f"p99 {fnum(fc['p99'], 1)} us / p999 {fnum(fc['p999'], 1)} us (n={fc['n']}, {fc['n_fast']} fast)",
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
            st = ratio_stats(jc["reps"], sc["reps"])
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

def cross_check_meta(session_dir, reps, perf_tsv_rows=None):
    """Compare parsed JSON with cell-meta.tsv, tolerating documented collisions.

    cell-meta.tsv has no phase column, and the perf phase reused the W1/W3 ids
    with variant `std` for juring/seastar, so those meta rows key-collide with
    the read-matrix rows (12 pairs). Keys with several meta rows are treated as
    ambiguous and excluded from the value comparison instead of producing a
    bogus mismatch. The six fio perf `perf-matched` JSON files are missing
    because of the documented fio `perf`/`perf-matched` filename-stem collision;
    they are reported as documented collisions, not as errors.
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
    for key, rows in by_key.items():
        case, engine, variant, bs_txt, qd_txt, rep_txt = key
        bs = parse_size(bs_txt)
        qd = as_int(qd_txt)
        rep_no = as_int(rep_txt)
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
        iops_meta = as_float(row.get("iops"))
        wall = as_float(row.get("wall_s"))
        if iops_meta and wall and iops_meta > 0 and wall > 0:
            expected_sectors = iops_meta * wall * (bs or 0) / 512
            read_sectors = as_int((row.get("read_sectors_delta") or "").strip())
            write_sectors = as_int((row.get("write_sectors_delta") or "").strip())
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
        p99_meta = as_float(row.get("p99_us"))
        if p99_meta and rec.get("p99"):
            # Known write-session defect: the write cell-meta/RESULT p99_us for
            # fio rows is fio clat in NANOSECONDS (e.g. 2211840 vs 2211.84 us).
            # Normalise for the CROSS-CHECK only; report values still come from
            # the raw JSON / cells-normalized.csv (never from this meta column).
            unit_normalized = None
            if (engine.startswith("fio") and p99_meta > 100.0 * rec["p99"]
                    and p99_meta > 100000.0):
                unit_normalized = p99_meta / 1000.0
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
        "byte_rows": byte_rows,
        "n_byte_warn": sum(1 for b in byte_rows if b["verdict"] != "PASS"),
    }


def cross_check_perf_tsv(session_dir, perf_records, perf_tsv_rows=None):
    """Cross-check the analyzer's raw-capture numbers against perf-cells.tsv.

    The harness table is authoritative for per-op CPU in this session (fio
    `perf`/`perf-matched` shared a filename stem, so the default run's raw
    capture was overwritten; both variants' computed rows survive). Here the
    raw captures that still exist are compared with the table to prove the
    mapping/parsing is right.

    Variant mapping (documented in notes.md): raw captures exist for
        juring/seastar `perf` stem -> table variant `std`
        fio `perf` stem            -> table variant `perf-matched` (the
                                      surviving matched-run capture)
    Table rows for fio `perf` (default) are computational-only and are skipped
    from the comparison.
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
        warnings.append(f"{rel(path)}: unreadable iotester-check.tsv ({exc})")
        return None
    last = {}
    for row in all_rows:
        if (row.get("status") or "").strip() not in ("", "ok"):
            continue
        key = ((row.get("case") or "").strip(),
               (row.get("engine") or "").strip(),
               (row.get("variant") or "").strip(),
               as_int((row.get("qd") or "").strip()),
               as_int((row.get("rep") or "").strip()))
        last[key] = {
            "case": key[0], "engine": key[1], "variant": key[2],
            "qd": key[3], "rep": key[4],
            "iops": as_float(row.get("iops")),
            "throughput_kbs": as_float(row.get("throughput_kbs")),
            "p50": as_float(row.get("p50_us")),
            "p99": as_float(row.get("p99_us")),
            "p999": as_float(row.get("p999_us")),
            "max": as_float(row.get("max_us")),
            "wall_s": as_float(row.get("wall_s")),
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
            fnum(c["bs"], 0), c["qd"], c["n"],
            fci(c["iops_mean"], c["ci95_half"], 0),
            fnum(c["iops_sd"], 0) if c["iops_sd"] is not None else "—",
            fnum(c["min"], 0), fnum(c["median"], 0), fnum(c["max"], 0),
            fnum(c["cv_pct"], 1) + (" !" if c["cv_unstable"] else "") if c["cv_pct"] is not None else "—",
            state, cell_flags(c),
        ])
    return md_table(headers, rows)


def render_latency_md(cells):
    headers = ["case", "engine", "variant", "qd", "n", "p50 us", "p95 us",
               "p99 us", "p999 us", "max us", "source (first rep)"]
    rows = []
    for c in cells:
        rows.append([
            c["case"], c["engine"], c["variant"], c["qd"], c["n"],
            fnum(c["p50"], 1), fnum(c["p95"], 1), fnum(c["p99"], 1),
            fnum(c["p999"], 1), fnum(c["lat_max"], 1), c["sources"][0],
        ])
    return md_table(headers, rows)


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
        fast_mean = (f"{fnum(c['fast_iops_mean'], 0)}"
                     if c["fast_iops_mean"] is not None else "—")
        if c["fast_ci95_half"] is not None:
            fast_mean += f" ± {fnum(c['fast_ci95_half'], 0)}"
        flags = []
        if c["cv_unstable"]:
            flags.append("CV>10%")
        if c["mixed_state"]:
            flags.append("mixed state")
        if c["bimodal_iops"] and not c["mixed_state"]:
            flags.append("IOPS spread>10%")
        rows.append([
            c["case"], c["engine"], c["variant"], c["qd"], c["n"], threshold,
            c["n_fast"], c["n_slow"], fast_mean, fnum(c["mean_slow"], 0),
            ", ".join(c["slow_ids"]) or "—",
            (f"{100.0 * c['iops_spread']:.1f}%" if c["iops_spread"] is not None else "—"),
            fnum(c["cv_pct"], 1) if c["cv_pct"] is not None else "—",
            ", ".join(flags) or "stable",
            c["state_note"],
        ])
    if not rows:
        return "_(no cells with per-rep state information in this session)_"
    return md_table(headers, rows)


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
            fratio(st["mean"]),
            f"{st['sd']:.4f}" if st.get("sd") is not None else "—",
            ci90, ci95,
            f"{st['lb95_1s']:.4f}" if st.get("lb95_1s") is not None else "—",
            f"{st['ub95_1s']:.4f}" if st.get("ub95_1s") is not None else "—",
            half, fast, r["note"],
        ])
    return md_table(headers, rows)


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
            stf["n_pairs"], fratio(stf["mean"]),
            f"{stf['sd']:.4f}" if stf.get("sd") is not None else "—",
            cia, cib,
            ", ".join(r["left_slow"]) or "—",
            ", ".join(r["right_slow"]) or "—",
            "YES" if r["classification_differs"] else "no",
            r["note"],
        ])
    if not rows:
        return "_(no fast-state subset differs from the all-reps set: every cell is stable, or subsets are identical)_"
    return md_table(headers, rows)


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
        cyc = (f"{fnum(cyc_u, 0)} + {fnum(cyc_k, 0)}"
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
            fnum(rec.get("ops"), 0), cyc,
            fnum(rec.get("cycles_per_op"), 1),
            fnum(rec.get("instr_per_op"), 1),
            fnum(rec.get("syscalls_per_op"), 3),
            fnum(rec.get("ctxt_per_op"), 4),
            provenance,
        ])
    if not rows:
        return "_(no perf stat captures / perf-cells.tsv rows)_"
    return md_table(headers, rows)


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
            fnum(it.get("iops"), 0), fnum(it.get("mibs"), 1),
            fnum(it.get("p99"), 1),
            _cell_pair_desc(sfb),
            fratio(ratio) if ratio is not None else "—",
            rel(it["path"]),
        ])
    if not rows:
        return "_(no io_tester YAML parsed; io_tester cross-check pending writable storage)_"
    return md_table(headers, rows)


def render_gates_md(gate_rows):
    headers = ["rule", "cell(s)", "JUring", "fio-matched comparator", "ratio",
               "threshold", "verdict", "achieved CI half-width", "note"]
    rows = []
    for r in gate_rows:
        rows.append([r["rule"], r["cells"], r["juring"], r["comparator"],
                     r["ratio"], r["threshold"], r["verdict"],
                     r["half_width"], r["note"]])
    return md_table(headers, rows)


def render_seastar_md(bracket):
    headers = ["case", "qd", "JUring IOPS", "seastar IOPS",
               "JUring/seastar", "90% CI", "95% CI", "paired SD"]
    rows = []
    for b in bracket:
        st = b["stats"]
        rows.append([
            b["case"], b["qd"], _cell_pair_desc(b["left"]),
            _cell_pair_desc(b["right"]), fratio(st["mean"]),
            (f"{st['ci90_low']:.4f}..{st['ci90_high']:.4f}"
             if st.get("ci90_low") is not None else "—"),
            (f"{st['ci95_low']:.4f}..{st['ci95_high']:.4f}"
             if st.get("ci95_low") is not None else "—"),
            f"{st['sd']:.4f}" if st.get("sd") is not None else "—",
        ])
    if not rows:
        return "_(no seastar cells)_"
    return md_table(headers, rows)


# ---------------------------------------------------------------------------
# serialisation
# ---------------------------------------------------------------------------

def rep_brief(rep):
    return {
        "rep": rep["rep"], "iops": rep.get("iops"), "mibs": rep.get("mibs"),
        "p50": rep.get("p50"), "p99": rep.get("p99"),
        "p999": rep.get("p999"), "max": rep.get("max"),
        "ops": rep.get("ops"), "errors": rep.get("errors"),
        "source": rel(rep["path"]),
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
        "source": rec.get("source") or rel(rec.get("path") or ""),
        "raw_source": rec.get("raw_source"),
        "provenance": rec.get("provenance"),
    }


def write_outputs(prefix, summary_md, tables, payload):
    prefix = Path(prefix)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    out = {}
    files = {
        "summary_md": prefix.with_name(prefix.name + ".summary.md"),
        "cells_csv": prefix.with_name(prefix.name + ".cells.csv"),
        "reps_csv": prefix.with_name(prefix.name + ".reps.csv"),
        "ratios_csv": prefix.with_name(prefix.name + ".ratios.csv"),
        "perf_csv": prefix.with_name(prefix.name + ".perf.csv"),
        "gates_csv": prefix.with_name(prefix.name + ".gates.csv"),
        "iotester_csv": prefix.with_name(prefix.name + ".iotester.csv"),
        "aggregate_json": prefix.with_name(prefix.name + ".aggregate.json"),
    }
    files["summary_md"].write_text(summary_md + "\n", encoding="utf-8")
    for key, rows, header in tables:
        target = files[key]
        with open(target, "w", newline="", encoding="utf-8") as fh:
            writer = csv.writer(fh)
            writer.writerow(header)
            writer.writerows(rows)
    files["aggregate_json"].write_text(
        json.dumps(payload, indent=1, sort_keys=False) + "\n", encoding="utf-8")
    for key, path in files.items():
        out[key] = str(path)
    return out


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def build_summary(session_dir, cells, ratios, perf_report, iotester, bracket,
                  gate_rows, rule_verdicts, parsed, warnings, meta_check,
                  overall, argv_text, perf_check=None, iot_check=None,
                  manifest=None):
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    lines = []
    lines.append(f"# M1 aggregate — `{rel(session_dir)}`")
    lines.append("")
    lines.append(f"Generated {now} by `bench/m1/analyze_m1.py` "
                 f"(v{VERSION}). Stdlib only; every number below is derived "
                 f"from the raw files cited in the tables.")
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
        lines.append(f"- session manifest: `{rel(manifest['path'])}` "
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
        lines.append(md_table(headers, rows))
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

    argv_text = (f"python3 bench/m1/analyze_m1.py --dir {rel(session_dir)}"
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
            + ", ".join(sorted({rel(r["path"]) for r in dropped_reps})[:6])
            + (" ..." if len(dropped_reps) > 6 else ""))
    reps = good_reps
    perf_windows = load_perf_windows(session_dir, warnings)
    perf_tsv_rows = load_perf_cells_tsv(session_dir, warnings)
    manifest = load_session_manifest(session_dir, warnings)
    if not reps and not perf_records and not iotester:
        print(f"error: no parseable artifacts found under {session_dir}",
              file=sys.stderr)
        return 2

    cells = build_cells(reps)
    if cells:
        attach_perf(perf_records, reps, cells, warnings, perf_windows)
    perf_report = build_perf_report(session_dir, perf_tsv_rows, perf_records, warnings)
    if perf_tsv_rows and not any(r.get("cycles_per_op") for r in perf_report):
        warnings.append("perf-cells.tsv rows parsed but no cycles/op values found")
    ratios = compute_ratios(cells)
    bracket, bracket_cases = evaluate_seastar_bracket(cells)

    gate1, v1, sat_present = evaluate_throughput_gate(cells, warnings)
    gate2, v2 = evaluate_cpu_gate(cells, perf_records, warnings, perf_tsv_rows)
    gate3, v3 = evaluate_tail_gate(cells)
    gate5 = evaluate_ci_rule(cells, [])

    gate_rows = gate1 + gate2 + gate3 + gate5
    rule_verdicts = {
        "1 throughput (1s95 LB >= 0.95 at W3/W4, W7/W8)": combine_verdicts(v1),
        "2 CPU (cycles/op within 15% of fio-matched)": combine_verdicts(v2),
        "3 tails (W15/W16 p99 <= 2x fio p99)": combine_verdicts(v3),
        "4 seastar bracket (no threshold)":
            (f"reported for {len(bracket_cases)} cell(s)"
             if bracket_cases else "no seastar cells"),
        "5 every cited number has a CI": gate5[0]["verdict"],
    }
    # rule 5 over the cells cited by rules 1-3
    cited = []
    for rat in ratios:
        if rat["kind"] == "fio-matched":
            cited.append(rat["left"])
            cited.append(rat["right"])
    for case in list(SATURATION_CASES_READ) + list(SATURATION_CASES_WRITE):
        jc = gate_juring_cell(cells, case)
        fc = gate_fio_matched_cell(cells, case)
        cited.extend([x for x in (jc, fc) if x is not None])
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
    read_only = not any(c["workload"] in ("randwrite", "seqwrite") for c in cells)
    if "FAIL" in core:
        overall = {"verdict": "FAIL",
                   "reason": "at least one M1 gate rule failed (see table)"}
    elif all(v == "PASS" for v in core):
        overall = {
            "verdict": "PASS" + (" (read path only; write half W7/W8 not assessed)"
                                 if read_only else ""),
            "reason": ("rules 1-3 and 5 pass at the saturation/latency cells; "
                       "seastar reported as a bracket"),
        }
    else:
        pending = [name for name, v in zip(
            ("rule 1 throughput", "rule 2 CPU", "rule 3 tails",
             "rule 5 CI"), core) if v != "PASS"]
        overall = {"verdict": "INCONCLUSIVE",
                   "reason": ("pending/insufficient data: " + ", ".join(pending)
                              + ". Achieved CI half-widths are in the gate table; "
                                "more reps or a registration-matched fio cell and "
                                "perf captures are needed to decide.")}
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

    meta_check = cross_check_meta(session_dir, reps, perf_tsv_rows)
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
            f"value(s) are in nanoseconds (write-session units defect) and were "
            f"normalised ns->us for the cross-check only; cited latency values "
            f"come from the raw JSON / cells-normalized.csv")
    if meta_check and meta_check.get("collision_missing"):
        warnings.append(
            f"cell-meta.tsv: {len(meta_check['collision_missing'])} fio "
            f"perf-matched JSON file(s) absent because of the documented "
            f"perf/perf-matched filename-stem collision; their computed numbers "
            f"are in perf-cells.tsv (see notes.md)")

    summary_md = build_summary(
        session_dir, cells, ratios, perf_report, iotester, bracket, gate_rows,
        rule_verdicts, parsed, warnings, meta_check, overall, argv_text, perf_check,
        iot_check_report, manifest)

    # ---- write policy: never write into the legacy 2026-11-13 dir ----------
    prefix = None
    if args.out_prefix:
        prefix = Path(args.out_prefix).expanduser()
    else:
        resolved = session_dir.resolve()
        if resolved == DEFAULT_DIR.resolve() or "m1-2026-11-13" in str(resolved):
            prefix = session_dir / "aggregate" / "m1"
    written = {}
    payload = {
        "schema": "m1-aggregate-1.0",
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "analyzer": f"bench/m1/analyze_m1.py v{VERSION}",
        "dir": str(session_dir),
        "argv": argv_text,
        "parsed": parsed,
        "manifest": ({"path": rel(manifest["path"]),
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
        "perf_source": rel(session_dir / "perf-cells.tsv") if perf_tsv_rows else "perf/*.txt",
        "iotester": [{
            "case": it["key"]["case"], "variant": it["key"]["variant"],
            "bs": it["key"]["bs"], "qd": it["key"]["qd"],
            "n_shards": it["n_shards"], "iops": it["iops"],
            "mibs": it["mibs"], "p50": it.get("p50"), "p95": it.get("p95"),
            "p99": it["p99"], "p999": it["p999"], "max": it.get("max"),
            "ops": it["ops"], "source": rel(it["path"]),
        } for it in iotester],
        "gate_rows": gate_rows,
        "rule_verdicts": rule_verdicts,
        "overall": overall,
        "iotester_check": ({
            "path": rel(iot_check_report["path"]),
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
            "path": rel(perf_check["path"]),
            "n_rows": perf_check["n_rows"],
            "n_checked": perf_check["n_checked"],
            "max_rel_diff": perf_check["max_rel_diff"],
            "missing": perf_check["missing"],
            "mismatches": perf_check["mismatches"],
            "n_tsv_only": perf_check.get("n_tsv_only", 0),
        } if perf_check else None),
        "cell_meta_check": ({
            "path": rel(meta_check["path"]),
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
             r["max"], r["ops"], r["errors"], rel(r["path"])]
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
             r.get("syscalls_per_op"), r.get("ctxt_per_op"), rel(r["path"])]
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
             rel(it["path"])]
            for it in iotester],
         ["case", "variant", "qd", "shards", "iops", "mibs", "p99_us",
          "p999_us", "source"]),
    ]

    if prefix is not None:
        written = write_outputs(prefix, summary_md, csv_tables, payload)
        print(summary_md)
        print()
        print("artifacts written:")
        for key in ("summary_md", "cells_csv", "reps_csv", "ratios_csv",
                    "perf_csv", "gates_csv", "iotester_csv", "aggregate_json"):
            print(f"  {key:16s} {rel(written[key])}")
    else:
        print(summary_md)
        print()
        print("note: aggregate artifacts NOT written (this directory is not the "
              "M1 session dir and --out-prefix was not given; legacy results are "
              "treated as read-only). Pass --out-prefix <path> to emit them.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
