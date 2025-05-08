package com.davidvlijmincx.lio.api;

public class FileDescriptor implements AutoCloseable {

    private final int fd;

    public FileDescriptor(String path, Flag flags, int mode) {
        this.fd = LibCWrapper.OpenFile(path, flags.getValue(), mode);
    }

    int getFd() {
        return fd;
    }

    @Override
    public void close(){
            LibCWrapper.closeFile(fd);
    }

}
