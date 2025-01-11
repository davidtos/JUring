package com.davidvlijmincx.lio.api;

public sealed interface WriteResult permits AsyncWriteResult, BlockingWriteResult{

    long getResult();
}
