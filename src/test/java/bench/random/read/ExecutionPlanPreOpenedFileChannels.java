package bench.random.read;

import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@State(Scope.Thread)
public class ExecutionPlanPreOpenedFileChannels {

    public Map<String, FileChannel> openFileChannels;
    private List<FileChannel> allFileChannels;

    @Setup
    public void setup(TaskCreator taskCreator) {
        openFileChannels = new HashMap<>();
        allFileChannels = new ArrayList<>();

        Map<String, FileChannel> uniqueFileChannels = new HashMap<>();
        
        for (Task task : taskCreator.tasks) {
            String filePath = task.pathAsString();
            if (!uniqueFileChannels.containsKey(filePath)) {
                try {
                    FileChannel fileChannel = FileChannel.open(task.path(), StandardOpenOption.READ);
                    uniqueFileChannels.put(filePath, fileChannel);
                    allFileChannels.add(fileChannel);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to open file: " + filePath, e);
                }
            }
        }

        openFileChannels.putAll(uniqueFileChannels);
    }

    @TearDown
    public void tearDown() {
        for (FileChannel fileChannel : allFileChannels) {
            try {
                fileChannel.close();
            } catch (IOException e) {
                System.err.println("Error closing FileChannel: " + e.getMessage());
            }
        }
    }
}