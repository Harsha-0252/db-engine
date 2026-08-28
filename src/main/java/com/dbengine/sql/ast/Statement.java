package com.dbengine.sql.ast;

import com.dbengine.sql.TokenType;
import java.util.List;
import java.util.Map;

public interface Statement {

    // --- References & Types ---
    record ColumnRef(String tableName, String columnName) {}
    sealed interface SelectItem permits PlainColRef, AggregateExpr, LiteralItem {}
    record PlainColRef(ColumnRef col) implements SelectItem {}
    record AggregateExpr(TokenType func, ColumnRef col) implements SelectItem {}
    record LiteralItem(Object value, String alias) implements SelectItem {}

    // FIX (Bug 3): OrderBy accepts full SelectItems to allow ordering by aggregate expressions
    record OrderByItem(SelectItem sortItem, boolean isAsc) {}

    // --- Database DDL ---
    record CreateDatabaseStatement(String databaseName) implements Statement {}
    record DropDatabaseStatement(String databaseName) implements Statement {}
    record UseStatement(String databaseName) implements Statement {}
    record ShowDatabasesStatement() implements Statement {}
    record ShowTablesStatement() implements Statement {}
    record DescribeStatement(String tableName) implements Statement {}

    // --- Table DDL ---
    record ColumnDefinition(String name, TokenType type, boolean isPrimaryKey, boolean isNotNull) {}
    record CreateTableStatement(String tableName, List<ColumnDefinition> columns) implements Statement {}
    record DropTableStatement(String tableName) implements Statement {}
    record TruncateTableStatement(String tableName) implements Statement {}

    sealed interface AlterTableStatement extends Statement permits AlterTableAdd, AlterTableDrop, AlterTableRename {}
    record AlterTableAdd(String tableName, String columnName, TokenType type) implements AlterTableStatement {}
    record AlterTableDrop(String tableName, String columnName) implements AlterTableStatement {}
    record AlterTableRename(String tableName, String oldName, String newName) implements AlterTableStatement {}

    // --- DML ---
    record InsertStatement(String tableName, List<String> columns, List<Object> values) implements Statement {}

    record JoinClause(String rightTable, String rightAlias, ColumnRef leftKey, ColumnRef rightKey) {}

    record SelectStatement(
            boolean distinct,
            List<SelectItem> selectItems,
            String tableName,
            String tableAlias,
            JoinClause joinClause,
            Condition whereClause,
            List<ColumnRef> groupBy,
            List<OrderByItem> orderBy,
            Integer limit
    ) implements Statement {}

    record UpdateStatement(String tableName, Map<String, Object> assignments, Condition whereClause) implements Statement {}
    record DeleteStatement(String tableName, Condition whereClause) implements Statement {}

    // --- Conditions (WHERE clauses) ---
    sealed interface Condition permits BinaryExpr, LogicalExpr {}
    record BinaryExpr(ColumnRef column, TokenType op, Object value) implements Condition {}
    record LogicalExpr(Condition left, TokenType logicalOp, Condition right) implements Condition {}

    // --- Transactions ---
    record BeginStatement() implements Statement {}
    record CommitStatement() implements Statement {}
    record RollbackStatement() implements Statement {}
}

