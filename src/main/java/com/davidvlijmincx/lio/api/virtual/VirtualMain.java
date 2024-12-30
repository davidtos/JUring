package com.davidvlijmincx.lio.api.virtual;

import com.davidvlijmincx.lio.api.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class VirtualMain {


    public static void main(String[] args) {

//        try (JLibUringVirtual vring = new JLibUringVirtual(1000, false)) {
//
//            ReadResult r = vring.prepareRead("/home/david/Desktop/tmp_file_read", 9, 0);
//            vring.submit();
//
//            r.getBuffer().set(JAVA_BYTE, r.getResult() - 1, (byte) 0);
//            String string = r.getBuffer().getString(0);
//            System.out.println(string);
//            vring.freeReadBuffer(r.getBuffer());
//        }
//
//        try (JLibUringVirtual vring = new JLibUringVirtual(1000, false)) {
//            String a = "Hello, form Java inside blocking request... 1111";
//            var StringBytes = a.getBytes();
//
//            BlockingWriteResult f = vring.prepareWrite("/home/david/Desktop/tmp_file_write", StringBytes, 0);
//            vring.submit();
//            f.getResult();
//            System.out.println("f = " + f.getId());
//        }
        final var paths = BenchmarkFiles.filesTooRead;


            try (JLibUringVirtual q = new JLibUringVirtual(2300, false)) {

                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

                    for (int i = 0; i < paths.length; i++) {
                        BlockingReadResult r = q.prepareRead(paths[i].sPath(), paths[i].bufferSize(), paths[i].offset());
                        q.submit();
                        executor.execute(() -> {
//                        r.getBuffer().set(JAVA_BYTE, r.getResult() - 1, (byte) 0);
//                        String string = r.getBuffer().getString(0);
//                        System.out.println(string);
                            q.freeReadBuffer(r.getBuffer());
                        });
                    }
                }
            }


//        for (int z = 0; z < 30; z++) {
//
//            try (JUring q = new JUring(2300, false)) {
//
//                try {
//                    for (int i = 0; i < paths.length; i++) {
//                        q.prepareRead(paths[i].sPath(), paths[i].bufferSize(), paths[i].offset());
//
//                        if (i % 100 == 0) {
//                            q.submit();
//                        }
//
//                    }
//
//                    q.submit();
//
//                    for (int i = 0; i < paths.length; i++) {
//                        Result result = q.waitForResult();
//
//                        if (result instanceof AsyncReadResult r) {
//
//                            q.freeReadBuffer(r.getBuffer());
//                        }
//                    }
//
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }
//            }
//
//
//        }






    }
}
