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
    private static final MethodHandle MH_STATIC_STANDARD;
    private static final MethodHandle MH_STATIC_CRITICAL;

    static {
        MemorySegment symbol = LINKER.defaultLookup().findOrThrow("abs");
        MH_STATIC_STANDARD = LINKER.downcallHandle(symbol, DESC);
        MH_STATIC_CRITICAL = LINKER.downcallHandle(symbol, DESC, Linker.Option.critical(true));
    }

    private MethodHandle mhInstance;
    private NativeAbs proxyStandard;
    private NativeAbs proxyCritical;
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

    @Benchmark
    public int record_staticFinal_proxy() {
        return STATIC_RECORD.abs().abs(testValue);
    }

    @Benchmark
    public int record_instance_proxy() {
        return instanceRecord.abs().abs(testValue);
    }

    @Benchmark
    public int instanceField_extractedFromRecord_proxy() {
        return instanceFinalProxy.abs(testValue);
    }

    @Benchmark
    public int staticFinal_invokeExact() throws Throwable {
        return (int) MH_STATIC_STANDARD.invokeExact(testValue);
    }

    @Benchmark
    public int staticFinal_critical_invokeExact() throws Throwable {
        return (int) MH_STATIC_CRITICAL.invokeExact(testValue);
    }

    @Benchmark
    public int staticFinal_invoke() throws Throwable {
        return (int) MH_STATIC_INVOKE(testValue);
    }

    private static int MH_STATIC_INVOKE(int val) throws Throwable {
        return (int) MH_STATIC_STANDARD.invoke(val);
    }

    @Benchmark
    public int instanceField_invokeExact() throws Throwable {
        return (int) mhInstance.invokeExact(testValue);
    }

    @Benchmark
    public int methodHandleProxies_asInterfaceInstance() {
        return proxyStandard.abs(testValue);
    }

    @Benchmark
    public int methodHandleProxies_critical() {
        return proxyCritical.abs(testValue);
    }

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
