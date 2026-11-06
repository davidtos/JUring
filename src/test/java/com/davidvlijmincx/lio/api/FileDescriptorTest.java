package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileDescriptorTest {

    @Test
    void testValidFileOpen() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", LinuxOpenOptions.READ, 0)) {
            assertThat(fd.getFd()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void testNegativeFdThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new FileDescriptor(-1)
        );
        assertThat(ex.getMessage()).contains("Invalid file descriptor: -1");
    }
}
