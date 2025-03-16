package bench;

import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * State class to provide files for sequential reading benchmark
 */
@State(Scope.Benchmark)
public class SequentialReadTaskCreator {
    private static final String BENCHMARK_FILE_EXTENSION = ".bin";
    private static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/mnt/data/test_files/");
    private final SequentialReadTask[] sequentialReadTasks;

    private List<Path> files;

    // Buffer sizes for sequential reads: 4KB, 8KB, 64KB, 1MB, 16MB, 128MB
    @Param({"4096", "8192", "65536", "1048576", "16777216", "134217728"})
    private int readSize;

    {
        try (Stream<Path> files = Files.walk(BASE_BENCHMARK_FILES_DIR)) {
            sequentialReadTasks = files
                    .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                    .map(SequentialReadTask::fromPath)
                    .toArray(SequentialReadTask[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SequentialReadTask[] getSequentialReadTasks() {
        return sequentialReadTasks;
    }

    public int getReadSize() {
        return readSize;
    }
}