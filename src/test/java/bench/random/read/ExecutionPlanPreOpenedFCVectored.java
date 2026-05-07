package bench.random.read;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@State(Scope.Thread)
public class ExecutionPlanPreOpenedFCVectored {

    public Map<String, FileChannel> openFileChannels;
    private List<FileChannel> allFileChannels;

    @Setup
    public void setup(VectoredTaskCreator taskCreator) {
        openFileChannels = new HashMap<>();
        allFileChannels = new ArrayList<>();

        for (Task task : taskCreator.readTasks) {
            String filePath = task.pathAsString();
            if (!openFileChannels.containsKey(filePath)) {
                try {
                    FileChannel fc = FileChannel.open(task.path(), StandardOpenOption.READ);
                    openFileChannels.put(filePath, fc);
                    allFileChannels.add(fc);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to open file: " + filePath, e);
                }
            }
        }
    }

    @TearDown
    public void tearDown() {
        for (FileChannel fc : allFileChannels) {
            try {
                fc.close();
            } catch (IOException e) {
                System.err.println("Error closing FileChannel: " + e.getMessage());
            }
        }
    }
}
