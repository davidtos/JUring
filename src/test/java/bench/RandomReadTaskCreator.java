package bench;

import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Collection of benchmark test files
 */
@State(Scope.Benchmark)
public class RandomReadTaskCreator {
    public static final String BENCHMARK_FILE_EXTENSION = ".bin";
    public static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/mnt/data/test_files/");
    public final RandomReadTask[] RandomReadTasks;

    // 512 bytes, 4K, 16KB, 64KB
    @Param({"512", "4096", "16386", "65536"})
    public static int readSize;

    {
        try (Stream<Path> files = Files.walk(BASE_BENCHMARK_FILES_DIR)) {
            RandomReadTasks = files
                    .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                    .map(a -> RandomReadTask.fromPath(a, readSize))
                    .toArray(RandomReadTask[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public RandomReadTask[] getRandomReadTasks() {
        return RandomReadTasks;
    }

}


