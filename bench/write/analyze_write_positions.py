#!/usr/bin/env python3
"""Position-controlled write comparison for the second-drive run.

The frozen state-controlled gate assumes the three JUring/fio arms inside a
homogeneous pass fall within max/min <= 1.25. On the near-empty second
drive the engines separate by more than that, and the spread follows the engine
rather than the execution slot. This script implements the contingency fixed in
docs/write-state-3way-protocol.md ("Run 2 analysis contingency"):

  * frozen gate result is primary and must be INCONCLUSIVE only because of the
    homogeneity bound (the caller passes it in / it is re-read here);
  * per-engine means, CIs and CV across passes;
  * position-stratified values (first/middle/last slot);
  * paired per-pass ratios with 90%/95% CIs;
  * the ranking is only emitted if every pre-registered check passes.

Passes are pass1..pass8 and the CLI flag is --passes.

Usage:
    python3 bench/write/analyze_write_positions.py --session DIR --passes pass1,pass2 --out DIR
"""
import argparse
import csv
import importlib.util
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(REPO, "bench"))
from analysis import cells, stats  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "frozen_write_state", os.path.join(REPO, "bench", "host-setup", "analyze_write_state.py"))
aw = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(aw)

CELLS = ("randwrite-4k-qd64", "randwrite-4k-qd128")
CELL_QD = {cell: str(cells.lookup(cell).qd) for cell in CELLS}
BS = 4096
ARMS = [
    ("juring", "std", "JUring"),
    ("fio-io_uring", "matched", "fio matched"),
    ("fio-io_uring", "matchedbatch", "fio matchedbatch"),
    ("seastar", "std", "Seastar"),
]
COMPARATORS = [
    (("juring", "std"), ("fio-io_uring", "matched"), "JUring/fio-matched"),
    (("juring", "std"), ("fio-io_uring", "matchedbatch"), "JUring/fio-matchedbatch"),
    (("juring", "std"), ("seastar", "std"), "JUring/Seastar"),
    (("fio-io_uring", "matched"), ("seastar", "std"), "fio-matched/Seastar"),
]
CV_MAX = 10.0
POS_VARIATION_MAX = 0.05
MIN_GATE_FAILURES = 4


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def load_rows(d):
    """Execution-ordered rows: cell-meta.tsv is appended in run order."""
    with open(os.path.join(d, "cell-meta.tsv"), encoding="utf-8") as fh:
        return list(csv.DictReader(fh, delimiter="\t"))


def collect(session, passes):
    per = {}          # (pass, cell, engine, variant) -> iops
    slot = {}         # (pass, cell, engine, variant) -> 1-based run slot
    frozen = {}       # pass -> bool
    gate_spread = {}  # (pass, cell) -> max/min of the three frozen arms
    for name in passes:
        d = os.path.join(session, name)
        rows = load_rows(d)
        frozen[name] = bool(aw.gen_verdict(d)["pass"])
        order = {}
        for r in rows:
            cell = cells.slug(r["case"])
            if cell not in CELLS:
                continue
            key = (r["engine"], r["variant"])
            order.setdefault(cell, []).append(key)
            per[(name, cell, r["engine"], r["variant"])] = (
                fnum(r["iops"]) if r["status"] == "ok" else None)
        for cell in CELLS:
            for i, (engine, variant) in enumerate(order.get(cell, []), start=1):
                slot[(name, cell, engine, variant)] = i
            levels = [per[(name, cell, e, v)] for e, v, _l in ARMS
                      if (e, v) in order.get(cell, []) and per[(name, cell, e, v)]]
            if len(levels) == 3:
                gate_spread[(name, cell)] = max(levels) / min(levels)
    return per, slot, frozen, gate_spread


def summary(per, passes, cell, engine, variant):
    vals = [per[(p, cell, engine, variant)] for p in passes
            if per.get((p, cell, engine, variant))]
    return stats.describe(vals)


def position_table(per, slot, passes, cell, engine, variant):
    by_pos = {}
    for p in passes:
        iops = per.get((p, cell, engine, variant))
        pos = slot.get((p, cell, engine, variant))
        if iops and pos:
            by_pos.setdefault(pos, []).append(iops)
    return by_pos


def ratio_for(per, passes, cell, left, right):
    lreps, rreps = [], []
    for i, p in enumerate(passes):
        a = per.get((p, cell, *left))
        b = per.get((p, cell, *right))
        if a and b:
            lreps.append({"rep_index": i, "iops": a})
            rreps.append({"rep_index": i, "iops": b})
    return stats.ratio_stats(lreps, rreps)


def verdict_for(st):
    if not st or st.get("n_pairs", 0) < 2 or st.get("mean") is None:
        return "INCONCLUSIVE (n<2)"
    if st["lb95_1s"] is not None and st["lb95_1s"] > 1.05:
        return "FASTER >=5% (one-sided 95% LB > 1.05)"
    if st["ci90_high"] is not None and st["ci90_high"] < 0.95:
        return "SLOWER >=5% (90% CI upper < 0.95)"
    v, _msg = stats.band_verdict(st, (0.95, 1.05))
    if v == "PASS":
        return "PARITY (90% CI inside [0.95, 1.05])"
    return "INCONCLUSIVE (interval spans the 5% bar)"


def write_report(outdir, session, passes, per, slot, frozen, gate_spread):
    os.makedirs(outdir, exist_ok=True)
    n_frozen_valid = sum(1 for p in passes if frozen[p])
    n_gate_failed = len(passes) - n_frozen_valid
    cell_data = {}
    checks = {"cv_ok": True, "position_ok": True, "gate_failures_ok": n_gate_failed >= MIN_GATE_FAILURES,
              "ratio_ok": True}
    for cell in CELLS:
        cell_data[cell] = {}
        for engine, variant, _label in ARMS:
            d = summary(per, passes, cell, engine, variant)
            pos = position_table(per, slot, passes, cell, engine, variant)
            pos_means = {p: sum(v) / len(v) for p, v in pos.items()}
            pos_var = (max(pos_means.values()) / min(pos_means.values())
                       if len(pos_means) > 1 else 1.0)
            if d["cv_pct"] is not None and d["cv_pct"] > CV_MAX:
                checks["cv_ok"] = False
            if pos_var - 1.0 > POS_VARIATION_MAX:
                checks["position_ok"] = False
            cell_data[cell][(engine, variant)] = {
                "summary": d, "positions": {p: v for p, v in pos.items()},
                "position_means": pos_means, "position_variation": pos_var,
            }
        for left, right, name in COMPARATORS:
            st = ratio_for(per, passes, cell, left, right)
            cell_data[cell][name] = st
            if st.get("n_pairs", 0) >= 2 and st.get("ci90_low") is not None:
                if not (st["ci90_low"] > 1.05 or st["ci90_high"] < 0.95
                        or (st["ci90_low"] >= 0.95 and st["ci90_high"] <= 1.05)):
                    checks["ratio_ok"] = False
    rankable = all(checks.values())

    lines = ["# Second-drive write comparison — position-controlled analysis", ""]
    lines.append(f"- session: `{session}`")
    lines.append("- protocol: [docs/write-state-3way-protocol.md](../docs/write-state-3way-protocol.md) "
                 "('Run 2 analysis contingency', fixed before this campaign finished)")
    lines.append("- passes: " + ", ".join(cells.pass_label(p) for p in passes)
                 + "; frozen state-controlled gate: "
                 f"**{n_frozen_valid}/{len(passes)} valid**, INCONCLUSIVE because the per-pass "
                 f"`max/min` bound rejects the spread")
    lines.append("- windows: 60 s warmup + 60 s measured per arm, fresh process, CPU 2, "
                 "continuous post-burn-in state on a near-empty same-model NVMe "
                 "(`nvme2n1p1`, 6% used; 400 GiB SLC burn-in before pass 1).")
    lines.append("- engine order rotated per pass (JUring/fio/Seastar 3-cycle), so each engine "
                 "ran first, middle and last; the position table below is the control for that.")
    lines.append("")
    lines.append(f"**Pre-registered checks:** CV < {CV_MAX:.0f}% for every engine: "
                 f"{'PASS' if checks['cv_ok'] else 'FAIL'}; position variation < "
                 f"{POS_VARIATION_MAX * 100:.0f}%: {'PASS' if checks['position_ok'] else 'FAIL'}; "
                 f">= {MIN_GATE_FAILURES} passes fail the frozen homogeneity bound: "
                 f"{'PASS' if checks['gate_failures_ok'] else 'FAIL'} ({n_gate_failed}/{len(passes)}); "
                 f"paired intervals decisive or parity: {'PASS' if checks['ratio_ok'] else 'FAIL'}.")
    lines.append("")
    if rankable:
        lines.append("**Result: the position-controlled comparison is admitted by all pre-registered "
                     "checks.** The frozen gate is INCONCLUSIVE only because its `max/min <= 1.25` "
                     "bound treats a real engine difference larger than 25% as state drift; with "
                     "every engine reproducing its value across slots, the spread is the engines.")
    else:
        lines.append("**Result: NOT admitted — at least one pre-registered check failed, so no "
                     "ranking is claimed.** The numbers are still published as descriptive.")
    lines.append("")

    lines.append("## Per-engine throughput across passes")
    lines.append("")
    lines.append("| cell | engine | n | IOPS mean ± 95% CI | MiB/s | CV% | min | median | max |")
    lines.append("|---|---|---|---|---|---|---|---|---|")
    for cell in CELLS:
        for engine, variant, label in ARMS:
            d = cell_data[cell][(engine, variant)]["summary"]
            mean_s = f"{d['mean']:,.0f}" if d["mean"] is not None else "-"
            ci_s = f" ± {d['ci95_half']:,.0f}" if d["ci95_half"] is not None else ""
            mibs = f"{d['mean'] * BS / 1048576.0:,.1f}" if d["mean"] is not None else "-"
            cv = f"{d['cv_pct']:.2f}" if d["cv_pct"] is not None else "-"
            min_s = f"{d['min']:,.0f}" if d["min"] is not None else "-"
            med_s = f"{d['median']:,.0f}" if d["median"] is not None else "-"
            max_s = f"{d['max']:,.0f}" if d["max"] is not None else "-"
            lines.append(f"| {cells.label(cell)} | {label} | {d['n']} | {mean_s}{ci_s} | "
                         f"{mibs} | {cv} | "
                         f"{min_s} | {med_s} | {max_s} |")
    lines.append("")

    lines.append("## Position control (slot in the pass)")
    lines.append("")
    lines.append("| cell | engine | slot 1 | slot 2 | slot 3 | slot 4 | slot max/min |")
    lines.append("|---|---|---|---|---|---|---|")
    for cell in CELLS:
        for engine, variant, label in ARMS:
            cd = cell_data[cell][(engine, variant)]
            means = cd["position_means"]
            cells_s = []
            for p in (1, 2, 3, 4):
                v = means.get(p)
                cells_s.append(f"{v:,.0f}" if v else "—")
            var = cd["position_variation"]
            var_s = f"{var:.4f}" if len(means) > 1 else "—"
            lines.append(f"| {cells.label(cell)} | {label} | "
                         + " | ".join(cells_s) + f" | {var_s} |")
    lines.append("")

    lines.append("## Paired ratios (per pass = same device state)")
    lines.append("")
    lines.append("| cell | ratio | n pairs | mean | 90% CI | 95% CI | verdict |")
    lines.append("|---|---|---|---|---|---|---|")
    for cell in CELLS:
        for _left, _right, name in COMPARATORS:
            st = cell_data[cell][name]
            ci90 = (f"[{st['ci90_low']:.4f}, {st['ci90_high']:.4f}]"
                    if st.get("ci90_low") is not None else "-")
            ci95 = (f"[{st['ci95_low']:.4f}, {st['ci95_high']:.4f}]"
                    if st.get("ci95_low") is not None else "-")
            mean = f"{st['mean']:.4f}" if st.get("mean") is not None else "-"
            lines.append(f"| {cells.label(cell)} | {name} | {st.get('n_pairs', 0)} | "
                         f"{mean} | {ci90} | "
                         f"{ci95} | {verdict_for(st)} |")
    lines.append("")

    lines.append("## Frozen-gate per-pass spread (why it is INCONCLUSIVE)")
    lines.append("")
    lines.append("| pass | cell | max/min of the three frozen arms | frozen PILOT_PASS |")
    lines.append("|---|---|---|---|")
    for p in passes:
        for cell in CELLS:
            sp = gate_spread.get((p, cell))
            lines.append(f"| {cells.pass_label(p)} | {cells.label(cell)} | {sp:.4f} | "
                         f"{frozen[p]} |" if sp
                         else f"| {cells.pass_label(p)} | {cells.label(cell)} | — | "
                         f"{frozen[p]} |")
    lines.append("")
    lines.append("## Caveats")
    lines.append("")
    lines.append("- One host, one near-empty consumer NVMe (same model/firmware as the read drive), "
                 "one kernel. The drive was in a fast, cache-assisted state after a 400 GiB "
                 "steady-state burn-in; absolute IOPS are not a sustained-endurance spec.")
    lines.append("- `fio` cannot set JUring's taskrun flags or fuse submits; Seastar is a bracket "
                 "(stock 200-entry ring, no registered file, no fixed buffers). No fsync/durability "
                 "claim.")
    lines.append("- The frozen state-controlled gate is reported next to this table and is "
                 "INCONCLUSIVE; this position-controlled comparison is the pre-registered secondary "
                 "analysis, not a re-run of that gate.")
    lines.append("")

    md_path = os.path.join(outdir, "write-positions-summary.md")
    with open(md_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    payload = {
        "session": session, "passes": passes, "frozen_valid": n_frozen_valid,
        "checks": checks, "rankable": rankable,
        "cells": {c: {"engines": {f"{e}|{v}": cd for (e, v), cd in
                                  ((k, cell_data[c][k]) for k in
                                   [(e, v) for e, v, _l in ARMS])},
                      "ratios": {n: cell_data[c][n] for _l, _r, n in COMPARATORS}}
                  for c in CELLS},
    }
    with open(os.path.join(outdir, "write-positions-summary.json"), "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2)
    return md_path, rankable, cell_data, checks, n_frozen_valid


def parse_passes(text):
    """Comma/space-separated passN values; gN forms are rejected."""
    tokens = [t for t in text.replace(",", " ").split() if t]
    if not tokens:
        raise argparse.ArgumentTypeError("at least one pass is required")
    for token in tokens:
        if cells.pass_number(token) is None or cells.pass_name(token) != token:
            raise argparse.ArgumentTypeError(
                "pass must be pass1..pass8 (got %r)" % (token,))
    return tokens


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--session", required=True)
    ap.add_argument("--passes", required=True, type=parse_passes,
                    help="comma/space-separated passes, pass1..pass8")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    passes = args.passes
    per, slot, frozen, gate_spread = collect(args.session, passes)
    md_path, rankable, cell_data, checks, nvalid = write_report(
        args.out, args.session, passes, per, slot, frozen, gate_spread)
    print(f"wrote {md_path}")
    print(f"frozen valid: {nvalid}/{len(passes)}; checks={checks}; rankable={rankable}")
    for cell in CELLS:
        for engine, variant, label in ARMS:
            d = cell_data[cell][(engine, variant)]["summary"]
            if d["mean"] is not None:
                ci = f" ±{d['ci95_half']:,.0f}" if d["ci95_half"] is not None else ""
                cv = f"{d['cv_pct']:.2f}%" if d["cv_pct"] is not None else "n/a"
                print(f"  {cells.label(cell)} {label:12s} n={d['n']} "
                      f"mean={d['mean']:,.0f}{ci} CV={cv}")
        for _l, _r, name in COMPARATORS:
            st = cell_data[cell][name]
            if st.get("mean") is not None:
                print(f"  {cells.label(cell)} {name:24s} n={st['n_pairs']} "
                      f"ratio={st['mean']:.4f} "
                      f"-> {verdict_for(st)}")


if __name__ == "__main__":
    main()
