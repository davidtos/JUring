package com.davidvlijmincx.lio.api;

import java.util.HashMap;
import java.util.Map;

public enum OperationType {
    READ(0),
    WRITE(1),
    WRITE_FIXED(2),
    OPEN(3),
    CLOSE(4);

    private static final Map<Integer, OperationType> map = new HashMap<>();

    private final int index;

    OperationType(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    static {
        for (OperationType pageType : OperationType.values()) {
            map.put(pageType.index, pageType);
        }
    }

    public static OperationType valueOf(int pageType) {
        return map.get(pageType);
    }
}
