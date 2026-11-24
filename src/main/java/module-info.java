/**
 * JUring: direct, asynchronous file I/O on Linux {@code io_uring} via the Java Foreign
 * Function and Memory API.
 *
 * <p>The module exports {@code com.davidvlijmincx.lio.api}, the supported public API:
 * the single-threaded {@code io_uring} ring ({@link com.davidvlijmincx.lio.api.JUring}),
 * its blocking and virtual-thread facade ({@link com.davidvlijmincx.lio.api.JUringBlocking}),
 * the registered-file convenience layer ({@link com.davidvlijmincx.lio.api.JuringFile}) and
 * the buffered streams ({@link com.davidvlijmincx.lio.api.JuringInputStream},
 * {@link com.davidvlijmincx.lio.api.JuringOutputStream}), together with the multi-ring engine
 * ({@link com.davidvlijmincx.lio.api.IoShard},
 * {@link com.davidvlijmincx.lio.api.RingGroup}), durability and group commit
 * ({@link com.davidvlijmincx.lio.api.GroupCommit}) and the metrics and JMX types.
 *
 * <p>The module requires {@code java.management} for the optional JMX bindings and
 * {@code jdk.management} statically.
 */
module JUring {
    exports com.davidvlijmincx.lio.api;

    requires static jdk.management;
    requires java.management;
}
