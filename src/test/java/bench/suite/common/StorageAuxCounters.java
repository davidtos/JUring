package bench.suite.common;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * JMH auxiliary counters for reporting system call amortizations and processed bytes
 * as dedicated columns in the benchmark results table.
 */
@AuxCounters(AuxCounters.Type.OPERATIONS)
@State(Scope.Thread)
public class StorageAuxCounters {

    /**
     * Estimated or measured system calls executed during the invocation.
     */
    public int syscalls;

    /**
     * Total I/O bytes processed during the invocation.
     */
    public long bytesProcessed;

    /**
     * Increments the counters for an invocation.
     *
     * @param numSyscalls number of system calls issued
     * @param bytes       number of bytes read or written
     */
    public void record(int numSyscalls, long bytes) {
        this.syscalls += numSyscalls;
        this.bytesProcessed += bytes;
    }
}
