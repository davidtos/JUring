package bench.random.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public record RandomWriteTask(String sPath, Path path, int fileSize, int offset, int bufferSize) {

    final static Random random = new Random(315315153152442L);

    public static RandomWriteTask fromPath(Path path, int readSize) {
        int fileSize;
        try {
            fileSize = (int) Files.size(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int offset = random.nextInt(0, fileSize - readSize);
        return new RandomWriteTask(path.toString(), path, fileSize, offset, readSize);
    }

}
