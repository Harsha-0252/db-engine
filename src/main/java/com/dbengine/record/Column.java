package com.dbengine.record;

public class Column {
    private final String name;
    private final Type type;
    private final boolean isPrimaryKey;
    private final boolean isNotNull;

    public Column(String name, Type type) {
        this(name, type, false, false);
    }

    public Column(String name, Type type, boolean isPrimaryKey, boolean isNotNull) {
        this.name = name;
        this.type = type;
        this.isPrimaryKey = isPrimaryKey;
        this.isNotNull = isNotNull;
    }

    public String getName() { return name; }
    public Type getType() { return type; }
    public boolean isPrimaryKey() { return isPrimaryKey; }
    public boolean isNotNull() { return isNotNull; }
}
