package bench.random.read;

import com.davidvlijmincx.lio.api.*;


import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.fail;

public class ProfilerHelper {


    public static final int DEPTH = 2200;

    public static void main(String[] args) {
        JUring jUring = new JUring(DEPTH);
        RandomReadTaskCreator randomReadTaskCreator = new RandomReadTaskCreator();
        RandomReadTask[] randomReadTasks = Arrays.stream(randomReadTaskCreator.getRandomReadTasks()).limit(DEPTH).toArray(RandomReadTask[]::new);
        ArrayList<FileDescriptor> openFiles = new ArrayList<>(DEPTH);


       for (int y = 0; y < 200; y++) {
           try {
               int j = 0;
               for (var task : randomReadTasks) {

                   //sanityCheck(task);


                   FileDescriptor fd = new FileDescriptor(task.sPath(), Flag.READ, 0);

                   openFiles.add(fd);

                   jUring.prepareRead(fd, task.bufferSize(), task.offset());



               }

               jUring.submit();

               for (int i = 0; i < randomReadTasks.length; i++) {
                   List<Result> results = jUring.peekForBatchResult(100);

                   for (Result result : results) {
                       if (result instanceof AsyncReadResult r) {
                           //     r.getBuffer().set(JAVA_BYTE, r.getResult() + 5, (byte) 0);
                           String string = r.getBuffer().getString(0);
                           // System.out.println("string = " + string.substring(0,4).replace("\n", "").replace("\r", ""));
                           r.freeBuffer();
                       }
                   }
                   i += results.size();
               }

           } catch (Exception e) {
               throw new RuntimeException(e);
           }

           for (FileDescriptor fd : openFiles) {
               fd.close();
           }
       }
    }

    private static void sanityCheck(RandomReadTask task) throws IOException {
        final ByteBuffer data = ByteBuffer.allocate(4096);
        final FileChannel fc = FileChannel.open(task.path(), StandardOpenOption.READ);
        fc.read(data, task.offset());
        data.flip();
        System.out.println(new String(data.array(), Charset.forName("UTF8")).substring(0,4).replace("\n", "").replace("\r", ""));
    }
}
