package com.dbengine.storage;

import com.dbengine.common.Constants;
import java.nio.ByteBuffer;

public final class Page {

    private final ByteBuffer buffer;
    private PageId pageId;
    private boolean dirty;
    private int pinCount;

    public Page() {
        this(PageId.INVALID);
    }

    public Page(PageId pageId) {
        this.pageId = pageId;
        this.buffer = ByteBuffer.allocate(Constants.PAGE_SIZE);
        this.dirty = false;
        this.pinCount = 0;
    }

    public ByteBuffer getBuffer() { return buffer; }
    public PageId getPageId() { return pageId; }
    public void setPageId(PageId pageId) { this.pageId = pageId; }

    public boolean isDirty() { return dirty; }
    public void markDirty() { this.dirty = true; }
    public void clearDirty() { this.dirty = false; }

    public int getPinCount() { return pinCount; }
    public void pin() { pinCount++; }
    public void unpin() { if (pinCount > 0) pinCount--; }

    /** Zeroes out the page's contents and resets the buffer position/limit. */
    public void reset() {
        buffer.clear();
        for (int i = 0; i < Constants.PAGE_SIZE; i++) {
            buffer.put(i, (byte) 0);
        }
        buffer.clear();
        dirty = false;
    }
}
