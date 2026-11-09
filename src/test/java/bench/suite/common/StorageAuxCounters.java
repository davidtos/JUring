package bench.suite.common;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * JMH auxiliary counters for reporting processed bytes
 * as a dedicated column in the benchmark results table.
 */
@AuxCounters(AuxCounters.Type.OPERATIONS)
@State(Scope.Thread)
public class StorageAuxCounters {

    /**
     * Total I/O bytes processed during the invocation.
     */
    public long bytesProcessed;
    
    public void record(long bytes) {
        this.bytesProcessed += bytes;
    }
}
