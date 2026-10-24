package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileDescriptorTest {

    @Test
    @DisplayName("Opening a valid file creates FileDescriptor with valid fd >= 0")
    void testValidFileOpen() {
        try (FileDescriptor fd = new FileDescriptor("src/test/resources/read_file", LinuxOpenOptions.READ, 0)) {
            assertThat(fd.getFd()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("Opening a non-existent file throws RuntimeException with failure code")
    void testNonExistentFileThrows() {
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                new FileDescriptor("/nonexistent/file/path/that/does/not/exist.bin", LinuxOpenOptions.READ, 0)
        );
        assertThat(ex.getMessage()).contains("Failed to open").contains("fd=-");
    }

    @Test
    @DisplayName("Constructing FileDescriptor with negative fd throws IllegalArgumentException")
    void testNegativeFdThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new FileDescriptor(-1)
        );
        assertThat(ex.getMessage()).contains("Invalid file descriptor: -1");
    }
}
