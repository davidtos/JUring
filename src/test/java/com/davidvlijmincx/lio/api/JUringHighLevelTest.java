package com.davidvlijmincx.lio.api;

import bench.random.newRead.ExecutionPlanRegisteredFiles;
import bench.random.newRead.Task;
import bench.random.newRead.TaskCreator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

public class JUringHighLevelTest {

    ExecutionPlanRegisteredFiles plan;
    TaskCreator randomReadTaskCreator;

    @BeforeEach
    void setup() {
        randomReadTaskCreator = new TaskCreator();
        randomReadTaskCreator.setup();

        plan = new ExecutionPlanRegisteredFiles();
        plan.setup(randomReadTaskCreator);
    }

    @AfterEach
    void tearDown() {
        plan.jUring.close();
    }


    @Test
    void EventLoopJUring() {
        final var jUring = plan.jUring;
        final var readTasks = randomReadTaskCreator.tasks;
        final var registeredFileIndices = plan.registeredFileIndices;
        Map<Long, String> matchIdWithFile = new HashMap<>();

        int  bufferSize = 2048;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 256;

        while (processed < readTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < readTasks.length) {
                Task task = readTasks[taskIndex];
                int fileIndex = registeredFileIndices.get(task.pathAsString());
                String fileName = task.path().getFileName().toString().substring(5).replace(".bin","");
                long id = jUring.prepareRead(fileIndex, bufferSize, task.offset());

                matchIdWithFile.put(id, fileName);

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
                    String fileContent = r.buffer().getString(0).replace("\r", "").replace("\n", "").substring(0, 10);
                    String fileName = matchIdWithFile.remove(r.id());

                    assertThat(fileContent).contains(fileName);
                    r.freeBuffer();
                }
            }
            processed += results.size();
        }
       assertThat(processed).isEqualTo(2211);
    }
}
