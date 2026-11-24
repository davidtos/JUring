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

### Identifier and design-code note (2026-11-24)

The analyzers were renamed to the canonical cell names from `bench/analysis/cells.py` (W# -> workload
slug) and to *passes* instead of generations (`g1` -> `pass1`, flag `--gens` -> `--passes`). The
golden files below therefore still carry the historical W#/g# values and old "generation" wording:
they are captured output for sessions that are not present on this host, so `--update` (and the
local `--golden` run) skips those cases rather than fabricating them. Both old and new spellings are
accepted by the analyzers, so the historical sessions can still be regenerated on a host that has
them; run `--update` there (expect the identifier strings and the wording described below to
change) to turn the goldens back into a byte-exact regression target.

The same day, the human-readable analyzer output stopped using the retired internal design codes
(the old `B*`/`M*`/`D*`/`F*`/`A*.x` shorthand and the section references into deleted design docs)
in favour of descriptive labels, e.g. "Read/write aggregate", "1 s device I/O series", "the
variance rule (CV > 10 % = unstable, no ranking)" and "the background-load gate". The committed
read-matrix and write-matrix text goldens were edited to the identical new wording (numbers,
layout and line structure unchanged). The `_large-files.sha256` pins for those cases could not
be recomputed here because the session data is absent: they are already stale from the identifier
rename and the design-ID sweep (the pinned `_stdout.txt`, `summary.md` and `aggregate.json` carry
cell/pass identifiers, the old wording, and the removed design-identity manifest fields), so a
`--golden` run can only be green on a host that still has the historical sessions, after
`python3 bench/analysis/selftest.py --update`.

The multi-shard scaling campaign no longer records a design-document identity: the frozen protocol
doc it used to hash was removed from the public tree, so that check could never pass. The campaign
identity is now the frozen source/test content SHA-1 pair only; the scale-out golden's `m3.json`
SHA-256 pin is stale for the same reason and is refreshed by `--update`.

The preliminary shard-scaling pair (`bench/shard/run-shard-scaling.sh` + `analyze_shard.py`) and its
historical `b4-shard` case were removed after the multi-shard scaling campaign superseded them; that
session's data and its aggregate stay in the gitignored `bench/results/b4-2026-11-14/` (a historical
directory name), and both scripts are recoverable from git history.

The analyzers and their runner scripts were renamed at the same time (`analyze_m1.py` ->
`analyze_read_write.py`, `analyze_m3.py` -> `analyze_scale_out.py`, `analyze_b5_overhead.py` ->
`analyze_metrics_overhead.py`, `analyze_stepd.py` -> `analyze_host_setup.py`,
`write_state_analysis.py` -> `analyze_write_state.py`, `run-m3-campaign.sh` -> `run-scale-out.sh`,
`run-b5-overhead.sh` -> `run-metrics-overhead.sh`, `run-stepd.sh` -> `run-host-setup.sh`, and the host-setup analyzer output files are now
`analysis/host-setup-summary.md` / `analysis/host-setup.ratios.csv`); the
case directories use the campaign names without the old campaign codenames. The generated tables
inside a session's `aggregate/` directory keep their historical file names (`m1.*`, `m3.*`,
`overhead.*`, ...) so the release archive and the docs stay valid.
