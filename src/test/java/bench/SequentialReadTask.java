package bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record SequentialReadTask(String sPath, Path path, int fileSize) {

    public static SequentialReadTask fromPath(Path path) {
        int fileSize;
        try {
            fileSize = (int) Files.size(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new SequentialReadTask(path.toString(), path, fileSize);
    }

}
