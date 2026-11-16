package com.davidvlijmincx.lio.api;

/**
 * Immutable snapshot of one {@code io_uring} ring's counters and occupancy (B5).
 *
 * <p>Counters count every SQE/CQE that passes through the ring, including runtime-internal
 * operations (for example the shard runtime's eventfd wakeup {@code READ} and the ring-wide
 * shutdown cancel), because they are the kernel-level truth for that ring. User-level task
 * counters live in {@link ShardStats}.
 *
 * <p>The record is produced by {@link JUring#ringMetrics()} from any thread. Snapshots are
 * a <em>best-effort</em> telemetry view, not linearizable: they may lag the ring owner by a
 * loop pass, and a snapshot taken across a life-cycle transition can be internally
 * inconsistent by a few events. After {@link JUring#close()} the snapshot is frozen:
 * counters keep their final values, {@code sqQueued} and {@code cqReady} are reported as
 * {@code 0} (the ring memory is unmapped) and {@code sqEntries}/{@code cqEntries} keep
 * their effective values.
 *
 * <p>Per the B5 design (amendment A1.1), {@code timedOut} counts reaped completions whose
 * result is {@code -ETIME (-62)} — the raw ring's io_uring timeout result — or
 * {@code -ETIMEDOUT (-110)} (defensive; the {@code JuringFile} mapping layer never reaches
 * raw ring metrics). {@code cancelled} counts {@code -ECANCELED (-125)}.
 *
 * @param sqesPrepared     successful {@code getSqe()} allocations (SQEs taken from the SQ ring)
 * @param submitted        SQEs the kernel has consumed (SQ {@code khead} progress, owner-observed)
 * @param reaped           CQEs consumed by the library (CQ {@code khead} progress; incl. failed)
 * @param completed        reaped CQEs with result {@code >= 0}
 * @param failed           reaped CQEs with result {@code < 0}
 * @param cancelled        reaped CQEs with result {@code -ECANCELED}
 * @param timedOut         reaped CQEs with result {@code -ETIME} or {@code -ETIMEDOUT}
 * @param inFlight         {@code max(0, submitted - reaped)}
 * @param enters           {@code io_uring_enter()} calls (submit/wait/poll paths)
 * @param submitBatches    flush calls that set a non-empty SQ tail
 * @param submitErrors     {@code enter()} failures not classified as retries (e.g. {@code EBUSY})
 * @param submitRetries    {@code enter()} {@code EAGAIN}/{@code EINTR} retries
 * @param sqFullEvents     {@code getSqe()} returned 0 because the SQ ring was full
 * @param sqQueued         snapshot: prepared - kernel-consumed (SQ occupancy; live read)
 * @param sqEntries        effective SQ ring depth
 * @param cqReady          snapshot: CQEs available to reap (live read)
 * @param cqEntries        effective CQ ring depth
 * @param cqOverflowEvents snapshot: kernel {@code cq.koverflow} (u32; best effort)
 * @param cqFlushEnters    non-blocking {@code enter(GETEVENTS, min_complete=0)} flushes issued to
 *                         drain the kernel CQ-overflow list and run deferred task work
 */
public record RingMetrics(
        long sqesPrepared,
        long submitted,
        long reaped,
        long completed,
        long failed,
        long cancelled,
        long timedOut,
        long inFlight,
        long enters,
        long submitBatches,
        long submitErrors,
        long submitRetries,
        long sqFullEvents,
        long sqQueued,
        int sqEntries,
        long cqReady,
        int cqEntries,
        long cqOverflowEvents,
        long cqFlushEnters) {
}
