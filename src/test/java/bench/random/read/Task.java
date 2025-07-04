package bench.random.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public record Task(Type type, String sPath, Path path, int fileSize, int offset, int bufferSize) {

    final static Random random = new Random(315315153152442L);

    public static Task fromPath(Type type, Path path, int readSize) {
        int fileSize;
        try {
            fileSize = (int) Files.size(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int offset = 0; //random.nextInt(0, fileSize - readSize);
        return new Task(type, path.toString(), path, fileSize, offset, readSize);
    }

}

enum Type {
    READ, WRITE
}