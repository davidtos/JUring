"""Shared code for the benchmark analyzers.

The entry points under ``bench/`` are thin CLIs: they own the campaign policy
(which cells, which bars, which verdict) and nothing else.  Everything that
more than one analyzer needs -- Student-t intervals, paired ratios, JSON and
manifest loading, markdown/number formatting -- lives here so the campaigns
cannot quietly drift apart.  Stdlib only, like the entry points: these tools
must run with a bare ``python3`` on the bench host, with no install step.

Two statistics conventions are deliberately kept side by side, because the
published tables were produced with each and a silent swap would move numbers
in BENCHMARK.md:

* :func:`mean`, :func:`sd`, :func:`ci95`, :func:`one_sided_lower`,
  :func:`cv_pct` use summed moments and frozen t-critical tables (the
  ``analyze_stream`` family and the shard/host-setup analyzers);
* :func:`describe`, :func:`ratio_stats` use :mod:`statistics` and an exact
  inverse Student-t CDF (:mod:`analyze_read_write`).

New analyzers should prefer the exact (``describe``/``ratio_stats``) path.
"""
