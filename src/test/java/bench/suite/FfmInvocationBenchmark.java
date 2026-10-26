package bench.suite;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark comparing the different ways to invoke a native function via Java FFM (JEP 454).
 * <p>
 * Evaluates:
 * <ol>
 *   <li><b>Static Final MethodHandle (invokeExact):</b> The gold-standard constant-folded JIT path.</li>
 *   <li><b>Static Final MethodHandle with critical(true):</b> Skipping safepoints for leaf calls.</li>
 *   <li><b>Instance MethodHandle (invokeExact):</b> MethodHandle stored in an instance field (no constant folding).</li>
 *   <li><b>Static Final MethodHandle (invoke):</b> Generic invoke with type checking.</li>
 *   <li><b>MethodHandleProxies (Interface Proxy):</b> The pattern used in LibCDispatcher (MethodHandleProxies.asInterfaceInstance).</li>
 *   <li><b>MethodHandleProxies with critical(true):</b> Interface proxy over a critical downcall handle.</li>
 * </ol>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@State(Scope.Thread)
public class FfmInvocationBenchmark {

    @FunctionalInterface
    public interface NativeAbs {
        int abs(int value);
    }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final FunctionDescriptor DESC = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    // 1. Static final MethodHandle (Standard downcall)
    private static final MethodHandle MH_STATIC_STANDARD;

    // 2. Static final MethodHandle (Critical downcall: skips safepoint polls)
    private static final MethodHandle MH_STATIC_CRITICAL;

    static {
        MemorySegment symbol = LINKER.defaultLookup().findOrThrow("abs");
        MH_STATIC_STANDARD = LINKER.downcallHandle(symbol, DESC);
        MH_STATIC_CRITICAL = LINKER.downcallHandle(symbol, DESC, Linker.Option.critical(true));
    }

    // 3. Instance field MethodHandle (Non-static, tests impact when JIT cannot constant-fold)
    private MethodHandle mhInstance;

    // 4. MethodHandleProxies interface instance (as used in LibCDispatcher.java)
    private NativeAbs proxyStandard;

    // 5. MethodHandleProxies over critical downcall handle
    private NativeAbs proxyCritical;

    // 6. Record holding the proxy (like LibCDispatcher)
    public record DispatcherRecord(NativeAbs abs) {}

    public static final DispatcherRecord STATIC_RECORD = new DispatcherRecord(
            MethodHandleProxies.asInterfaceInstance(NativeAbs.class, MH_STATIC_STANDARD)
    );

    private DispatcherRecord instanceRecord;
    private NativeAbs instanceFinalProxy;

    private int testValue = -42;

    @Setup(Level.Trial)
    public void setup() {
        MemorySegment symbol = LINKER.defaultLookup().findOrThrow("abs");
        mhInstance = LINKER.downcallHandle(symbol, DESC);

        // Pattern used in LibCDispatcher.java:
        proxyStandard = MethodHandleProxies.asInterfaceInstance(NativeAbs.class, MH_STATIC_STANDARD);
        proxyCritical = MethodHandleProxies.asInterfaceInstance(NativeAbs.class, MH_STATIC_CRITICAL);

        instanceRecord = new DispatcherRecord(proxyStandard);
        instanceFinalProxy = instanceRecord.abs();
    }

    /**
     * Stored in a static final Record: DispatcherRecord.abs().abs(val)
     */
    @Benchmark
    public int record_staticFinal_proxy() {
        return STATIC_RECORD.abs().abs(testValue);
    }

    /**
     * Stored in an instance Record: instanceRecord.abs().abs(val)
     */
    @Benchmark
    public int record_instance_proxy() {
        return instanceRecord.abs().abs(testValue);
    }

    /**
     * Extracted from record into instance final field (exactly like IoUringDispatcher: private final IoUringEnter ioUringEnter)
     */
    @Benchmark
    public int instanceField_extractedFromRecord_proxy() {
        return instanceFinalProxy.abs(testValue);
    }

    /**
     * Option 1: Static Final MethodHandle via invokeExact.
     * C2 JIT inlines this into a direct native call stub with constant-folded entry point.
     */
    @Benchmark
    public int staticFinal_invokeExact() throws Throwable {
        return (int) MH_STATIC_STANDARD.invokeExact(testValue);
    }

    /**
     * Option 2: Static Final MethodHandle with Linker.Option.critical(true) via invokeExact.
     * In addition to inlining, the JVM skips safepoint checks on transition.
     */
    @Benchmark
    public int staticFinal_critical_invokeExact() throws Throwable {
        return (int) MH_STATIC_CRITICAL.invokeExact(testValue);
    }

    /**
     * Option 3: Static Final MethodHandle via invoke().
     * Incurs MethodHandle type-checking and adaptation logic before dispatch.
     */
    @Benchmark
    public int staticFinal_invoke() throws Throwable {
        return (int) MH_STATIC_INVOKE(testValue);
    }

    private static int MH_STATIC_INVOKE(int val) throws Throwable {
        return (int) MH_STATIC_STANDARD.invoke(val);
    }

    /**
     * Option 4: Instance MethodHandle via invokeExact.
     * When the MethodHandle is stored in an instance variable (not static final),
     * C2 cannot constant-fold the handle identity, adding field dereference and de-opt guards.
     */
    @Benchmark
    public int instanceField_invokeExact() throws Throwable {
        return (int) mhInstance.invokeExact(testValue);
    }

    /**
     * Option 5: Foreign Invocation via MethodHandleProxies (as in LibCDispatcher.java).
     * Invoked through a Java interface proxy wrapping the MethodHandle.
     */
    @Benchmark
    public int methodHandleProxies_asInterfaceInstance() {
        return proxyStandard.abs(testValue);
    }

    /**
     * Option 6: MethodHandleProxies wrapping a critical(true) downcall handle.
     */
    @Benchmark
    public int methodHandleProxies_critical() {
        return proxyCritical.abs(testValue);
    }

    /**
     * Standalone main runner: allows running with standard `java` without full JMH CLI.
     */
    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(FfmInvocationBenchmark.class.getSimpleName())
                .warmupIterations(3)
                .measurementIterations(3)
                .forks(1)
                .build();
        new Runner(opt).run();
    }
}
