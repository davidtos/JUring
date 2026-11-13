#!/usr/bin/env python3
"""Turn one `perf stat -x, -p <engine-pid> -e ... -- sleep <window>` capture into
per-op CPU/syscall numbers, using the engine's own IOPS for the op count.

Usage: perf_summary.py <perf.txt> <engine.json> <window_seconds>

Prints one TSV line (no header):
  ops_window iops task_clock_s cyc_u cyc_k instr_u instr_k syscalls ctxt
  cyc_u_per_op cyc_k_per_op cyc_per_op instr_per_op syscalls_per_op ctxt_per_op

`perf stat -p PID` counts all threads of the engine process (verified on this
host: 2 busy pthreads -> task-clock 2x the wall window). The window lies inside
the engine's measured phase, so ops_window is estimated as iops * window (a
<1.5 % approximation; the Lead records the exact attach offsets in the log).
"""
import json
import sys


def engine_iops(path):
    """Total measured IOPS from a JUring/seastar report or a fio JSON job."""
    try:
        with open(path) as f:
            d = json.load(f)
    except Exception:
        return 0.0
    if "jobs" in d:  # fio
        total = 0.0
        for job in d.get("jobs") or []:
            for rw in ("read", "write"):
                b = job.get(rw) or {}
                try:
                    total += float(b.get("iops") or 0.0)
                except (TypeError, ValueError):
                    pass
        return total
    runs = d.get("runs") or []
    if runs:
        iops = runs[0].get("iops")
        if iops is not None:
            return float(iops)
    agg = d.get("aggregate") or {}
    return float((agg.get("iops") or {}).get("mean", 0.0) or 0.0)


def parse_perf(path):
    """Return {event: summed_value} from `perf stat -x,` output."""
    vals = {}
    with open(path, errors="replace") as f:
        for line in f:
            parts = line.strip().split(",")
            if len(parts) < 3:
                continue
            raw, _unit, event = parts[0], parts[1], parts[2]
            if not event:
                continue
            if raw.startswith("<") or raw in ("not supported", "not counted"):
                continue
            try:
                v = float(raw)
            except ValueError:
                continue
            vals[event] = vals.get(event, 0.0) + v
    return vals


def main():
    if len(sys.argv) != 4:
        print("usage: perf_summary.py <perf.txt> <engine.json> <window_s>", file=sys.stderr)
        return 2
    perf_path, json_path, window = sys.argv[1], sys.argv[2], float(sys.argv[3])
    vals = parse_perf(perf_path)
    iops = engine_iops(json_path)
    ops = iops * window
    task_clock_s = vals.get("task-clock", 0.0) / 1000.0  # msec -> s
    cyc_u = vals.get("cycles:u", 0.0)
    cyc_k = vals.get("cycles:k", 0.0)
    instr_u = vals.get("instructions:u", 0.0)
    instr_k = vals.get("instructions:k", 0.0)
    syscalls = vals.get("raw_syscalls:sys_enter", 0.0)
    ctxt = vals.get("context-switches", 0.0)

    def per_op(v):
        return v / ops if ops > 0 else -1.0

    fields = [
        ops, iops, task_clock_s, cyc_u, cyc_k, instr_u, instr_k, syscalls, ctxt,
        per_op(cyc_u), per_op(cyc_k), per_op(cyc_u + cyc_k),
        per_op(instr_u + instr_k), per_op(syscalls), per_op(ctxt),
    ]
    out = []
    for i, v in enumerate(fields):
        out.append(f"{v:.3f}" if i in (1, 2, 9, 10, 11, 12, 13, 14) or i == 0
                   else f"{v:.0f}")
    # ops stays 0-decimal as well; keep it simple and explicit:
    out[0] = f"{ops:.0f}"
    print("\t".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
