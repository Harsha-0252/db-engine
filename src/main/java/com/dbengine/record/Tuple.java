package com.dbengine.record;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Tuple {
    private final List<Value> values = new ArrayList<>();

    public void addValue(Value v) { values.add(v); }
    public List<Value> getValues() { return values; }

    /**
     * Converts this Tuple into a raw byte array for disk storage.
     */

    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        for (Value v : values) {
            // Null Bitmap simulation: prepend 1 byte flag indicating NULL status
            buffer.put((byte) (v.isNull() ? 1 : 0));
            if (!v.isNull()) {
                switch (v.getType()) {
                    case INTEGER -> buffer.putInt(v.getAsInt());
                    case FLOAT   -> buffer.putFloat(v.getAsFloat());
                    case BOOLEAN -> buffer.put((byte) (v.getAsBoolean() ? 1 : 0));
                    case VARCHAR -> {
                        byte[] bytes = v.getAsString().getBytes(StandardCharsets.UTF_8);
                        buffer.putInt(bytes.length);
                        buffer.put(bytes);
                    }
                }
            }
        }
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    /**
     * Reconstructs a Tuple from a raw byte array using the table's Schema.
     */

    public static Tuple deserialize(byte[] data, Schema schema) {
        Tuple tuple = new Tuple();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        for (Column col : schema.getColumns()) {
            boolean isNull = buffer.get() == 1;
            if (isNull) {
                tuple.addValue(Value.nullValue(col.getType()));
            } else {
                switch (col.getType()) {
                    case INTEGER -> tuple.addValue(new Value(buffer.getInt()));
                    case FLOAT   -> tuple.addValue(new Value(buffer.getFloat()));
                    case BOOLEAN -> tuple.addValue(new Value(buffer.get() == 1));
                    case VARCHAR -> {
                        int len = buffer.getInt();
                        byte[] bytes = new byte[len];
                        buffer.get(bytes);
                        tuple.addValue(new Value(new String(bytes, StandardCharsets.UTF_8)));
                    }
                }
            }
        }
        return tuple;
    }
}