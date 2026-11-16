# JUring Benchmarks

Raw campaign artifacts are not committed (they are host-specific and large); the release data archive and reproduction/citation caveats are documented in [DATA-AVAILABILITY.md](DATA-AVAILABILITY.md).

This document is organized in two parts:

1. **Part I — Reproducible L0 / L1 / L2 Benchmark Programme (`bench/`, vs `fio` and Seastar):** The authoritative, pre-registered benchmark campaigns with recorded manifests, raw per-rep JSON artifacts, 95 % confidence intervals, device-state gating, CPU/syscall profiling, and one-command reproduction scripts.
2. **Part II — Supplementary JMH Microbenchmarks (`FileChannel`, `JUringBlocking` Virtual Threads, and Panama FFM Downcalls):** Historical JMH microbenchmarks comparing asynchronous `io_uring` against synchronous `FileChannel`, virtual-thread carrier pools, and FFM `MethodHandle` invocation strategies, accompanied by methodology notes.

---

## Part I — Reproducible L0 / L1 / L2 Benchmark Programme (`bench/`)

### 1. Hardware, Software & Methodology Contract

All Part I campaigns follow [`docs/benchmark-methodology.md`](docs/benchmark-methodology.md) and are stored with full per-run manifests and raw outputs under `bench/results/`:

- **CPU:** AMD Ryzen 9 3950X (16 cores / 32 threads, 1 NUMA node, SMT pairs `c` and `c+16`), governor `performance`, EPP `performance`, boost active (`1`).
- **Memory:** 62 GiB DDR4; THP active mode `madvise` (seastar requests THP via `MADV_HUGEPAGE`); dedicated 2 MiB `hugetlbfs` pool (`4,608 × 2 MiB = 9 GiB` at `/mnt/hugepages-juring`) tested in Step D.
- **Storage:** GIGABYTE GP-ASM2NE6100TTTD 1 TB PCIe 4.0 NVMe (`/dev/nvme1n1p1`, `ext4 rw,nosuid,nodev,relatime,seclabel`, no `discard`, `percentage_used = 3%`, `media_errors = 0`, SMART thermal throttle transitions `T1 = 0, T2 = 0`).
- **OS & Toolchain:** Fedora Linux 44, kernel `7.0.12-201.fc44.x86_64`, OpenJDK `26.0.2` (`2026-07-21`), `fio 3.40`, `perf 7.2.11`, GCC `16.1.1`, Seastar `25.05.0-1392-g04b1c04d` (`04b1c04d`, `--mode=release`).
- **Datasets:**
  - **Read (`DS-S`):** `/mnt/TestData/read_files/single_file_bench.bin` (10 GiB = `10,737,418,240 B`, 72 extents, aged `mtime 2026-11-03`, opened read-only with `O_DIRECT`; never modified).
  - **Write:** `nvme-data/write_files/single_file_bench_w.bin` (dedicated 10 GiB preallocated file on the same NVMe partition).
- **Statistics (`±` convention):** Every cell runs one fresh process/JVM per repetition (`R=5` for primary throughput/latency/stream/scaling cells; `R=3` for brackets and `perf` cells), with engines interleaved inside each rep block to cancel drift. In Part I, **`±` always denotes the two-sided 95 % Student-$t$ confidence interval of the mean (`df = n - 1`)**; ratio intervals are two-sided 90 % / 95 % (the two-sided 90 % lower bound equals the one-sided 95 % lower bound, `1s95 LB`). No repetition is ever silently dropped or trimmed.

| Report | Scope | Status / Verdict |
|---|---|---|
| [`docs/internal/m1-report.md`](docs/internal/m1-report.md) | **M1 (L0 read path)** — single-shard 4 KiB randread (`W1–W4`), 1 MiB seqread (`W9–W11`), tail latency (`W15/W16`), `perf` CPU/syscalls, `fio` brackets | **PASS (signed)** |
| [`docs/internal/m1-write-report.md`](docs/internal/m1-write-report.md) | **M1 (L0 write path)** — Step A full write matrix (`W5–W8`, `W12–W14`, 130 runs) + Session 12 state-controlled write protocol (`W0/W1-A/W2`, 42 runs) | **`INCONCLUSIVE` — no ranking / no write-parity claim** (device write-state cliffs) |
| [`docs/internal/m2-report.md`](docs/internal/m2-report.md) | **M2 (L1 buffered streams)** — `JuringInputStream` / `JuringOutputStream` vs Seastar `fstream` (`W21/W22`, 60 runs) | **PASS (signed)** |
| [`docs/internal/b6-report.md`](docs/internal/b6-report.md) | **B6 (L1 durability & group commit)** — `FadviseAdvice`, `DurabilityMode` (`W24`), `GroupCommit` batching proof (`W25`) | **DELIVERED & VERIFIED** (`fsyncs_per_group == 1.000000`) |
| [`docs/internal/m3-report.md`](docs/internal/m3-report.md) | **M3 (L2 multi-shard scaling & metrics)** — `IoShard` / `RingGroup` (`W17–W20`, `N=1,2,4,8`, 60 runs) + B5 per-ring metrics (`docs/internal/b5-report.md`) | **PASS (signed)** |
| [`docs/internal/stepd-report.md`](docs/internal/stepd-report.md) | **Step D (host representativeness)** — Seastar `hugetlbfs` vs THP bracket (Block A), `unlimited` memlock 1 MiB seqread (Block B), root `nvme smart-log` & `drop_caches` | **COMPLETE** (75/75 `ok`; no signed conclusion changes) |
| [`docs/internal/b7-decision.md`](docs/internal/b7-decision.md) / [`docs/m4-decision.md`](docs/m4-decision.md) | **B7** (provided buffers + multishot) & **B8/M4** (I/O QoS scheduler) | **B7 DEFERRED** by measured gate; **M4 CLOSED** by documented scope decision |

---

### 2. L0 — Single-Shard `io_uring` Read Parity (`M1 PASS`, [`docs/internal/m1-report.md`](docs/internal/m1-report.md))

#### 2.1 Throughput (`W1–W4` 4 KiB randread, `W9–W11` 1 MiB seqread, `R=5`, 10 s warmup + 30 s measured, CPU 2)

- **JUring (`std`):** `IORING_SETUP_SINGLE_ISSUER | IORING_SETUP_COOP_TASKRUN | IORING_SETUP_DEFER_TASKRUN`, registered file (`IOSQE_FIXED_FILE`), pre-registered fixed buffers (`prepareReadFixed`), fused `submitAndWait`.
- **`fio-io_uring` (`matched`):** `--ioengine=io_uring --direct=1 --registerfiles=1 --fixedbufs=1`.
- **Seastar (`std`):** `seastar_file_bench --reactor-backend=io_uring --smp 1 --cpuset 2 --memory 1G --lock-memory 0` (stock 200-entry ring, no `SINGLE_ISSUER`/`DEFER_TASKRUN`, no registered files/fixed buffers; reported as an architectural bracket).

| Case | Workload | QD | Engine / Variant | `n` | IOPS Mean ± 95% CI | MiB/s | `p50` (µs) | `p99` (µs) | CV% | Paired Ratio vs `fio` [95% CI] | Paired Ratio vs Seastar [95% CI] |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **W1** | 4K randread | 1 | `juring/std` | 5 | 10,969 ± 557 | 42.8 | 83.7 | 137.0 | 4.09 | 1.0234 [0.9248, 1.1220] | 0.9491 [0.9002, 0.9979] |
| **W1** | 4K randread | 1 | `fio-io_uring/matched` | 5 | 10,748 ± 664 | 42.0 | 81.2 | 156.7 | 4.97 | — | — |
| **W1** | 4K randread | 1 | `seastar/std` | 5 | 11,558 ± 31 | 45.1 | 81.9 | 98.9 | 0.21 | — | — |
| **W2** | 4K randread | 16 | `juring/std` | 5 | 144,235 ± 4,248 | 563.4 | 100.9 | 222.8 | 2.37 | 1.0108 [0.9556, 1.0660] | 0.9908 [0.9612, 1.0204] |
| **W2** | 4K randread | 16 | `fio-io_uring/matched` | 5 | 142,818 ± 4,994 | 557.9 | 98.6 | 225.1 | 2.82 | — | — |
| **W2** | 4K randread | 16 | `seastar/std` | 5 | 145,575 ± 262 | 568.7 | 101.2 | 214.2 | 0.14 | — | — |
| **W3** | 4K randread | 64 | `juring/std` | 5 | **327,371 ± 280** | 1,278.8 | 169.6 | 481.9 | 0.07 | **1.0771 [1.0477, 1.1064]** | **1.0284 [1.0276, 1.0292]** |
| **W3** | 4K randread | 64 | `fio-io_uring/matched` | 5 | 304,064 ± 8,038 | 1,187.8 | 182.9 | 476.8 | 2.13 | — | — |
| **W3** | 4K randread | 64 | `seastar/std` | 5 | 318,317 ± 437 | 1,243.4 | 176.8 | 471.4 | 0.11 | — | — |
| **W4** | 4K randread | 128 | `juring/std` | 5 | **411,136 ± 19,180**¹ | 1,606.0 | 262.6 | 916.9 | 3.76 | **1.2388 [1.1669, 1.3108]**¹ | **1.0543 [1.0138, 1.0947]** |
| **W4** | 4K randread | 128 | `fio-io_uring/matched` | 5 | 332,133 ± 13,601¹ | 1,297.4 | 350.2 | 741.4 | 3.30 | — | — |
| **W4** | 4K randread | 128 | `seastar/std` | 5 | 389,967 ± 9,419 | 1,523.3 | 290.8 | 794.2 | 1.95 | — | — |
| **W9** | 1M seqread | 1 | `juring/std` | 5 | 1,729 ± 54 | 1,728.8 | 546.6 | 888.7 | 2.53 | 1.0046 [0.9181, 1.0911] | 0.9916 [0.9049, 1.0783] |
| **W9** | 1M seqread | 1 | `fio-io_uring/matched` | 5 | 1,725 ± 108 | 1,725.5 | 504.2 | 1,044.5 | 5.03 | — | — |
| **W9** | 1M seqread | 1 | `seastar/std` | 5 | 1,748 ± 105 | 1,748.0 | 546.2 | 882.0 | 4.83 | — | — |
| **W10** | 1M seqread (8M memlock) | 8 | `juring/std` (`arena`) | 5 | 4,656 ± 167 | 4,656.1 | 1,678.3 | 3,568.1 | 2.89 | 0.9788 [0.9428, 1.0147] | 0.9776 [0.9440, 1.0112] |
| **W10** | 1M seqread (8M memlock) | 8 | `fio-io_uring/regfile` | 5 | 4,757 ± 17 | 4,757.2 | 1,577.8 | 2,388.8 | 0.29 | — | — |
| **W10** | 1M seqread (8M memlock) | 8 | `seastar/std` | 5 | 4,763 ± 14 | 4,762.6 | 1,680.8 | 2,406.7 | 0.23 | — | — |
| **W11** | 1M seqread (8M memlock) | 32 | `juring/std` (`arena`) | 5 | 4,720 ± 123 | 4,720.5 | 6,723.2 | 10,189.0 | 2.10 | 1.0161 [0.9891, 1.0431] | 1.0202 [0.9807, 1.0597] |
| **W11** | 1M seqread (8M memlock) | 32 | `fio-io_uring/regfile` | 5 | 4,647 ± 151 | 4,646.9 | 6,586.4 | 14,155.8 | 2.61 | — | — |
| **W11** | 1M seqread (8M memlock) | 32 | `seastar/std` | 5 | 4,632 ± 261 | 4,631.9 | 6,694.2 | 14,553.6 | 4.53 | — | — |

¹ In W4, JUring (`r5`: `386,629`) and `fio-matched` (`r3`: `314,883`) each had one slow-state rep flagged by the `< 0.92 × max` rule; fast-subset means (`n=4`) are `417,263 ± 13,110` vs `336,445 ± 9,547` IOPS, and the paired fast-subset ratio (`n=3`) is **`1.2542 [1.2139, 1.2946]`** (`PASS`).

#### 2.2 CPU, Instructions & Syscalls per I/O (`perf stat`, 30 s steady-state window, `R=3`)

| Case | QD | Engine / Variant | `n` | Cycles / op (`u+k`) ± 95% CI | Instructions / op | Syscalls / op | Context Switches / op |
|---|---|---|---|---|---|---|---|
| **W1** | 1 | `juring/std` | 3 | 27,254 ± 188 | 16,381 | 0.991 | 0.9910 |
| **W1** | 1 | `fio-io_uring/perf-matched` | 3 | 32,424 ± 229 | 19,781 | 2.009 | 0.9983 |
| **W1** | 1 | `seastar/std` (busy-poll reactor) | 3 | 383,329 ± 170 | 618,289 | 1.350 | 0.0010 |
| **W3** | 64 | `juring/std` | 3 | **11,335 ± 121** | **7,542** | **0.330** | 0.0437 |
| **W3** | 64 | `fio-io_uring/perf-matched` | 3 | 15,227 ± 604 | 10,372 | 1.012 | 0.0010 |
| **W3** | 64 | `seastar/std` | 3 | 13,859 ± 158 | 9,583 | 0.186 | 0.0000 |

At saturation (`W3` QD64), JUring uses **25.6 % fewer CPU cycles/op than `fio-matched`** (`ratio = 0.7445 [0.7225, 0.7665]`, non-inferiority gate `≤ 1.15×` **PASS**).

#### 2.3 Tail Latency (`W15` QD1 / `W16` QD64, 4 KiB randread, 10 s warmup + 120 s measured, `R=5`)

JUring and `seastar_file_bench` timestamp before submission to completion (uniform Algorithm R reservoirs); `fio` reports `clat`.

| Case | QD | Engine | `n` | IOPS Mean ± 95% CI | `p50` (µs) | `p95` (µs) | `p99` (µs) | `p999` (µs) | `max` (µs) | Paired `JUring/fio` `p99` Ratio [95% CI] |
|---|---|---|---|---|---|---|---|---|---|---|
| **W15** | 1 | `juring/std` | 5 | 11,042 ± 125 | 83.6 | 99.2 | 107.8 | 630.9 | 7,084.1 | 1.0931 [0.9349, 1.2513] (`≤ 2×` gate **PASS**) |
| **W15** | 1 | `fio-io_uring/matched` | 5 | 11,133 ± 19 | 81.4 | 96.8 | 98.6 | 590.6 | 4,250.3 | — |
| **W15** | 1 | `seastar/std` | 5 | 11,533 ± 77 | 81.9 | 97.6 | 98.9 | 140.5 | 4,681.5 | — |
| **W16** | 64 | `juring/std` | 5 | 327,488 ± 2,141 | 169.5 | 367.1 | **481.5** | **660.5** | 5,014.5 | **1.0064 [0.9670, 1.0458]** (`≤ 1.10×` parity **PASS**) |
| **W16** | 64 | `fio-io_uring/matched` | 5 | 303,410 ± 3,322 | 182.9 | 361.7 | 478.8 | 985.5 | 8,022.7 | — |
| **W16** | 64 | `seastar/std` | 5 | 308,547 ± 11,877 | 177.2 | 371.8 | 504.9 | 1,881.2 | 16,016.8 | — |

#### 2.4 `fio` Configuration Bracket (`W3` QD64 & `W4` QD128; bracket variants `R=3`, `matched` reference `n=5` from the main matrix)

| Case | QD | `fio` Configuration | `n` | IOPS Mean ± 95% CI | `p50` (µs) | `p99` (µs) | CV% |
|---|---|---|---|---|---|---|---|
| **W3** | 64 | `fio-io_uring/matched` (`--registerfiles=1 --fixedbufs=1`) | 5 | 304,064 ± 8,038 | 182.9 | 476.8 | 2.1 |
| **W3** | 64 | `fio-io_uring/batch32` (`--iodepth_batch_submit=32`) | 3 | 296,990 ± 327 | 189.4 | 458.1 | 0.0 |
| **W3** | 64 | `fio-io_uring/default` (unregistered, `batch_submit=1`) | 3 | 290,440 ± 20,620 | 192.2 | 478.5 | 2.9 |
| **W3** | 64 | `fio-io_uring/batch16` (`--iodepth_batch_submit=16`) | 3 | 288,413 ± 17,422 | 191.5 | 500.4 | 2.4 |
| **W3** | 64 | `fio-io_uring/batch8` (`--iodepth_batch_submit=8`) | 3 | 282,847 ± 34,161 | 192.9 | 527.0 | 4.9 |
| **W3** | 64 | `fio-libaio/std` | 3 | 277,559 ± 4,704 | 207.9 | 448.5 | 0.7 |
| **W3** | 64 | `fio-io_uring/sqpoll` (`--sqthread_poll=1`, negative bracket) | 3 | 10,766 ± 30 | 5,996.5 | 8,978.4 | 0.1 |
| **W4** | 128 | `fio-io_uring/matched` (`--registerfiles=1 --fixedbufs=1`) | 5 | 332,133 ± 13,601 | 350.2 | 741.4 | 3.3 |
| **W4** | 128 | `fio-io_uring/default` | 3 | 296,536 ± 21,157 | 403.5 | 708.6 | 2.9 |
| **W4** | 128 | `fio-libaio/std` | 3 | 271,047 ± 6,121 | 441.7 | 741.4 | 0.9 |

---

### 3. L0 — Write Matrix & State-Controlled Protocol (`INCONCLUSIVE`, [`docs/internal/m1-write-report.md`](docs/internal/m1-write-report.md))

**No write-parity ranking is claimed for v1.** Two pre-registered campaigns were executed to test write parity on `nvme1n1`:

1. **Step A full write matrix ([`docs/internal/m1-write-report.md` §1–§11](docs/internal/m1-write-report.md), 130 completed runs, 8 h 47 m):** Measured `W5`–`W8` (4 KiB randwrite QD1/16/64/128) and `W12`–`W14` (1 MiB seqwrite QD1/8/32) with per-cell D1 preconditioning (8 × 4 GiB chunks = 34.36 GB + 600 s rest), 300 s inter-rep rests, and 1 s device IOPS series. Result: **36 of 130 runs experienced an in-run SLC-burst → steady-state write cliff** (within-run burst/steady ratios `0.14×` to `3.85×`, e.g. `195k → 59k` IOPS), leaving **21 of 34 cells unstable (`17` with `CV > 10 %`)** under methodology rule D7 ("unstable, no ranking"). At W7 (4 KiB QD64, `R=5`), JUring was stable (`188,704 ± 1,983` IOPS, `CV = 0.8 %`), whereas `fio/matched` (`158,570 ± 50,427`, `CV = 25.6 %`), `fio/matchedbatch` (`170,543 ± 42,826`, `CV = 20.2 %`), and Seastar (`167,073 ± 43,672`, `CV = 21.1 %`) randomly sampled post-cliff states across reps.
2. **Session 12 pre-registered state-controlled generation campaign ([`docs/internal/m1-write-report.md` §12](docs/internal/m1-write-report.md), 7 generations = 42 runs, 2 h 22 m):** Tested whether running `juring/std`, `fio/matched`, and `fio/matchedbatch` back-to-back inside a single preconditioned generation yields single-state, homogeneous comparisons (`max/min ≤ 1.25` per cell):
   - Pilot `w0-control` (600 s post-preconditioning rest, 0 s inter-run rest) **failed** (`W7 max/min = 2.0921`: `191,497 / 167,671 / 91,534` IOPS), proving that a 600 s rest partially rebuilds SLC burst capacity that exhausts mid-generation.
   - Pilot `w1a-continuous` (0 s post-preconditioning rest, 0 s inter-run rest) **passed** (`W7`: `188,070 / 197,148 / 196,440` IOPS, `max/min = 1.0483`; `W8`: `185,943 / 196,802 / 196,719` IOPS, `max/min = 1.0584`).
   - In the 5-generation `W2` gate campaign (`w2-g1`..`w2-g5` with `continuous`), **2 generations (`w2-g1`, `w2-g3`) were valid** and **3 (`w2-g2`, `w2-g4`, `w2-g5`) failed homogeneity** because one or more `fio` arms entered a deep GC state (`39.7k–84.8k` IOPS) during their 120 s warmup. With `n_valid = 2 < 4`, the frozen gate verdict is **`INCONCLUSIVE`**. On the 2 valid W2 generations, paired `JUring / fio` ratios are `0.9459 [0.9316, 0.9602]` (`W7 matched`), `0.9531 [0.9369, 0.9693]` (`W7 matchedbatch`), `0.9533 [0.9244, 0.9821]` (`W8 matched`), and `0.9835 [0.6333, 1.3336]` (`W8 matchedbatch`).

---

### 4. L1 — Buffered Streams & Durability (`M2 PASS` + `B6`, [`docs/internal/m2-report.md`](docs/internal/m2-report.md), [`docs/internal/b6-report.md`](docs/internal/b6-report.md))

#### 4.1 `JuringInputStream` / `JuringOutputStream` vs Seastar `fstream` (`M2`, `R=5`, 10 s warmup + 60 s measured, 300 s rests)

| Cell | Workload | Buffer Size | Prefetch / Write-Behind | JUring MiB/s (± 95% CI) | Seastar `fstream` MiB/s (± 95% CI) | Point Ratio | Paired Ratio [90% / 95% CI] | Verdict (Bar `≥ 0.85`) |
|---|---|---|---|---|---|---|---|---|
| **W22-128k** | Seq stream read | 128 KiB | `readAhead = 8` | 2,284.6 ± 76.2 | 2,327.5 ± 72.3 | **0.9816** | 0.9816 [90% 0.966..0.998; 95% 0.961..1.003] | **PASS** (strict LB `0.966`, ±5% parity) |
| **W22-1M** | Seq stream read | 1 MiB | `readAhead = 4` | 4,662.8 ± 287.6 | 4,724.3 ± 131.3 | **0.9865** | 0.9865 [90% 0.960..1.013; 95% 0.952..1.021] | **PASS** (strict LB `0.960`, ±5% parity) |
| **W21-128k** | Seq stream write | 128 KiB | `writeBehind = 8` | 610.0 ± 402.8 | 438.6 ± 359.8 | 1.3908 | Position-matched: `1.232×` fast / `0.984×` slow (pooled `1.250×`) | **PASS** (point estimate; drive state-confounded¹) |
| **W21-1M** | Seq stream write | 1 MiB | `writeBehind = 4` | 569.5 ± 386.7 | 355.7 ± 300.1 | 1.6011 | Position-matched: `1.269×` fast / `1.384×` slow (pooled `1.142×`) | **PASS** (point estimate; drive state-confounded¹) |

¹ **M2 W21 disclosures:** (a) On writes, the first engine after a 300 s rest ran in the drive's fast state (`~600–850 MiB/s`) and the second in the slow state (`~107–572 MiB/s`), so position-matched strata are reported rather than paired-rep speedups; (b) in M2 W21, Seastar ran `durable=true` (2 `fdatasync`/pass at `flush()` + `close()`) while JUring ran `syncOnClose=false` (0/pass). B6 ([`docs/internal/b6-report.md` §6–§7](docs/internal/b6-report.md)) added explicit `DurabilityMode` (`WRITEBACK`, `FDATASYNC`, `FSYNC`, `DSYNC`) and verified matched durability accounting (`MiB/sync ≈ 3.99` for both engines in `W24`).

#### 4.2 `GroupCommit` Batching Proof (`W25`, [`docs/internal/b6-report.md` §8](docs/internal/b6-report.md))

4 KiB `O_DIRECT` writes with `DurabilityMode.FDATASYNC`, `2,048` committed writes per run (`8 MiB`), `R=3`, followed by a fresh-process reopen and byte-by-byte verification:

| Batch Size (`N`) | Groups | Committed Writes | Explicit `fdatasync`s | `fsyncs_per_group` | Throughput (MiB/s ± 95% CI) | Writes/s | Fresh-Process Restart Check |
|---|---|---|---|---|---|---|---|
| **1** | 2,048 | 2,048 | 2,048 | **`1.000000`** | 5.9 ± 0.1 | 1,522 | **PASS** |
| **8** | 256 | 2,048 | 256 | **`1.000000`** | 47.0 ± 0.5 | 12,035 | **PASS** |
| **32** | 64 | 2,048 | 64 | **`1.000000`** | 184.5 ± 3.1 | 47,232 | **PASS** |
| **128** | 16 | 2,048 | 16 | **`1.000000`** | **720.7 ± 12.1** | **184,497** | **PASS** |

---

### 5. L2 — Multi-Shard Scaling & Observability (`M3 PASS` + `B5`, [`docs/internal/m3-report.md`](docs/internal/m3-report.md), [`docs/internal/b5-report.md`](docs/internal/b5-report.md))

#### 5.1 `W17–W20` Multi-Shard Scaling (`N ∈ {1, 2, 4, 8}`, 4 KiB randread, `QD=64`/shard, `R=5`, 10 s warmup + 30 s measured, 300 s rests)

Placement: `N=1..4` on CPUs `1..N`; `N=8` confined to the 8 data-NVMe `blk-mq` hardware-queue CPUs `1,2,4,6,8,10,12,14` (`mq/0..7`).

| `N` (Cell) | `juring-group` IOPS (1s95 CI) | `fio numjobs=N` IOPS (1s95 CI) | `seastar --smp N` IOPS (1s95 CI) | Paired `JUring / fio` Mean (1s95 LB) | Paired `Seastar / JUring` Mean (1s95 LB) | `juring-group` Per-Shard `min/mean` (1s95 LB) |
|---|---|---|---|---|---|---|
| **1 (W17)** | 320,760 `[313,221, 328,300]` | 323,284 `[317,252, 329,315]` | 317,330 `[304,399, 330,260]` | 0.992 (`LB 0.959 ≥ 0.85`) | 0.990 (`LB 0.936`) | — |
| **2 (W18)** | 391,855 `[351,094, 432,617]` | 403,669 `[388,783, 418,556]` | 427,332 `[426,113, 428,551]` | 0.973 (`LB 0.859 ≥ 0.85`) | 1.102 (`LB 0.976`) | — |
| **4 (W19)** | 465,936 `[455,790, 476,083]` | 463,263 `[446,410, 480,117]` | 462,364 `[446,966, 477,762]` | 1.007 (`LB 0.961 ≥ 0.85`) | 0.993 (`LB 0.948`) | — |
| **8 (W20)** | **473,126 `[464,598, 481,654]`** | 459,125 `[441,418, 476,832]` | 466,460 `[458,249, 474,672]` | **1.032 (`LB 0.987 ≥ 0.85`)** | **0.986 (`LB 0.959 ≥ 0.90`)** | **0.963 (`LB 0.947 ≥ 0.85`)** |

Seastar/JUring ratios are paired per-rep means (the signed M3 bar (d) is defined at `N=8` only).

All five frozen M3 bars (`(a)` throughput vs `fio ≥ 0.85`, `(b)` same-`N` scaling efficiency & `p99` reporting, `(c)` `N=8` per-shard `min/mean ≥ 0.85`, `(d)` `N=8` `Seastar/JUring ≥ 0.90`, and `(e)` B5 per-ring queue depth `62–65` + runtime completion `p99` with `0` failed/cancelled/timedOut ops across all 75 ring rows) **PASS**.

#### 5.2 B5 Observability Overhead A/B ([`docs/internal/b5-report.md` §6](docs/internal/b5-report.md))

Paired A/B comparison (`R=5`, 4 KiB randread QD64) of B5 (`RingMetrics` + `LatencyHistogram` with `latencySampleEvery = 1` + 1 Hz `ring_series` enabled) vs the uninstrumented B4 baseline (`6cc1a75`):
- `shard N=1`: paired `B5 / B4 = 1.0190` (`1s95 LB = 0.991`)
- `shard N=8`: paired `B5 / B4 = 1.0110` (`1s95 LB = 0.973`)
- `threads N=1`: paired `B5 / B4 = 1.0070` (`1s95 LB = 0.972`)
- **Result:** `< 1 %` throughput cost bar **PASS** (no measurable overhead).

---

### 6. Step D — Host Representativeness ([`docs/internal/stepd-report.md`](docs/internal/stepd-report.md))

Executed on the host (`bench/results/unattended-2026-11-16/stepd/`, 75/75 runs `status=ok`, 0 failures):

1. **Block A — Seastar `hugetlbfs` (`--hugepages /mnt/hugepages-juring`, 2 MiB pages) vs THP (`R=3` paired):**
   - `W3` (4K QD64): `huge/THP = 0.9966 [0.9943, 0.9989]` (`317,986` vs `319,074` IOPS; `−0.34 %`, `< 5 %`)
   - `W4` (4K QD128): `huge/THP = 1.0122 [0.9475, 1.0768]` (`388,830` vs `384,248` IOPS; CI spans 1)
   - `W10` (1M QD8): `huge/THP = 0.9836 [0.9105, 1.0567]` (`4,677` vs `4,754` IOPS; CI spans 1)
   - `W11` (1M QD32): `huge/THP = 1.0389 [0.8721, 1.2057]` (`4,765` vs `4,599` IOPS; CI spans 1)
   - `M3N8` (4K QD64 × 8 shards): `huge/THP = 1.0123 [0.9589, 1.0657]` (`467,341` vs `461,800` IOPS; CI spans 1)
   - *Amendment A1 / Path B disclosure:* `run-stepd.sh` sampled `/proc/meminfo` `HugePages_Free` before spawning `seastar_file_bench` and after process exit; because `seastar::memory::configure` (`SeaStarRef/src/core/memory.cc:2016-2025`) maps an unlinked `file_desc::temporary` in `/mnt/hugepages-juring` with `MAP_SHARED | MAP_POPULATE` (and throws on failure), pages are freed on process exit before `hp1` is read (`hp0 = 4608 → hp1 = 4608`, 15 warnings disclosed in `docs/internal/stepd-report.md` §3.2).
2. **Block B — `unlimited` memlock (`prlimit --memlock=unlimited:unlimited`, `R=5` paired):** All 15 JUring runs used `io_buffer_mode = registered-fixed` and all 15 `fio` runs used `fixedbufs = 1, registerfiles = 1`. Paired `JUring / fio-matched` means: **W9** `1.0138 [0.9825, 1.0451]`, **W10** `1.0108 [0.9582, 1.0635]`, **W11** `1.0005 [0.9573, 1.0437]`.
3. **Host SMART & cold-cache controls:** Root `nvme smart-log` before/mid/after confirmed `Thermal Management T1/T2 Trans Count = 0`, `critical_warning = 0`, `media_errors = 0`; root `drop_caches` verified functional (`F11`; primary cells are `O_DIRECT`).

---

### 7. Reproducing Part I Results

```bash
cd /home/david/projects/JUring

# Re-generate all aggregate tables from the raw session artifacts (no device I/O):
python3 bench/m1/analyze_m1.py --dir bench/results/m1-2026-11-13
python3 bench/m1/analyze_m1.py --dir bench/results/m1-write-2026-11-15 \
    --out-prefix bench/results/m1-write-2026-11-15/aggregate/m1-write
python3 bench/stream/analyze_stream.py --session m2-final
python3 bench/shard/analyze_m3.py --session m3-2026-11-15
python3 bench/stepd/analyze_stepd.py --dir bench/results/unattended-2026-11-16/stepd
python3 bench/stepd/write_state_analysis.py \
    --session bench/results/unattended-2026-11-16/write-state \
    --gens w2-g1,w2-g2,w2-g3,w2-g4,w2-g5 \
    --out bench/results/unattended-2026-11-16/write-state/analysis
```

Full campaign execution commands are documented in [`docs/internal/HANDOVER.md` §4](docs/internal/HANDOVER.md) and each `bench/*/README.md`.

---

## Part II — Supplementary JMH Microbenchmarks (`FileChannel`, Virtual Threads, FFM)

> [!IMPORTANT]
> **Methodology & comparability caveats for Part II (`docs/benchmark-methodology.md` §9.1):**
> - The tables below come from the earlier JMH suite (`src/test/java/bench/suite/`, OpenJDK `25.0.3`), where `±` is JMH's 99.9 % confidence interval across JMH iterations (not the multi-process Student-$t$ 95 % CI used in Part I).
> - Do **not** compare JMH ops/invocation numbers directly against `fio` time-window IOPS, and do not use the multi-thread write rows below for ranking (several write cells have `±` intervals of `30k–397k` ops/s due to uncontrolled NVMe SLC-cache exhaustion). For rigorous `fio` and Seastar comparisons, always use **Part I** above.

### 1. Single-Core Framework Overhead & Synchronous `FileChannel` Baseline

Baseline ring memory overhead (`NopBenchmark`) and single-thread 4 KiB Direct Reads/Writes (`O_DIRECT`) comparing asynchronous `JUring` against synchronous `FileChannel` (which is clamped to `QD=1` on a single thread).

| Target                   | Concurrency / QD                  | Throughput (ops/s)      | Bandwidth          | Syscalls / op (`raw_syscalls:sys_enter`) | User CPU (`cycles:u/op`) | Kernel CPU (`cycles:k/op`) | Heap Alloc |
|--------------------------|-----------------------------------|-------------------------|--------------------|------------------------------------------|--------------------------|----------------------------|------------|
| `NopBenchmark.juringNop` | 1T (QD=64, ring memory only)      | 11,590,187.4 ± 53,591.1 | —                  | 0.0313 (32.0 ops/call)                   | 52.7                     | 351.1                      | 0.006 B/op |
| `FileChannel.read`       | 1T (QD=1, synchronous `pread64`)  | 11,101.7 ± 979.7        | 43.4 ± 3.8 MB/s    | 1.0023                                   | 1370.2                   | 24409.8                    | 0.209 B/op |
| `JUring.read` (QD=1)     | 1T (QD=1, unbatched `O_DIRECT`)   | 11,479.8 ± 34.1         | 44.8 ± 0.1 MB/s    | 1.0046                                   | 3990.5                   | 25004.6                    | 6.451 B/op |
| `JUring.read` (QD=64)    | 1T (QD=64, batched pipeline)      | 330,195.7 ± 361.3       | 1,289.8 ± 1.4 MB/s | 0.4569                                   | 283.6                    | 10541.4                    | 0.227 B/op |
| `FileChannel.write`      | 1T (QD=1, synchronous `pwrite64`) | 72,470.8 ± 1,044.1      | 283.1 ± 4.1 MB/s   | 1.0004                                   | 901.3                    | 25023.8                    | 0.050 B/op |
| `JUring.write` (QD=1)    | 1T (QD=1, unbatched `O_DIRECT`)   | 72,409.4 ± 1,577.1      | 282.8 ± 6.2 MB/s   | 1.0022                                   | 724.5                    | 26086.7                    | 1.521 B/op |
| `JUring.write` (QD=64)   | 1T (QD=64, batched pipeline)      | 191,163.1 ± 17,715.3    | 746.7 ± 69.2 MB/s  | 0.1445                                   | 286.3                    | 11941.4                    | 0.574 B/op |

> [!NOTE]
> After initialization, JUring's submission and completion hot path (submitting SQEs, reaping CQEs via `ResultConsumer`, and reusing fixed buffers) allocates 0 bytes on the calling thread, verified in [`ZeroAllocationHotPathTest.java`](src/test/java/com/davidvlijmincx/lio/api/ZeroAllocationHotPathTest.java) (and for B1 `JuringFile`, B4 `JUringBlocking`, B5 `RingMetrics`/`LatencyHistogram`, and B6 `GroupCommit` in their respective test suites).

### 2. Single-Thread Queue Depth Sweep vs `FileChannel` (`QD = 1, 16, 32, 64`)

Because `FileChannel` operations are synchronous, a single thread can only issue one I/O request at a time (`QD=1`), whereas `JUring` drives hardware queue depth asynchronously from a single thread:

| Queue Depth (QD) | JUring (1 Thread) Throughput | JUring Bandwidth   | FileChannel (1 Thread) Throughput | FileChannel Bandwidth | Speedup (JUring vs FileChannel) |
|------------------|------------------------------|--------------------|-----------------------------------|-----------------------|---------------------------------|
| QD = 1           | 11,492.2 ± 49.6              | 44.9 ± 0.2 MB/s    | 11,431.7 ± 48.2                   | 44.7 ± 0.2 MB/s       | 1.0x                            |
| QD = 16          | 148,011.3 ± 190.3            | 578.2 ± 0.7 MB/s   | 11,447.1 ± 34.3                   | 44.7 ± 0.1 MB/s       | 12.9x                           |
| QD = 32          | 236,162.3 ± 423.9            | 922.5 ± 1.7 MB/s   | 11,174.3 ± 1,316.8                | 43.6 ± 5.1 MB/s       | 21.1x                           |
| QD = 64          | 330,089.1 ± 748.0            | 1,289.4 ± 2.9 MB/s | 11,437.3 ± 21.9                   | 44.7 ± 0.1 MB/s       | 28.9x                           |

### 3. Virtual Threads (`JUringBlocking` vs `FileChannel` at 1,000 Virtual Threads)

> [!IMPORTANT]
> This whole subsection is **historical JMH evidence** from before the 0.1.0 release-readiness
> pass. It was not rerun for 0.1.0: the benchmark harness does not record a committed CLI/JVM
> flag string, several confidence intervals overlap, and the "equal total platform thread
> budget" framing measures thread efficiency rather than a per-operation engine difference.
> It is kept for transparency and context, not as a 0.1.0 ranking. The defensible result is
> **parity at Loom defaults** (uncapped carrier pool, §3.2) plus "same device throughput with
> 2–4 poller threads instead of hundreds of carriers" under a fixed budget.

When `FileChannel` blocks a virtual thread in kernel I/O, the virtual thread remains pinned/mounted to its carrier thread, forcing the ForkJoinPool scheduler to compensate by spawning up to `maxPoolSize` (default 256) carrier threads. `JUringBlocking` unmounts the virtual thread via `LockSupport.park()` while its `IoShard`-backed poller threads drive `io_uring`.

#### 3.1 Equal Total Platform Thread Budget (`1–2, 4, 8, 16` Platform Threads, 1,000 VTs)

| Platform Thread Budget | Engine         | Thread Breakdown          | Throughput (ops/s)    | Bandwidth            | Speedup  |
|------------------------|----------------|---------------------------|-----------------------|----------------------|----------|
| 1 Thread (Min)         | FileChannel    | 1 carrier                 | 11,433.8 ± 31.5       | 44.7 ± 0.1 MB/s      | baseline |
| 1–2 Threads (Min)      | JUringBlocking | 1 carrier + 1 poller (2T) | 399,108.7 ± 89,551.6  | 1,559.0 ± 349.8 MB/s | 34.91x   |
| 4 Threads              | FileChannel    | 4 carriers                | 43,080.4 ± 220.6      | 168.3 ± 0.9 MB/s     | baseline |
| 4 Threads              | JUringBlocking | 2 carriers + 2 pollers    | 452,192.8 ± 1,262.9   | 1,766.4 ± 4.9 MB/s   | 10.50x   |
| 8 Threads              | FileChannel    | 8 carriers                | 73,003.5 ± 23,547.5   | 285.2 ± 92.0 MB/s    | baseline |
| 8 Threads              | JUringBlocking | 4 carriers + 4 pollers    | 389,340.6 ± 158,626.3 | 1,520.9 ± 619.6 MB/s | 5.33x    |
| 16 Threads             | FileChannel    | 16 carriers               | 137,326.1 ± 32,534.5  | 536.4 ± 127.1 MB/s   | baseline |
| 16 Threads             | JUringBlocking | 8 carriers + 8 pollers    | 443,592.3 ± 40,401.8  | 1,732.8 ± 157.8 MB/s | 3.23x    |

> [!NOTE]
> **Buffer registration & CI width in `VirtualThreadBenchmark`:**
> In `VirtualThreadBenchmark.java`, `JUringVirtualThreadState` uses `ring.read(fileIdx, buf, fileOffset)` (registered file descriptor with caller-allocated aligned `MemorySegment`, **not** pre-registered fixed buffers) so buffer allocation is symmetric with `FileChannel`. Note also that the `JUringBlocking` 1–2T (`399,109 ± 89,552`), 8T (`389,341 ± 158,626`), and 16T (`443,592 ± 40,402`) confidence intervals overlap the 4T mean (`452,193 ± 1,263`) because the NVMe device is already near saturation at 2–4 pollers.

#### 3.2 Equal Carrier Pool (`4` Carriers, 1,000 VTs) vs Uncapped Default Carrier Pool (`maxPoolSize = 256` / `1024`)

| Configuration                                   | Virtual Threads | Engine         | Throughput (ops/s)   | Bandwidth            |
|-------------------------------------------------|-----------------|----------------|----------------------|----------------------|
| Equal Carriers (`4` carrier threads)            | 1,000           | FileChannel    | 39,917.4 ± 10,251.7  | 155.9 ± 40.0 MB/s    |
| Equal Carriers (`4` carrier threads)            | 1,000           | JUringBlocking | 449,448.2 ± 12,379.6 | 1,755.7 ± 48.4 MB/s  |
| Default Loom Pool (`maxPoolSize=256`, 250 VTs)  | 250             | FileChannel    | 445,255.8 ± 502.2    | 1,739.3 ± 2.0 MB/s   |
| Default Loom Pool (`maxPoolSize=256`, 250 VTs)  | 250             | JUringBlocking | 441,046.7 ± 3,085.8  | 1,722.8 ± 12.1 MB/s  |
| Expanded Loom Pool (`maxPoolSize=1024`)         | 1,000           | FileChannel    | 379,776.6 ± 21,294.0 | 1,483.5 ± 83.2 MB/s  |
| Expanded Loom Pool (`maxPoolSize=1024`)         | 1,000           | JUringBlocking | 425,417.5 ± 86,203.3 | 1,661.8 ± 336.7 MB/s |

When `FileChannel` is allowed to spawn hundreds of OS carrier threads (`maxPoolSize = 256` or `1024`), the kernel block layer receives high concurrency from those carrier threads and saturates the NVMe at the cost of hundreds of OS threads; when platform threads are budgeted (`4` carriers), `JUringBlocking` reaches the same device ceiling with `2–4` poller threads.

### 4. Project Panama (FFM API) Downcall Overhead

JMH microbenchmark of foreign function invocation mechanisms using the Java FFM API:

| Invocation Strategy                       | Call Mechanism                         | Average Time (ns/op) |
|-------------------------------------------|----------------------------------------|----------------------|
| `staticFinal_critical_invokeExact`        | `Linker.Option.critical(true)`         | 2.62 ± 0.01 ns/op    |
| `methodHandleProxies_critical`            | `Linker.Option.critical(true)`         | 4.67 ± 0.02 ns/op    |
| `staticFinal_invoke`                      | Direct `MethodHandle`                  | 5.75 ± 0.43 ns/op    |
| `record_staticFinal_proxy`                | `MethodHandleProxies` / Record         | 5.91 ± 0.01 ns/op    |
| `staticFinal_invokeExact`                 | Direct `MethodHandle`                  | 5.92 ± 0.02 ns/op    |
| `instanceField_invokeExact`               | Instance Field Handle                  | 7.99 ± 0.06 ns/op    |
| `instanceField_extractedFromRecord_proxy` | `MethodHandleProxies` / Record         | 8.23 ± 0.07 ns/op    |
| `methodHandleProxies_asInterfaceInstance` | `MethodHandleProxies` / Record         | 8.24 ± 0.12 ns/op    |
| `record_instance_proxy`                   | `MethodHandleProxies` / Record         | 8.64 ± 0.07 ns/op    |
