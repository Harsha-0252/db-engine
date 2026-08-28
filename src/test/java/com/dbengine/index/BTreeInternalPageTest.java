package com.dbengine.index;

import com.dbengine.storage.PageId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class BTreeInternalPageTest {

    @Test
    void testInternalNodeRouting() {
        ByteBuffer buffer = ByteBuffer.allocate(4096);
        BTreeInternalPage internalPage = new BTreeInternalPage(buffer);
        internalPage.init(100);

        // Setup the initial state:
        // A single leftmost child pointer (Page 2) at index 0.
        // We artificially increase the size to 1 to account for the leftmost pointer.
        internalPage.setPageIdAt(0, new PageId(2));
        internalPage.increaseSize(1);

        // Insert some routing keys
        // "Keys >= 50 go to Page 3"
        internalPage.insert(50, new PageId(3));
        // "Keys >= 100 go to Page 4"
        internalPage.insert(100, new PageId(4));

        assertEquals(3, internalPage.getCurrentSize());

        // Now test the routing logic (lookup)

        // Key 10 is < 50. Should go to leftmost child (Page 2)
        assertEquals(2, internalPage.lookup(10).value());

        // Key 50 is == 50. Should go to middle child (Page 3)
        assertEquals(3, internalPage.lookup(50).value());

        // Key 75 is between 50 and 100. Should go to middle child (Page 3)
        assertEquals(3, internalPage.lookup(75).value());

        // Key 100 is == 100. Should go to right child (Page 4)
        assertEquals(4, internalPage.lookup(100).value());

        // Key 500 is > 100. Should go to right child (Page 4)
        assertEquals(4, internalPage.lookup(500).value());
    }
}
