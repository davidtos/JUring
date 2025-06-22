package com.davidvlijmincx.lio.api;

enum DirectoryFileDescriptorFlags {

    AT_FDCWD((byte) -100L);

    byte value;

    DirectoryFileDescriptorFlags(byte value) {
        this.value = value;
    }
}
