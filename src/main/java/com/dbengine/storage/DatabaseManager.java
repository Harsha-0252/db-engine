package com.dbengine.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the physical directories for databases.
 * Maps 'CREATE DATABASE' to mkdir() and tracks the active environment.
 */
public class DatabaseManager {
    private final File rootDir;
    private String currentDatabase = null;

    public DatabaseManager(String rootPath) {
        this.rootDir = new File(rootPath);
        if (!rootDir.exists()) {
            rootDir.mkdirs(); // Create the master data folder if it doesn't exist
        }
    }

    public boolean createDatabase(String dbName) {
        File dbDir = new File(rootDir, dbName);
        if (dbDir.exists()) {
            return false; // Database already exists
        }
        return dbDir.mkdir();
    }

    public List<String> showDatabases() {
        List<String> databases = new ArrayList<>();
        File[] files = rootDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    databases.add(file.getName());
                }
            }
        }
        return databases;
    }

    public boolean useDatabase(String dbName) {
        File dbDir = new File(rootDir, dbName);
        if (dbDir.exists() && dbDir.isDirectory()) {
            this.currentDatabase = dbName;
            return true;
        }
        return false;
    }

    public String getCurrentDatabase() {
        return currentDatabase;
    }

    public File getCurrentDatabasePath() {
        if (currentDatabase == null) return null;
        return new File(rootDir, currentDatabase);
    }
}
