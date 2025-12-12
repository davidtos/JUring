package com.davidvlijmincx.lio.api;

final public class NativeDispatcher {

    public static final LibCDispatcher C = LibCDispatcher.create();

    static LibUringDispatcher getUringInstance(int queueDepth, IoUringOptions... ioUringflags) {
        return LibUringDispatcher.create(queueDepth, ioUringflags);
    }

    private NativeDispatcher() {
    }
}
