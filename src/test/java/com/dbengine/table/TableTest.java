package com.dbengine.table;

import com.dbengine.buffer.BufferPoolManager;
import com.dbengine.record.Column;
import com.dbengine.record.Schema;
import com.dbengine.record.Tuple;
import com.dbengine.record.Type;
import com.dbengine.record.Value;
import com.dbengine.storage.DiskManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {

    private DiskManager diskManager;
    private BufferPoolManager bpm;
    private Table usersTable;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        // Initialize the bottom three layers of the engine
        Path dbFile = tempDir.resolve("production-db.db");
        diskManager = new DiskManager(dbFile);
        bpm = new BufferPoolManager(10, diskManager);

        // Define a "Users" table schema
        Schema schema = new Schema(List.of(
                new Column("id", Type.INTEGER),
                new Column("username", Type.VARCHAR)
        ));

        usersTable = new Table(bpm, schema);
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    @Test
    void testEndToEndInsertAndRetrieve() throws IOException {
        // 1. Create a new Row
        Tuple user1 = new Tuple();
        user1.addValue(new Value(1));
        user1.addValue(new Value("admin_user"));

        // 2. Insert it into the database!
        RecordId rid1 = usersTable.insertTuple(user1);
        assertNotNull(rid1);

        // 3. Retrieve it back using the RecordId
        Tuple fetchedUser = usersTable.getTuple(rid1);
        assertNotNull(fetchedUser);

        // 4. Verify the data traversed the Buffer Pool and Disk perfectly
        assertEquals(1, fetchedUser.getValues().get(0).getAsInt());
        assertEquals("admin_user", fetchedUser.getValues().get(1).getAsString());
    }

    @Test
    void testMultipleInsertsForcePageAllocation() throws IOException {
        // Insert enough records to fill up one 4096-byte page and force the
        // Table class to ask the BufferPool for a second page.

        RecordId firstRid = null;
        RecordId lastRid = null;

        // Inserting 200 rows with a decent sized string should easily spill over 4KB
        for (int i = 0; i < 200; i++) {
            Tuple t = new Tuple();
            t.addValue(new Value(i));
            t.addValue(new Value("This is a long string to eat up bytes in the slotted page " + i));

            RecordId rid = usersTable.insertTuple(t);

            if (i == 0) firstRid = rid;
            if (i == 199) lastRid = rid;
        }

        // Prove that the first and last records are on entirely different physical pages
        assertNotEquals(firstRid.getPageId(), lastRid.getPageId(),
                "The table did not allocate a new page when the first one filled up!");

        // Prove we can still retrieve the very first record successfully
        Tuple fetchedFirst = usersTable.getTuple(firstRid);
        assertEquals(0, fetchedFirst.getValues().get(0).getAsInt());

        // Prove we can retrieve the very last record successfully
        Tuple fetchedLast = usersTable.getTuple(lastRid);
        assertEquals(199, fetchedLast.getValues().get(0).getAsInt());
    }
}
