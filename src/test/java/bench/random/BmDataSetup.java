package bench.random;


import bench.random.read.Task;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;

@State(Scope.Benchmark)
public class BmDataSetup {

    // @Param({"512", "4096", "16386", "65536"})
    @Param({"4096"})
    public static int bufferSize;

    final static Random random = new Random();
    private static String charPool = "abcdefghijklmnopqrstuvwxyz0123456789";

    public static final String BENCHMARK_FILE_EXTENSION = ".bin";
    public static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/home/david/testData/text_files/");
    public static final Path BASE_BENCHMARK_WRITE_FILES_DIR = Path.of("/home/david/testData/write_files/");

    public byte[] content;
    public Task[] readTasks;
    public Task[] writeTasks;
    public ByteBuffer bytesToWrite;
    public MemorySegment bytesToWriteAsSegment;

    @Setup
    public void setup() {
        System.out.println("ProcessHandle.current().pid(); = " + ProcessHandle.current().pid());

        readTasks = getTasks(2200, 1);
        writeTasks = getTasks(2200, 0);

        content = bytesToWrite(bufferSize);
        bytesToWrite = ByteBuffer.allocateDirect(content.length);
        bytesToWrite.put(content);
        bytesToWrite.flip();

        bytesToWriteAsSegment = MemorySegment.ofBuffer(bytesToWrite);
    }

    public Task[] getTasks(int numberOfTask, double readWriteRatio){

        try (var readPath = Files.walk(BASE_BENCHMARK_FILES_DIR); var writePath = Files.walk(BASE_BENCHMARK_WRITE_FILES_DIR)) {
            var availableReadPaths = readPath
                    .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                    .toArray(Path[]::new);

            var availableWritePaths = writePath
                    .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                    .toArray(Path[]::new);

            Task[] tasks = new Task[numberOfTask];

            int numberOfReadTasks = (int) (numberOfTask * readWriteRatio);
            int numberOfWriteTasks = numberOfTask - numberOfReadTasks;

            for (int y = 0; y < numberOfReadTasks; y++) {
                var type = Type.READ;
                var path = availableReadPaths[random.nextInt(0, availableReadPaths.length - 1)];
                var offset = random.nextInt(0, (int) Files.size(path) - bufferSize);

                tasks[y] = new Task(path, bufferSize, type, offset);
            }

            for (int y = 0; y < numberOfWriteTasks; y++) {
                var type =  Type.WRITE;
                //var path = availableWritePaths[random.nextInt(0, availableWritePaths.length - 1)];
                var path = availableWritePaths[y];
                var offset = random.nextInt(0, (int) Files.size(path) - bufferSize);

                tasks[y] = new Task(path, bufferSize, type, offset);
            }
            return tasks;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public byte[] bytesToWrite(int size){
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < size; i++) {
            char randomChar = charPool.charAt(random.nextInt(charPool.length()));
            sb.append(randomChar);
        }
        return sb.toString().getBytes(UTF_8);
    }

}
