package bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Collection of benchmark test files
 */
public class BenchmarkFiles {
    public static final String BENCHMARK_FILE_EXTENSION = ".bin";
    //public static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/media/david/Data2/text_files");
    public static final Path BASE_BENCHMARK_FILES_DIR = Path.of("/mnt/smb_share/text_files/");
    public static final FileTooReadData[] filesTooRead;

    static {
        try (Stream<Path> files = Files.walk(BASE_BENCHMARK_FILES_DIR)){
            filesTooRead = files
                    .filter(p -> p.getFileName().toString().endsWith(BENCHMARK_FILE_EXTENSION))
                    .map(FileTooReadData::fromPath)
                    .toArray(FileTooReadData[]::new);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}


