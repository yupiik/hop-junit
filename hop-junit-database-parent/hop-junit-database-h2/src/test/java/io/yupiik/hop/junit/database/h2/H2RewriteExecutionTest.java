/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.database.h2.driver.H2SqlRewriteDriver;
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
                    "SELECT RAWTOHEX(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW(CONCAT('a', 'bc')), 4))")) {
                assertTrue(result.next());
                assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", result.getString(1));
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
        try (final var connection = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:sysctx;MODE=Oracle"));
                final var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ALIAS SYS_CONTEXT FOR 'io.yupiik.hop.junit.database.h2.H2OracleExtendedShims.SYS_CONTEXT'");
            try (final var result = statement.executeQuery(
                    "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), SYS_CONTEXT('USERENV','SESSION_USER')")) {
                assertTrue(result.next());
                final var schema = result.getString(1);
                assertTrue(schema != null && !schema.isBlank(), () -> "schema must not be blank: " + schema);
                assertEquals(schema, result.getString(2));
            }
            try (final var result = statement.executeQuery("SELECT SYS_CONTEXT('OTHER', 'CURRENT_SCHEMA')")) {
                assertTrue(result.next());
                assertTrue(result.getString(1) == null, "unknown namespace must return null");
            }
        }
    }
}
