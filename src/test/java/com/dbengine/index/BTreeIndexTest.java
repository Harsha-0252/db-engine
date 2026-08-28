package com.dbengine.index;

import com.dbengine.buffer.BufferPoolManager;
import com.dbengine.storage.DiskManager;
import com.dbengine.storage.Page;
import com.dbengine.storage.PageId;
import com.dbengine.table.RecordId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BTreeIndexTest {

    private DiskManager diskManager;
    private BufferPoolManager bpm;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Path dbFile = tempDir.resolve("index-test.db");
        diskManager = new DiskManager(dbFile);
        bpm = new BufferPoolManager(10, diskManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    @Test
    void testFullBTreeTraversal() throws IOException {
        // --- SETUP THE TREE ---

        // 1. Create Left Leaf Page (Keys < 100)
        Page leftLeaf = bpm.newPage();
        BTreeLeafPage leftLeafNode = new BTreeLeafPage(leftLeaf.getBuffer());
        leftLeafNode.init(100);
        leftLeafNode.insert(25, new RecordId(new PageId(10), 1));
        leftLeafNode.insert(50, new RecordId(new PageId(10), 2));
        bpm.unpinPage(leftLeaf.getPageId(), true);

        // 2. Create Right Leaf Page (Keys >= 100)
        Page rightLeaf = bpm.newPage();
        BTreeLeafPage rightLeafNode = new BTreeLeafPage(rightLeaf.getBuffer());
        rightLeafNode.init(100);
        rightLeafNode.insert(150, new RecordId(new PageId(20), 1));
        rightLeafNode.insert(200, new RecordId(new PageId(20), 2));
        bpm.unpinPage(rightLeaf.getPageId(), true);

        // 3. Create Root Internal Page
        Page root = bpm.newPage();
        BTreeInternalPage rootNode = new BTreeInternalPage(root.getBuffer());
        rootNode.init(100);

        // Setup routing: Leftmost child is leftLeaf. Keys >= 100 go to rightLeaf.
        rootNode.setPageIdAt(0, leftLeaf.getPageId());
        rootNode.increaseSize(1);
        rootNode.insert(100, rightLeaf.getPageId());
        bpm.unpinPage(root.getPageId(), true);

        // --- TEST THE SEARCH ENGINE ---

        BTreeIndex index = new BTreeIndex(bpm, root.getPageId());

        // Search for a key in the Left Leaf
        RecordId rid50 = index.search(50);
        assertNotNull(rid50);
        assertEquals(10, rid50.getPageId().value());
        assertEquals(2, rid50.getSlotId());

        // Search for a key in the Right Leaf
        RecordId rid150 = index.search(150);
        assertNotNull(rid150);
        assertEquals(20, rid150.getPageId().value());
        assertEquals(1, rid150.getSlotId());

        // Search for a missing key
        RecordId missing = index.search(999);
        assertNull(missing);
    }
}
