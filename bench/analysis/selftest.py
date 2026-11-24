#!/usr/bin/env python3
"""Regression harness for the bench analyzers.

The analyzers under ``bench/`` were consolidated onto the shared
``bench/analysis/`` package.  This file pins the shared helpers -- Student-t
tables and intervals, descriptive statistics and paired ratios, gate verdicts,
markdown formatting, JSON/manifest loading, CLI session resolution and the
canonical cell/pass identifiers -- against hand-computed values and
cross-checks.

Run ``python3 bench/analysis/selftest.py --unit`` before touching anything under
``bench/``.  ``--unit`` is the only mode and the default, so a bare invocation
does the same thing.  Stdlib only, like the analyzers.
"""
from __future__ import annotations

import argparse
import statistics
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BENCH = REPO / "bench"

sys.path.insert(0, str(BENCH))
from analysis import cells, cli, jsonio, md, stats  # noqa: E402

# ---------------------------------------------------------------------------
# unit tests
# ---------------------------------------------------------------------------

_GROUPS: dict[str, list] = {}


def test(group):
    def register(fn):
        _GROUPS.setdefault(group, []).append(fn)
        return fn
    return register


def eq(actual, expected, what="value"):
    if actual != expected:
        raise AssertionError(f"{what}: {actual!r} != {expected!r}")


def close(actual, expected, tol=1e-12, what="value"):
    if actual is None or abs(actual - expected) > tol:
        raise AssertionError(f"{what}: {actual!r} != {expected!r} (tol {tol:g})")


def scratch_dir():
    base = REPO / "scratch"
    base.mkdir(exist_ok=True)
    return tempfile.TemporaryDirectory(prefix="selftest-", dir=base)


# --- stats: summed-moment helpers ------------------------------------------

@test("stats")
def summed_moments():
    eq(stats.mean([1, 2, 3, 4]), 2.5)
    eq(stats.mean([]), None)
    eq(stats.sd([1, 2, 3]), 1.0)
    eq(stats.sd([3]), None)
    vals = [10.0, 12.0, 9.0, 11.0]
    close(stats.mean(vals), statistics.fmean(vals))
    close(stats.sd(vals), statistics.stdev(vals))


@test("stats")
def ci95_and_one_sided_lower():
    vals = [10.0, 12.0, 9.0, 11.0]  # n=4, df=3
    m, s = statistics.fmean(vals), statistics.stdev(vals)
    close(stats.ci95(vals), 3.182 * s / 2.0, what="ci95")
    close(stats.one_sided_lower(vals), m - 2.353 * s / 2.0, what="one_sided_lower")
    eq(stats.ci95([1.0]), None)
    eq(stats.one_sided_lower([1.0]), None)


@test("stats")
def cv_pct_and_mean_ci():
    vals = [2.0, 4.0]
    close(stats.cv_pct(vals), 100.0 * statistics.stdev(vals) / 3.0, what="cv_pct")
    eq(stats.cv_pct([0.0, 0.0]), None)
    eq(stats.cv_pct([5.0]), None)
    eq(stats.mean_ci([]), (None, None, None))
    eq(stats.mean_ci([5.0]), (5.0, None, None))
    m, lo, hi = stats.mean_ci([1.0, 2.0, 3.0])
    half = 4.303 * 1.0 / (3.0 ** 0.5)
    close(m, 2.0, what="mean_ci mean")
    close(lo, 2.0 - half, what="mean_ci low")
    close(hi, 2.0 + half, what="mean_ci high")


# --- stats: t tables and exact inverse CDF ----------------------------------

@test("ttable")
def two_sided_table():
    for df in range(1, 31):
        t = stats.t975(df)
        close(stats.t_cdf(t, df), 0.975, 2e-3, what=f"t_cdf(t975({df}))")
        close(stats.t_ppf(0.975, df), t, 5e-3, what=f"t_ppf(0.975, {df})")


@test("ttable")
def one_sided_table():
    for df in range(1, 31):
        t = stats.t95_one_sided(df)
        close(stats.t_cdf(t, df), 0.95, 2e-3, what=f"t_cdf(t95_one_sided({df}))")
        close(stats.t_ppf(0.95, df), t, 5e-3, what=f"t_ppf(0.95, {df})")


@test("ttable")
def four_dp_table_agrees_with_three_dp():
    eq(sorted(stats.T95_ONE_SIDED_4DP), list(range(1, 31)))
    for df, value in stats.T95_ONE_SIDED_4DP.items():
        close(value, stats.T95_ONE_SIDED_3DP[df - 1], 1e-3, what=f"df={df}")


@test("ttable")
def out_of_range_defaults():
    eq(stats.t975(0), None)
    eq(stats.t95_one_sided(0), None)
    eq(stats.t975(31), 1.96)
    eq(stats.t95_one_sided(31), 1.645)


# --- stats: describe / ratio_stats -----------------------------------------

@test("describe")
def describe_vector():
    d = stats.describe([1, 2, 3, 4])
    eq(d["n"], 4)
    close(d["mean"], 2.5)
    close(d["sd"], statistics.stdev([1.0, 2.0, 3.0, 4.0]))
    close(d["ci95_half"], stats.t_ppf(0.975, 3) * d["sd"] / 2.0, what="ci95_half")
    close(d["ci95_low"], d["mean"] - d["ci95_half"], what="ci95_low")
    close(d["ci95_high"], d["mean"] + d["ci95_half"], what="ci95_high")
    eq((d["min"], d["median"], d["max"]), (1, 2.5, 4))
    close(d["cv_pct"], 100.0 * d["sd"] / 2.5)


@test("describe")
def describe_small_samples():
    empty = stats.describe([])
    eq(empty["n"], 0)
    for key in ("mean", "sd", "ci95_low", "ci95_high", "ci95_half", "cv_pct"):
        eq(empty[key], None, what=key)
    one = stats.describe([7.5])
    eq(one["n"], 1)
    eq(one["mean"], 7.5)
    for key in ("sd", "ci95_low", "ci95_high", "ci95_half", "cv_pct"):
        eq(one[key], None, what=key)
    eq((one["min"], one["median"], one["max"]), (7.5, 7.5, 7.5))
    two = stats.describe([5, None, 7])  # absent values are dropped, not zeroed
    eq(two["n"], 2)
    close(two["mean"], 6.0)
    eq(two["median"], 6.0)


@test("describe")
def ratio_pairs_by_rep_index():
    left = [{"rep_index": 2, "iops": 200.0}, {"rep_index": 1, "iops": 100.0},
            {"rep_index": 3, "iops": 400.0}]
    right = [{"rep_index": 1, "iops": 50.0}, {"rep_index": 2, "iops": 100.0},
             {"rep_index": 3, "iops": 200.0}]
    r = stats.ratio_stats(left, right)
    eq(r["n_pairs"], 3)
    eq(r["paired_reps"], [1, 2, 3])
    eq(r["ratios"], [2.0, 2.0, 2.0])
    close(r["mean"], 2.0)
    close(r["sd"], 0.0)
    for key in ("ci90_low", "ci90_high", "ci95_low", "ci95_high", "lb95_1s", "ub95_1s"):
        close(r[key], 2.0, what=key)
    close(r["ci95_half"], 0.0)
    close(r["ci95_half_pct"], 0.0)
    close(r["ratio_of_means"], 2.0)
    eq((r["n_left"], r["n_right"]), (3, 3))


@test("describe")
def ratio_never_invents_a_missing_side():
    left = [{"rep_index": 1, "iops": 100.0}, {"rep_index": 2, "iops": 0.0},
            {"rep_index": 3, "iops": 300.0}, {"rep_index": 4, "iops": 400.0}]
    right = [{"rep_index": 1, "iops": 50.0}, {"rep_index": 2, "iops": 100.0},
             {"rep_index": 3, "iops": None}, {"rep_index": 5, "iops": 500.0}]
    r = stats.ratio_stats(left, right)
    eq(r["paired_reps"], [1, 2, 3])  # present on both sides...
    eq(r["ratios"], [2.0])  # ...but only the positive pair is usable
    eq(r["n_pairs"], 1)
    eq((r["n_left"], r["n_right"]), (4, 4))
    none = stats.ratio_stats([{"rep_index": 1, "iops": 10.0}],
                             [{"rep_index": 2, "iops": 20.0}])
    eq(none["n_pairs"], 0)
    eq(none["paired_reps"], [])
    eq(none["ratios"], [])
    eq(none["mean"], None)
    eq(none["ci95_low"], None)


@test("describe")
def ratio_single_pair_has_no_interval():
    r = stats.ratio_stats([{"rep_index": 1, "iops": 150.0}],
                          [{"rep_index": 1, "iops": 100.0}])
    eq(r["n_pairs"], 1)
    close(r["mean"], 1.5)
    eq(r["sd"], None)
    for key in ("ci90_low", "ci90_high", "ci95_low", "ci95_high", "ci95_half"):
        eq(r[key], None, what=key)


# --- stats: gate verdicts ---------------------------------------------------

def _stat(**overrides):
    base = {
        "n_pairs": 5, "mean": 1.0, "sd": 0.05,
        "ci90_low": 0.96, "ci90_high": 1.04,
        "ci95_low": 0.95, "ci95_high": 1.05,
        "lb95_1s": 0.96, "ub95_1s": 1.04,
        "ci95_half": 0.05, "ci95_half_pct": 5.0,
    }
    base.update(overrides)
    return base


@test("verdicts")
def lower_bound_verdict_branches():
    no_ci = ("INCONCLUSIVE", "n<2 reps (no CI)")
    eq(stats.lower_bound_verdict(None, 1.0), no_ci)
    eq(stats.lower_bound_verdict(_stat(n_pairs=1), 1.0), no_ci)
    eq(stats.lower_bound_verdict(_stat(lb95_1s=1.10), 1.05),
       ("PASS", "1s95 LB 1.1000 >= 1.05"))
    eq(stats.lower_bound_verdict(_stat(lb95_1s=0.80, ub95_1s=0.90), 1.0),
       ("FAIL", "95% UB 0.9000 < 1.0"))
    eq(stats.lower_bound_verdict(_stat(lb95_1s=0.90, ub95_1s=1.10), 1.0),
       ("INCONCLUSIVE", "CI [0.9500, 1.0500] spans 1.0; half-width 0.0500 "
                        "(5.0% of mean)"))


@test("verdicts")
def upper_bound_verdict_branches():
    no_ci = ("INCONCLUSIVE", "n<2 reps (no CI)")
    eq(stats.upper_bound_verdict(None, 1.15), no_ci)
    eq(stats.upper_bound_verdict(_stat(ci95_high=1.10), 1.15),
       ("PASS", "95% UB 1.1000 <= 1.15"))
    eq(stats.upper_bound_verdict(_stat(ci95_high=1.30, lb95_1s=1.20), 1.15),
       ("FAIL", "1s95 LB 1.2000 > 1.15"))
    eq(stats.upper_bound_verdict(_stat(ci95_high=1.30, lb95_1s=1.05), 1.15),
       ("INCONCLUSIVE", "CI [0.9500, 1.3000] spans 1.15; half-width 0.0500"))


@test("verdicts")
def noninferior_verdict_branches():
    no_ci = ("INCONCLUSIVE", "n<2 reps (no CI)")
    eq(stats.noninferior_verdict(None, 1.15), no_ci)
    eq(stats.noninferior_verdict(_stat(ci95_high=1.10), 1.15),
       ("PASS", "95% UB 1.1000 <= 1.15 (non-inferior)"))
    eq(stats.noninferior_verdict(_stat(ci95_high=1.30, lb95_1s=1.20), 1.15),
       ("FAIL", "1s95 LB 1.2000 > 1.15"))
    eq(stats.noninferior_verdict(_stat(ci95_high=1.30, lb95_1s=1.05), 1.15),
       ("INCONCLUSIVE", "CI [0.9500, 1.3000] spans 1.15"))


@test("verdicts")
def band_verdict_branches():
    no_ci = ("INCONCLUSIVE", "n<2 reps (no CI)")
    eq(stats.band_verdict(None, (0.95, 1.05)), no_ci)
    eq(stats.band_verdict(_stat(), (0.95, 1.05)),
       ("PASS", "90% CI [0.9600, 1.0400] inside [0.95, 1.05]"))
    eq(stats.band_verdict(_stat(ci90_low=0.90, ci90_high=0.94), (0.95, 1.05)),
       ("FAIL", "90% CI [0.9000, 0.9400] outside [0.95, 1.05]"))
    eq(stats.band_verdict(_stat(ci90_low=0.90, ci90_high=1.10), (0.95, 1.05)),
       ("INCONCLUSIVE", "90% CI [0.9000, 1.1000] wider than [0.95, 1.05]; "
                        "half-width 0.0500"))


@test("verdicts")
def combine_and_classify():
    eq(stats.combine_verdicts([]), "INCONCLUSIVE")
    eq(stats.combine_verdicts([None, "PASS", "PASS"]), "PASS")
    eq(stats.combine_verdicts(["PASS", "FAIL"]), "FAIL")
    eq(stats.combine_verdicts(["PASS", "INCONCLUSIVE"]), "INCONCLUSIVE")
    eq(stats.classify_ge(None, None, 1.0), "INCONCLUSIVE")
    eq(stats.classify_ge(1.2, 1.10, 1.05), "PASS")
    eq(stats.classify_ge(0.9, 0.85, 1.0), "FAIL")
    eq(stats.classify_ge(1.02, 0.99, 1.0), "INCONCLUSIVE")
    eq(stats.classify_le(None, None, 1.0), "INCONCLUSIVE")
    eq(stats.classify_le(0.9, 0.95, 1.0), "PASS")
    eq(stats.classify_le(1.2, 1.3, 1.0), "FAIL")
    eq(stats.classify_le(0.98, None, 1.0), "INCONCLUSIVE")


# --- markdown / number formatting -------------------------------------------

@test("md")
def tables():
    eq(md.table(["a", "b"], [[1, 2], [3, None]]),
       "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | None |")
    eq(md.table(["x"], []), "| x |\n|---|")


@test("md")
def fixed_and_signed():
    eq(md.fixed(1.2345), "1.2")
    eq(md.fixed(1.25, digits=3), "1.250")
    eq(md.fixed(None), "n/a")
    eq(md.fixed(None, dash="\u2014"), "\u2014")
    eq(md.signed(1.5), "+1.50")
    eq(md.signed(-1.0), "-1.00")
    eq(md.signed(None), "n/a")


@test("md")
def thousands():
    eq(md.thousands(1234567.891), "1,234,567.9")
    eq(md.thousands(1234.5, digits=2), "1,234.50")
    eq(md.thousands(None), "n/a")
    eq(md.thousands("bad"), "n/a")


@test("md")
def human_numbers():
    eq(md.num(1234.5), "1,234")
    eq(md.num(123.5), "123.5")
    eq(md.num(12.5), "12.50")
    eq(md.num(1234.5, nd=1), "1,234.5")
    eq(md.num(None), "\u2014")


@test("md")
def intervals_and_ratios():
    eq(md.ci(10.0, 1.0), "10.00 \u00b1 1.00")
    eq(md.ci(10.0, 1.0, nd=0), "10 \u00b1 1")
    eq(md.ci(10.0, None), "10.00 (n<2)")
    eq(md.ci(None, 1.0), "\u2014")
    eq(md.ratio(1.05), "1.0500 (+5.0%)")
    eq(md.ratio(0.9), "0.9000 (-10.0%)")
    eq(md.ratio(None), "\u2014")
    eq(md.ci_ratio(1.05, 0.02), "1.0500 \u00b1 0.0200")
    eq(md.ci_ratio(1.05, None), "1.0500 (n<2)")
    eq(md.ci_ratio(None, 0.02), "\u2014")


@test("md")
def percentages_and_stat_ci():
    eq(md.pct(0.6), "60.0 %")
    eq(md.pct(0.6, digits=0), "60 %")
    eq(md.pct(None), "n/a")
    eq(md.stat_ci({"mean": 1234567, "lb": 1000000, "ub": 1500000}),
       "1,234,567 [1,000,000, 1,500,000]")
    eq(md.stat_ci({"mean": 12.5, "lb": 10, "ub": 15}, digits=2),
       "12.50 [10.00, 15.00]")
    eq(md.stat_ci(None), "n/a")
    eq(md.stat_ci({"mean": None, "lb": 1, "ub": 2}), "n/a")


# --- JSON / manifest plumbing ----------------------------------------------

@test("jsonio")
def load_json_and_coercion():
    with scratch_dir() as tmp:
        good = Path(tmp) / "ok.json"
        good.write_text('{"a": 1}')
        eq(jsonio.load_json(good), {"a": 1})
        eq(jsonio.load_json(Path(tmp) / "missing.json"), None)
        bad = Path(tmp) / "bad.json"
        bad.write_text("{not json")
        eq(jsonio.load_json(bad), None)
    eq(jsonio.as_float("1.5"), 1.5)
    eq(jsonio.as_float(3), 3.0)
    eq(jsonio.as_float(None), None)
    eq(jsonio.as_float(True), None)
    eq(jsonio.as_float(False), None)
    eq(jsonio.as_float("nope"), None)
    eq(jsonio.as_int(3.9), 3)
    eq(jsonio.as_int("2"), 2)
    eq(jsonio.as_int(True), None)
    eq(jsonio.as_int("nope"), None)


@test("jsonio")
def manifests():
    with scratch_dir() as tmp:
        path = Path(tmp) / "manifest.txt"
        path.write_text("# title\n## section\nkey: value\nkey: second\n"
                        "other:  spaced  \n\nfree text\n")
        eq(jsonio.read_manifest(path), {"key": "value", "other": "spaced"})
        eq(jsonio.read_manifest(Path(tmp) / "absent.txt"), {})


@test("jsonio")
def stems_and_paths():
    eq(jsonio.split_stem("randread-4k-qd1__juring__std__bs4k__qd1__t1__r2"),
       ["randread-4k-qd1", "juring", "std", "bs4k", "qd1", "t1", "r2"])
    eq(jsonio.rep_index("randread-4k-qd1__juring__std__bs4k__qd1__t1__r2"), 2)
    eq(jsonio.rep_index("randread-4k-qd1__juring__std__r12"), 12)
    eq(jsonio.rep_index("noseparator"), None)
    eq(jsonio.rel(jsonio.REPO_ROOT / "bench" / "README.md"), "bench/README.md")
    eq(jsonio.rel("/definitely/not/in/repo"), "/definitely/not/in/repo")
    eq(jsonio.rel(REPO / "bench", root=REPO), "bench")


# --- canonical cell / pass identifiers --------------------------------------

@test("cells")
def canonical_cell_map():
    eq(cells.slug("randwrite-4k-qd64"), "randwrite-4k-qd64")
    eq(cells.label("randwrite-4k-qd64"), "4 KiB random write, 64 in flight")
    eq(cells.slug("not-a-cell"), "not-a-cell")
    eq(cells.label("not-a-cell"), "not-a-cell")
    eq(cells.lookup("randwrite-4k-qd64").kind, "throughput")
    eq(cells.lookup("pass1"), None, "pass ids are not cells")
    eq(len({c.slug for c in cells.CELLS}), len(cells.CELLS), "canonical slugs")
    eq(len({c.slug for c in cells.ALL}), len(cells.ALL), "all slugs")


@test("cells")
def cell_for_and_passes():
    eq(cells.cell_for("throughput", "randread", "4k", 64), "randread-4k-qd64")
    eq(cells.cell_for("latency", "randread", "4k", 64), "randread-4k-qd64-latency")
    eq(cells.cell_for("throughput", "randwrite", "4k", 128),
       "randwrite-4k-qd128")
    eq(cells.cell_for("durability", "stream_write", "128k", 8),
       "durability-write-128k")
    eq(cells.cell_for("groupcommit", "randwrite", "4k", None, 32),
       "groupcommit-4k-batch32")
    eq(cells.cell_for("throughput", "randread", "4k", 3), None)
    for value in ("pass1", "PASS1"):
        eq(cells.pass_name(value), "pass1", value)
        eq(cells.pass_label(value), "pass 1", value)
    # Non-canonical pass tokens are not accepted and pass through unchanged.
    for value in ("not-a-pass", "other"):
        eq(cells.pass_name(value), value, value)
        eq(cells.pass_label(value), value, value)
    eq(cells.pass_number("not-a-pass"), None)
    eq(cells.pass_number("pass3"), 3)
    eq(cells.pass_dir("/sessions/read-write", "pass4"), "/sessions/read-write/pass4")
    eq(cells.shard_count("shards-8"), 8)
    eq(cells.shard_count("not-shards"), None, "unknown shard id")
    eq(cells.shard_slug(4), "shards-4")


# --- CLI plumbing -----------------------------------------------------------

@test("cli")
def resolve_session():
    with scratch_dir() as tmp:
        root = Path(tmp) / "results"
        eq(cli.resolve_session(session="read-write", results_root=root),
           (root / "read-write").resolve())
        explicit = Path(tmp) / "elsewhere"
        eq(cli.resolve_session(session="read-write", session_dir=str(explicit)),
           explicit.resolve())
        eq(cli.resolve_session(default_session="scale-out"),
           (jsonio.REPO_ROOT / "bench" / "results" / "scale-out").resolve())
        try:
            cli.resolve_session()
        except ValueError:
            pass
        else:
            raise AssertionError("resolve_session() accepted no session name")


def run_unit() -> bool:
    failures = 0
    passed = 0
    for group, tests in _GROUPS.items():
        bad = 0
        for fn in tests:
            try:
                fn()
            except Exception as exc:  # the point is to report every failing test
                bad += 1
                print(f"unit {group}: FAIL {fn.__name__}: "
                      f"{type(exc).__name__}: {exc}")
        status = "ok" if not bad else "FAIL"
        print(f"unit {group:<12s} {status:<4s} ({len(tests)} test"
              f"{'s' if len(tests) != 1 else ''})")
        passed += len(tests) - bad
        failures += bad
    print(f"unit: {passed} passed, {failures} failed")
    return failures == 0


# ---------------------------------------------------------------------------

def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Regression harness for the bench analyzers.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--unit", action="store_true",
                        help="run the unit tests (the default and only mode)")
    parser.parse_args(argv)

    ok = run_unit()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
