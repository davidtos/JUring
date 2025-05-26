package bench.random.read;

import com.davidvlijmincx.lio.api.*;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class ProfilerHelper {


    public static final int DEPTH = 6000;

    public static void main(String[] args) {
        RandomReadTaskCreator randomReadTaskCreator = new RandomReadTaskCreator();
        RandomReadTask[] readTasks = Arrays.stream(randomReadTaskCreator.getRandomReadTasks()).limit(DEPTH).toArray(RandomReadTask[]::new);
        AtomicInteger completed = new AtomicInteger();
        int index = 0;

        try (JUring jUring = new JUring(DEPTH, 4096, DEPTH); Arena arena = Arena.ofConfined()) {

            for (int i = 0; i < readTasks.length; i++) {
                jUring.open(arena.allocateFrom(readTasks[i].sPath()), Flag.READ, 0);
            }

            jUring.submit();

            while (completed.get() < readTasks.length) {
                boolean dds = false;

                var results = jUring.peekForBatchResult(100);
                for (int i = 0; i < results.length; i++) {
                    var result = results[i];
                    switch (result.type()) {
                        case OPEN -> {
                            jUring.read(result.returnValue(), 4096, 0);
                            jUring.close(result.returnValue());
                        }
                        case READ -> {
                            result.readBuffer().set(JAVA_BYTE, result.bytesTransferred() + 5, (byte) 0);
                            String string = result.readBuffer().getString(0);
                            System.out.println("string = " + string.substring(0, 4).replace("\n", "").replace("\r", ""));
                            result.freeBuffer();
                        }
                        case CLOSE -> completed.getAndIncrement();
                    }
                }


                if (results.length == 0) {
                    jUring.submit();
                }
                index++;
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
