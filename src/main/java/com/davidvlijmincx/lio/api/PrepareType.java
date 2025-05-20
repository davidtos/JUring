package com.davidvlijmincx.lio.api;

import java.util.HashMap;
import java.util.Map;

public enum PrepareType {
    READ(0),
    WRITE(1),
    OPEN(2),
    CLOSE(3);

    private static final Map<Integer,PrepareType> map = new HashMap<>();

    private final int index;

    private PrepareType(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    static {
        for (PrepareType pageType : PrepareType.values()) {
            map.put(pageType.index, pageType);
        }
    }

    public static PrepareType valueOf(int pageType) {
        return map.get(pageType);
    }


}
