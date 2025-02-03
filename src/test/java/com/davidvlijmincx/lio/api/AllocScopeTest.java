package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.junit.jupiter.api.Assertions.*;

class AllocScopeTest {

    JUring jUring;

    @BeforeEach
    void setUp() {
        jUring = new JUring(10);
    }

    @AfterEach
    void tearDown() {
        jUring.close();
    }

    @Test
    void CreateScope(){
        try(AllocScope scope = jUring.allocScope()){
            long id = scope.prepareRead("src/test/resources/read_file", 14, 0);
            jUring.submit();
            Result result = jUring.waitForResult();

            if (result instanceof ReadResult readResult) {
                assertEquals(id, readResult.getId());
                assertEquals(13, readResult.getResult());

                readResult.getBuffer().set(JAVA_BYTE, readResult.getResult(), (byte) 0);
                String string = readResult.getBuffer().getString(0);

                assertEquals("Hello, World!", string);
            } else {
                fail("Result is not a ReadResult");
            }
        }
    }


}