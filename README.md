# Java Relational Database Engine

![Java](https://img.shields.io/badge/Java-17-orange)
![Build](https://img.shields.io/badge/Build-Maven-blue)
![Tests](https://img.shields.io/badge/Tests-JUnit%205-green)

A relational database engine built from scratch in Java 17: slotted pages, a buffer pool with LRU eviction, a disk-backed B+Tree, and a hand-written SQL parser.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Design Decisions & Known Limitations](#design-decisions--known-limitations)
- [Getting Started](#getting-started)
- [Usage Walkthrough](#usage-walkthrough)
- [SQL Command Reference](#sql-command-reference)
- [Project Structure](#project-structure)
- [Testing](#testing)
- [Future Work](#future-work)
- [About This Project](#about-this-project)

## Overview

This project is a fully functional, embedded relational database management system (RDBMS) built from the ground up. It was developed primarily as an architectural exercise to understand, at the byte level, how real databases parse queries, manage memory under fixed constraints, and persist data safely to disk.

It is designed as a standalone, file-based engine. It operates locally via a custom Read-Eval-Print Loop (REPL) and does not currently operate as a distributed or networked database server.

## Architecture

```text
       +-------------------------------------------------+
       |                  CLI / REPL                      |
       +-------------------------------------------------+
                               |
       +-------------------------------------------------+
       |                  SQL LAYER                       |
       |     Lexer -> Recursive Descent Parser -> AST     |
       +-------------------------------------------------+
                               |
       +-------------------------------------------------+
       |             STATEMENT EXECUTOR                   |
       |    (Relational Algebra, Joins, Aggregates)        |
       +-------+---------------------------------+--------+
               |                                  |
       +-------v--------+                +--------v-------+
       |  TRANSACTION   |                | TABLE / SCHEMA |
       |    CONTEXT     |                |     LAYER      |
       | (Overlay Map)  |                +--------+-------+
       +----------------+                         |
                                          +--------v-------+
                                          |  BUFFER POOL   |
                                          |    MANAGER     |
                                          | (LRU Eviction) |
                                          +--------+-------+
                                                   |
                                          +--------v-------+
                                          |  DISK MANAGER  |
                                          | (FileChannel,  |
                                          |  Slotted Pages)|
                                          +----------------+
```

- **SQL Layer** — A custom Lexer and Parser translate raw string input into an Abstract Syntax Tree (AST). It does not rely on regular expressions for statement matching; it strictly enforces a defined grammar.
- **Execution & Transactions** — Evaluates AST nodes. Transactions are handled via an in-memory `TransactionContext` overlay map: uncommitted mutations (inserts, updates, deletes) are held in memory and merged with disk reads on the fly, providing read-your-own-writes semantics until `COMMIT` or `ROLLBACK`.
- **Table & Schema Layer** — Maps logical relational concepts (columns, data types, constraints) into physical `Tuple` serialization, handling exact byte offsets for `INTEGER`, `FLOAT`, `BOOLEAN`, and `VARCHAR`.
- **Buffer Pool Manager** — Bridges memory and disk. Memory is constrained to a fixed number of frames; when full, an LRU (Least Recently Used) policy evicts the oldest page to disk.
- **Storage Layer** — A standard slotted-page layout (4096 bytes) mapping logical `RecordId`s to physical offsets, with tombstoning for deleted records.

## Design Decisions & Known Limitations

- **Non-durable transactions** — Transactions are atomic and isolated within a session, but not durable. There is no Write-Ahead Log (WAL); a crash mid-transaction loses uncommitted data. True crash recovery via an ARIES-style WAL is scoped as future work, not an oversight.
- **Full table scans, not index-seeks** — The storage engine fully implements B+Tree indexing (`BTreeInternalPage`, `BTreeLeafPage`, `BTreeIndex`), but the `StatementExecutor` currently fulfills all `SELECT`/`UPDATE`/`DELETE` filtering via full table scans. The B+Tree isn't yet wired into a query optimizer for index-seek lookups — the index structure works, it's just not consulted by the query path yet.
- **Nested-loop joins** — `INNER JOIN` uses nested-loop evaluation (`O(n * m)`). Hash joins or sort-merge joins are out of scope for this iteration.
- **Case semantics modeled on Linux MySQL** — Table and database names are case-sensitive. Keywords and column identifiers are case-insensitive. This mirrors MySQL as deployed on Linux, not its case-insensitive behavior on Windows/macOS filesystems.
- **Binary string collation only** — String comparisons (e.g. `WHERE name = 'Alice'`) are case-sensitive. Configurable, case-insensitive collations (MySQL's `_ci` default) were excluded to keep comparison and serialization logic simple.
- **Unified constraint validation** — `PRIMARY KEY` and `NOT NULL` constraints are enforced in-memory immediately before mutation, through a single shared validation path used by both `INSERT` and `UPDATE` — found and fixed during testing after an earlier version validated `INSERT` but not `UPDATE`.

## Getting Started

**Prerequisites:**

- Java 17+
- Maven 3.6+

Clone the repository and build the fat JAR:

```bash
git clone <your-repo-url>
cd db-engine
mvn clean package
```

Run the interactive SQL REPL:

```bash
java -jar target/db-engine-1.0.0-jar-with-dependencies.jar
```

## Usage Walkthrough

```sql
CREATE DATABASE analytics;
USE analytics;

CREATE TABLE metrics (
    id INT PRIMARY KEY,
    service VARCHAR NOT NULL,
    latency FLOAT,
    active BOOLEAN
);

BEGIN;
INSERT INTO metrics VALUES (1, 'auth-service', 45.5, TRUE);
INSERT INTO metrics VALUES (2, 'payment-gateway', 120.2, FALSE);
INSERT INTO metrics VALUES (3, 'user-profile', NULL, TRUE);
COMMIT;

SELECT service, AVG(latency)
FROM metrics
WHERE active = TRUE
GROUP BY service
ORDER BY AVG(latency) DESC;
```

## SQL Command Reference

| Command | Syntax | Description |
|---|---|---|
| **CREATE DATABASE** | `CREATE DATABASE <name>;` | Initializes a new database directory and catalog. |
| **USE** | `USE <name>;` | Switches session context to the specified database. |
| **DROP DATABASE** | `DROP DATABASE <name>;` | Recursively deletes the database and all underlying table files. |
| **SHOW DATABASES** | `SHOW DATABASES;` | Lists all databases managed by the engine. |
| **CREATE TABLE** | `CREATE TABLE <name> (<col> <type> [PRIMARY KEY] [NOT NULL], ...);` | Creates a new table schema and persistent storage file. |
| **DROP TABLE** | `DROP TABLE <name>;` | Removes a table and its catalog metadata. |
| **TRUNCATE TABLE** | `TRUNCATE TABLE <name>;` | Deletes all rows while preserving the schema; rollback-safe within a transaction. |
| **ALTER TABLE** | `ALTER TABLE <name> ADD COLUMN <col> <type>;` `ALTER TABLE <name> DROP COLUMN <col>;` `ALTER TABLE <name> RENAME COLUMN <old> TO <new>;` | Mutates an existing table's schema. |
| **DESCRIBE** | `DESCRIBE <name>;` | Outputs the schema definition and constraints for a table. |
| **SHOW TABLES** | `SHOW TABLES;` | Lists all tables in the current database. |
| **INSERT** | `INSERT INTO <name> [(<cols>)] VALUES (<values>);` | Appends a row. Enforces `PRIMARY KEY` and `NOT NULL` constraints. |
| **UPDATE** | `UPDATE <name> SET <col> = <val>, ... [WHERE <condition>];` | Mutates existing rows matching the condition. |
| **DELETE** | `DELETE FROM <name> [WHERE <condition>];` | Removes rows matching the condition. |
| **SELECT** | `SELECT <cols> FROM <name> [JOIN ...] [WHERE ...] [GROUP BY ...] [ORDER BY ...] [LIMIT n];` | Queries data. Supports `WHERE`, `INNER JOIN`, aggregates, `GROUP BY`, `ORDER BY`, `LIMIT`, and `DISTINCT`. |
| **BEGIN / COMMIT / ROLLBACK** | `BEGIN;` / `COMMIT;` / `ROLLBACK;` | Starts, commits, or discards an in-memory transactional overlay. |

## Project Structure

```text
src/main/java/com/dbengine/
├── buffer/       # BufferPoolManager and LRU eviction logic
├── common/       # System-wide constants (page size, invalid IDs)
├── index/        # B+Tree internal and leaf page implementations
├── record/       # Tuple, Value, Schema, and binary serialization logic
├── sql/          # Lexer, Parser, AST nodes, and StatementExecutor
├── storage/      # DiskManager, file I/O, and SlottedPageLayout
└── table/        # Physical Table mapping and RecordId abstractions
```

**Key classes to explore:**

- `StatementExecutor.java` — bridges AST nodes to physical relational algebra and transactional state.
- `Parser.java` — recursive-descent parser mapping SQL grammar into typed statement classes.
- `BufferPoolManager.java` — the memory-management unit controlling I/O between the executor and disk.
- `Tuple.java` — fixed- and variable-width binary serialization of rows.

## Testing

The project uses **JUnit 5** for layered unit testing across the architecture (`LexerTest`, `BufferPoolManagerTest`, `TupleTest`, and others).

Beyond unit tests, the engine was driven through adversarial manual and regression testing that specifically verified: transaction isolation (rolled-back mutations don't leak into later queries), byte-offset correctness when mixing variable-width `VARCHAR` with fixed-width `FLOAT`/`BOOLEAN` columns, and consistent enforcement of `PRIMARY KEY`/`NOT NULL` constraints across every write path — this process caught and fixed several real bugs (a case-sensitivity inconsistency between quoted and unquoted identifiers, a multi-aggregate parsing failure, an overly strict `ORDER BY` restriction, and the `UPDATE` constraint gap noted above) before they shipped.

Run the suite with:

```bash
mvn test
```

## Future Work

- **Write-ahead logging (WAL)** — sequential log appends plus an ARIES-style recovery model, to make transactions actually durable.
- **Query optimizer** — wire the existing B+Tree index into `StatementExecutor` so indexed filters use index-seeks instead of full table scans.
- **Expanded joins** — `LEFT`, `RIGHT`, and `FULL OUTER JOIN`.
- **Subqueries** — extend the AST and executor to handle nested query resolution.

## About This Project

Built as a from-scratch systems project to understand database internals end to end — storage, memory management, indexing, query parsing, and transactions — rather than to compete with an existing engine.
