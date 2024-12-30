package com.davidvlijmincx.lio.api.virtual;

import com.davidvlijmincx.lio.api.BlockingWriteResult;
import com.davidvlijmincx.lio.api.ReadResult;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

public class VirtualMain {


    public static void main(String[] args) {

        try (JLibUringVirtual vring = new JLibUringVirtual(1000, false)) {

            ReadResult r = vring.prepareRead("/home/david/Desktop/tmp_file_read", 9, 0);
            vring.submit();

            r.getBuffer().set(JAVA_BYTE, r.getResult() - 1, (byte) 0);
            String string = r.getBuffer().getString(0);
            System.out.println(string);
            vring.freeReadBuffer(r.getBuffer());
        }

        try (JLibUringVirtual vring = new JLibUringVirtual(1000, false)) {
            String a = "Hello, form Java inside blocking request... 1111";
            var StringBytes = a.getBytes();

            BlockingWriteResult f = vring.prepareWrite("/home/david/Desktop/tmp_file_write", StringBytes, 0);
            vring.submit();
            f.getResult();
            System.out.println("f = " + f.getId());
        }
    }
}
