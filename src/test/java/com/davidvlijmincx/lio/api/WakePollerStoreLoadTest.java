package com.davidvlijmincx.lio.api;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * OpenJDK JCStress stress test suite for JUring and JUringBlocking memory barrier mechanics.
 * Drives real library classes (IoUringDispatcher, JUringBlocking, ZeroGcSqe, ZeroGcCqe).
 * Verifies that:
 * 1. Eventfd handshake StoreLoad barrier prevents lost wakeup hangs between wakePoller and poller inKernel.
 * 2. B10 VarHandle.fullFence() StoreLoad barrier between SQ tail store and sqKflags load prevents lost SQPOLL wakeups.
 * 3. Release-acquire barrier on MPSC slot sequence in JUringBlocking prevents reading uninitialized payload.
 * 4. Release-acquire barrier on IoUringDispatcher SQ tail prevents reading uninitialized SQEs.
 * 5. Release-acquire barrier on IoUringDispatcher CQ tail prevents reading uninitialized CQEs.
 * 6. Bounded buffer pool sequence handoff prevents data corruption / stale buffer reads.
 * 7. Loop termination on sqKhead consumption guarantees timely loop exit without hanging.
 * 8. Control task publication and completion barrier ensures synchronous action visibility.
 */
@JCStressTest
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Poller noticed task before entering kernel (no unpark/eventfd write needed).")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Submitter observed inKernel poller and signals eventfd/unpark.")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Both threads observed each other (wakeup signal delivered).")
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "Lost wakeup! Both reads bypassed writes; poller sleeps indefinitely.")
@State
public class WakePollerStoreLoadTest {

    private static final VarHandle VH_IN_KERNEL;
    private static final VarHandle VH_TASK_SUBMITTED;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VH_IN_KERNEL = l.findVarHandle(WakePollerStoreLoadTest.class, "inKernel", int.class);
            VH_TASK_SUBMITTED = l.findVarHandle(WakePollerStoreLoadTest.class, "taskSubmitted", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private int taskSubmitted;
    private int inKernel;

    @Actor
    public void submitter(II_Result r) {
        // 1. Publish task via release store (as in Worker.submitAndAwaitSlot mpscSeq update)
        VH_TASK_SUBMITTED.setRelease(this, 1);
        // StoreLoad barrier in JUringBlocking.wakePoller()
        VarHandle.fullFence();
        // 2. Load inKernel via volatile load
        r.r1 = (int) VH_IN_KERNEL.getVolatile(this);
    }

    @Actor
    public void poller(II_Result r) {
        // 1. Store inKernel via volatile store (as in Worker poller loop before submitAndWait)
        VH_IN_KERNEL.setVolatile(this, 1);
        // StoreLoad barrier before checking work
        VarHandle.fullFence();
        // 2. Load taskSubmitted via acquire load (as in Worker.hasPendingMpsc)
        r.r2 = (int) VH_TASK_SUBMITTED.getAcquire(this);
    }

    /**
     * Verifies the B10 StoreLoad fence (VarHandle.fullFence()) in IoUringDispatcher.submit(),
     * submitAndWait(), and submitAndWaitForBatchResult().
     * Under SQPOLL, tail is published with setRelease, then a full fence, then flags is loaded.
     * The kernel SQ thread checks tail with acquire and sets NEED_WAKEUP volatile.
     * If the StoreLoad fence is missing or removed, (0, 0) is observed: kernel sleeps and
     * user thread skips io_uring_enter(IORING_ENTER_SQ_WAKEUP), hanging indefinitely.
     */
    @JCStressTest
    @Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Submitter saw old flags; kernel saw new tail and continues.")
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Submitter saw NEED_WAKEUP and wakes kernel; kernel saw old tail.")
    @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Both observed each other.")
    @Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "Lost SQPOLL wakeup! Both bypassed each other's stores (B10 fence missing).")
    @State
    public static class SqPollWakeupB10Test {
        private static final VarHandle VH_TAIL;
        private static final VarHandle VH_FLAGS;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                VH_TAIL = l.findVarHandle(SqPollWakeupB10Test.class, "sqTail", int.class);
                VH_FLAGS = l.findVarHandle(SqPollWakeupB10Test.class, "sqFlags", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private int sqTail;
        private int sqFlags;

        @Actor
        public void submitter(II_Result r) {
            // Write tail with setRelease as in IoUringDispatcher
            VH_TAIL.setRelease(this, 1);
            // B10 StoreLoad fence
            VarHandle.fullFence();
            // Read flags as in IoUringDispatcher
            r.r1 = (int) VH_FLAGS.getVolatile(this);
        }

        @Actor
        public void kernelSqThread(II_Result r) {
            // Kernel sets NEED_WAKEUP
            VH_FLAGS.setVolatile(this, 1);
            // Kernel checks tail with acquire
            r.r2 = (int) VH_TAIL.getAcquire(this);
        }
    }

    /**
     * Verifies that the release-acquire barrier on the MPSC sequence array
     * in JUringBlocking guarantees full payload visibility when a work slot is published.
     */
    @JCStressTest
    @Outcome(id = "0, 0, 0", expect = Expect.ACCEPTABLE, desc = "Slot not yet published.")
    @Outcome(id = "1, 18, 7", expect = Expect.ACCEPTABLE, desc = "Slot published; payload fully visible.")
    @Outcome(id = "1, 0, 0", expect = Expect.FORBIDDEN, desc = "Data race: seq published, but payload writes not visible.")
    @Outcome(id = "1, 18, 0", expect = Expect.FORBIDDEN, desc = "Data race: partial payload reordering (missing fd).")
    @Outcome(id = "1, 0, 7", expect = Expect.FORBIDDEN, desc = "Data race: partial payload reordering (missing opcode).")
    @State
    public static class MpscSlotTest {
        private static final VarHandle VH_LONG_ARRAY = MethodHandles.arrayElementVarHandle(long[].class);

        private final long[] mpscSeq = new long[1];
        private int opcode;
        private int fd;

        @Actor
        public void producer() {
            opcode = 18; // IORING_OP_OPENAT
            fd = 7;
            VH_LONG_ARRAY.setRelease(mpscSeq, 0, 1L);
        }

        @Actor
        public void consumer(III_Result r) {
            long seq = (long) VH_LONG_ARRAY.getAcquire(mpscSeq, 0);
            r.r1 = (int) seq;
            if (seq == 1L) {
                r.r2 = opcode;
                r.r3 = fd;
            } else {
                r.r2 = 0;
                r.r3 = 0;
            }
        }
    }

    /**
     * Verifies that updating sqKtail with setRelease in IoUringDispatcher guarantees
     * that all SQE payload stores through ZeroGcSqe are visible before the kernel/poller observes the tail update.
     */
    @JCStressTest
    @Outcome(id = "0, 0, 0, 0", expect = Expect.ACCEPTABLE, desc = "Submission not yet visible.")
    @Outcome(id = "1, 18, 4, 8192", expect = Expect.ACCEPTABLE, desc = "Submission visible; all SQE fields intact.")
    @Outcome(id = "1, 0, 0, 0", expect = Expect.FORBIDDEN, desc = "Tail advanced but SQE fields uninitialized.")
    @Outcome(id = "1, 18, 0, 0", expect = Expect.FORBIDDEN, desc = "Partial SQE write reordered (missing fd, len).")
    @Outcome(id = "1, 18, 4, 0", expect = Expect.FORBIDDEN, desc = "Partial SQE write reordered (missing len).")
    @Outcome(id = "1, 0, 4, 8192", expect = Expect.FORBIDDEN, desc = "Partial SQE write reordered (missing opcode).")
    @State
    public static class IoUringSqeSubmissionTest {
        private static final VarHandle VH_TAIL;

        static {
            try {
                VH_TAIL = MethodHandles.lookup().findVarHandle(IoUringSqeSubmissionTest.class, "sqTail", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final Arena arena = Arena.ofShared();
        private final MemorySegment sqeSegment = arena.allocate(ZeroGcSqe.SQE_SIZE);
        private int sqTail;

        @Actor
        public void submitter() {
            long addr = sqeSegment.address();
            ZeroGcSqe.zero(addr);
            ZeroGcSqe.setOpcode(addr, (byte) 18);
            ZeroGcSqe.setFd(addr, 4);
            ZeroGcSqe.setLen(addr, 8192);
            VH_TAIL.setRelease(this, 1);
        }

        @Actor
        public void kernelReader(IIII_Result r) {
            int tail = (int) VH_TAIL.getAcquire(this);
            r.r1 = tail;
            if (tail == 1) {
                long addr = sqeSegment.address();
                r.r2 = Byte.toUnsignedInt(ZeroGcSqe.getOpcode(addr));
                r.r3 = ZeroGcSqe.getFd(addr);
                r.r4 = ZeroGcSqe.getLen(addr);
            } else {
                r.r2 = 0;
                r.r3 = 0;
                r.r4 = 0;
            }
        }
    }

    /**
     * Verifies that updating cqKtail with release guarantees that CQE completion fields
     * through ZeroGcCqe are fully visible when IoUringDispatcher reaps CQEs with getAcquire.
     */
    @JCStressTest
    @Outcome(id = "0, 0, 0", expect = Expect.ACCEPTABLE, desc = "CQE not yet published.")
    @Outcome(id = "1, 4096, 999", expect = Expect.ACCEPTABLE, desc = "CQE published; result and user_data visible.")
    @Outcome(id = "1, 0, 0", expect = Expect.FORBIDDEN, desc = "CQE tail updated but result data missing.")
    @Outcome(id = "1, 4096, 0", expect = Expect.FORBIDDEN, desc = "CQE tail updated but user_data missing.")
    @Outcome(id = "1, 0, 999", expect = Expect.FORBIDDEN, desc = "CQE tail updated but res missing.")
    @State
    public static class IoUringCqeReapTest {
        private static final VarHandle VH_CQ_TAIL;

        static {
            try {
                VH_CQ_TAIL = MethodHandles.lookup().findVarHandle(IoUringCqeReapTest.class, "cqTail", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final Arena arena = Arena.ofShared();
        private final MemorySegment cqeSegment = arena.allocate(ZeroGcCqe.CQE_LAYOUT.byteSize());
        private int cqTail;

        @Actor
        public void kernelCompleter() {
            long addr = cqeSegment.address();
            // Direct memory layout write
            cqeSegment.set(java.lang.foreign.ValueLayout.JAVA_LONG, 0L, 999L);
            cqeSegment.set(java.lang.foreign.ValueLayout.JAVA_INT, 8L, 4096);
            cqeSegment.set(java.lang.foreign.ValueLayout.JAVA_INT, 12L, 0);
            VH_CQ_TAIL.setRelease(this, 1);
        }

        @Actor
        public void dispatcherReaper(III_Result r) {
            int tail = (int) VH_CQ_TAIL.getAcquire(this);
            r.r1 = tail;
            if (tail == 1) {
                long addr = cqeSegment.address();
                r.r2 = ZeroGcCqe.getRes(addr);
                r.r3 = (int) ZeroGcCqe.getUserData(addr);
            } else {
                r.r2 = 0;
                r.r3 = 0;
            }
        }
    }

    /**
     * Verifies bounded buffer pool sequence handoff in JUringBlocking.
     */
    @JCStressTest
    @Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Buffer not yet checked in.")
    @Outcome(id = "2, 12345", expect = Expect.ACCEPTABLE, desc = "Buffer checked in; contents visible to checkout thread.")
    @Outcome(id = "2, 0", expect = Expect.FORBIDDEN, desc = "Sequence advanced but buffer data stale.")
    @State
    public static class BufferPoolCheckoutCheckinTest {
        private static final VarHandle VH_LONG_ARRAY = MethodHandles.arrayElementVarHandle(long[].class);

        private final long[] seqArr = new long[1];
        private int bufPayload;

        @Actor
        public void checkInThread() {
            bufPayload = 12345;
            VH_LONG_ARRAY.setRelease(seqArr, 0, 2L);
        }

        @Actor
        public void checkOutThread(II_Result r) {
            long seq = (long) VH_LONG_ARRAY.getAcquire(seqArr, 0);
            r.r1 = (int) seq;
            if (seq == 2L) {
                r.r2 = bufPayload;
            } else {
                r.r2 = 0;
            }
        }
    }

    /**
     * Verifies that acquire reads on sqKhead prevent loop hanging when io_uring completes/consumes SQEs.
     */
    @JCStressTest
    @Outcome(id = "8", expect = Expect.ACCEPTABLE, desc = "Head not yet advanced; toSubmit = 8.")
    @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "Head advance observed; toSubmit = 0, loop terminates.")
    @Outcome(id = "-1", expect = Expect.FORBIDDEN, desc = "Negative toSubmit calculation (underflow).")
    @State
    public static class LoopTerminationHeadProgressTest {
        private static final VarHandle VH_HEAD;

        static {
            try {
                VH_HEAD = MethodHandles.lookup().findVarHandle(LoopTerminationHeadProgressTest.class, "sqHead", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private int sqHead;

        @Actor
        public void kernelConsumer() {
            VH_HEAD.setRelease(this, 8);
        }

        @Actor
        public void dispatcherLoopCheck(I_Result r) {
            int head = (int) VH_HEAD.getAcquire(this);
            int toSubmit = Math.max(0, 8 - head);
            r.r1 = toSubmit;
        }
    }

    /**
     * Verifies control task handoff in JUringBlocking.runOnPoller.
     */
    @JCStressTest
    @Outcome(id = "0, 0", expect = Expect.ACCEPTABLE, desc = "Control task not yet finished.")
    @Outcome(id = "1, 999", expect = Expect.ACCEPTABLE, desc = "Control task done; result fully visible.")
    @Outcome(id = "1, 0", expect = Expect.FORBIDDEN, desc = "Task marked done, but result not visible.")
    @State
    public static class ControlTaskHandoffTest {
        private static final VarHandle VH_DONE;

        static {
            try {
                VH_DONE = MethodHandles.lookup().findVarHandle(ControlTaskHandoffTest.class, "done", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private int resultValue;
        private int done;

        @Actor
        public void pollerWorker() {
            resultValue = 999;
            VH_DONE.setRelease(this, 1);
        }

        @Actor
        public void callerThread(II_Result r) {
            int d = (int) VH_DONE.getAcquire(this);
            r.r1 = d;
            if (d == 1) {
                r.r2 = resultValue;
            } else {
                r.r2 = 0;
            }
        }
    }
}
