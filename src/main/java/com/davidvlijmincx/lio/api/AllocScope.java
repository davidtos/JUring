package com.davidvlijmincx.lio.api;

import java.io.Closeable;
import java.io.IOException;

public final class AllocScope implements Closeable {

    private final JUring jUring;
    private final AllocArena allocArena;

    AllocScope(JUring jUring) {
        this.jUring = jUring;
        allocArena = new AllocArena();
    }

    public long prepareRead(String path, int readSize, int offset){
        return jUring.prepareRead(path, readSize, offset,  allocArena::allocate);
    }

    @Override
    public void close() {
        allocArena.close();
    }
}
