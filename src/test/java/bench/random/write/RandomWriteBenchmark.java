package bench.random.write;

import bench.ExecutionPlanBlocking;
import bench.ExecutionPlanJUring;
import bench.random.read.Task;
import bench.random.read.TaskCreator;
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
import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@OperationsPerInvocation(2211)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(20)
public class RandomWriteBenchmark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RandomWriteBenchmark.class.getSimpleName())
                .forks(1)
                // .addProfiler(AsyncProfiler.class, "event=cpu;simple=true;output=flamegraph;dir=./profiler-results")
                .build();

        new Runner(opt).run();
    }

    @Benchmark
    public void registeredFiles(Blackhole blackhole, ExecutionPlanWriteRegisteredFiles plan, TaskCreator taskCreator) {
        final var jUring = plan.jUring;
        final var writeTasks = taskCreator.writeTasks;

        final var registeredFileIndices = plan.registeredFileIndices;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 256;

        while (processed < writeTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < writeTasks.length) {
                Task task = writeTasks[taskIndex];
                int fileIndex = registeredFileIndices.get(task.pathAsString());
                jUring.prepareWrite(fileIndex, taskCreator.content, task.offset());

                submitted++;
                taskIndex++;

                if (submitted % 64 == 0) {
                    jUring.submit();
                }
            }

            if (submitted > processed) {
                jUring.submit();
            }

            List<Result> results = jUring.peekForBatchResult(64);
            for (Result result : results) {
                if (result instanceof WriteResult r) {
                    blackhole.consume(r);
                }
            }
            processed += results.size();
        }

    }

    @Benchmark
    public void preOpenedFileChannels(Blackhole blackhole, ExecutionPlanPreOpenedWriteFileChannels plan, TaskCreator taskCreator) throws IOException {
        final var openFileChannels = plan.openFileChannels;
        final var writeTasks = taskCreator.writeTasks;

        for (var task : writeTasks) {
            final FileChannel fc = openFileChannels.get(task.pathAsString());
            int written = fc.write(ByteBuffer.wrap(taskCreator.content), task.offset());
            blackhole.consume(written);
        }

    }

    @Benchmark
    public void juringOpenWriteClose(Blackhole blackhole, ExecutionPlanJUring plan, TaskCreator taskCreator) {
        final var jUring = plan.jUring;
        final var writeTasks = taskCreator.writeTasks;

        ArrayList<FileDescriptor> openFiles = new ArrayList<>(writeTasks.length);

        try {
            int submitted = 0;
            int processed = 0;
            int taskIndex = 0;
            final int maxInFlight = 256;

            while (processed < writeTasks.length) {
                while (submitted - processed < maxInFlight && taskIndex < writeTasks.length) {
                    Task task = writeTasks[taskIndex];

                    FileDescriptor fd = new FileDescriptor(task.pathAsString(), LinuxOpenOptions.WRITE, 0);
                    openFiles.add(fd);
                    jUring.prepareWrite(fd, taskCreator.content, task.offset());

                    submitted++;
                    taskIndex++;

                    if (submitted % 64 == 0) {
                        jUring.submit();
                    }
                }

                if (submitted > processed) {
                    jUring.submit();
                }

                List<Result> results = jUring.peekForBatchResult(64);
                for (Result result : results) {
                    if (result instanceof WriteResult r) {
                        blackhole.consume(r);
                    }
                }
                processed += results.size();
            }

            for (FileDescriptor fd : openFiles) {
                fd.close();
            }


        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void fileChannelOpenWriteClose(Blackhole blackhole, TaskCreator taskCreator) throws IOException {
        Task[] writeTasks = taskCreator.writeTasks;
        FileChannel[] fileChannels = new FileChannel[writeTasks.length];

        for (int i = 0; i < writeTasks.length; i++) {
            try {
                fileChannels[i] = FileChannel.open(writeTasks[i].path(), StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        for (int i = 0; i < writeTasks.length; i++) {
            final FileChannel fc = fileChannels[i];
            int written = fc.write(ByteBuffer.wrap(taskCreator.content), writeTasks[i].offset());
            blackhole.consume(written);

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
