# OpenJDK nio-dev / loom-dev note (draft, not sent)

**Do not cold-email Alan Bateman or file an issue with a library pitch.** The useful
contribution is reproducible data on the question the JDK has already stated it is exploring:
file I/O does capture the carrier, io_uring is under consideration, and the open question is
per-operation bookkeeping/overhead for microsecond-scale file I/O. Send this only after 0.1.0 is
released and the data archive is available. One post, no follow-up if there is no interest.

## Where

`nio-dev@openjdk.org`, cc `loom-dev@openjdk.org`. Subject:
`Measurements: io_uring file-I/O overhead and a virtual-thread blocking shape (data, not a proposal)`

## Body draft

> Hi,
>
> I built a standalone Java io_uring file-I/O implementation to measure the two things that come
> up in the Loom file-I/O discussions: what a blocking shape costs when the wait is a parked
> virtual thread rather than a blocked carrier, and what the per-operation overhead looks like
> for 4 KiB reads in the range where "the bookkeeping and overhead when doing file I/O ops that
> may take only a few microseconds" matters (Alan Bateman, loom-dev, June 2024).
>
> Setup: Linux 6.x/7.0, Ryzen 9 3950X, consumer PCIe 4.0 NVMe, ext4, O_DIRECT, one 10 GiB aged
> file, JDK 26, FFM downcalls only (no JNI/liburing), `SINGLE_ISSUER|COOP_TASKRUN|DEFER_TASKRUN`,
> registered files + fixed buffers, one owner thread per ring. Harness, raw JSON and analysis
> scripts are public; the release data archive is checksummed.
>
> Data points that may be relevant (95% CIs, R=5, fresh process per rep):
> - 4 KiB randread, QD64: ~327k IOPS at ~11.3k attributed cycles/op (0.33 syscalls/op with fused
>   submit); registration-matched fio (default batching) ~304k at ~15.2k cycles/op. At QD1 all
>   engines are ~11k IOPS: no async win.
> - `AsynchronousFileChannel` is thread-pool emulation on Linux, so I did not use it as an async
>   baseline; fio is the neutral referee.
> - Virtual threads: with Loom defaults FileChannel reaches parity; with a fixed 1–4 platform
>   thread budget, a park/unpark blocking facade keeps the device busy from 1–2 poller threads.
>   This is thread-efficiency data, not a per-op engine claim (historical JMH; CIs overlap).
> - Tail: at QD64 p99 is ~parity, p999 is better than a busy-polling C++ reactor in this
>   bracket; at QD1 p999 is dominated by JVM safepoints/GC (~630 µs vs ~140 µs).
>
> Two questions I cannot answer from the outside, if anyone has the context:
> 1. Would an internal `FileChannel`/`java.io` io_uring implementation want a blocking facade of
>    this shape (park the VT, unpark from a poller), or is the plan to keep the existing blocker
>    path and only change the poller underneath (JDK-8374170)?
> 2. Is per-op FFM downcall + ring bookkeeping in the ~10–30 ns range acceptable for the JDK's
>    threshold, or does the microsecond-file-I/O case require avoiding the extra object/slot
>    bookkeeping entirely?
>
> Not proposing to upstream a Linux-only library; I know the JDK cannot depend on one. Happy to
> provide the harness/data or test API shapes against the sandbox if useful. The related prior
> art is Michael McMahon's `jdk-sandbox` `iouring` branch, PanamaUring (pure FFM, 2022) and
> JAsyncfio (JNI); this work differs in the file-I/O-focused surface and the evidence trail, not
> in the idea of driving rings from Java.
>
> Thanks,
> David Vlijmincx

## Rules

- No "please adopt", no benchmark-boasting, no unsolicited patch.
- Link the repo, BENCHMARK.md and DATA-AVAILABILITY.md; do not attach giant logs.
- If asked for the raw data, point at the release archive and the reproduction commands.
- If the response is "known limitation, no plans", thank them and stop.
