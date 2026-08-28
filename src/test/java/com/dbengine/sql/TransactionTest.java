package com.dbengine.sql;

import com.dbengine.sql.ast.Statement;
import com.dbengine.storage.DatabaseManager;
import com.dbengine.storage.DiskManager;
import com.dbengine.buffer.BufferPoolManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    private DiskManager diskManager;
    private BufferPoolManager bpm;
    private StatementExecutor executor;
    private Path dbFile;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        dbFile = tempDir.resolve("tx-test.db");
        diskManager = new DiskManager(dbFile);
        bpm = new BufferPoolManager(10, diskManager);
        executor = new StatementExecutor(new DatabaseManager(tempDir.resolve("data").toString()), bpm);

        // FIX: Setup active database environment
        executeSql("CREATE DATABASE testdb;");
        executeSql("USE testdb;");
        executeSql("CREATE TABLE accounts (id INT PRIMARY KEY, balance INT);");
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    private QueryResult executeSql(String sql) {
        Lexer lexer = new Lexer(sql);
        Statement stmt = new Parser(lexer.tokenize()).parse().get(0);
        return executor.execute(stmt);
    }

    @Test
    void testTransaction_RollbackDiscardsMemory() {
        executeSql("BEGIN;");
        executeSql("INSERT INTO accounts VALUES (1, 500);");

        QueryResult innerSelect = executeSql("SELECT * FROM accounts;");
        assertEquals(1, innerSelect.rows().size());

        executeSql("ROLLBACK;");

        QueryResult outerSelect = executeSql("SELECT * FROM accounts;");
        assertEquals(0, outerSelect.rows().size());
    }

    @Test
    void testTransaction_CommitPersistsToStorage() throws IOException {
        executeSql("BEGIN TRANSACTION;");
        executeSql("INSERT INTO accounts VALUES (2, 1000);");
        executeSql("COMMIT;");

        // Simulate a graceful engine shutdown by flushing all dirty pages to disk
        for (long i = 0; i < diskManager.getNumPages(); i++) {
            bpm.flushPage(new com.dbengine.storage.PageId(i));
        }

        // Simulate reconnecting to the database file completely cold
        diskManager.close();
        DiskManager coldDisk = new DiskManager(dbFile);
        BufferPoolManager coldBpm = new BufferPoolManager(10, coldDisk);
        DatabaseManager coldDbManager = new DatabaseManager(dbFile.getParent().resolve("data").toString());
        StatementExecutor coldExecutor = new StatementExecutor(coldDbManager, coldBpm);

        // USE testdb auto-loads the persisted catalog.meta!
        coldExecutor.execute(new Parser(new Lexer("USE testdb;").tokenize()).parse().get(0));

        // Attempt select on the new connection
        Statement select = new Parser(new Lexer("SELECT * FROM accounts;").tokenize()).parse().get(0);
        QueryResult coldSelect = coldExecutor.execute(select);

        assertEquals(1, coldSelect.rows().size());
        assertEquals("1000", coldSelect.rows().get(0).get(1));

        coldDisk.close();
    }

    @Test
    void testTransaction_Errors() {
        QueryResult failCommit = executeSql("COMMIT;");
        assertFalse(failCommit.success());
        assertTrue(failCommit.message().contains("No active"));

        executeSql("BEGIN;");
        QueryResult failBegin = executeSql("BEGIN;");
        assertFalse(failBegin.success());
        assertTrue(failBegin.message().contains("already active"));
    }
}
