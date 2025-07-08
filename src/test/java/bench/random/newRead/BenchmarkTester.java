package bench.random.newRead;

import com.davidvlijmincx.lio.api.ReadResult;
import com.davidvlijmincx.lio.api.Result;

import java.util.List;

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

        int prints = 0;

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
                    r.buffer().set(JAVA_BYTE, r.result(), (byte) 0);
                    System.out.println("string = " + r.buffer().getString(0).substring(0,10).replace("\n", "").replace("\r", ""));
                    r.freeBuffer();
                    prints++;
                }
            }
            processed += results.size();
        }

        System.out.println("prints = " + prints);

    }
}
