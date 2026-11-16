# Finalizing JUring Foundation Plan: Ring Multiplexing, `ioprio`, and Developer-Focused README

This plan covers the final two technical capabilities to complete JUring's v1 foundation—**ring-level completion multiplexing** and **Linux block-layer `ioprio` support**—followed by a **developer-focused `README.md` overhaul**.

---

## Part 1: Ring-Level Completion Multiplexing (`B10a`)

### Goal
Allow multiple [`JuringFile`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java), [`JuringInputStream`](src/main/java/com/davidvlijmincx/lio/api/JuringInputStream.java), [`JuringOutputStream`](src/main/java/com/davidvlijmincx/lio/api/JuringOutputStream.java), and [`GroupCommit`](src/main/java/com/davidvlijmincx/lio/api/GroupCommit.java) instances to share and multiplex a single [`JUring`](src/main/java/com/davidvlijmincx/lio/api/JUring.java) concurrently without throwing `IllegalStateException` when one handle's blocking helper (`await`, `read`, `write`, `drain`, `cancel`, `close`) observes completions belonging to a sibling `JuringFile` on the same ring.

### Design & Mechanics
1. **Ring-Registered `JuringFile` Table on `JUring`:**
   * Add a lightweight, zero-allocation array list (`JuringFile[] openFiles`, count `openFileCount`) and a `ResultConsumer sharedFileDispatcher` on [`JUring`](src/main/java/com/davidvlijmincx/lio/api/JUring.java).
   * When `JuringFile.open(ring, ...)` creates a handle, it registers itself with `ring.attachFile(this)` and unregisters in `JuringFile.close()` via `ring.detachFile(this)`.
2. **Routing Completions Across Co-Resident `JuringFile` Handles:**
   * When any `JuringFile` reaps completions (in `await`, `drain`, `cancel`, `cancelAll`, `close`), it passes `ring.sharedFileCompletionConsumer()` instead of `this::onCompletion`.
   * The ring's dispatcher first checks the calling `JuringFile`'s ledger (fast path: 1-file case has zero extra overhead) and, if not found, iterates the other attached `JuringFile` instances on that ring to deliver the completion to its owning handle's ledger (`states[slot] = DONE`, `results[slot] = value`, decrementing that file's `inFlight` counter).
   * If a completion does not match *any* attached `JuringFile` (and is not a stray `TIMEOUT`/`LINK_TIMEOUT`), it is still flagged as **raw foreign ring work** (preserving the existing B1/B3 safety contract when raw `ring.prepare*` operations are mixed without reaping them first).
3. **Refining the Ownership Guard (`checkRingOwnership`):**
   * Replace `ring.inFlightCount() != this.inFlight` with `ring.inFlightCount() != ring.attachedFilesInFlightCount()`.
   * This means any number of `JuringFile`, `JuringInputStream`, `JuringOutputStream`, and `GroupCommit` instances can interleave operations on a single `JUring` seamlessly, while still guarding against un-reaped raw `JUring.prepare*` operations.
4. **Scoped `drain()`, `cancelAll()`, and `close()` on Shared Rings:**
   * `file.drain()` waits until `this.inFlight == 0` while routing any sibling `JuringFile` completions that arrive along the way into their respective ledgers.
   * `file.cancelAll()` and `file.close(timeout)` issue per-handle cancellations (`issueCancelsForOwnPending`: using `ring.prepareCancelAny()` when `this.inFlight == ring.inFlightCount()`, or per-handle `ring.prepareCancelByUserData(handle)` when sibling `JuringFile`s also have in-flight operations) so closing or cancelling `File A` never cancels `File B`'s in-flight operations!
5. **Update Examples & Docs:**
   * Update [`StreamCopyExampleTest.java`](src/test/java/com/davidvlijmincx/lio/examples/StreamCopyExampleTest.java) and [`ManyFilesExampleTest.java`](src/test/java/com/davidvlijmincx/lio/examples/ManyFilesExampleTest.java) (and [`docs/examples.md`](docs/examples.md)) to show `JuringInputStream` + `JuringOutputStream` and multiple `JuringFile`s sharing a **single** `JUring`.

---

## Part 2: Linux Block-Layer I/O Priority (`ioprio`) (`B10b`)

### Goal
Expose the Linux kernel's native `ioprio` 16-bit field in `io_uring_sqe` ([`Sqe.java`](src/main/java/com/davidvlijmincx/lio/api/Sqe.java#L90-L96)) cleanly at both the low-level [`JUring`](src/main/java/com/davidvlijmincx/lio/api/JUring.java) level and high-level [`JuringFile`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java) / stream level with zero hot-path overhead.

### Design & Mechanics
1. **New Public Helper Class `IoPriority` (`com.davidvlijmincx.lio.api.IoPriority`):**
   * Constants and encoding matching Linux `<linux/ioprio.h>`:
     * `CLASS_NONE = 0`, `CLASS_RT = 1` (Real-Time), `CLASS_BE = 2` (Best-Effort), `CLASS_IDLE = 3` (Idle / background compaction).
     * `short ioprio(int prioClass, int level)` (validates class `0..3` and level `0..7`, packs `(prioClass << 13) | level`).
     * Convenience constants/factories: `IoPriority.NONE` (`0`), `IoPriority.realTime(int level)`, `IoPriority.bestEffort(int level)`, `IoPriority.idle()`.
2. **Low-Level `JUring` Support:**
   * Add a `short defaultIoprio` field on [`JUring`](src/main/java/com/davidvlijmincx/lio/api/JUring.java) (default `0`) with `ring.setDefaultIoprio(short ioprio)` / `ring.defaultIoprio()`, plus `ring.setLastSqeIoprio(short ioprio)` so callers can either set a ring-wide default or tag individual SQEs right after `prepare*` without exploding the 60+ `prepare*` method overloads.
   * In `JUring.getSqe(byte flags, boolean fixedFile)`, if `defaultIoprio != 0`, call `Sqe.setIoprio(sqe, defaultIoprio)`.
3. **High-Level `JuringFile` Support:**
   * Add `JuringFile.withIoPriority(short ioprio)` and `JuringFile.ioPriority()` on [`JuringFile`](src/main/java/com/davidvlijmincx/lio/api/JuringFile.java).
   * Whenever a `JuringFile` prepares an operation on its ring, it applies its configured `ioPriority` (if non-zero) to the prepared SQE. Because `JuringInputStream`, `JuringOutputStream`, and `GroupCommit` wrap a `JuringFile`, setting `file.withIoPriority(IoPriority.idle())` automatically prioritizes all stream read-ahead, write-behind, or group-commit I/O on that file!

---

## Part 3: Developer-Focused `README.md` Overhaul (`B10c`)

### Goal
Transform [`README.md`](README.md) from an internal benchmark report into a compelling, developer-first open-source landing page that immediately communicates **why JUring is awesome**, **how easy it is to use**, and **how it stacks up against `fio`, C++ Seastar, and Java `FileChannel`**.

### Proposed `README.md` Structure
1. **Punchy Hero Section & Value Proposition:**
   * Clear hook: *"True asynchronous Linux `io_uring` disk I/O for Java 25+ using the Foreign Function & Memory (FFM) API. Zero JNI. Zero `liburing`. Zero heap allocations on the hot path."*
   * Explain the **Java Virtual Thread File I/O Problem** in 3 sentences (why `FileChannel` pins carrier threads on Linux disk I/O and forces carrier pool expansion, and how `JUring` fixes it with up to **35x higher throughput** per platform thread).
2. **Architecture & Feature Highlights (At a Glance):**
   * Clean Mermaid diagram or layered table showing how developers can pick their abstraction level:
     * **Virtual Threads (`JUringBlocking`)** — Drop-in blocking calls that unmount virtual threads.
     * **High-Level Async Files & Streams (`JuringFile`, `JuringInputStream`, `JuringOutputStream`, `GroupCommit`)** — Adaptive read-ahead, write-behind, `O_DIRECT` alignment, `ioprio`, and single-ring multi-file multiplexing.
     * **Multi-Shard Runtime (`IoShard`, `RingGroup`, `MetricsJmx`)** — Thread-per-core pinned rings and sub-1% overhead latency histograms.
     * **Raw Ring (`JUring`)** — Direct SQE/CQE control, registered files (`IOSQE_FIXED_FILE`), fixed buffers, and linked kernel timeouts.
3. **Quick-Start Code Examples (Progressive Disclosure):**
   * *Example 1: Virtual Threads (`JUringBlocking`)* — 15 lines showing how familiar and clean it is.
   * *Example 2: High-Level File & Stream Copy on One Shared Ring* — Showcasing the new single-ring stream multiplexing + `IoPriority` + `DurabilityMode`.
   * *Example 3: Maximum IOPS Zero-Allocation Pipeline* — Registered files + fixed buffers + fused batch submit/reap.
4. **Headline Benchmarks (Clean, Scannable Visuals):**
   * Distill the dense scientific tables into 3 crisp, high-impact comparison tables (keeping links to [BENCHMARK.md](BENCHMARK.md) and `docs/m*-report.md` for the full Student-$t$ CIs and methodology):
     1. **Virtual Threads (`JUringBlocking` vs `FileChannel`)**: Highlighting the **34.9x** (1–2T) to **10.5x** (4T) speedup. *Use the disclosed claim shape: the 1–2T and 4T JMH confidence intervals overlap (`BENCHMARK.md` Part II); do not present this as a statistically separated ranking.*
     2. **Single-Core Engine Parity (`JUring` vs `fio` vs C++ Seastar)**: Highlighting **411k IOPS** on 1 core (+24% vs `fio`, +5.4% vs Seastar at QD128) and **25.6% fewer CPU cycles/op**.
     3. **Buffered Streams, Group Commit & 8-Shard Scaling**: Highlighting **4.7 GiB/s** stream reads (parity with Seastar `fstream`), **184k durable writes/s** with `GroupCommit`, and **473k IOPS** at 8 shards.
5. **Requirements, Build & Compatibility Links:**
   * Linux kernel 6.1+ (5.6+ minimum), JDK 25+, `--enable-native-access=ALL-UNNAMED`, and links to [docs/examples.md](docs/examples.md) and [docs/api-stability.md](docs/api-stability.md).

---

## Verification Plan
1. **Unit & Conformance Tests (`mvn -o test`):**
   * `JuringFileMultiplexingTest`:
     * Two `JuringFile` instances interleaved on one `JUring` (`prepareRead` / `prepareWrite` + out-of-order `await`, `read`, `write`, `drain`).
     * `JuringInputStream` reading `File A` and `JuringOutputStream` writing `File B` on the **same** `JUring` simultaneously.
     * `cancel(handle)`, `cancelAll()`, and `close(timeout)` on `File A` while `File B` has active in-flight timers/reads on the same ring—verifying `File B`'s operations are untouched and complete normally.
     * Raw foreign `ring.prepareNop()` still triggers the `IllegalStateException` guard when unreaped.
   * `IoPriorityTest`:
     * Encoding/validation of `IoPriority` classes (`RT`, `BE`, `IDLE`, `NONE`) and levels (`0..7`).
     * End-to-end reads and writes with `IoPriority.bestEffort(0)`, `IoPriority.idle()`, and `JuringFile.withIoPriority(...)`.
   * Zero-allocation check (`JuringFileZeroAllocationTest` and `ZeroAllocationHotPathTest`) to verify 0 heap bytes on the hot path with multiplexing and `ioprio` wired in.
2. **Javadoc Gate (`mvn -o javadoc:javadoc`):**
   * Ensure `doclint=all` passes with **0 warnings**.
