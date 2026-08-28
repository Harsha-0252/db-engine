package com.dbengine;

import com.dbengine.buffer.BufferPoolManager;
import com.dbengine.sql.Lexer;
import com.dbengine.sql.LexerException;
import com.dbengine.sql.ParseException;
import com.dbengine.sql.Parser;
import com.dbengine.sql.QueryResult;
import com.dbengine.sql.StatementExecutor;
import com.dbengine.sql.Token;
import com.dbengine.sql.ast.Statement;
import com.dbengine.storage.DatabaseManager;
import com.dbengine.storage.DiskManager;

import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("      JAVA RELATIONAL DB ENGINE v4.0    ");
        System.out.println("        (SQL Command Layer Active)      ");
        System.out.println("========================================");
        System.out.println("Type 'help' for commands.\n");

        DatabaseManager dbManager = new DatabaseManager("db_data");

        try (DiskManager diskManager = new DiskManager(Paths.get("production.db"))) {
            BufferPoolManager bpm = new BufferPoolManager(10, diskManager);

            // Our new Execution Engine replaces the hardcoded Table/Schema from v3.0
            StatementExecutor executor = new StatementExecutor(dbManager, bpm);

            Scanner scanner = new Scanner(System.in);
            StringBuilder statementBuffer = new StringBuilder();

            while (true) {
                // Dynamic prompt: if buffer is empty, show DB name. Otherwise, show continuation prompt.
                String prompt;
                if (statementBuffer.length() == 0) {
                    prompt = dbManager.getCurrentDatabase() == null ? "db> " : dbManager.getCurrentDatabase() + "> ";
                } else {
                    prompt = "  -> ";
                }

                System.out.print(prompt);
                String input = scanner.nextLine().trim();

                if (input.isEmpty()) continue;

                // --- 1. SYSTEM META-COMMANDS (Bypass Lexer/Parser entirely) ---
                if (statementBuffer.length() == 0) {
                    String upperInput = input.toUpperCase();
                    if (upperInput.equals("EXIT") || upperInput.equals("QUIT")) {
                        bpm.flushAllPages(); // Ensure shutdown writes all pages to disk
                        System.out.println("Shutting down database engine safely...");
                        break;
                    } else if (upperInput.equals("TABLES")) {
                        QueryResult r = executor.execute(new Statement.ShowTablesStatement());
                        printQueryResult(r);
                        continue;
                    } else if (upperInput.equals("HELP")) {
                        System.out.println("System Commands: exit, tables, buffer_pool, btree, help");
                        System.out.println("SQL Commands   : SELECT, INSERT, UPDATE, DELETE, CREATE/DROP DATABASE/TABLE, SHOW, USE (Must end with ';')");
                        continue;
                    } else if (upperInput.equals("TABLES")) {
                        System.out.println("users (hardcoded for now)"); // Kept exactly as requested
                        continue;
                    } else if (upperInput.equals("BUFFER_POOL")) {
                        System.out.println("Buffer Pool Size: 10 frames");
                        System.out.println("Total Disk Pages Allocated: " + diskManager.getNumPages());
                        continue;
                    } else if (upperInput.equals("BTREE")) {
                        System.out.println("B+Tree Index Manager: Active (Awaiting query hookup)");
                        continue;
                    }
                }

                // --- 2. SQL ACCUMULATION & EXECUTION ---
                statementBuffer.append(input).append(" ");

                if (input.endsWith(";")) {
                    String fullSql = statementBuffer.toString().trim();
                    statementBuffer.setLength(0); // Flush the buffer for the next command

                    try {
                        // Stage 1: Lexical Analysis
                        Lexer lexer = new Lexer(fullSql);
                        List<Token> tokens = lexer.tokenize();

                        // Stage 2: Parsing & AST Generation
                        Parser parser = new Parser(tokens);
                        List<Statement> statements = parser.parse();

                        // Stage 3: Execution
                        for (Statement stmt : statements) {
                            QueryResult result = executor.execute(stmt);
                            printQueryResult(result);
                        }

                    } catch (LexerException e) {
                        System.out.println("Lexer Error: " + e.getMessage());
                    } catch (ParseException e) {
                        System.out.println("Parser Error: " + e.getMessage());
                    } catch (Exception e) {
                        System.out.println("Execution Error: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Fatal database error:");
            e.printStackTrace();
        }
    }

    private static void printQueryResult(QueryResult result) {
        if (!result.success()) {
            System.out.println("Error: " + result.message());
            return;
        }

        // Handle Result Sets (SELECT / SHOW)
        if (result.columns() != null && result.rows() != null) {
            System.out.println("----------------------------------------");
            if (!result.columns().isEmpty()) {
                System.out.println(String.join(" | ", result.columns()));
                System.out.println("----------------------------------------");
            }
            for (List<String> row : result.rows()) {
                System.out.println(String.join(" | ", row));
            }
            System.out.println("----------------------------------------");
        }

        // Print the status message (e.g., "1 row(s) affected.")
        System.out.println(result.message());
    }
}
