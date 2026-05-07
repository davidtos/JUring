package bench.random.read;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

@State(Scope.Thread)
public class ExecutionPlanReadBufferPool {

    public JUring jUring;
    public int[] taskFileIndices;
    private List<FileDescriptor> openFileDescriptors;

    @Setup
    public void setup(TaskCreator taskCreator) {
        jUring = new JUring(2500, IORING_SETUP_SINGLE_ISSUER);
        jUring.setupReadBufferPool(TaskCreator.bufferSize, 300);

        Map<String, Integer> uniqueFiles = new HashMap<>();
        openFileDescriptors = new ArrayList<>();
        int uniqueFileCount = 0;

        for (Task task : taskCreator.readTasks) {
            String filePath = task.pathAsString();
            if (!uniqueFiles.containsKey(filePath)) {
                uniqueFiles.put(filePath, uniqueFileCount++);
            }
        }

        FileDescriptor[] fileDescriptors = new FileDescriptor[uniqueFiles.size()];
        int index = 0;
        for (Map.Entry<String, Integer> entry : uniqueFiles.entrySet()) {
            FileDescriptor fd = new FileDescriptor(entry.getKey(), LinuxOpenOptions.READ, 0);
            fileDescriptors[index] = fd;
            openFileDescriptors.add(fd);
            index++;
        }

        int result = jUring.registerFiles(fileDescriptors);
        if (result != 0) {
            throw new RuntimeException("Failed to register files: " + result);
        }

        Map<String, Integer> registeredFileIndices = new HashMap<>();
        for (Map.Entry<String, Integer> entry : uniqueFiles.entrySet()) {
            registeredFileIndices.put(entry.getKey(), entry.getValue());
        }

        taskFileIndices = new int[taskCreator.readTasks.length];
        for (int i = 0; i < taskCreator.readTasks.length; i++) {
            taskFileIndices[i] = registeredFileIndices.get(taskCreator.readTasks[i].pathAsString());
        }
    }

    @TearDown
    public void tearDown() {
        for (FileDescriptor fd : openFileDescriptors) {
            fd.close();
        }
        jUring.close();
    }
}
