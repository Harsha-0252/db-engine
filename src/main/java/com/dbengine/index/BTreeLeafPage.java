package com.dbengine.index;

import com.dbengine.storage.PageId;
import com.dbengine.table.RecordId;

import java.nio.ByteBuffer;

/**
 * Physical layout of a Leaf Page:
 * 0-11: BTreePageHeader (Type, Current Size, Max Size)
 * 12-15: Next Page ID (for range scans, -1 if none)
 * 16+: Key-Value Pairs. Each pair is 12 bytes:
 *      - Key (Integer, 4 bytes)
 *      - RecordId (PageId: 4 bytes, SlotId: 4 bytes)
 */
public class BTreeLeafPage extends BTreePageHeader {

    private static final int NEXT_PAGE_OFFSET = HEADER_SIZE; // Byte 12
    private static final int LEAF_HEADER_SIZE = 16;
    private static final int PAIR_SIZE = 12; // 4(key) + 4(pageId) + 4(slotId)

    public BTreeLeafPage(ByteBuffer buffer) {
        super(buffer);
    }

    public void init(int maxSize) {
        setPageType(BTreePageType.LEAF);
        setCurrentSize(0);
        setMaxSize(maxSize);
        setNextPageId(new PageId(-1));
    }

    public PageId getNextPageId() {
        return new PageId(buffer.getInt(NEXT_PAGE_OFFSET));
    }

    public void setNextPageId(PageId nextPageId) {
        buffer.putInt(NEXT_PAGE_OFFSET, (int) nextPageId.value());
    }

    public int getKeyAt(int index) {
        return buffer.getInt(LEAF_HEADER_SIZE + index * PAIR_SIZE);
    }

    public RecordId getRecordIdAt(int index) {
        int offset = LEAF_HEADER_SIZE + index * PAIR_SIZE;
        PageId pageId = new PageId(buffer.getInt(offset + 4));
        int slotId = buffer.getInt(offset + 8);
        return new RecordId(pageId, slotId);
    }

    /**
     * Inserts a key and RecordId, maintaining strictly sorted order.
     * Returns true if successful, false if the page is full.
     */
    public boolean insert(int key, RecordId value) {
        if (getCurrentSize() >= getMaxSize()) {
            return false; // Page is full! We will need to implement splitting later.
        }

        // Find the correct index to insert at to maintain sorted order
        int insertIndex = 0;
        while (insertIndex < getCurrentSize() && getKeyAt(insertIndex) < key) {
            insertIndex++;
        }

        // Shift all pairs to the right to make room
        int currentSize = getCurrentSize();
        for (int i = currentSize; i > insertIndex; i--) {
            int srcOffset = LEAF_HEADER_SIZE + (i - 1) * PAIR_SIZE;
            int destOffset = LEAF_HEADER_SIZE + i * PAIR_SIZE;

            // Move key
            buffer.putInt(destOffset, buffer.getInt(srcOffset));
            // Move PageId
            buffer.putInt(destOffset + 4, buffer.getInt(srcOffset + 4));
            // Move SlotId
            buffer.putInt(destOffset + 8, buffer.getInt(srcOffset + 8));
        }

        // Insert the new pair
        int insertOffset = LEAF_HEADER_SIZE + insertIndex * PAIR_SIZE;
        buffer.putInt(insertOffset, key);
        buffer.putInt(insertOffset + 4, (int) value.getPageId().value());
        buffer.putInt(insertOffset + 8, value.getSlotId());

        increaseSize(1);
        return true;
    }

    /**
     * Performs a binary search directly on the byte array to find a RecordId.
     */
    public RecordId lookup(int key) {
        int low = 0;
        int high = getCurrentSize() - 1;

        while (low <= high) {
            int mid = low + (high - low) / 2;
            int midKey = getKeyAt(mid);

            if (midKey == key) {
                return getRecordIdAt(mid);
            } else if (midKey < key) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return null; // Key not found
    }
}
