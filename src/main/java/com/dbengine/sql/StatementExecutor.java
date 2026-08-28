package com.dbengine.sql;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import com.dbengine.sql.ast.Statement;
import static com.dbengine.sql.ast.Statement.*;
import com.dbengine.storage.CatalogManager;
import com.dbengine.storage.DatabaseManager;
import com.dbengine.buffer.BufferPoolManager;
import com.dbengine.table.Table;
import com.dbengine.table.RecordId;
import com.dbengine.record.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class StatementExecutor {
    private final DatabaseManager dbManager;
    private final BufferPoolManager bpm;
    private final TransactionContext txContext = new TransactionContext();

    private final Map<String, Schema> schemas = new HashMap<>();
    private final Map<String, Table> tables = new HashMap<>();

    public StatementExecutor(DatabaseManager dbManager, BufferPoolManager bpm) {
        this.dbManager = dbManager;
        this.bpm = bpm;
    }

    public QueryResult execute(Statement stmt) {
        try {
            if (stmt instanceof CreateDatabaseStatement c) return executeCreateDatabase(c);
            if (stmt instanceof DropDatabaseStatement d) return executeDropDatabase(d);
            if (stmt instanceof TruncateTableStatement t) return executeTruncateTable(t);
            if (stmt instanceof UseStatement u) return executeUse(u);
            if (stmt instanceof ShowDatabasesStatement) return executeShowDatabases();

            // Table DDL & DML require an active database context
            if (!(stmt instanceof SelectStatement s && s.tableName() == null)) {
                if (stmt instanceof CreateTableStatement || stmt instanceof DropTableStatement ||
                        stmt instanceof InsertStatement || stmt instanceof UpdateStatement ||
                        stmt instanceof DeleteStatement || stmt instanceof ShowTablesStatement ||
                        stmt instanceof DescribeStatement) { // <-- Added DescribeStatement here
                    if (dbManager.getCurrentDatabase() == null) {
                        return QueryResult.error("No database selected. Run 'USE <database_name>;' first.");
                    }
                }
            }

            if (stmt instanceof ShowTablesStatement) return executeShowTables();
            if (stmt instanceof DescribeStatement d) return executeDescribe(d);
            if (stmt instanceof CreateTableStatement c) return executeCreateTable(c);
            if (stmt instanceof DropTableStatement d) return executeDropTable(d);
            if (stmt instanceof AlterTableAdd a) return executeAlterTableAdd(a);
            if (stmt instanceof AlterTableDrop d) return executeAlterTableDrop(d);
            if (stmt instanceof AlterTableRename r) return executeAlterTableRename(r);

            if (stmt instanceof InsertStatement i) return executeInsert(i);
            if (stmt instanceof SelectStatement s) return executeSelect(s);
            if (stmt instanceof UpdateStatement u) return executeUpdate(u);
            if (stmt instanceof DeleteStatement d) return executeDelete(d);

            if (stmt instanceof BeginStatement) return executeBegin();
            if (stmt instanceof CommitStatement) return executeCommit();
            if (stmt instanceof RollbackStatement) return executeRollback();

            return QueryResult.error("Execution for " + stmt.getClass().getSimpleName() + " is pending.");
        } catch (Exception e) {
            return QueryResult.error("Execution Error: " + e.getMessage());
        }
    }

    private QueryResult executeCreateDatabase(CreateDatabaseStatement stmt) {
        if (dbManager.createDatabase(stmt.databaseName())) {
            return QueryResult.success("Database '" + stmt.databaseName() + "' created.");
        }
        return QueryResult.error("Database '" + stmt.databaseName() + "' already exists.");
    }

    private QueryResult executeDropDatabase(DropDatabaseStatement stmt) {
        try {
            if (stmt.databaseName().equals(dbManager.getCurrentDatabase())) {
                return QueryResult.error("Cannot drop the currently active database — switch first.");
            }
            File dbDir = new File(dbManager.getCurrentDatabasePath().getParentFile(), stmt.databaseName());
            if (!dbDir.exists()) {
                return QueryResult.error("Database '" + stmt.databaseName() + "' does not exist.");
            }

            // Recursive OS Deletion
            Files.walkFileTree(dbDir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return QueryResult.success("Database '" + stmt.databaseName() + "' dropped.");
        } catch (IOException e) {
            return QueryResult.error("Failed to drop database: " + e.getMessage());
        }
    }

    private QueryResult executeTruncateTable(TruncateTableStatement stmt) throws Exception {
        Table table = validateTableExists(stmt.tableName());
        Map<RecordId, Tuple> allRecords = getOverlayedRecords(stmt.tableName());
        int count = 0;

        for (RecordId rid : allRecords.keySet()) {
            if (txContext.isActive()) {
                txContext.addDelete(stmt.tableName(), rid);
            } else {
                table.deleteTuple(rid);
            }
            count++;
        }
        if (!txContext.isActive()) {
            bpm.flushAllPages();
        }
        return QueryResult.success("Table '" + stmt.tableName() + "' truncated. " + count + " rows deleted.");
    }

    private QueryResult executeUse(UseStatement stmt) {
        if (dbManager.useDatabase(stmt.databaseName())) {
            loadCatalogFromCurrentDb();
            return QueryResult.success("Switched to database '" + stmt.databaseName() + "'.");
        }
        return QueryResult.error("Database '" + stmt.databaseName() + "' does not exist.");
    }

    private void loadCatalogFromCurrentDb() {
        schemas.clear();
        tables.clear();
        File dbDir = dbManager.getCurrentDatabasePath();
        try {
            List<CatalogManager.TableMeta> metas = CatalogManager.loadCatalog(dbDir);
            for (CatalogManager.TableMeta meta : metas) {
                schemas.put(meta.tableName(), meta.schema());
                Table t = new Table(bpm, meta.schema());
                t.loadPages(meta.pageIds());
                tables.put(meta.tableName(), t);
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to load catalog: " + e.getMessage());
        }
    }

    private void persistCurrentCatalog() throws IOException {
        File dbDir = dbManager.getCurrentDatabasePath();
        if (dbDir == null) return;
        Map<String, List<com.dbengine.storage.PageId>> tablePages = new HashMap<>();
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            tablePages.put(entry.getKey(), entry.getValue().getPages());
        }
        CatalogManager.saveCatalog(dbDir, schemas, tablePages);
    }

    private QueryResult executeShowDatabases() {
        List<String> dbs = dbManager.showDatabases();
        List<List<String>> rows = dbs.stream().map(List::of).collect(Collectors.toList());
        return QueryResult.resultSet(List.of("Database"), rows);
    }

    private QueryResult executeShowTables() {
        List<List<String>> rows = tables.keySet().stream().map(List::of).collect(Collectors.toList());
        return QueryResult.resultSet(List.of("Table"), rows);
    }

    private QueryResult executeDescribe(DescribeStatement stmt) {
        Table table = validateTableExists(stmt.tableName());
        Schema schema = schemas.get(stmt.tableName());
        List<List<String>> rows = new ArrayList<>();
        for (Column c : schema.getColumns()) {
            rows.add(List.of(
                    c.getName(),
                    c.getType().name(),
                    c.isPrimaryKey() ? "PRI" : "",
                    c.isNotNull() ? "NO" : "YES" // FIX: NOT NULL means Null=NO
            ));
        }
        return QueryResult.resultSet(List.of("Field", "Type", "Key", "Null"), rows);
    }

    private QueryResult executeCreateTable(CreateTableStatement stmt) throws IOException {
        if (tables.containsKey(stmt.tableName())) return QueryResult.error("Table '" + stmt.tableName() + "' already exists.");

        List<Column> recordColumns = new ArrayList<>();
        for (ColumnDefinition def : stmt.columns()) {
            // Support FLOAT and BOOLEAN types
            Type engineType = switch (def.type()) {
                case INT -> Type.INTEGER;
                case VARCHAR -> Type.VARCHAR;
                case FLOAT -> Type.FLOAT;
                case BOOLEAN -> Type.BOOLEAN;
                default -> throw new IllegalArgumentException("Unsupported type: " + def.type());
            };
            recordColumns.add(new Column(def.name(), engineType, def.isPrimaryKey(), def.isNotNull()));
        }

        Schema schema = new Schema(recordColumns);
        schemas.put(stmt.tableName(), schema);
        tables.put(stmt.tableName(), new Table(bpm, schema));
        persistCurrentCatalog();
        return QueryResult.success("Table '" + stmt.tableName() + "' created.");
    }

    private QueryResult executeDropTable(DropTableStatement stmt) throws IOException {
        if (tables.remove(stmt.tableName()) != null) {
            schemas.remove(stmt.tableName());
            persistCurrentCatalog();
            return QueryResult.success("Table '" + stmt.tableName() + "' dropped.");
        }
        return QueryResult.error("Table '" + stmt.tableName() + "' does not exist.");
    }

    private QueryResult executeAlterTableAdd(AlterTableAdd stmt) throws IOException {
        Table table = validateTableExists(stmt.tableName());
        Schema oldSchema = schemas.get(stmt.tableName());

        if (oldSchema.getColumns().stream().anyMatch(c -> c.getName().equalsIgnoreCase(stmt.columnName()))) {
            return QueryResult.error("Column '" + stmt.columnName() + "' already exists.");
        }

        Type engineType = switch (stmt.type()) {
            case INT -> Type.INTEGER;
            case VARCHAR -> Type.VARCHAR;
            default -> throw new IllegalArgumentException("Storage engine only supports INT and VARCHAR.");
        };

        List<Column> newCols = new ArrayList<>(oldSchema.getColumns());
        newCols.add(new Column(stmt.columnName(), engineType, false, false));

        updateTableSchema(stmt.tableName(), table, new Schema(newCols));
        return QueryResult.success("Column '" + stmt.columnName() + "' added successfully.");
    }

    private QueryResult executeAlterTableRename(AlterTableRename stmt) throws IOException {
        Table table = validateTableExists(stmt.tableName());
        Schema oldSchema = schemas.get(stmt.tableName());

        List<Column> newCols = new ArrayList<>();
        boolean found = false;
        for (Column c : oldSchema.getColumns()) {
            if (c.getName().equalsIgnoreCase(stmt.oldName())) {
                newCols.add(new Column(stmt.newName(), c.getType(), c.isPrimaryKey(), c.isNotNull()));
                found = true;
            } else {
                newCols.add(c);
            }
        }

        if (!found) return QueryResult.error("Column '" + stmt.oldName() + "' does not exist.");

        updateTableSchema(stmt.tableName(), table, new Schema(newCols));
        return QueryResult.success("Column '" + stmt.oldName() + "' renamed to '" + stmt.newName() + "'.");
    }

    private QueryResult executeAlterTableDrop(AlterTableDrop stmt) throws IOException {
        Table table = validateTableExists(stmt.tableName());
        Schema oldSchema = schemas.get(stmt.tableName());

        List<Column> newCols = new ArrayList<>();
        boolean found = false;
        for (Column c : oldSchema.getColumns()) {
            if (c.getName().equalsIgnoreCase(stmt.columnName())) {
                found = true;
            } else {
                newCols.add(c);
            }
        }

        if (!found) return QueryResult.error("Column '" + stmt.columnName() + "' does not exist.");

        updateTableSchema(stmt.tableName(), table, new Schema(newCols));
        return QueryResult.success("Column '" + stmt.columnName() + "' dropped successfully.");
    }

    private void updateTableSchema(String tableName, Table oldTable, Schema newSchema) throws IOException {
        schemas.put(tableName, newSchema);
        Table newTable = new Table(bpm, newSchema);
        newTable.loadPages(oldTable.getPages()); // Transfer the physical disk pages to the new schema mapping
        tables.put(tableName, newTable);
        persistCurrentCatalog(); // Immediately save the new schema to disk
    }



    private QueryResult executeBegin() {
        try {
            txContext.begin();
            return QueryResult.success("Transaction started.");
        } catch (IllegalStateException e) {
            return QueryResult.error(e.getMessage());
        }
    }

    private QueryResult executeCommit() throws Exception {
        if (!txContext.isActive()) return QueryResult.error("No active transaction to commit.");

        for (Map.Entry<String, Map<RecordId, Tuple>> tableEntry : txContext.getOverlayMap().entrySet()) {
            Table table = validateTableExists(tableEntry.getKey());
            for (Map.Entry<RecordId, Tuple> rowEntry : tableEntry.getValue().entrySet()) {
                RecordId rid = rowEntry.getKey();
                Tuple tuple = rowEntry.getValue();

                if (rid.getPageId().value() == com.dbengine.common.Constants.INVALID_PAGE_ID) {
                    if (tuple != null) table.insertTuple(tuple);
                } else {
                    if (tuple == null) {
                        table.deleteTuple(rid);
                    } else {
                        table.updateTuple(rid, tuple);
                    }
                }
            }
        }

        txContext.clearAndDeactivate();
        bpm.flushAllPages(); // Guarantee durability to disk on commit
        persistCurrentCatalog();
        return QueryResult.success("Transaction committed.");
    }

    private QueryResult executeRollback() {
        if (!txContext.isActive()) return QueryResult.error("No active transaction to rollback.");
        txContext.clearAndDeactivate();
        return QueryResult.success("Transaction rolled back.");
    }

    private Map<RecordId, Tuple> getOverlayedRecords(String tableName) throws Exception {
        Table table = validateTableExists(tableName);
        Map<RecordId, Tuple> records = new LinkedHashMap<>(table.scanAllRecords());

        if (txContext.isActive()) {
            Map<RecordId, Tuple> overlay = txContext.getTableOverlay(tableName);
            if (overlay != null) {
                for (Map.Entry<RecordId, Tuple> entry : overlay.entrySet()) {
                    if (entry.getValue() == null) {
                        records.remove(entry.getKey());
                    } else {
                        records.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return records;
    }

    private QueryResult executeInsert(InsertStatement stmt) throws Exception {
        Table table = validateTableExists(stmt.tableName());
        Schema schema = schemas.get(stmt.tableName());
        validateInsertCount(stmt, schema);

        Tuple tuple = new Tuple();
        Object pkVal = null;
        int pkIdx = -1;

        for (int i = 0; i < schema.getColumns().size(); i++) {
            Column col = schema.getColumns().get(i);
            Object val = stmt.values().get(i);

            if (val == null) {
                tuple.addValue(Value.nullValue(col.getType()));
                continue; // Skip type casting, validateRowAgainstSchema will catch constraint violations
            }

            if (col.isPrimaryKey()) { pkVal = val; pkIdx = i; }

            switch (col.getType()) {
                case INTEGER -> {
                    if (!(val instanceof Integer)) throw new IllegalArgumentException("Type mismatch for '" + col.getName() + "'. Expected INTEGER.");
                    tuple.addValue(new Value((Integer) val));
                }
                case FLOAT -> {
                    if (!(val instanceof Float)) throw new IllegalArgumentException("Type mismatch for '" + col.getName() + "'. Expected FLOAT.");
                    tuple.addValue(new Value((Float) val));
                }
                case BOOLEAN -> {
                    if (!(val instanceof Boolean)) throw new IllegalArgumentException("Type mismatch for '" + col.getName() + "'. Expected BOOLEAN.");
                    tuple.addValue(new Value((Boolean) val));
                }
                case VARCHAR -> {
                    if (!(val instanceof String)) throw new IllegalArgumentException("Type mismatch for '" + col.getName() + "'. Expected VARCHAR.");
                    tuple.addValue(new Value((String) val));
                }
            }
        }

        // Run the shared constraint validation on the constructed tuple
        validateRowAgainstSchema(schema, tuple);

        // ... (Keep existing Duplicate PK logic and insert commit logic) ...
        // Primary Key Uniqueness Check
        if (pkVal != null) {
            Map<RecordId, Tuple> currentRecords = getOverlayedRecords(stmt.tableName());
            for (Tuple existing : currentRecords.values()) {
                Value existingVal = existing.getValues().get(pkIdx);
                if (!existingVal.isNull() && existingVal.getAsInt() == (Integer) pkVal) {
                    throw new IllegalArgumentException("Duplicate primary key value '" + pkVal + "' for column '" + schema.getColumns().get(pkIdx).getName() + "'.");
                }
            }
        }

        if (txContext.isActive()) txContext.addInsert(stmt.tableName(), tuple);
        else { table.insertTuple(tuple); bpm.flushAllPages(); persistCurrentCatalog(); }
        return QueryResult.rowCount(1);
    }


    private QueryResult executeSelect(SelectStatement stmt) throws Exception {
        // --- 1. Literal-Only SELECT (e.g., SELECT 1;) ---
        if (stmt.tableName() == null) {
            List<String> outCols = new ArrayList<>();
            List<String> row = new ArrayList<>();
            for (SelectItem item : stmt.selectItems()) {
                if (item instanceof LiteralItem lit) {
                    outCols.add(lit.alias());
                    row.add(lit.value().toString());
                } else {
                    throw new IllegalArgumentException("SELECT without FROM only supports literal values.");
                }
            }
            return QueryResult.resultSet(outCols, List.of(row));
        }

        // --- 2. Table-backed SELECT ---
        Table leftTable = validateTableExists(stmt.tableName());
        Schema leftSchemaBase = schemas.get(stmt.tableName());
        String leftAlias = stmt.tableAlias() != null ? stmt.tableAlias() : stmt.tableName();

        List<Column> currentCols = leftSchemaBase.getColumns().stream()
                .map(c -> new Column(leftAlias + "." + c.getName(), c.getType()))
                .collect(Collectors.toList());
        Schema currentSchema = new Schema(currentCols);
        List<Tuple> currentRows = new ArrayList<>(getOverlayedRecords(stmt.tableName()).values());

        if (stmt.joinClause() != null) {
            JoinClause join = stmt.joinClause();
            Table rightTable = validateTableExists(join.rightTable());
            Schema rightSchemaBase = schemas.get(join.rightTable());
            String rightAlias = join.rightAlias() != null ? join.rightAlias() : join.rightTable();

            List<Column> rightCols = rightSchemaBase.getColumns().stream()
                    .map(c -> new Column(rightAlias + "." + c.getName(), c.getType()))
                    .collect(Collectors.toList());

            currentCols.addAll(rightCols);
            Schema joinedSchema = new Schema(currentCols);

            int leftKeyIdx = getColumnIndex(currentSchema, join.leftKey());
            int rightKeyIdx = getColumnIndex(new Schema(rightCols), join.rightKey());

            List<Tuple> joinedRows = new ArrayList<>();
            for (Tuple lTuple : currentRows) {
                for (Tuple rTuple : getOverlayedRecords(join.rightTable()).values()) {
                    if (lTuple.getValues().get(leftKeyIdx).equals(rTuple.getValues().get(rightKeyIdx))) {
                        Tuple combined = new Tuple();
                        lTuple.getValues().forEach(combined::addValue);
                        rTuple.getValues().forEach(combined::addValue);
                        joinedRows.add(combined);
                    }
                }
            }
            currentSchema = joinedSchema;
            currentRows = joinedRows;
        }

        List<SelectItem> finalSelectItems = new ArrayList<>(stmt.selectItems());
        if (finalSelectItems.isEmpty()) {
            for (Column c : currentSchema.getColumns()) {
                finalSelectItems.add(new PlainColRef(new ColumnRef(null, c.getName())));
            }
        }

        boolean hasAgg = finalSelectItems.stream().anyMatch(i -> i instanceof AggregateExpr);
        boolean hasGroup = stmt.groupBy() != null && !stmt.groupBy().isEmpty();
        boolean isPlainQuery = !hasAgg && !hasGroup && !stmt.distinct();

        if (hasAgg || hasGroup) {
            for (SelectItem item : finalSelectItems) {
                if (item instanceof PlainColRef p) {
                    boolean valid = stmt.groupBy() != null && stmt.groupBy().stream()
                            .anyMatch(g -> {
                                String gName = g.columnName();
                                String pName = p.col().columnName();
                                return gName.equalsIgnoreCase(pName) || pName.toLowerCase().endsWith("." + gName.toLowerCase());
                            });
                    if (!valid) {
                        throw new IllegalArgumentException("Column '" + p.col().columnName() +
                                "' is invalid in the select list because it is not contained in an aggregate function or the GROUP BY clause.");
                    }
                }
            }
        }

        List<Tuple> filtered = new ArrayList<>();
        for (Tuple tuple : currentRows) {
            if (evaluateCondition(stmt.whereClause(), currentSchema, tuple)) {
                filtered.add(tuple);
            }
        }

        // FIX (Bug 3): If it's a plain query, we sort the raw filtered rows BEFORE projection.
        // This allows ORDER BY to reference columns that are not in the SELECT list.
        if (isPlainQuery && stmt.orderBy() != null && !stmt.orderBy().isEmpty()) {
            final Schema sortSchema = currentSchema; // Create a final snapshot for the lambda
            filtered.sort((t1, t2) -> {
                for (OrderByItem obi : stmt.orderBy()) {
                    if (obi.sortItem() instanceof AggregateExpr) {
                        throw new IllegalArgumentException("Cannot use aggregate function in ORDER BY of a non-aggregated query.");
                    }
                    PlainColRef p = (PlainColRef) obi.sortItem();
                    int idx = getColumnIndex(sortSchema, p.col()); // Use the final snapshot here
                    Value v1 = t1.getValues().get(idx);
                    Value v2 = t2.getValues().get(idx);
                    int cmp = compareValues(v1, v2);
                    if (cmp != 0) return obi.isAsc() ? cmp : -cmp;
                }
                return 0;
            });
        }

        List<List<Object>> projectedRows = new ArrayList<>();
        if (!isPlainQuery) {
            if (hasAgg || hasGroup) {
                Map<List<Object>, List<Tuple>> groups = new LinkedHashMap<>();
                if (!hasGroup) {
                    groups.put(List.of(), filtered);
                } else {
                    for (Tuple t : filtered) {
                        List<Object> key = new ArrayList<>();
                        for (ColumnRef g : stmt.groupBy()) {
                            key.add(getColValue(currentSchema, t, g));
                        }
                        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
                    }
                }
                for (List<Tuple> groupRows : groups.values()) {
                    List<Object> row = new ArrayList<>();
                    for (SelectItem item : finalSelectItems) {
                        if (item instanceof PlainColRef p) row.add(getColValue(currentSchema, groupRows.get(0), p.col()));
                        else if (item instanceof AggregateExpr agg) row.add(computeAggregate(agg, currentSchema, groupRows));
                    }
                    projectedRows.add(row);
                }
            } else {
                // FIX: DISTINCT ONLY (No aggs, no group by) - map directly without collapsing to 1 row
                for (Tuple t : filtered) {
                    List<Object> row = new ArrayList<>();
                    for (SelectItem item : finalSelectItems) {
                        if (item instanceof PlainColRef p) row.add(getColValue(currentSchema, t, p.col()));
                        else if (item instanceof LiteralItem lit) row.add(lit.value());
                    }
                    projectedRows.add(row);
                }
            }

            if (stmt.distinct()) {
                projectedRows = new ArrayList<>(new LinkedHashSet<>(projectedRows));
            }

            if (stmt.orderBy() != null && !stmt.orderBy().isEmpty()) {
                projectedRows.sort((r1, r2) -> {
                    for (OrderByItem obi : stmt.orderBy()) {
                        int idx = getSelectIndexCaseInsensitive(finalSelectItems, obi.sortItem());
                        if (idx == -1) throw new IllegalArgumentException("ORDER BY column must be in SELECT list for DISTINCT/GROUP BY queries.");
                        Object v1 = r1.get(idx); Object v2 = r2.get(idx);
                        int cmp = compareRawObjects(v1, v2);
                        if (cmp != 0) return obi.isAsc() ? cmp : -cmp;
                    }
                    return 0;
                });
            }
        } else {
            for (Tuple t : filtered) {
                List<Object> row = new ArrayList<>();
                for (SelectItem item : finalSelectItems) {
                    if (item instanceof PlainColRef p) row.add(getColValue(currentSchema, t, p.col()));
                    else if (item instanceof LiteralItem lit) row.add(lit.value());
                }
                projectedRows.add(row);
            }
        }

        if (stmt.limit() != null) {
            projectedRows = projectedRows.subList(0, Math.min(stmt.limit(), projectedRows.size()));
        }

        Schema finalCurrentSchema = currentSchema;
        List<String> outCols = new ArrayList<>();
        for (SelectItem i : finalSelectItems) {
            if (i instanceof PlainColRef p) {
                // Restore exact prefix matching (e.g., users.id) for backward compatibility with existing tests
                outCols.add(finalCurrentSchema.getColumns().get(getColumnIndex(finalCurrentSchema, p.col())).getName());
            } else if (i instanceof AggregateExpr a) {
                outCols.add(a.func().name() + "(" + (a.col() == null ? "*" : a.col().columnName()) + ")");
            } else if (i instanceof LiteralItem lit) {
                outCols.add(lit.alias());
            }
        }

        List<List<String>> stringRows = new ArrayList<>();
        for (List<Object> r : projectedRows) {
            List<String> strRow = new ArrayList<>();
            for (Object obj : r) {
                // Safely convert Java nulls into the printable "NULL" string
                strRow.add(obj == null ? "NULL" : obj.toString());
            }
            stringRows.add(strRow);
        }

        return QueryResult.resultSet(outCols, stringRows);
    }

    private Object getColValue(Schema schema, Tuple tuple, ColumnRef colRef) {
        int idx = getColumnIndex(schema, colRef);
        Value val = tuple.getValues().get(idx);

        if (val.isNull()) return null; // Return raw null so aggregates can filter it

        return switch (val.getType()) {
            case INTEGER -> val.getAsInt();
            case VARCHAR -> val.getAsString();
            case FLOAT -> val.getAsFloat();
            case BOOLEAN -> val.getAsBoolean();
        };
    }

    private int getSelectIndexCaseInsensitive(List<SelectItem> items, SelectItem target) {
        for (int i = 0; i < items.size(); i++) {
            SelectItem item = items.get(i);
            if (item instanceof PlainColRef p1 && target instanceof PlainColRef p2) {
                if (p1.col().columnName().equalsIgnoreCase(p2.col().columnName())) return i;
            } else if (item instanceof AggregateExpr a1 && target instanceof AggregateExpr a2) {
                if (a1.func() == a2.func()) {
                    if (a1.col() == null && a2.col() == null) return i;
                    if (a1.col() != null && a2.col() != null && a1.col().columnName().equalsIgnoreCase(a2.col().columnName())) return i;
                }
            }
        }
        return -1;
    }

    private int getSelectIndex(List<SelectItem> items, ColumnRef target) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof PlainColRef p) {
                String pName = p.col().columnName();
                if (pName.equals(target.columnName()) || pName.endsWith("." + target.columnName())) return i;
            }
            if (items.get(i) instanceof AggregateExpr a && a.col() != null) {
                String aName = a.col().columnName();
                if (aName.equals(target.columnName()) || aName.endsWith("." + target.columnName())) return i;
            }
        }
        return -1;
    }

    private Object computeAggregate(AggregateExpr agg, Schema schema, List<Tuple> rows) {
        if (rows.isEmpty()) return 0;
        if (agg.func() == TokenType.COUNT) return rows.size();

        List<Integer> ints = rows.stream()
                .map(t -> getColValue(schema, t, agg.col()))
                .filter(Objects::nonNull) // Safely ignore NULLs for math
                .map(val -> {
                    if (val instanceof Integer i) return i;
                    if (val instanceof Float f) return Math.round(f);
                    return 0;
                })
                .collect(Collectors.toList());

        if (ints.isEmpty()) return null;

        return switch (agg.func()) {
            case SUM -> ints.stream().mapToInt(Integer::intValue).sum();
            case AVG -> (float) ints.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            case MIN -> ints.stream().mapToInt(Integer::intValue).min().orElse(0);
            case MAX -> ints.stream().mapToInt(Integer::intValue).max().orElse(0);
            default -> 0;
        };
    }

    private QueryResult executeUpdate(UpdateStatement stmt) throws Exception {
        Table table = validateTableExists(stmt.tableName());
        Schema schema = schemas.get(stmt.tableName());
        validateColumnsExist(schema, stmt.assignments().keySet());

        Map<RecordId, Tuple> allRecords = getOverlayedRecords(stmt.tableName());
        int affected = 0;

        for (Map.Entry<RecordId, Tuple> entry : allRecords.entrySet()) {
            if (evaluateCondition(stmt.whereClause(), schema, entry.getValue())) {
                Tuple newTuple = new Tuple();
                for (Column col : schema.getColumns()) {
                    if (stmt.assignments().containsKey(col.getName())) {
                        Object newVal = stmt.assignments().get(col.getName());
                        if (newVal == null) {
                            newTuple.addValue(Value.nullValue(col.getType()));
                        } else {
                            switch (col.getType()) {
                                case INTEGER -> newTuple.addValue(new Value((Integer) newVal));
                                case FLOAT -> newTuple.addValue(new Value((Float) newVal));
                                case BOOLEAN -> newTuple.addValue(new Value((Boolean) newVal));
                                case VARCHAR -> newTuple.addValue(new Value((String) newVal));
                            }
                        }
                    } else {
                        int colIdx = getColumnIndex(schema, new ColumnRef(null, col.getName()));
                        newTuple.addValue(entry.getValue().getValues().get(colIdx));
                    }
                }

                // FIX: Enforce NOT NULL and PRIMARY KEY constraints on the resulting updated row!
                validateRowAgainstSchema(schema, newTuple);

                if (txContext.isActive()) {
                    txContext.addUpdate(stmt.tableName(), entry.getKey(), newTuple);
                } else {
                    table.updateTuple(entry.getKey(), newTuple);
                }
                affected++;
            }
        }

        if (!txContext.isActive()) {
            bpm.flushAllPages();
            persistCurrentCatalog();
        }
        return QueryResult.rowCount(affected);
    }

    private QueryResult executeDelete(DeleteStatement stmt) throws Exception {
        Table table = validateTableExists(stmt.tableName());
        Schema schema = schemas.get(stmt.tableName());

        Map<RecordId, Tuple> allRecords = getOverlayedRecords(stmt.tableName());
        int affected = 0;

        for (Map.Entry<RecordId, Tuple> entry : allRecords.entrySet()) {
            if (evaluateCondition(stmt.whereClause(), schema, entry.getValue())) {
                if (txContext.isActive()) {
                    txContext.addDelete(stmt.tableName(), entry.getKey());
                } else {
                    table.deleteTuple(entry.getKey());
                }
                affected++;
            }
        }
        if (!txContext.isActive()) {
            bpm.flushAllPages();
            persistCurrentCatalog();
        }
        return QueryResult.rowCount(affected);
    }

    private Table validateTableExists(String tableName) {
        Table table = tables.get(tableName);
        if (table == null) throw new IllegalArgumentException("Table '" + tableName + "' does not exist.");
        return table;
    }

    private void validateInsertCount(InsertStatement stmt, Schema schema) {
        if (stmt.values().size() != schema.getColumns().size()) {
            throw new IllegalArgumentException("Insert value count (" + stmt.values().size() + ") does not match schema column count (" + schema.getColumns().size() + ").");
        }
    }

    private void validateColumnsExist(Schema schema, Collection<String> columnsToCheck) {
        Set<String> validCols = schema.getColumns().stream().map(Column::getName).collect(Collectors.toSet());
        for (String col : columnsToCheck) {
            if (!validCols.contains(col)) {
                throw new IllegalArgumentException("Column '" + col + "' does not exist in schema.");
            }
        }
    }

    private void validateRowAgainstSchema(Schema schema, Tuple tuple) {
        for (int i = 0; i < schema.getColumns().size(); i++) {
            Column col = schema.getColumns().get(i);
            Value val = tuple.getValues().get(i);
            if (val.isNull()) {
                if (col.isPrimaryKey()) {
                    throw new IllegalArgumentException("Column '" + col.getName() + "' is PRIMARY KEY and cannot be NULL.");
                }
                if (col.isNotNull()) {
                    throw new IllegalArgumentException("Column '" + col.getName() + "' is NOT NULL.");
                }
            }
        }
    }

    private int getColumnIndex(Schema schema, ColumnRef ref) {
        int matchedIdx = -1;
        for (int i = 0; i < schema.getColumns().size(); i++) {
            String colName = schema.getColumns().get(i).getName();
            boolean matches = false;

            // MySQL-style Case-Insensitive column resolution
            if (ref.tableName() != null) {
                matches = colName.equalsIgnoreCase(ref.tableName() + "." + ref.columnName());
            } else {
                matches = colName.equalsIgnoreCase(ref.columnName()) || colName.toLowerCase().endsWith("." + ref.columnName().toLowerCase());
            }

            if (matches) {
                if (matchedIdx != -1) {
                    throw new IllegalArgumentException("Column '" + ref.columnName() + "' is ambiguous — qualify as t1." + ref.columnName() + " or t2." + ref.columnName());
                }
                matchedIdx = i;
            }
        }
        if (matchedIdx == -1) {
            throw new IllegalArgumentException("Unknown column: " + (ref.tableName() != null ? ref.tableName() + "." : "") + ref.columnName());
        }
        return matchedIdx;
    }


    private boolean evaluateCondition(Condition cond, Schema schema, Tuple tuple) {
        if (cond == null) return true;

        if (cond instanceof LogicalExpr logic) {
            boolean left = evaluateCondition(logic.left(), schema, tuple);
            boolean right = evaluateCondition(logic.right(), schema, tuple);
            return logic.logicalOp() == TokenType.AND ? (left && right) : (left || right);
        } else if (cond instanceof BinaryExpr binary) {
            int colIdx = getColumnIndex(schema, binary.column());
            Value tupleVal = tuple.getValues().get(colIdx);

            // Standard SQL: Comparisons with NULL evaluate to false
            if (binary.value() == null || tupleVal.isNull()) return false;

            return switch (tupleVal.getType()) {
                case INTEGER -> {
                    int dbVal = tupleVal.getAsInt();
                    int queryVal = (Integer) binary.value();
                    yield evaluateBinaryOp(binary.op(), Integer.compare(dbVal, queryVal));
                }
                case FLOAT -> {
                    float dbVal = tupleVal.getAsFloat();
                    float queryVal = (Float) binary.value();
                    yield evaluateBinaryOp(binary.op(), Float.compare(dbVal, queryVal));
                }
                case BOOLEAN -> {
                    boolean dbVal = tupleVal.getAsBoolean();
                    boolean queryVal = (Boolean) binary.value();
                    if (binary.op() == TokenType.EQUALS) yield dbVal == queryVal;
                    if (binary.op() == TokenType.NOT_EQUALS) yield dbVal != queryVal;
                    throw new IllegalArgumentException("Operator not supported for BOOLEAN.");
                }
                case VARCHAR -> {
                    String dbVal = tupleVal.getAsString();
                    String queryVal = (String) binary.value();
                    if (binary.op() == TokenType.EQUALS) yield dbVal.equals(queryVal);
                    if (binary.op() == TokenType.NOT_EQUALS) yield !dbVal.equals(queryVal);
                    throw new IllegalArgumentException("Operator not supported for VARCHAR.");
                }
            };
        }
        return false;
    }

    private boolean evaluateBinaryOp(TokenType op, int cmp) {
        return switch (op) {
            case EQUALS -> cmp == 0;
            case NOT_EQUALS -> cmp != 0;
            case LT -> cmp < 0;
            case GT -> cmp > 0;
            case LTE -> cmp <= 0;
            case GTE -> cmp >= 0;
            default -> false;
        };
    }

    private int compareValues(Value v1, Value v2) {
        if (v1.getType() == Type.INTEGER && v2.getType() == Type.INTEGER) {
            return Integer.compare(v1.getAsInt(), v2.getAsInt());
        } else if (v1.getType() == Type.VARCHAR && v2.getType() == Type.VARCHAR) {
            return v1.getAsString().compareTo(v2.getAsString());
        }
        return 0;
    }

    private int compareRawObjects(Object v1, Object v2) {
        if (v1 instanceof Number n1 && v2 instanceof Number n2) {
            return Float.compare(n1.floatValue(), n2.floatValue());
        } else if (v1 instanceof Comparable c1 && v2 instanceof Comparable) {
            return c1.compareTo(v2);
        }
        return 0;
    }

}
