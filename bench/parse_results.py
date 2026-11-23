#!/usr/bin/env python3
"""Normalize benchmark results from three engines into one table/CSV.

Usage:
    python3 bench/parse_results.py --dir bench-results --csv bench-results/results.csv
    python3 bench/parse_results.py bench-results/raw/juring/*.json bench-results/raw/fio/*.json

Inputs are auto-detected by content, extension does not matter:
  * JUring      - JSON printed by bench/juring/JUringFileBench.java
  * fio         - JSON produced with `fio --output-format=json`
  * seastar     - YAML printed by apps/io_tester (stdout; preamble lines skipped)

Outputs: a fixed-width table on stdout and, with --csv or a scanned --dir,
<dir>/results.csv sorted by case then engine.  Only the Python standard library
is required; PyYAML is used for the io_tester YAML when installed, otherwise a
built-in YAML-subset parser handles it (maps, sequences of maps, scalars,
comments).  The table/CSV shape and the exit codes are a frozen contract with
bench/run-comparison.sh, which calls this script.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analysis import jsonio, md  # noqa: E402

ENGINE_ORDER = {"juring": 0, "fio": 1, "seastar": 2}

COLUMNS = [
    "case",
    "engine",
    "workload",
    "bs",
    "qd",
    "threads",
    "iops",
    "mibs",
    "p50_us",
    "p99_us",
    "p999_us",
    "max_us",
    "ops",
    "errors",
    "buffer_mode",
    "source",
]

NUM_RE = re.compile(r"^\s*[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?\s*$")


# ---------------------------------------------------------------------------
# PyYAML-optional YAML loading with a built-in io_tester-oriented fallback
# ---------------------------------------------------------------------------

def _mini_scalar(text: str):
    t = text.strip()
    if t in ("", "~", "null", "Null", "NULL"):
        return None
    if t in ("true", "True", "TRUE"):
        return True
    if t in ("false", "False", "FALSE"):
        return False
    if (len(t) >= 2) and ((t[0] == t[-1] == '"') or (t[0] == t[-1] == "'")):
        return t[1:-1]
    if NUM_RE.match(t):
        try:
            return int(t) if re.match(r"^\s*[-+]?\d+\s*$", t) else float(t)
        except ValueError:
            return t
    return t


def _strip_comment(line: str) -> str:
    # io_tester/YAML::Emitter comments look like "value # comment".  Ignore '#'
    # inside quoted scalars.
    out = []
    quote = None
    for i, ch in enumerate(line):
        if quote:
            if ch == quote:
                quote = None
            out.append(ch)
        elif ch in ("'", '"'):
            quote = ch
            out.append(ch)
        elif ch == "#" and (i == 0 or line[i - 1].isspace()):
            break
        else:
            out.append(ch)
    return "".join(out).rstrip()


class MiniYamlError(Exception):
    pass


def mini_yaml_load(text: str):
    """Parse the YAML subset used by seastar's io_tester output.

    Supports: block maps, block sequences of maps, plain/quoted scalars,
    comments, and the leading `---` / trailing `...` document markers.
    """
    raw = []
    for line in text.splitlines():
        if line.strip() in ("---", "..."):
            continue
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        line = _strip_comment(line)
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        raw.append((indent, line.strip()))

    def parse_block(i: int, indent: int):
        if i >= len(raw) or raw[i][0] < indent:
            return None, i
        if raw[i][1].startswith("- "):
            return parse_seq(i, indent)
        return parse_map(i, indent)

    def parse_seq(i: int, indent: int):
        items = []
        while i < len(raw) and raw[i][0] == indent and raw[i][1].startswith("- "):
            content = raw[i][1][2:].strip()
            i += 1
            if not content:
                sub, i = parse_block(i, indent + 2)
                items.append(sub)
                continue
            # "- key: value" starts a map whose keys are indented by 2 from '-'
            m = re.match(r"^([^:]+):(.*)$", content)
            if not m:
                items.append(_mini_scalar(content))
                continue
            key = _mini_scalar(m.group(1).strip())
            rest = m.group(2).strip()
            item = {}
            if rest:
                item[key] = _mini_scalar(rest)
            else:
                sub, i = parse_block(i, indent + 2)
                item[key] = sub
            # remaining keys of this item at indent + 2
            if i < len(raw) and raw[i][0] > indent:
                child_indent = raw[i][0]
                child, i = parse_map(i, child_indent)
                if isinstance(child, dict):
                    item.update(child)
            items.append(item)
        return items, i

    def parse_map(i: int, indent: int):
        result = {}
        while i < len(raw) and raw[i][0] == indent:
            text = raw[i][1]
            m = re.match(r"^([^:]+):(.*)$", text)
            if not m:
                raise MiniYamlError(f"cannot parse line: {text!r}")
            key = _mini_scalar(m.group(1).strip())
            rest = m.group(2).strip()
            i += 1
            if rest:
                result[key] = _mini_scalar(rest)
            else:
                if i < len(raw) and raw[i][0] > indent:
                    sub, i = parse_block(i, raw[i][0])
                    result[key] = sub
                else:
                    result[key] = None
        return result, i

    value, i = parse_block(0, 0 if not raw else raw[0][0])
    if i != len(raw):
        raise MiniYamlError(f"unparsed YAML remainder at line {i}")
    return value


def load_yaml_text(text: str):
    try:
        import yaml  # type: ignore
        return yaml.safe_load(text)
    except ImportError:
        return mini_yaml_load(text)
    except Exception:
        return mini_yaml_load(text)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def infer_bs_qd(case: str):
    """Infer block size / queue depth from case names like randread_4k_qd64."""
    bs = None
    qd = None
    m = re.search(r"(?:^|[_-])(\d+)([kmg])(?:[_-]|$)", case or "")
    if m:
        mult = {"k": 1024, "m": 1024 ** 2, "g": 1024 ** 3}[m.group(2)]
        bs = int(m.group(1)) * mult
    m = re.search(r"qd(\d+)", case or "")
    if m:
        qd = int(m.group(1))
    return bs, qd


def infer_workload(case: str):
    c = (case or "").lower()
    for w in ("randread", "randwrite", "seqread", "seqwrite", "nop"):
        if w in c:
            return w
    if "latency" in c:
        return "randread"
    return ""


def make_row(engine, case, workload, bs, qd, threads, iops, mibs, p50, p99, p999, mx,
             ops, errors, buffer_mode, source):
    ibs, iqd = infer_bs_qd(case)
    return {
        "case": case,
        "engine": engine,
        "workload": workload or infer_workload(case),
        "bs": bs if bs is not None else ibs,
        "qd": qd if qd is not None else iqd,
        "threads": threads,
        "iops": iops,
        "mibs": mibs,
        "p50_us": p50,
        "p99_us": p99,
        "p999_us": p999,
        "max_us": mx,
        "ops": ops,
        "errors": errors,
        "buffer_mode": buffer_mode,
        "source": str(source),
    }


# ---------------------------------------------------------------------------
# JUring JSON
# ---------------------------------------------------------------------------

def parse_juring(path: Path, data: dict):
    engine = data.get("engine")
    if engine not in ("juring", "seastar"):
        return []
    cfg = data.get("config") or {}
    agg = data.get("aggregate")
    if not agg:
        return []  # failed run: no measurements
    label = data.get("label") or cfg.get("workload") or path.stem
    iops = jsonio.as_float((agg.get("iops") or {}).get("mean"))
    mibs = jsonio.as_float((agg.get("mibs") or {}).get("mean"))
    lat = agg.get("latency_us") or {}
    errors = jsonio.as_int(agg.get("errors_total"))
    if errors is None:
        errors = ""
    return [make_row(
        engine, label, cfg.get("workload"),
        jsonio.as_int(cfg.get("bs")), jsonio.as_int(cfg.get("qd")),
        jsonio.as_int(cfg.get("threads")), iops, mibs,
        jsonio.as_float(lat.get("p50")), jsonio.as_float(lat.get("p99")),
        jsonio.as_float(lat.get("p999")), jsonio.as_float(lat.get("max")),
        jsonio.as_int(agg.get("ops_total")), errors,
        cfg.get("io_buffer_mode"), path,
    )]


# ---------------------------------------------------------------------------
# fio JSON
# ---------------------------------------------------------------------------

def _fio_percentile(section: dict, target: float):
    for key in ("clat_ns", "lat_ns", "lat"):
        s = section.get(key)
        if not isinstance(s, dict):
            continue
        pct = s.get("percentile")
        if isinstance(pct, dict) and pct:
            best = None
            for k, v in pct.items():
                try:
                    kf = float(k)
                except (TypeError, ValueError):
                    continue
                if best is None or abs(kf - target) < abs(best[0] - target):
                    best = (kf, v)
            if best is not None:
                if key.endswith("_ns"):
                    return jsonio.as_float(best[1]) / 1000.0
                return jsonio.as_float(best[1])
        for mk in ("max", "mean"):
            if mk in s and target >= 99.999 and mk == "max":
                if key.endswith("_ns"):
                    return jsonio.as_float(s[mk]) / 1000.0
                return jsonio.as_float(s[mk])
    return None


def _fio_max_lat(section: dict):
    for key in ("clat_ns", "lat_ns", "lat"):
        s = section.get(key)
        if isinstance(s, dict) and "max" in s:
            v = jsonio.as_float(s["max"])
            return v / 1000.0 if key.endswith("_ns") else v
    return None


def parse_fio(path: Path, data: dict):
    if "jobs" not in data or "fio version" not in data:
        return []
    rows = []
    for job in data.get("jobs") or []:
        opts = job.get("job options") or {}
        rw = str(opts.get("rw") or "")
        read = job.get("read") or {}
        write = job.get("write") or {}
        use_read = rw.startswith("read") or rw in ("randread", "read")
        if not rw:
            use_read = (jsonio.as_float(read.get("io_bytes"))
                        or 0 >= (jsonio.as_float(write.get("io_bytes")) or 0))
        # pick the direction that actually moved bytes
        if not use_read and ((jsonio.as_float(read.get("io_bytes")) or 0)
                             > (jsonio.as_float(write.get("io_bytes")) or 0)):
            use_read = True
        elif use_read and ((jsonio.as_float(write.get("io_bytes")) or 0)
                           > (jsonio.as_float(read.get("io_bytes")) or 0)):
            use_read = False
        sec = read if use_read else write
        workload = "randread" if "randread" in rw else "seqread" if "read" in rw else \
                   "randwrite" if "randwrite" in rw else "seqwrite" if rw else ""
        name = job.get("jobname") or path.stem
        case = path.stem if len(data.get("jobs") or []) == 1 else f"{path.stem}:{name}"
        iops = jsonio.as_float(sec.get("iops"))
        bw_kib = jsonio.as_float(sec.get("bw"))
        mibs = bw_kib / 1024.0 if bw_kib is not None else None
        p50 = _fio_percentile(sec, 50.0)
        p99 = _fio_percentile(sec, 99.0)
        p999 = _fio_percentile(sec, 99.9)
        mx = _fio_max_lat(sec)
        errors = job.get("error")
        if errors is None:
            errors = sec.get("total_err")
        rows.append(make_row(
            "fio", case, workload,
            jsonio.as_int(opts.get("bs")), jsonio.as_int(opts.get("iodepth")),
            jsonio.as_int(opts.get("numjobs")) or 1, iops, mibs, p50, p99, p999, mx,
            jsonio.as_int(sec.get("total_ios")), jsonio.as_int(errors), "", path,
        ))
    return rows


# ---------------------------------------------------------------------------
# seastar io_tester YAML
# ---------------------------------------------------------------------------

SHARD_LINE_RE = re.compile(r"^\s*-\s*shard:\s*\d+\s*$")


def find_yaml_start(text: str) -> int:
    """Return index of the first io_tester YAML line, skipping the preamble."""
    for i, line in enumerate(text.splitlines()):
        if SHARD_LINE_RE.match(line):
            return i
    return -1


def _pct_value(d: dict, key: str):
    if not isinstance(d, dict):
        return None
    return jsonio.as_float(d.get(key))


def parse_seastar(path: Path, text: str):
    start = find_yaml_start(text)
    if start < 0:
        return []
    yaml_text = "\n".join(text.splitlines()[start:])
    doc = load_yaml_text(yaml_text)
    if isinstance(doc, dict):
        doc = [doc]
    if not isinstance(doc, list):
        return []
    # group every job (class) by name across shards
    jobs: dict[str, list] = {}
    shard_count = 0
    aio_retries = None
    for shard_map in doc:
        if not isinstance(shard_map, dict):
            continue
        shard_count += 1
        for key, value in shard_map.items():
            if key == "statistics":
                if isinstance(value, dict):
                    aio_retries = jsonio.as_float(value.get("aio_retries"))
                continue
            if key == "shard":
                continue
            jobs.setdefault(key, []).append(value)

    rows = []
    for job_name, entries in jobs.items():
        iops = 0.0
        throughput_kbs = 0.0
        requests = 0
        p50 = p99 = p999 = mx = None
        for e in entries:
            if not isinstance(e, dict):
                continue
            iops += jsonio.as_float(e.get("IOPS")) or 0.0
            throughput_kbs += jsonio.as_float(e.get("throughput")) or 0.0
            requests += jsonio.as_int(e.get("stats", {}).get("total_requests")) or 0
            lat = e.get("latencies") or {}
            # io_tester quantile keys are "p0.5", "p0.99", "p0.999"
            for key, cur in (("p0.5", "p50"), ("p0.99", "p99"), ("p0.999", "p999")):
                v = _pct_value(lat, key)
                if v is not None:
                    if cur == "p50":
                        p50 = v if p50 is None else max(p50, v)
                    elif cur == "p99":
                        p99 = v if p99 is None else max(p99, v)
                    else:
                        p999 = v if p999 is None else max(p999, v)
            vmax = _pct_value(lat, "max")
            if vmax is not None:
                mx = vmax if mx is None else max(mx, vmax)
        case = path.stem if len(jobs) == 1 else f"{path.stem}:{job_name}"
        errors = aio_retries
        if errors is not None and float(errors).is_integer():
            errors = int(errors)
        rows.append(make_row(
            "seastar", case, None, None, None, shard_count, iops,
            throughput_kbs / 1024.0 if throughput_kbs else None, p50, p99, p999, mx,
            requests, "" if errors is None else errors, "", path,
        ))
    return rows


# ---------------------------------------------------------------------------
# Discovery / output
# ---------------------------------------------------------------------------

def parse_file(path: Path):
    suffix = path.suffix.lower()
    try:
        if suffix == ".json":
            with path.open("r", encoding="utf-8", errors="replace") as f:
                data = json.load(f)
            if data.get("engine") in ("juring", "seastar"):
                return parse_juring(path, data)
            if "jobs" in data:
                return parse_fio(path, data)
            return []
        if suffix in (".yaml", ".yml", ".txt"):
            text = path.read_text(encoding="utf-8", errors="replace")
            return parse_seastar(path, text)
    except Exception as e:  # robust: one bad file must not kill the report
        print(f"warning: skipping {path}: {e}", file=sys.stderr)
        return []
    return []


def collect_paths(paths, default_dir: Path):
    files = []
    for p in paths:
        pp = Path(p)
        if pp.is_dir():
            files.extend(sorted(q for q in pp.rglob("*") if q.is_file()))
        elif pp.is_file():
            files.append(pp)
        else:
            print(f"warning: no such path: {p}", file=sys.stderr)
    if not files:
        if default_dir.is_dir():
            files = sorted(q for q in default_dir.rglob("*") if q.is_file())
        else:
            print(f"error: {default_dir} is not a directory and no files given", file=sys.stderr)
            return []
    return files


def fmt(value, width, precision=1):
    """Right-justified table cell; the numeric core is ``md.thousands``."""
    if value is None or value == "":
        return "".rjust(width)
    if isinstance(value, float):
        return md.thousands(value, precision).rjust(width)
    if isinstance(value, int):
        return md.thousands(value, 0).rjust(width)
    return str(value).rjust(width)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="*", help="raw result files or directories")
    ap.add_argument("--dir", default="bench-results",
                    help="results directory scanned recursively when no paths are given "
                         "(default: bench-results)")
    ap.add_argument("--csv", default=None,
                    help="CSV output path (default: <dir>/results.csv if --dir exists, else no CSV)")
    ap.add_argument("--no-table", action="store_true", help="do not print the table")
    ap.add_argument("--engine", choices=sorted(ENGINE_ORDER), action="append",
                    help="only include these engines (repeatable)")
    args = ap.parse_args(argv)

    default_dir = Path(args.dir)
    files = collect_paths(args.paths, default_dir)
    if not files:
        return 1

    rows = []
    skipped = 0
    for f in files:
        parsed = parse_file(f)
        if parsed:
            rows.extend(parsed)
        else:
            skipped += 1
    if args.engine:
        rows = [r for r in rows if r["engine"] in args.engine]
    if not rows:
        print(f"error: no benchmark results found in {len(files)} file(s) "
              f"({skipped} not recognized)", file=sys.stderr)
        return 1

    rows.sort(key=lambda r: (r["case"] or "", ENGINE_ORDER.get(r["engine"], 99), r["engine"]))

    if not args.no_table:
        header = f"{'case':32s} {'engine':8s} {'workload':10s} {'bs':>8s} {'qd':>5s} {'thr':>4s} " \
                 f"{'IOPS':>12s} {'MiB/s':>10s} {'p50us':>9s} {'p99us':>9s} {'p99.9us':>9s} {'maxus':>9s} {'ops':>12s} {'err':>5s}"
        print(header)
        print("-" * len(header))
        for r in rows:
            bs = r["bs"]
            bs_str = ""
            if bs:
                for mult, suf in ((1024 ** 3, "g"), (1024 ** 2, "m"), (1024, "k")):
                    if bs >= mult and bs % mult == 0:
                        bs_str = f"{bs // mult}{suf}"
                        break
                else:
                    bs_str = str(bs)
            print(f"{(r['case'] or '')[:32]:32s} {r['engine']:8s} {(r['workload'] or '')[:10]:10s} "
                  f"{bs_str:>8s} {fmt(r['qd'], 5)} {fmt(r['threads'], 4)} "
                  f"{fmt(r['iops'], 12)} {fmt(r['mibs'], 10)} "
                  f"{fmt(r['p50_us'], 9)} {fmt(r['p99_us'], 9)} {fmt(r['p999_us'], 9)} "
                  f"{fmt(r['max_us'], 9)} {fmt(r['ops'], 12)} {fmt(r['errors'], 5)}")
        print()

    csv_path = args.csv
    if csv_path is None:
        if default_dir.is_dir():
            csv_path = str(default_dir / "results.csv")
        else:
            csv_path = None
    if csv_path:
        out = Path(csv_path)
        if out.parent and str(out.parent) not in ("", "."):
            out.parent.mkdir(parents=True, exist_ok=True)
        with out.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=COLUMNS)
            writer.writeheader()
            for r in rows:
                writer.writerow({k: r.get(k) for k in COLUMNS})
        print(f"wrote {len(rows)} row(s) to {out}", file=sys.stderr)
    print(f"# {len(rows)} row(s) from {len(files) - skipped} recognized file(s), "
          f"{len(files)} scanned", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
