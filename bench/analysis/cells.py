#!/usr/bin/env python3
"""Single source of truth for benchmark cell and pass identifiers.

Cells are the published workloads (``randread-4k-qd64``, ``shards-8``,
``groupcommit-4k-batch32``, ...) and passes are the state-controlled write
protocol's repetitions (``pass1``..``pass8``).  Every analyzer imports this
module and the bash harness loads it through ``bench/cells.sh``, so the names
exist in exactly one place.

Usage as a library::

    from analysis import cells
    cells.slug("randwrite-4k-qd64")   # "randwrite-4k-qd64"
    cells.label("randwrite-4k-qd64")  # "4 KiB random write, 64 in flight"
    cells.pass_name("pass3")          # "pass3"
    cells.pass_label("pass3")         # "pass 3"

Usage as a CLI (used by ``bench/cells.sh`` and by scripts)::

    python3 bench/analysis/cells.py --list
    python3 bench/analysis/cells.py --shell      # bash array declarations
    python3 bench/analysis/cells.py slug randread-4k-qd64
    python3 bench/analysis/cells.py label randread-4k-qd64
    python3 bench/analysis/cells.py for throughput randread 4k 64
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from typing import NamedTuple, Optional


class Cell(NamedTuple):
    """One benchmark cell: canonical workload slug and human label.

    ``kind`` is the measurement family (throughput, latency, overhead, shards,
    stream, durability, groupcommit, phase); ``workload``/``bs``/``qd``/``batch``
    let a runner derive the canonical ID from its own parameters instead of
    repeating a literal map.
    """

    slug: str
    label: str
    kind: str
    workload: Optional[str] = None
    bs: Optional[str] = None
    qd: Optional[int] = None
    batch: Optional[int] = None


CELLS = (
    Cell("randread-4k-qd1", "4 KiB random read, 1 request in flight",
         "throughput", "randread", "4k", 1),
    Cell("randread-4k-qd16", "4 KiB random read, 16 in flight",
         "throughput", "randread", "4k", 16),
    Cell("randread-4k-qd64", "4 KiB random read, 64 in flight",
         "throughput", "randread", "4k", 64),
    Cell("randread-4k-qd128", "4 KiB random read, 128 in flight",
         "throughput", "randread", "4k", 128),
    Cell("randwrite-4k-qd1", "4 KiB random write, 1 request in flight",
         "throughput", "randwrite", "4k", 1),
    Cell("randwrite-4k-qd16", "4 KiB random write, 16 in flight",
         "throughput", "randwrite", "4k", 16),
    Cell("randwrite-4k-qd64", "4 KiB random write, 64 in flight",
         "throughput", "randwrite", "4k", 64),
    Cell("randwrite-4k-qd128", "4 KiB random write, 128 in flight",
         "throughput", "randwrite", "4k", 128),
    Cell("seqread-1m-qd1", "1 MiB sequential read, 1 in flight",
         "throughput", "seqread", "1m", 1),
    Cell("seqread-1m-qd8", "1 MiB sequential read, 8 in flight",
         "throughput", "seqread", "1m", 8),
    Cell("seqread-1m-qd32", "1 MiB sequential read, 32 in flight",
         "throughput", "seqread", "1m", 32),
    Cell("seqwrite-1m-qd1", "1 MiB sequential write, 1 in flight",
         "throughput", "seqwrite", "1m", 1),
    Cell("seqwrite-1m-qd8", "1 MiB sequential write, 8 in flight",
         "throughput", "seqwrite", "1m", 8),
    Cell("seqwrite-1m-qd32", "1 MiB sequential write, 32 in flight",
         "throughput", "seqwrite", "1m", 32),
    Cell("randread-4k-qd1-latency", "4 KiB random-read latency, QD 1",
         "latency", "randread", "4k", 1),
    Cell("randread-4k-qd64-latency", "4 KiB random-read latency, QD 64",
         "latency", "randread", "4k", 64),
    Cell("shards-1", "multi-shard scaling, 1 shard", "shards"),
    Cell("shards-2", "multi-shard scaling, 2 shards", "shards"),
    Cell("shards-4", "multi-shard scaling, 4 shards", "shards"),
    Cell("shards-8", "multi-shard scaling, 8 shards", "shards"),
    Cell("stream-write-128k", "128 KiB stream write",
         "stream", "stream_write", "128k", 8),
    Cell("stream-write-1m", "1 MiB stream write",
         "stream", "stream_write", "1m", 8),
    Cell("stream-read-128k", "128 KiB stream read",
         "stream", "stream_read", "128k", 8),
    Cell("stream-read-1m", "1 MiB stream read",
         "stream", "stream_read", "1m", 8),
    Cell("nop-qd64", "ring round-trip with no storage I/O",
         "overhead", "nop", "4k", 64),
    Cell("durability-write-128k", "128 KiB durable write, QD 8",
         "durability", "stream_write", "128k", 8),
    Cell("groupcommit-4k-batch1", "4 KiB group-commit write, batch 1",
         "groupcommit", "randwrite", "4k", None, 1),
    Cell("groupcommit-4k-batch8", "4 KiB group-commit write, batch 8",
         "groupcommit", "randwrite", "4k", None, 8),
    Cell("groupcommit-4k-batch32", "4 KiB group-commit write, batch 32",
         "groupcommit", "randwrite", "4k", None, 32),
    Cell("groupcommit-4k-batch128", "4 KiB group-commit write, batch 128",
         "groupcommit", "randwrite", "4k", None, 128),
)

# The state-controlled write protocol's phases (the host-setup campaign).  These
# are protocol names, not benchmark cells; the docs call them "the control
# pass", "the state-control variants" and "the gate campaign".
PHASES = (
    Cell("control-pass", "the control pass", "phase"),
    Cell("variant-a", "the state-control variants (A/B)", "phase"),
    Cell("variant-a-continuous", "state-control variant A (continuous)", "phase"),
    Cell("variant-a-briefidle", "state-control variant B (brief idle)", "phase"),
    Cell("gate-campaign", "the gate campaign", "phase"),
)

ALL = CELLS + PHASES

_BY_SLUG = {c.slug.lower(): c for c in ALL}

PASS_RE = re.compile(r"^pass(\d+)$", re.IGNORECASE)
SHARD_RE = re.compile(r"^shards-(\d+)$", re.IGNORECASE)

PASS_COUNT = 8


def lookup(value):
    """Return the :class:`Cell` for a canonical slug, else None."""
    if value is None:
        return None
    token = str(value).strip()
    return _BY_SLUG.get(token.lower())


def slug(value):
    """Canonical slug for a slug; unknown values pass through."""
    cell = lookup(value)
    return cell.slug if cell is not None else str(value).strip()


def label(value):
    """Human label for a slug; unknown values pass through."""
    cell = lookup(value)
    return cell.label if cell is not None else str(value).strip()


def cell_for(kind, workload, bs, qd=None, batch=None):
    """Canonical slug for a runner's parameters, or None.

    ``kind`` separates cells that share workload/bs/qd (e.g. the read-latency
    cells and the throughput cells, or the stream and durability cells).
    """
    qd = None if qd is None else int(qd)
    batch = None if batch is None else int(batch)
    for cell in CELLS:
        if cell.kind != kind or cell.workload != workload:
            continue
        if cell.bs is not None and cell.bs.lower() != str(bs).lower():
            continue
        if cell.qd is not None and cell.qd != qd:
            continue
        if cell.batch is not None and cell.batch != batch:
            continue
        return cell.slug
    return None


def shard_slug(count):
    """Canonical slug for an N-shard cell."""
    return "shards-%d" % int(count)


def shard_count(value):
    """Shard count from a canonical ``shards-<n>`` slug, else None."""
    m = SHARD_RE.match(str(value).strip())
    return int(m.group(1)) if m else None


def pass_number(value):
    """Pass number from a canonical ``passN`` value, else None."""
    m = PASS_RE.match(str(value).strip())
    return int(m.group(1)) if m else None


def pass_name(value):
    """Canonical ``passN`` value; unknown values pass through."""
    n = pass_number(value)
    return "pass%d" % n if n is not None else str(value).strip()


def pass_label(value):
    """Human label ``pass N``; unknown values pass through."""
    n = pass_number(value)
    return "pass %d" % n if n is not None else str(value).strip()


def pass_dir(session, value):
    """Directory for one pass: ``<session>/passN``."""
    return os.path.join(session, pass_name(value))


def _shell_quote(text):
    return "'" + str(text).replace("'", "'\\''") + "'"


def shell_declarations():
    """Bash associative arrays shared by the shell harness (bench/cells.sh).

    The canonical slug is both the key and the value of ``CELL_SLUG_BY_ID`` so
    ``cell_slug`` can validate a known workload while passing unknown values
    through unchanged; ``CELL_BY_WORKLOAD``/``CELL_BY_SHARDS`` let a runner
    derive the slug from its own parameters.
    """
    slug_by_id = {cell.slug: cell.slug for cell in ALL}
    label_by_id = {cell.slug: cell.label for cell in ALL}
    out = ["# generated by bench/analysis/cells.py --shell; do not edit by hand"]
    for name, mapping in (("CELL_SLUG_BY_ID", slug_by_id),
                          ("CELL_LABEL_BY_ID", label_by_id)):
        pairs = " ".join("[%s]=%s" % (_shell_quote(k), _shell_quote(v))
                         for k, v in sorted(mapping.items()))
        out.append("declare -gA %s=(%s)" % (name, pairs))

    by_workload = {}
    for cell in CELLS:
        if cell.kind == "shards":
            continue
        key = "%s|%s|%s|%s|%s" % (cell.kind, cell.workload or "", cell.bs or "",
                                   "" if cell.qd is None else cell.qd,
                                   "" if cell.batch is None else cell.batch)
        by_workload[key] = cell.slug
    pairs = " ".join("[%s]=%s" % (_shell_quote(k), _shell_quote(v))
                     for k, v in sorted(by_workload.items()))
    out.append("declare -gA CELL_BY_WORKLOAD=(%s)" % pairs)
    pairs = " ".join("[%s]=%s" % (_shell_quote(str(n)), _shell_quote("shards-%d" % n))
                     for n in (1, 2, 4, 8))
    out.append("declare -gA CELL_BY_SHARDS=(%s)" % pairs)
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Benchmark cell/pass identifier map.")
    ap.add_argument("--list", action="store_true",
                    help="print canonical slug and human label per cell")
    ap.add_argument("--shell", action="store_true",
                    help="print bash array declarations for bench/cells.sh")
    ap.add_argument("command", nargs="*",
                    help="slug ID | label ID | for KIND WORKLOAD BS [QD] [BATCH] "
                         "| pass ID")
    args = ap.parse_args(argv)
    if args.shell:
        print(shell_declarations())
        return 0
    if args.list:
        for cell in ALL:
            print("%s\t%s" % (cell.slug, cell.label))
        return 0
    if not args.command:
        ap.print_help()
        return 2
    cmd, rest = args.command[0], args.command[1:]
    if cmd == "slug" and rest:
        print(slug(rest[0]))
    elif cmd == "label" and rest:
        print(label(rest[0]))
    elif cmd == "for" and len(rest) >= 3:
        qd = rest[3] if len(rest) > 3 else None
        batch = rest[4] if len(rest) > 4 else None
        found = cell_for(rest[0], rest[1], rest[2], qd, batch)
        if found is None:
            sys.stderr.write("cells.py: no cell for %s\n" % " ".join(rest))
            return 1
        print(found)
    elif cmd == "pass" and rest:
        print(pass_name(rest[0]))
    else:
        ap.error("unknown command: %s" % " ".join(args.command))
    return 0


if __name__ == "__main__":
    sys.exit(main())
