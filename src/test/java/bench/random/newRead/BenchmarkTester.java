package bench.random.newRead;

import com.davidvlijmincx.lio.api.*;

import java.util.ArrayList;
import java.util.List;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class BenchmarkTester {


    public static void main(String[] args) {
        final var jUring = new JUring(2500, IORING_SETUP_SINGLE_ISSUER);
        final var readTasks = new TaskCreator().getTasks(10, 1);
        ArrayList<FileDescriptor> openFiles = new ArrayList<>(5000);

        try {
            int j = 0;
            for (var task : readTasks) {

                FileDescriptor fd = new FileDescriptor(task.pathAsString(), LinuxOpenOptions.READ, 0);
                openFiles.add(fd);

                jUring.prepareRead(fd, 10, task.offset());

                j++;
                if (j % 100 == 0) {
                    jUring.submit();
                }
            }

            jUring.submit();

            for (int i = 0; i < readTasks.length; i++) {
                List<Result> results = jUring.peekForBatchResult(100);

                for (Result result : results) {
                    if (result instanceof ReadResult r) {
                        r.buffer().set(JAVA_BYTE, r.result(), (byte) 0);
                        System.out.println(r.buffer().getString(0));
                        System.out.println("---");
                        r.freeBuffer();
                    }
                }
                i += results.size();
            }

            for (FileDescriptor fd : openFiles) {
                fd.close();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
