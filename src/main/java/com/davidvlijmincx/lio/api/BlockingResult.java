package com.davidvlijmincx.lio.api;

public sealed interface BlockingResult permits BlockingReadResult, BlockingWriteResult {

    void setResult(Result result);
}
