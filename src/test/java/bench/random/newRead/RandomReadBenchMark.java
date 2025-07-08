package bench.random.newRead;

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
@OperationsPerInvocation(2211)
@Fork(value = 3, jvmArgs = {"--enable-native-access=ALL-UNNAMED"})
@Threads(20)
public class RandomReadBenchMark {

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(RandomReadBenchMark.class.getSimpleName())
                .forks(1)
               // .addProfiler(AsyncProfiler.class, "event=cpu;simple=true;output=flamegraph;dir=./profiler-results")
                .build();

        new Runner(opt).run();
    }

    @Benchmark()
    public void libUringBlocking(Blackhole blackhole, ExecutionPlanBlocking plan, TaskCreator randomReadTaskCreator) {
        final var jUringBlocking = plan.jUringBlocking;
        final var readTasks = randomReadTaskCreator.tasks;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (Task readTask : readTasks) {
                FileDescriptor fd = new FileDescriptor(readTask.pathAsString(), LinuxOpenOptions.READ, 0);
                var r = jUringBlocking.prepareRead(fd, readTask.bufferSize(), readTask.offset());
                jUringBlocking.submit();
                executor.execute(() -> {
                    try {
                        blackhole.consume(r.get().buffer());
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

    @Benchmark
    public void registeredFiles(Blackhole blackhole, ExecutionPlanRegisteredFiles plan, TaskCreator randomReadTaskCreator) {
        final var jUring = plan.jUring;
        final var readTasks = randomReadTaskCreator.tasks;
        final var registeredFileIndices = plan.registeredFileIndices;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 256;

        while (processed < readTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < readTasks.length) {
                Task task = readTasks[taskIndex];
                int fileIndex = registeredFileIndices.get(task.pathAsString());
                jUring.prepareRead(fileIndex, task.bufferSize(), task.offset());
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
                if (result instanceof ReadResult r) {
                    blackhole.consume(r.buffer());
                    r.freeBuffer();
                }
            }
            processed += results.size();
        }

    }

    @Benchmark
    public void preOpenedFileChannels(Blackhole blackhole, ExecutionPlanPreOpenedFileChannels plan, TaskCreator randomReadTaskCreator) throws Throwable {
        final var openFileChannels = plan.openFileChannels;
        final var readTasks = randomReadTaskCreator.tasks;

        for (var task : readTasks) {
            final ByteBuffer data = ByteBuffer.allocate(task.bufferSize());
            final FileChannel fc = openFileChannels.get(task.pathAsString());
            fc.read(data, task.offset());
            data.flip();
            blackhole.consume(data);
        }
    }

    @Benchmark()
    public void libUring(Blackhole blackhole, ExecutionPlanJUring plan, TaskCreator randomReadTaskCreator) {
        final var jUring = plan.jUring;
        final var readTasks = randomReadTaskCreator.tasks;
        ArrayList<FileDescriptor> openFiles = new ArrayList<>(readTasks.length);

        try {
            int submitted = 0;
            int processed = 0;
            int taskIndex = 0;
            final int maxInFlight = 256;

            while (processed < readTasks.length) {
                while (submitted - processed < maxInFlight && taskIndex < readTasks.length) {
                    Task task = readTasks[taskIndex];
                    
                    FileDescriptor fd = new FileDescriptor(task.pathAsString(), LinuxOpenOptions.READ, 0);
                    openFiles.add(fd);
                    
                    jUring.prepareRead(fd, task.bufferSize(), task.offset());
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
                    if (result instanceof ReadResult r) {
                        blackhole.consume(r.buffer());
                        r.freeBuffer();
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
    public void readUsingFileChannel(Blackhole blackhole, TaskCreator randomReadTaskCreator) throws Throwable {
        Task[] readTasks = randomReadTaskCreator.tasks;
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
    public void readUsingFileChannelVirtualThreads(Blackhole blackhole, TaskCreator randomReadTaskCreator) {
        Task[] readTasks = randomReadTaskCreator.tasks;
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

