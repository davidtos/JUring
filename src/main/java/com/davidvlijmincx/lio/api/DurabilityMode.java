package com.davidvlijmincx.lio.api;

/**
 * Durability mode of a {@link JuringFile} and of the B6 durability points on the buffered
 * streams / group commit (see {@code docs/b6-design.md} §2.2 and §3.1).
 *
 * <table>
 *   <caption>Durability semantics</caption>
 *   <tr><th>mode</th><th>meaning</th><th>sync at a durability point</th></tr>
 *   <tr><td>{@link #WRITEBACK}</td><td>completion means the kernel accepted the bytes;
 *       durability is the caller's job (today's default)</td><td>none</td></tr>
 *   <tr><td>{@link #FDATASYNC}</td><td>a durability point flushes file data</td>
 *       <td>exactly one {@code fdatasync}</td></tr>
 *   <tr><td>{@link #FSYNC}</td><td>a durability point flushes data and metadata</td>
 *       <td>exactly one {@code fsync}</td></tr>
 *   <tr><td>{@link #DSYNC}</td><td>the file is opened with {@code O_DSYNC}; every write
 *       completion is durable</td><td>none</td></tr>
 * </table>
 *
 * <p>{@link #DSYNC} is a <b>contract assertion about the backing file</b>, not a mode
 * that silently adds the open flag: selecting it for a stream/group commit whose
 * {@link JuringFile#durability()} is not {@link #DSYNC} fails with
 * {@link IllegalArgumentException}. {@link JuringFile#durability()} derives the mode from
 * the open flags ({@code O_DSYNC}/{@code O_SYNC} → {@link #DSYNC}, else
 * {@link #WRITEBACK}). A durability mode never changes SQE preparation or the ring
 * ownership rules.
 */
public enum DurabilityMode {

    /** Completion is kernel acceptance only; no implicit sync (today's default). */
    WRITEBACK,
    /** A durability point issues exactly one {@code fdatasync} (file data). */
    FDATASYNC,
    /** A durability point issues exactly one {@code fsync} (data and metadata). */
    FSYNC,
    /** The backing file is opened with {@code O_DSYNC}; write completions are durable, no explicit sync. */
    DSYNC
}
