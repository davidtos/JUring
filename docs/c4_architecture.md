# C4 Architecture Model for JUring

This document provides a comprehensive C4 architecture model (Context, Container, Component, and Code) for `JUring`.

---

## 1. Level 1: System Context Diagram

The System Context diagram illustrates how a Java application interacts with `JUring`, the Linux operating system, and the physical NVMe storage hardware.

```mermaid
flowchart TD
    UserApp["Java User Application<br/><i>[Java 22+ / OpenJDK]</i><br/>High-performance database, storage engine, or network service"]
    
    JUringLib["JUring Library<br/><i>[Java / Foreign Function & Memory (FFM) API]</i><br/>Zero-allocation, direct memory-mapped io_uring bindings"]
    
    LinuxKernel["Linux Kernel Subsystem<br/><i>[Linux 6.x+ Kernel]</i><br/>io_uring core, block layer, virtual memory manager, and NVMe driver"]
    
    NVMeHardware["NVMe Storage Controller & Flash<br/><i>[PCIe Hardware]</i><br/>Physical NVMe drive executing direct bus-master DMA transfers"]

    UserApp -->|"Submits asynchronous I/O requests & reaps completions"| JUringLib
    JUringLib -->|"Direct memory-mapped ring access & io_uring_enter syscalls"| LinuxKernel
    LinuxKernel -->|"PCIe bus-master DMA commands & hardware interrupts"| NVMeHardware
    NVMeHardware -->|"DMA data transfers directly into registered host RAM"| LinuxKernel

    classDef appStyle fill:#1168bd,stroke:#0b4884,color:#ffffff,font-weight:bold;
    classDef juringStyle fill:#00758f,stroke:#004f61,color:#ffffff,font-weight:bold;
    classDef kernelStyle fill:#2e7d32,stroke:#1b5e20,color:#ffffff,font-weight:bold;
    classDef hwStyle fill:#424242,stroke:#212121,color:#ffffff,font-weight:bold;

    class UserApp appStyle;
    class JUringLib juringStyle;
    class LinuxKernel kernelStyle;
    class NVMeHardware hwStyle;
```

---

## 2. Level 2: Container / Runtime Architecture Diagram

The Container diagram details the runtime boundary between the JVM user-space process and the Linux kernel, highlighting the shared memory-mapped rings, registered page-aligned memory slabs, and hardware interactions.

```mermaid
flowchart TB
    subgraph JVMProcess ["JVM Process (User Space)"]
        direction TB
        
        AppThread["Platform Worker Thread<br/><i>[Thread-Confined Issuer]</i><br/>Issues I/O operations and reaps completions"]
        
        JUringEngine["JUring Engine<br/><i>[Java 22+ FFM / Panama Linker]</i><br/>Zero-copy SQE building, in-place CQE parsing, fused syscall dispatcher"]
        
        subgraph UserSharedMemory ["Mmapped Shared Rings & Slabs (User View)"]
            UserSQRing["Submission Queue (SQ) Ring<br/><i>[MemorySegment]</i><br/>sq_khead, sq_ktail, sqes[64B]"]
            UserCQRing["Completion Queue (CQ) Ring<br/><i>[MemorySegment]</i><br/>cq_khead, cq_ktail, cqes[16B]"]
            UserBufSlab["Page-Aligned Buffer Slab<br/><i>[MemorySegment (4KB-aligned)]</i><br/>Contiguous memory sliced per iovec"]
        end
        
        AppThread -->|"Prepares & submits"| JUringEngine
        JUringEngine -->|"VarHandle writes (8x stores)"| UserSQRing
        JUringEngine -->|"VarHandle reads & in-place dispatch"| UserCQRing
        JUringEngine -->|"Zero-copy staging & DMA target"| UserBufSlab
    end

    subgraph LinuxKernelBoundary ["Linux Kernel Space"]
        direction TB
        
        SyscallHandler["io_uring_enter / io_uring_register<br/><i>[Fast-path syscall entry]</i><br/>Validates registered ring FD and GETEVENTS"]
        
        subgraph KernelSharedMemory ["Shared Kernel Memory Mapping"]
            KernelSQRing["Kernel SQ Ring View"]
            KernelCQRing["Kernel CQ Ring View"]
            PinnedPages["Pinned Physical RAM Pages<br/><i>[io_mapped_ubuf]</i><br/>Locked against RLIMIT_MEMLOCK (1 page per 4KB)"]
        end
        
        IoUringCore["io_uring Engine & Workqueues<br/><i>[Kernel Taskrun & Submission]</i><br/>Consumes SQEs, tracks completion"]
        
        BlockLayer["Linux Block Layer & NVMe Driver<br/><i>[O_DIRECT Fast Path]</i><br/>Constructs NVMe PRP commands with no page splits"]
        
        SyscallHandler --> IoUringCore
        IoUringCore --> KernelSQRing
        IoUringCore --> KernelCQRing
        IoUringCore --> BlockLayer
        BlockLayer --> PinnedPages
    end

    subgraph PCIeHardware ["Hardware Layer"]
        NVMeDrive["NVMe SSD Flash Controller<br/><i>[PCIe Gen 4/5 Bus Master]</i>"]
    end

    JUringEngine -->|"io_uring_enter (fused submit + wait)"| SyscallHandler
    UserSQRing <===>|"Shared mmap memory"| KernelSQRing
    UserCQRing <===>|"Shared mmap memory"| KernelCQRing
    UserBufSlab <===>|"Direct physical mapping"| PinnedPages
    BlockLayer -->|"Direct NVMe command queue"| NVMeDrive
    NVMeDrive -->|"PCIe Bus-Master DMA (Direct to host RAM)"| PinnedPages

    classDef jvmStyle fill:#e3f2fd,stroke:#1565c0,color:#0d47a1;
    classDef kernelBoxStyle fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20;
    classDef hwBoxStyle fill:#f5f5f5,stroke:#424242,color:#212121;
    classDef mmapStyle fill:#fff3e0,stroke:#e65100,color:#bf360c,font-weight:bold;

    class AppThread,JUringEngine jvmStyle;
    class SyscallHandler,IoUringCore,BlockLayer kernelBoxStyle;
    class NVMeDrive hwBoxStyle;
    class UserSQRing,UserCQRing,UserBufSlab,KernelSQRing,KernelCQRing,PinnedPages mmapStyle;
```

---

## 3. Level 3: Component Diagram

The Component diagram details the internal software modules inside `JUring` and how they interact with Java's Foreign Function & Memory (FFM) API.

```mermaid
flowchart TD
    subgraph JUringLibrary ["JUring Library Architecture (com.davidvlijmincx.lio.api)"]
        direction TB

        JUringFacade["JUring (Facade)<br/>High-level single-issuer API.<br/>Provides prepareReadFixed, prepareWriteFixed, submitAndWait,<br/>and buffer pooling."]

        JUringBlockingComp["JUringBlocking<br/>Zero-GC Virtual-Thread wrapper.<br/>Single dedicated platform poller thread.<br/>Lock-free MPSC ring & LockSupport.park/unpark."]

        IoUringDisp["IoUringDispatcher<br/>Core ring controller.<br/>Caches ring pointers & masks in Java primitives.<br/>Executes fused submitAndWait and in-place CQE reaping."]

        LibCDisp["LibCDispatcher<br/>Native POSIX & LibC interop.<br/>Allocates 4096-aligned buffer slabs, captures errno,<br/>and manages mmap/munmap/close."]

        ZeroGcSqeComp["ZeroGcSqe<br/>Zero-allocation SQE builder.<br/>Operates directly on raw long addresses.<br/>Emits 8x 64-bit aligned stores via VarHandle."]

        ZeroGcCqeComp["ZeroGcCqe<br/>Zero-allocation CQE parser.<br/>Extracts user_data, res, and flags<br/>via primitive VarHandle lookups."]

        ZeroGcUserDataComp["ZeroGcUserData<br/>Bit-packing encoder/decoder.<br/>Packs OperationType, bufferIndex, and opSeq/pointers<br/>into raw 64-bit integer."]

        ResultComp["Result & ResultConsumer<br/>Zero-allocation completion model.<br/>Single mutable Result instance reaped in-place<br/>and passed to consumer callback."]

        FFMLinker["Java FFM Linker & MethodHandles<br/><i>[java.lang.foreign]</i><br/>Downcall handles for io_uring_enter, io_uring_register,<br/>io_uring_setup, mmap, and munmap."]

        JUringFacade --> IoUringDisp
        JUringFacade --> ZeroGcUserDataComp
        JUringFacade --> ResultComp

        JUringBlockingComp --> IoUringDisp
        JUringBlockingComp --> LibCDisp
        
        IoUringDisp --> LibCDisp
        IoUringDisp --> ZeroGcSqeComp
        IoUringDisp --> ZeroGcCqeComp
        IoUringDisp --> ZeroGcUserDataComp
        IoUringDisp --> ResultComp
        IoUringDisp --> FFMLinker
        
        LibCDisp --> FFMLinker
    end

    classDef compPrimary fill:#0288d1,stroke:#01579b,color:#ffffff,font-weight:bold;
    classDef compSecondary fill:#00897b,stroke:#004d40,color:#ffffff;
    classDef compUtil fill:#546e7a,stroke:#263238,color:#ffffff;

    class JUringFacade,JUringBlockingComp,IoUringDisp compPrimary;
    class LibCDisp,ZeroGcSqeComp,ZeroGcCqeComp,ZeroGcUserDataComp,ResultComp compSecondary;
    class FFMLinker compUtil;
```

---

## 4. Level 4: Code & Data Structures (Memory Layout)

The Code-level diagram shows the physical memory layout of data structures mapped between Java and the Linux kernel.

### A. 64-Byte Submission Queue Entry (`io_uring_sqe`) Layout
```
+---------------+---------------+---------------+---------------+
|  opcode (1B)  |   flags (1B)  |          ioprio (2B)          |  Offset 0..3
+---------------+---------------+---------------+---------------+
|                             fd (4B)                           |  Offset 4..7
+---------------------------------------------------------------+
|                            off (8B)                           |  Offset 8..15
+---------------------------------------------------------------+
|                           addr (8B)                           |  Offset 16..23
+-------------------------------+-------------------------------+
|            len (4B)           |          rw_flags (4B)        |  Offset 24..31
+-------------------------------+-------------------------------+
|                          user_data (8B)                       |  Offset 32..39
+---------------+---------------+-------------------------------+
| buf_index (2B)|  personality  |        file_index (4B)        |  Offset 40..47
+---------------+---------------+-------------------------------+
|                           addr3 (8B)                          |  Offset 48..55
+---------------------------------------------------------------+
|                            pad (8B)                           |  Offset 56..63
+---------------------------------------------------------------+
```

### B. 16-Byte Completion Queue Entry (`io_uring_cqe`) Layout
```
+---------------------------------------------------------------+
|                          user_data (8B)                       |  Offset 0..7
+-------------------------------+-------------------------------+
|            res (4B)           |           flags (4B)          |  Offset 8..15
+-------------------------------+-------------------------------+
```
* **Why it matters:** In-place reaping loops read `user_data`, `res`, and `flags` directly from this 16-byte memory-mapped ring slot via `ZeroGcCqe` VarHandles bound to primitive `long`/`int` without allocating any intermediate `MemorySegment` objects on the Java heap.

### C. 64-Bit Bitpacked `user_data` Layouts

**1. Fixed-Buffer Operations (`READ_FIXED`, `WRITE_FIXED`):**
```
 63           56 55           40 39                            0
+---------------+---------------+-------------------------------+
| OpType (8 bits| BufIdx (16 bit|      Operation ID (40 bits)   |
|   0..255      |   0..65535    |       Monotonic opSeq         |
+---------------+---------------+-------------------------------+
```

**2. General Pointer Operations (`READ`, `WRITE`, `OPEN`, `CONNECT`, `RECV`, `SEND`):**
```
 63           56 55                                            0
+---------------+-----------------------------------------------+
| OpType (8 bits|            Native Payload Pointer (56 bits)   |
|   0..255      |           Raw off-heap memory address         |
+---------------+-----------------------------------------------+
```

**3. External Write Operations (`TYPE_WRITE_EXT = 20`):**
```
 63           56 55                                            0
+---------------+-----------------------------------------------+
| Type 20 (8 bit|              Operation ID (56 bits)           |
+---------------+-----------------------------------------------+
```
* **Why it matters:** Eliminates hash maps, off-heap state descriptors, and memory allocation during completion reaping. When a completion arrives, `(userData >>> 56) & 0xFF` extracts the operation type in a single CPU cycle, compiling directly into a fast C2 JIT `tableswitch`.

### D. Contiguous Page-Aligned Buffer Slab
```
+-----------------------------------+-----------------------------------+
|     4096-Byte Buffer [0]          |     4096-Byte Buffer [1]          |
|  Page N (Aligned to 4096 bytes)   |  Page N+1 (Aligned to 4096 bytes) |
|  Single NVMe PRP Entry (No Split) |  Single NVMe PRP Entry (No Split) |
+-----------------------------------+-----------------------------------+
```
* **Why it matters:** Slicing from an aligned slab guarantees that every 4 KB buffer is bounded within exactly one physical page, preventing double-counting against `RLIMIT_MEMLOCK` and eliminating NVMe DMA split overhead.
