"""JSON, CSV and manifest plumbing shared by the bench analyzers.

Tolerance is a feature here: a session directory is evidence, and a single
corrupt cell JSON must be reported as a data-quality row, never crash the
aggregate.  Callers therefore get ``None`` from :func:`load_json` instead of an
exception, and are expected to count the failures.
"""

import csv
import json
import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def load_json(path):
    """Parsed JSON, or None when the file is missing/unreadable/invalid."""
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def load_csv_rows(path, delimiter="\t"):
    """Dict rows of a delimited file (empty list when unreadable)."""
    try:
        with open(path, newline="", encoding="utf-8") as fh:
            return list(csv.DictReader(fh, delimiter=delimiter))
    except OSError:
        return []


def as_float(value):
    """float(value) or None; booleans and non-numeric strings are absent."""
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def as_int(value):
    v = as_float(value)
    return int(v) if v is not None else None


def cell_files(directory):
    """Sorted ``*.json`` files directly under ``<directory>/cells``."""
    cells = Path(directory) / "cells"
    if not cells.is_dir():
        return []
    return sorted(p for p in cells.glob("*.json") if p.is_file())


def cell_stem(path):
    return Path(path).stem


def split_stem(stem):
    """``<cell>__<engine>__<variant>__r<rep>`` -> list of its parts."""
    return stem.split("__")


def rep_index(stem):
    """The integer in the trailing ``r<N>`` part, or None."""
    parts = stem.split("__")
    try:
        return int(parts[-1].lstrip("r"))
    except (ValueError, IndexError):
        return None


def read_manifest(path):
    """``key: value`` lines of a session manifest, first occurrence wins.

    The bench manifests are markdown-ish: ``## section`` headings and free text
    are skipped.  Returns ``{}`` when the file is unreadable.
    """
    out = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if line.startswith("#") or ":" not in line:
                    continue
                key, _, value = line.partition(":")
                key, value = key.strip(), value.strip()
                if key and key not in out:
                    out[key] = value
    except OSError:
        return {}
    return out


def rel(path, root=REPO_ROOT):
    """Repo-relative path for display, falling back to the absolute path."""
    try:
        return str(Path(path).resolve().relative_to(root))
    except ValueError:
        return str(path)


def abspath(path):
    return os.path.abspath(os.path.expanduser(str(path)))
