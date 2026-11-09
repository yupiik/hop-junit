/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.junit.database.h2;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Installs Oracle data-dictionary emulation on an H2 database running in
 * {@link H2DatabaseMode#ORACLE_EXTENDED} mode, so pipelines that read the
 * {@code ALL_*}/{@code USER_*} dictionary views resolve without the consumer seeding
 * dictionary-named tables.
 *
 * <p>The emulation splits the dictionary into two kinds:
 *
 * <ul>
 *   <li><b>Schema reflection</b> ({@code ALL_TABLES}, {@code ALL_TAB_COLS},
 *       {@code ALL_TAB_COLUMNS}, {@code ALL_CONSTRAINTS}). These are installed as real H2
 *       <em>views</em> over {@code INFORMATION_SCHEMA} so they always reflect the actual schema:
 *       real base tables plus synonyms (an {@code SA.X FOR PUBLIC.X} synonym is reported under
 *       owner {@code SA}). No consumer seeding is required.</li>
 *   <li><b>Partitioning/app metadata</b> ({@code ALL_TAB_PARTITIONS}, {@code ALL_PART_TABLES},
 *       {@code ALL_PART_KEY_COLUMNS}, {@code ALL_PART_INDEXES}, {@code ALL_IND_PARTITIONS} and
 *       their {@code USER_*} counterparts). H2 cannot represent Oracle partitioning, so these are
 *       installed as ordinary Oracle-typed <em>tables</em> that the consumer seeds with its own
 *       partition data.</li>
 * </ul>
 */
public final class OracleDictionaryEmulation {
    private OracleDictionaryEmulation() {
        // no instances
    }

    /** Executes the full dictionary-emulation DDL on the given connection. */
    public static void install(final Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (final var ddl : ddl()) {
                statement.execute(ddl);
            }
        }
    }

    /** Returns every dictionary-emulation statement in dependency order, for inspection/testing. */
    public static List<String> ddl() {
        return List.of(
                // ---- schema reflection views ----
                "CREATE OR REPLACE VIEW ALL_TABLES AS "
                        + "SELECT OWNER, TABLE_NAME FROM ("
                        + "  SELECT T.TABLE_SCHEMA AS OWNER, T.TABLE_NAME"
                        + "    FROM INFORMATION_SCHEMA.TABLES T"
                        + "   WHERE T.TABLE_SCHEMA <> 'INFORMATION_SCHEMA' AND T.TABLE_TYPE = 'BASE TABLE'"
                        + "     AND UPPER(T.TABLE_NAME) NOT IN " + scaffoldNames()
                        + "  UNION"
                        + "  SELECT S.SYNONYM_SCHEMA AS OWNER, S.SYNONYM_NAME"
                        + "    FROM INFORMATION_SCHEMA.SYNONYMS S"
                        + "   WHERE S.SYNONYM_SCHEMA <> 'INFORMATION_SCHEMA')",
                "CREATE OR REPLACE VIEW ALL_TAB_COLS AS "
                        + "SELECT K.OWNER, K.TABLE_NAME, "
                        + "  C.COLUMN_NAME AS COLUMN_NAME, "
                        + "  C.ORDINAL_POSITION AS COLUMN_ID, "
                        + "  " + oracleType("C.DATA_TYPE") + " AS DATA_TYPE, "
                        + "  CAST(NULL AS VARCHAR(128)) AS DATA_TYPE_OWNER, "
                        + "  C.NUMERIC_PRECISION AS DATA_PRECISION, "
                        + "  C.NUMERIC_SCALE AS DATA_SCALE, "
                        + "  CASE WHEN C.IS_GENERATED = 'ALWAYS' THEN 'YES' ELSE 'NO' END AS VIRTUAL_COLUMN, "
                        + "  'NO' AS HIDDEN_COLUMN "
                        + "FROM " + schemaKey() + " K "
                        + "JOIN INFORMATION_SCHEMA.COLUMNS C "
                        + "  ON C.TABLE_SCHEMA = K.UNDER_SCHEMA AND C.TABLE_NAME = K.UNDER_NAME",
                "CREATE OR REPLACE VIEW ALL_TAB_COLUMNS AS "
                        + "SELECT OWNER, TABLE_NAME, COLUMN_NAME, COLUMN_ID, DATA_TYPE FROM ALL_TAB_COLS",
                "CREATE OR REPLACE VIEW ALL_CONSTRAINTS AS "
                        + "SELECT K.OWNER, "
                        + "  TC.CONSTRAINT_NAME AS CONSTRAINT_NAME, "
                        + "  " + constraintType("TC.CONSTRAINT_TYPE") + " AS CONSTRAINT_TYPE, "
                        + "  K.TABLE_NAME "
                        + "FROM " + schemaKey() + " K "
                        + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC "
                        + "  ON TC.TABLE_SCHEMA = K.UNDER_SCHEMA AND TC.TABLE_NAME = K.UNDER_NAME",
                // ---- partitioning / app-metadata scaffold tables ----
                "CREATE TABLE IF NOT EXISTS ALL_TAB_PARTITIONS ("
                        + "TABLE_OWNER VARCHAR2(128) NOT NULL, "
                        + "TABLE_NAME VARCHAR2(128) NOT NULL, "
                        + "PARTITION_NAME VARCHAR2(128) NOT NULL, "
                        + "NUM_ROWS NUMBER(19), "
                        + "PARTITION_POSITION NUMBER(10), "
                        + "HIGH_VALUE CLOB)",
                "CREATE TABLE IF NOT EXISTS ALL_PART_TABLES ("
                        + "OWNER VARCHAR2(128) NOT NULL, "
                        + "TABLE_NAME VARCHAR2(128) NOT NULL, "
                        + "PARTITIONING_TYPE VARCHAR2(32), "
                        + "SUBPARTITIONING_TYPE VARCHAR2(32), "
                        + "PARTITION_COUNT NUMBER(10))",
                "CREATE TABLE IF NOT EXISTS ALL_PART_KEY_COLUMNS ("
                        + "OWNER VARCHAR2(128) NOT NULL, "
                        + "NAME VARCHAR2(128) NOT NULL, "
                        + "OBJECT_TYPE VARCHAR2(32) NOT NULL, "
                        + "COLUMN_NAME VARCHAR2(128) NOT NULL, "
                        + "COLUMN_POSITION NUMBER(10))",
                "CREATE TABLE IF NOT EXISTS ALL_PART_INDEXES ("
                        + "OWNER VARCHAR2(128), "
                        + "INDEX_NAME VARCHAR2(128), "
                        + "TABLE_NAME VARCHAR2(128), "
                        + "LOCALITY VARCHAR2(32))",
                "CREATE TABLE IF NOT EXISTS ALL_IND_PARTITIONS ("
                        + "INDEX_NAME VARCHAR2(128), "
                        + "PARTITION_NAME VARCHAR2(128), "
                        + "STATUS VARCHAR2(32))",
                "CREATE TABLE IF NOT EXISTS ALL_INDEXES ("
                        + "OWNER VARCHAR2(128), "
                        + "INDEX_NAME VARCHAR2(128), "
                        + "TABLE_OWNER VARCHAR2(128), "
                        + "TABLE_NAME VARCHAR2(128), "
                        + "INDEX_TYPE VARCHAR2(32))",
                "CREATE TABLE IF NOT EXISTS USER_TAB_PARTITIONS ("
                        + "TABLE_NAME VARCHAR2(128) NOT NULL, "
                        + "PARTITION_NAME VARCHAR2(128) NOT NULL)",
                "CREATE TABLE IF NOT EXISTS USER_PART_INDEXES ("
                        + "INDEX_NAME VARCHAR2(128), "
                        + "TABLE_NAME VARCHAR2(128), "
                        + "LOCALITY VARCHAR2(32))",
                "CREATE TABLE IF NOT EXISTS USER_IND_PARTITIONS ("
                        + "INDEX_NAME VARCHAR2(128), "
                        + "PARTITION_NAME VARCHAR2(128), "
                        + "STATUS VARCHAR2(32))",
                "CREATE TABLE IF NOT EXISTS USER_INDEXES ("
                        + "INDEX_NAME VARCHAR2(128), "
                        + "TABLE_NAME VARCHAR2(128))");
    }

    private static String schemaKey() {
        return "(SELECT S.SYNONYM_SCHEMA AS OWNER, S.SYNONYM_NAME AS TABLE_NAME, "
                + "S.SYNONYM_FOR_SCHEMA AS UNDER_SCHEMA, S.SYNONYM_FOR AS UNDER_NAME "
                + "  FROM INFORMATION_SCHEMA.SYNONYMS S WHERE S.SYNONYM_SCHEMA <> 'INFORMATION_SCHEMA' "
                + "UNION ALL "
                + "SELECT T.TABLE_SCHEMA AS OWNER, T.TABLE_NAME AS TABLE_NAME, "
                + "T.TABLE_SCHEMA AS UNDER_SCHEMA, T.TABLE_NAME AS UNDER_NAME "
                + "  FROM INFORMATION_SCHEMA.TABLES T "
                + " WHERE T.TABLE_SCHEMA <> 'INFORMATION_SCHEMA' AND T.TABLE_TYPE = 'BASE TABLE'"
                + "   AND UPPER(T.TABLE_NAME) NOT IN " + scaffoldNames() + ")";
    }

    /** The emulation-owned scaffold table names; never present as user schema reflections. */
    private static String scaffoldNames() {
        return "('ALL_TABLES','ALL_TAB_COLS','ALL_TAB_COLUMNS','ALL_CONSTRAINTS','ALL_INDEXES','ALL_IND_PARTITIONS',"
                + "'ALL_PART_INDEXES','ALL_PART_KEY_COLUMNS','ALL_PART_TABLES','ALL_TAB_PARTITIONS',"
                + "'USER_INDEXES','USER_IND_PARTITIONS','USER_PART_INDEXES','USER_TAB_PARTITIONS')";
    }

    /** Maps an H2 catalog type name to an Oracle-ish type name for dictionary conventions. */
    private static String oracleType(final String columnExpr) {
        return "CASE " + columnExpr
                + " WHEN 'NUMERIC' THEN 'NUMBER' "
                + " WHEN 'DECFLOAT' THEN 'NUMBER' "
                + " WHEN 'CHARACTER VARYING' THEN 'VARCHAR2' "
                + " WHEN 'CHARACTER' THEN 'CHAR' "
                + " WHEN 'BOOLEAN' THEN 'NUMBER' "
                + " WHEN 'DATE' THEN 'DATE' "
                + " WHEN 'TIME' THEN 'DATE' "
                + " WHEN 'TIMESTAMP' THEN 'TIMESTAMP(6)' "
                + " WHEN 'TIMESTAMP WITH TIME ZONE' THEN 'TIMESTAMP(6) WITH TIME ZONE' "
                + " WHEN 'CLOB' THEN 'CLOB' "
                + " WHEN 'CHARACTER LARGE OBJECT' THEN 'CLOB' "
                + " WHEN 'NCLOB' THEN 'NCLOB' "
                + " WHEN 'NATIONAL CHARACTER LARGE OBJECT' THEN 'NCLOB' "
                + " WHEN 'BLOB' THEN 'BLOB' "
                + " WHEN 'BINARY LARGE OBJECT' THEN 'BLOB' "
                + " ELSE " + columnExpr + " END";
    }

    private static String constraintType(final String typeExpr) {
        return "CASE " + typeExpr
                + " WHEN 'PRIMARY KEY' THEN 'P' "
                + " WHEN 'FOREIGN KEY' THEN 'R' "
                + " WHEN 'UNIQUE' THEN 'U' "
                + " WHEN 'CHECK' THEN 'C' "
                + " ELSE " + typeExpr + " END";
    }
}
