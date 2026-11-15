/**
 * Tier 1 public API of JUring: direct, asynchronous file I/O on Linux {@code io_uring}.
 *
 * <p>Every type in this package is part of the supported public API. The package provides
 * three layers:
 * <ul>
 *   <li><b>Ring engines</b> — {@link com.davidvlijmincx.lio.api.JUring} is the single-threaded
 *       {@code io_uring} engine: an application creates one ring per owning thread, submits
 *       operations, and reaps completions from the same thread. Applications that prefer a
 *       blocking call style, including virtual threads, use
 *       {@link com.davidvlijmincx.lio.api.JUringBlocking} instead.
 *       {@link com.davidvlijmincx.lio.api.JuringFile} layers registered files and fixed
 *       buffers on top of a {@code JUring} ring for one file at a time.</li>
 *   <li><b>Multi-ring engine</b> — {@link com.davidvlijmincx.lio.api.IoShard} owns one
 *       {@code io_uring} per shard thread, and {@link com.davidvlijmincx.lio.api.RingGroup}
 *       spreads work over several shards. {@link com.davidvlijmincx.lio.api.ShardOptions},
 *       {@link com.davidvlijmincx.lio.api.ShardContext},
 *       {@link com.davidvlijmincx.lio.api.ShardTask} and
 *       {@link com.davidvlijmincx.lio.api.CompletionHandler} configure and drive a shard.</li>
 *   <li><b>Buffered streams and durability</b> — {@link com.davidvlijmincx.lio.api.JuringInputStream}
 *       and {@link com.davidvlijmincx.lio.api.JuringOutputStream} provide read-ahead and
 *       write-behind {@code fstream}-style I/O;
 *       {@link com.davidvlijmincx.lio.api.GroupCommit} batches durability for concurrent
 *       writers.</li>
 * </ul>
 *
 * <p>Supporting types describe submissions and completions
 * ({@link com.davidvlijmincx.lio.api.Result},
 * {@link com.davidvlijmincx.lio.api.OperationType},
 * {@link com.davidvlijmincx.lio.api.SqeOptions},
 * {@link com.davidvlijmincx.lio.api.IoUringOptions},
 * {@link com.davidvlijmincx.lio.api.LinuxOpenOptions},
 * {@link com.davidvlijmincx.lio.api.FileDescriptor}) and expose metrics
 * ({@link com.davidvlijmincx.lio.api.ShardStats},
 * {@link com.davidvlijmincx.lio.api.ShardMetrics},
 * {@link com.davidvlijmincx.lio.api.RingMetrics},
 * {@link com.davidvlijmincx.lio.api.GroupMetrics},
 * {@link com.davidvlijmincx.lio.api.LatencyHistogram},
 * {@link com.davidvlijmincx.lio.api.LatencySnapshot},
 * {@link com.davidvlijmincx.lio.api.MetricsJmx},
 * {@link com.davidvlijmincx.lio.api.IoShardMetricsMXBean},
 * {@link com.davidvlijmincx.lio.api.RingGroupMetricsMXBean}).
 *
 * <p>Checked I/O failures are reported as {@link com.davidvlijmincx.lio.api.JuringException},
 * which extends {@link java.io.IOException}.
 */
package com.davidvlijmincx.lio.api;
