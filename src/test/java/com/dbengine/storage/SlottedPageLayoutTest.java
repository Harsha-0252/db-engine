package com.dbengine.storage;

import com.dbengine.common.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SlottedPageLayoutTest {

    private ByteBuffer page;

    @BeforeEach
    void setUp() {
        page = ByteBuffer.allocate(Constants.PAGE_SIZE);
        SlottedPageLayout.initPage(page);
    }

    @Test
    void freshPageIsEmpty() {
        assertEquals(0, SlottedPageLayout.getSlotCount(page));
        assertEquals(Constants.PAGE_SIZE, SlottedPageLayout.getFreeSpacePointer(page));
    }

    @Test
    void insertAndReadSingleRecordRoundTrips() {
        byte[] record = "hello world".getBytes(StandardCharsets.UTF_8);
        int slotId = SlottedPageLayout.insertRecord(page, record);
        assertEquals(0, slotId);
        assertArrayEquals(record, SlottedPageLayout.getRecord(page, slotId));
    }

    @Test
    void deletedSlotReturnsNullAndDoesNotAffectOtherSlots() {
        int slotA = SlottedPageLayout.insertRecord(page, "A".getBytes(StandardCharsets.UTF_8));
        int slotB = SlottedPageLayout.insertRecord(page, "B".getBytes(StandardCharsets.UTF_8));

        SlottedPageLayout.deleteRecord(page, slotB);

        assertNull(SlottedPageLayout.getRecord(page, slotB));
        assertTrue(SlottedPageLayout.isTombstoned(page, slotB));
        assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), SlottedPageLayout.getRecord(page, slotA));
    }

    @Test
    void insertFailsExactlyWhenPageRunsOutOfSpace() {
        byte[] record = new byte[100];
        int inserted = 0;
        while (SlottedPageLayout.insertRecord(page, record) != -1) {
            inserted++;
        }
        assertTrue(inserted > 0);
        int remaining = SlottedPageLayout.getFreeSpace(page);
        assertTrue(remaining < record.length + SlottedPageLayout.SLOT_SIZE);
    }
}
