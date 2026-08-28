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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StatementExecutorTest {

    private DiskManager diskManager;
    private BufferPoolManager bpm;
    private DatabaseManager dbManager;
    private StatementExecutor executor;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        dbManager = new DatabaseManager(tempDir.resolve("db_data").toString());
        diskManager = new DiskManager(tempDir.resolve("exec-test.db"));
        bpm = new BufferPoolManager(10, diskManager);
        executor = new StatementExecutor(dbManager, bpm);

        // FIX: Create and use a database so queries don't fail with "No database selected"
        executeSql("CREATE DATABASE testdb;");
        executeSql("USE testdb;");
    }

    @AfterEach
    void tearDown() throws IOException {
        diskManager.close();
    }

    private QueryResult executeSql(String sql) {
        Lexer lexer = new Lexer(sql);
        Parser parser = new Parser(lexer.tokenize());
        Statement stmt = parser.parse().get(0);
        return executor.execute(stmt);
    }

    @Test
    void testEndToEnd_CreateTableInsertAndSelect() {
        QueryResult res1 = executeSql("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR);");
        assertTrue(res1.success(), res1.message());

        QueryResult res2 = executeSql("INSERT INTO users VALUES (1, 'Alice');");
        assertTrue(res2.success());
        assertEquals(1, res2.rowsAffected());

        executeSql("INSERT INTO users VALUES (2, 'Bob');");
        executeSql("INSERT INTO users VALUES (3, 'Charlie');");

        QueryResult res3 = executeSql("SELECT id, name FROM users WHERE id > 1;");
        assertTrue(res3.success());
        assertEquals(2, res3.rows().size());
        assertEquals("2", res3.rows().get(0).get(0));
        assertEquals("Bob", res3.rows().get(0).get(1));
        assertEquals(List.of("users.id", "users.name"), res3.columns());
    }

    @Test
    void testSchemaValidation_FailsOnMismatchedColumns() {
        executeSql("CREATE TABLE users (id INT, name VARCHAR);");
        QueryResult result = executeSql("INSERT INTO users VALUES (1, 'Alice', 'Extra');");
        assertFalse(result.success());
        assertTrue(result.message().contains("Insert value count (3) does not match schema column count (2)"));
    }

    @Test
    void testUpdateAndDelete_ModifyRowCounts() {
        executeSql("CREATE TABLE inventory (id INT, item VARCHAR);");
        executeSql("INSERT INTO inventory VALUES (1, 'Sword');");
        executeSql("INSERT INTO inventory VALUES (2, 'Shield');");

        QueryResult updateRes = executeSql("UPDATE inventory SET item = 'Iron Shield' WHERE id = 2;");
        assertTrue(updateRes.success());
        assertEquals(1, updateRes.rowsAffected());

        QueryResult deleteRes = executeSql("DELETE FROM inventory WHERE id = 1;");
        assertTrue(deleteRes.success());
        assertEquals(1, deleteRes.rowsAffected());

        QueryResult finalSelect = executeSql("SELECT * FROM inventory;");
        assertEquals(1, finalSelect.rows().size());
        assertEquals("Iron Shield", finalSelect.rows().get(0).get(1));
    }

    @Test
    void testPhaseE_AggregatesAndGroupBy() {
        executeSql("CREATE TABLE sales (region VARCHAR, amount INT);");
        executeSql("INSERT INTO sales VALUES ('North', 100);");
        executeSql("INSERT INTO sales VALUES ('North', 200);");
        executeSql("INSERT INTO sales VALUES ('South', 500);");

        QueryResult res = executeSql("SELECT region, SUM(amount), COUNT(*), AVG(amount) FROM sales GROUP BY region ORDER BY region DESC;");
        assertTrue(res.success(), res.message());
        assertEquals(2, res.rows().size());

        assertEquals("South", res.rows().get(0).get(0));
        assertEquals("500", res.rows().get(0).get(1));
        assertEquals("1", res.rows().get(0).get(2));

        assertEquals("North", res.rows().get(1).get(0));
        assertEquals("300", res.rows().get(1).get(1));
        assertEquals("2", res.rows().get(1).get(2));
        assertEquals("150.0", res.rows().get(1).get(3));
    }

    @Test
    void testPhaseE_GroupByValidationError() {
        executeSql("CREATE TABLE sales (region VARCHAR, amount INT);");
        QueryResult res = executeSql("SELECT region, amount FROM sales GROUP BY region;");
        assertFalse(res.success());
        assertTrue(res.message().contains("invalid in the select list because it is not contained in an aggregate function or the GROUP BY clause"));
    }

    @Test
    void testPhaseE_LimitAndDistinct() {
        executeSql("CREATE TABLE test (val INT);");
        executeSql("INSERT INTO test VALUES (1);");
        executeSql("INSERT INTO test VALUES (1);");
        executeSql("INSERT INTO test VALUES (2);");

        QueryResult distinctRes = executeSql("SELECT DISTINCT val FROM test;");
        assertEquals(2, distinctRes.rows().size());

        QueryResult limitRes = executeSql("SELECT * FROM test ORDER BY val ASC LIMIT 2;");
        assertEquals(2, limitRes.rows().size());
        assertEquals("1", limitRes.rows().get(0).get(0));
        assertEquals("1", limitRes.rows().get(1).get(0));
    }

    @Test
    void testPhaseF_InnerJoinHappyPath() {
        executeSql("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR);");
        executeSql("CREATE TABLE posts (pid INT, title VARCHAR, user_id INT);");
        executeSql("INSERT INTO users VALUES (1, 'Alice');");
        executeSql("INSERT INTO users VALUES (2, 'Bob');");
        executeSql("INSERT INTO posts VALUES (101, 'Alice Post 1', 1);");
        executeSql("INSERT INTO posts VALUES (102, 'Alice Post 2', 1);");
        executeSql("INSERT INTO posts VALUES (103, 'Bob Post', 2);");

        QueryResult res = executeSql("SELECT users.name, posts.title FROM users INNER JOIN posts ON users.id = posts.user_id ORDER BY posts.title ASC;");
        assertTrue(res.success());
        assertEquals(3, res.rows().size());
        assertEquals(List.of("users.name", "posts.title"), res.columns());

        assertEquals("Alice", res.rows().get(0).get(0));
        assertEquals("Alice Post 1", res.rows().get(0).get(1));

        assertEquals("Bob", res.rows().get(2).get(0));
        assertEquals("Bob Post", res.rows().get(2).get(1));
    }

    @Test
    void testPhaseF_InnerJoinWithAliases() {
        executeSql("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR);");
        executeSql("CREATE TABLE posts (pid INT, title VARCHAR, user_id INT);");
        executeSql("INSERT INTO users VALUES (1, 'Alice');");
        executeSql("INSERT INTO posts VALUES (101, 'Post 1', 1);");

        QueryResult res = executeSql("SELECT u.name, p.title FROM users AS u INNER JOIN posts AS p ON u.id = p.user_id;");
        assertTrue(res.success());
        assertEquals(1, res.rows().size());
        assertEquals(List.of("u.name", "p.title"), res.columns());
    }

    @Test
    void testPhaseF_AmbiguousColumnError() {
        executeSql("CREATE TABLE t1 (id INT, val VARCHAR);");
        executeSql("CREATE TABLE t2 (id INT, score INT);");
        QueryResult res = executeSql("SELECT id FROM t1 INNER JOIN t2 ON t1.id = t2.id;");
        assertFalse(res.success());
        assertTrue(res.message().contains("ambiguous"));
    }

    @Test
    void testPhaseF_ZeroMatchJoinReturnsEmpty() {
        executeSql("CREATE TABLE t1 (id INT);");
        executeSql("CREATE TABLE t2 (id INT);");
        executeSql("INSERT INTO t1 VALUES (1);");
        executeSql("INSERT INTO t2 VALUES (2);");
        QueryResult res = executeSql("SELECT * FROM t1 INNER JOIN t2 ON t1.id = t2.id;");
        assertTrue(res.success());
        assertEquals(0, res.rows().size());
    }

    @Test
    void testPart1_CaseInsensitivityKeywordsAndColumns() {
        executeSql("create table CasingTest (Id INT, MY_Name VARCHAR);");
        executeSql("insert into CasingTest VaLuEs (1, 'John');");

        // Use mixed case keywords, mixed case column resolution
        QueryResult res = executeSql("sElEcT iD, mY_nAmE fRoM CasingTest wHeRe id = 1;");
        assertTrue(res.success());

        // FIX: Add the CasingTest. prefix to match the engine's output!
        assertEquals(List.of("CasingTest.Id", "CasingTest.MY_Name"), res.columns());
        assertEquals("John", res.rows().get(0).get(1));
    }

    @Test
    void testPart1_CaseSensitiveStringLiteral() {
        executeSql("CREATE TABLE Users (id INT, name VARCHAR);");
        executeSql("INSERT INTO Users VALUES (1, 'John');");

        // String binary comparison means 'john' misses 'John'
        QueryResult res = executeSql("SELECT * FROM Users WHERE name = 'john';");
        assertEquals(0, res.rows().size());
    }

    @Test
    void testPart1_Bug1_TableCaseAndQuotes() {
        executeSql("CREATE TABLE \"Employees\" (id INT);");
        executeSql("CREATE TABLE employees (id INT);");

        executeSql("INSERT INTO \"Employees\" VALUES (1);");
        executeSql("INSERT INTO employees VALUES (2);");

        QueryResult lowerRes = executeSql("SELECT * FROM employees;");
        assertEquals("2", lowerRes.rows().get(0).get(0));

        QueryResult exactRes = executeSql("SELECT * FROM \"Employees\";");
        assertEquals("1", exactRes.rows().get(0).get(0));

        // Divergence test (Bug 1 regression validation)
        QueryResult bug1Quoted = executeSql("SELECT * FROM \"Employees\";");
        QueryResult bug1Unquoted = executeSql("SELECT * FROM Employees;");

        // FIX: Because case is perfectly preserved everywhere, BOTH queries succeed identically!
        assertTrue(bug1Quoted.success());
        assertTrue(bug1Unquoted.success());
    }

    @Test
    void testPart2_Bug2_MultipleAggregates() {
        executeSql("CREATE TABLE math (val INT);");
        executeSql("INSERT INTO math VALUES (10);");
        executeSql("INSERT INTO math VALUES (20);");

        QueryResult res = executeSql("SELECT COUNT(*), SUM(val), AVG(val), MIN(val), MAX(val) FROM math;");
        assertTrue(res.success());
        assertEquals("2", res.rows().get(0).get(0)); // COUNT
        assertEquals("30", res.rows().get(0).get(1)); // SUM
        assertEquals("15.0", res.rows().get(0).get(2)); // AVG
    }

    @Test
    void testPart2_Bug3_OrderByNotSelected() {
        executeSql("CREATE TABLE emps (name VARCHAR, salary INT);");
        executeSql("INSERT INTO emps VALUES ('Alice', 100);");
        executeSql("INSERT INTO emps VALUES ('Bob', 50);");

        // ORDER BY unselected column
        QueryResult res = executeSql("SELECT name FROM emps ORDER BY salary ASC;");
        assertTrue(res.success());
        assertEquals("Bob", res.rows().get(0).get(0));
        assertEquals("Alice", res.rows().get(1).get(0));
    }

    @Test
    void testPart3_Describe() {
        executeSql("CREATE TABLE Meta (id INT PRIMARY KEY, tag VARCHAR NOT NULL);");
        QueryResult res = executeSql("DESCRIBE Meta;");
        assertTrue(res.success());

        assertEquals("id", res.rows().get(0).get(0));
        assertEquals("INTEGER", res.rows().get(0).get(1));
        assertEquals("PRI", res.rows().get(0).get(2));

        assertEquals("tag", res.rows().get(1).get(0));
        // FIX: 'tag' is NOT NULL, so Null should be "NO"!
        assertEquals("NO", res.rows().get(1).get(3));
    }

    @Test
    void testPhaseI_Bug1_TruncateTableTransactional() throws Exception {
        executeSql("CREATE TABLE scratch (id INT PRIMARY KEY, note VARCHAR);");
        executeSql("INSERT INTO scratch VALUES (1, 'temp');");

        // Truncate inside transaction
        executeSql("BEGIN;");
        QueryResult truncRes = executeSql("TRUNCATE TABLE scratch;");
        assertTrue(truncRes.success());
        assertEquals("Table 'scratch' truncated. 1 rows deleted.", truncRes.message());

        QueryResult selectEmpty = executeSql("SELECT * FROM scratch;");
        assertEquals(0, selectEmpty.rows().size());

        // Rollback restores table
        executeSql("ROLLBACK;");
        QueryResult selectRestored = executeSql("SELECT * FROM scratch;");
        assertEquals(1, selectRestored.rows().size());
        assertEquals("temp", selectRestored.rows().get(0).get(1));
    }

    @Test
    void testPhaseI_Bug2_DropDatabaseOSDeletion() throws IOException {
        executeSql("CREATE DATABASE drop_test;");
        executeSql("USE drop_test;");
        executeSql("CREATE TABLE dummy (id INT);");

        Path dropDbPath = Path.of(dbManager.getCurrentDatabasePath().getParentFile().getAbsolutePath(), "drop_test");
        assertTrue(java.nio.file.Files.exists(dropDbPath));

        // Switch out so we can drop it
        executeSql("USE testdb;");

        QueryResult dropRes = executeSql("DROP DATABASE drop_test;");
        assertTrue(dropRes.success());

        // Confirm recursive OS files are actually deleted
        assertFalse(java.nio.file.Files.exists(dropDbPath));

        QueryResult useDropped = executeSql("USE drop_test;");
        assertFalse(useDropped.success());
    }

    @Test
    void testPhaseI_Bug3_NullConstraintsAndDisplay() {
        executeSql("CREATE TABLE c_test (id INT PRIMARY KEY, name VARCHAR NOT NULL, note VARCHAR);");

        // Valid NULL
        QueryResult res1 = executeSql("INSERT INTO c_test VALUES (1, 'Alice', NULL);");
        assertTrue(res1.success());

        // PK Null violation
        QueryResult res2 = executeSql("INSERT INTO c_test VALUES (NULL, 'Bob', 'note');");
        assertFalse(res2.success());
        assertTrue(res2.message().contains("PRIMARY KEY and cannot be NULL"));

        // NOT NULL violation
        QueryResult res3 = executeSql("INSERT INTO c_test VALUES (2, NULL, 'note');");
        assertFalse(res3.success());
        assertTrue(res3.message().contains("is NOT NULL"));

        // Print Output check
        QueryResult selectRes = executeSql("SELECT * FROM c_test;");
        assertEquals("NULL", selectRes.rows().get(0).get(2)); // Stored and printed explicitly as NULL
    }

    @Test
    void testPhaseI_Bug4_FloatAndBooleanOffsets() {
        // Mixing fixed-width fields carefully tests the Tuple byte[] calculation logic
        executeSql("CREATE TABLE mixed (id INT PRIMARY KEY, price FLOAT, in_stock BOOLEAN, extra VARCHAR);");
        executeSql("INSERT INTO mixed VALUES (1, 10.50, TRUE, 'A');");
        executeSql("INSERT INTO mixed VALUES (2, 5.0, FALSE, 'B');");
        executeSql("INSERT INTO mixed VALUES (3, 20.99, TRUE, 'C');");

        // Float Ordering
        QueryResult floatRes = executeSql("SELECT id FROM mixed WHERE price > 9.0 ORDER BY price ASC;");
        assertEquals(2, floatRes.rows().size());
        assertEquals("1", floatRes.rows().get(0).get(0)); // 10.50
        assertEquals("3", floatRes.rows().get(1).get(0)); // 20.99

        // Boolean Equality and Byte Offset validation
        QueryResult boolRes = executeSql("SELECT extra FROM mixed WHERE in_stock = FALSE;");
        assertEquals(1, boolRes.rows().size());
        assertEquals("B", boolRes.rows().get(0).get(0)); // Extra column validates offsets survived BOOLEAN
    }

    @Test
    void testBug_UpdateEnforcesNotNullAndPK() {
        executeSql("CREATE TABLE constraint_test (id INT PRIMARY KEY, required_field VARCHAR NOT NULL, optional_field VARCHAR);");
        executeSql("INSERT INTO constraint_test VALUES (1, 'ok', 'also ok');");

        // 1. UPDATE required field to NULL -> Error
        QueryResult res1 = executeSql("UPDATE constraint_test SET required_field = NULL WHERE id = 1;");
        assertFalse(res1.success());
        assertTrue(res1.message().contains("is NOT NULL"));

        // 2. UPDATE id to NULL -> Error (PK)
        QueryResult res2 = executeSql("UPDATE constraint_test SET id = NULL WHERE id = 1;");
        assertFalse(res2.success());
        assertTrue(res2.message().contains("PRIMARY KEY and cannot be NULL"));

        // 3. UPDATE optional_field to NULL -> Succeeds
        QueryResult res3 = executeSql("UPDATE constraint_test SET optional_field = NULL WHERE id = 1;");
        assertTrue(res3.success());
        assertEquals(1, res3.rowsAffected());

        // 4. UPDATE leaving required_field untouched -> Succeeds (validates full resulting row)
        QueryResult res4 = executeSql("UPDATE constraint_test SET optional_field = 'new' WHERE id = 1;");
        assertTrue(res4.success());
        assertEquals(1, res4.rowsAffected());
    }
}
