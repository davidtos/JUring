package bench.random.newRead;

import com.davidvlijmincx.lio.api.*;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class BenchmarkTester {


    public static void main(String[] args) throws Throwable {
        System.out.println("Testing registered files benchmark...");

        var taskCreator = new TaskCreator();
        taskCreator.setup();

        ExecutionPlanRegisteredFiles plan = new ExecutionPlanRegisteredFiles();
        plan.setup(taskCreator);

        registeredFiles(plan, taskCreator);
    }


    public static void registeredFiles(ExecutionPlanRegisteredFiles plan, TaskCreator randomReadTaskCreator) throws Throwable {
        final var jUring = plan.jUring;
        final var readTasks = randomReadTaskCreator.tasks;
        final var registeredFileIndices = plan.registeredFileIndices;

        try {
            int j = 0;
            for (int i = 0; i < readTasks.length; i++) {
                var task = readTasks[i];
                int fileIndex = registeredFileIndices.get(task.pathAsString());

                jUring.prepareRead(fileIndex, task.bufferSize(), task.offset());

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
                        String string = r.buffer().getString(0);
                        System.out.println(r.id());
                        r.freeBuffer();
                    }
                }
                i += results.size();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
