package com.dbengine.storage;

import com.dbengine.record.Column;
import com.dbengine.record.Schema;
import com.dbengine.record.Type;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

public class CatalogManager {
    public record TableMeta(String tableName, Schema schema, List<PageId> pageIds) {}

    public static void saveCatalog(File dbDir, Map<String, Schema> schemas, Map<String, List<PageId>> tablePages) throws IOException {
        if (dbDir == null || !dbDir.exists()) return;
        File metaFile = new File(dbDir, "catalog.meta");

        try (BufferedWriter writer = Files.newBufferedWriter(metaFile.toPath())) {
            for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
                String table = entry.getKey();
                Schema schema = entry.getValue();
                List<PageId> pages = tablePages.getOrDefault(table, List.of());

                // Format: TABLE:<name>
                writer.write("TABLE:" + table);
                writer.newLine();

                // Format: PAGES:0,1,2
                StringBuilder pageStr = new StringBuilder("PAGES:");
                for (int i = 0; i < pages.size(); i++) {
                    pageStr.append(pages.get(i).value());
                    if (i < pages.size() - 1) pageStr.append(",");
                }
                writer.write(pageStr.toString());
                writer.newLine();

                // Format: COL:<name>:<type>:<isPk>:<isNotNull>
                for (Column col : schema.getColumns()) {
                    writer.write(String.format("COL:%s:%s:%b:%b", col.getName(), col.getType().name(), col.isPrimaryKey(), col.isNotNull()));
                    writer.newLine();
                }
                writer.write("END_TABLE");
                writer.newLine();
            }
        }
    }

    public static List<TableMeta> loadCatalog(File dbDir) throws IOException {
        List<TableMeta> list = new ArrayList<>();
        if (dbDir == null || !dbDir.exists()) return list;
        File metaFile = new File(dbDir, "catalog.meta");
        if (!metaFile.exists()) return list;

        try (BufferedReader reader = Files.newBufferedReader(metaFile.toPath())) {
            String line;
            String currentTable = null;
            List<PageId> currentPages = new ArrayList<>();
            List<Column> currentCols = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("TABLE:")) {
                    currentTable = line.substring(6);
                    currentPages = new ArrayList<>();
                    currentCols = new ArrayList<>();
                } else if (line.startsWith("PAGES:")) {
                    String raw = line.substring(6).trim();
                    if (!raw.isEmpty()) {
                        for (String p : raw.split(",")) {
                            currentPages.add(new PageId(Long.parseLong(p.trim())));
                        }
                    }
                } else if (line.startsWith("COL:")) {
                    String[] parts = line.split(":");
                    String colName = parts[1];
                    Type colType = Type.valueOf(parts[2]);
                    boolean isPk = Boolean.parseBoolean(parts[3]);
                    boolean isNotNull = Boolean.parseBoolean(parts[4]);
                    currentCols.add(new Column(colName, colType, isPk, isNotNull));
                } else if (line.equals("END_TABLE")) {
                    if (currentTable != null) {
                        list.add(new TableMeta(currentTable, new Schema(currentCols), currentPages));
                    }
                }
            }
        }
        return list;
    }
}
