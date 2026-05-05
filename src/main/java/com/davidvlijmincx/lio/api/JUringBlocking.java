package com.davidvlijmincx.lio.api;


import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Supplier;

public class JUringBlocking implements AutoCloseable {

    public final Duration timeout;
    private final Map<Long, CompletableFuture<? extends Result>> requests;
    private final JUring jUring;
    private boolean running = true;
    private Thread pollerThread;

    public JUringBlocking(int queueDepth, IoUringOptions... ioUringFlags) {
        this(queueDepth, Duration.ofMillis(-1), ioUringFlags);
    }

    public JUringBlocking(int queueDepth, Duration timeout, IoUringOptions... ioUringFlags) {
        this.jUring = new JUring(queueDepth, ioUringFlags);
        this.timeout = timeout;
        this.requests = new ConcurrentHashMap<>(queueDepth * 6, 0.5f);
        startPoller();
    }

    private void startPoller() {
        pollerThread = Thread.ofPlatform().daemon(true).start(() -> {
            while (running) {
                jUring.peekForBatchResult(100).forEach(result -> {
                    var request = requests.remove(result.id());
                    switch (result) {
                        case ReadResult r -> ((CompletableFuture<ReadResult>) request).complete(r);
                        case ReadResultFixed r -> ((CompletableFuture<ReadResultFixed>) request).complete(r);
                        case WriteResult r -> ((CompletableFuture<WriteResult>) request).complete(r);
                        case OpenResult r -> ((CompletableFuture<OpenResult>) request).complete(r);
                        case CloseResult r -> ((CompletableFuture<CloseResult>) request).complete(r);
                        case ReadvResult r -> ((CompletableFuture<ReadvResult>) request).complete(r);
                        case AcceptResult r -> ((CompletableFuture<AcceptResult>) request).complete(r);
                        case ConnectResult r -> ((CompletableFuture<ConnectResult>) request).complete(r);
                        case RecvResult r -> ((CompletableFuture<RecvResult>) request).complete(r);
                        case SendResult r -> ((CompletableFuture<SendResult>) request).complete(r);
                    }
                });
                sleepInterval();
            }
        });
    }

    private void sleepInterval() {
        if (timeout.isNegative() || timeout.isZero()) {
            Thread.yield();
            return;
        }
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void submit() {
        jUring.submit();
    }

    private <T extends Result> Future<T> prepareAsync(Supplier<Long> prepareOperation) {
        long id = prepareOperation.get();
        CompletableFuture<T> result = new CompletableFuture<>();
        requests.put(id, result);
        return result;
    }

    public Future<ReadResult> prepareRead(FileDescriptor fd, int size, long offset) {
        return prepareAsync(() -> jUring.prepareRead(fd, size, offset));
    }

    public Future<WriteResult> prepareWrite(FileDescriptor fd, byte[] bytes, long offset) {
        return prepareAsync(() -> jUring.prepareWrite(fd, bytes, offset));
    }

    public Future<ReadResult> prepareRead(int indexFD, int readSize, long offset, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareRead(indexFD, readSize, offset, sqeOptions));
    }

    public Future<ReadResultFixed> prepareReadFixed(FileDescriptor fd, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareReadFixed(fd, readSize, offset, bufferIndex, sqeOptions));
    }

    public Future<ReadResultFixed> prepareReadFixed(int indexFD, int readSize, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareReadFixed(indexFD, readSize, offset, bufferIndex, sqeOptions));
    }

    public Future<WriteResult> prepareWrite(int indexFD, byte[] bytes, long offset, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareWrite(indexFD, bytes, offset, sqeOptions));
    }

    public Future<WriteResult> prepareWriteFixed(FileDescriptor fd, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareWriteFixed(fd, bytes, offset, bufferIndex, sqeOptions));
    }

    public Future<WriteResult> prepareWriteFixed(int indexFD, byte[] bytes, long offset, int bufferIndex, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareWriteFixed(indexFD, bytes, offset, bufferIndex, sqeOptions));
    }

    public Future<OpenResult> prepareOpen(String filePath, int flags, int mode, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareOpen(filePath, flags, mode, sqeOptions));
    }

    public Future<OpenResult> prepareOpenDirect(String filePath, LinuxOpenOptions flags, int mode, int fileIndex, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareOpenDirect(filePath, flags.getValue(), mode, fileIndex, sqeOptions));
    }

    public Future<CloseResult> prepareClose(FileDescriptor fd, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareClose(fd, sqeOptions));
    }

    public Future<CloseResult> prepareCloseDirect(int fileIndex, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareCloseDirect(fileIndex, sqeOptions));
    }

    // -------------------------------------------------------------------------
    // Socket helpers (delegated to inner JUring)
    // -------------------------------------------------------------------------

    public int createServerSocket(int port, int backlog) {
        return jUring.createServerSocket(port, backlog);
    }

    public int createClientSocket() {
        return jUring.createClientSocket();
    }

    // -------------------------------------------------------------------------
    // Socket async operations
    // -------------------------------------------------------------------------

    public Future<AcceptResult> prepareAccept(int serverFd, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareAccept(serverFd, sqeOptions));
    }

    public long prepareMultishotAccept(int serverFd, SqeOptions... sqeOptions) {
        return jUring.prepareMultishotAccept(serverFd, sqeOptions);
    }

    public Future<ConnectResult> prepareConnect(int fd, String host, int port, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareConnect(fd, host, port, sqeOptions));
    }

    public Future<RecvResult> prepareRecv(int fd, int length, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareRecv(fd, length, sqeOptions));
    }

    public Future<RecvResult> prepareRecv(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareRecv(fd, buffer, length, sqeOptions));
    }

    public Future<SendResult> prepareSend(int fd, byte[] bytes, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareSend(fd, bytes, sqeOptions));
    }

    public Future<SendResult> prepareSend(int fd, MemorySegment buffer, int length, SqeOptions... sqeOptions) {
        return prepareAsync(() -> jUring.prepareSend(fd, buffer, length, sqeOptions));
    }

    public MemorySegment[] registerBuffers(int size, int nrOfBuffers) {
        return jUring.registerBuffers(size, nrOfBuffers);
    }

    public int registerFiles(FileDescriptor... fileDescriptors) {
        return jUring.registerFiles(fileDescriptors);
    }

    public int registerFilesUpdate(int offset, int[] fileDescriptors) {
        return jUring.registerFilesUpdate(offset, fileDescriptors);
    }

    @Override
    public void close() {
        running = false;
        try {
            pollerThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        jUring.close();
    }
}
