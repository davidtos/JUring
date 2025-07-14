package bench.random.read;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.davidvlijmincx.lio.api.IoUringOptions.*;

@State(Scope.Thread)
public class ExecutionPlanRegisteredFiles {

    public JUring jUring;
    public Map<String, Integer> registeredFileIndices;
    private List<FileDescriptor> openFileDescriptors;

    @Setup
    public void setup(TaskCreator taskCreator) {
        jUring = new JUring(2500, IORING_SETUP_SINGLE_ISSUER);
        registeredFileIndices = new HashMap<>();
        openFileDescriptors = new ArrayList<>();

        Map<String, Integer> uniqueFiles = new HashMap<>();
        int uniqueFileCount = 0;
        
        for (Task task : taskCreator.tasks) {
            String filePath = task.pathAsString();
            if (!uniqueFiles.containsKey(filePath)) {
                uniqueFiles.put(filePath, uniqueFileCount++);
            }
        }

        FileDescriptor[] fileDescriptors = new FileDescriptor[uniqueFiles.size()];
        int index = 0;
        
        for (Map.Entry<String, Integer> entry : uniqueFiles.entrySet()) {
            String filePath = entry.getKey();
            
            FileDescriptor fd = new FileDescriptor(filePath, LinuxOpenOptions.READ, 0);
            fileDescriptors[index] = fd;
            openFileDescriptors.add(fd);
            registeredFileIndices.put(filePath, index);
            index++;
        }

        int result = jUring.registerFiles(fileDescriptors);
        if (result != 0) {
            throw new RuntimeException("Failed to register files: " + result);
        }
    }

    @TearDown
    public void tearDown() throws Throwable {
        for (FileDescriptor fd : openFileDescriptors) {
            fd.close();
        }
        jUring.close();
    }
}