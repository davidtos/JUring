package com.davidvlijmincx.lio.api;

public record HttpResponse(int statusCode, String contentType, byte[] body) {
    public byte[] toBytes() {
        String response = String.format("""
            HTTP/1.1 %d %s
            Content-Type: %s
            Content-Length: %d
            Connection: close

            """,
                statusCode, getStatusText(statusCode),
                contentType,
                body.length);

        byte[] headers = response.getBytes();
        byte[] fullResponse = new byte[headers.length + body.length];
        System.arraycopy(headers, 0, fullResponse, 0, headers.length);
        System.arraycopy(body, 0, fullResponse, headers.length, body.length);

        return fullResponse;
    }

    private String getStatusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> "Unknown";
        };
    }
}