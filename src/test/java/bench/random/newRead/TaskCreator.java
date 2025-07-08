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

    @Param({ "4096"})
    public static int bufferSize = 4096;

    final static Random random = new Random(315315153152442L);

    public static final String BENCHMARK_FILE_EXTENSION = ".bin";
    public static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/home/david/testData/text_files/");
    public static final Path BASE_BENCHMARK_FILES_DIR_2 = Path.of("/home/david/DataDisk/text_files/");

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

        try (var path1 = Files.walk(BASE_BENCHMARK_FILES_DIR); var path2 = Files.walk(BASE_BENCHMARK_FILES_DIR_2)) {
            var availablePaths = getAvailablePaths(path1, path2);

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

    private static Path[] getAvailablePaths(Stream<Path> path1, Stream<Path> path2) {
        List<Path> list1 = path1.toList();
        List<Path> list2 = path2.toList();

        List<Path> combined = new ArrayList<>();
        final int[] i = {0};
        list1.forEach(p -> {
            combined.add(p);
            combined.add(list2.get(i[0]));
            i[0]++;
        });

        return combined.stream()
                .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                .toArray(Path[]::new);
    }

}

