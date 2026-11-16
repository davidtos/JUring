# HN submission plan (not posted)

**Do not post until 0.1.0 is published and CI is green.** Use an article or the GitHub release
page as the URL, not the same repository URL as the January 2025 Show HN (dedupe/flag risk).

## Suggested title

> Show HN: JUring – io_uring file I/O for Java, measured honestly against fio and Seastar

or, article-first:

> Virtual threads don't fix file I/O: measuring what io_uring changes for Java

## URL

The corrected article (foojay) or `https://github.com/davidtos/JUring/releases/tag/v0.1.0`.

## Author's first comment (post immediately)

> Author here. JUring is a pure-FFM io_uring file-I/O library for Java (no JNI, no liburing).
> Some honest limits up front, because benchmark threads usually go badly:
>
> - Single host: Ryzen 9 3950X, consumer PCIe 4.0 NVMe, ext4, O_DIRECT, one aged 10 GiB file.
>   Absolute IOPS are device-state dependent; don't extrapolate.
> - At the default Loom carrier pool, FileChannel reaches **parity** (roughly 445k vs 441k
>   ops/s at 250 virtual threads). The win is thread efficiency under a fixed platform-thread
>   budget: ~10× point estimate at a 4-thread budget in historical JMH, with overlapping CIs —
>   not a statistically separated ranking. QD1 is parity.
> - The write comparison is **INCONCLUSIVE**; this consumer NVMe has SLC/TLC write cliffs.
> - fio's read reference is registration-matched but uses default submit batching (fio can't set
>   io_uring's SINGLE_ISSUER/DEFER_TASKRUN); it's a best-config comparison. The 0.1.0 claims pass
>   adds a batching-matched control.
> - io_uring is denied by Docker's default seccomp profile and restricted by AppArmor /
>   kernel.io_uring_disabled in several environments. `JUring.isSupported()` exists for fallback;
>   this is Linux-only.
> - The repo includes the benchmark harness and the write-up of where it loses, plus the raw
>   data archive on the release. PRIOR ART: PanamaUring (pure FFM, 2022), JAsyncfio (JNI), the
>   JDK's own io_uring sandbox branch; JUring's angle is a file-I/O-focused surface + evidence.
> - Solo maintainer, 0.1.0, pre-1.0 API. I'm here for technical criticism, especially from people
>   who have run storage at scale.

## Questions to pre-answer

- "Why not AsynchronousFileChannel?" — On Linux it is a thread-pool emulation over blocking
  pread (SimpleAsynchronousFileChannelImpl), so it cannot avoid the carrier-thread problem.
- "Why not just more threads?" — You can; the claim is about doing it with a fixed small thread
  budget under CPU quotas. At Loom defaults we measure parity.
- "Why not JNI/liburing?" — No native build/dependency and no JNI boundary; the cost is FFM
  downcall overhead, which the benchmarks show is acceptable at QD≥16.
- "Why not Rust/Go?" — Different runtime; the point is JVM-native file I/O for JVM services.
- "Did the JDK fix this?" — JEP 491 removed pinning for `synchronized`, not file I/O. The JDK has
  an internal io_uring sandbox (no JEP); the JDK-8374170 poller work continues.
- "Who maintains this?" — Answer with the plan: CI, releases, a second maintainer sought; no
  overclaim.

## Do not

- Do not open with "35× faster" or "beats Seastar".
- Do not link a marketing page; link the repo/release.
- Do not argue about benchmark methodology in-thread. Point to the raw data and correct the
  record once.
