#!/usr/bin/env python3
"""Step D (host representativeness) analyzer -- FROZEN with docs/stepd-design.md.

Usage:
    python3 bench/stepd/analyze_stepd.py --dir bench/results/stepd-2026-11-16

Reads <dir>/cell-meta.tsv (written by bench/stepd/run-stepd.sh) and emits
<dir>/analysis/stepd-summary.md + <dir>/analysis/stepd.ratios.csv.

Block A: paired seastar hugetlbfs / THP ratios per cell (R=3 under the frozen
protocol). Block B: paired juring/fio-matched and seastar/fio-matched ratios
per cell (R=5). Two-sided 95 % CIs use the Student t multiplier for n-1 df.
This script makes no pass/fail claim: Step D is a disclosure campaign.
"""
import argparse
import csv
import math
import os
import sys

T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447,
       7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228}


def read_meta(path):
    rows = []
    with open(path, newline="") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            rows.append(r)
    return rows


def ok_iops(r):
    try:
        i = float(r["iops"])
    except (TypeError, ValueError):
        return None
    if r.get("status") != "ok" or i <= 0:
        return None
    return i


def mean_ci(xs):
    n = len(xs)
    if n == 0:
        return None
    m = sum(xs) / n
    if n < 2:
        return m, None, None
    var = sum((x - m) ** 2 for x in xs) / (n - 1)
    se = math.sqrt(var / n)
    t = T95.get(n - 1)
    if t is None:
        return m, None, None
    return m, m - t * se, m + t * se


def fmt(v, nd=4):
    return "n/a" if v is None else f"{v:.{nd}f}"


def mibs(row, iops):
    try:
        bs = float(row["bs"].lower().replace("k", "000").replace("m", "000000"))
    except ValueError:
        return None
    return iops * bs / 1048576.0


def block_a(rows):
    out = []
    cells = sorted({(r["case"], r["bs"], r["qd"]) for r in rows if r["variant"] in ("thp", "huge")})
    for case, bs, qd in cells:
        per = {}
        for r in rows:
            if (r["case"], r["bs"], r["qd"]) != (case, bs, qd):
                continue
            i = ok_iops(r)
            if i is None:
                continue
            per.setdefault(r["rep"], {})[r["variant"]] = (i, r)
        pairs = []
        for rep, d in sorted(per.items(), key=lambda kv: int(kv[0])):
            if "thp" not in d or "huge" not in d:
                continue
            i_thp, r_thp = d["thp"]
            i_huge, r_huge = d["huge"]
            ratio = i_huge / i_thp
            hp0 = int(r_huge["hugepages_free_start"] or -1)
            hp1 = int(r_huge["hugepages_free_end"] or -1)
            pairs.append({"case": case, "bs": bs, "qd": qd, "rep": rep,
                          "thp": i_thp, "huge": i_huge, "ratio": ratio,
                          "hp_used": hp0 - hp1,
                          "mibs_thp": mibs(r_thp, i_thp), "mibs_huge": mibs(r_huge, i_huge)})
        out.append((case, bs, qd, pairs))
    return out


def block_b(rows):
    out = []
    cells = sorted({(r["case"], r["bs"], r["qd"]) for r in rows
                    if r["engine"] in ("juring", "fio-io_uring", "seastar") and r["variant"] in ("std", "matched")})
    for case, bs, qd in cells:
        per = {}
        for r in rows:
            if (r["case"], r["bs"], r["qd"]) != (case, bs, qd):
                continue
            i = ok_iops(r)
            if i is None:
                continue
            key = "fio" if r["engine"] == "fio-io_uring" else r["engine"]
            per.setdefault(r["rep"], {})[key] = i
        pairs = []
        for rep, d in sorted(per.items(), key=lambda kv: int(kv[0])):
            if "fio" not in d:
                continue
            p = {"case": case, "bs": bs, "qd": qd, "rep": rep, "fio": d["fio"]}
            p["juring"] = d.get("juring")
            p["seastar"] = d.get("seastar")
            p["j_ratio"] = (d["juring"] / d["fio"]) if d.get("juring") else None
            p["s_ratio"] = (d["seastar"] / d["fio"]) if d.get("seastar") else None
            pairs.append(p)
        out.append((case, bs, qd, pairs))
    return out


def ratio_stats(pairs, key):
    xs = [p[key] for p in pairs if p.get(key) is not None]
    if not xs:
        return None
    m, lo, hi = mean_ci(xs)
    return {"n": len(xs), "mean": m, "lo": lo, "hi": hi}


def verdict(st, delta_bar=0.05):
    if not st:
        return "no data"
    if st["lo"] is None:
        return "n<2 (no CI)"
    if st["lo"] <= 1.0 <= st["hi"]:
        return "no material difference (CI spans 1)"
    if abs(st["mean"] - 1.0) < delta_bar:
        return f"statistically distinct but < {delta_bar:.0%} (not material)"
    if st["lo"] > 1.0:
        return "ratio higher at >= 5%"
    return "ratio lower at >= 5%"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    args = ap.parse_args()
    d = args.dir
    meta = os.path.join(d, "cell-meta.tsv")
    if not os.path.exists(meta):
        sys.exit(f"missing {meta}")
    rows = read_meta(meta)
    fails = [r for r in rows if r.get("status") != "ok"]
    warns = 0
    wf = os.path.join(d, "logs", "warnings.log")
    if os.path.exists(wf):
        with open(wf) as f:
            warns = sum(1 for _ in f)

    lines = []
    lines.append("# Step D — host representativeness: campaign analysis\n")
    lines.append(f"- results dir: `{d}`")
    lines.append(f"- rows: {len(rows)} ({len(rows) - len(fails)} ok, {len(fails)} fail), "
                 f"hugepage warnings: {warns}")
    lines.append("- protocol: `docs/stepd-design.md` v1.0 (frozen); disclosure campaign, **no pass/fail**")
    if fails:
        lines.append("- failed rows: " + ", ".join(
            f"{r['case']}/{r['engine']}/{r['variant']}/qd{r['qd']}/r{r['rep']}" for r in fails[:12]))
    lines.append("")
    ratios_csv = []

    a = block_a(rows)
    if a:
        lines.append("## Block A — seastar THP vs hugetlbfs (paired, R=3)\n")
        lines.append("| cell | rep | THP IOPS | hugetlbfs IOPS | huge/THP | hugepages consumed |")
        lines.append("|---|---|---|---|---|---|")
        summaries = []
        for case, bs, qd, pairs in a:
            for p in pairs:
                lines.append(f"| {case} qd{qd} | {p['rep']} | {p['thp']:,.0f} | {p['huge']:,.0f} | "
                             f"{p['ratio']:.4f} | {p['hp_used']} |")
                ratios_csv.append(["A", case, bs, qd, p["rep"], "huge/thp", f"{p['ratio']:.6f}"])
            st = ratio_stats(pairs, "ratio")
            summaries.append((case, qd, st))
        lines.append("")
        lines.append("| cell | mean huge/THP | 95% CI | n | reading |")
        lines.append("|---|---|---|---|---|")
        for case, qd, st in summaries:
            if st:
                lines.append(f"| {case} qd{qd} | {st['mean']:.4f} | "
                             f"[{fmt(st['lo'])}, {fmt(st['hi'])}] | {st['n']} | {verdict(st)} |")
            else:
                lines.append(f"| {case} qd{qd} | n/a | n/a | 0 | no paired runs |")
        lines.append("")

    b = block_b(rows)
    if b:
        lines.append("## Block B — memlock best-config read cells (paired, R=5)\n")
        lines.append("| cell | rep | juring IOPS | fio matched IOPS | seastar IOPS | J/fio | seastar/fio |")
        lines.append("|---|---|---|---|---|---|---|")
        summaries = []
        for case, bs, qd, pairs in b:
            for p in pairs:
                j = f"{p['juring']:,.0f}" if p["juring"] else "fail"
                s = f"{p['seastar']:,.0f}" if p["seastar"] else "fail"
                jr = fmt(p["j_ratio"]) if p["j_ratio"] else "n/a"
                sr = fmt(p["s_ratio"]) if p["s_ratio"] else "n/a"
                lines.append(f"| {case} qd{qd} | {p['rep']} | {j} | {p['fio']:,.0f} | {s} | {jr} | {sr} |")
                if p["j_ratio"]:
                    ratios_csv.append(["B", case, bs, qd, p["rep"], "juring/fio", f"{p['j_ratio']:.6f}"])
                if p["s_ratio"]:
                    ratios_csv.append(["B", case, bs, qd, p["rep"], "seastar/fio", f"{p['s_ratio']:.6f}"])
            summaries.append((case, qd,
                              ratio_stats(pairs, "j_ratio"), ratio_stats(pairs, "s_ratio")))
        lines.append("")
        lines.append("| cell | mean J/fio | 95% CI | n | mean seastar/fio | 95% CI | n |")
        lines.append("|---|---|---|---|---|---|---|")
        for case, qd, jst, sst in summaries:
            def cellfmt(st):
                if not st:
                    return "n/a", "n/a", 0
                return f"{st['mean']:.4f}", f"[{fmt(st['lo'])}, {fmt(st['hi'])}]", st["n"]
            jm, jc, jn = cellfmt(jst)
            sm, sc, sn = cellfmt(sst)
            lines.append(f"| {case} qd{qd} | {jm} | {jc} | {jn} | {sm} | {sc} | {sn} |")
        lines.append("")

    os.makedirs(os.path.join(d, "analysis"), exist_ok=True)
    md = os.path.join(d, "analysis", "stepd-summary.md")
    csvf = os.path.join(d, "analysis", "stepd.ratios.csv")
    with open(md, "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(csvf, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["block", "case", "bs", "qd", "rep", "ratio_name", "ratio"])
        w.writerows(ratios_csv)
    print("\n".join(lines))
    print(f"\nwrote {md}\nwrote {csvf}")


if __name__ == "__main__":
    main()
