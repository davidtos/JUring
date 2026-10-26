# Direct Read Flow (`O_DIRECT` + Fixed Files & Buffers)

This document details the complete end-to-end flow of an asynchronous Direct I/O (`O_DIRECT`) read operation using `JUring`.

---

## 1. Architectural Highlights of the Read Path

1. **Page-Aligned Registered Buffers (`IORING_OP_READ_FIXED`)**:
   - Registered buffers are pre-allocated from a contiguous, 4,096-byte page-aligned slab.
   - The Linux kernel pins the underlying physical pages into memory once during initialization (`io_uring_register(IORING_REGISTER_BUFFERS)`).
   - During reads, NVMe DMA transfers transfer directly from storage controller into this page-aligned buffer without page-straddling (1 NVMe PRP entry per 4 KB page) and without bouncing through the kernel page cache.
2. **Registered File Descriptors (`IOSQE_FIXED_FILE`)**:
   - Files are registered into the ring file table (`io_uring_register(IORING_REGISTER_FILES)`).
   - The kernel eliminates per-I/O `fget()` / `fput()` descriptor lookups and atomic reference counter increments.
3. **Registered Ring File Descriptor (`IORING_ENTER_REGISTERED_RING`)**:
   - The `io_uring` ring fd itself is registered (`IORING_REGISTER_RING_FDS`).
   - Every `io_uring_enter` bypasses Linux process-wide file descriptor table (`files_struct`) lookup and atomic refcounting on the shared JVM process.
4. **Fused `submitAndWaitForBatchResult`**:
   - Submits queued SQEs and blocks until at least `minComplete` completions arrive in a single `io_uring_enter` syscall.
   - Reaps all available completions (up to `maxReap`) in a single pass without breaking batching.
5. **Zero-Allocation In-Place Reaping**:
   - CQEs are reaped directly from the shared memory-mapped CQ ring.
   - A single mutable `Result` instance is populated in-place, and user-defined `ResultConsumer` is invoked with 0 heap allocations (`~0 B/op`).

---

## 2. Sequence Diagram

```mermaid
sequenceDiagram
    autonumber
    actor App as Application / Benchmark
    participant JUring as JUring Facade
    participant Disp as IoUringDispatcher
    participant SQ as SQ Ring (Mmapped Memory)
    participant Kernel as Linux Kernel (io_uring & NVMe)
    participant CQ as CQ Ring (Mmapped Memory)
    participant Consumer as ResultConsumer (Callback)

    Note over App, Disp: 1. Setup Phase (Executed Once)
    App->>JUring: registerBuffers(size=4096, count=64)
    JUring->>Disp: registerBuffers -> io_uring_register(IORING_REGISTER_BUFFERS)
    Note over Disp: Allocates 4KB page-aligned slab; kernel pins pages
    App->>JUring: registerFiles(fd)
    JUring->>Disp: registerFiles -> io_uring_register(IORING_REGISTER_FILES)

    Note over App, SQ: 2. Operation Preparation Phase
    App->>JUring: checkOutBuffer()
    JUring-->>App: bufferIndex = 0
    App->>JUring: prepareReadFixed(indexFD=0, readSize=4096, offset=0, bufferIndex=0)
    JUring->>Disp: getSqe()
    Disp->>SQ: Read sq_khead & compute index (sqeTail & mask)
    Disp->>SQ: Zero 64-byte SQE slot (8x aligned 64-bit stores)
    Disp-->>JUring: raw sqeAddress (long)
    Note over JUring: Encode bitpacked userData: [OpType: 8b | BufIdx: 16b | OpSeq: 40b]
    JUring->>Disp: prepareReadFixed(sqe, fd, bufAddr, size, offset, bufIndex)
    Disp->>SQ: Write opcode=IORING_OP_READ_FIXED, fd, off, addr, len, buf_index
    JUring->>Disp: setUserData(sqe, userData)
    Disp->>SQ: Write user_data (64-bit store)
    JUring-->>App: userData token (long)

    Note over App, Kernel: 3. Fused Submission & Wait Phase
    App->>JUring: submitAndWaitForBatchResult(minComplete=1, maxReap=64, consumer)
    JUring->>Disp: submitAndWaitForBatchResult(1, 64, consumer)
    Disp->>SQ: Release barrier: VH_INT.setRelease(sq_ktail, tail)
    Disp->>Kernel: io_uring_enter(to_submit, minComplete=1, flags=GETEVENTS | REGISTERED_RING)
    Note over Kernel: Kernel reads SQEs from shared ring.<br/>Issues direct DMA to NVMe controller.<br/>Thread sleeps if completions < minComplete.
    Note over Kernel, CQ: NVMe DMA completes into registered buffer.<br/>Kernel writes CQE(res=4096, userData) to CQ ring.<br/>Kernel updates cq_ktail with release semantics.<br/>Kernel wakes waiting thread.

    Note over Disp, Consumer: 4. In-Place CQE Reaping Phase
    Disp->>CQ: Acquire barrier: VH_INT.getAcquire(cq_ktail)
    loop For each available CQE (up to maxReap)
        Disp->>CQ: Read cqe.user_data, cqe.res, and cqe.flags
        Disp->>Disp: Bit-unpack OpType (READ_FIXED) & BufferIndex
        Disp->>Disp: Populate reusable Result in-place
        Disp->>Consumer: consumer.accept(result)
        Consumer-->>Disp: Processed (e.g., checksum / counter)
    end
    Disp->>CQ: Release barrier: VH_INT.setRelease(cq_khead, head + count)
    Disp-->>JUring: reapedCount (e.g., 1..64)
    JUring-->>App: reapedCount
```

---

## 3. Step-by-Step Execution Walkthrough

### Step 1: Memory & File Registration
* `JUring.registerBuffers()` allocates a contiguous slab aligned to `4096` bytes using `Arena.allocate(size * count, 4096)`.
* `io_uring_register(IORING_REGISTER_BUFFERS)` informs the kernel of these buffers. The kernel pins the virtual pages into physical memory, locking them against `RLIMIT_MEMLOCK`.
* Fast primitive arrays (`registeredBufferAddresses` and `registeredBufferSizes`) are cached in both `JUring` and `IoUringDispatcher` to eliminate `List<MemorySegment>` method dispatches and bounds checking in hot paths.

### Step 2: Preparing the Read SQE
* `getSqe()` inspects the cached `sqKheadAddr` and local `sqeTail` to ensure the submission queue is not full.
* It computes the SQE slot address: `sqSqesAddr + (sqeTail & sqRingMask) * 64L`.
* `ZeroGcSqe.zero(sqeAddress)` clears the 64-byte SQE using 8 fast 64-bit integer writes.
* `ZeroGcUserData.encodeFixed(OperationType.READ_FIXED, bufferIndex, opId)` packs the opcode (8 bits), buffer index (16 bits), and monotonically incrementing primitive `opSeq` (40 bits) into a raw 64-bit `long`.
* The SQE fields (opcode, fd, addr, len, off, buf_index) are set directly via `VarHandle` stores to off-heap memory, followed by `ZeroGcSqe.setUserData(sqe, userData)`.

### Step 3: Fused Submission & Wait (`io_uring_enter`)
* `submitAndWaitForBatchResult` updates `sq_ktail` with `VarHandle.setRelease` so the kernel can safely read the newly queued SQEs.
* It invokes `io_uring_enter(fd, to_submit, minComplete, IORING_ENTER_GETEVENTS | IORING_ENTER_REGISTERED_RING)`.
* By combining submission and waiting into one syscall:
  * Syscalls per operation drop from ~1.0–2.0 to **~0.4–0.5 enters/op**.
  * The thread waits only if fewer than `minComplete` entries have finished.

### Step 4: Zero-Copy NVMe DMA Transfer
* The Linux NVMe driver maps the registered buffer's physical page directly into the NVMe command SQE (using a single PRP1 entry without requiring PRP lists or bounce buffers).
* The NVMe controller transfers data directly across PCIe bus into the host RAM via bus-master DMA.
* Upon completion, the kernel interrupt handler (or polled worker) places a CQE into the memory-mapped CQ ring and updates `cq_ktail`.

### Step 5: In-Place CQE Reaping
* User-space reads `cq_khead` and reads `cq_ktail` using `VarHandle.getAcquire`.
* The loop iterates across all ready completions up to `maxReap`.
* For each CQE:
  * Reads `userData`, `res` (bytes read), and `flags` (`ZeroGcCqe.getFlags(address)`).
  * Switches on the primitive 8-bit `typeIndex` (compiles to a fast C2 JIT `tableswitch`).
  * Populates the pre-allocated, reusable [`Result`](file:///home/david/projects/JUring/src/main/java/com/davidvlijmincx/lio/api/Result.java) instance.
  * Dispatches to `ResultConsumer.accept(result)`.
* Advances `cq_khead` via `VarHandle.setRelease` to return the completion slots to the kernel.
