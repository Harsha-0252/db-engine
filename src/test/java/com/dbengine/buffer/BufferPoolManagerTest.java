package com.dbengine.buffer;

import com.dbengine.storage.DiskManager;
import com.dbengine.storage.Page;
import com.dbengine.storage.PageId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolManagerTest {

    private DiskManager diskManager;
    private BufferPoolManager bpm;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Path dbFile = tempDir.resolve("test-bpm.db");
        diskManager = new DiskManager(dbFile);
        // Extremely small pool size (just 3 pages) to force evictions easily
        bpm = new BufferPoolManager(3, diskManager);
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    @Test
    void testBufferPoolEvictionAndDirtyWriteBack() throws IOException {
        // 1. Allocate 3 pages (filling the pool completely)
        Page page0 = bpm.newPage();
        Page page1 = bpm.newPage();
        Page page2 = bpm.newPage();

        assertNotNull(page0);
        assertNotNull(page1);
        assertNotNull(page2);

        PageId id0 = page0.getPageId();
        PageId id1 = page1.getPageId();
        PageId id2 = page2.getPageId();

        // 2. Write some specific data to page 0 and mark it dirty
        page0.getBuffer().putInt(0, 9999);

        // 3. Unpin all pages so they are eligible for eviction
        bpm.unpinPage(id0, true);  // page0 is dirty
        bpm.unpinPage(id1, false);
        bpm.unpinPage(id2, false);

        // 4. Allocate a 4th page. This MUST trigger an eviction.
        // Because of LRU, page0 was unpinned first, so it will be evicted.
        Page page3 = bpm.newPage();
        assertNotNull(page3);

        // 5. Fetch page 0 again. This forces page3 (or 1/2) to be evicted,
        // and reads page 0 back from the disk.
        Page fetchedPage0 = bpm.fetchPage(id0);
        assertNotNull(fetchedPage0);

        // 6. Verify the dirty data was actually written to disk during eviction and loaded back
        assertEquals(9999, fetchedPage0.getBuffer().getInt(0),
                "Data was lost! The dirty page was not flushed to disk before eviction.");
    }
}
