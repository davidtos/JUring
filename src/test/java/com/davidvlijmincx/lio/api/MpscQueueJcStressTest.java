package com.davidvlijmincx.lio.api;

import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.IIII_Result;

/**
 * jcstress coverage of the <em>real</em> {@link MpscQueue} — the bounded lock-free
 * multi-producer / single-consumer sequence ring that carries one shard's inbound tasks and
 * cross-shard handoff continuations. Unlike the standalone VarHandle models in
 * {@link WakePollerStoreLoadTest}, this test calls the production {@code offer}/{@code poll}
 * methods concurrently, so it exercises the queue's own CAS tail reservation, release
 * publication of the slot contents and acquire consumption.
 *
 * <p><b>Design.</b> Two producer {@link Actor}s each publish {@value #PER_PRODUCER} uniquely
 * encoded values, then set their own volatile done flag. The single consumer {@link Actor}
 * polls concurrently in a loop and records, from whatever it observes, the number of values,
 * duplicates and per-producer ordering violations; once both done flags are visible (each is
 * set after the producer's last {@code offer} with volatile release ordering) it does one final
 * drain and returns. The drain is what makes termination correct: an empty poll may race with a
 * publication that only becomes visible after the done flags are observed.
 *
 * <p><b>Why a FORBIDDEN outcome is a genuine library bug.</b> The queue capacity equals the
 * total number of offers (16), so {@code offer} can never legitimately report "full": a
 * rejection means the ring computed its free space wrongly. All offered values are unique, and
 * an MPSC queue must preserve FIFO order per producer. So the only acceptable result is
 * {@code 16, 0, 0, 0} — all 16 publications observed exactly once, in per-producer order, with
 * no rejected offer. Fewer values means a lost publication (slot overwritten or a null element
 * returned from a published slot), a duplicate means a slot was reused/published twice,
 * out-of-order means the sequence protocol was violated, and a rejection means the full
 * check is broken. None of those can be caused by scheduling or backpressure: the consumer only
 * frees slots, the producers never exceed the capacity, and the done flags make the end state
 * deterministic rather than timing-dependent.
 *
 * <p><b>What this cannot assert without internals.</b> The queue does not expose its head,
 * tail or slot sequence numbers, so the test cannot attribute a duplicate to a specific slot,
 * and it cannot assert a global (cross-producer) order: for an MPSC queue the only observable
 * order guarantee is FIFO per producer, because the relative reservation order of two different
 * producers is not externally determined. The consumer is a single actor because {@code poll()}
 * is a single-consumer API by contract.
 *
 * <p>This class is compiled by the normal test-compile; it is executed by the jcstress runner
 * (the separate {@code -Pjcstress} workflow-dispatch job), not by the JUnit suite.
 */
@JCStressTest
@Outcome(id = "16, 0, 0, 0", expect = Expect.ACCEPTABLE,
        desc = "All 16 publications observed exactly once, in per-producer order; no offer rejected.")
@Outcome(expect = Expect.FORBIDDEN,
        desc = "MpscQueue lost, duplicated or reordered a publication, or reported a full queue.")
@State
public class MpscQueueJcStressTest {

    /** Number of concurrent producer actors. */
    static final int PRODUCERS = 2;
    /** Values offered by each producer; total == {@link #QUEUE_CAPACITY}, so "full" is impossible. */
    static final int PER_PRODUCER = 8;
    /** Queue capacity: exactly the number of offers, so a legitimate rejection cannot happen. */
    static final int QUEUE_CAPACITY = PRODUCERS * PER_PRODUCER;
    /** Encodes (producer, sequence) into one non-zero int for the boxed payload. */
    private static final int ENCODING = 1_000;

    private final MpscQueue queue = new MpscQueue(QUEUE_CAPACITY);
    private volatile boolean producerZeroDone;
    private volatile boolean producerOneDone;
    private volatile boolean producerZeroOfferRejected;
    private volatile boolean producerOneOfferRejected;

    @Actor
    public void producerZero() {
        for (int sequence = 0; sequence < PER_PRODUCER; sequence++) {
            if (!queue.offer(item(0, sequence))) {
                producerZeroOfferRejected = true;
            }
        }
        producerZeroDone = true;
    }

    @Actor
    public void producerOne() {
        for (int sequence = 0; sequence < PER_PRODUCER; sequence++) {
            if (!queue.offer(item(1, sequence))) {
                producerOneOfferRejected = true;
            }
        }
        producerOneDone = true;
    }

    @Actor
    public void consumer(IIII_Result result) {
        int[] seen = new int[PRODUCERS];
        int[] lastSequence = new int[PRODUCERS];
        int[] stats = new int[3]; // observed, duplicates, out-of-order/invalid
        for (int producer = 0; producer < PRODUCERS; producer++) {
            lastSequence[producer] = -1;
        }
        while (true) {
            Object element = queue.poll();
            if (element != null) {
                record(stats, seen, lastSequence, element);
                continue;
            }
            if (producerZeroDone && producerOneDone) {
                // The empty poll above may have raced with an offer that was not yet visible, while
                // the done flags became visible before this check. Each flag is set with volatile
                // release ordering after the producer's last offer, so observing both with acquire
                // makes every publication visible; a final drain therefore cannot miss an element.
                while ((element = queue.poll()) != null) {
                    record(stats, seen, lastSequence, element);
                }
                break;
            }
            Thread.onSpinWait();
        }
        result.r1 = stats[0];
        result.r2 = stats[1];
        result.r3 = stats[2];
        result.r4 = (producerZeroOfferRejected ? 1 : 0) | (producerOneOfferRejected ? 2 : 0);
    }

    private static Integer item(int producer, int sequence) {
        return producer * ENCODING + sequence + 1;
    }

    private static void record(int[] stats, int[] seen, int[] lastSequence, Object element) {
        if (!(element instanceof Integer value)) {
            stats[2]++;
            return;
        }
        int zeroBased = value - 1;
        int producer = zeroBased / ENCODING;
        int sequence = zeroBased % ENCODING;
        if (producer < 0 || producer >= PRODUCERS || sequence < 0 || sequence >= PER_PRODUCER) {
            stats[2]++;
            return;
        }
        stats[0]++;
        int bit = 1 << sequence;
        if ((seen[producer] & bit) != 0) {
            stats[1]++;
        }
        seen[producer] |= bit;
        if (sequence <= lastSequence[producer]) {
            stats[2]++;
        }
        lastSequence[producer] = sequence;
    }
}
