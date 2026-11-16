# Conference Proposal: Java at NVMe Line Rate

**Title:** Java at NVMe Line Rate: Driving Linux io_uring with FFM and Loom  
**Track:** Java & The JVM / High Performance & Architecture  
**Formats:** 45-minute Breakout Session / 50-minute Conference Talk  
**Target Conferences:** Voxxed Days Amsterdam, Devoxx Greece, JNation, Devoxx UK, DevBcn  

---

### Abstract (Short / Catalog Description - 150 words)

Project Loom transformed Java networking with Virtual Threads, but modern enterprise workloads don’t just talk to sockets—they read and write gigabytes of data to NVMe storage. When a virtual thread hits `FileChannel`, it stays pinned to its carrier thread, prompting the JVM to spawn up to 256 OS threads and causing heavy thread contention and cache thrashing.

What if Java could drive storage at hardware line rate without JNI or blocking OS threads?

In this talk, we dive into how to bypass POSIX bottlenecks using Linux `io_uring` and the Foreign Function & Memory (FFM) API (JEP 454). You’ll see how we built a zero-allocation, pure-Java engine achieving over 400,000 IOPS and beating `FileChannel` by up to 35x on virtual threads. Learn the hidden kernel flags (`SINGLE_ISSUER`, `DEFER_TASKRUN`), how to park/unpark virtual threads cooperatively on disk I/O, and what happens when hardware hits real-world flash cache cliffs.

---

### Extended Description (For Reviewers / Program Committee)

Java developers are rapidly adopting Virtual Threads under the assumption that blocking operations are now "free." While this holds true for network sockets unmounted via `epoll`, the Linux kernel provides no readiness polling for regular disk files. As a result, virtual threads performing disk I/O remain pinned to carrier platform threads, forcing Loom's scheduler to spawn compensating carrier threads. Under high concurrency in containerized environments, this leads to CFS throttling, context-switching overhead, and throughput collapse.

This session presents a deep architectural exploration of bridging Java with modern Linux asynchronous storage via `io_uring`:

1. **The Virtual Thread File Problem:** Why `FileChannel` degrades under constrained thread budgets and how Loom compensates under the hood.
2. **Driving Linux io_uring with Java FFM (JEP 454):** Mapping off-heap submission (SQ) and completion (CQ) rings into `MemorySegment`s, managing memory barriers with `VarHandle`, and executing native downcalls without JNI or C dependencies.
3. **The Poller-Reactor Model for Loom:** Demonstrating `JUringBlocking`, where virtual threads park cleanly via `LockSupport.park()` and are unparked by dedicated shard pollers when kernel CQEs arrive.
4. **Hard Benchmarks & Kernel Secrets:** 
   * How proper configuration (`IORING_SETUP_SINGLE_ISSUER`, `IORING_SETUP_DEFER_TASKRUN`, fixed buffers) achieves 411,000 IOPS and 25.6% fewer CPU cycles/op than `fio`.
   * Real NVMe benchmarking pitfalls: discovering and handling SSD SLC cache exhaustion cliffs (195k -> 59k IOPS) during write testing.
   * Why the kernel rejects multishot on regular files and why we backed out provided-buffer rings based on empirical measurements.
   * **Claim caveats:** the 34.9× virtual-thread figure is a JMH point estimate whose 1–2T and 4T intervals overlap (`BENCHMARK.md` Part II); the write-path claims are explicitly `INCONCLUSIVE` — no write-parity ranking is claimed.

Attendees will leave with a clear mental model of how Loom handles disk I/O, how modern Java can execute bare-metal systems programming via FFM, and practical architectural patterns for high-throughput Java storage engines.

---

### Pitch to Committee / Why This Talk Matters
* **Timeliness:** Covers Java 21+ Virtual Threads, JEP 454 (FFM), and modern Linux storage (kernel 6.x/7.x).
* **Empirical Rigor:** Based on open-source code with reproducible benchmarks (95% Student-t confidence intervals against `fio` and C++ Seastar), not slides full of theory.
* **Practical & Contrarian:** Challenges the common myth that Loom magically solves all I/O bottlenecks and gives attendees actionable patterns for storage-heavy services.
