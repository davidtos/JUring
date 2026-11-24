# Host-representativeness and write-state runs (bench/host-setup/)

I wanted proof that the hugepage choice and the memlock limit don't change the read answer, and one more honest attempt at the write path. The numbers I quote are in [`BENCHMARK.md`](../../BENCHMARK.md); the protocol is in [`docs/benchmark-methodology.md`](../../docs/benchmark-methodology.md).

### Identifiers

Read cells use the canonical names from [`bench/analysis/cells.py`](../analysis/cells.py) (for
example `randread-4k-qd64`, `seqread-1m-qd8`). The write chain's protocol phases are the **control
pass**, the two **state-control variants** (A continuous / B brief-idle) and the **gate campaign**;
a full campaign pass is a *pass* (`pass1`..`pass8`), and the analyzers take `--passes` (legacy
`--gens`/`--gen-dir` still accepted). Historical sessions keep their old `W#`/`g#` names and both
analyzers accept them.

`run-host-setup.sh` runs two read blocks. The first compares Seastar on 2 MiB hugepages against THP on 4 KiB random read QD 64/128, 1 MiB sequential read QD 8/32 and 8-shard QD 64, three repetitions. The second is the raised-memlock run of 1 MiB sequential read at QD 1/8/32 for JUring, `fio-matched` and Seastar, five repetitions. `analyze_host_setup.py` writes `analysis/host-setup-summary.md` and `analysis/host-setup.ratios.csv` with paired ratios and 95% CIs.

`run-write-chain.sh` runs the state-controlled chain: the control pass, variants only if needed, then five gate-campaign passes that must each be single-state and homogeneous. `analyze_write_state.py` classifies them with a 10 s-median cliff detector and a `max/min <= 1.25` rule. The read session completed 75 of 75 runs with `status=ok`; the write chain completed 7 passes and 42 of 42 runs with `status=ok`.

Historical session directories in the release archive keep the names recorded when the
run was made (for example the `stepd/` subdirectory of the 2026-11-16 unattended session).

## Run it on the host, as user `david`

```bash
cd /path/to/JUring
sudo sysctl -w vm.nr_hugepages=4608          # 9 GiB pool; the 8-shard hugepage arm needs 8 GiB
sudo nvme smart-log /dev/nvme1n1 > scratch/host-setup-run/smart-before.txt

sudo prlimit --memlock=unlimited:unlimited -- runuser -u david -- env \
  JAVA_BIN=/path/to/jdk-26/bin/java \
  JAVA_HOME=/path/to/jdk-26 \
  bash -lc 'cd /path/to/JUring && bench/host-setup/run-host-setup.sh --block all 2>&1 | tee scratch/host-setup-run/block1.log'

sudo nvme smart-log /dev/nvme1n1 > scratch/host-setup-run/smart-after.txt
python3 bench/host-setup/analyze_host_setup.py --dir bench/results/host-setup \
  | tee scratch/host-setup-run/block1-analysis.md
```

The memlock wrapper is mandatory for Block B. The runner aborts on loadavg(1m) >= 1.6, a wrong JDK or a missing hugepage pool, and warns in `logs/warnings.log` when a hugetlbfs run uses no hugepages.

Dry run: `bench/host-setup/run-host-setup.sh --block all --dry-run`. Outputs: `bench/results/host-setup/{manifest-run*.txt,cell-meta.tsv,cells/,logs/,analysis/}` (gitignored). Cleanup: `sudo bash scratch/host-setup-run/host-setup-preflight.sh --cleanup`.

## Unattended full chain

```bash
cd /path/to/JUring
sudo bash bench/host-setup/run-unattended.sh
```

It runs host prep, both read blocks under the memlock wrapper, the write chain, the analyses and cleanup, then writes `RESULTS-SUMMARY.md` and a `DONE` marker. Outputs land in `bench/results/unattended-<date>/`: `STATUS.txt`, `host-setup/`, `write-state/` with `WRITE-STATE-VERDICT.md` and `analysis/write-state-summary.md`, and `logs/`. Only 2 of the 5 gate-campaign passes were valid, short of the 4 I required, so the write result is inconclusive; if no variant can hold a single-state pass, the chain stops with `UNRESOLVED` instead of inventing a ranking. `--dry-run` prints the plan; `--window-hours H` sets the time guard.
