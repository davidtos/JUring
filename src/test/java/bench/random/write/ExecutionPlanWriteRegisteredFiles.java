package bench.random.write;

import bench.random.read.Task;
import bench.random.read.TaskCreator;
import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.lang.foreign.MemorySegment;
import java.util.*;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;
import static com.davidvlijmincx.lio.api.LinuxOpenOptions.WRITE;

@State(Scope.Thread)
public class ExecutionPlanWriteRegisteredFiles {

    public JUring jUring;
    public Map<String, Integer> registeredFileIndices;
    public MemorySegment[] registeredBuffers;
    private List<FileDescriptor> openFileDescriptors;

    @Setup
    public void setup(TaskCreator taskCreator) {
        jUring = new JUring(300, IORING_SETUP_SINGLE_ISSUER,IORING_SETUP_DEFER_TASKRUN, IORING_SETUP_COOP_TASKRUN);
        registeredFileIndices = new HashMap<>();
        openFileDescriptors = new ArrayList<>();

        Map<String, Integer> uniqueFiles = new HashMap<>();
        int uniqueFileCount = 0;
        
        for (Task task : taskCreator.writeTasks) {
            String filePath = task.pathAsString();
            if (!uniqueFiles.containsKey(filePath)) {
                uniqueFiles.put(filePath, uniqueFileCount++);
            }
        }

        FileDescriptor[] fileDescriptors = new FileDescriptor[uniqueFiles.size()];
        int index = 0;
        
        for (Map.Entry<String, Integer> entry : uniqueFiles.entrySet()) {
            String filePath = entry.getKey();
            
            FileDescriptor fd = new FileDescriptor(filePath, WRITE, 0);
            fileDescriptors[index] = fd;
            openFileDescriptors.add(fd);
            registeredFileIndices.put(filePath, index);
            index++;
        }

        int result = jUring.registerFiles(fileDescriptors);
        if (result != 0) {
            throw new RuntimeException("Failed to register files: " + result);
        }

        registeredBuffers = jUring.registerBuffers(5000, 260);
    }

    @TearDown
    public void tearDown() throws Throwable {
        for (FileDescriptor fd : openFileDescriptors) {
            fd.close();
        }
        jUring.close();
    }
}