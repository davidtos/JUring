package bench.suite.common;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Per-thread CPU time counters (split into user and kernel/sys nanoseconds)
 * measured via Linux {@code getrusage(RUSAGE_THREAD)} using FFM.
 * <p>
 * With {@link AuxCounters.Type#OPERATIONS}, JMH divides the accumulated nanoseconds by
 * iteration duration (seconds), reporting {@code userNs} and {@code sysNs} per second of wall time.
 * Dividing those rates by the benchmark's primary {@code ops/s} throughput yields exact
 * {@code user ns/op} and {@code sys ns/op} for the benchmark thread without needing root or perf.
 */
@AuxCounters(AuxCounters.Type.OPERATIONS)
@State(Scope.Thread)
public class CpuAuxCounters {

    private static final int RUSAGE_THREAD = 1;
    private static final long RUSAGE_SIZE = 144L;
    private static final MethodHandle GETRUSAGE;

    static {
        Linker linker = Linker.nativeLinker();
        MemorySegment sym = linker.defaultLookup().find("getrusage").orElseThrow();
        GETRUSAGE = linker.downcallHandle(sym, FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    }

    /**
     * User-mode CPU nanoseconds accumulated by the benchmark thread.
     */
    public long userNs;

    /**
     * Kernel-mode (system) CPU nanoseconds accumulated by the benchmark thread.
     */
    public long sysNs;

    private final MemorySegment buf = Arena.ofAuto().allocate(RUSAGE_SIZE, 8L);
    private long u0;
    private long s0;

    public void begin() {
        sample();
        u0 = readUserNs();
        s0 = readSysNs();
    }

    public void end() {
        sample();
        userNs += Math.max(0L, readUserNs() - u0);
        sysNs += Math.max(0L, readSysNs() - s0);
    }

    private void sample() {
        try {
            int rc = (int) GETRUSAGE.invokeExact(RUSAGE_THREAD, buf);
            if (rc != 0) {
                throw new IllegalStateException("getrusage(RUSAGE_THREAD) failed: " + rc);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private long readUserNs() {
        long sec = buf.get(JAVA_LONG, 0L);
        long usec = buf.get(JAVA_LONG, 8L);
        return sec * 1_000_000_000L + usec * 1_000L;
    }

    private long readSysNs() {
        long sec = buf.get(JAVA_LONG, 16L);
        long usec = buf.get(JAVA_LONG, 24L);
        return sec * 1_000_000_000L + usec * 1_000L;
    }
}
