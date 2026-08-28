package com.dbengine.index;

import com.dbengine.storage.PageId;
import java.nio.ByteBuffer;

/**
 * Manages the common header for both Internal and Leaf B+Tree pages.
 *
 * Header Layout (12 bytes total):
 * Offset  Size   Field
 * 0       4      Page Type (0 = INVALID, 1 = INTERNAL, 2 = LEAF)
 * 4       4      Current Number of Keys/Pairs
 * 8       4      Max Number of Keys/Pairs (Capacity)
 */
public abstract class BTreePageHeader {
    protected static final int PAGE_TYPE_OFFSET = 0;
    protected static final int CURRENT_SIZE_OFFSET = 4;
    protected static final int MAX_SIZE_OFFSET = 8;
    protected static final int HEADER_SIZE = 12;

    protected final ByteBuffer buffer;

    public BTreePageHeader(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    public boolean isLeafPage() {
        return getPageType() == BTreePageType.LEAF;
    }

    public boolean isInternalPage() {
        return getPageType() == BTreePageType.INTERNAL;
    }

    public BTreePageType getPageType() {
        int typeInt = buffer.getInt(PAGE_TYPE_OFFSET);
        if (typeInt == 1) return BTreePageType.INTERNAL;
        if (typeInt == 2) return BTreePageType.LEAF;
        return BTreePageType.INVALID;
    }

    public void setPageType(BTreePageType type) {
        int typeInt = 0; // INVALID
        if (type == BTreePageType.INTERNAL) typeInt = 1;
        else if (type == BTreePageType.LEAF) typeInt = 2;
        buffer.putInt(PAGE_TYPE_OFFSET, typeInt);
    }

    public int getCurrentSize() {
        return buffer.getInt(CURRENT_SIZE_OFFSET);
    }

    public void setCurrentSize(int size) {
        buffer.putInt(CURRENT_SIZE_OFFSET, size);
    }

    public void increaseSize(int amount) {
        setCurrentSize(getCurrentSize() + amount);
    }

    public int getMaxSize() {
        return buffer.getInt(MAX_SIZE_OFFSET);
    }

    public void setMaxSize(int size) {
        buffer.putInt(MAX_SIZE_OFFSET, size);
    }
}
