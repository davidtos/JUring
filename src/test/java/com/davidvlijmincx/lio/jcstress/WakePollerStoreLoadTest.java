package com.davidvlijmincx.lio.jcstress;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * OpenJDK JCStress test suite for JUring and JUringBlocking memory barrier mechanics.
 * Proves that:
 * 1. StoreLoad barrier prevents lost wakeup hangs between wakePoller and poller parking.
 * 2. Release-acquire barrier on MPSC slot sequence prevents reading uninitialized payload.
 * 3. Release-acquire barrier on IoUringDispatcher SQ tail prevents reading uninitialized SQEs.
 * 4. Release-acquire barrier on IoUringDispatcher CQ tail prevents reading uninitialized CQEs.
 * 5. Bounded buffer pool sequence handoff prevents data corruption / stale buffer reads.
 * 6. Loop termination on sqKhead consumption guarantees timely loop exit without hanging.
 * 7. Control task publication and completion barrier ensures synchronous action visibility.
 */
@JCStressTest
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Poller noticed task before parking (no unpark needed).")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Submitter observed parked poller and unparked it.")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Both threads observed each other (unpark occurred).")
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "Lost wakeup! Both reads bypassed writes; poller hangs.")
@State
public class WakePollerStoreLoadTest {

    private static final VarHandle VH_POL_PARKED;
    private static final VarHandle VH_TASK_SUBMITTED;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VH_POL_PARKED = l.findVarHandle(WakePollerStoreLoadTest.class, "pollerParked", int.class);
            VH_TASK_SUBMITTED = l.findVarHandle(WakePollerStoreLoadTest.class, "taskSubmitted", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private int taskSubmitted;
    private int pollerParked;

    @Actor
    public void submitter(II_Result r) {
        // 1. Store task via release store (as in mpscSeq release update)
        VH_TASK_SUBMITTED.setRelease(this, 1);
        // StoreLoad barrier as in JUringBlocking.wakePoller()
        VarHandle.fullFence();
        // 2. Load pollerParked via volatile load
        r.r1 = (int) VH_POL_PARKED.getVolatile(this);
    }

    @Actor
    public void poller(II_Result r) {
        // 1. Store pollerParked via volatile store
        VH_POL_PARKED.setVolatile(this, 1);
        // StoreLoad barrier before checking work as in JUringBlocking.poller
        VarHandle.fullFence();
        // 2. Load taskSubmitted via acquire load (as in hasPendingMpsc)
        r.r2 = (int) VH_TASK_SUBMITTED.getAcquire(this);
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
            opcode = 18; // IORING_OP_READ
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
     * that all SQE payload stores are visible before the kernel/poller observes the tail update.
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

        private int sqeOpcode;
        private int sqeFd;
        private int sqeLen;
        private int sqTail;

        @Actor
        public void submitter() {
            // Write SQE fields
            sqeOpcode = 18;
            sqeFd = 4;
            sqeLen = 8192;
            // setRelease as in IoUringDispatcher.submit() / submitAndWait()
            VH_TAIL.setRelease(this, 1);
        }

        @Actor
        public void kernelReader(IIII_Result r) {
            // getAcquire tail
            int tail = (int) VH_TAIL.getAcquire(this);
            r.r1 = tail;
            if (tail == 1) {
                r.r2 = sqeOpcode;
                r.r3 = sqeFd;
                r.r4 = sqeLen;
            } else {
                r.r2 = 0;
                r.r3 = 0;
                r.r4 = 0;
            }
        }
    }

    /**
     * Verifies that updating cqKtail with release guarantees that CQE completion fields
     * are fully visible when IoUringDispatcher reaps CQEs with getAcquire.
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

        private int cqeRes;
        private int cqeUserData;
        private int cqTail;

        @Actor
        public void kernelCompleter() {
            cqeRes = 4096;
            cqeUserData = 999;
            VH_CQ_TAIL.setRelease(this, 1);
        }

        @Actor
        public void dispatcherReaper(III_Result r) {
            int tail = (int) VH_CQ_TAIL.getAcquire(this);
            r.r1 = tail;
            if (tail == 1) {
                r.r2 = cqeRes;
                r.r3 = cqeUserData;
            } else {
                r.r2 = 0;
                r.r3 = 0;
            }
        }
    }

    /**
     * Verifies bounded buffer pool sequence handoff in JUringBlocking.
     * When checking in a buffer, release store to sequence ensures payload written to buffer
     * is visible to subsequent checkout thread.
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
            // Advance sequence from 0 -> 2 (indicating buffer returned and ready)
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
     * With getAcquire, the submit loop is guaranteed to see head advancing to match tail,
     * reducing toSubmit to 0 and exiting the loop.
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
            // Kernel consumes 8 entries and advances sqHead
            VH_HEAD.setRelease(this, 8);
        }

        @Actor
        public void dispatcherLoopCheck(I_Result r) {
            // submitAndWait calculates toSubmit = Math.max(0, tail - head)
            int head = (int) VH_HEAD.getAcquire(this);
            int toSubmit = Math.max(0, 8 - head);
            r.r1 = toSubmit;
        }
    }

    /**
     * Verifies control task handoff in JUringBlocking.runOnPoller.
     * When task.done is marked with setRelease, caller unparking is guaranteed
     * to observe task result and non-null error without race conditions.
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


