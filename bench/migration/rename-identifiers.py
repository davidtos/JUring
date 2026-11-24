#!/usr/bin/env python3
"""Idempotent rename of the old W#/g# identifiers under bench/results/.

The raw session data is evidence: this script only renames files and
directories, never rewrites the bytes inside them.  The rules are the contract
in `scratch/rename-glossary.md` (sections 3 and 5):

* a pass directory ``gN`` becomes ``passN``;
* a cell-bearing stem ``W7__...`` becomes ``randwrite-4k-qd64__...`` (any cell
  from `bench/analysis/cells.py`);
* ``precondition_gN*`` / ``precondition-W7-*`` logs follow the same naming;
* the write-state bookkeeping files stop saying "generation";
* every session gets one ``RENAMES.txt`` with the old -> new paths.

Every change is recorded in ``<session>/RENAMES.txt``.  Running the script twice
is a no-op: already-canonical names are left alone and an existing RENAMES.txt is
only extended, never truncated.

Usage::

    python3 bench/migration/rename-identifiers.py --dry-run
    python3 bench/migration/rename-identifiers.py
    python3 bench/migration/rename-identifiers.py --session bench/results/<name>
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "bench"))
from analysis import cells  # noqa: E402

DEFAULT_ROOT = REPO / "bench" / "results"

PASS_DIR_RE = re.compile(r"^(?:.*[-_])?g(\d+)$")
CELL_STEM_RE = re.compile(r"^([Ww][0-9][A-Za-z0-9-]*)__(.*)$")
PRECOND_GEN_RE = re.compile(r"^precondition_g(\d+)(-.+|_chunk.+\.json)$")
CLASSIFY_RE = re.compile(r"^g(\d+)\.classify\.txt$")
PRECOND_CELL_RE = re.compile(r"^precondition-([Ww][0-9][A-Za-z0-9-]*)-(.+)$")
CONCEPT_RENAMES = {
    "write-generations.tsv": "write-passes.tsv",
    "write-generation.counter": "write-pass.counter",
}


def rename_component(name):
    """New name for one path component, or the same name when nothing changes."""
    m = PASS_DIR_RE.match(name)
    if m:
        return "pass%s" % m.group(1)
    m = CELL_STEM_RE.match(name)
    if m:
        canonical = cells.slug(m.group(1).upper())
        if canonical != m.group(1):
            return canonical + "__" + m.group(2)
    m = PRECOND_GEN_RE.match(name)
    if m:
        return "precondition_pass%s%s" % (m.group(1), m.group(2))
    m = CLASSIFY_RE.match(name)
    if m:
        return "pass%s.classify.txt" % m.group(1)
    m = PRECOND_CELL_RE.match(name)
    if m:
        canonical = cells.slug(m.group(1).upper())
        if canonical != m.group(1):
            return "precondition-%s-%s" % (canonical, m.group(2))
    return CONCEPT_RENAMES.get(name, name)


def renamed_path(rel):
    """Final path for one original relative path: transform every component."""
    return Path(*[rename_component(part) for part in rel.parts])


def collect_plan(session):
    """(src, dst, old_rel, new_rel) for every entry whose name must change."""
    plan = []
    for dirpath, dirnames, filenames in os.walk(session):
        dirnames.sort()
        here = Path(dirpath)
        for name in sorted(dirnames + filenames):
            src = here / name
            rel = src.relative_to(session)
            final_rel = renamed_path(rel)
            if final_rel != rel:
                plan.append((src, here / final_rel.name, rel, final_rel))
    # deepest first: children are renamed before their parent directory moves
    plan.sort(key=lambda item: len(item[2].parts), reverse=True)
    return plan


def read_existing(renames_path):
    seen = []
    if not renames_path.is_file():
        return seen
    for line in renames_path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        if " -> " in line:
            seen.append(line)
    return seen


def write_renames(session, lines):
    renames_path = session / "RENAMES.txt"
    if not lines:
        if renames_path.is_file():
            print("  RENAMES.txt already present; no further renames")
        return
    header = [
        "# Identifier renames applied by bench/migration/rename-identifiers.py",
        "# Format: old path -> new path (relative to the repository root)",
        "# Raw JSON/TSV/LOG contents were NOT rewritten; only names changed.",
        "",
    ]
    merged = sorted(set(read_existing(renames_path)) | set(lines))
    renames_path.write_text("\n".join(header + merged) + "\n", encoding="utf-8")
    print("  wrote %s (%d mapping line(s))"
          % (renames_path.relative_to(REPO), len(merged)))


def migrate_session(session, dry_run):
    try:
        shown = session.relative_to(REPO)
    except ValueError:
        shown = session
    print("session %s" % shown)
    moved = 0
    lines = []
    already = set(read_existing(session / "RENAMES.txt"))
    for src, dst, rel, final_rel in collect_plan(session):
        line = "%s -> %s" % (rel.as_posix(), final_rel.as_posix())
        if dst.exists() and not src.samefile(dst):
            print("  SKIP %s -> %s (target already exists)"
                  % (rel.as_posix(), final_rel.as_posix()))
            continue
        if dry_run:
            print("  would rename %s -> %s" % (rel.as_posix(), final_rel.as_posix()))
        else:
            os.rename(src, dst)
            print("  renamed %s -> %s" % (rel.as_posix(), final_rel.as_posix()))
        if line not in already:
            lines.append(line)
            already.add(line)
        moved += 1
    if not dry_run:
        write_renames(session, lines)
    return moved


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Rename legacy W#/g# identifiers in bench/results (names only).")
    ap.add_argument("--root", default=str(DEFAULT_ROOT),
                    help="results root (default: bench/results)")
    ap.add_argument("--session", action="append",
                    help="specific session directory (repeatable; default: all)")
    ap.add_argument("--dry-run", action="store_true",
                    help="print the planned renames without touching anything")
    args = ap.parse_args(argv)

    root = Path(args.root)
    if not root.is_absolute():
        root = REPO / root
    if not root.is_dir():
        ap.error("results root does not exist: %s" % root)
    if args.session:
        sessions = []
        for name in args.session:
            path = Path(name)
            if path.is_dir():
                path = path.resolve()
            else:
                path = root / name
            sessions.append(path)
    else:
        sessions = sorted(p for p in root.iterdir() if p.is_dir())
    total = 0
    for session in sessions:
        if not session.is_dir():
            ap.error("session directory does not exist: %s" % session)
        total += migrate_session(session, args.dry_run)
    verb = "would rename" if args.dry_run else "renamed"
    print("%s %d name(s) across %d session(s)" % (verb, total, len(sessions)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
