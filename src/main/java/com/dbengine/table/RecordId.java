package com.dbengine.table;

import com.dbengine.storage.PageId;
import java.util.Objects;

public class RecordId {
    private final PageId pageId;
    private final int slotNum;

    public RecordId(PageId pageId, int slotNum) {
        this.pageId = pageId;
        this.slotNum = slotNum;
    }

    public PageId getPageId() { return pageId; }

    // FIX: Restored the original method name your codebase expects!
    public int getSlotId() { return slotNum; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecordId recordId = (RecordId) o;
        // Compare the raw long value of PageId to be completely safe
        return slotNum == recordId.slotNum && pageId.value() == recordId.pageId.value();
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId.value(), slotNum);
    }
}
