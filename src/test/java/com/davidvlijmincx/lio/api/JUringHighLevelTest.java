package com.davidvlijmincx.lio.api;

import bench.random.read.ExecutionPlanRegisteredFiles;
import bench.random.read.Task;
import bench.random.read.TaskCreator;
import bench.random.write.ExecutionPlanPreOpenedWriteFileChannels;
import bench.random.write.ExecutionPlanWriteRegisteredFiles;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
/*
The idea behind these tests is the following:
- verify that implementations of JUring work that go beyond basic unit tests.
- Verify that the benchmarks are correct
- To get a good feeling of the API (sort of quality control)

 */
public class JUringHighLevelTest {

    @Test
    void EventLoopJUring() {
        TaskCreator randomReadTaskCreator = new TaskCreator();
        randomReadTaskCreator.setup();

        ExecutionPlanRegisteredFiles plan = new ExecutionPlanRegisteredFiles();
        plan.setup(randomReadTaskCreator);

        final var jUring = plan.jUring;
        final var readTasks = randomReadTaskCreator.readTasks;
        final var registeredFileIndices = plan.registeredFileIndices;
        Map<Long, String> matchIdWithFile = new HashMap<>();

        int  bufferSize = 2048;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 256;

        while (processed < readTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < readTasks.length) {
                Task task = readTasks[taskIndex];
                int fileIndex = registeredFileIndices.get(task.pathAsString());
                String fileName = task.path().getFileName().toString().substring(5).replace(".bin","");
                long id = jUring.prepareRead(fileIndex, bufferSize, task.offset());

                matchIdWithFile.put(id, fileName);

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

                    r.buffer().set(JAVA_BYTE, r.result(), (byte) 0);
                    String fileContent = r.buffer().getString(0).replace("\r", "").replace("\n", "").substring(0, 10);
                    String fileName = matchIdWithFile.remove(r.id());
                    assertThat(fileContent).contains(fileName);

                    r.freeBuffer();
                }
            }
            processed += results.size();
        }
       assertThat(processed).isEqualTo(2211);

        plan.jUring.close();
    }

    @Test
    void openReadCloseFile(){
        TaskCreator randomReadTaskCreator = new TaskCreator();
        randomReadTaskCreator.setup();

        final var jUring =  new JUring(2500, IORING_SETUP_SINGLE_ISSUER);
        final var readTasks = randomReadTaskCreator.readTasks;
        ArrayList<FileDescriptor> openFiles = new ArrayList<>(readTasks.length);
        Map<Long, String> matchIdWithFile = new HashMap<>();

        int  bufferSize = 2048;

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
                    long id = jUring.prepareRead(fd, bufferSize, task.offset());
                    String fileName = task.path().getFileName().toString().substring(5).replace(".bin","");
                    matchIdWithFile.put(id, fileName);

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

                        r.buffer().set(JAVA_BYTE, r.result(), (byte) 0);
                        String fileContent = r.buffer().getString(0).replace("\r", "").replace("\n", "").substring(0, 10);
                        String fileName = matchIdWithFile.remove(r.id());
                        assertThat(fileContent).contains(fileName);

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
        } finally {
            jUring.close();
        }
    }

    record holder(byte[] content, Task task) {}

    @Test
    void EventWriteLoopJUring() {
        int passed = 0;
        TaskCreator taskCreator = new TaskCreator();
        taskCreator.setup();

        ExecutionPlanWriteRegisteredFiles plan = new ExecutionPlanWriteRegisteredFiles();
        plan.setup(taskCreator);

        final var jUring = plan.jUring;
        final var writeTasks = new ArrayList<>(Arrays.stream(taskCreator.writeTasks)
                .collect(Collectors.toMap(
                        Task::path,
                        Function.identity(),
                        (existing, _) -> existing))
                .values()).toArray(new Task[0]);

        assertThat(writeTasks).hasSizeGreaterThan(100);

        final var registeredFileIndices = plan.registeredFileIndices;
        Map<Long, holder> matchIdWithFile = new HashMap<>();

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 900;

        while (processed < writeTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < writeTasks.length) {
                Task task = writeTasks[taskIndex];
                int fileIndex = registeredFileIndices.get(task.pathAsString());

                byte[] content = taskCreator.bytesToWrite(5);

                ByteBuffer bb = ByteBuffer.allocateDirect(content.length);
                bb.put(content);
                bb.flip();

                long id = jUring.prepareWrite(fileIndex, MemorySegment.ofBuffer(bb), task.offset());

                matchIdWithFile.put(id, new holder(content, task));

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
                    holder holder = matchIdWithFile.remove(r.id());
                    assertThat(r.result()).isEqualTo(holder.content.length);

                    byte[] bytes = readFromOffset(holder.task.path(), holder.task.offset(), holder.content.length);
                    assertThat(bytes).isEqualTo(holder.content);
                    passed++;
                }
            }
            processed += results.size();
        }

        plan.jUring.close();

        assertThat(processed).isEqualTo(writeTasks.length);
        assertThat(matchIdWithFile).isEmpty();
        assertThat(passed).isEqualTo(writeTasks.length);
    }

    @Test
    void preOpenedFileChannels() throws IOException {

        TaskCreator taskCreator = new TaskCreator();
        taskCreator.setup();

        ExecutionPlanPreOpenedWriteFileChannels plan = new ExecutionPlanPreOpenedWriteFileChannels();
        plan.setup(taskCreator);

        byte[] content = taskCreator.bytesToWrite(5000);

        final var openFileChannels = plan.openFileChannels;
        final var writeTasks = taskCreator.writeTasks;

        for (var task : writeTasks) {
            final FileChannel fc = openFileChannels.get(task.pathAsString());
            int written = fc.write(ByteBuffer.wrap(content), task.offset());

            assertThat(written).isGreaterThan(1);
            assertThat(written).isEqualTo(content.length);
            byte[] bytes = readFromOffset(task.path(), task.offset(), content.length);
            assertThat(bytes).isEqualTo(content);

        }
    }

    @Test
    void openWriteCloseFile(){
        int passed = 0;
        TaskCreator taskCreator = new TaskCreator();
        taskCreator.setup();

        final var jUring =  new JUring(2500, IORING_SETUP_SINGLE_ISSUER);
        final var writeTasks = new ArrayList<>(Arrays.stream(taskCreator.writeTasks)
                .collect(Collectors.toMap(
                        Task::path,
                        Function.identity(),
                        (existing, _) -> existing))
                .values()).toArray(new Task[0]);

        ArrayList<FileDescriptor> openFiles = new ArrayList<>(writeTasks.length);
        Map<Long, Task> matchIdWithFile = new HashMap<>();

        byte[] content = taskCreator.bytesToWrite(5000);

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
                    long id = jUring.prepareWrite(fd, content, task.offset());
                    matchIdWithFile.put(id, task);

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

                        assertThat(r.result()).isEqualTo(content.length);
                        Task task = matchIdWithFile.remove(r.id());
                        byte[] bytes = readFromOffset(task.path(), task.offset(), content.length);
                        assertThat(bytes).isEqualTo(content);
                        passed++;
                    }
                }
                processed += results.size();
            }

            for (FileDescriptor fd : openFiles) {
                fd.close();
            }

            assertThat(matchIdWithFile).isEmpty();
            assertThat(passed).isEqualTo(writeTasks.length);

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            jUring.close();
        }
    }

    public static byte[] readFromOffset(Path filePath, long offset, int length) {
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(length);

            int bytesRead = channel.read(buffer, offset);

            if (bytesRead == -1) {
                return new byte[0]; // End of file
            }

            // Flip buffer to prepare for reading
            buffer.flip();

            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);

            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void writeTaskCreation(){
        TaskCreator taskCreator = new TaskCreator();
        taskCreator.setup();

        Task[] tasks = taskCreator.getTasks(2211, 0);

        assertThat(tasks).hasSize(2211);
        assertThat(tasks).noneMatch(Objects::isNull);
        assertThat(tasks).allMatch(task -> task.path().toString().contains("write_file"));
    }

    @Test
    void readTaskCreation(){
        TaskCreator taskCreator = new TaskCreator();
        taskCreator.setup();

        Task[] tasks = taskCreator.getTasks(2211, 1);

        assertThat(tasks).hasSize(2211);
        assertThat(tasks).noneMatch(Objects::isNull);
        assertThat(tasks).allMatch(task -> task.path().toString().contains("text_file"));
    }

    @Test
    void bytesToWrite(){
        TaskCreator taskCreator = new TaskCreator();

        final int size = 500;

        byte[] bytes = taskCreator.bytesToWrite(size);

        assertThat(new String(bytes))
                .isNotEmpty()
                .hasSize(size)
                .matches("[a-z0-9]+");
    }
}
