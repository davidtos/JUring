package bench;

import com.davidvlijmincx.lio.api.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.openjdk.jmh.annotations.Threads.MAX;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(MAX)
public class SequentialReadBenchmark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(SequentialReadBenchmark.class.getSimpleName())
                .forks(1)
                .shouldDoGC(false)
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void libUringBlocking(Blackhole blackhole, ExecutionPlanBlocking plan, SequentialReadTaskCreator taskCreator) {
        final var jUringBlocking = plan.jUringBlocking;
        final int bufferSize = taskCreator.getReadSize();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (var task : taskCreator.getSequentialReadTasks()) {
                FileDescriptor fd = new FileDescriptor(task.sPath(), Flag.READ, 0);
                long fileSize = task.fileSize();
                long position = 0;

                // Read file sequentially in chunks of bufferSize
                List<BlockingReadResult> results = new ArrayList<>();
                while (position < fileSize) {
                    long currentPosition = position;
                    int readSize = (int) Math.min(bufferSize, fileSize - position);

                    results.add(jUringBlocking.prepareRead(fd, readSize, currentPosition));
                    jUringBlocking.submit();

                    position += readSize;
                }

                executor.execute(() -> {
                    results.forEach(r -> {
                        blackhole.consume(r.getBuffer());
                        r.freeBuffer();
                    });

                    fd.close();
                });

            }
        }
    }

    @Benchmark()
    public void libUring(Blackhole blackhole, ExecutionPlanJUring plan, SequentialReadTaskCreator taskCreator) {
        final var jUring = plan.jUring;
        final var readTasks = taskCreator.getSequentialReadTasks();
        final int bufferSize = taskCreator.getReadSize();

        ArrayList<FileDescriptor> openFiles = new ArrayList<>(5000);

        try {
            int taskNumber = 0;
            for (var task : readTasks) {

                long position = 0;
                long fileSize = task.fileSize();

                FileDescriptor fd = new FileDescriptor(task.sPath(), Flag.READ, 0);
                openFiles.add(fd);

                while (position < fileSize) {
                    long currentPosition = position;
                    int readSize = (int) Math.min(bufferSize, fileSize - position);

                    jUring.prepareRead(fd, readSize, currentPosition);

                    position += readSize;

                    taskNumber++;
                }

                if (taskNumber % 100 >= 0) {
                    jUring.submit();
                }
            }

            jUring.submit();

            for (int i = 0; i < taskNumber; i++) {
                Result result = jUring.waitForResult();

                if (result instanceof AsyncReadResult r) {
                    blackhole.consume(r.getBuffer());
                    r.freeBuffer();
                }
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
            FileChannel fc = fileChannels[i];

            while (fc.read(data) > 0) {
                fc.read(data, readTasks[i].offset());
                data.flip();
                blackhole.consume(data);
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
