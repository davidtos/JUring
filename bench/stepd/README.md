# Host-representativeness and write-state runs (bench/stepd/)

I wanted proof that the hugepage choice and the memlock limit don't change the read answer, and one more honest attempt at the write path. The numbers I quote are in [`BENCHMARK.md`](../../BENCHMARK.md); the protocol is in [`docs/benchmark-methodology.md`](../../docs/benchmark-methodology.md).

`run-stepd.sh` runs two read blocks. The first compares Seastar on 2 MiB hugepages against THP on 4 KiB random read QD 64/128, 1 MiB sequential read QD 8/32 and 8-shard QD 64, three repetitions. The second is the raised-memlock run of 1 MiB sequential read at QD 1/8/32 for JUring, `fio-matched` and Seastar, five repetitions. `analyze_stepd.py` writes `analysis/stepd-summary.md` and `analysis/stepd.ratios.csv` with paired ratios and 95% CIs.

`run-write-chain.sh` runs the state-controlled chain: control generation, variants only if needed, then five generations that must each be single-state and homogeneous. `write_state_analysis.py` classifies them with a 10 s-median cliff detector and a `max/min <= 1.25` rule. The read session completed 75 of 75 runs with `status=ok`; the write chain completed 7 generations and 42 of 42 runs with `status=ok`.

## Run it on the host, as user `david`

```bash
cd /home/david/projects/JUring
sudo sysctl -w vm.nr_hugepages=4608          # 9 GiB pool; the 8-shard hugepage arm needs 8 GiB
sudo nvme smart-log /dev/nvme1n1 > scratch/stepd-host/smart-before.txt

sudo prlimit --memlock=unlimited:unlimited -- runuser -u david -- env \
  JAVA_BIN=/home/david/.jdks/openjdk-26.0.2/bin/java \
  JAVA_HOME=/home/david/.jdks/openjdk-26.0.2 \
  bash -lc 'cd /home/david/projects/JUring && bench/stepd/run-stepd.sh --block all 2>&1 | tee scratch/stepd-host/block1.log'

sudo nvme smart-log /dev/nvme1n1 > scratch/stepd-host/smart-after.txt
python3 bench/stepd/analyze_stepd.py --dir bench/results/stepd-2026-11-16 \
  | tee scratch/stepd-host/block1-analysis.md
```

The memlock wrapper is mandatory for Block B. The runner aborts on loadavg(1m) >= 1.6, a wrong JDK or a missing hugepage pool, and warns in `logs/warnings.log` when a hugetlbfs run uses no hugepages.

Dry run: `bench/stepd/run-stepd.sh --block all --dry-run`. Outputs: `bench/results/stepd-2026-11-16/{manifest-run*.txt,cell-meta.tsv,cells/,logs/,analysis/}` (gitignored). Cleanup: `sudo bash scratch/stepd-host/stepd-preflight.sh --cleanup`.

## Unattended full chain

```bash
cd /home/david/projects/JUring
sudo bash bench/stepd/run-unattended.sh
```

It runs host prep, both read blocks under the memlock wrapper, the write chain, the analyses and cleanup, then writes `RESULTS-SUMMARY.md` and a `DONE` marker. Outputs land in `bench/results/unattended-2026-11-16/`: `STATUS.txt`, `stepd/`, `write-state/` with `WRITE-STATE-VERDICT.md` and `analysis/write-state-summary.md`, and `logs/`. Only 2 of the 5 write generations were valid, short of the 4 I required, so the write result is inconclusive; if no variant can hold a single-state generation, the chain stops with `UNRESOLVED` instead of inventing a ranking. `--dry-run` prints the plan; `--window-hours H` sets the time guard.
