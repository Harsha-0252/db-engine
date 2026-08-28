package com.dbengine.storage;

import com.dbengine.common.Constants;
import java.nio.ByteBuffer;

public final class SlottedPageLayout {

    public static final int HEADER_SIZE = 8;
    public static final int SLOT_SIZE = 4; // 2 bytes offset + 2 bytes length
    public static final short TOMBSTONE_LENGTH = -1;

    private static final int SLOT_COUNT_OFFSET = 0;
    private static final int FREE_SPACE_POINTER_OFFSET = 2;
    private static final int PAGE_TYPE_OFFSET = 4;
    private static final int RESERVED_OFFSET = 6;

    private SlottedPageLayout() { }

    public static void initPage(ByteBuffer page) {
        setSlotCount(page, 0);
        setFreeSpacePointer(page, Constants.PAGE_SIZE);
        page.putShort(PAGE_TYPE_OFFSET, (short) 0);
        page.putShort(RESERVED_OFFSET, (short) 0);
    }

    public static int getSlotCount(ByteBuffer page) {
        return page.getShort(SLOT_COUNT_OFFSET);
    }

    private static void setSlotCount(ByteBuffer page, int count) {
        page.putShort(SLOT_COUNT_OFFSET, (short) count);
    }

    public static int getFreeSpacePointer(ByteBuffer page) {
        return page.getShort(FREE_SPACE_POINTER_OFFSET);
    }

    private static void setFreeSpacePointer(ByteBuffer page, int offset) {
        page.putShort(FREE_SPACE_POINTER_OFFSET, (short) offset);
    }

    public static int getFreeSpace(ByteBuffer page) {
        int slotDirectoryEnd = HEADER_SIZE + getSlotCount(page) * SLOT_SIZE;
        return getFreeSpacePointer(page) - slotDirectoryEnd;
    }

    public static boolean canFit(ByteBuffer page, int recordLength) {
        return getFreeSpace(page) >= recordLength + SLOT_SIZE;
    }

    public static int insertRecord(ByteBuffer page, byte[] record) {
        if (record == null) throw new IllegalArgumentException("record must not be null");
        if (!canFit(page, record.length)) return -1;

        int slotCount = getSlotCount(page);
        int newTupleOffset = getFreeSpacePointer(page) - record.length;

        // Write tuple bytes
        for (int i = 0; i < record.length; i++) {
            page.put(newTupleOffset + i, record[i]);
        }

        // Write slot directory entry
        int slotEntryOffset = HEADER_SIZE + slotCount * SLOT_SIZE;
        page.putShort(slotEntryOffset, (short) newTupleOffset);
        page.putShort(slotEntryOffset + 2, (short) record.length);

        setFreeSpacePointer(page, newTupleOffset);
        setSlotCount(page, slotCount + 1);

        return slotCount;
    }

    public static byte[] getRecord(ByteBuffer page, int slotId) {
        validateSlotId(page, slotId);
        int slotEntryOffset = HEADER_SIZE + slotId * SLOT_SIZE;
        short length = page.getShort(slotEntryOffset + 2);
        if (length == TOMBSTONE_LENGTH) return null;

        int offset = page.getShort(slotEntryOffset);
        byte[] record = new byte[length];
        for (int i = 0; i < length; i++) {
            record[i] = page.get(offset + i);
        }
        return record;
    }

    public static boolean isTombstoned(ByteBuffer page, int slotId) {
        validateSlotId(page, slotId);
        int slotEntryOffset = HEADER_SIZE + slotId * SLOT_SIZE;
        return page.getShort(slotEntryOffset + 2) == TOMBSTONE_LENGTH;
    }

    public static void deleteRecord(ByteBuffer page, int slotId) {
        validateSlotId(page, slotId);
        int slotEntryOffset = HEADER_SIZE + slotId * SLOT_SIZE;
        page.putShort(slotEntryOffset + 2, TOMBSTONE_LENGTH);
    }

    private static void validateSlotId(ByteBuffer page, int slotId) {
        if (slotId < 0 || slotId >= getSlotCount(page)) {
            throw new IndexOutOfBoundsException("slotId out of range");
        }
    }
}
