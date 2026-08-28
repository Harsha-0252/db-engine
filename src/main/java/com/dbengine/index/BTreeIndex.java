package com.dbengine.index;

import com.dbengine.buffer.BufferPoolManager;
import com.dbengine.storage.Page;
import com.dbengine.storage.PageId;
import com.dbengine.table.RecordId;

import java.io.IOException;

/**
 * Manages the B+Tree index. Handles traversing from the root node down
 * to the leaf nodes using the BufferPoolManager to load pages into memory.
 */
public class BTreeIndex {
    private final BufferPoolManager bpm;
    private PageId rootPageId;

    public BTreeIndex(BufferPoolManager bpm, PageId rootPageId) {
        this.bpm = bpm;
        this.rootPageId = rootPageId;
    }

    /**
     * Traverses the B+Tree from the root to find the RecordId associated with the key.
     */
    public RecordId search(int key) throws IOException {
        PageId currPageId = rootPageId;
        Page currPage = bpm.fetchPage(currPageId);

        // We use an anonymous subclass of BTreePageHeader just to read the first 12 bytes
        // and figure out what kind of page we are looking at.
        BTreePageHeader header = new BTreePageHeader(currPage.getBuffer()) { };

        // 1. Traverse down through Internal Nodes
        while (header.getPageType() == BTreePageType.INTERNAL) {
            BTreeInternalPage internalPage = new BTreeInternalPage(currPage.getBuffer());

            // Ask the internal node which child page to go to next
            PageId nextChildId = internalPage.lookup(key);

            // Release the current page back to the buffer pool (we only read it, so dirty = false)
            bpm.unpinPage(currPageId, false);

            // Fetch the next page down the tree
            currPageId = nextChildId;
            currPage = bpm.fetchPage(currPageId);
            header = new BTreePageHeader(currPage.getBuffer()) { };
        }

        // 2. We have reached a Leaf Node!
        if (header.getPageType() == BTreePageType.LEAF) {
            BTreeLeafPage leafPage = new BTreeLeafPage(currPage.getBuffer());
            RecordId result = leafPage.lookup(key);

            bpm.unpinPage(currPageId, false);
            return result;
        }

        bpm.unpinPage(currPageId, false);
        return null;
    }
}
