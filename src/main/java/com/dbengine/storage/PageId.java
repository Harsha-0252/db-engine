package com.dbengine.storage;

import com.dbengine.common.Constants;

public final class PageId {

    public static final PageId INVALID = new PageId(Constants.INVALID_PAGE_ID);

    private final long value;

    public PageId(long value) {
        this.value = value;
    }

    public long value() {
        return value;
    }

    /** Byte offset of this page within the database file. */
    public long toFileOffset() {
        return value * Constants.PAGE_SIZE;
    }

    public boolean isValid() {
        return value >= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PageId other)) return false;
        return value == other.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "PageId{" + value + "}";
    }
}
