package com.dbengine.record;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TupleTest {

    @Test
    void testTupleSerializationRoundTrip() {
        // 1. Define a Schema for our table: (id: INTEGER, username: VARCHAR, age: INTEGER)
        Schema userSchema = new Schema(List.of(
                new Column("id", Type.INTEGER),
                new Column("username", Type.VARCHAR),
                new Column("age", Type.INTEGER)
        ));

        // 2. Create a Row (Tuple) matching that Schema
        Tuple originalTuple = new Tuple();
        originalTuple.addValue(new Value(101));
        originalTuple.addValue(new Value("db_admin"));
        originalTuple.addValue(new Value(35));

        // 3. Serialize to raw bytes (This is what gets sent to SlottedPageLayout!)
        byte[] rawBytes = originalTuple.serialize();

        // Assert that bytes were actually generated
        assertNotNull(rawBytes);
        assertTrue(rawBytes.length > 0);

        // 4. Deserialize back into a Tuple using the Schema
        Tuple restoredTuple = Tuple.deserialize(rawBytes, userSchema);

        // 5. Verify the data perfectly matches
        List<Value> restoredValues = restoredTuple.getValues();
        assertEquals(3, restoredValues.size());

        assertEquals(101, restoredValues.get(0).getAsInt());
        assertEquals("db_admin", restoredValues.get(1).getAsString());
        assertEquals(35, restoredValues.get(2).getAsInt());

        // Verify Value.equals() works
        assertEquals(originalTuple.getValues(), restoredTuple.getValues());
    }
}
