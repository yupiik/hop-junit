/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.junit.database.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.database.h2.driver.H2SqlRewriteDriver;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class H2RewriteExecutionTest {
    private final H2SqlRewriteDriver driver = new H2SqlRewriteDriver();

    @Test
    void upstreamBandQueriesAreDisjointAndComplete() throws Exception {
        try (final var connection = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:bands;MODE=Oracle"));
                final var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ORDERS (ORDER_ID INT PRIMARY KEY)");
            statement.execute("INSERT INTO ORDERS SELECT X FROM SYSTEM_RANGE(1, 100)");
            final var all = new HashSet<Integer>();
            for (var band = 0; band < 4; band++) {
                final var rows = new HashSet<Integer>();
                final var sql = "SELECT t.ORDER_ID FROM ORDERS PARTITION (\"P20260101\") t "
                        + "WHERE MOD(ORA_HASH(ROWIDTOCHAR (t.ROWID)), GREATEST(1, 4)) = ?";
                try (final var prepared = connection.prepareStatement(sql)) {
                    prepared.setInt(1, band);
                    try (final var result = prepared.executeQuery()) {
                        while (result.next()) {
                            final var id = result.getInt(1);
                            assertTrue(rows.add(id));
                            assertTrue(all.add(id));
                        }
                    }
                    try (final var result = prepared.executeQuery()) {
                        final var repeated = new HashSet<Integer>();
                        while (result.next()) {
                            repeated.add(result.getInt(1));
                        }
                        assertEquals(rows, repeated);
                    }
                }
            }
            assertEquals(100, all.size());
            try (final var result = statement.executeQuery("SELECT ROWIDTOCHAR(ROWID) FROM ORDERS")) {
                assertTrue(result.next());
                assertFalse(result.getString(1).isEmpty());
            }
        }
    }

    @Test
    void digestRetentionAndTranchesExecute() throws Exception {
        try (final var connection = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:rewrites;MODE=Oracle"));
                final var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ALIAS TO_CLOB FOR 'io.yupiik.hop.junit.database.h2.H2OracleExtendedShims.TO_CLOB'");
            statement.execute(
                    "CREATE ALIAS TO_NUMBER FOR 'io.yupiik.hop.junit.database.h2.H2OracleExtendedShims.TO_NUMBER'");
            try (final var result =
                    statement.executeQuery("SELECT RAWTOHEX(DBMS_CRYPTO.HASH(TO_CLOB('abc'), DBMS_CRYPTO.HASH_SH1))")) {
                assertTrue(result.next());
                assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", result.getString(1));
            }
            try (final var result = statement.executeQuery(
                    "SELECT RAWTOHEX(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW(CONCAT('a', 'bc')), 3))")) {
                assertTrue(result.next());
                assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", result.getString(1));
            }
            try (final var result = statement.executeQuery(
                    "SELECT RAWTOHEX(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW(CONCAT('a', 'bc')), 4))")) {
                assertTrue(result.next());
                assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", result.getString(1));
            }
            for (final var days : new int[] {-30, 0, 30}) {
                final var sql = "SELECT TRUNC(SYSDATE) - CASE WHEN REGEXP_LIKE(TRIM('" + days
                        + "'), '^-?[0-9]+$') THEN TO_NUMBER(TRIM('" + days + "')) ELSE 0 END";
                try (final var result = statement.executeQuery(sql)) {
                    assertTrue(result.next());
                    assertEquals(
                            LocalDate.now().minusDays(days), result.getDate(1).toLocalDate());
                }
                try (final var prepared = connection.prepareStatement(
                        "SELECT TRUNC(SYSDATE) - CASE WHEN REGEXP_LIKE(TRIM(?), '^-?[0-9]+$') THEN TO_NUMBER(TRIM(?)) ELSE 0 END")) {
                    prepared.setString(1, "not a number");
                    prepared.setString(2, "not a number");
                    try (final var result = prepared.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(LocalDate.now(), result.getDate(1).toLocalDate());
                    }
                }
            }
            try (final var result =
                    statement.executeQuery("select level - 1 as tranche_id from dual connect by level <= 4")) {
                for (var i = 0; i < 4; i++) {
                    assertTrue(result.next());
                    assertEquals(i, result.getInt(1));
                }
                assertFalse(result.next());
            }
            statement.execute("CREATE TABLE load_cols (quoted_column VARCHAR, column_id INT)");
            statement.execute("INSERT INTO load_cols VALUES ('B', 2), ('A', 1)");
            try (final var result = statement.executeQuery(
                    "SELECT rtrim (xmlcast (xmlagg (xmlelement (e, quoted_column || ',') order by column_id) as clob), ',') FROM load_cols")) {
                assertTrue(result.next());
                assertEquals("A,B", result.getString(1));
            }
        }
    }

    @Test
    void numericCaseOffsetsPreserveBranchesBindsAndFractionalDays() throws Exception {
        try (final var connection = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:case_offsets;MODE=Oracle"));
                final var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE delivery_windows (kind VARCHAR, adjustment INT)");
            statement.execute("INSERT INTO delivery_windows VALUES ('express', 1), ('standard', 2), ('other', 3)");
            try (final var result =
                    statement.executeQuery("SELECT DATE '2042-07-19' - CASE kind WHEN 'express' THEN 2 ELSE -1 END "
                            + "FROM delivery_windows ORDER BY adjustment")) {
                for (final var expected : new String[] {"2042-07-17T00:00", "2042-07-20T00:00", "2042-07-20T00:00"}) {
                    assertTrue(result.next());
                    assertEquals(
                            expected, result.getTimestamp(1).toLocalDateTime().toString());
                }
                assertFalse(result.next());
            }
            try (final var prepared = connection.prepareStatement(
                    "SELECT DATE '2042-07-19' - (CASE WHEN kind = ? THEN CAST(? AS NUMBER(10, 2)) "
                            + "WHEN adjustment = 2 THEN CASE WHEN ? = 1 THEN 0.5 ELSE 2 END ELSE NULL END) "
                            + "FROM delivery_windows ORDER BY adjustment")) {
                prepared.setString(1, "express");
                prepared.setBigDecimal(2, new BigDecimal("1.25"));
                prepared.setInt(3, 1);
                try (final var result = prepared.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(
                            "2042-07-17T18:00",
                            result.getTimestamp(1).toLocalDateTime().toString());
                    assertTrue(result.next());
                    assertEquals(
                            "2042-07-18T12:00",
                            result.getTimestamp(1).toLocalDateTime().toString());
                    assertTrue(result.next());
                    assertNull(result.getTimestamp(1));
                    assertFalse(result.next());
                }
            }
            try (final var prepared =
                    connection.prepareStatement("SELECT DATE '2042-07-19' - CASE WHEN ? = 1 THEN ? END")) {
                prepared.setInt(1, 1);
                prepared.setBigDecimal(2, new BigDecimal("-0.5"));
                try (final var result = prepared.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(
                            "2042-07-19T12:00",
                            result.getTimestamp(1).toLocalDateTime().toString());
                }
                prepared.setInt(1, 0);
                try (final var result = prepared.executeQuery()) {
                    assertTrue(result.next());
                    assertNull(result.getTimestamp(1));
                }
            }
            try (final var result = statement.executeQuery(
                    "SELECT DATE '2042-07-19' - CASE WHEN 2 > 1 THEN (2.0 + 1) / 2 ELSE 0 END * 2")) {
                assertTrue(result.next());
                assertEquals(
                        "2042-07-16T00:00",
                        result.getTimestamp(1).toLocalDateTime().toString());
            }
        }
    }

    @Test
    void dateAliasesDoNotLeakIntoOtherCteProjections() throws Exception {
        try (final var connection = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:date_scopes;MODE=Oracle"));
                final var statement = connection.createStatement()) {
            try (final var result = statement.executeQuery(
                    "WITH calendar_values AS (SELECT TO_DATE('2042-07-19', 'YYYY-MM-DD') AS marker FROM dual), "
                            + "measurements AS (SELECT 40 AS marker FROM dual) "
                            + "SELECT m.marker + 2 FROM measurements m")) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            }
            try (final var result = statement.executeQuery(
                    "WITH calendar_values AS (SELECT CASE WHEN 1=1 THEN TO_DATE('2042-07-19', 'YYYY-MM-DD') "
                            + "ELSE NULL END AS marker FROM dual), "
                            + "measurements AS (SELECT CASE WHEN 1=1 THEN 40 ELSE NULL END AS marker FROM dual) "
                            + "SELECT m.marker + 2, c.marker + 1 FROM measurements m CROSS JOIN calendar_values c")) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
                assertEquals(LocalDate.of(2042, 7, 20), result.getDate(2).toLocalDate());
            }
            // EXISTS retains the nested declaration without H2's scalar-subquery alias flattening bug.
            try (final var result = statement.executeQuery(
                    "WITH measurements AS (SELECT EXISTS(SELECT TO_DATE('2042-07-19', 'YYYY-MM-DD') AS marker "
                            + "FROM dual) AS observed, 40 AS marker FROM dual) SELECT m.marker + 2 FROM measurements m")) {
                assertTrue(result.next());
                assertEquals(42, result.getInt(1));
            }
        }
    }

    @Test
    void literalsAndCommentsAreNotRewritten() throws Exception {
        try (final var connection = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:literals;MODE=Oracle"));
                final var statement = connection.createStatement();
                final var result = statement.executeQuery(
                        "SELECT 'FROM T PARTITION(P1) ROWIDTOCHAR(t.ROWID) \"SA\".T' /* PARTITION(P1) */")) {
            assertTrue(result.next());
            assertEquals("FROM T PARTITION(P1) ROWIDTOCHAR(t.ROWID) \"SA\".T", result.getString(1));
        }
    }

    @Test
    void sysContextResolvesToRealCurrentSchema() throws Exception {
        try (final var connection =
                        DriverManager.getConnection(driver.wrap("jdbc:h2:mem:sysctx;MODE=Oracle"), "sa", "");
                final var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ALIAS SYS_CONTEXT FOR 'io.yupiik.hop.junit.database.h2.H2OracleExtendedShims.SYS_CONTEXT'");
            statement.execute("CREATE SCHEMA DIFFERENT_OWNER");
            statement.execute("SET SCHEMA DIFFERENT_OWNER");
            statement.execute("SET SCHEMA_SEARCH_PATH DIFFERENT_OWNER, PUBLIC");
            try (final var result = statement.executeQuery(
                    "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), SYS_CONTEXT('USERENV','SESSION_USER')")) {
                assertTrue(result.next());
                final var schema = result.getString(1);
                assertEquals("DIFFERENT_OWNER", schema);
                assertEquals("SA", result.getString(2));
            }
            try (final var result = statement.executeQuery("SELECT SYS_CONTEXT('OTHER', 'CURRENT_SCHEMA')")) {
                assertTrue(result.next());
                assertTrue(result.getString(1) == null, "unknown namespace must return null");
            }
        }
    }
}
