package com.dbengine.record;
import java.util.Objects;

public class Value {
    private final Type type;
    private final Object data;
    private final boolean isNull;

    public Value(Type type, Object data, boolean isNull) {
        this.type = type;
        this.data = data;
        this.isNull = isNull;
    }

    public Value(Integer data) { this(Type.INTEGER, data, data == null); }
    public Value(String data)  { this(Type.VARCHAR, data, data == null); }
    public Value(Float data)   { this(Type.FLOAT, data, data == null); }
    public Value(Boolean data) { this(Type.BOOLEAN, data, data == null); }

    public static Value nullValue(Type type) {
        return new Value(type, null, true);
    }

    public Type getType() { return type; }
    public boolean isNull() { return isNull; }

    public Integer getAsInt()     { return isNull ? null : (Integer) data; }
    public String getAsString()   { return isNull ? null : (String) data; }
    public Float getAsFloat()     { return isNull ? null : (Float) data; }
    public Boolean getAsBoolean() { return isNull ? null : (Boolean) data; }

    @Override
    public int hashCode() {
        return Objects.hash(type, data, isNull);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Value v)) return false;
        if (isNull && v.isNull) return true;
        return type == v.type && Objects.equals(data, v.data);
    }

    @Override
    public String toString() {
        if (isNull) return "NULL";
        return data.toString();
    }
}
