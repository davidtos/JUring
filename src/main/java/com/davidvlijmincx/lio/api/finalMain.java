package com.davidvlijmincx.lio.api;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class finalMain {


    public static void main(String[] args) {

        try (JUring jUring = new JUring(1000, false)) {

            for (int i = 0; i < 100; i++) {
                jUring.prepareRead("/home/david/Desktop/tmp_file_read", 9, 0);
            }

            jUring.submit();

            for (int i = 0; i < 100; i++) {
                Result result = jUring.waitForResult();

                if (result instanceof ReadResult r) {
                    r.getBuffer().set(JAVA_BYTE, r.getResult() - 1, (byte) 0);
                    String string = r.getBuffer().getString(0);
                    System.out.println(string);
                    jUring.freeReadBuffer(r.getBuffer());
                }
            }

            String a = "Hello, form Java";
            var StringBytes = a.getBytes();
            jUring.prepareWrite("/home/david/Desktop/tmp_file_write", StringBytes, 0);

            jUring.submit();
            Result result = jUring.waitForResult();


            if (result instanceof WriteResult w) {
                System.out.println("w.getResult() = " + w.getResult());
            }

        }
    }
}
