package com.dbengine.buffer;

import com.dbengine.storage.DiskManager;
import com.dbengine.storage.Page;
import com.dbengine.storage.PageId;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

public class BufferPoolManager {

    private final int poolSize;
    private final DiskManager diskManager;
    private final Page[] pages;
    private final LRUReplacer replacer;

    // Maps a logical PageId to a physical frame index in the 'pages' array
    private final Map<PageId, Integer> pageTable;
    // Tracks completely empty frames that have never been used
    private final Queue<Integer> freeList;

    public BufferPoolManager(int poolSize, DiskManager diskManager) {
        this.poolSize = poolSize;
        this.diskManager = diskManager;
        this.pages = new Page[poolSize];
        this.replacer = new LRUReplacer(poolSize);
        this.pageTable = new HashMap<>();
        this.freeList = new LinkedList<>();

        for (int i = 0; i < poolSize; i++) {
            freeList.add(i);
            pages[i] = new Page();
        }
    }

    /**
     * Fetches the requested page. If it's not in memory, brings it in from disk.
     */
    public synchronized Page fetchPage(PageId pageId) throws IOException {
        // 1. If it's already in the buffer pool, return it
        if (pageTable.containsKey(pageId)) {
            int frameId = pageTable.get(pageId);
            pages[frameId].pin();
            replacer.pin(frameId);
            return pages[frameId];
        }

        // 2. Otherwise, we need to bring it into memory. Find a frame.
        int frameId = getAvailableFrame();
        if (frameId == -1) {
            return null; // Buffer pool is full and every single page is pinned
        }

        // 3. Read from disk into the allocated frame
        Page page = pages[frameId];
        diskManager.readPage(pageId, page);

        // 4. Update metadata
        pageTable.put(pageId, frameId);
        page.pin();
        replacer.pin(frameId);

        return page;
    }

    /**
     * Creates a brand new page on disk and brings it into the buffer pool.
     */
    public synchronized Page newPage() throws IOException {
        int frameId = getAvailableFrame();
        if (frameId == -1) {
            return null; // Pool is entirely pinned
        }

        PageId newPageId = diskManager.allocatePage();
        Page page = pages[frameId];
        page.reset();
        page.setPageId(newPageId);

        pageTable.put(newPageId, frameId);
        page.pin();
        replacer.pin(frameId);

        return page;
    }

    /**
     * Must be called when a caller is done with a page.
     */
    public synchronized void unpinPage(PageId pageId, boolean isDirty) {
        if (!pageTable.containsKey(pageId)) return;

        int frameId = pageTable.get(pageId);
        Page page = pages[frameId];

        if (isDirty) {
            page.markDirty();
        }

        page.unpin();
        if (page.getPinCount() <= 0) {
            replacer.unpin(frameId);
        }
    }

    /**
     * Forces a page to be written to disk if it is dirty.
     */
    public synchronized void flushPage(PageId pageId) throws IOException {
        if (!pageTable.containsKey(pageId)) return;

        int frameId = pageTable.get(pageId);
        Page page = pages[frameId];

        if (page.isDirty()) {
            diskManager.writePage(page.getPageId(), page);
        }
    }


    /**
     * Flushes all dirty pages currently in the buffer pool to disk.
     */
    public synchronized void flushAllPages() throws IOException {
        for (Map.Entry<PageId, Integer> entry : pageTable.entrySet()) {
            Page page = pages[entry.getValue()];
            if (page.isDirty()) {
                diskManager.writePage(page.getPageId(), page);
                page.clearDirty();
            }
        }
    }


    /**
     * Finds a free frame, handling dirty evictions if necessary.
     */
    private int getAvailableFrame() throws IOException {
        if (!freeList.isEmpty()) {
            return freeList.poll();
        }

        // If no free frames, we must evict something using LRU
        Integer victimFrame = replacer.evict().orElse(null);
        if (victimFrame != null) {
            Page victimPage = pages[victimFrame];
            if (victimPage.isDirty()) {
                diskManager.writePage(victimPage.getPageId(), victimPage);
            }
            pageTable.remove(victimPage.getPageId());
            return victimFrame;
        }

        return -1; // All pages are currently pinned (being used)
    }
}
