package com.dbengine.common;

/**
 * Engine-wide constants. Kept in one place so every package (storage, buffer,
 * index, ...) agrees on the same physical page size without importing each other.
 */
public final class Constants {

    private Constants() {
        // no instances
    }

    /** Every page on disk and in memory is exactly this many bytes. */
    public static final int PAGE_SIZE = 4096;

    /** Sentinel used for "no page" / "not yet allocated". */
    public static final long INVALID_PAGE_ID = -1L;
}
