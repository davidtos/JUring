package bench.random.read;

import com.davidvlijmincx.lio.api.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class ProfilerHelper {


    public static final int DEPTH = 2200;

    public static void main(String[] args) {
        JUring jUring = new JUring(DEPTH, 4096, DEPTH);
        RandomReadTaskCreator randomReadTaskCreator = new RandomReadTaskCreator();
        RandomReadTask[] randomReadTasks = Arrays.stream(randomReadTaskCreator.getRandomReadTasks()).limit(DEPTH).toArray(RandomReadTask[]::new);
        ArrayList<FileDescriptor> openFiles = new ArrayList<>(DEPTH);

            try {

                for (int i = 0; i < randomReadTasks.length; i++) {
                    //sanityCheck(task);

                    FileDescriptor fd = new FileDescriptor(randomReadTasks[i].sPath(), Flag.READ, 0);

                    openFiles.add(fd);

                    jUring.prepareReadFixed(fd, randomReadTasks[i].offset(), i);
                }

                jUring.submit();

                for (int i = 0; i < randomReadTasks.length; i++) {
                    List<IoResult> results = jUring.peekForBatchResult(100);

                    for (IoResult result : results) {
                        if (OperationType.READ.equals(result.type())) {
                                 result.readBuffer().set(JAVA_BYTE, result.bytesTransferred() + 5, (byte) 0);
                                String string = result.readBuffer().getString(0);
                          //   System.out.println("string = " + string.substring(0,4).replace("\n", "").replace("\r", ""));
                           // r.freeBuffer();

                        }
                    }
                    i += results.size();
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            System.out.println("done");
            for (FileDescriptor fd : openFiles) {
                jUring.prepareClose(fd.getFd());
            }
            jUring.submit();

            int done = 0;
            while (done < openFiles.size()) {
                List<IoResult> results = jUring.peekForBatchResult(100);
                done += results.size();
            }

    }

    private static void sanityCheck(RandomReadTask task) throws IOException {
        final ByteBuffer data = ByteBuffer.allocate(4096);
        final FileChannel fc = FileChannel.open(task.path(), StandardOpenOption.READ);
        fc.read(data, task.offset());
        data.flip();
        System.out.println(new String(data.array(), StandardCharsets.UTF_8).substring(0, 4).replace("\n", "").replace("\r", ""));
    }
}
