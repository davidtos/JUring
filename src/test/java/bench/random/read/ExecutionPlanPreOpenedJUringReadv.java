package bench.random.read;

import com.davidvlijmincx.lio.api.FileDescriptor;
import com.davidvlijmincx.lio.api.JUring;
import com.davidvlijmincx.lio.api.LinuxOpenOptions;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_IOPOLL;
import static com.davidvlijmincx.lio.api.IoUringOptions.IORING_SETUP_SINGLE_ISSUER;

@State(Scope.Thread)
public class ExecutionPlanPreOpenedJUringReadv {

    public JUring jUring;
    public FileDescriptor[] taskFds;
    private List<FileDescriptor> openFileDescriptors;

    @Setup
    public void setup(VectoredTaskCreator taskCreator) {
        jUring = new JUring(2500, IORING_SETUP_SINGLE_ISSUER);

        Map<String, FileDescriptor> fdByPath = new HashMap<>();
        openFileDescriptors = new ArrayList<>();

        for (Task task : taskCreator.readTasks) {
            String path = task.pathAsString();
            if (!fdByPath.containsKey(path)) {
                FileDescriptor fd = new FileDescriptor(path, LinuxOpenOptions.READ, 0);
                fdByPath.put(path, fd);
                openFileDescriptors.add(fd);
            }
        }

        taskFds = new FileDescriptor[taskCreator.readTasks.length];
        for (int i = 0; i < taskCreator.readTasks.length; i++) {
            taskFds[i] = fdByPath.get(taskCreator.readTasks[i].pathAsString());
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
