package com.dbengine.table;

import com.dbengine.buffer.BufferPoolManager;
import com.dbengine.record.Schema;
import com.dbengine.record.Tuple;
import com.dbengine.storage.Page;
import com.dbengine.storage.PageId;
import com.dbengine.storage.SlottedPageLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a single database table. Manages allocating pages,
 * inserting tuples, and scanning them back out.
 */
public class Table {
    private final BufferPoolManager bpm;
    private final Schema schema;

    // Tracks which pages belong to this table.
    private final List<PageId> pages;

    public Table(BufferPoolManager bpm, Schema schema) {
        this.bpm = bpm;
        this.schema = schema;
        this.pages = new ArrayList<>();
    }

    /**
     * Serializes the tuple and inserts it into the first page with available space.
     * Allocates a new page if necessary.
     */
    public RecordId insertTuple(Tuple tuple) throws IOException {
        byte[] recordBytes = tuple.serialize();
        Page targetPage = null;

        // 1. Try to find a page that already has enough free space (checking the last page first)
        if (!pages.isEmpty()) {
            PageId lastPageId = pages.get(pages.size() - 1);
            targetPage = bpm.fetchPage(lastPageId);

            if (targetPage != null) {
                if (!SlottedPageLayout.canFit(targetPage.getBuffer(), recordBytes.length)) {
                    // Not enough space, release it back to the buffer pool
                    bpm.unpinPage(lastPageId, false);
                    targetPage = null;
                }
            }
        }

        // 2. If no existing page has space (or table is empty), allocate a fresh one
        if (targetPage == null) {
            targetPage = bpm.newPage();
            if (targetPage == null) {
                throw new IllegalStateException("Buffer pool is entirely full, cannot allocate page!");
            }
            // Brand new pages must be initialized with the slot directory headers
            SlottedPageLayout.initPage(targetPage.getBuffer());
            pages.add(targetPage.getPageId());
        }

        // 3. Actually insert the bytes into the page layout
        int slotId = SlottedPageLayout.insertRecord(targetPage.getBuffer(), recordBytes);
        RecordId rid = new RecordId(targetPage.getPageId(), slotId);

        // 4. Release the page back to the buffer pool, marking it as DIRTY
        // so the pool knows it MUST write it to disk eventually.
        bpm.unpinPage(targetPage.getPageId(), true);

        return rid;
    }

    public List<PageId> getPages() {
        return Collections.unmodifiableList(pages);
    }

    public void loadPages(List<PageId> existingPages) {
        this.pages.clear();
        this.pages.addAll(existingPages);
    }


    /**
     * Fetches a tuple directly using its exact physical location.
     */
    public Tuple getTuple(RecordId rid) throws IOException {
        Page page = bpm.fetchPage(rid.getPageId());
        if (page == null) {
            throw new IllegalStateException("Failed to fetch page from buffer pool!");
        }

        // Extract the bytes using our layout manager
        byte[] recordBytes = SlottedPageLayout.getRecord(page.getBuffer(), rid.getSlotId());

        // We only read it, so it is not dirty
        bpm.unpinPage(rid.getPageId(), false);

        if (recordBytes == null) {
            return null; // The record was deleted/tombstoned
        }

        // Rehydrate the bytes back into Java objects based on the table's schema
        return Tuple.deserialize(recordBytes, schema);
    }

    /**
     * Scans all pages in the table and retrieves every valid tuple.
     */
    public List<Tuple> scanAll() throws IOException {
        List<Tuple> results = new ArrayList<>();
        for (PageId pageId : pages) {
            Page page = bpm.fetchPage(pageId);
            if (page != null) {
                int slotCount = SlottedPageLayout.getSlotCount(page.getBuffer());
                for (int i = 0; i < slotCount; i++) {
                    byte[] recordBytes = SlottedPageLayout.getRecord(page.getBuffer(), i);
                    if (recordBytes != null) {
                        results.add(Tuple.deserialize(recordBytes, schema));
                    }
                }
                bpm.unpinPage(pageId, false);
            }
        }
        return results;
    }

    /**
     * Scans all pages and returns a map of RecordId to Tuple, required for WHERE clause filtering.
     */
    public java.util.Map<RecordId, Tuple> scanAllRecords() throws IOException {
        java.util.Map<RecordId, Tuple> results = new java.util.LinkedHashMap<>();
        for (PageId pageId : pages) {
            Page page = bpm.fetchPage(pageId);
            if (page != null) {
                int slotCount = SlottedPageLayout.getSlotCount(page.getBuffer());
                for (int i = 0; i < slotCount; i++) {
                    byte[] recordBytes = SlottedPageLayout.getRecord(page.getBuffer(), i);
                    if (recordBytes != null) {
                        results.put(new RecordId(pageId, i), Tuple.deserialize(recordBytes, schema));
                    }
                }
                bpm.unpinPage(pageId, false);
            }
        }
        return results;
    }

    /**
     * Tombstones a record using the SlottedPageLayout manager.
     */
    public void deleteTuple(RecordId rid) throws IOException {
        Page page = bpm.fetchPage(rid.getPageId());
        if (page != null) {
            SlottedPageLayout.deleteRecord(page.getBuffer(), rid.getSlotId());
            bpm.unpinPage(rid.getPageId(), true); // Mark dirty so it writes to disk
        }
    }

    /**
     * Replaces a tuple in-place if it fits, otherwise tombstones it and inserts a new one.
     */
    public RecordId updateTuple(RecordId rid, Tuple newTuple) throws IOException {
        deleteTuple(rid);
        return insertTuple(newTuple);
    }

}
