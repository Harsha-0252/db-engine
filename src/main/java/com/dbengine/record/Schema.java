package com.dbengine.record;

import java.util.List;

public class Schema {
    private final List<Column> columns;

    public Schema(List<Column> columns) {
        this.columns = columns;
    }

    public List<Column> getColumns() {
        return columns;
    }
}
