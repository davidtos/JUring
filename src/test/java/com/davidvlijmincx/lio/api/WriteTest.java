package com.davidvlijmincx.lio.api;

import bench.random.BmDataSetup;
import bench.random.read.Task;
import bench.random.write.ExecutionPlanWriteRegisteredFiles;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.infra.ThreadParams;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

import static java.nio.charset.StandardCharsets.UTF_8;

public class WriteTest {

    final static Random random = new Random(315315153152442L);
    private static String charPool = "abcdefghijklmnopqrstuvwxyz0123456789";

    @Test
    void checkIfWritesWork(){
        ExecutionPlanWriteRegisteredFiles plan = new ExecutionPlanWriteRegisteredFiles();
       var t = new ThreadParams(0, 1, 0, 1, 0, 1, 0, 1, 0, 1);
        var dmd = new BmDataSetup();
        dmd.setup();

       plan.setup(dmd, t);

        final var jUring = plan.jUring;
        final Task[] writeTasks =  plan.writeTasks;
        final var registeredFileIndices = plan.registeredFileIndices;

        int submitted = 0;
        int processed = 0;
        int taskIndex = 0;
        final int maxInFlight = 56;

        int submitBatcher = 0;

        byte[] content = bytesToWrite(4096);
        ByteBuffer bytesToWrite = ByteBuffer.allocateDirect(content.length);
        bytesToWrite.put(content);
        bytesToWrite.flip();
        var ms = MemorySegment.ofBuffer(bytesToWrite);

        while (processed < writeTasks.length) {
            while (submitted - processed < maxInFlight && taskIndex < writeTasks.length) {
                Task task = writeTasks[taskIndex];
                int fileIndex = registeredFileIndices.get(task.pathAsString());

                System.out.println("task.pathAsString() = " + task.pathAsString());

                jUring.prepareWrite(fileIndex,ms, 4096);
                submitted++;
                taskIndex++;
                submitBatcher++;
            }

            int sSize = 55;
            if (submitBatcher > sSize || taskIndex + sSize >= writeTasks.length){
                jUring.submit();
                submitBatcher = 0;
            }

            int maxToWait = Math.min(submitted - processed, 56);
            List<Result> results = jUring.waitForBatchResult(maxToWait);
            for (Result result : results) {
                if (result instanceof WriteResult r) {

                }
            }
            processed += results.size();
        }
    }

    public byte[] bytesToWrite(int size){
        StringBuilder sb = new StringBuilder();
        sb.append("hello ");
        for (int i = 6; i < size; i++) {
            char randomChar = charPool.charAt(random.nextInt(charPool.length()));
            sb.append(randomChar);
        }
        return sb.toString().getBytes(UTF_8);
    }
}
