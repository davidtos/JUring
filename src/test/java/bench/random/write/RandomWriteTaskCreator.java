package bench.random.write;

import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Collection of benchmark test files
 */
@State(Scope.Benchmark)
public class RandomWriteTaskCreator {
    public static final String BENCHMARK_FILE_EXTENSION = ".bin";
    public static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/home/david/testData/text_files/");
    public static final Path BASE_BENCHMARK_FILES_DIR_2 = Path.of("/home/david/DataDisk/text_files/");
    public final RandomWriteTask[] randomWriteTasks;
    public byte[] content;

    // 512 bytes, 4K, 16KB, 64KB
    @Param({"512", "4096", "16386", "65536"})
    public static int writeSize;

    {
        try (var path1 = Files.walk(BASE_BENCHMARK_FILES_DIR); var path2 = Files.walk(BASE_BENCHMARK_FILES_DIR_2)) {


            List<Path> list1 = path1.toList();
            List<Path> list2 = path2.toList();

            List<Path> combined = new ArrayList<>();
            final int[] i = {0};
            list1.forEach(p -> {
                combined.add(p);
                combined.add(list2.get(i[0]));
                i[0]++;
            });

            randomWriteTasks = combined.stream()
                    .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                    .map(a -> RandomWriteTask.fromPath(a, writeSize))
                    .toArray(RandomWriteTask[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Setup
    public void setup() {
        content = new byte[writeSize];
        new Random().nextBytes(content);
    }

    public RandomWriteTask[] getRandomWriteTasks() {
        return randomWriteTasks;
    }

}


