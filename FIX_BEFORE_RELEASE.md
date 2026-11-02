# Fix Before Release

This is a code-only review of JUring, in two passes: a first review, and a second independent pass that looked for anything the first one missed (items marked *Added by the second-pass review*). A third pass (2026-11-02) checked the plan itself against the code and corrected it (items marked *Third pass*). A fourth pass (2026-11-02) replaced the in-JVM CPU and syscall counters with `perf` measurements, moved the fio comparison onto JMH and fio's own JSON, and replaced the homemade latency histogram with HdrHistogram (items marked *Fourth pass*; A19, A20, C12; steps 2.2, 2.3, 2.4, 2.12, 2.13). Nothing was compiled or run. It looks at the library and at the JMH/fio benchmark suite before the project goes to the OpenJDK team, Reddit, and Foojay.

> **Fifth pass / Second-opinion review (2026-11-02, verified on the Linux benchmark machine `treediagram`):** Every finding and plan step below was verified against the codebase and empirically tested on `treediagram` (Linux `7.0.12-201.fc44.x86_64`, AMD Ryzen 9 3950X, OpenJDK `25.0.3+9`, `/home/david/testData` -> `/mnt/TestData` on `ext4` `/dev/nvme1n1p1`). All findings in Sections A–C are accurate. Inline `> **Second-opinion remark:**` notes have been added where an "unverified" claim was empirically confirmed or where a subtle implementation trap / missed file would otherwise trip up the implementing agent.

Sections A–C list the findings. Section D is the plan for fixing them, in order.

Each item has:
- **Hypothesis**: what is wrong and why it matters.
- **Evidence**: where it is in the code.
- **Fix**: what to change.

Severity levels:
- **[P0]** Invalidates a published claim, or can corrupt memory.
- **[P1]** Skews results, or is a real bug that reviewers will find.
- **[P2]** Release hygiene and polish.

---

## Instructions for the implementing agent

Read this section first. Where it conflicts with anything below, this section wins.

### Scope
- **You own phases 0, 1 and 2** (section D). Work through them in order, one step at a time.
- **Phases 3, 4 and 5 belong to the maintainer.** Stop after step 2.13 and the phase 2 smoke run. The only README edits you make are the header fixes in step 0.3 (license, JDK, kernel). Every other README claim waits for phase 4, because it needs numbers from the phase 3 run.
- Don't push, open PRs, or post anything outside this repository unless the maintainer asks.
- This file replaces `hypothesis.md` and `BENCHMARK_VERIFICATION_AND_SCALING_PLAN.md`. Don't follow them; step 0.1 deletes them.

### Environment
- These instructions assume the Linux benchmark machine, where you can run `mvn test`, JCStress, and benchmark smoke runs yourself. **Check `uname -s` first.** The repository is also checked out on the maintainer's macOS machine, where io_uring doesn't exist and every io_uring test fails. If you are not on Linux, do only the code changes, mark each step's runtime proof `blocked: not Linux`, and tell the maintainer.
- At the start, record `uname -r`, `java -version`, the filesystem and mount options of the benchmark data directory, and whether `fio` is installed. Put them in the first commit message.
- Steps that need root, drive preconditioning, or long fio runs (A5 preconditioning, A13 re-run): implement the scripts and flags, but don't run the long campaigns. They belong to phase 3.

### Working rules
- **Branch:** `remove-network-sockets` (it matches decision C2).
  > **Second-opinion remark:** On `treediagram`, the checked-out branch is currently `fix-bench-issues`, which points to the exact same commit (`e5494f4`) as `remove-network-sockets`. Also, `git status` shows a clean working tree (`IoUringDispatcher.waitCqe(MemorySegment ring, MemorySegment cqePtr)` still has the unused `ring` parameter in `IoUringDispatcher.java:673`). Check `git status` first; if `IoUringDispatcher.java` is clean, ignore the bullet below or remove the unused `ring` parameter in a separate hygiene commit if desired.
- **Before step 0.1:** the working tree has an uncommitted change in `IoUringDispatcher.java` (the unused `ring` parameter removed from `waitCqe`). Ask the maintainer whether to commit it on its own first. Don't fold it into a step commit.
- **One commit per step** (for example `fix(1.1): advance CQ head before invoking consumer (B1)`). Each commit compiles and passes `mvn test`. Until step 0.4 lands, `mvn test` can fail for reasons unrelated to the step (C7). Record the failing tests once before step 0.1, and until 0.4 the rule is "no new failures compared with that baseline".
- **Progress:** when a step is done, set its *Status* cell in section D to `done <short-hash>`. If a step is blocked, set `blocked: <reason>` and move on to the next independent step.
- **Line numbers in this file are approximate** and drift as fixes land. Class and method names are authoritative; search for them.
- **Regression tests first:** for every Phase 1 step, write the test, watch it fail, then fix. If a test can't be made to fail on this machine (timing-dependent), say so in the commit message. For memory-safety bugs (double `free`, use after free), don't let the failing test actually corrupt the heap: observe the bug through a package-private allocation counter or hook, or run the test in a forked JVM, so that a failure is reported as a failure and not as a JVM crash.
- **Design stop:** steps 1.3 (poller wakeup) and 1.8 (cancel-and-drain on close) change the concurrency design. They depend on each other (the drain needs the eventfd to get the poller out of `io_uring_enter`), so write **one** design note that covers both. Before implementing them, post a short design note in the conversation (states, who frees what and when, which thread does which syscall) and wait for the maintainer's approval.
- **"Unverified" claims** (A3 io-wq, A15 fio setup flags, A13 registration limits): verify them on this machine before acting on them, and record the result in the step's commit message. If verification contradicts this doc, update the item and tell the maintainer.
  > **Second-opinion remark:** All three "unverified" claims have now been empirically verified on `treediagram` and confirmed; see the remarks under A3, A13, and A15 below.

### Decisions already made
These are final. Don't reopen them.

| Item | Decision |
|---|---|
| C2 | **Remove the socket support:** socket/accept/connect/send/recv/bind/listen/setsockopt from `JUring`, `JUringBlocking`, `IoUringDispatcher`, `LibCDispatcher`; delete `SocketTest` and its surefire exclusion. |
| C6 license | **Unlicense.** Keep `LICENSE`; fix the README badge and footer. |
| B2 | **Reject** non-native segments with `IllegalArgumentException`. No bounce buffer. `byte[]` overloads keep their existing copy. *(Third pass: the library has no `ByteBuffer` entry points, so the earlier "heap `ByteBuffer`s" wording has been dropped.)* |
| B3 | **Register the ring fd only when `IORING_SETUP_SINGLE_ISSUER` is set.** Otherwise always use the plain fd. Remove the re-register-on-thread-change path. |
| B11 | **eventfd wakeup.** The poller keeps an `IORING_OP_READ` armed on an eventfd; producers `write()` to it only when the poller has published that it is (about to be) in the kernel. That flag handshake needs a StoreLoad fence on both sides (the same pattern as B10). |
| B13 | **Package-private.** `ZeroGcSqe`, `ZeroGcCqe`, `ZeroGcUserData`, `ZeroGcIovecBlock` and `IoUringDispatcher` become package-private. *(Third pass, same decision applied to classes the first list missed: `Iovec`, `io_uring_params`, `io_sqring_offsets`, `io_cqring_offsets`, and `JUring.checkInReadBuffer(long)`. See B13.)* In `Result`, the full constructor and both `set(...)` overloads become package-private; the public no-arg and copy constructors stay (the benchmarks use `new Result()`). No package moves. |
| Old plans | **Delete** `hypothesis.md` and `BENCHMARK_VERIFICATION_AND_SCALING_PLAN.md`. |

---

## A. Benchmark methodology (affects published numbers)

### A1 [P1] The "26x / 31x faster than FileChannel" headline is a per-thread number presented as an API-vs-API number
*Revised after discussion: severity lowered from P0 to P1. The benchmark itself is valid; the framing is the problem.*

**Hypothesis:** A single thread with `FileChannel` can only have one `pread` in flight, so comparing one JUring thread at QD 64–128 with one `FileChannel` thread at QD 1 is a valid measurement of *what one thread can do with each API*. But `FileChannel` is not limited to one I/O per channel: positional `read(buf, pos)` / `write(buf, pos)` take no lock on Linux, so N threads (platform or virtual) can each have a `pread` in flight on the same channel. In Java, that is how you get QD=N with `FileChannel`.

The README presents the per-thread ratio without qualification: line 15 says "outperforms standard Java `FileChannel` by up to **31x**", line 123 says "26.2x higher read throughput and 6.1x higher write throughput". Readers, and the OpenJDK team in particular, will read those as API-vs-API ratios, and the first reply will be "compare at equal queue depth". That undercuts the rest of the results, including the stronger claims that already exist (README line 83: one JUring thread beats 8–16 `FileChannel` threads).

**Evidence:**
- `DirectReadBenchmark.java:63`: `@Param({"1","16","64","128"}) qd` only affects the JUring path.
- `fileChannelDirectRead` (`:209-230`) ignores `qd`, so the same QD=1 loop runs four times and shows up in the result tables as if it were a queue-depth sweep.
- `DirectWriteBenchmark.java:144`: `qd = 64` hard-coded for JUring; `FileChannel` is QD=1.
- `MultiThreadScalingBenchmark` and `VirtualThreadBenchmark` already provide the equal-concurrency comparisons.

**Fix:**
- Label the 26x/31x/6.1x numbers as "one thread: io_uring at QD=64/128 vs blocking `pread`", or remove them from the opening paragraph.
- Lead with the equal-concurrency comparison: N `FileChannel` threads (or N virtual threads) against one JUring thread at QD=N. The "one JUring thread beats 8–16 FileChannel threads" result is the claim that survives scrutiny.
- Publish the QD=1 vs QD=1 row (`submitAndWait(1)` against `FileChannel.read`) so readers can see the per-op API overhead separately.
- Move `qd` out of the state used by `fileChannelDirectRead` (or split the state), so the FileChannel row appears once.

### A2 [P0] `VirtualThreadBenchmark` measures CPU on the wrong thread
**Hypothesis:** `CpuAuxCounters` uses `getrusage(RUSAGE_THREAD)` on the JMH harness thread. In `VirtualThreadBenchmark` that thread only submits tasks and waits on `latch.await()`. The I/O runs on carrier threads and on the `JUringBlocking-Poller-*` platform threads, and none of their CPU is counted. The README claim that kernel CPU drops by 88.9% (3.2 against 28.8 sys ns/op, line 86/199) compares how much CPU two idle waiting threads used, not the I/O cost.

**Evidence:**
- `CpuAuxCounters.java:30,68` uses `RUSAGE_THREAD`.
- `VirtualThreadBenchmark.java:129/188` and `:193/250` place `cpu.begin()`/`cpu.end()` around `latch.await()`.

**Fix:**
- Measure CPU with `-prof perfnorm` (`task-clock`, `cycles:u`, `cycles:k` per op). It counts every thread of the forked JVM (pollers, carriers, io-wq workers, GC/JIT) over the measurement window only, and normalises by total ops. See step 2.2.
- *Fourth pass:* the earlier fix suggested `getrusage(RUSAGE_SELF)` inside `CpuAuxCounters`. That doesn't work: JMH requires `@AuxCounters` to be `Scope.Thread`, so with `@Threads(N)` every thread adds the process-wide delta over overlapping windows and the result is roughly N times too high.
- Take the 88.9% sys-time claim out of the README until it is measured this way.

### A3 [P1] Per-thread CPU counters are tick-quantised, and miss io-wq worker CPU in the buffered benchmarks
*Revised after discussion: SQPOLL is not used anywhere in the benchmarks, so that mention was wrong and has been removed. Severity lowered from P0 to P1, and the io-wq part is now limited to the buffered benchmarks.*

**Hypothesis:**
1. **Tick quantisation (applies to every benchmark using `CpuAuxCounters`).** On kernels without `VIRT_CPU_ACCOUNTING_GEN`, the user/sys split from `getrusage` is estimated from scheduler ticks (`cputime_adjust`). Each invocation (2048 ops, about 5 ms for JUring) is about 1 tick at HZ=250. Values like "11.9 ns user/op" and "77.6 ns/op" (README line 84) are reported with more precision than the method can deliver.
2. **io-wq worker CPU (buffered benchmarks only).** Without SQPOLL, io_uring still hands a request to an `iou-wrk-*` worker thread when it cannot be issued without blocking. For O_DIRECT on NVMe this only happens when the device queue is full, so it barely affects the direct benchmarks. For buffered writes it depends on the filesystem: filesystems without non-blocking buffered-write support (ext4, as far as I know; XFS and btrfs have it on recent kernels; unverified for the benchmark machine) punt every write to io-wq. That CPU is not counted by `RUSAGE_THREAD`, while `FileChannel` does its work inline and is charged in full. The hand-off to a worker thread may also explain much of the "pwrite ~33% faster" result (README line 237), better than the README's "ring coordination overhead" explanation.

**Evidence:** `CpuAuxCounters.java:30,68` (`RUSAGE_THREAD`); `BufferedWriteBenchmark` and `BufferedReadBenchmark` use the same counters.

**Fix:**
- Check whether io-wq is involved: run the buffered write benchmark and look for `iou-wrk` threads (`ps -L -p <pid>` or `top -H`). Record the filesystem and kernel version.
- Measure CPU with `-prof perfnorm` (see step 2.2). `task-clock` is exact, and `cycles:u`/`cycles:k` give the user/kernel split from hardware counters instead of tick samples. Confirm that `iou-wrk` threads are counted (they are created as threads of the JVM process on kernel 5.12+; unverified on the benchmark machine): a buffered-write run should show more `task-clock` per op than the harness thread alone.
- Report CPU per op with one or two significant digits and the error.
- If io-wq is involved, correct the README's explanation of the buffered-write result.

> **Second-opinion remark (verified on `treediagram`):**
> 1. **Tick quantisation confirmed:** `/boot/config-7.0.12-201.fc44.x86_64` has `CONFIG_HZ=1000` and `CONFIG_VIRT_CPU_ACCOUNTING_GEN=y`, but `/proc/cmdline` has no `nohz_full=`, so `vtime_accounting_enabled_this_cpu()` is false and `getrusage` falls back to tick-sampled `cputime_adjust` for the user/sys split.
> 2. **io-wq in buffered writes confirmed:** `/home/david/testData` is a symlink to `/mnt/TestData` on `ext4` (`/dev/nvme1n1p1`). Running `BufferedWriteBenchmark.juringBufferedWrite` on `treediagram` (`ps -L -p <jvm_pid>`) spawns ~45 `iou-wrk-<tid>` threads as threads of the forked JVM process, with two `iou-wrk` threads running at ~98–101% CPU each during the measurement window. Because they are clone children of the JVM task group, `perfnorm` (`perf stat -p <pid>`) automatically includes their `task-clock` and `cycles`.

### A4 [P1] The live fio comparison does not check I/O results or fio's exit code
*Revised after discussion: severity lowered from P0 to P1. The fixed 4 KiB size doesn't affect this finding, but the code gives no reason to think the published runs had errors: `maxBlocks = fileSize / BLOCK_SIZE` (`DirectLatencyBenchmark.java:193`) keeps reads inside the file, and the registered buffers are page-aligned. This is a missing check, not evidence of inflated numbers.*

**Hypothesis:** The `--fio-live` runner gives the "+8.7% faster than fio" and "within 2.6% parity" numbers (README lines 72, 307–320). Its JUring consumer increments `opCount` for every CQE without checking `result.result()`. A fixed 4 KiB request can still fail or come back short (`-EIO`; `-ENOSPC` on writes; `-EINVAL` if alignment is ever off; `0` past the end of the file if the file is replaced by a smaller one). Each of those would be counted as a successful 4 KiB op. The JMH variant of the same benchmark does validate, and that inconsistency is easy for a reviewer to spot. Separately, a failed fio run is parsed as `iops=0` and printed as "JUring +∞%".

**Evidence:** `DirectLatencyBenchmark.java:710-719` (the consumer in `runJuringBench`); the fio process exit code is never checked.

**Fix:** Add `if (result.result() != BLOCK_SIZE) throw new IllegalStateException(...)`, exactly as the JMH `readConsumer`/`writeConsumer` do. Check the fio exit code (`p.waitFor() != 0`) and fail on `iops == 0`.

*Fourth pass:* step 2.4 now deletes `runJuringBench` and runs the JUring side through JMH, whose consumers already check `result.result()`. What remains of this item is the fio side: `bench.sh` fails when fio exits non-zero or its JSON reports errors or zero IOPS (A19).

### A5 [P1] The fio comparison is not like-for-like and always runs in the same order
**Hypothesis:** Several things favour JUring in the head-to-head:
1. JUring always runs first and fio last (`:596-632`). For `randwrite`, the SLC cache, garbage-collection backlog, and drive temperature all get worse with each phase. `syncDevice()` only runs `sync`, which does nothing for O_DIRECT, and then sleeps for 1 s. That is not enough recovery time.
2. fio uses its random map by default (`norandommap=0`): it records every block it touches and never repeats one. That bookkeeping costs CPU on a single CPU-bound thread at about 380k IOPS. JUring's `FioPrng` has no such cost. The "+8.7% raw engine speed" figure may simply be fio's random-map overhead.
3. `runJuringBench` starts both JUring phases with `new FioPrng(42424242L)` (`:688`). Phase 2 ("telemetry OFF", the +8.7% number) re-reads exactly the same block sequence as phase 1.
4. With telemetry ON, JUring makes an extra `submit()` syscall per loop (`:769`), but fio's telemetry does not. This one goes against JUring, so it is conservative, but it means the configurations are still different.
5. The plain xorshift (no `*` multiplier) followed by `% maxBlocks`, where `maxBlocks` = 2,621,440 = 5·2¹⁹, depends heavily on the weak low bits. The access pattern is not as uniform as "mirrors fio's tausworthe" suggests.

**Fix:**
- Run fio with `--norandommap --randrepeat=0` (and optionally `--gtod_reduce=1` for the raw run).
- For "raw engine speed", also compare against `t/io_uring` from the fio repo, which is the tool kernel developers use for peak IOPS.
- Alternate or randomise the run order, repeat each configuration at least 3 times, and report mean ± stddev.
- Precondition the drive before write tests (for example a full sequential write followed by 2× random write).
- Use a different seed for each phase.
- Use `SplittableRandom`/`xoshiro256**` or xorshift64*, and `Math.floorMod`/`nextLong(bound)`.

*Fourth pass:* after step 2.4 the JUring side is the JMH `FioComparisonBenchmark`. Points 3 and 4 then become: seed each fork differently (record the seed in the results), and drop the extra telemetry `submit()` (A19).

### A6 [P1] `DirectReadBenchmark` replays one 8 MiB hot set, and every thread reads the same LBAs
**Hypothesis:** The task list is fixed at 2048 offsets (`DEFAULT_TASK_COUNT`, `DatasetManager.java:29`), which is 8 MiB, and every invocation replays it. `threadOffset` is always 0 (`DirectReadBenchmark.java:82`) and the dataset is `Scope.Benchmark`. With `--threads N`, every thread therefore reads the same sequence in lockstep.

O_DIRECT bypasses the page cache, but not the controller's FTL mapping cache or read buffers, and not dm-cache, VM host caches, or RAID controllers. An 8 MiB working set gives much better device behaviour than random reads over 10 GB, which inflates both the absolute IOPS and the cross-tool comparisons. `MultiThreadScalingBenchmark` already uses a per-thread pool of 65536 tasks, so the two suites are inconsistent.

**Fix:**
- Use `generateThreadTasks(threadIndex, threadCount, 65536+)` for each thread, as `MultiThreadScalingBenchmark` and `DirectWriteBenchmark` do.
- Better still, generate offsets on the fly across the whole file with a PRNG, as the fio comparison does.
- Document the working-set size.

### A7 [P1] `BufferedWriteBenchmark` adds a 4 KiB memcpy per op on the JUring side only
**Hypothesis:** `ring.prepareWriteFixed(fileIdx, state.writeSegment, offset, bufIdx)` (`BufferedWriteBenchmark.java:137`) calls the `MemorySegment` overload. Because `writeSegment` is not the registered buffer, that overload copies it into the registered buffer (`JUring.java:623-625`). `FileChannel.write` writes straight from the direct buffer, so JUring does one extra 4 KiB copy per op. This is part of the reason for the README claim that "pwrite is ~33% faster than io_uring for buffered writes" (lines 78, 237).

**Fix:** Use the zero-copy overload `prepareWriteFixed(fileIdx, DEFAULT_BUFFER_SIZE, offset, bufIdx)`; the registered buffers are already filled with `0x3C` in setup. Then re-measure, and measure io-wq CPU as described in A3.

### A8 [P1] Under DEFER_TASKRUN, peeking after a plain submit never reaps anything; read and write pipelines also differ
**Hypothesis:** With `IORING_SETUP_DEFER_TASKRUN`, the kernel only posts CQEs during an `io_uring_enter(GETEVENTS)` call made by the submitting task. The write benchmarks call `ring.submit()`, which does not set GETEVENTS, and then `peekForBatchResult()` (`DirectWriteBenchmark.java:169-176`; the same code is in `MultiThreadScalingBenchmark.juringDirectWrite`). That peek always returns 0. The `submitBatch`/`waitBatch` tuning therefore does not do what its comments describe, and enters per op are higher than they need to be.

The read benchmarks keep their pipeline full across invocations. The write benchmarks drain to 0 at the end of every 2048-op invocation, so the write numbers include a drain period that the read numbers do not.

**Fix:**
- Use one pattern for both reads and writes: refill to QD, then `submitAndWaitForBatchResult(1, qd, consumer)`.
- Keep `inFlight` across invocations for writes, as the reads already do.
- Remove the peek-after-submit.

### A9 [P1] Interpreting the `VirtualThreadBenchmark` at 1000 VTs
**Hypothesis:** `FileChannel` on a virtual thread pins the carrier for the duration of `pread`. Because this benchmark uses O_DIRECT, the JDK compensates by growing the ForkJoinPool (for buffered reads it does not compensate at all, see C4), but only up to `jdk.virtualThreadScheduler.maxPoolSize` (default `max(parallelism, 256)`, so 256 on the benchmark machine). JUringBlocking is created with `qd = min(1024, vts)`, so at 1000 VTs it really does have about 1000 I/Os in flight, while FileChannel is capped at about 256. The "FileChannel collapses / 1.87x" result is therefore mostly this JDK limit, not raw efficiency.

Also: the benchmark creates 1000 VTs per invocation to do only 2048 ops, and it calls one `Blackhole` from many threads, which is not thread-safe.

**Fix:**
- Explain the maxPoolSize cap in the README.
- Add a run with `-Djdk.virtualThreadScheduler.maxPoolSize=1024`.
- Raise ops per invocation so that each VT does many I/Os.
- Consume results on the harness thread, for example by summing into a `LongAdder` and consuming that once.

### A10 [P2] The measured runs are instrumented
**Hypothesis:** Every benchmark fork, `bench.sh`, and `BenchmarkRunner` pass `-Djuring.countEnters=true`. That adds an `AtomicInteger.incrementAndGet()` to every `io_uring_enter`. The cost is small, but a "beats fio by +8.7%" headline should be measured on the build you ship.

**Fix:** Count syscalls in a separate run with `perfnorm` tracepoint events (step 2.3), and publish throughput with no counting in the JVM. *Fourth pass:* the `-Djuring.countEnters` counter becomes unnecessary once perf counts the syscalls; remove it (step 2.3).

The two `getrusage` calls per invocation in `CpuAuxCounters` are also inside the measured region. They cost under 1 ns/op over 2048 ops, but they go away together with the class (step 2.2).

### A11 [P2] Statistical rigour
**Hypothesis:** The "FULL publication" mode uses `forks(1)` (`BenchmarkRunner.java`, the `full` branch), so differences between forks (JIT, layout, ASLR) never show up in the error bars. Several README tables report single numbers with no ± value.

**Fix:**
- Publication runs should use at least 3 forks (5 is better).
- Report the JMH score ± error for every row.
- Record the hardware: CPU, governor, SMT, NVMe model and firmware, kernel, filesystem, and mount options.

### A12 [P2] Benchmarks are hard-wired to the author's machine
**Hypothesis:**
- `DatasetManager.java:25-26` hard-codes `/home/david/testData/...`, and line ~331 hard-codes `/mnt/TestData/...`.
- MULTI_FILE mode expects up to 2211 pre-existing `.bin` files.
- `generateThreadTasks` throws on any file smaller than 4 KiB, because `random.nextInt(0)` throws.

Reddit/Foojay readers cannot reproduce the results.

**Fix:**
- Make the data directory a system property or environment variable with a sane default (for example `./bench-data`).
- Add a generator that creates the multi-file dataset with random, non-sparse content.
- Skip files smaller than the block size.

### A13 [P0] "fio crashed with EMFILE" and "registered files bypass RLIMIT_NOFILE" are false
*Added by the second-pass review.*

**Hypothesis:** The README says JUring handles 2,211 files "where native C fio crashes with `EMFILE`" because registered files "bypass the standard file descriptor limit". That can't be the explanation:
- `registerFiles` takes fds that are already open (`FileDescriptor::getFd`), so every file still uses a normal process fd.
- `DirectReadBenchmark.java:89` and `:97` open 2,211 `FileDescriptor`s **and** 2,211 `FileChannel`s in the same JVM, which is 4,422 fds. The FileChannel MULTI_FILE rows (README L176, L179) ran fine too.
- HotSpot raises the soft `RLIMIT_NOFILE` to the hard limit at startup (`-XX:+MaxFDLimit`, on by default). fio launched from a shell keeps the shell's soft limit of 1024.
- The repo's own `reference/fio_job.conf:14` already sets `openfiles=100`, the fio-side fix.

So the difference is the JVM's startup behaviour, not io_uring. Anyone who knows fio will spot this.

**Evidence:** README L82, L177–183, L343–344, L611–612.

**Fix:** Re-run fio with `ulimit -n 65536` (or `--openfiles`) and publish the real numbers. Delete the "Crashed" rows and the "bypasses ulimit" text. If you want to mention it, say "the JVM raises the fd soft limit by default; fio needs `ulimit -n`".

> **Second-opinion remark (verified on `treediagram`):** Confirmed: `ulimit -Sn` is `1024` and `ulimit -Hn` is `524288` on `treediagram`. HotSpot automatically raises its soft limit to `524288` at startup (`-XX:+MaxFDLimit`), whereas `fio` inherits `1024` and fails during `open(2)` before `IORING_REGISTER_FILES` is ever called.

### A14 [P0] Buffered-I/O "enters/op" counts only `submit()` calls, and FileChannel's 1.0 is hard-coded
*Added by the second-pass review.*

**Hypothesis:** The buffered "64.1x / 16.3x fewer syscalls" claims are not measurements.
- `BufferedReadBenchmark.java:137,145` increments `submitsIssued`, and `:156` records it as the syscall count. The `waitForBatchResult` enters (`:152`) are never counted, and `takeEnterCount()` is never read. The reported 0.0156 is exactly 1/64, the batch size.
- `BufferedWriteBenchmark.java:144,152,159,163` does the same.
- On the FileChannel side, "1.0000 enters/op" is `counters.record(totalTasks, …)`: an assumption, not a measurement (`BufferedReadBenchmark:179`, `DirectReadBenchmark:228`, `DirectWriteBenchmark:220`, `MultiThreadScalingBenchmark:311,405`).

**Evidence:** README L231–233 and the FileChannel syscall columns.

**Fix:** Measure both sides the same way, from outside the JVM, with `perfnorm` tracepoint events (`raw_syscalls:sys_enter`, `syscalls:sys_enter_io_uring_enter`, `syscalls:sys_enter_pread64`, `syscalls:sys_enter_pwrite64`, `syscalls:sys_enter_futex`). See step 2.3. *Fourth pass:* the earlier fix said to use `ring.takeEnterCount()`. Don't: it only counts `io_uring_enter`, not the futex/eventfd calls where the virtual-thread and poller designs differ, and it instruments the hot path (A10). Also note that `VirtualThreadBenchmark` falls back to `1` enter per 2048 ops when the counter is off (`countedEnters > 0 ? countedEnters : 1`).

### A15 [P1] The "+8.7% faster than fio" is attributed to optimisations fio also uses, and the real difference is not mentioned
*Added by the second-pass review.*

**Hypothesis:** README L337–344 credits JUring's advantage to registered files, DEFER_TASKRUN and registered-file handling. But:
- The fio command (`DirectLatencyBenchmark.java:816-831`) already passes `--registerfiles=1 --fixedbufs=1`, on a single file.
- Recent fio versions set `SINGLE_ISSUER`/`DEFER_TASKRUN`/`COOP_TASKRUN` themselves. *Unverified:* confirm with `strace -e trace=io_uring_setup fio …`.
- The unlisted difference is batching: the live fio run uses fio's defaults (`iodepth_batch_submit=1`, `iodepth_batch_complete_min=1`), while JUring batches at about 0.36 enters/op. That is the more likely source of the gap.

**Fix:** Run fio with the same batching as the "tuned fio" column (or compare with `t/io_uring`, see A5). Remove the causal explanation unless a profile backs it.

> **Second-opinion remark (verified on `treediagram`):**
> - Confirmed by disassembling `/usr/bin/fio` (`fio-3.40`, `objdump -d /usr/bin/fio` at `0x8e4f8` before `syscall(425)`): `fio` ORs `0x3108` into `params.flags` by default (`IORING_SETUP_CQSIZE (0x8) | IORING_SETUP_COOP_TASKRUN (0x100) | IORING_SETUP_SINGLE_ISSUER (0x1000) | IORING_SETUP_DEFER_TASKRUN (0x2000)`). Note that `strace` is not installed on `treediagram` (`objdump` or `/proc/<pid>/fdinfo/<ring_fd>` can be used instead).
> - Two genuine differences between JUring and `fio` beyond batching also exist in `/usr/bin/fio` (`fio-3.40`): (1) `fio` does **not** use `IORING_REGISTER_RING_FDS` (`IORING_ENTER_REGISTERED_RING = 1 << 4`), whereas JUring does; and (2) `fio_ioring_commit` (`0x8e191`) calls `io_uring_enter(to_submit, min_complete=0, IORING_ENTER_GETEVENTS)` and then `fio_ioring_getevents` (`0x8df77`) calls `io_uring_enter(0, min_complete, IORING_ENTER_GETEVENTS)` when CQEs aren't immediately ready, whereas JUring fuses submit+wait into a single `submitAndWaitForBatchResult(1, qd, consumer)` syscall.

### A16 [P1] The submission-latency explanation (2.4 µs vs 8.7 µs) is technically wrong
*Added by the second-pass review.*

**Hypothesis:**
- README L76/L329 say fio "mutates SQE structs in CPU stack memory". SQEs live in the shared mmap'd SQE array for both tools (`IoUringDispatcher.java:339-357`, `:845`).
- The README blames about 6 µs on "FFM downcall preparation", while its own FFM table (L277) shows about 6 ns per downcall, 1000 times smaller.
- JUring's slat (`DirectLatencyBenchmark.java:345-369`, `:379-388`) runs from preparing the first SQE of a refill batch until a separate `submit()` returns. That includes batching and the block-layer submit, so it is not the same metric as fio's slat.

**Fix:** Measure slat the way fio defines it, or report only clat/lat and drop the explanation. *Fourth pass: decided: drop slat and compare `lat` only (A19).*

### A17 [P2] JUringBlocking pollers run during the FileChannel virtual-thread benchmark
*Added by the second-pass review.*

**Hypothesis:** `VirtualThreadBenchmark.java:92-95` creates the `JUringBlocking` ring in shared Trial-level state. While the FileChannel benchmark runs, up to 4 idle pollers wake every 50 µs (`JUringBlocking.java:321-329`) and compete with FileChannel's carrier threads. This biases against FileChannel.

**Fix:** Give each benchmark method its own state, so the ring only exists while JUring is measured.

### A18 [P2] `bench.sh --pin N` changes the JVM configuration
*Added by the second-pass review.*

**Hypothesis:** README commands #4, #8 and #9 use `--pin 4`. With fewer CPUs visible, `availableProcessors()` drops, which changes GC selection (Serial GC at 1 CPU), JIT thread count, and the virtual-thread scheduler's parallelism. io-wq workers inherit the same affinity. Pinned and unpinned numbers are not comparable.

**Fix:** State in each table whether it was pinned. Pin with `-XX:ActiveProcessorCount` and explicit `-XX:+UseG1GC`, or pin only the benchmark thread.

### A19 [P1] The fio head-to-head runs in a hand-written harness, and the latency histograms are homemade
*Added by the fourth pass.*

**Hypothesis:**
1. The `--fio-live` numbers ("+8.7% faster than fio", "within 2.6% parity") don't come from JMH. `FioComparisonBenchmark.runDirectComparison` → `runJuringBench` → `runLoop` warms up for 1 s by wall clock, measures once for 3 s (default `--runtime`), in one JVM, with no error estimate and no check that the JIT has reached steady state. A reviewer will ask why the one comparison against native code is the one that skips JMH.
2. fio's output is read with `redirectErrorStream(true)`, so stderr warnings land inside the JSON, and then parsed with regexes (`parseFioJson`). The exit code is ignored (A4).
3. The JMH variant of the same benchmark records into histograms created in `@Setup(Level.Trial)` that are never reset (so they include warmup iterations) and never reported (only `BenchmarkSuiteValidationTest` reads them). With `telemetry=true` it pays for the timestamps and gets nothing back.
4. `DatasetManager.FioLatencyHistogram` is a home-written copy of fio's histogram. HdrHistogram is the standard Java tool for this, and its author is Azul's CTO.
5. `telemetry=true` doesn't only add timestamps: it adds a separate `submit()` per loop to time slat (A5 point 4), so telemetry ON and OFF measure different submission patterns.

**Evidence:** `DirectLatencyBenchmark.java`: `runDirectComparison`, `runJuringBench`, `runLoop`, `runNativeFio`, `parseFioJson`; `FioThreadState.setup` (histograms per trial); `juringDirectRead`/`juringDirectWrite` (`if (telemetry && queuedCount > 0) { ring.submit(); … }`); `DatasetManager.FioLatencyHistogram`.

**Fix:** see step 2.4 (harness) and step 2.12 (histograms).
- The JUring side of the comparison runs only through JMH (`FioComparisonBenchmark`, ≥3 forks, `-rf json`); the fio side through fio's own `--output=<file>.json`. `bench.sh` runs both, in random order, N times, writing every raw file to `results/<date>/`. The comparison table is generated from those files. Delete the Java harness and the regex parser.
- Drop slat (A16). Telemetry ON only takes a timestamp when an SQE is prepared and one when its CQE is reaped, so the submit/reap loop is identical with telemetry ON and OFF. Report that as `lat` and compare it with fio's `lat_ns` (run fio with `--lat_percentiles=1`).
- Record with HdrHistogram; reset each iteration; keep only measurement iterations; write the results to `results/`.

### A20 [P2] Closed-loop latency percentiles can be misread as latency under load
*Added by the fourth pass.*

**Hypothesis:** Every latency number is taken from a closed loop at a fixed queue depth: a new I/O is issued only when one completes. That is the same as fio, so the comparison is fair. But when the device stalls, the loop stops issuing, and the stall is recorded once instead of for every request that would have arrived during it (coordinated omission). These percentiles describe per-I/O completion time at QD=N, not what a client sending requests at a fixed rate would see. Azul's audience will look for exactly this.

**Fix:** Wording, not code. Label the numbers "per-I/O completion latency, closed loop at QD=N, comparable to fio's lat" and explain the limitation in the README's methodology section (phase 4). Build an open-loop mode (fixed arrival rate, latency measured from each I/O's intended start time, for JUring and FileChannel) only if the README is going to claim anything about latency under load.

---

## B. Library correctness

### B1 [P0] An exception in a `ResultConsumer` makes CQEs get processed twice (double free, double check-in)
**Hypothesis:** `reapAvailable` (`IoUringDispatcher.java:1166-1186`) and `reapRawCqes` (`:1211-1231`) call `populateResultFromCqe` and the consumer for each CQE, but only advance `cq.khead` once, after the loop. `populateResultFromCqe` has side effects: it `free()`s write and open buffers (`:1431`, `:1438`, `:1469`) and checks iovec blocks back into the pool (`:1462`, `:1471`, `:1476`). If the consumer throws at CQE k, CQEs 0..k-1 have already been processed, but the head has not moved. The next reap delivers them again, which leads to a double `free()` (heap corruption) and duplicate pool entries.

The benchmark consumers do throw on an unexpected `res`. Their teardown then reaps again and calls `checkInBuffer` twice, which overflows `freeBufferStack`.

**Fix:** Advance the head before invoking the consumer, either one CQE at a time (`setRelease(cqKhead, head + i + 1)`) or in a `try/finally` that publishes `head + processed`. Count a CQE as processed **before** calling the consumer: `populateResultFromCqe` has already freed its buffers, so a CQE that is delivered again after its consumer threw is itself a double free. Document that a throwing consumer loses only the CQE that threw.

### B2 [P0] Heap segments and closed arenas reach the kernel as raw addresses
**Hypothesis:** `JUringBlocking.read/write(.., MemorySegment buffer, ..)` (`JUringBlocking.java:618, 640, 662, …`) and `JUring.prepareWrite(.., MemorySegment, ..)` (`JUring.java:553`) pass `buffer.address()` with no check that `buffer.isNative()`. For `MemorySegment.ofArray(...)` or a segment wrapping a heap `ByteBuffer`, `address()` is an offset into the array, not a pointer, so the kernel reads from or writes to a bogus address. Nothing keeps the segment's arena alive while the I/O is in flight either. If an arena is closed (or an automatic arena is collected) before the CQE arrives, the kernel DMA-writes into freed memory. Reads also don't check `isReadOnly()`, so the kernel can write into a segment the caller made read-only.

This is the first thing Alan Bateman will check in an FFM I/O library.

*Third pass:* the earlier fix said "hold the segment's scope for the duration of the call". The public FFM API has no way to do that: `whileAlive` was removed after JDK 20, and `ScopedMemoryAccess` is JDK-internal. What can actually be done:
- **Confined arena:** already safe in `JUringBlocking`, because only the owner thread can close it, and that thread is blocked in the call.
- **Automatic arena:** the JIT may treat `buffer` as unreachable once `address()` has been read, so the GC can free it during the I/O. A `Reference.reachabilityFence(buffer)` in a `finally` after the wait fixes this.
- **Shared arena:** another thread can close it during the call, and nothing public prevents that. This has to be a documented contract.

The `prepareWriteFixed(…, MemorySegment, …)` overloads copy into the registered buffer, so heap segments are safe there. But their "skip the copy if `bytes.address() == targetAddr`" check compares a heap offset with a native pointer, so it needs `bytes.isNative() &&` in front.

**Fix:**
- Reject non-native segments at every entry point that passes the segment's address to the kernel: `if (!buffer.isNative()) throw new IllegalArgumentException(...)`. (Decided: no bounce buffer.) Entry points that copy (`byte[]`, `prepareWriteFixed(…, MemorySegment, …)`) are unchanged, apart from the `isNative()` guard on the address comparison.
- Reject `isReadOnly()` segments for reads.
- In `JUringBlocking`, add `Reference.reachabilityFence(buffer)` after the wait, and document that a shared arena must not be closed while a call that uses one of its segments is running.
- For the async `JUring`, document the lifetime contract clearly ("segment must stay alive and reachable until its CQE is reaped").

> **Second-opinion remark (critical FFM traps to avoid in B2 / Step 1.2):**
> 1. **`address()` and `byteSize()` do NOT check liveness or thread confinement in JDK 25:** Calling `buffer.address()` on an already-closed `Arena` segment returns the freed raw address without throwing! Every entry point that passes a `MemorySegment` to the kernel (and `prepareWriteFixed` before reading `bytes`) **must also check**:
>    - `if (!buffer.scope().isAlive()) throw new IllegalStateException("Segment scope is already closed");`
>    - `if (!buffer.isAccessibleBy(Thread.currentThread())) throw new WrongThreadException("Segment is confined to another thread");`
> 2. **Validate BEFORE `getSqe()`:** In `JUring.prepareWriteInternal(int, MemorySegment, ...)` (`:549`), `prepareWriteFixedInternal` (`:600`, `:621`), and `prepareOpen` (`:443`), `getSqe(...)` is currently called *before* validating/copying the arguments. Move all validation (`isNative()`, `scope().isAlive()`, `isAccessibleBy(...)`, `isReadOnly()`, bounds, and path encoding/NUL check) **before** `getSqe(...)` so a throwing call never advances `sqeTail` and leaves a zeroed phantom SQE in the SQ ring (the exact bug `BugVerificationTest.failedPrepareDoesNotLeavePhantomSqeAndValidatesBounds` tests).

### B3 [P1] Registered ring fds leak when a ring is used from more than one platform thread
**Hypothesis:** `IORING_REGISTER_RING_FDS` is per task. `submit` / `submitAndWait*` / `waitCqe` re-register the ring on any new thread (`fallbackOrReRegisterRingFd`, `:1009`). Only the registration belonging to the most recent thread is ever unregistered (`unregisterRingFd`, `:993-995` returns early otherwise). Each leaked registration holds a reference to the ring `file`. After `close()`, the ring, its mmaps, and any registered buffers (which count against RLIMIT_MEMLOCK) stay alive until each of those threads exits.

When work moves between pooled threads (A→B→A…), each switch registers the ring again on that thread and takes another of the 16 slots each thread has, until registration starts failing.

**Fix (decided):** Register only when the ring has `IORING_SETUP_SINGLE_ISSUER`, which already ties it to one thread. Otherwise always use the plain fd, and remove the re-register-on-thread-change path (`fallbackOrReRegisterRingFd`).

*Third pass:* the registration table is per task, so only the owner thread can unregister. A `SINGLE_ISSUER` ring closed from another thread still leaks until the owner thread exits (`unregisterRingFd` returns early). `JUringBlocking` is fine, because its rings are created on the poller threads, and those exit on close. For `JUring`, document that `close()` must run on the owner thread, as submission already must.

### B4 [P1] `open`/`close` are linked with `Linker.Option.critical(true)`
**Hypothesis:** `critical` is meant for short leaf functions that never block, because the thread stays in Java state and delays safepoints (and therefore GC) for every thread. `open(2)` and `close(2)` can block for milliseconds or longer: NFS, FUSE, path-lookup I/O on a cold dentry cache, or `close` flushing a file. `socket`, `bind`, `listen`, `setsockopt`, and `calloc` are marked critical too (`LibCDispatcher.java:69-80`). The README (around line 293) also says critical is good for "native Linux kernel symbols", which is the wrong advice. The `abs()` benchmark behind the 2.67 ns figure calls libc, not the kernel.

**Fix:**
- Link `open`, `close`, and the socket calls as normal downcalls.
- Because `open` currently relies on `critical` to accept a heap byte array, allocate the path off-heap (a confined arena, or `Arena.allocateFrom(String)`).
- Add `captureCallState("errno")` so `FileDescriptor` can report the real errno.
- Correct the README wording.

*Second pass:* `Linker.Option.critical(boolean)` takes `allowHeapAccess` as its argument; it is not an on/off switch. `link()` always passes `critical(critical)` (`LibCDispatcher.java:86-90`), so `strerror` and `munmap`, linked with `false` and apparently meant to be non-critical, are critical too. `malloc`, `calloc` and `free` also get heap access they don't need. `link()` needs a separate "critical or not" parameter.

### B5 [P1] Variadic `syscall()` is linked as if it were fixed-arity
**Hypothesis:** `long syscall(long, ...)` is variadic. `linkSyscallCaptureErrno` (`LibCDispatcher.java:99-103`) creates the downcall without `Linker.Option.firstVariadicArg(1)`. The FFM specification says variadic functions must be linked with that option; otherwise behaviour depends on the platform ABI. It happens to work on x86-64 and Linux aarch64 today, but it is undefined according to the specification, and an OpenJDK reviewer will flag it. The int arguments are also read by `syscall()` as `long` through va_arg.

**Fix:** Add `Linker.Option.firstVariadicArg(1)` and pass `JAVA_LONG` for every variadic argument (int-sized ones widened to long). Alternatively, link `io_uring_setup/enter/register` from liburing, or use the glibc wrappers if they exist.

### B6 [P1] JUringBlocking can free a buffer the kernel is still using
**Hypothesis:** In `submitAndAwaitSlot` (`JUringBlocking.java:498-532`), if `running` becomes false while an I/O is still in flight, the waiter throws. Its `finally` block then frees `slotFreeAddr` (the copied `byte[]` write buffer) and releases the slot, but the SQE may still be in the kernel. The kernel then reads freed memory, and the slot can be handed out again while a CQE for it is still pending.

**Fix:** `close()` should stop accepting new work, wait for (or cancel and then wait for) every in-flight CQE, and only then free buffers and wake the waiters. Do not free `slotFreeAddr` on the waiter's exception path.

### B7 [P1] JUringBlocking poller under DEFER_TASKRUN delays completions until the SQ fills
**Hypothesis:** In `pollerLoop` (`JUringBlocking.java:295-303`), when new SQEs keep arriving (`preparedNow > 0`) and the SQ is not full, the poller only calls `dispatcher.submit()`, which does not set GETEVENTS. With `IORING_SETUP_DEFER_TASKRUN`, which `VirtualThreadBenchmark` uses, completions are not posted until an enter with GETEVENTS. As long as submissions keep coming, CQEs pile up in the kernel until in-flight reaches `sqEntries`. That inflates latency and makes the effective queue depth larger than intended.

**Fix:** When DEFER_TASKRUN is set, always submit with `IORING_ENTER_GETEVENTS` and `min_complete=0` (`submitAndWait(0)`). Otherwise, call `flushDeferredTaskrun()` whenever `reaped == 0 && totalInFlight > 0`. (The "unbounded" branch that does this today only exists for socket ops and goes away in step 0.2.)

> **Second-opinion remark (trap in `IoUringDispatcher.submitAndWait(0)`):** In `IoUringDispatcher.java:619-623`, `submitAndWait(int waitNr)` currently returns early if `toSubmit == 0 && waitNr == 0`, and computes `int enterFlags = (waitNr > 0 && available < waitNr) ? IORING_ENTER_GETEVENTS : 0;`! So calling `submitAndWait(0)` today does **not** pass `IORING_ENTER_GETEVENTS`! When implementing B7 / Step 1.3, either update `submitAndWait` so `isDeferTaskrun` sets `IORING_ENTER_GETEVENTS` even when `waitNr == 0`, or add a dedicated `submitAndFlushTaskrun()` method that passes `IORING_ENTER_GETEVENTS` with `min_complete=0`.

### B8 [P2] `submit()` can busy-loop on EAGAIN/EBUSY
**Hypothesis:** `submit()` (`IoUringDispatcher.java:554-561`) retries `io_uring_enter` immediately on EAGAIN or EBUSY and never reaps. If the CQ has overflowed (the caller has more than `cq_entries` in flight without reaping) and the kernel returns EBUSY, the calling thread spins at 100% CPU and never recovers. `getSqe()` only checks SQ space, so nothing stops a user from submitting more than the CQ can hold.

*Second pass:* the same retry loop is in `submitAndWait` (`:655-659`) and `submitAndWaitForBatchResult` (`:1362-1366`).

**Fix:** In all three places, on EBUSY, return the count submitted so far (or throw a clear "reap completions first" exception) instead of looping. Optionally track in-flight count and reject once it reaches `cq_entries`.

> **Second-opinion remark:** In `submitAndWait` (`:619`) and `submitAndWaitForBatchResult` (`:1326`), the code currently skips reaping existing CQEs before `io_uring_enter` whenever `toSubmit > 0` (`if (available >= minComplete && toSubmit == 0)`). If the CQ ring is already full (`EBUSY`) and `toSubmit > 0`, `submitAndWaitForBatchResult` should reap available CQEs first (or on `EBUSY`) so callers using the fused submit+reap loop can drain the full CQ ring and recover automatically.

### B9 [P2] Smaller API footguns
- `JUring.checkInBuffer` (`JUring.java:974`) has no bounds check and no double-check-in check. Checking the same index in twice gives two in-flight I/Os the same buffer.
- Calling `registerBuffers` twice does not unregister the previous set, and the kernel returns EBUSY.
- `FileDescriptor` reports `fd=-1` instead of the errno, and does not set `O_CLOEXEC`.
- `ZeroGcUserData` keeps 56 bits of the pointer, which drops aarch64 MTE/TBI pointer tags. A `free()` on the untagged pointer would then fault on MTE-enabled glibc. Document "x86-64 / untagged aarch64 only", or keep the pointer in a side table indexed by an op id.

> **Second-opinion remark (`O_CLOEXEC` vs `openDirect` kernel trap):** Adding `O_CLOEXEC` (`0x80000`) in `FileDescriptor` (`LibCDispatcher.open`), `JUring.prepareOpen`, and `JUringBlocking.open` is right, **but do NOT pass `O_CLOEXEC` in `prepareOpenDirect` / `openDirect`!** In Linux (`io_openat_prep` in `fs/io_uring.c` / `io_uring/openclose.c`), `IORING_OP_OPENAT` into a fixed file slot (`file_index != 0`) explicitly rejects `O_CLOEXEC` with `-EINVAL` because fixed files do not live in the POSIX fd table and are not inherited across `execve`. Strip `flags & ~O_CLOEXEC` in `prepareOpenDirect` / `openDirect` if the user or `LinuxOpenOptions` sets `O_CLOEXEC`.

### B10 [P1] SQPOLL is missing a StoreLoad fence, which can lose a wakeup and hang
*Added by the second-pass review.*

**Hypothesis:** The SQ tail is written with `setRelease` (`IoUringDispatcher.java:510`) and the SQ flags are read with `getVolatile` (`:526`). A release store followed by a volatile load is not ordered (StoreLoad), and on x86 both are plain moves, so the store buffer can reorder them. If the SQ thread sets `NEED_WAKEUP` after seeing an empty ring, JUring reads the old flags, skips `io_uring_enter`, and the submission waits indefinitely. liburing puts `io_uring_smp_mb()` exactly here. The same pattern is at `:608`→`:627` and `:1315`→`:1334`.

The benchmarks don't use SQPOLL, but `IORING_SETUP_SQPOLL` is a public option and no test uses it.

**Fix:** When SQPOLL is set, call `VarHandle.fullFence()` between the tail store and the flags load. Add a test with SQPOLL and an idle period longer than `sq_thread_idle`.

### B11 [P1] The JUringBlocking poller can't be woken while it is blocked in `io_uring_enter`
*Added by the second-pass review.*

**Hypothesis:** When the poller has nothing new and reaped nothing, it calls `dispatcher.submitAndWait(1)` (`JUringBlocking.java:298-305`). `pollerParked` is only set around `parkNanos` (`:313-318`, `:323-328`), and `wakePoller` (`:386-391`) only unparks when that flag is set. `unpark` can't interrupt a syscall anyway. A virtual thread that queues a request while the poller is in the kernel waits for an unrelated I/O to finish (about 80 µs on NVMe) before its SQE is even submitted. This adds latency at low and medium VT counts (the 50 and 100 VT rows).

**Fix (decided):** eventfd. The poller keeps an `IORING_OP_READ` armed on an eventfd (link `eventfd` and `write` in `LibCDispatcher`). Before blocking, it publishes a "in kernel" flag; producers that enqueue work while the flag is set `write()` 1 to the eventfd. Use a full fence between "store flag / load queue" on the poller side and "store queue / load flag" on the producer side, or wakeups get lost. The eventfd CQE is recognised by its user_data and re-armed, never handed to a waiter.

> **Second-opinion remark (`eventfd` implementation details for B11 / Step 1.3):**
> 1. **`eventfd` reads/writes must be 8 bytes (`uint64_t`):** `eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK)` requires an 8-byte buffer (`sizeof(uint64_t) == 8`) for both `IORING_OP_READ` and `write(2)`; smaller buffers fail with `-EINVAL`.
> 2. **Reserve 1 SQE slot for the `eventfd` read:** Do not allow user submissions from the MPSC queue to consume the last SQE slot (`userInFlight < sqEntries - 1` or reserve 1 SQE when re-arming), otherwise a full burst of user ops leaves `getSqe() == 0L` when the poller needs to re-arm the `eventfd` (or issue `IORING_OP_ASYNC_CANCEL` on close). Also track `userInFlight` separately from the armed `eventfd` SQE.
> 3. **Coalesce `write(eventfd)` via CAS:** In `wakePoller()`, transition the `inKernel` state with `compareAndSet(1, 0)` before calling `write(eventfd)` so that 1,000 virtual threads enqueuing work concurrently issue at most **one** `write(2)` syscall per poller wakeup instead of 1,000 syscalls.

### B12 [P1] `JUring.close()` frees library-owned buffers while I/O is still in flight
*Added by the second-pass review. This is the async-API version of B6.*

**Hypothesis:** `IoUringDispatcher.close()` (`:1524-1535`) closes the ring fd and then `arena.close()`. That frees the registered-buffer slab, the iovec pool and (via `JUring.close()`) the `ReadBufferPool`. Nothing cancels or drains in-flight requests first. The kernel completes in-flight requests asynchronously after the fd is closed, so it can DMA into memory that malloc has already handed to someone else.

**Fix:** In `close()`, submit `IORING_OP_ASYNC_CANCEL` with `IORING_ASYNC_CANCEL_ANY`, reap until in-flight is 0, then free. Same mechanism as B6, so implement it once in `IoUringDispatcher`.

*Third pass:* the drain has to run on the ring's submitter thread. With `SINGLE_ISSUER`, a submit from any other thread fails with `EEXIST`. With `DEFER_TASKRUN`, only the submitter's `GETEVENTS` enter posts the completions. In `JUringBlocking`, `close()` is called from a user thread while the poller may be blocked in `io_uring_enter`, so `close()` has to wake the poller (the eventfd from B11) and let the poller do the drain before it exits. Today `close()` sets `running = false`, joins the poller, and closes the dispatcher from the user thread.

> **Second-opinion remark (two traps in B12 / Step 1.8):**
> 1. **`IORING_ASYNC_CANCEL_ANY` alone only cancels ONE request:** In `<linux/io_uring.h>`, `IORING_ASYNC_CANCEL_ANY` (`1 << 2`) without `IORING_ASYNC_CANCEL_ALL` (`1 << 0`) cancels only the **first** matching request found! To cancel all in-flight requests, set `cancel_flags` (`sqe.msg_ring_flags` / offset 28) to `IORING_ASYNC_CANCEL_ANY | IORING_ASYNC_CANCEL_ALL` (`(1 << 2) | (1 << 0) = 5`).
> 2. **Freeing read buffers during drain & unsubmitted SQEs:** In `IoUringDispatcher.populateResultFromCqe`, `WRITE`/`OPEN` malloc buffers and `READV_FIXED`/`WRITEV*` iovec blocks are freed automatically, **but `READ`, `READV`, and `READ_POOLED` buffers are NOT freed by `populateResultFromCqe`** (they are placed on `Result` for the caller to close). Therefore, the drain consumer in `close()` **must call `result.close()`** (and return `READ_POOLED` buffers to `ReadBufferPool`), and any prepared-but-unsubmitted SQEs (`sqeHead != sqeTail`) must either be submitted before `ASYNC_CANCEL` or have their buffers freed before closing the arena/pools.

### B13 [P1] The public API lets code without native access read, write and free arbitrary memory (JEP 472)
*Added by the second-pass review.*

**Hypothesis:** The exported package contains:
- public `ZeroGcSqe.zero/setOpcode/...(long addr)`, which write through `GLOBAL_MEMORY = ofAddress(0).reinterpret(Long.MAX_VALUE)` (`ZeroGcSqe.java:51,58-75`);
- `Result` with a public constructor and `set(..., long bufferAddress, ...)`, whose `buffer()` and `freeBuffer()` reinterpret or `free()` that address (`Result.java:27-56,95-110,209-223`);
- `IoUringDispatcher` with a public constructor.

*Third pass, more holes of the same kind:*
- `Iovec`, `io_uring_params`, `io_sqring_offsets` and `io_cqring_offsets` are public jextract-style classes. Each has a public `reinterpret(MemorySegment addr, Arena, Consumer)` that calls the restricted `MemorySegment.reinterpret` on the caller's behalf, which turns any address into a usable segment. They are used only inside the package (`IoUringDispatcher`, `LibCDispatcher`).
- `JUring.checkInReadBuffer(long address)` puts any address into the `ReadBufferPool`. The pool later either `free()`s it or hands it to the kernel as a read target.

So `new Result(0, 4096, READ, anyAddr, …).freeBuffer()` works from a module that was never granted native access. JEP 472's whole point is that this needs `--enable-native-access`. This is Alan Bateman's area. The README also never mentions `--enable-native-access`.

**Fix (decided):** Make the `ZeroGc*` classes, `IoUringDispatcher`, `Iovec`, `io_uring_params`, `io_sqring_offsets` and `io_cqring_offsets` package-private. Replace `checkInReadBuffer(long)` with an overload that takes the `Result` the buffer came from, or make it package-private and return pooled buffers from `Result.close()`. In `Result`, make the full constructor and both `set(...)` overloads package-private; keep the public no-arg and copy constructors, because `DirectLatencyBenchmark` uses `new Result()` (an empty `Result` is harmless once `set` is hidden). No package moves; `module-info.java` already exports only `com.davidvlijmincx.lio.api`. Document `--enable-native-access=<module>` / `ALL-UNNAMED` and the `Enable-Native-Access` manifest attribute.

> **Second-opinion remark (callers to update for B13 / Step 1.4):**
> - Don't miss `ZeroGcCqe` (`src/main/java/com/davidvlijmincx/lio/api/ZeroGcCqe.java:14`), which is also `public` today alongside `ZeroGcSqe`, `ZeroGcUserData`, and `ZeroGcIovecBlock`. (`ReadBufferPool`, `IovecBlockPool`, `LibCDispatcher`, and `NativeDispatcher` are already package-private; `com.davidvlijmincx.lio.api.functions.*` is not exported by `module-info.java`.)
> - When replacing `JUring.checkInReadBuffer(long)` or moving pooled-buffer check-in into `Result.close()`, update the existing callers in `src/test/java/com/davidvlijmincx/lio/api/JUringTest.java` and `src/test/java/com/davidvlijmincx/lio/api/Phase1BugProofTest.java:61` (`src/test/java/bench/random/read/ReadBufferPoolBenchmark.java` also calls it and is deleted in Step 2.13; if Step 1.4 runs before Step 2.13, update it in Step 1.4 so `mvn test-compile` keeps passing at every commit).

---

## C. Release hygiene

### C1 [P2] Unresolved merge state and IDE files are in the index
*Third pass:* the merge conflict on `.idea/misc.xml` has already been resolved; `git status` no longer shows it. `.idea/encodings.xml`, `misc.xml`, `vcs.xml` and `workspace.xml` are still tracked, and `.gitignore` lists only some `.idea` files. Run `git rm --cached` on the tracked `.idea/` files and replace the per-file `.idea` lines in `.gitignore` with `.idea/`.

### C2 [P2] The branch name and the code disagree
The branch is `remove-network-sockets`, but socket, accept, connect, send, and recv code is still in `JUring`, `JUringBlocking`, `IoUringDispatcher`, and `LibCDispatcher`. `SocketTest` is excluded in the surefire configuration. **Decided:** remove the socket support, `SocketTest`, and its surefire exclusion.

> **Second-opinion remark (complete file list for C2 / Step 0.2 so `mvn test` and `grep -ri socket src/` pass):**
> Besides `JUring`, `JUringBlocking`, `IoUringDispatcher`, `LibCDispatcher`, and `SocketTest`, socket symbols/tests also exist in:
> 1. `src/main/java/com/davidvlijmincx/lio/api/functions/{Socket,Bind,Listen,SetSockOpt}.java` — delete all four files.
> 2. `src/main/java/com/davidvlijmincx/lio/api/OperationType.java` — remove `ACCEPT`, `CONNECT`, `RECV`, `SEND`, `SEND_EXT`, `RECV_EXT`, `MULTISHOT_ACCEPT` and renumber the remaining enum constants (`0..12`) so `VALUES[index]` stays contiguous!
> 3. `src/main/java/com/davidvlijmincx/lio/api/Result.java` — remove `bytesSent()` (`:145`) and `OperationType.RECV` from `ownsMallocBuffer()` (`:228`).
> 4. `src/test/java/com/davidvlijmincx/lio/api/Phase1BugProofTest.java` — delete `proof5_multishotAcceptPreservesCqeFlagsMore` (`:166-196`) and `import java.net.Socket`.
> 5. `src/test/java/com/davidvlijmincx/lio/api/BugVerificationTest.java` — delete `jUringBlockingDoesNotDeadlockWithFullSocketWaitersOrDeferTaskrun` and `createLoopbackPair` (`:171-250`, or rewrite its `DEFER_TASKRUN` portion using file I/O), and remove the `prepareConnect`/`prepareRecv`/`prepareSend` assertions from `failedPrepareDoesNotLeavePhantomSqeAndValidatesBounds` (`:292-302`).
> 6. `src/test/java/com/davidvlijmincx/lio/api/Phase2VirtualThreadProofTest.java` — delete `proof3_virtualThreadSocketEchoServerAndClients` (`:92-153`) and update the class javadoc (`:22`).
> 7. `src/test/java/com/davidvlijmincx/lio/api/ZeroGcSqeTest.java` — replace `OperationType.MULTISHOT_ACCEPT` in `testZeroGcUserDataEdgeCases` (`:105`) with a file `OperationType` (e.g., `OperationType.NOP`).

### C3 [P2] README claims to re-check after the fixes above
The following README claims should be re-measured or reworded before posting:
- "up to 31x / 26.2x / 6.1x faster than FileChannel" (A1)
- "slashing kernel CPU time by 88.9%" (A2)
- "11.9 ns user CPU per op" (A3)
- "+8.7% faster than fio" (A4, A5, A10)
- "pwrite ~33% faster for buffered writes" (A7)
- "faster than JNI" for `critical` downcalls (B4)
- "+87% / 1.87x over FileChannel at 1000 VTs" (A9)
- "fio crashes with EMFILE; registered files bypass ulimit" (A13)
- "64.1x / 16.3x fewer syscalls" and FileChannel "1.0000 enters/op" (A14)
- Why JUring beats fio, and the slat explanation (A15, A16)
- Loom carrier compensation description (C4)
- "Formally verified" memory barriers (C5)
- License, "Java 22+", "Kernel 5.1+" (C6)
- "PCIe 4.0 x4 bus limit" and the other contradictions in C8

### C4 [P1] The README's Loom explanation is wrong for ordinary buffered file I/O
*Added by the second-pass review; checked against JDK 25 sources.*

**Hypothesis:** The README (L11, L49–57) and the `JUringBlocking.java:21-27` javadoc describe carrier compensation as what happens for all `FileChannel` I/O on virtual threads. In JDK 25, `FileChannelImpl` calls `Blocker.begin(direct)` on reads (lines 249, 302, 1196) and `Blocker.begin(sync || direct)` on writes (353, 405, 1259), and `Blocker.begin(false)` returns without compensating. So compensation only happens for O_DIRECT (and O_SYNC/O_DSYNC writes). A buffered `FileChannel.read` on a virtual thread blocks its carrier and the pool does not grow. The cap is `jdk.virtualThreadScheduler.maxPoolSize`, default `max(parallelism, 256)`. Alan Bateman wrote this code path.

**Fix:** Say that compensation applies only to direct and sync I/O, up to `maxPoolSize`, and that the virtual-thread benchmark uses DIRECT. Update A9 accordingly.

### C5 [P1] The "formally verified" JCStress tests don't test JUring code
*Added by the second-pass review.*

**Hypothesis:** Every actor in `WakePollerStoreLoadTest.java` (`:44-62`, `:82-100`, `:131-155`, `:184-202`) re-implements the pattern on the test's own fields; no JUring class is referenced. The SQ/CQ tests stand in for the kernel with a Java thread, which proves nothing about kernel/JVM ordering. JCStress is stress testing, not formal verification. The tests also miss the real missing fence (B10), and the pom has no way to run them. README L87 and L619–620 rely on these tests.

**Fix:** Reword to "JCStress stress tests of the handshake pattern". Drive the real `JUringBlocking`/`IoUringDispatcher` code through package-private hooks. The tests are in `com.davidvlijmincx.lio.jcstress`, so they have to move to `com.davidvlijmincx.lio.api` (test sources) to reach those hooks, especially after B13. Add a documented command to run them. The pom only has the JCStress annotation processor; nothing runs the tests.

### C6 [P1] License, Java version and kernel version claims don't match the code
*Added by the second-pass review.*

- **License:** `LICENSE` is the Unlicense; the README badge (L3) and L701 say Apache-2.0.
- **Java:** the README says "Java 22+" (L4, L674). `pom.xml:51-52` compiles with source/target 25, and `LibCDispatcher.java:88,94,100` use `SymbolLookup.findOrThrow` (`@since 23`).
- **Kernel:** the README says "Kernel 5.1+" (L5, L673). `IORING_OP_READ/WRITE/OPENAT/CLOSE` need 5.6; the documented flags need 6.0 (`SINGLE_ISSUER`) and 6.1 (`DEFER_TASKRUN`). There's no `IORING_REGISTER_PROBE` check, so an old kernel fails with an obscure errno.
- **Dev container:** `.devcontainer/Dockerfile` uses JDK 24 EA, so the project can't build there.

**Fix:** Keep the Unlicense (decided) and fix the README badge and footer. Use `<maven.compiler.release>25</maven.compiler.release>` and say "JDK 25+". Say "Linux 6.1+ for the documented setup (5.6 minimum)" and probe opcodes at ring creation. Update the dev container to JDK 25.

### C7 [P1] `mvn test` fails on every machine except the author's, and there's no CI
*Added by the second-pass review. Related to A12, but this is the first thing a Reddit reader runs.*

**Hypothesis:** Surefire only excludes `SocketTest` (`pom.xml:86-88`). `BenchmarkSuiteValidationTest` (14 tests) builds a `DatasetManager` over `/home/david/testData`, which throws (`DatasetManager.java:94-96`), and `:489-494` tries to write a 10 GB file (`DatasetManager.java:112-141,337`). No test is restricted to Linux.

**Fix:** Move benchmark-validation tests behind a JUnit tag or Maven profile. Add `@EnabledOnOs(LINUX)` to io_uring tests. Add a GitHub Actions workflow (ubuntu-latest has kernel 6.x) that runs `mvn verify`.

### C8 [P1] The README contradicts itself
*Added by the second-pass review.*

- L83: one JUring thread beats 8–16 `FileChannel` threads. L161: `FileChannel` with 16 write threads does 578,619 ops/s against JUring's single-thread 441,522.
- Single-thread QD64 read: 416,213 (L117), 324,606 (L83, L133), 352–385k (L72, L307).
- QD=1 median latency: 82.8 µs (L250) against about 64.2 µs (L77, L261, L330).
- ~2.8 GB/s is called the PCIe 4.0 x4 bus limit (L74, L147, L313). That bus carries about 7.9 GB/s; at 4 KiB, the ceiling is the drive's random-IOPS limit, not the bus.
- 0.05–0.12 enters/op (L470, L614) against 0.3626 (L117).
- "114/114 JUnit" (L87) against "16 JUnit" (L620).
- FFM table method names (`methodHandleProxies_standard`, `instanceField_extractedFromRecord`) don't exist in `FfmInvocationBenchmark`.

**Fix:** Generate README tables from the JMH/fio JSON of one publication run (plan phase 3), and state every number once.

### C9 [P2] Tests that would pass even if the code were broken
*Added by the second-pass review.*

- `ZeroGcSqeTest.java:49-81` only round-trips values through the same layout, so it would pass with wrong offsets. (The SQE layout itself was checked against the kernel uapi and is correct today.)
- `JUringBlockingTest` uses qd=10, which gives one poller; the multi-poller path only runs on machines with ≥8 CPUs (`defaultWorkerCount`, `:557-569`).

**Fix:** Assert absolute offsets and sizes (SQE 64 bytes with the uapi offsets, CQE 16 bytes, `io_uring_params` 120 bytes). Add a `JUringBlocking` test that forces several pollers.

### C10 [P2] "Lock-free MPSC" is the wrong term
*Added by the second-pass review.*

A producer preempted between `getAndAdd` (`JUringBlocking.java:487`) and `setRelease` (`:493`) stops the poller from consuming any later entry (`:253-258`). That makes it blocking in the progress-guarantee sense. Call it a "bounded MPSC queue (Vyukov-style)".

### C11 [P2] Smaller documentation and API issues
*Added by the second-pass review.*

- README example 4 never closes its `Result`, so it leaks the read buffer; `allocateFrom` writes the trailing NUL to the file; the WRITE example has no CREATE flag.
- The public `JUringBlocking.timeout` field and constructor argument are unused (`:71`, `:588`).
- Nothing documents that `JUring` is not thread-safe (`sqeTail`, `opSeq`, `freeBufferStack` are plain fields).
- Paths are encoded with `String.getBytes()` and not checked for embedded NUL (`JUring.prepareOpen`, `JUringBlocking.open`, `LibCDispatcher.open`). The JDK uses `sun.jnu.encoding` and rejects NUL.

---
### C12 [P2] Code that prints performance numbers but isn't a benchmark
*Added by the fourth pass.*

**Hypothesis:**
- `DirectReadVisualVerificationTest` is a valuable correctness check (it poisons buffers and verifies every block byte-for-byte against the file), but it also prints wall time, MB/s and IOPS from `System.nanoTime()`, and `BenchmarkRunner --visual-read` launches it next to the real benchmarks. Its numbers will end up quoted next to the JMH ones.
- `src/test/java/bench/random/` and `src/test/java/bench/ExecutionPlanBlocking.java` / `ExecutionPlanJUring.java` are an older, second JMH suite. Nothing in `bench/suite`, `bench.sh` or the README uses them; only the old plan files link to them. A reviewer has to work out which suite produced the published numbers.

**Fix:** step 2.13.

## D. Fix plan

The order matters. Library fixes change the hot path (B1, B7, B11), and benchmark fixes change what is measured, so all code changes land **before** one re-measurement campaign. Re-measuring after each fix wastes machine time and gives a README with numbers from different builds.

### Phase 0 — Repository and packaging (≈half a day)
| Step | Items | Change | Done when | Status |
|---|---|---|---|---|
| 0.1 | C1 | Untrack `.idea/` (the `misc.xml` conflict is already resolved); put `.idea/` in `.gitignore`. Delete `hypothesis.md` and `BENCHMARK_VERIFICATION_AND_SCALING_PLAN.md`. `BENCHMARK_FINDINGS.md` and `BENCHMARK_PLAN.md` probably repeat claims this review disputes: ask the maintainer before touching them. | `git status` shows only real changes; the two old plan files are gone. | |
| 0.2 | C2 | Remove the socket support (decided): socket ops from `JUring`, `JUringBlocking`, `IoUringDispatcher`; socket/bind/listen/setsockopt and `SOCKADDR_IN_LAYOUT`/`AF_INET`/`SO_*` from `LibCDispatcher`; the "unbounded op" path in `JUringBlocking.pollerLoop` (`isUnboundedOp`, `unboundedInFlight`, and the branch that parks with I/O in flight), which only exists for accept/recv/connect/send; delete `SocketTest` and its surefire exclusion; remove socket examples from the README. | `grep -ri socket src/` finds nothing socket-related; `pollerLoop` has no unbounded-op branch; `mvn test` passes with no exclusions. | |
| 0.3 | C6 | Keep the Unlicense; fix the README badge and footer. `maven.compiler.release=25`; README says JDK 25+ and Linux 6.1+ (5.6 minimum); dev container on JDK 25. | README header matches `pom.xml` and `LICENSE`. | |
| 0.4 | C7, A12 | Data directory from a system property/env var; benchmark tests behind a tag/profile; `@EnabledOnOs(LINUX)`; GitHub Actions running `mvn verify`. | `mvn verify` passes from a fresh clone with no data directory; workflow file is committed (it goes green once pushed). | |

### Phase 1 — Library correctness (before anyone reads the code)
Each step needs a regression test that fails before the fix.

| Step | Items | Change | Regression test | Status |
|---|---|---|---|---|
| 1.1 | B1 | Publish `cq.khead` in a `finally` with `head + processed` in `reapAvailable` / `reapRawCqes` (one release store per batch, same hot-path cost). A CQE counts as processed before its consumer is called. | Consumer throws on the 3rd of 8 WRITE(byte[]) CQEs; the next reap returns only the remaining 5; `free` count equals `malloc` count (package-private counter, not a real double free). | done be781bd |
| 1.2 | B2 | Reject `!segment.isNative()` with `IllegalArgumentException` at every entry point that passes the segment's address to the kernel; reject read-only segments for reads; `isNative()` guard on the address comparison in `prepareWriteFixed(…, MemorySegment, …)`; `Reference.reachabilityFence(buffer)` after the wait in `JUringBlocking`; document the lifetime contract (shared arenas, async `JUring`). | Heap segment → `IllegalArgumentException`; read-only segment on a read → `IllegalArgumentException`; heap segment on `prepareWriteFixed(…, MemorySegment, …)` still works (copied). | done 25e860b |
| 1.3 | B7, B11 | **Design stop first** (one note together with 1.8). Poller: with DEFER_TASKRUN, submit with GETEVENTS (`min_complete=0`); block on an armed eventfd read that producers signal (see B11), instead of bare `submitAndWait(1)`. | One VT doing sequential reads while another floods: p99 doesn't grow with the flood; single-VT latency with no other I/O ≈ device latency; a VT that submits while the poller is in the kernel completes without waiting for unrelated I/O. | done 8852536 |
| 1.4 | B13 | Make `ZeroGcSqe`/`ZeroGcCqe`/`ZeroGcUserData`/`ZeroGcIovecBlock`, `IoUringDispatcher`, `Iovec`, `io_uring_params`, `io_sqring_offsets`, `io_cqring_offsets` package-private; `Result`'s full constructor and `set(...)` package-private, no-arg and copy constructors stay public; replace `JUring.checkInReadBuffer(long)` (see B13). Document `--enable-native-access`. | A reflection test lists the public classes and the public methods/constructors that take a `long` address in the exported package, and checks them against an allowlist; benchmarks still compile. (A "can't compile" test can't be part of the suite.) | done cd3f8ba |
| 1.5 | B3 | Register the ring fd only with `SINGLE_ISSUER`; otherwise always use the plain fd. Remove the re-register-on-thread-change path. Document that `JUring.close()` must run on the owner thread. | Non-single-issuer ring used from two pool threads: no registration happens, and after `close()` `/proc/self/fd` no longer holds the ring. Single-issuer ring: registers once, unregisters on close on the owner thread. | done 46f3fba |
| 1.6 | B4, B5 | `link()` takes "critical or not" separately from `allowHeapAccess`; `open`/`close`/`strerror`/`munmap` as normal downcalls with `captureCallState("errno")`; path allocated off-heap; `syscall` with `firstVariadicArg(1)` and `JAVA_LONG` variadic args. (Socket calls are gone after 0.2.) | `FileDescriptor` on a missing file reports `ENOENT`. | done 91bd1fd |
| 1.7 | B10 | `VarHandle.fullFence()` between SQ tail store and flags load when SQPOLL is set (three places). | SQPOLL test with an idle period longer than `sq_thread_idle`, repeated; never hangs. | done f7e9f03 |
| 1.8 | B6, B12 | **Design stop first** (same note as 1.3). One drain routine in `IoUringDispatcher`: stop accepting work, `ASYNC_CANCEL` (ANY), reap until in-flight is 0, then free and wake waiters. It runs on the ring's submitter thread: from `JUring.close()` directly, and for `JUringBlocking.close()` on each poller, which `close()` wakes through the eventfd from 1.3. Waiter exception paths never free `slotFreeAddr`. | Close while 1000 VTs (blocking) or 128 SQEs (async) are in flight: no crash, every waiter gets a result or exception, allocation counts balance; `close()` returns while the poller was blocked in `io_uring_enter`. | done 1876d17 |
| 1.9 | B8, B9, C11 | EBUSY returns/throws instead of spinning (`submit`, `submitAndWait`, `submitAndWaitForBatchResult`); `checkInBuffer` bounds + double check-in; `registerBuffers` twice throws; `O_CLOEXEC`; errno in `FileDescriptor`; NUL check and `sun.jnu.encoding` for paths; remove unused `timeout`; document thread-safety and the 56-bit pointer limit. | Unit test per item. | done a3c8e82 |
| 1.10 | C6 | Probe required opcodes/features at ring creation with `IORING_REGISTER_PROBE`; fail with a clear message. | Test with a flag the kernel doesn't support → readable exception. | done f0ebec5 |
| 1.11 | C9, C5 | Absolute layout assertions (SQE/CQE/params); force several pollers in `JUringBlockingTest`; JCStress tests that drive the real classes (including the eventfd handshake from 1.3), moved into the `com.davidvlijmincx.lio.api` test package, plus a run command in the README's build section or `pom.xml` profile. | The new tests fail if an offset or the B10 fence is changed. | |

After phase 1: full test suite + JCStress on the benchmark machine. One smoke run of the benchmarks, not for publication.

### Phase 2 — Benchmark harness (all before any publication run)
| Step | Items | Change | Done when | Status |
|---|---|---|---|---|
| 2.1 | A1 | Split the state so `fileChannelDirectRead` has no `qd`; make sure N-thread FileChannel vs one JUring thread at QD=N exists for read and write; add the QD=1 vs QD=1 row. | A short JMH run lists the FileChannel read row once; equal-concurrency read and write benchmarks exist; a QD=1 vs QD=1 pair exists. | |
| 2.2 | A2, A3, A10 | **CPU per op comes from `perfnorm`, not from inside the JVM** (*fourth pass*; replaces "`RUSAGE_SELF` in `CpuAuxCounters`", which over-counts N times with `@Threads(N)`, see A2). Delete `CpuAuxCounters` and its `begin()`/`end()` calls from every benchmark. In `BenchmarkRunner`, make `--perfnorm` pass `events=task-clock,cycles,instructions,cycles:u,cycles:k` and print `task-clock`/op (total CPU, all threads), `cycles:u`/op and `cycles:k`/op in the summary table in place of `user ns/op` / `sys ns/op`. Keep the existing genuine-`perf` check, and also fail fast if `/proc/sys/kernel/perf_event_paranoid` > 1 (kernel events would be silently dropped). Remove the hard-coded `bytesRate / 4096.0` op count in the summary (`BenchmarkRunner`, `denomOps`); use the primary score. **Verify first:** run the buffered write benchmark, check for `iou-wrk` threads (`ps -L`), and check that `perfnorm` counts their CPU; record the result, kernel and filesystem in the commit. | No `CpuAuxCounters` and no `getrusage` call in any `@Benchmark` method; a short `--perfnorm` run of `VirtualThreadBenchmark` shows non-zero `task-clock`/op for both sides, larger than the old harness-thread number; the runner refuses to run `--perfnorm` with `perf_event_paranoid` > 1. | |
| 2.3 | A14, A10 | **Syscalls per op come from `perf` tracepoints, not from counters in the code** (*fourth pass*; replaces `takeEnterCount()`). Delete the `syscalls` field from `StorageAuxCounters` and every local syscall/enter count (`submitsIssued`, `enters`, the `countedEnters > 0 ? … : …` fallbacks, the FileChannel `record(totalTasks, …)` assumption). Remove `-Djuring.countEnters` from all fork `jvmArgs`, `bench.sh` and `BenchmarkRunner`, then remove `COUNT_ENTERS`, `enterCount` and `takeEnterCount()` from `IoUringDispatcher` (and their callers in `JUring`/`JUringBlocking`). Add a counting mode (`--syscalls` in `BenchmarkRunner` and `bench.sh`) that runs `perfnorm` with `events=raw_syscalls:sys_enter,syscalls:sys_enter_io_uring_enter,syscalls:sys_enter_pread64,syscalls:sys_enter_pwrite64,syscalls:sys_enter_futex` and prints each per op. perf needs read access to tracefs to resolve tracepoints (usually root): before running, check that `perf list 'syscalls:sys_enter_io_uring_enter'` lists the event, and fail with a clear message if it doesn't. Throughput runs never enable this mode. `bytesProcessed` can stay as a column if it is useful; otherwise remove it too. | `grep -rn 'countEnters\|takeEnterCount\|enterCount' src/ bench.sh` finds nothing; no benchmark records a syscall count; `--syscalls` on a short `DirectReadBenchmark` run prints `io_uring_enter`/op for JUring and `pread64`/op for FileChannel, both measured. | |
| 2.4 | A4, A5, A15, A16, A19 | **The fio comparison runs through JMH and fio's own JSON, not a Java harness** (*fourth pass*; replaces "fio-live: check `result.result()` and fio's exit code"). Delete `runDirectComparison`, `runJuringBench`, `runLoop`, `runNativeFio`, `parseFioJson`, `isFioAvailable`, `syncDevice` and the `--fio-live`/`--compare-fio` branch in `BenchmarkRunner`. Add `bench.sh --fio-compare` that, for each of N repeats (default 3) and in random order per repeat, runs (a) the JMH `FioComparisonBenchmark` with `-rf json -rff results/<date>/jmh-<rep>.json` and (b) fio with `--output-format=json --output=results/<date>/fio-<mode>-<rep>.json --lat_percentiles=1 --norandommap --randrepeat=0` and the same QD, block size, file, registered files and buffers, and runtime as the JMH measurement window; it exits non-zero if fio exits non-zero, or its JSON has `error != 0` or zero IOPS. A `jq` script (`bench/fio-compare-table.sh` or similar) prints the comparison table, mean ± stddev, from those files only. In the JMH benchmark: drop slat and the telemetry `submit()` (A19), seed each fork differently and log the seed, use a better PRNG (A5). Keep: batching matched to JUring (or `t/io_uring`), preconditioning script (not run, see Environment). **Verify first:** `strace -f -e trace=io_uring_setup fio …` to see which setup flags fio uses; record in the commit. | `grep -n 'runDirectComparison\|parseFioJson' -r src/` finds nothing; a short `./bench.sh --fio-compare` (seconds per run, N=2) writes JMH and fio JSON files into `results/<date>/` and the table script prints both rows from them; replacing `fio` with a script that exits 1 makes `bench.sh` exit non-zero; with telemetry ON and OFF the JUring loop issues the same number of `io_uring_enter` calls (`--syscalls` mode, step 2.3). | |
| 2.5 | A13 | Make the multi-file fio command pass `--openfiles` (or raise `ulimit -n` in the runner). The real re-run is phase 3. | The generated fio command no longer hits EMFILE in a short run. | |
| 2.6 | A6 | Per-thread task pools ≥65536 offsets (or PRNG offsets over the whole file) in `DirectReadBenchmark`. | With `--threads 4`, the four threads' offset sequences differ; working-set size is logged at setup. | |
| 2.7 | A7 | Zero-copy `prepareWriteFixed(fileIdx, len, offset, bufIdx)` in `BufferedWriteBenchmark`. | No `MemorySegment` overload of `prepareWriteFixed` in the benchmark. | |
| 2.8 | A8 | One submit/reap pattern for reads and writes; keep the write pipeline full across invocations; no peek-after-submit. | `DirectWriteBenchmark` and `MultiThreadScalingBenchmark.juringDirectWrite` use the same refill + `submitAndWaitForBatchResult(1, qd, …)` loop as reads; no `peekForBatchResult` right after `submit`. | |
| 2.9 | A9, A17, C4 | Larger ops per invocation; `LongAdder` consumed on the harness thread; separate state per method so pollers don't run during FileChannel; extra FileChannel run with `-Djdk.virtualThreadScheduler.maxPoolSize=1024`. | No `Blackhole` call off the harness thread; no `JUringBlocking` instance exists during the FileChannel method; the maxPoolSize variant is runnable from `bench.sh`. | |
| 2.10 | A18 | Record pinning per run; use `-XX:ActiveProcessorCount` + explicit GC if pinning. | Every result file states pinned/unpinned; pinned runs pass explicit GC and processor-count flags. | |
| 2.11 | A12 | Dataset generator script; skip files smaller than the block size. `./bench.sh` works from a clean checkout. | Fresh clone + generator + `./bench.sh` smoke mode runs end to end with a data dir other than `/home/david/...`. | |
| 2.12 | A19, A20 | **Latency histograms: HdrHistogram, measurement iterations only.** Add `org.hdrhistogram:HdrHistogram` (test scope). Replace `DatasetManager.FioLatencyHistogram` with `new Histogram(1, 60_000_000_000L, 3)` (fixed range, so `recordValue` never allocates) and delete its percentile-math test in `BenchmarkSuiteValidationTest`. In `FioComparisonBenchmark.FioThreadState`: reset in `@Setup(Level.Iteration)`; in `@TearDown(Level.Iteration)` take `IterationParams` and add the iteration histogram to a trial histogram only when `getType() == IterationType.MEASUREMENT`; in `@TearDown(Level.Trial)` write `outputPercentileDistribution` and an `.hlog` (`HistogramLogWriter`) to `results/<date>/` (directory from a system property set by `bench.sh`). Only `lat` is recorded (A19). | No `FioLatencyHistogram` in `src/`; `-prof gc` on `FioComparisonBenchmark` with `telemetry=true` still shows ≈0 B/op; a short run writes one percentile file and one `.hlog` per fork, and their total count equals measurement-iteration ops only (check: warmup ops excluded). | |
| 2.13 | C12 | Remove the timing output (wall time, MB/s, IOPS) from `DirectReadVisualVerificationTest`; make it a Linux-only JUnit test that asserts every block matches (tag it like the other Linux tests from 0.4) and remove `--visual-read` from `BenchmarkRunner`. Delete `src/test/java/bench/random/`, `src/test/java/bench/ExecutionPlanBlocking.java` and `src/test/java/bench/ExecutionPlanJUring.java` (`BENCHMARK_PLAN.md` links to them: that file is the maintainer's call, see 0.1). | `grep -rn 'nanoTime\|MB/s\|IOPS' DirectReadVisualVerificationTest.java` finds nothing; `src/test/java/bench/` contains only `suite/`; `mvn verify` passes. | |

> **Second-opinion remarks on Phase 2 (Steps 2.2, 2.3, 2.4, 2.12):**
> 1. **Step 2.2 — Do NOT divide `perfnorm` results by `denomOps` (`score`)!** JMH's `perfnorm` profiler **already normalizes** every secondary metric per `@Benchmark` invocation (`#/op`, or `#/op` scaled by `@OperationsPerInvocation`). Only `@AuxCounters` (`bytesProcessed`) are raw per-second rates that need dividing by `score`. Dividing `perfnorm` metrics (`task-clock`, `cycles:u`, `cycles:k`, `syscalls:*`) by `denomOps` will divide them by `ops/s` a second time!
> 2. **Step 2.2 — `task-clock` unit is milliseconds (`msec/op`), not nanoseconds:** `perf stat` reports `task-clock` in milliseconds (`msec`). Multiply `perfnorm`'s `task-clock` score by `1_000_000.0` if converting `msec/op` to `ns/op` for the summary table.
> 3. **Steps 2.2, 2.4, 2.12 — Update `BenchmarkSuiteValidationTest.java`:** `BenchmarkSuiteValidationTest` imports and passes `CpuAuxCounters` (`:3, :381-384, :510-527`) and tests `parseFioJson` (`:537-570`) and `clatHistogram`/`slatHistogram` (`:517-519`). Update those test methods in `BenchmarkSuiteValidationTest.java` in Steps 2.2, 2.4, and 2.12 so `mvn test-compile` passes at every step.
> 4. **Steps 2.2 & 2.3 — System permissions on `treediagram` (`perf_event_paranoid` & `tracefs`):** Currently on `treediagram`, `/proc/sys/kernel/perf_event_paranoid` is `2` and `/sys/kernel/tracing` is `0700 root:root`. Under `perf_event_paranoid=2`, `perf` silently strips `:k` (`cycles:k` -> `<not supported>`, `task-clock` -> `task-clock:u`), and `perf list 'syscalls:sys_enter_io_uring_enter'` returns empty for non-root users. If those permissions are still restricted when the implementing agent reaches Steps 2.2 and 2.3, implement the code and preflight checks, verify that the preflight check fails fast with a clear message as specified, and ask the maintainer to run `sudo sysctl kernel.perf_event_paranoid=-1` and `sudo chmod a+rx /sys/kernel/tracing /sys/kernel/debug/tracing` (or mark the runtime proof `blocked: perf_event_paranoid=2 / tracefs permissions`).

After phase 2: one smoke run of every benchmark (short iterations, not for publication), then stop and hand over to the maintainer for phase 3.

### Phase 3 — One publication run (maintainer)
- Record the environment once (A11): CPU, governor, SMT, NVMe model + firmware, kernel, filesystem + mount options, JDK build, `HZ` and CPU accounting mode, pinning (A18), whether `iou-wrk` threads appeared in buffered runs (A3), the `perf` version and `perf_event_paranoid` value, and the exact `perfnorm` event lists used (steps 2.2, 2.3).
- JMH: ≥3 forks (5 for headline numbers); score ± error on every row.
- fio: randomised order, ≥3 repeats, mean ± stddev.
- Commit raw JMH and fio JSON to `results/<date>/`.

### Phase 4 — README rewrite (maintainer)
- Generate the tables from `results/<date>/` (C8), so every number appears once and from one run.
- Rewrite every claim listed in C3; drop any the new data doesn't support.
- Next to every comparison: threads × QD on both sides, direct/buffered, working-set size.
- State in "Methodology and limitations" that latency percentiles are closed-loop at a fixed QD (A20), and how the fio comparison was run (JMH forks, fio repeats, order, raw files).
- State in "Methodology and limitations" what `perfnorm` covers: every thread of the forked JVM over the measurement window, but not interrupt-context completion work on other CPUs. If the README claims total system cost, back it with a pinned `perf stat -a` run.
- Correct the technical explanations: Loom compensation (C4), `critical` (B4), buffered-write result (A3), fio advantage (A15), slat (A16), EMFILE (A13), PCIe limit (C8), JCStress wording (C5), "lock-free" (C10).
- Fix the code examples (C11) and add the native-access flag (B13), JDK/kernel requirements (C6), and a "Methodology and limitations" section.

### Phase 5 — Pre-publication check (maintainer)
- [ ] Every README number traces to a file in `results/`.
- [ ] Every comparison states threads × QD on both sides.
- [ ] No P0/P1 item open, or each open one is listed in the README's limitations section.
- [ ] Clean clone → `mvn verify` → `./bench.sh` works on a machine that isn't the author's, and CI is green.
- [ ] `--enable-native-access` is the only flag a user needs, and it's documented.
- [ ] Someone else has read the README's Loom and FFM paragraphs against the JDK 25 sources.

### Coverage check: does the plan address everything that would hurt the release?
Every finding maps to a plan step. What each audience is likely to notice first:

| Audience | What would hurt | Items | Plan steps |
|---|---|---|---|
| OpenJDK (Alan Bateman) | Heap/closed memory passed to the kernel, frees under in-flight I/O | B2, B6, B12, B1 | 1.1, 1.2, 1.8 |
| | Public API that bypasses native-access restrictions (JEP 472) | B13 | 1.4 |
| | FFM linkage misuse (`critical`, variadic) | B4, B5 | 1.6 |
| | Wrong description of Loom's file I/O compensation | C4, A9 | 2.9, phase 4 |
| | Memory-ordering bug and "formally verified" wording | B10, C5 | 1.7, 1.11, phase 4 |
| Reddit r/java | Headline ratios at unequal concurrency | A1 | 2.1, phase 4 |
| | "Beats fio" claims that don't hold (EMFILE, attribution, unchecked results, ordering) | A4, A5, A13, A15, A16 | 2.4, 2.5 |
| | Unmeasured or mismeasured CPU and syscall numbers | A2, A3, A14 | 2.2, 2.3 |
| | Self-contradicting numbers, wrong license/Java/kernel versions | C6, C8 | 0.3, phase 4 |
| | `mvn test` fails, benchmarks can't be reproduced | C7, A12 | 0.4, 2.11 |
| foojay | Copy-paste examples that leak or write wrong data; missing setup flag | C11, B13 | 1.9, phase 4 |
| | Results without error bars or environment | A11 | phase 3 |
| Azul | Homemade latency histogram, warmup in percentiles, fio comparison outside JMH | A19 | 2.4, 2.12 |
| | Closed-loop percentiles presented as latency under load (coordinated omission) | A20 | phase 4 |

Remaining items mapped: A6→2.6, A7→2.7, A8→2.8, A10→2.3, A17→2.9, A18→2.10, B3→1.5, B7→1.3, B8/B9→1.9, B11→1.3, C1→0.1, C2→0.2, C3→phase 4, C9→1.11, C10→phase 4, C12→2.13.

**Limits of this check.** All three review passes only read the code; nothing was compiled or run. The third pass checked the plan's library and hygiene claims against the code, and spot-checked the benchmark claims (A2, A7, A8, A10, A11, A14). The other benchmark findings were not re-checked. Three points are marked unverified and should be confirmed on the benchmark machine before relying on them: which filesystem punts buffered writes to io-wq (A3), whether the installed fio sets DEFER_TASKRUN itself (A15), and whether the kernel caps file registration at `RLIMIT_NOFILE` (A13, which doesn't change that fix). Phase 1's regression tests and phase 3's run are where the remaining risk shows up.
