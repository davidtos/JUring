package bench.random.read;

import java.nio.file.Path;

public


final class Task {
    private final Path path;
    private final String PathAsString;
    private final Type type;
    private final int offset;
    private final int bufferSize;

    public Task(Path path, int bufferSize, Type type, int offset) {
        this.PathAsString = path.toString();
        this.path = path;
        this.bufferSize = bufferSize;
        this.type = type;
        this.offset = offset;
    }

    public String pathAsString() {
        return PathAsString;
    }

    public Type type() {
        return type;
    }

    public int offset() {
        return offset;
    }

    public int bufferSize() {
        return bufferSize;
    }

    public Path path() {
        return path;
    }

    @Override
    public String toString() {
        return "Task[" +
                "PathAsString=" + PathAsString + ", " +
                "type=" + type + ", " +
                "offset=" + offset + ", " +
                "bufferSize=" + bufferSize + ']';
    }


}
