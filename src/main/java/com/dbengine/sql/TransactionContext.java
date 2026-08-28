package com.dbengine.sql;

import com.dbengine.common.Constants;
import com.dbengine.record.Tuple;
import com.dbengine.storage.PageId;
import com.dbengine.table.RecordId;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class TransactionContext {
    private boolean active = false;
    private int fakeSlotCounter = -1;

    // TableName -> Map<RecordId, Tuple>
    // A null Tuple represents a pending DELETE.
    // A RecordId with PageId.INVALID represents a pending INSERT.
    private final Map<String, Map<RecordId, Tuple>> overlay = new HashMap<>();

    public boolean isActive() {
        return active;
    }

    public void begin() {
        if (active) throw new IllegalStateException("Transaction is already active.");
        active = true;
        overlay.clear();
        fakeSlotCounter = -1;
    }

    public void clearAndDeactivate() {
        active = false;
        overlay.clear();
    }

    public Map<String, Map<RecordId, Tuple>> getOverlayMap() {
        return overlay;
    }

    public Map<RecordId, Tuple> getTableOverlay(String tableName) {
        return overlay.get(tableName);
    }

    public void addInsert(String tableName, Tuple tuple) {
        overlay.computeIfAbsent(tableName, k -> new LinkedHashMap<>())
                .put(new RecordId(PageId.INVALID, fakeSlotCounter--), tuple);
    }

    public void addUpdate(String tableName, RecordId rid, Tuple newTuple) {
        overlay.computeIfAbsent(tableName, k -> new LinkedHashMap<>()).put(rid, newTuple);
    }

    public void addDelete(String tableName, RecordId rid) {
        overlay.computeIfAbsent(tableName, k -> new LinkedHashMap<>()).put(rid, null);
    }
}
