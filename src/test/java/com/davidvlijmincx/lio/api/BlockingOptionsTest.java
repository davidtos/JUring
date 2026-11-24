package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BlockingOptions} contract: defaults, validation, capacity rounding and the memory
 * estimate that the scale documentation is based on.
 */
class BlockingOptionsTest {

    private static int nextPowerOfTwo(int value) {
        return value <= 1 ? 1 : Integer.highestOneBit(value) == value
                ? value : Integer.highestOneBit(value) << 1;
    }

    @Test
    void defaultsPreserveTheDocumentedBehaviour() {
        BlockingOptions options = BlockingOptions.defaults();
        assertEquals(BlockingOptions.DEFAULT_QUEUE_DEPTH, options.queueDepth());
        assertEquals(0, options.pollers());
        assertEquals(0, options.maxConcurrentCalls());
        assertEquals(OverflowPolicy.BLOCK, options.overflow());
        assertEquals(0, options.preParkSpins());
        assertEquals(BlockingOptions.defaultPollers(options.queueDepth()), options.resolvedPollers());
    }

    @Test
    void builderMethodsReturnNewInstances() {
        BlockingOptions defaults = BlockingOptions.defaults();
        BlockingOptions changed = defaults.withQueueDepth(64);
        assertNotSame(defaults, changed);
        assertEquals(BlockingOptions.DEFAULT_QUEUE_DEPTH, defaults.queueDepth());
        assertEquals(64, changed.queueDepth());
        assertEquals(64, changed.withPollers(2).withQueueDepth(64).queueDepth());
    }

    @Test
    void derivedCapacityMatchesTheZeroDotOneFormula() {
        for (int queueDepth : new int[]{8, 64, 256, 1024}) {
            BlockingOptions options = BlockingOptions.defaults()
                    .withQueueDepth(queueDepth)
                    .withPollers(1);
            int expected = nextPowerOfTwo(Math.max(256, queueDepth * 8));
            assertEquals(expected, options.perWorkerCapacity(1),
                    "derived per-worker capacity for queueDepth " + queueDepth);
        }
    }

    @Test
    void explicitCapacityIsSplitAndRoundedUpPerWorker() {
        BlockingOptions options = BlockingOptions.defaults()
                .withQueueDepth(64)
                .withPollers(4)
                .withMaxConcurrentCalls(100_000);
        assertEquals(4, options.resolvedPollers());
        // ceil(100_000 / 4) = 25_000 -> next power of two
        assertEquals(32_768, options.perWorkerCapacity(4));
        assertTrue(options.estimatedBytes() >= 4L * 32_768L * 88L, "estimate covers the records");
    }

    @Test
    void millionConcurrentCallsCostAboutNinetyTwoMegabytes() {
        BlockingOptions options = BlockingOptions.defaults()
                .withQueueDepth(256)
                .withPollers(4)
                .withMaxConcurrentCalls(1_000_000);
        assertEquals(262_144, options.perWorkerCapacity(4));
        long bytes = options.estimatedBytes();
        assertTrue(bytes > 90_000_000L && bytes < 95_000_000L,
                "10^6 records estimate should be ~92 MB, got " + bytes);
    }

    @Test
    void explicitPollersAreRoundedDownToAPowerOfTwo() {
        assertEquals(4, BlockingOptions.defaults().withPollers(7).resolvedPollers());
        assertEquals(1, BlockingOptions.defaults().withPollers(1).resolvedPollers());
        assertEquals(16, BlockingOptions.defaults().withPollers(16).resolvedPollers());
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BlockingOptions.defaults().withQueueDepth(0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockingOptions.defaults().withQueueDepth(BlockingOptions.MAX_QUEUE_DEPTH + 1));
        assertThrows(IllegalArgumentException.class, () -> BlockingOptions.defaults().withPollers(0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockingOptions.defaults().withPollers(BlockingOptions.MAX_POLLERS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> BlockingOptions.defaults().withMaxConcurrentCalls(-1));
        assertThrows(IllegalArgumentException.class,
                () -> BlockingOptions.defaults().withMaxConcurrentCalls(17 * (1 << 20)));
        assertThrows(IllegalArgumentException.class,
                () -> BlockingOptions.defaults().withPreParkSpins(-1));
        assertThrows(NullPointerException.class,
                () -> BlockingOptions.defaults().withOverflow(null));
    }

    @Test
    void preParkSpinIsCarriedThrough() {
        BlockingOptions options = BlockingOptions.defaults().withPreParkSpins(128);
        assertEquals(128, options.preParkSpins());
        assertEquals(0, BlockingOptions.defaults().preParkSpins());
    }
}
