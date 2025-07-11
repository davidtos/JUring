package com.davidvlijmincx.lio.api;

import java.lang.foreign.*;
import java.util.function.*;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.MemoryLayout.PathElement.*;

/**
 * {@snippet lang = c:
 * struct com.davidvlijmincx.lio.api.iovec {
 *     void *iov_base;
 *     size_t iov_len;
 * }
 *}
 */
public class Iovec {

    Iovec() {
        // Should not be called directly
    }

    private static final GroupLayout $LAYOUT = MemoryLayout.structLayout(
            ValueLayout.ADDRESS
                    .withTargetLayout(MemoryLayout.sequenceLayout(Long.MAX_VALUE, ValueLayout.JAVA_BYTE)).withName("iov_base"),
            JAVA_LONG.withName("iov_len")
    ).withName("com.davidvlijmincx.lio.api.iovec");

    /**
     * The layout of this struct
     */
    public static final GroupLayout layout() {
        return $LAYOUT;
    }

    private static final AddressLayout iov_base$LAYOUT = (AddressLayout) $LAYOUT.select(groupElement("iov_base"));

    /**
     * Layout for field:
     * {@snippet lang = c:
     * void *iov_base
     *}
     */
    public static final AddressLayout iov_base$layout() {
        return iov_base$LAYOUT;
    }

    private static final long iov_base$OFFSET = 0;

    /**
     * Offset for field:
     * {@snippet lang = c:
     * void *iov_base
     *}
     */
    public static final long iov_base$offset() {
        return iov_base$OFFSET;
    }

    /**
     * Getter for field:
     * {@snippet lang = c:
     * void *iov_base
     *}
     */
    public static MemorySegment iov_base(MemorySegment struct) {
        return struct.get(iov_base$LAYOUT, iov_base$OFFSET);
    }

    /**
     * Setter for field:
     * {@snippet lang = c:
     * void *iov_base
     *}
     */
    public static void iov_base(MemorySegment struct, MemorySegment fieldValue) {
        struct.set(iov_base$LAYOUT, iov_base$OFFSET, fieldValue);
    }

    private static final OfLong iov_len$LAYOUT = (OfLong) $LAYOUT.select(groupElement("iov_len"));

    /**
     * Layout for field:
     * {@snippet lang = c:
     * size_t iov_len
     *}
     */
    public static final OfLong iov_len$layout() {
        return iov_len$LAYOUT;
    }

    private static final long iov_len$OFFSET = 8;

    /**
     * Offset for field:
     * {@snippet lang = c:
     * size_t iov_len
     *}
     */
    public static final long iov_len$offset() {
        return iov_len$OFFSET;
    }

    /**
     * Getter for field:
     * {@snippet lang = c:
     * size_t iov_len
     *}
     */
    public static long iov_len(MemorySegment struct) {
        return struct.get(iov_len$LAYOUT, iov_len$OFFSET);
    }

    /**
     * Setter for field:
     * {@snippet lang = c:
     * size_t iov_len
     *}
     */
    public static void iov_len(MemorySegment struct, long fieldValue) {
        struct.set(iov_len$LAYOUT, iov_len$OFFSET, fieldValue);
    }

    /**
     * Obtains a slice of {@code arrayParam} which selects the array element at {@code index}.
     * The returned segment has address {@code arrayParam.address() + index * layout().byteSize()}
     */
    public static MemorySegment asSlice(MemorySegment array, long index) {
        return array.asSlice(layout().byteSize() * index);
    }

    /**
     * The size (in bytes) of this struct
     */
    public static long sizeof() {
        return layout().byteSize();
    }

    /**
     * Allocate a segment of size {@code layout().byteSize()} using {@code allocator}
     */
    public static MemorySegment allocate(SegmentAllocator allocator) {
        return allocator.allocate(layout());
    }

    /**
     * Allocate an array of size {@code elementCount} using {@code allocator}.
     * The returned segment has size {@code elementCount * layout().byteSize()}.
     */
    public static MemorySegment allocateArray(long elementCount, SegmentAllocator allocator) {
        return allocator.allocate(MemoryLayout.sequenceLayout(elementCount, layout()));
    }

    /**
     * Reinterprets {@code addr} using target {@code arena} and {@code cleanupAction} (if any).
     * The returned segment has size {@code layout().byteSize()}
     */
    public static MemorySegment reinterpret(MemorySegment addr, Arena arena, Consumer<MemorySegment> cleanup) {
        return reinterpret(addr, 1, arena, cleanup);
    }

    /**
     * Reinterprets {@code addr} using target {@code arena} and {@code cleanupAction} (if any).
     * The returned segment has size {@code elementCount * layout().byteSize()}
     */
    public static MemorySegment reinterpret(MemorySegment addr, long elementCount, Arena arena, Consumer<MemorySegment> cleanup) {
        return addr.reinterpret(layout().byteSize() * elementCount, arena, cleanup);
    }
}

