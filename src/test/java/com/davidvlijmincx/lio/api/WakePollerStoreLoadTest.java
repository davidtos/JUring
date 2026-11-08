package com.davidvlijmincx.lio.api;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

@JCStressTest
@Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Poller noticed task before entering kernel (no unpark/eventfd write needed).")
@Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Submitter observed inKernel poller and signals eventfd/unpark.")
@Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Both threads observed each other (wakeup signal delivered).")
@Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "Lost wakeup! Both reads bypassed writes; poller sleeps indefinitely.")
@State
public class WakePollerStoreLoadTest {

    private int inKernel;
    private int taskSubmitted;
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

    @Actor
    public void submitter(II_Result r) {
        VH_TASK_SUBMITTED.setRelease(this, 1);
        VarHandle.fullFence();
        r.r1 = (int) VH_IN_KERNEL.getVolatile(this);
    }

    @Actor
    public void poller(II_Result r) {
        VH_IN_KERNEL.setVolatile(this, 1);
        VarHandle.fullFence();
        r.r2 = (int) VH_TASK_SUBMITTED.getAcquire(this);
    }

    @JCStressTest
    @Outcome(id = "0, 1", expect = Expect.ACCEPTABLE, desc = "Submitter saw old flags; kernel saw new tail and continues.")
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Submitter saw NEED_WAKEUP and wakes kernel; kernel saw old tail.")
    @Outcome(id = "1, 1", expect = Expect.ACCEPTABLE, desc = "Both observed each other.")
    @Outcome(id = "0, 0", expect = Expect.FORBIDDEN, desc = "Lost SQPOLL wakeup! Both bypassed each other's stores (missing StoreLoad barrier).")
    @State
    public static class SqPollWakeupTest {
        private static final VarHandle VH_TAIL;
        private static final VarHandle VH_FLAGS;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                VH_TAIL = l.findVarHandle(SqPollWakeupTest.class, "sqTail", int.class);
                VH_FLAGS = l.findVarHandle(SqPollWakeupTest.class, "sqFlags", int.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private int sqTail;
        private int sqFlags;

        @Actor
        public void submitter(II_Result r) {
            VH_TAIL.setRelease(this, 1);
            VarHandle.fullFence();
            r.r1 = (int) VH_FLAGS.getVolatile(this);
        }

        @Actor
        public void kernelSqThread(II_Result r) {
            VH_FLAGS.setVolatile(this, 1);
            VarHandle.fullFence();
            r.r2 = (int) VH_TAIL.getAcquire(this);
        }
    }

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

        private final Arena arena = Arena.ofAuto();
        private final MemorySegment sqeSegment = arena.allocate(Sqe.SQE_SIZE);
        private int sqTail;

        @Actor
        public void submitter() {
            long addr = sqeSegment.address();
            Sqe.zero(addr);
            Sqe.setOpcode(addr, (byte) 18);
            Sqe.setFd(addr, 4);
            Sqe.setLen(addr, 8192);
            VH_TAIL.setRelease(this, 1);
        }

        @Actor
        public void kernelReader(IIII_Result r) {
            int tail = (int) VH_TAIL.getAcquire(this);
            r.r1 = tail;
            if (tail == 1) {
                long addr = sqeSegment.address();
                r.r2 = Byte.toUnsignedInt(Sqe.getOpcode(addr));
                r.r3 = Sqe.getFd(addr);
                r.r4 = Sqe.getLen(addr);
            } else {
                r.r2 = 0;
                r.r3 = 0;
                r.r4 = 0;
            }
        }
    }

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

        private final Arena arena = Arena.ofAuto();
        private final MemorySegment cqeSegment = arena.allocate(Cqe.CQE_LAYOUT.byteSize());
        private int cqTail;

        @Actor
        public void kernelCompleter() {
            long addr = cqeSegment.address();
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
                r.r2 = Cqe.getRes(addr);
                r.r3 = (int) Cqe.getUserData(addr);
            } else {
                r.r2 = 0;
                r.r3 = 0;
            }
        }
    }

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

    @JCStressTest
    @Outcome(id = "1, 0", expect = Expect.ACCEPTABLE, desc = "Slot still in STATE_PARKED; result not yet published.")
    @Outcome(id = "2, 4096", expect = Expect.ACCEPTABLE, desc = "Slot in STATE_DONE; CQE result visible to virtual thread.")
    @Outcome(id = "2, 0", expect = Expect.FORBIDDEN, desc = "Slot marked STATE_DONE, but slotResult write was reordered/invisible.")
    @State
    public static class SlotCompletionHandoffTest {
        private static final VarHandle VH_INT_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);
        private static final int STATE_PARKED = 1;
        private static final int STATE_DONE = 2;

        private final int[] slotState = new int[]{STATE_PARKED};
        private final long[] slotResult = new long[1];

        @Actor
        public void pollerCompleter() {
            slotResult[0] = 4096L;
            VH_INT_ARRAY.compareAndSet(slotState, 0, STATE_PARKED, STATE_DONE);
        }

        @Actor
        public void virtualThreadWaiter(IJ_Result r) {
            int state = (int) VH_INT_ARRAY.getAcquire(slotState, 0);
            r.r1 = state;
            if (state == STATE_DONE) {
                r.r2 = slotResult[0];
            } else {
                r.r2 = 0L;
            }
        }
    }

    @JCStressTest
    @Outcome(id = "false, 0", expect = Expect.ACCEPTABLE, desc = "Control task not yet finished.")
    @Outcome(id = "true, 999", expect = Expect.ACCEPTABLE, desc = "Control task done; result fully visible.")
    @Outcome(id = "true, 0", expect = Expect.FORBIDDEN, desc = "Task marked done, but result not visible.")
    @State
    public static class ControlTaskHandoffTest {
        private int resultValue;
        private volatile boolean done;

        @Actor
        public void pollerWorker() {
            resultValue = 999;
            done = true;
        }

        @Actor
        public void callerThread(ZI_Result r) {
            boolean d = done;
            r.r1 = d;
            if (d) {
                r.r2 = resultValue;
            } else {
                r.r2 = 0;
            }
        }
    }
}
