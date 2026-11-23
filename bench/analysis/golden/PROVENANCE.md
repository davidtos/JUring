# Golden analyzer outputs

These files were captured from the pristine analyzers at git commit `8521f9f` on the author's
host, before the consolidation onto `bench/analysis/`. Each case directory holds the normalized
analyzer exit code, stdout, stderr and output tree for the matching case in
`bench/analysis/selftest.py`. Timestamps become `<TIMESTAMP>`, session roots `<SESSIONS>`, the
repo root `<REPO>` and the scratch work root `<WORK>`; outputs over 100 KB are pinned by SHA-256
in the case's `_large-files.sha256` instead of being committed as text.

The session data those analyzers read (`bench/results/`) is gitignored, so a fresh clone and CI skip every golden case. Run
`python3 bench/analysis/selftest.py --update` on a host that has the sessions to regenerate this
tree from the current analyzers.

The preliminary B4 pair (`bench/shard/run-shard-scaling.sh` + `analyze_shard.py`) and its
`b4-shard` case were removed after the M3 campaign superseded them; the B4 session data and its
aggregate stay in the gitignored `bench/results/b4-2026-11-14/`, and both scripts are recoverable
from git history.

The analyzers and their runner scripts were renamed at the same time (`analyze_m1.py` ->
`analyze_read_write.py`, `analyze_m3.py` -> `analyze_scale_out.py`, `analyze_b5_overhead.py` ->
`analyze_metrics_overhead.py`, `analyze_stepd.py` -> `analyze_host_setup.py`,
`write_state_analysis.py` -> `analyze_write_state.py`, `run-m3-campaign.sh` -> `run-scale-out.sh`,
`run-b5-overhead.sh` -> `run-metrics-overhead.sh`, `run-stepd.sh` -> `run-host-setup.sh`); the
case directories use the campaign names without the milestone codenames. The generated tables
inside a session's `aggregate/` directory keep their historical file names (`m1.*`, `m3.*`,
`overhead.*`, ...) so the release archive and the docs stay valid.
