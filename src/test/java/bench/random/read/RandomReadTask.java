package bench.random.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public record RandomReadTask(String sPath, Path path, int fileSize, int offset, int bufferSize) {

    final static Random random = new Random(315315153152442L);

    public static RandomReadTask fromPath(Path path, int readSize) {
        int fileSize;
        try {
            fileSize = (int) Files.size(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int offset = random.nextInt(0, fileSize - readSize);
        return new RandomReadTask(path.toString(), path, fileSize, offset, readSize);
    }

}
