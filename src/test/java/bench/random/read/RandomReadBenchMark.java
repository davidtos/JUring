package bench.random.read;

import bench.ExecutionPlanBlocking;
import bench.ExecutionPlanJUring;
import com.davidvlijmincx.lio.api.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2300)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(15)
public class RandomReadBenchMark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RandomReadBenchMark.class.getSimpleName())
                .forks(1)
                .addProfiler(AsyncProfiler.class, "lock=1ms simple=true output=flamegraph")
                .build();

        new Runner(opt).run();
    }

    @Benchmark()
    public void libUringBlocking(Blackhole blackhole, ExecutionPlanBlocking plan, RandomReadTaskCreator randomReadTaskCreator) {
        final var jUringBlocking = plan.jUringBlocking;
        final var readTasks = randomReadTaskCreator.RandomReadTasks;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (RandomReadTask readTask : readTasks) {
                FileDescriptor fd = new FileDescriptor(readTask.sPath(), Flag.READ, 0);
                var r = jUringBlocking.prepareRead(fd, readTask.bufferSize(), readTask.offset());
                jUringBlocking.submit();
                executor.execute(() -> {
                    try {
                        blackhole.consume(r.get().getBuffer());
                        r.get().freeBuffer();
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    } finally {
                        fd.close();
                    }
                });
            }
        }
    }

    @Benchmark()
    public void libUring(Blackhole blackhole, ExecutionPlanJUring plan, RandomReadTaskCreator randomReadTaskCreator) {
        final var jUring = plan.jUring;
        final var readTasks = randomReadTaskCreator.RandomReadTasks;
        ArrayList<FileDescriptor> openFiles = new ArrayList<>(5000);

        try {
            int j = 0;
            for (var task : readTasks) {

                FileDescriptor fd = new FileDescriptor(task.sPath(), Flag.READ, 0);
                openFiles.add(fd);

                jUring.prepareRead(fd, task.bufferSize(), task.offset());

                j++;
                if (j % 100 == 0) {
                    jUring.submit();
                }
            }

            jUring.submit();

            for (int i = 0; i < readTasks.length; i++) {
                List<Result> results = jUring.peekForBatchResult(100);

                for (Result result : results) {
                    if (result instanceof ReadResult r) {
                        blackhole.consume(r.getBuffer());
                        r.freeBuffer();
                    }
                }
                i += results.size();
            }

            for (FileDescriptor fd : openFiles) {
                fd.close();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void readUsingFileChannel(Blackhole blackhole, RandomReadTaskCreator randomReadTaskCreator) throws Throwable {
        RandomReadTask[] readTasks = randomReadTaskCreator.RandomReadTasks;
        FileChannel[] fileChannels = new FileChannel[readTasks.length];

        for (int i = 0; i < readTasks.length; i++) {
            try {
                fileChannels[i] = FileChannel.open(readTasks[i].path(), StandardOpenOption.READ);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (int i = 0; i < readTasks.length; i++) {
            final ByteBuffer data = ByteBuffer.allocate(readTasks[i].bufferSize());
            final FileChannel fc = fileChannels[i];
            fc.read(data, readTasks[i].offset());
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

    @Benchmark
    public void readUsingRandomAccessFile(Blackhole blackhole, RandomReadTaskCreator randomReadTaskCreator) throws Throwable {
        RandomReadTask[] readTasks = randomReadTaskCreator.RandomReadTasks;
        FileChannel[] fileChannels = new FileChannel[readTasks.length];

        for (int i = 0; i < readTasks.length; i++) {
            try {
                fileChannels[i] = new RandomAccessFile(readTasks[i].sPath(), "r").getChannel();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (int i = 0; i < readTasks.length; i++) {
            final ByteBuffer data = ByteBuffer.allocate(readTasks[i].bufferSize());
            final FileChannel fc = fileChannels[i];
            fc.read(data, readTasks[i].offset());
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

    @Benchmark
    public void readUsingFileChannelVirtualThreads(Blackhole blackhole, RandomReadTaskCreator randomReadTaskCreator) {
        RandomReadTask[] readTasks = randomReadTaskCreator.RandomReadTasks;
        FileChannel[] fileChannels = new FileChannel[readTasks.length];

        for (int i = 0; i < readTasks.length; i++) {
            try {
                fileChannels[i] = FileChannel.open(readTasks[i].path(), StandardOpenOption.READ);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < readTasks.length; i++) {
                int finalI = i;
                executor.execute(() -> {
                    final ByteBuffer data = ByteBuffer.allocate(readTasks[finalI].bufferSize());
                    final FileChannel fc = fileChannels[finalI];
                    try {
                        fc.read(data, readTasks[finalI].offset());
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

