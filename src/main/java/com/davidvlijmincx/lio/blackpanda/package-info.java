/**
 * Tier 2 (experimental) public API of JUring: {@code Project BlackPanda}, a
 * Kafka-style partition log engine built on the {@code com.davidvlijmincx.lio.api}
 * engine.
 *
 * <p>This package is publicly visible but carries <b>no compatibility guarantee</b>:
 * its types may change or be removed in a minor release (see {@code docs/api-stability.md}).
 * The supported, Tier 1 API is {@code com.davidvlijmincx.lio.api}.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link com.davidvlijmincx.lio.blackpanda.UringPartitionLog} — the write engine:
 *       concurrent virtual threads append records to an off-heap slab, a dedicated
 *       {@code io_uring} flusher thread writes them to {@code O_DIRECT} segment files with
 *       group commit, and committed records are published to an in-memory tail buffer.</li>
 *   <li>{@link com.davidvlijmincx.lio.blackpanda.UringPartitionManager} — owns the active
 *       and closed {@link com.davidvlijmincx.lio.blackpanda.UringLogSegment}s of one
 *       partition directory and performs segment rolling and lookup.</li>
 *   <li>{@link com.davidvlijmincx.lio.blackpanda.UringHistoricReader} — reads records that
 *       have already fallen out of the tail buffer from disk with
 *       {@code IORING_OP_READ_FIXED} direct I/O; its nested
 *       {@link com.davidvlijmincx.lio.blackpanda.UringHistoricReader.RecordConsumer} receives
 *       the record payloads.</li>
 *   <li>{@link com.davidvlijmincx.lio.blackpanda.PartitionTailBuffer} — the lock-free,
 *       off-heap circular buffer that serves real-time tailing consumers.</li>
 *   <li>{@link com.davidvlijmincx.lio.blackpanda.RecordBatch} — the on-disk batch framing,
 *       header layout and CRC helpers shared by the writer and readers.</li>
 * </ul>
 *
 * <p>Files use Kafka-compatible naming: a segment is a zero-padded base-offset
 * {@code .log} file with companion {@code .index} and {@code .timeindex} files documented by
 * {@link com.davidvlijmincx.lio.blackpanda.UringSparseIndex}.
 */
package com.davidvlijmincx.lio.blackpanda;
