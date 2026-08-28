package com.dbengine.storage;

import com.dbengine.common.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DiskManagerTest {

    private DiskManager diskManager;
    private Path dbFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        // @TempDir creates a temporary directory that JUnit cleans up automatically
        dbFile = tempDir.resolve("test-database.db");
        diskManager = new DiskManager(dbFile);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Ensure file locks are released after every test
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    void newDiskManagerStartsEmpty() {
        assertEquals(0, diskManager.getNumPages());
        assertTrue(Files.exists(dbFile));
    }

    @Test
    void allocatePageIncrementsCountAndGrowsFile() throws IOException {
        PageId pageId0 = diskManager.allocatePage();
        assertEquals(0, pageId0.value());
        assertEquals(1, diskManager.getNumPages());
        assertEquals(Constants.PAGE_SIZE, Files.size(dbFile));

        PageId pageId1 = diskManager.allocatePage();
        assertEquals(1, pageId1.value());
        assertEquals(2, diskManager.getNumPages());
        assertEquals(Constants.PAGE_SIZE * 2, Files.size(dbFile));
    }

    @Test
    void readAndWritePagePreservesData() throws IOException {
        PageId pageId = diskManager.allocatePage();
        Page writePage = new Page(pageId);

        // Put some recognizable data in the buffer
        String testString = "Database Engine Test Data";
        writePage.getBuffer().put(testString.getBytes());
        diskManager.writePage(pageId, writePage);

        // Read it back into a completely fresh Page object
        Page readPage = new Page();
        diskManager.readPage(pageId, readPage);

        // Verify the data matches
        byte[] readBytes = new byte[testString.length()];
        readPage.getBuffer().position(0);
        readPage.getBuffer().get(readBytes);

        assertEquals(testString, new String(readBytes));
    }

    @Test
    void persistenceAcrossRestarts() throws IOException {
        // 1. Allocate a page and write data
        PageId pageId = diskManager.allocatePage();
        Page writePage = new Page(pageId);
        writePage.getBuffer().putInt(42); // Write the number 42
        diskManager.writePage(pageId, writePage);

        // 2. "Crash" or shutdown the database
        diskManager.close();

        // 3. Restart the database using the same file
        DiskManager recoveredDiskManager = new DiskManager(dbFile);

        // It should know there is exactly 1 page based on the file size
        assertEquals(1, recoveredDiskManager.getNumPages());

        // We should be able to read our data back
        Page readPage = new Page();
        recoveredDiskManager.readPage(pageId, readPage);
        readPage.getBuffer().position(0);

        assertEquals(42, readPage.getBuffer().getInt());

        recoveredDiskManager.close();
    }

    @Test
    void readingUnallocatedPageThrowsException() {
        PageId invalidId = new PageId(999);
        Page page = new Page();

        assertThrows(IOException.class, () -> diskManager.readPage(invalidId, page),
                "Reading a page that doesn't exist should throw an IOException");
    }
}
