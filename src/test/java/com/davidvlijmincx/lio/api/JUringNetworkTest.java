package com.davidvlijmincx.lio.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JUringNetworkTest {


    @Test
    void testJUringNetwork() {
        try (JUringNetwork network = new JUringNetwork(256)) {
            int serverSocket = network.setupListener();

            for (int i = 0; i < 5; i++) {
                network.prepareAccept(serverSocket);
                network.submit();

                NetworkResult result = network.waitForResult();
                if (result.getResult() >= 0) {
                    // Handle client connection...
                    HttpResponse response = new HttpResponse(200, "text/html", """
                        <html>
                            <head>
                                <title>
                                    IO_URING!
                                </title>
                            </head>
                            <body>
                            <h1>Hello, great world!!!</h1>
                            </body>
                        </html>
                        """.getBytes());
                    network.prepareWrite((int)result.getResult(), response.toBytes());
                    network.submit();
                    network.waitForResult();

                }
            }

        }
    }


}