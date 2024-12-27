package com.davidvlijmincx.lio.api;

public abstract sealed class Result permits ReadResult, WriteResult{
}
