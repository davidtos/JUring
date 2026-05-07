package bench.random.read;

import bench.random.Type;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

@State(Scope.Benchmark)
public class VectoredTaskCreator {

    static final int VECTOR_SIZE = 4096;
    static final int NUM_VECTORS = 4;
    static final int TOTAL_SIZE = VECTOR_SIZE * NUM_VECTORS;
    static final int[] VECTOR_SIZES = {VECTOR_SIZE, VECTOR_SIZE, VECTOR_SIZE, VECTOR_SIZE};

    public Task[] readTasks;

    private static final Random random = new Random(315315153152442L);

    @Setup
    public void setup() throws IOException {
        try (var walk = Files.walk(TaskCreator.BASE_BENCHMARK_FILES_DIR)) {
            Path[] available = walk
                    .filter(p -> p.getFileName().toString().endsWith(TaskCreator.BENCHMARK_FILE_EXTENSION))
                    .toArray(Path[]::new);

            readTasks = new Task[2211];
            for (int i = 0; i < readTasks.length; i++) {
                Path path = available[random.nextInt(0, available.length - 1)];
                int offset = random.nextInt(0, (int) Files.size(path) - TOTAL_SIZE);
                readTasks[i] = new Task(path, TOTAL_SIZE, Type.READ, offset);
            }
            Collections.shuffle(Arrays.asList(readTasks), random);
        }
    }
}
