package bench.random.write;

import bench.ExecutionPlanBlocking;
import bench.ExecutionPlanJUring;
import com.davidvlijmincx.lio.api.BlockingWriteResult;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.Flag;
import com.davidvlijmincx.lio.api.Result;
import org.openjdk.jmh.annotations.*;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.openjdk.jmh.annotations.Threads.MAX;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2300)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(15)
public class RandomWriteBenchMark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RandomWriteBenchMark.class.getSimpleName())
                .forks(1)
                .shouldDoGC(false)
                .build();

        new Runner(opt).run();
    }

    @Benchmark()
    public void libUringBlocking(ExecutionPlanBlocking plan, RandomWriteTaskCreator randomWriteTaskCreator) {
        final var jUringBlocking = plan.jUringBlocking;
        final var writeTasks = randomWriteTaskCreator.getRandomWriteTasks();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (RandomWriteTask writeTask : writeTasks) {
                FileDescriptor fd = new FileDescriptor(writeTask.sPath(), Flag.WRITE, 0);
                BlockingWriteResult r = jUringBlocking.prepareWrite(fd, randomWriteTaskCreator.content, writeTask.offset());
                jUringBlocking.submit();
                executor.execute(() -> {
                    r.getResult();
                    fd.close();
                });
            }
        }
    }

    @Benchmark()
    public void libUring(ExecutionPlanJUring plan, RandomWriteTaskCreator randomWriteTaskCreator) {
        final var jUring = plan.jUring;
        final var writeTasks = randomWriteTaskCreator.randomWriteTasks;
        ArrayList<FileDescriptor> openFiles = new ArrayList<>(5000);

        try {
            int j = 0;
            for (var task : writeTasks) {

                FileDescriptor fd = new FileDescriptor(task.sPath(), Flag.WRITE, 0);
                openFiles.add(fd);

                jUring.prepareWrite(fd, randomWriteTaskCreator.content, task.offset());

                j++;
                if (j % 100 == 0) {
                    jUring.submit();
                }
            }

            jUring.submit();

            for (int i = 0; i < writeTasks.length; i++) {
                List<Result> results = jUring.peekForBatchResult(100);
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
    public void writeUsingFileChannel(RandomWriteTaskCreator randomWriteTaskCreator) throws Throwable {
        RandomWriteTask[] writeTasks = randomWriteTaskCreator.getRandomWriteTasks();
        FileChannel[] fileChannels = new FileChannel[writeTasks.length];

        for (int i = 0; i < writeTasks.length; i++) {
            try {
                fileChannels[i] = FileChannel.open(writeTasks[i].path(), StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (int i = 0; i < writeTasks.length; i++) {
            final ByteBuffer data = ByteBuffer.wrap(randomWriteTaskCreator.content);
            final FileChannel fc = fileChannels[i];
            fc.write(data, writeTasks[i].offset());
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
    public void writeUsingRandomAccessFile(RandomWriteTaskCreator randomWriteTaskCreator) throws Throwable {
        RandomWriteTask[] writeTasks = randomWriteTaskCreator.getRandomWriteTasks();
        FileChannel[] fileChannels = new FileChannel[writeTasks.length];

        for (int i = 0; i < writeTasks.length; i++) {
            try {
                fileChannels[i] = new RandomAccessFile(writeTasks[i].sPath(), "rw").getChannel();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (int i = 0; i < writeTasks.length; i++) {
            final ByteBuffer data = ByteBuffer.wrap(randomWriteTaskCreator.content);
            final FileChannel fc = fileChannels[i];
            fc.write(data, writeTasks[i].offset());
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
    public void writeUsingFileChannelVirtualThreads(RandomWriteTaskCreator randomWriteTaskCreator) {
        RandomWriteTask[] writeTasks = randomWriteTaskCreator.getRandomWriteTasks();
        FileChannel[] fileChannels = new FileChannel[writeTasks.length];

        for (int i = 0; i < writeTasks.length; i++) {
            try {
                fileChannels[i] = FileChannel.open(writeTasks[i].path(), StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int i = 0; i < writeTasks.length; i++) {
                int finalI = i;
                executor.execute(() -> {
                    final ByteBuffer data = ByteBuffer.wrap(randomWriteTaskCreator.content);
                    final FileChannel fc = fileChannels[finalI];;
                    try {
                        fc.write(data, writeTasks[finalI].offset());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
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

