package com.davidvlijmincx.lio.api;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class finalMain {


    public static void main(String[] args) {

        try (JUring jUring = new JUring(1000, false)) {

            for (int i = 0; i < 100; i++) {
                long id = jUring.prepareRead("/home/david/Desktop/tmp_file_read", 9, 0);
                System.out.println("read id = " + id);
            }

            jUring.submit();

            for (int i = 0; i < 100; i++) {
                Result result = jUring.waitForResult();

                if (result instanceof ReadResult r) {
                    System.out.println("r.getId() = " + r.getId());
                    r.getBuffer().set(JAVA_BYTE, r.getResult() - 1, (byte) 0);
                    String string = r.getBuffer().getString(0);
                    System.out.println(string);
                    jUring.freeReadBuffer(r.getBuffer());
                }
            }

            String a = "Hello, form Java";
            var StringBytes = a.getBytes();
            long l = jUring.prepareWrite("/home/david/Desktop/tmp_file_write", StringBytes, 0);
            System.out.println("write id = " + l);

            jUring.submit();
            Result result = jUring.waitForResult();


            if (result instanceof WriteResult w) {
                System.out.println("w.getId() = " + w.getId());
                System.out.println("w.getResult() = " + w.getResult());
            }

        }
    }
}
