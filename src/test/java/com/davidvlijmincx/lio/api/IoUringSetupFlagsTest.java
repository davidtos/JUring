package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setup-flag validation and platform probe.
 *
 * <p>The public {@link IoUringOptions} enum mirrors the kernel flag set, but several flags cannot
 * be honoured by this implementation (no parameter setters, unsupported features). Passing them
 * used to reach the kernel and fail with a cryptic {@code EINVAL}/misleading message; the public
 * surface now rejects them with an actionable {@link IllegalArgumentException}.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class IoUringSetupFlagsTest {

    private static void assertRejected(IoUringOptions option, String expectedMessagePart) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new JUring(8, IORING_SETUP_SINGLE_ISSUER, option).close());
        assertTrue(error.getMessage().contains(expectedMessagePart),
                () -> "expected message containing '" + expectedMessagePart + "' but was: " + error.getMessage());
    }

    @Test
    void deferTaskrunRequiresSingleIssuer() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new JUring(8, IORING_SETUP_DEFER_TASKRUN).close());
        assertTrue(error.getMessage().contains("IORING_SETUP_DEFER_TASKRUN requires IORING_SETUP_SINGLE_ISSUER"));
    }

    @Test
    void unsupportedFlagsAreRejectedBeforeSetup() {
        assertRejected(IORING_SETUP_SQ_AFF, "IORING_SETUP_SQ_AFF");
        assertRejected(IORING_SETUP_CQSIZE, "IORING_SETUP_CQSIZE");
        assertRejected(IORING_SETUP_NO_MMAP, "IORING_SETUP_NO_MMAP");
        assertRejected(IORING_SETUP_REGISTERED_FD_ONLY, "IORING_SETUP_REGISTERED_FD_ONLY");
        assertRejected(IORING_SETUP_HYBRID_IOPOLL, "IORING_SETUP_HYBRID_IOPOLL");
        assertRejected(IORING_SETUP_IOPOLL, "IORING_SETUP_IOPOLL");
    }

    @Test
    void supportedFlagCombinationsConstructAndClose() {
        int[][] combinations = {
                {},
                {IORING_SETUP_SINGLE_ISSUER.value},
                {IORING_SETUP_SINGLE_ISSUER.value, IORING_SETUP_COOP_TASKRUN.value, IORING_SETUP_DEFER_TASKRUN.value},
                {IORING_SETUP_SQE128.value},
                {IORING_SETUP_CQE32.value},
                {IORING_SETUP_NO_SQARRAY.value},
                {IORING_SETUP_SINGLE_ISSUER.value, IORING_SETUP_SQE128.value, IORING_SETUP_CQE32.value},
        };
        for (int[] flags : combinations) {
            IoUringOptions[] options = new IoUringOptions[flags.length];
            for (int i = 0; i < flags.length; i++) {
                options[i] = optionFor(flags[i]);
            }
            try (JUring ignored = new JUring(8, options)) {
                // constructing and closing without a native error is the assertion
            }
        }
    }

    @Test
    void attachedRingRejectsSqpollAndUnsupportedFlags() {
        try (JUring parent = new JUring(8, IORING_SETUP_SINGLE_ISSUER)) {
            assertThrows(IllegalArgumentException.class,
                    () -> parent.getSharedWorkerRing(8, IORING_SETUP_SQPOLL));
            assertThrows(IllegalArgumentException.class,
                    () -> parent.getSharedWorkerRing(8, IORING_SETUP_NO_MMAP));
        }
    }

    @Test
    void isSupportedTrueOnThisHost() {
        assertTrue(JUring.isSupported(), "this host supports io_uring (the rest of the suite runs)");
    }

    private static IoUringOptions optionFor(int value) {
        for (IoUringOptions option : IoUringOptions.values()) {
            if (option.value == value) {
                return option;
            }
        }
        throw new IllegalArgumentException("no IoUringOptions for value " + value);
    }
}
