package com.davidvlijmincx.lio.api.virtual;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public record FileTooReadData(String sPath, Path path, int fileSize, int offset, int bufferSize) {

    final static Random random = new Random(315315153152442L);
    public static final int READ_SIZE = 4096;

    public static FileTooReadData fromPath(Path path) {
        String spath = path.toString();
        int fileSize;
        try {
            fileSize = (int) Files.size(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int offset = random.nextInt(0, fileSize - READ_SIZE);

        return new FileTooReadData(spath, path, fileSize, offset, READ_SIZE);
    }
}
