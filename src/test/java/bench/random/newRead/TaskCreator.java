package bench.random.newRead;

import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@State(Scope.Benchmark)
public class TaskCreator {

    @Param({"512", "4096", "16386", "65536"})
    public static int bufferSize;

    final static Random random = new Random(315315153152442L);

    public static final String BENCHMARK_FILE_EXTENSION = ".bin";
    public static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/home/david/testData/text_files/");

    // for writing
    public byte[] content;
    public Task[] tasks;

    @Setup
    public void setup() {
        content = new byte[bufferSize];
        new Random().nextBytes(content);
        tasks = getTasks(2211, 1);
    }

    public Task[] getTasks(int numberOfTask, double readWriteRatio){

        try (var path1 = Files.walk(BASE_BENCHMARK_FILES_DIR)) {
            var availablePaths = path1
                    .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                    .toArray(Path[]::new);

            Task[] tasks = new Task[numberOfTask];

            int numberOfReadTasks = (int) (numberOfTask * readWriteRatio);

            for (int y = 0; y < numberOfTask; y++) {
                var type = y < numberOfReadTasks ? Type.READ : Type.WRITE;
                var path = availablePaths[random.nextInt(0, availablePaths.length - 1)];
                var offset = random.nextInt(0, (int) Files.size(path) - bufferSize);

                tasks[y] = new Task(path, bufferSize, type, offset);
            }

            Collections.shuffle(Arrays.asList(tasks));
            return tasks;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}

