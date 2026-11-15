/**
 * JUring: direct, asynchronous file I/O on Linux {@code io_uring} via the Java Foreign
 * Function and Memory API.
 *
 * <p>The module exports exactly two packages. {@code com.davidvlijmincx.lio.api} is the
 * supported Tier 1 API: the single-threaded {@code io_uring} ring
 * ({@link com.davidvlijmincx.lio.api.JUring}), its blocking and virtual-thread facade
 * ({@link com.davidvlijmincx.lio.api.JUringBlocking}), the registered-file convenience layer
 * ({@link com.davidvlijmincx.lio.api.JuringFile}) and the buffered streams
 * ({@link com.davidvlijmincx.lio.api.JuringInputStream},
 * {@link com.davidvlijmincx.lio.api.JuringOutputStream}), together with the multi-ring engine
 * ({@link com.davidvlijmincx.lio.api.IoShard},
 * {@link com.davidvlijmincx.lio.api.RingGroup}), durability and group commit
 * ({@link com.davidvlijmincx.lio.api.GroupCommit}) and the metrics and JMX types.
 *
 * <p>{@code com.davidvlijmincx.lio.blackpanda} is the Tier 2 (experimental) partition-log
 * engine built on the same ring; it is publicly visible but carries no compatibility
 * guarantee. Its entry point is
 * {@link com.davidvlijmincx.lio.blackpanda.UringPartitionLog}.
 *
 * <p>The module requires {@code java.management} for the optional JMX bindings and
 * {@code jdk.management} statically.
 */
module JUring {
    exports com.davidvlijmincx.lio.api;
    exports com.davidvlijmincx.lio.blackpanda;

    requires static jdk.management;
    requires java.management;
}
