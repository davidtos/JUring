package com.davidvlijmincx.lio.api;

import bench.BenchmarkFiles;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

public class idGenTest {

    @Test
    public void test2() {

        try (JUring jUring = new JUring(2500)) {
            final var paths = BenchmarkFiles.filesTooRead;

                ArrayList<FileDescriptor> openFiles = new ArrayList<>(5000);
                Map<Long, String> idpath = new HashMap<>(5000);

                try {
                    int j = 0;
                    for (var path : paths) {

                        FileDescriptor fd = new FileDescriptor(path.sPath(), Flag.READ, 0);
                        openFiles.add(fd);

                        long id = jUring.prepareRead(fd, path.bufferSize(), path.offset());
                        idpath.put(id, path.sPath());

                        j++;
                        if (j % 100 == 0) {
                            jUring.submit();
                        }
                    }
                    jUring.submit();
                    for (int i = 0; i < paths.length; i++) {
                        Result result = jUring.waitForResult();

                        if (result instanceof AsyncReadResult r) {
                            String path = idpath.remove(r.getId());
                            path = path.substring(path.lastIndexOf('/') + 6, path.length() -4);

                            MemorySegment buffer = r.getBuffer();

                            if(!buffer.getString(0).contains(path)){
                                fail("The wrong content was returned for the path: " + path);
                            }

                            r.freeBuffer();
                        }
                    }

                    for (FileDescriptor fd : openFiles) {
                        fd.close();
                    }

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

        }

    }
}