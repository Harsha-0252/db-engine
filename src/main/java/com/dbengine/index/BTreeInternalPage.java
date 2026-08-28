package com.dbengine.index;

import com.dbengine.storage.PageId;

import java.nio.ByteBuffer;

/**
 * Physical layout of an Internal Page:
 * 0-11: BTreePageHeader (Type, Current Size, Max Size)
 * 12+: Key-Value Pairs. Each pair is 8 bytes:
 *      - Key (Integer, 4 bytes)
 *      - PageId (Integer, 4 bytes) - Points to the child page
 *
 * Note: The Key at index 0 is conceptually "infinity" / ignored.
 * The PageId at index 0 is the pointer to the leftmost child.
 */
public class BTreeInternalPage extends BTreePageHeader {

    private static final int INTERNAL_HEADER_SIZE = 12;
    private static final int PAIR_SIZE = 8; // 4(key) + 4(pageId)

    public BTreeInternalPage(ByteBuffer buffer) {
        super(buffer);
    }

    public void init(int maxSize) {
        setPageType(BTreePageType.INTERNAL);
        setCurrentSize(0);
        setMaxSize(maxSize);
    }

    public int getKeyAt(int index) {
        return buffer.getInt(INTERNAL_HEADER_SIZE + index * PAIR_SIZE);
    }

    public void setKeyAt(int index, int key) {
        buffer.putInt(INTERNAL_HEADER_SIZE + index * PAIR_SIZE, key);
    }

    public PageId getPageIdAt(int index) {
        return new PageId(buffer.getInt(INTERNAL_HEADER_SIZE + index * PAIR_SIZE + 4));
    }

    public void setPageIdAt(int index, PageId pageId) {
        buffer.putInt(INTERNAL_HEADER_SIZE + index * PAIR_SIZE + 4, (int) pageId.value());
    }

    /**
     * Determines which child page contains the target key.
     */
    public PageId lookup(int key) {
        // Start at 1, because index 0's key is ignored (it's the leftmost pointer)
        for (int i = 1; i < getCurrentSize(); i++) {
            if (key < getKeyAt(i)) {
                return getPageIdAt(i - 1);
            }
        }
        // If the key is greater than or equal to all keys, it belongs in the rightmost child
        return getPageIdAt(getCurrentSize() - 1);
    }

    /**
     * Inserts a routing key and its right-side child pointer into the node.
     */
    public boolean insert(int key, PageId childPageId) {
        if (getCurrentSize() >= getMaxSize()) {
            return false; // Node is full, requires splitting
        }

        // Find the index to insert
        int insertIndex = 1;
        while (insertIndex < getCurrentSize() && getKeyAt(insertIndex) < key) {
            insertIndex++;
        }

        // Shift elements right to make room
        int currentSize = getCurrentSize();
        for (int i = currentSize; i > insertIndex; i--) {
            setKeyAt(i, getKeyAt(i - 1));
            setPageIdAt(i, getPageIdAt(i - 1));
        }

        // Insert the new key and page pointer
        setKeyAt(insertIndex, key);
        setPageIdAt(insertIndex, childPageId);

        increaseSize(1);
        return true;
    }
}
