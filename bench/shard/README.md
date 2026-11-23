# Multi-ring scaling and metrics overhead (bench/shard/)

One ring on one thread has a ceiling. I measured whether sharding work across rings scales cleanly, on the same workload, against `fio numjobs=N` and Seastar `--smp N`. It also measures what the per-ring metrics cost when you turn them on, in a paired A/B of the same code with metrics enabled and disabled.

The results I trust are in [`BENCHMARK.md`](../../BENCHMARK.md#multi-ring-scale-out). The protocol and statistics are in [`docs/benchmark-methodology.md`](../../docs/benchmark-methodology.md), and the decision not to build a shard runtime with work stealing or QoS is in [`docs/design-notes.md`](../../docs/design-notes.md).

## What it measures

The workload is 4 KiB random read, QD 64 per shard, on the aged 10 GiB read-only file `/mnt/TestData/read_files/single_file_bench.bin`, opened `O_DIRECT`, with 10 s warmup and 30 s measured. Shards run from one to eight. For one to four shards, workers are pinned to CPUs `1..N`. For eight shards the runners are confined to the eight data-NVMe `blk-mq` hardware-queue CPUs `1,2,4,6,8,10,12,14` (`mq/0..7`), and the shard runtime runs unpinned inside that CPU mask.

The engines:

- JUring shard runtime: `bench/juring/JUringFileBench.java --runtime=shard --shards N`. One `IoShard` and one ring per worker in one `RingGroup`, registered file and registered fixed 4 KiB buffers, QD 64 per shard. The pipeline is driven by `ctx.onCompletion` handlers dispatched by the shard's batched poll loop.
- JUring threaded mode: `--runtime=threads --threads N`. One platform thread and one ring per worker, each running its own fill and reap loop.
- `fio`: io_uring, `numjobs=N`, registration matched to JUring: `--registerfiles=1 --fixedbufs=1 --iodepth=64 --direct=1 --rw=randread`, pinned with `taskset -c 1..N`.
- Seastar: `bench/seastar/seastar_file_bench --smp N`, built by `bench/seastar/build-file-bench.sh`, same JSON schema.

Only reads run: `--readonly` for fio and read-only `O_DIRECT` descriptors for JUring. Five repetitions, fresh process or JVM per invocation, engines interleaved with the order alternating per repetition block, 300 s rest between invocations. Every repetition is kept and reported.

The session I ran in `bench/results/m3-2026-11-15/` had 60 invocations, all `status=ok`. At eight shards JUring measured `473,126` IOPS against `459,125` for `fio numjobs=8` and `466,460` for Seastar `--smp 8`. The per-shard `min/mean` was `0.963` with a 95% lower bound of `0.947`. Six of the 60 repetitions were in a slow drive state and are disclosed, not dropped.

| Shards | JUring IOPS [95% CI] | `fio numjobs=N` | Seastar `--smp N` | JUring / fio mean (95% lower bound) | Seastar / JUring mean (95% lower bound) |
|---|---|---|---|---|---|
| 1 | 320,760 [313,221, 328,300] | 323,284 [317,252, 329,315] | 317,330 [304,399, 330,260] | 0.993 (0.959) | 0.990 (0.936) |
| 2 | 391,855 [351,094, 432,617] | 403,669 [388,783, 418,556] | 427,332 [426,113, 428,551] | 0.973 (0.859) | 1.102 (0.976) |
| 4 | 465,936 [455,790, 476,083] | 463,263 [446,410, 480,117] | 462,364 [446,966, 477,762] | 1.007 (0.961) | 0.993 (0.948) |
| 8 | 473,126 [464,598, 481,654] | 459,125 [441,418, 476,832] | 466,460 [458,249, 474,672] | 1.032 (0.987) | 0.986 (0.959) |

## The drivers

- `run-scale-out.sh` is the scaling driver. It runs the JUring shard runtime, `fio` and Seastar at 1, 2, 4 and 8 shards, with five repetitions and a 300 s rest per invocation. It is resumable in chunks: `--max-blocks K` runs at most K `(N, repetition)` blocks, and `--resume` keeps complete configurations and runs only the missing invocations. The 300 s rest is carried across chunk boundaries by reading the last invocation's end time from `cell-meta.tsv`. The driver never invokes Maven: the JUring harness is compiled `javac`-only (`JURING_SKIP_MVN=1 ... --help`) against `target/classes`, then runs with `JURING_SKIP_COMPILE=1`.
- `run-metrics-overhead.sh` is the metrics A/B. The baseline arm is commit `6cc1a75`, built once in a `git archive` scratch tree with offline Maven and its own `javac`; the workspace is not edited. The metrics arm is the current tree with metrics on: `RingMetrics` plus `LatencyHistogram`, `latencySampleEvery = 1`, 1 Hz `ring_series`. The three configurations are one shard, eight shards, and threaded mode with one worker. Five repetitions each, 30 s rests, interleaved arms with the order alternating per repetition.
- `analyze_scale_out.py` builds the scaling tables from the raw JSON: one-sided 95% t intervals, paired ratios against `fio` and Seastar, per-shard `min/mean`, p99 ratios, and the per-ring queue-depth and runtime-completion p99 tables. It writes `aggregate/m3.{md,json}`.
- `analyze_metrics_overhead.py` reads the same raw JSON and writes `aggregate/overhead.{json,md}`: paired per-repetition ratio metrics-on / metrics-off, mean, standard deviation and one-sided 95% t interval, with state-flagged repetitions disclosed.

## Run the scaling session

```bash
# plan only: preflight plus every exact command, no files, no I/O
bench/shard/run-scale-out.sh --dry-run

# pilot: 2 s harness check, not session data
bench/shard/run-scale-out.sh --session m3-pilot-2026-11-15 --force \
  --engines juring-group,juring-manual,fio,seastar --n-list 1,2,4,8 \
  --reps 1 --seconds 2 --warmup 2 --rest 30

# full session in chunks: repeat the --resume line until it finishes
ALLOW_RESIDUAL_LOAD=1 bench/shard/run-scale-out.sh --session m3-2026-11-15 --max-blocks 3
ALLOW_RESIDUAL_LOAD=1 bench/shard/run-scale-out.sh --session m3-2026-11-15 --resume --max-blocks 3

python3 bench/shard/analyze_scale_out.py --session m3-2026-11-15
```

Overrides: `--session`, `--n-list`, `--engines`, `--reps`, `--seconds`, `--warmup`, `--rest`, `--max-blocks`, `--force`, `--resume`, `--dry-run`. Environment: `READ_FILE`, `JURING_RUN`, `FIO_BIN`, `SEASTAR_BIN`, `JVM_OPTS` (default `-Xms8g -Xmx8g -XX:+AlwaysPreTouch`), `FIO_GROUP_REPORTING` (default 1), `ALLOW_RESIDUAL_LOAD=1`.

`ALLOW_RESIDUAL_LOAD=1` relaxes only our own chunk-start loadavg residual. A foreign process above 20% CPU is still a hard failure. The driver records `uptime`, `loadavg` and the top `ps` entry per invocation in `logs/<id>.env`, and records the start and end check in `cell-meta.tsv`.

## Run the metrics A/B

```bash
# build the baseline arm once: git archive 6cc1a75 into scratch/b5-bench/baseline,
# offline maven compile, then its own javac
bench/shard/run-metrics-overhead.sh --prep-baseline

# plan only
bench/shard/run-metrics-overhead.sh --dry-run

# real session, ~40 min on an otherwise idle machine
FROZEN_HEAD=<sha> FROZEN_SRC_SHA1=<sha> FROZEN_TEST_SHA1=<sha> \
  bench/shard/run-metrics-overhead.sh --session b5-overhead-2026-11-14

python3 bench/shard/analyze_metrics_overhead.py --session b5-overhead-2026-11-14
```

The three variables are the code identity the session must run against; `--allow-unfrozen` skips the check for a dry run or a prep. Overrides for this driver: `--session`, `--reps`, `--seconds`, `--warmup`, `--rest`, `--prep-only`, `--prep-baseline`, `--resume`, `--force`, `--dry-run`. The analyzer reports state-flagged repetitions side by side under the flat external p99 rule and under a rule scaled by the number of workers, and the primary mean is the same under both.

| Configuration | Paired metrics-on / metrics-off | 95% lower bound |
|---|---|---|
| 1 shard | 1.0190 | 0.991 |
| 8 shards | 1.0110 | 0.973 |
| Threaded mode, 1 worker | 1.0070 | 0.972 |

The point estimates are slightly above 1, but all three lower bounds are below 1. I read that as no measurable throughput cost at this power, not a speedup. The session had `30/30` invocations `status=ok` and a minimum individual repetition of `0.9543`. If the cost lands above the bar, one re-run with a cheaper sampling configuration (`latencySampleEvery 8` and/or a 2 s series interval) is allowed; report both configurations and state the final one.

## The runner options

The default threaded invocation is unchanged:

```bash
bench/juring/run-juring.sh --workload randread --file <10GiB> \
    --bs 4k --qd 64 --threads N --seconds 30 --warmup-sec 10 \
    --runs 1 --seed 20261114 --readonly --label <id> --json <out.json>
```

Shard-runtime mode:

```bash
bench/juring/run-juring.sh --workload randread --file <10GiB> \
    --bs 4k --qd 64 --runtime=shard --shards N --first-cpu 1 \
    --seconds 30 --warmup-sec 10 --runs 1 --seed 20261114 \
    --readonly --label <id> --json <out.json>
```

| flag | meaning |
|---|---|
| `--runtime threads\|shard` | worker runtime; default `threads` |
| `--shards N` | shard count for `--runtime=shard`, one `IoShard` and ring per shard; defaults to `--threads` and is rejected with `--runtime=threads` |
| `--first-cpu N` | base CPU for shard affinity, shard `i` to `first-cpu+i` (default 1, `-1` means no affinity); shard mode only |
| `--metrics on\|off` | per-worker `ring_metrics` and measured-phase `ring_series` (default on); `off` removes every metrics field and disables the shard latency sampling |
| `--metrics-interval SEC` | ring-series sampling interval during the measured phase (default `1.0`; `0` disables the series) |

In shard mode `--threads` is ignored; the worker count is `--shards`. Both modes record `config.threads` as the total worker count, so total QD is `64 x workers` and stays comparable to `fio numjobs=N`.

Shard mode adds `config.runtime`, `config.shards`, `config.first_cpu` and `runs[].shards[]` with per-shard counters plus the full `ShardStats` snapshot. `runs[].threads[]` is still populated with the same numbers so older consumers keep working. With metrics on, every worker entry carries `ring_metrics`, `ring_series` and, in shard mode, `dispatch_latency_us`. `runs[]` adds `metrics {enabled, interval_sec, ring_series_points}`, and `aggregate` adds summed ring counters, `ring_queue_depth` and the shard dispatch-latency percentiles. `--metrics off` emits none of it.

## Output locations

```text
bench/results/<session>/
  cells/<N>__<engine>__r<rep>.json      raw per-process JSON
  logs/<id>.log, logs/<id>.env          process output; pre/post uptime, loadavg, top
  cell-meta.tsv                         one row per invocation, all repetitions kept
  manifest.txt                          host, kernel, JDK, CPU, NVMe, protocol, commands
  aggregate/m3.{json,md}                scaling session analysis
  aggregate/overhead.{json,md}          metrics A/B analysis
```

Session directories are gitignored. The overhead session also archived its first analysis as `aggregate/overhead-first-pass.{md,json}`.

## Caveats

1. All shards and engines use the same 10 GiB file. Page cache and extent mapping are shared, so this is one-file scaling, not one file per shard.
2. `fio` runs with `--group_reporting=1`. With `numjobs>1`, `fio` 3.40 emits one aggregated `jobs[]` entry, so per-job spread is not available. The per-shard `min/mean` is evaluated on JUring shards. `FIO_GROUP_REPORTING=0` exposes per-job entries but changes only the output.
3. Per-shard fairness depends on the drive's queue topology. The NVMe's `blk-mq` contexts each cover four CPUs (`/sys/block/nvme1n1/mq/*/cpu_list`, for example `mq/0: 0,1,16,17`). Pinning to CPUs `1..8` gives CPU 1 and CPU 8 dedicated queues while the others share, which produced a roughly 2:1 per-shard ratio at eight shards in the older three-repetition run. The current driver avoids that by using the hardware-queue CPU set and running the shard runtime unpinned inside it.
4. `SE(N)` and `p99(N)/p99(1)` on their own measure the saturated device, not the runtime. One shard at QD 64 already reaches about 68% of the measured device ceiling of roughly 476k IOPS, so `SE(8)` is capped near 0.18 for every engine. Compare scaling efficiency against `fio` at the same N, as the analyzer does.
5. The shard runtime's per-shard latency includes its batched dispatch; threaded mode measures the same before-submit to callback window without that dispatch. That difference is the runtime cost being measured.
6. The shard runtime does not use one `JuringFile` per shard or a Seastar-style multi-file layout. The harness opens one `fd` per shard on the same file and uses `readFixed` with registered buffers.
7. `ring_metrics` counters include runtime-internal ring operations, the shard wakeup read and the shutdown cancel. Snapshots are best-effort and may lag one loop pass, so `reaped == completed + failed` holds per snapshot but combined views are not atomic.
8. `dispatch_latency_us` is runtime completion latency, from registration to dispatch. It starts before the SQE is submitted, so it includes kernel and device completion plus the runtime's post-completion dispatch; it excludes SQE preparation and the handler body. Its percentiles are bucket upper bounds, up to 6.25% over-estimate for values of 16 ns and above. It is not `fio` `clat` and not the external `latency_us`, and the analyzer never treats them as the same number.
9. Reads only. There are no write configurations and no CPU-per-op or syscall-per-op measurements here.
10. If a session has to be trimmed, `--n-list 1,8` is the documented fallback, and the notes must say so.
