package com.dbengine.index;

import com.dbengine.storage.PageId;
import com.dbengine.table.RecordId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BTreeLeafPageTest {

    @Test
    void testInsertMaintainsSortedOrder() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        BTreeLeafPage leafPage = new BTreeLeafPage(buffer);

        // Let's say this page can hold 100 entries max
        leafPage.init(100);

        assertEquals(BTreePageType.LEAF, leafPage.getPageType());
        assertEquals(0, leafPage.getCurrentSize());

        // Insert out of order!
        leafPage.insert(50, new RecordId(new PageId(5), 1));
        leafPage.insert(10, new RecordId(new PageId(1), 1));
        leafPage.insert(30, new RecordId(new PageId(3), 1));

        assertEquals(3, leafPage.getCurrentSize());

        // Prove the memory shifted them into strict sorted order
        assertEquals(10, leafPage.getKeyAt(0));
        assertEquals(30, leafPage.getKeyAt(1));
        assertEquals(50, leafPage.getKeyAt(2));
    }

    @Test
    void testBinarySearchLookup() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        BTreeLeafPage leafPage = new BTreeLeafPage(buffer);
        leafPage.init(100);

        leafPage.insert(100, new RecordId(new PageId(10), 0));
        leafPage.insert(200, new RecordId(new PageId(20), 0));
        leafPage.insert(300, new RecordId(new PageId(30), 0));

        // Binary search should find the exact RecordId
        RecordId found = leafPage.lookup(200);
        assertNotNull(found);
        assertEquals(20, found.getPageId().value());

        // Should return null for missing keys
        assertNull(leafPage.lookup(250));
    }
}
