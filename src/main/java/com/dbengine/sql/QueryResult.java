package com.dbengine.sql;

import java.util.List;

public record QueryResult(
        boolean success,
        String message,
        List<String> columns,
        List<List<String>> rows,
        Integer rowsAffected
) {
    public static QueryResult success(String message) {
        return new QueryResult(true, message, null, null, null);
    }

    public static QueryResult error(String message) {
        return new QueryResult(false, message, null, null, null);
    }

    public static QueryResult rowCount(int count) {
        return new QueryResult(true, count + " row(s) affected.", null, null, count);
    }

    public static QueryResult resultSet(List<String> columns, List<List<String>> rows) {
        return new QueryResult(true, rows.size() + " row(s) returned.", columns, rows, null);
    }
}
