"""Statistics shared by the bench analyzers.

Two independent implementations of the same maths live here on purpose (see the
package docstring): the summed-moment helpers with frozen t tables, and the
exact ``statistics``-module + inverted-CDF helpers used by the read/write
analyzer.  Both are covered by ``bench/analysis/selftest.py``.
"""

import math
import statistics

# Two-sided 95% Student-t critical values, df = 1..30 (3 dp).
T975_3DP = [
    12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
    2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
    2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042,
]

# One-sided 95% Student-t critical values, df = 1..30 (3 dp).
T95_ONE_SIDED_3DP = [
    6.314, 2.920, 2.353, 2.132, 2.015, 1.943, 1.895, 1.860, 1.833, 1.812,
    1.796, 1.782, 1.771, 1.761, 1.753, 1.746, 1.740, 1.734, 1.729, 1.725,
    1.721, 1.717, 1.714, 1.711, 1.708, 1.706, 1.703, 1.701, 1.699, 1.697,
]

# One-sided 95% t quantiles to 4 dp, df = 1..30; df >= 30 -> 1.645
# (the frozen table in bench/shard/analyze_scale_out.py).
T95_ONE_SIDED_4DP = {
    1: 6.3138, 2: 2.9200, 3: 2.3534, 4: 2.1318, 5: 2.0150, 6: 1.9432,
    7: 1.8946, 8: 1.8595, 9: 1.8331, 10: 1.8125, 11: 1.7959, 12: 1.7823,
    13: 1.7709, 14: 1.7613, 15: 1.7531, 16: 1.7459, 17: 1.7396, 18: 1.7341,
    19: 1.7291, 20: 1.7247, 21: 1.7207, 22: 1.7171, 23: 1.7139, 24: 1.7109,
    25: 1.7081, 26: 1.7056, 27: 1.7033, 28: 1.7011, 29: 1.6991, 30: 1.6973,
}


# ---------------------------------------------------------------------------
# summed-moment helpers (frozen t tables; analyze_stream / shard / host-setup)
# ---------------------------------------------------------------------------

def mean(values):
    return sum(values) / len(values) if values else None


def sd(values):
    """Sample standard deviation (n-1); None for n < 2."""
    n = len(values)
    if n < 2:
        return None
    m = mean(values)
    return math.sqrt(sum((x - m) ** 2 for x in values) / (n - 1))


def t975(df):
    """Two-sided 95% t critical value; None for df < 1."""
    if df < 1:
        return None
    return T975_3DP[df - 1] if df <= len(T975_3DP) else 1.96


def t95_one_sided(df):
    """One-sided 95% t critical value; None for df < 1."""
    if df < 1:
        return None
    return T95_ONE_SIDED_3DP[df - 1] if df <= len(T95_ONE_SIDED_3DP) else 1.645


def ci95(values):
    """Two-sided 95% CI half-width of the mean (Student t); None for n < 2."""
    n = len(values)
    if n < 2:
        return None
    return t975(n - 1) * sd(values) / math.sqrt(n)


def one_sided_lower(values):
    """One-sided 95% lower confidence bound of the mean (Student t)."""
    n = len(values)
    if n < 2:
        return None
    return mean(values) - t95_one_sided(n - 1) * sd(values) / math.sqrt(n)


def cv_pct(values):
    """Coefficient of variation in percent; None when mean is 0 or n < 2."""
    m = mean(values)
    if not m or len(values) < 2:
        return None
    return 100.0 * sd(values) / abs(m)


def mean_ci(values):
    """``(mean, low, high)`` with a two-sided 95% t interval.

    ``mean`` is None for an empty sample; ``low``/``high`` are None for n < 2
    (no interval).  This is the tuple shape the host-setup analyzers print.
    """
    n = len(values)
    if n == 0:
        return None, None, None
    m = mean(values)
    if n < 2:
        return m, None, None
    half = t975(n - 1) * sd(values) / math.sqrt(n)
    return m, m - half, m + half


def one_sided_ci(values):
    """``(mean, sd, low, high)`` one-sided 95% t interval (analyze_scale_out shape).

    A single value has no spread: it returns ``(m, 0.0, m, m)`` so the scaling bars
    can still label the cell rather than dropping it.
    """
    if not values:
        return None, None, None, None
    m = mean(values)
    if len(values) < 2:
        return m, 0.0, m, m
    half = t95_one_sided(len(values) - 1) * sd(values) / math.sqrt(len(values))
    return m, sd(values), m - half, m + half


# ---------------------------------------------------------------------------
# exact Student-t (stdlib only, Numerical-Recipes style incomplete beta)
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
# descriptive statistics + paired ratios (analyze_read_write)
# ---------------------------------------------------------------------------

def describe(values):
    """n / mean / sample SD / 95% CI of mean / min / median / max / CV%."""
    vals = [float(v) for v in values if v is not None]
    n = len(vals)
    if n == 0:
        return {"n": 0, "mean": None, "sd": None, "ci95_low": None,
                "ci95_high": None, "ci95_half": None, "min": None,
                "median": None, "max": None, "cv_pct": None}
    mean_v = statistics.fmean(vals)
    if n > 1:
        sd_v = statistics.stdev(vals)
        half = t_ppf(0.975, n - 1) * sd_v / math.sqrt(n)
        cv = 100.0 * sd_v / mean_v if mean_v else None
    else:
        sd_v = None
        half = None
        cv = None
    return {
        "n": n, "mean": mean_v, "sd": sd_v,
        "ci95_low": (mean_v - half) if half is not None else None,
        "ci95_high": (mean_v + half) if half is not None else None,
        "ci95_half": half,
        "min": min(vals), "median": statistics.median(vals), "max": max(vals),
        "cv_pct": cv,
    }


def ratio_stats(left_reps, right_reps):
    """Paired-by-rep-index ratio left/right with 90%/95% CIs of the mean.

    ``left_reps``/``right_reps`` are dicts with ``rep_index`` and ``iops``, as
    built by the read/write analyzer.  Reps are paired by index only when
    both sides have a positive IOPS: nothing is invented for a missing rep.
    """
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
    mean_v = statistics.fmean(ratios)
    out["mean"] = mean_v
    if n > 1:
        sd_v = statistics.stdev(ratios)
        se = sd_v / math.sqrt(n)
        t90 = t_ppf(0.95, n - 1)
        t95 = t_ppf(0.975, n - 1)
        out.update({
            "sd": sd_v,
            "ci90_low": mean_v - t90 * se, "ci90_high": mean_v + t90 * se,
            "ci95_low": mean_v - t95 * se, "ci95_high": mean_v + t95 * se,
            # two-sided 90% bounds == one-sided 95% bounds
            "lb95_1s": mean_v - t90 * se, "ub95_1s": mean_v + t95 * se,
            "ci95_half": t95 * se,
            "ci95_half_pct": 100.0 * t95 * se / mean_v if mean_v else None,
        })
    return out


# ---------------------------------------------------------------------------
# gate verdicts (the read/write convention: PASS / FAIL / INCONCLUSIVE)
# ---------------------------------------------------------------------------

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

    The read/write CPU gate compares JUring's cycles/op with fio's; a JUring CPU cost
    *below* fio's is an advantage, not a failure, so the gate is one-sided
    ("JUring not more than 15 % worse"). The literal two-sided +-15 % band is
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
    same convention as the +-5% parity rule in docs/benchmark-methodology.md)."""
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


def classify_ge(m, lb, thr):
    """Bar 'mean/ratio >= thr', decided on the one-sided 95 % lower bound."""
    if m is None:
        return "INCONCLUSIVE"
    if lb is not None and lb >= thr:
        return "PASS"
    if m < thr:
        return "FAIL"
    return "INCONCLUSIVE"


def classify_le(m, ub, thr):
    """Bar 'mean/ratio <= thr', decided on the one-sided 95 % upper bound."""
    if m is None:
        return "INCONCLUSIVE"
    if ub is not None and ub <= thr:
        return "PASS"
    if m > thr:
        return "FAIL"
    return "INCONCLUSIVE"
