"""Small CLI plumbing shared by the bench analyzers.

Every analyzer resolves its session directory and writes its aggregate the same
way; keeping that here means the campaign scripts only contain policy.
"""

import json
import os
from pathlib import Path

from . import jsonio


def resolve_session(session=None, session_dir=None,
                    results_root=None, default_session=None):
    """Absolute session directory from ``--session`` / ``--results-dir``.

    ``session`` is a directory name under ``results_root`` (default
    ``bench/results``); ``session_dir`` wins when given.  Nothing is
    created or checked -- analyzers report their own missing-session error.
    """
    if session_dir:
        return Path(jsonio.abspath(session_dir))
    name = session or default_session
    if not name:
        raise ValueError("either session or session_dir is required")
    root = Path(results_root) if results_root else jsonio.REPO_ROOT / "bench" / "results"
    return (root / name).resolve()


def ensure_dir(path):
    Path(path).mkdir(parents=True, exist_ok=True)
    return Path(path)


def write_text(path, text, newline=True):
    """Write UTF-8 text, creating parent directories; optional trailing newline."""
    path = Path(path)
    ensure_dir(path.parent)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
        if newline:
            fh.write("\n")
    return path


def write_json(path, obj, indent=2, newline=True, **dump_kwargs):
    """Write ``obj`` as JSON, creating parent directories.

    ``newline`` defaults to True (the stream/shard convention); pass False for
    the writers that historically end the file at the closing brace.
    """
    text = json.dumps(obj, indent=indent, sort_keys=False, **dump_kwargs)
    return write_text(path, text, newline=newline)


def results_root_from_args(args, attribute="results_root"):
    """The ``--results``/``--results-dir`` override, or None."""
    return getattr(args, attribute, None) or None


def repo_relative(path):
    return os.path.relpath(str(path), str(jsonio.REPO_ROOT))
