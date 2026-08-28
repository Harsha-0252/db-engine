package com.dbengine.sql;

import com.dbengine.sql.ast.*;
import static com.dbengine.sql.ast.Statement.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Parser {
    private final List<Token> tokens;
    private int current = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public List<Statement> parse() {
        List<Statement> statements = new ArrayList<>();
        while (!isAtEnd()) {
            statements.add(parseStatement());
        }
        return statements;
    }

    private Statement parseStatement() {
        if (match(TokenType.CREATE)) {
            if (match(TokenType.DATABASE)) return parseCreateDatabase();
            if (match(TokenType.TABLE)) return parseCreateTable();
            throw error(peek(), "Expected DATABASE or TABLE after CREATE.");
        }
        if (match(TokenType.DROP)) {
            if (match(TokenType.DATABASE)) return parseDropDatabase();
            if (match(TokenType.TABLE)) return parseDropTable();
            throw error(peek(), "Expected DATABASE or TABLE after DROP.");
        }
        if (match(TokenType.USE)) return parseUse();
        if (match(TokenType.SHOW)) {
            if (match(TokenType.DATABASES)) return parseShowDatabases();
            if (match(TokenType.TABLES)) return parseShowTables();
            throw error(peek(), "Expected DATABASES or TABLES after SHOW.");
        }
        if (match(TokenType.DESCRIBE, TokenType.DESC)) {
            return parseDescribe();
        }
        if (match(TokenType.TRUNCATE)) {
            consume(TokenType.TABLE, "Expected TABLE after TRUNCATE.");
            return parseTruncateTable();
        }
        if (match(TokenType.ALTER)) {
            consume(TokenType.TABLE, "Expected TABLE after ALTER.");
            return parseAlterTable();
        }
        if (match(TokenType.INSERT)) return parseInsert();
        if (match(TokenType.SELECT)) return parseSelect();
        if (match(TokenType.UPDATE)) return parseUpdate();
        if (match(TokenType.DELETE)) return parseDelete();

        if (match(TokenType.BEGIN)) {
            match(TokenType.TRANSACTION);
            consume(TokenType.SEMICOLON, "Expected ';' after BEGIN.");
            return new BeginStatement();
        }
        if (match(TokenType.COMMIT)) {
            consume(TokenType.SEMICOLON, "Expected ';' after COMMIT.");
            return new CommitStatement();
        }
        if (match(TokenType.ROLLBACK)) {
            consume(TokenType.SEMICOLON, "Expected ';' after ROLLBACK.");
            return new RollbackStatement();
        }

        throw error(peek(), "Unrecognized statement type.");
    }

    private Statement parseCreateDatabase() {
        String dbName = consume(TokenType.IDENTIFIER, "Expected database name.").lexeme();
        consume(TokenType.SEMICOLON, "Expected ';' at the end of CREATE DATABASE statement.");
        return new CreateDatabaseStatement(dbName);
    }

    private Statement parseDropDatabase() {
        String dbName = consume(TokenType.IDENTIFIER, "Expected database name.").lexeme();
        consume(TokenType.SEMICOLON, "Expected ';' at the end of DROP DATABASE statement.");
        return new DropDatabaseStatement(dbName);
    }

    private Statement parseUse() {
        String dbName = consume(TokenType.IDENTIFIER, "Expected database name.").lexeme();
        consume(TokenType.SEMICOLON, "Expected ';' at the end of USE statement.");
        return new UseStatement(dbName);
    }

    private Statement parseShowDatabases() {
        consume(TokenType.SEMICOLON, "Expected ';' at the end of SHOW DATABASES statement.");
        return new ShowDatabasesStatement();
    }

    private Statement parseShowTables() {
        consume(TokenType.SEMICOLON, "Expected ';' at the end of SHOW TABLES statement.");
        return new ShowTablesStatement();
    }

    private Statement parseDescribe() {
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
        consume(TokenType.SEMICOLON, "Expected ';' at the end of DESCRIBE statement.");
        return new DescribeStatement(tableName);
    }

    private Statement parseCreateTable() {
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
        consume(TokenType.LPAREN, "Expected '(' before column definitions.");

        List<ColumnDefinition> columns = new ArrayList<>();
        do {
            String colName = consume(TokenType.IDENTIFIER, "Expected column name.").lexeme();
            if (!match(TokenType.INT, TokenType.VARCHAR, TokenType.BOOLEAN, TokenType.FLOAT)) {
                throw error(peek(), "Expected valid column type (INT, VARCHAR, BOOLEAN, FLOAT).");
            }
            TokenType colType = previous().type();

            if (colType == TokenType.VARCHAR && match(TokenType.LPAREN)) {
                consume(TokenType.INT_LITERAL, "Expected integer length for VARCHAR.");
                consume(TokenType.RPAREN, "Expected ')' after VARCHAR length.");
            }

            boolean isPrimaryKey = false;
            boolean isNotNull = false;

            if (match(TokenType.PRIMARY)) {
                consume(TokenType.KEY, "Expected KEY after PRIMARY.");
                isPrimaryKey = true;
            }
            if (match(TokenType.NOT)) {
                consume(TokenType.NULL, "Expected NULL after NOT.");
                isNotNull = true;
            }

            columns.add(new ColumnDefinition(colName, colType, isPrimaryKey, isNotNull));
        } while (match(TokenType.COMMA));

        consume(TokenType.RPAREN, "Expected ')' after column definitions.");
        consume(TokenType.SEMICOLON, "Expected ';' at the end of CREATE TABLE statement.");

        return new CreateTableStatement(tableName, columns);
    }

    private Statement parseDropTable() {
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
        consume(TokenType.SEMICOLON, "Expected ';' at the end of DROP TABLE statement.");
        return new DropTableStatement(tableName);
    }

    private Statement parseTruncateTable() {
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
        consume(TokenType.SEMICOLON, "Expected ';' at the end of TRUNCATE TABLE statement.");
        return new TruncateTableStatement(tableName);
    }

    private Statement parseAlterTable() {
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
        Statement stmt;
        if (match(TokenType.ADD)) {
            consume(TokenType.COLUMN, "Expected COLUMN after ADD.");
            String colName = consume(TokenType.IDENTIFIER, "Expected column name.").lexeme();
            if (!match(TokenType.INT, TokenType.VARCHAR, TokenType.BOOLEAN, TokenType.FLOAT)) {
                throw error(peek(), "Expected valid column type.");
            }
            stmt = new AlterTableAdd(tableName, colName, previous().type());
        } else if (match(TokenType.DROP)) {
            consume(TokenType.COLUMN, "Expected COLUMN after DROP.");
            String colName = consume(TokenType.IDENTIFIER, "Expected column name.").lexeme();
            stmt = new AlterTableDrop(tableName, colName);
        } else if (match(TokenType.RENAME)) {
            consume(TokenType.COLUMN, "Expected COLUMN after RENAME.");
            String oldName = consume(TokenType.IDENTIFIER, "Expected old column name.").lexeme();
            consume(TokenType.TO, "Expected TO after old column name.");
            String newName = consume(TokenType.IDENTIFIER, "Expected new column name.").lexeme();
            stmt = new AlterTableRename(tableName, oldName, newName);
        } else {
            throw error(peek(), "Expected ADD, DROP, or RENAME after ALTER TABLE <ident>.");
        }
        consume(TokenType.SEMICOLON, "Expected ';' at the end of ALTER TABLE statement.");
        return stmt;
    }

    private Statement parseInsert() {
        consume(TokenType.INTO, "Expected INTO after INSERT.");
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();

        List<String> columns = new ArrayList<>();
        if (match(TokenType.LPAREN)) {
            do { columns.add(consume(TokenType.IDENTIFIER, "Expected column name.").lexeme()); } while (match(TokenType.COMMA));
            consume(TokenType.RPAREN, "Expected ')' after column list.");
        }

        consume(TokenType.VALUES, "Expected VALUES clause.");
        consume(TokenType.LPAREN, "Expected '(' before values.");

        List<Object> values = new ArrayList<>();
        do {
            if (match(TokenType.NULL)) {
                values.add(null);
            } else if (!match(TokenType.INT_LITERAL, TokenType.STRING_LITERAL, TokenType.FLOAT_LITERAL, TokenType.BOOLEAN_LITERAL)) {
                throw error(peek(), "Expected literal value or NULL.");
            } else {
                values.add(previous().literalValue());
            }
        } while (match(TokenType.COMMA));

        consume(TokenType.RPAREN, "Expected ')' after value list.");
        consume(TokenType.SEMICOLON, "Expected ';' at the end of INSERT statement.");
        return new InsertStatement(tableName, columns, values);
    }

    private Statement parseSelect() {
        boolean distinct = match(TokenType.DISTINCT);

        List<SelectItem> items = new ArrayList<>();
        if (!match(TokenType.STAR)) {
            do {
                // FIX: Added NULL literal check right here!
                if (match(TokenType.NULL)) {
                    String alias = "NULL";
                    if (match(TokenType.AS)) alias = consume(TokenType.IDENTIFIER, "Expected alias after AS.").lexeme();
                    items.add(new LiteralItem(null, alias));
                } else if (match(TokenType.INT_LITERAL, TokenType.STRING_LITERAL, TokenType.FLOAT_LITERAL, TokenType.BOOLEAN_LITERAL)) {
                    Object val = previous().literalValue();
                    String alias = previous().lexeme();
                    if (match(TokenType.AS)) alias = consume(TokenType.IDENTIFIER, "Expected alias after AS.").lexeme();
                    items.add(new LiteralItem(val, alias));
                } else if (match(TokenType.COUNT, TokenType.SUM, TokenType.AVG, TokenType.MIN, TokenType.MAX)) {
                    TokenType func = previous().type();
                    consume(TokenType.LPAREN, "Expected '(' after aggregate function.");
                    ColumnRef col = null;
                    if (match(TokenType.STAR)) {
                        if (func != TokenType.COUNT) throw error(peek(), "Only COUNT can use '*'.");
                    } else {
                        col = parseColumnRef();
                    }
                    consume(TokenType.RPAREN, "Expected ')' after aggregate column.");
                    items.add(new AggregateExpr(func, col));
                } else {
                    items.add(new PlainColRef(parseColumnRef()));
                }

            } while (match(TokenType.COMMA)); // Fix Bug 2: loop reliably catches multiple aggregates
        }

        String tableName = null;
        String tableAlias = null;
        JoinClause join = null;

        // FROM is now correctly optional (enables SELECT 1;)
        if (match(TokenType.FROM)) {
            tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
            if (match(TokenType.AS)) tableAlias = consume(TokenType.IDENTIFIER, "Expected alias after AS.").lexeme();

            if (match(TokenType.INNER)) {
                consume(TokenType.JOIN, "Expected JOIN after INNER.");
                String rightTable = consume(TokenType.IDENTIFIER, "Expected right table name.").lexeme();
                String rightAlias = null;
                if (match(TokenType.AS)) rightAlias = consume(TokenType.IDENTIFIER, "Expected alias after AS.").lexeme();

                consume(TokenType.ON, "Expected ON after JOIN.");
                ColumnRef leftKey = parseColumnRef();
                consume(TokenType.EQUALS, "Expected '=' in JOIN condition.");
                ColumnRef rightKey = parseColumnRef();
                join = new JoinClause(rightTable, rightAlias, leftKey, rightKey);
            }
        }

        Condition where = match(TokenType.WHERE) ? parseCondition() : null;

        List<ColumnRef> groupBy = new ArrayList<>();
        if (match(TokenType.GROUP)) {
            consume(TokenType.BY, "Expected BY after GROUP.");
            do { groupBy.add(parseColumnRef()); } while (match(TokenType.COMMA));
        }

        List<OrderByItem> orderBy = new ArrayList<>();
        if (match(TokenType.ORDER)) {
            consume(TokenType.BY, "Expected BY after ORDER.");
            do {
                // FIX (Bug 3): ORDER BY grammar expanded to allow aggregates
                SelectItem sortItem;
                if (match(TokenType.COUNT, TokenType.SUM, TokenType.AVG, TokenType.MIN, TokenType.MAX)) {
                    TokenType func = previous().type();
                    consume(TokenType.LPAREN, "Expected '(' after aggregate function.");
                    ColumnRef col = match(TokenType.STAR) ? null : parseColumnRef();
                    consume(TokenType.RPAREN, "Expected ')' after aggregate column.");
                    sortItem = new AggregateExpr(func, col);
                } else {
                    sortItem = new PlainColRef(parseColumnRef());
                }

                boolean asc = true;
                if (match(TokenType.ASC)) asc = true;
                else if (match(TokenType.DESC)) asc = false;
                orderBy.add(new OrderByItem(sortItem, asc));
            } while (match(TokenType.COMMA));
        }

        Integer limit = null;
        if (match(TokenType.LIMIT)) {
            limit = (Integer) consume(TokenType.INT_LITERAL, "Expected integer after LIMIT.").literalValue();
        }

        consume(TokenType.SEMICOLON, "Expected ';' at the end of SELECT statement.");
        return new SelectStatement(distinct, items, tableName, tableAlias, join, where, groupBy, orderBy, limit);
    }

    private ColumnRef parseColumnRef() {
        String part1 = consume(TokenType.IDENTIFIER, "Expected column name.").lexeme();
        if (match(TokenType.DOT)) {
            String part2 = consume(TokenType.IDENTIFIER, "Expected column name after '.'.").lexeme();
            return new ColumnRef(part1, part2);
        }
        return new ColumnRef(null, part1);
    }

    private Condition parseSingleCondition() {
        ColumnRef column = parseColumnRef();
        if (!match(TokenType.EQUALS, TokenType.NOT_EQUALS, TokenType.LT, TokenType.GT, TokenType.LTE, TokenType.GTE)) {
            throw error(peek(), "Expected operator (=, !=, <, >, <=, >=).");
        }
        TokenType op = previous().type();

        if (match(TokenType.NULL)) {
            return new BinaryExpr(column, op, null);
        } else if (!match(TokenType.INT_LITERAL, TokenType.STRING_LITERAL, TokenType.FLOAT_LITERAL, TokenType.BOOLEAN_LITERAL)) {
            throw error(peek(), "Expected literal value or NULL in condition.");
        }
        return new BinaryExpr(column, op, previous().literalValue());
    }

    private Statement parseUpdate() {
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
        consume(TokenType.SET, "Expected SET after table name.");

        Map<String, Object> assignments = new LinkedHashMap<>();
        do {
            String colName = consume(TokenType.IDENTIFIER, "Expected column name.").lexeme();
            consume(TokenType.EQUALS, "Expected '=' after column name.");
            if (match(TokenType.NULL)) {
                assignments.put(colName, null);
            } else if (!match(TokenType.INT_LITERAL, TokenType.STRING_LITERAL, TokenType.FLOAT_LITERAL, TokenType.BOOLEAN_LITERAL)) {
                throw error(peek(), "Expected literal value or NULL.");
            } else {
                assignments.put(colName, previous().literalValue());
            }
        } while (match(TokenType.COMMA));

        Condition where = match(TokenType.WHERE) ? parseCondition() : null;
        consume(TokenType.SEMICOLON, "Expected ';' at the end of UPDATE statement.");
        return new UpdateStatement(tableName, assignments, where);
    }


    private Statement parseDelete() {
        consume(TokenType.FROM, "Expected FROM after DELETE.");
        String tableName = consume(TokenType.IDENTIFIER, "Expected table name.").lexeme();
        Condition where = match(TokenType.WHERE) ? parseCondition() : null;
        consume(TokenType.SEMICOLON, "Expected ';' at the end of DELETE statement.");
        return new DeleteStatement(tableName, where);
    }

    private Condition parseCondition() {
        Condition expr = parseSingleCondition();
        while (match(TokenType.AND, TokenType.OR)) {
            TokenType logicalOp = previous().type();
            Condition right = parseSingleCondition();
            expr = new LogicalExpr(expr, logicalOp, right);
        }
        return expr;
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private boolean check(TokenType type) { return isAtEnd() ? false : peek().type() == type; }
    private Token advance() { if (!isAtEnd()) current++; return previous(); }
    private boolean isAtEnd() { return peek().type() == TokenType.EOF; }
    private Token peek() { return tokens.get(current); }
    private Token previous() { return tokens.get(current - 1); }
    private ParseException error(Token token, String message) { return new ParseException(token, message); }
}
