package bench.random.read;

import com.davidvlijmincx.lio.api.Flag;
import com.davidvlijmincx.lio.api.JUring;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class BenchHelper {


    public static final int DEPTH = 6000;

    public static void main(String[] args) throws IOException {
        RandomReadTaskCreator randomReadTaskCreator = new RandomReadTaskCreator();
        RandomReadTask[] readTasks = Arrays.stream(randomReadTaskCreator.getRandomReadTasks()).limit(DEPTH).toArray(RandomReadTask[]::new);


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
          //  blackhole.consume(data);

        }

        for (FileChannel fc : fileChannels) {
            try {
                fc.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }


    }

    private static void sanityCheck(RandomReadTask task) throws IOException {
        final ByteBuffer data = ByteBuffer.allocate(4096);
        try (FileChannel fc = FileChannel.open(task.path(), StandardOpenOption.READ)) {
            fc.read(data, task.offset());
            data.flip();
            System.out.println(new String(data.array(), StandardCharsets.UTF_8).substring(0, 4).replace("\n", "").replace("\r", ""));
        }

    }
}

