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


    public static void registeredFiles( ExecutionPlanRegisteredFiles plan, TaskCreator randomReadTaskCreator) {
        final var jUring = plan.jUring;
        final var readTasks = randomReadTaskCreator.tasks;
        final var registeredFileIndices = plan.registeredFileIndices;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 256;

        while (processed < readTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < readTasks.length) {
                Task task = readTasks[taskIndex];
                int fileIndex = registeredFileIndices.get(task.pathAsString());
                jUring.prepareRead(fileIndex, task.bufferSize(), task.offset());
                submitted++;
                taskIndex++;

                if (submitted % 64 == 0) {
                    jUring.submit();
                }
            }

            if (submitted > processed) {
                jUring.submit();
            }

            List<Result> results = jUring.peekForBatchResult(64);
            for (Result result : results) {
                if (result instanceof ReadResult r) {
                    String string = r.buffer().getString(0);
                    r.freeBuffer();
                }
            }
            processed += results.size();
        }

    }
}
