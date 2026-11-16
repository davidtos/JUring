# bench/stepd/ — Step D host representativeness harness + state-controlled write chain

**Status: EXECUTED 2026-11-16 (`bench/results/unattended-2026-11-16/`, `exit=0`).**
- **Step D deliverable:** [`docs/stepd-report.md`](../../docs/stepd-report.md) (`75/75` runs `status=ok`, frozen [`docs/stepd-design.md`](../../docs/stepd-design.md) v1.0 + Amendment A1 / Path B).
- **Write-state follow-up deliverable:** [`docs/m1-write-report.md` §12](../../docs/m1-write-report.md) (`7` generations = `42/42` runs `status=ok`, frozen [`docs/m1-write-state-design.md`](../../docs/m1-write-state-design.md) v1.0; verdict `INCONCLUSIVE` — `2/5` valid W2 generations `< 4`).
- Host-prep evidence: `scratch/stepd-host/` (audit + preflight outputs, gitignored); independent verification: `scratch/stepd-verify/VERDICT.md` (422 assertions, PASS).

| file | purpose |
|---|---|
| `run-stepd.sh` | campaign runner: **Block A** seastar THP vs hugetlbfs (W3/W4/W10/W11/M3N8, R=3) + **Block B** memlock best-config read cells (W9/W10/W11, juring/fio-matched/seastar, R=5) |
| `analyze_stepd.py` | reads `<results>/cell-meta.tsv`, writes `analysis/stepd-summary.md` + `analysis/stepd.ratios.csv` (paired ratios + 95% CIs; disclosure only) |
| `run-write-chain.sh` | pre-registered state-controlled write chain (`W0 → W1-A/B → W2 × 5`) |
| `write_state_analysis.py` | frozen classifier (`steady` 10 s-median cliff detector + `max/min ≤ 1.25` generation homogeneity + W2 gate analysis) |
| `run-unattended.sh` | one-command unattended root driver (Phase 0 prep → Phase 1 Step D → Phase 2 write chain → Phase 3 cleanup & summary) |

## Run it (host terminal, as user `david`)

```bash
cd /home/david/projects/JUring
sudo sysctl -w vm.nr_hugepages=4608          # 9 GiB pool (M3N8 huge arm needs 8 GiB)
sudo nvme smart-log /dev/nvme1n1 > scratch/stepd-host/smart-before.txt

sudo prlimit --memlock=unlimited:unlimited -- runuser -u david -- env \
  JAVA_BIN=/home/david/.jdks/openjdk-26.0.2/bin/java \
  JAVA_HOME=/home/david/.jdks/openjdk-26.0.2 \
  bash -lc 'cd /home/david/projects/JUring && bench/stepd/run-stepd.sh --block all 2>&1 | tee scratch/stepd-host/block1.log'

sudo nvme smart-log /dev/nvme1n1 > scratch/stepd-host/smart-after.txt
python3 bench/stepd/analyze_stepd.py --dir bench/results/stepd-2026-11-16 \
  | tee scratch/stepd-host/block1-analysis.md
```

The memlock wrapper is mandatory for Block B (the runner aborts with instructions if it
is missing). The runner also aborts on loadavg(1m) ≥ 1.6 (F14), on a missing/wrong JDK, a
missing hugepage pool/mount, or a hugetlbfs run that consumes no hugepages is flagged in
`logs/warnings.log`.

**Dry-run** (no I/O, safe any time): `bench/stepd/run-stepd.sh --block all --dry-run`

Outputs: `bench/results/stepd-2026-11-16/{manifest-run*.txt,cell-meta.tsv,cells/,logs/,analysis/}` (gitignored).
Cleanup after the session: `sudo bash scratch/stepd-host/stepd-preflight.sh --cleanup`.

## Unattended full chain (one command, no interaction)

```bash
cd /home/david/projects/JUring
sudo bash bench/stepd/run-unattended.sh
```

Runs **everything** in one window, self-gated and self-cleaning:
1. host prep (dedicated hugetlbfs + hugepage pool, state snapshot, smart-log before)
2. **Step D** (`docs/stepd-design.md`): seastar THP-vs-hugetlbfs bracket + memlock
   best-config read cells, as user `david` under `prlimit --memlock=unlimited:unlimited`
3. **write-state chain** (`docs/m1-write-state-design.md`): W0 control pilot, then
   pre-registered W1-A/W1-B variants only if needed, then the 5-generation gate campaign
   only if a variant yields valid generations
4. analyses, smart-log after, hugepage pool restored and dedicated mount removed,
   `RESULTS-SUMMARY.md` + `DONE` marker

Artifacts: `bench/results/unattended-<date>/` (gitignored): `STATUS.txt` (phase log),
`stepd/` (Step D raws + `analysis/stepd-summary.md`), `write-state/`
(`WRITE-STATE-VERDICT.md`, `analysis/write-state-summary.md`, generations), `logs/`.
Time guards keep it inside the window; if the drive cannot hold a single-state
generation under any variant, the chain stops early with `UNRESOLVED` rather than
inventing a ranking. `--dry-run` prints the plan; `--window-hours H` sets the guard.
