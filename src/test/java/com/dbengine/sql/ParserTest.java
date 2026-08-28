package com.dbengine.sql;

import com.dbengine.sql.ast.*;
import static com.dbengine.sql.ast.Statement.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private Statement parseSingle(String sql) {
        Lexer lexer = new Lexer(sql);
        Parser parser = new Parser(lexer.tokenize());
        return parser.parse().get(0);
    }

    @Test
    void testParseCreateDatabase_HappyPath() {
        Statement stmt = parseSingle("CREATE DATABASE production;");
        assertTrue(stmt instanceof CreateDatabaseStatement);
        assertEquals("production", ((CreateDatabaseStatement) stmt).databaseName());
    }

    @Test
    void testParseCreateTable_HappyPath() {
        Statement stmt = parseSingle("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255) NOT NULL, active BOOLEAN);");
        assertTrue(stmt instanceof CreateTableStatement);

        CreateTableStatement create = (CreateTableStatement) stmt;
        assertEquals("users", create.tableName());
        assertEquals(3, create.columns().size());

        assertEquals("id", create.columns().get(0).name());
        assertTrue(create.columns().get(0).isPrimaryKey());

        assertEquals("active", create.columns().get(2).name());
        assertEquals(TokenType.BOOLEAN, create.columns().get(2).type());
    }

    @Test
    void testParseInsert_HappyPath() {
        Statement stmt = parseSingle("INSERT INTO logs (level, message) VALUES ('ERROR', 'Crash');");
        assertTrue(stmt instanceof InsertStatement);

        InsertStatement insert = (InsertStatement) stmt;
        assertEquals("logs", insert.tableName());
        assertEquals(List.of("level", "message"), insert.columns());
        assertEquals(List.of("ERROR", "Crash"), insert.values());
    }

    @Test
    void testParseSelect_HappyPath_WithCondition() {
        Statement stmt = parseSingle("SELECT id, name FROM users WHERE age >= 18 AND active = TRUE;");
        assertTrue(stmt instanceof SelectStatement);

        SelectStatement select = (SelectStatement) stmt;
        assertEquals("users", select.tableName());

        // Phase E Update: Assert against the new selectItems structure
        assertEquals(2, select.selectItems().size());
        assertEquals("id", ((PlainColRef) select.selectItems().get(0)).col().columnName());
        assertEquals("name", ((PlainColRef) select.selectItems().get(1)).col().columnName());

        assertTrue(select.whereClause() instanceof LogicalExpr);
        LogicalExpr logic = (LogicalExpr) select.whereClause();
        assertEquals(TokenType.AND, logic.logicalOp());

        BinaryExpr left = (BinaryExpr) logic.left();
        // Phase E Update: Column is now a ColumnRef instead of a String
        assertEquals("age", left.column().columnName());
        assertEquals(TokenType.GTE, left.op());
        assertEquals(18, left.value());
    }

    @Test
    void testParse_FailsOnMissingSemicolon() {
        ParseException ex = assertThrows(ParseException.class, () -> parseSingle("DROP DATABASE dev"));
        assertTrue(ex.getMessage().contains("Expected ';'"));
        assertTrue(ex.getMessage().contains("end of input"));
    }

    @Test
    void testParse_FailsOnMissingClosingParen() {
        ParseException ex = assertThrows(ParseException.class, () -> parseSingle("INSERT INTO tab VALUES (1, 2;"));
        assertTrue(ex.getMessage().contains("Expected ')'"));
    }

    @Test
    void testParse_FailsOnMalformedWhereClause() {
        ParseException ex = assertThrows(ParseException.class, () -> parseSingle("DELETE FROM users WHERE id AND 5;"));
        assertTrue(ex.getMessage().contains("Expected operator"));
    }
}
