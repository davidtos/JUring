package bench;

import com.davidvlijmincx.lio.api.AsyncReadResult;
import com.davidvlijmincx.lio.api.BlockingReadResult;
import com.davidvlijmincx.lio.api.Result;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.openjdk.jmh.annotations.Threads.MAX;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2300)
@Fork(value = 3, jvmArgs = { "--enable-native-access=ALL-UNNAMED" })

@Threads(MAX)
public class BenchMarkLibUring {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(BenchMarkLibUring.class.getSimpleName())
                .forks(1)
                .shouldDoGC(false)
                .build();

        new Runner(opt).run();
    }



//    @Benchmark()
    public void libUringBlocking(Blackhole blackhole, ExecutionPlanBlocking plan) {

        final var q = plan.q;
        final var paths = BenchmarkFiles.filesTooRead;

        try(ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < paths.length; i++) {
                BlockingReadResult r = q.prepareRead(paths[i].sPath(), paths[i].bufferSize(), paths[i].offset());
                q.submit();
                executor.execute(() -> {
                    blackhole.consume(r.getBuffer());
                    r.freeBuffer();
                });
            }
        }
    }

  //  @Benchmark()
    public void libUring(Blackhole blackhole, ExecutionPlanJUring plan) {


        final var q = plan.q;
        final var paths = BenchmarkFiles.filesTooRead;

        try {
            for (int i = 0; i < paths.length; i++) {
                q.prepareRead(paths[i].sPath(), paths[i].bufferSize(), paths[i].offset());

                if (i % 100 == 0) {
                    q.submit();
                }

            }

            q.submit();

            for (int i = 0; i < paths.length; i++) {
                Result result = q.waitForResult();

                if (result instanceof AsyncReadResult r) {
                    blackhole.consume(r.getBuffer());
                    r.freeBuffer();
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

 //   @Benchmark
    public void readUsingFileChannel(Blackhole blackhole) throws Throwable {

        FileTooReadData[] files = BenchmarkFiles.filesTooRead;

        FileChannel[] fileChannels = new FileChannel[files.length];
        for (int i = 0; i < files.length; i++) {
            try {
                fileChannels[i] = FileChannel.open(files[i].path(), StandardOpenOption.READ);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (int i = 0; i < files.length; i++) {
            final ByteBuffer data = ByteBuffer.allocate(files[i].bufferSize());
            final FileChannel fc = fileChannels[i];
            fc.read(data, files[i].offset());
            data.flip();
            blackhole.consume(data);

        }

        for (FileChannel fc : fileChannels) {
            try {
                fc.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

  //  @Benchmark
    public void readUsingFileChannelVirtualThreads(Blackhole blackhole) {

        FileTooReadData[] files = BenchmarkFiles.filesTooRead;

        FileChannel[] fileChannels = new FileChannel[files.length];
        for (int i = 0; i < files.length; i++) {
            try {
                fileChannels[i] = FileChannel.open(files[i].path(), StandardOpenOption.READ);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try(ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < files.length; i++) {
                int finalI = i;
                executor.execute(() -> {
                    final ByteBuffer data = ByteBuffer.allocate(files[finalI].bufferSize());
                    final FileChannel fc = fileChannels[finalI];
                    try {
                        fc.read(data, files[finalI].offset());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    data.flip();
                    blackhole.consume(data);
                });


            }
        }

        for (FileChannel fc : fileChannels) {
            try {
                fc.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }


}

