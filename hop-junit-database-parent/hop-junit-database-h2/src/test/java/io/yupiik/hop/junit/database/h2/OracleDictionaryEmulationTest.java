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

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OracleDictionaryEmulationTest {
    @Test
    void reflectsPhysicalTablesSynonymsAndScaffolds() throws Exception {
        try (final var c = DriverManager.getConnection("jdbc:h2:mem:dicttest;MODE=Oracle", "sa", "");
                final var s = c.createStatement()) {
            s.execute("CREATE SCHEMA LAB");
            s.execute("CREATE TABLE PUBLIC.MEASUREMENTS(id NUMBER(19) PRIMARY KEY, nm VARCHAR2(20), dt DATE)");
            s.execute(
                    "ALTER TABLE PUBLIC.MEASUREMENTS ADD CONSTRAINT FK_X FOREIGN KEY(id) REFERENCES PUBLIC.MEASUREMENTS(id)");
            s.execute("CREATE SYNONYM LAB.MEASUREMENTS FOR PUBLIC.MEASUREMENTS");
            OracleDictionaryEmulation.install(c);

            // ALL_TABLES reflects the physical table and the synonym, but never the scaffold tables.
            assertRows(
                    s,
                    "SELECT OWNER, TABLE_NAME FROM ALL_TABLES ORDER BY OWNER",
                    "[PUBLIC, MEASUREMENTS]",
                    "[LAB, MEASUREMENTS]");
            assertFalse(hasRow(s, "SELECT 1 FROM ALL_TABLES WHERE TABLE_NAME = 'ALL_TAB_PARTITIONS'"));
            assertFalse(hasRow(s, "SELECT 1 FROM ALL_TABLES WHERE TABLE_NAME = 'USER_INDEXES'"));

            // ALL_TAB_COLS reflects the synonym's columns under the LAB owner with Oracle-ish types.
            assertRows(
                    s,
                    "SELECT COLUMN_NAME, DATA_TYPE FROM ALL_TAB_COLS WHERE OWNER='LAB' AND TABLE_NAME='MEASUREMENTS' "
                            + "ORDER BY COLUMN_ID",
                    "[ID, NUMBER]",
                    "[NM, VARCHAR2]",
                    "[DT, TIMESTAMP(6)]");

            // ALL_CONSTRAINTS maps H2 constraint types to Oracle codes.
            assertRows(
                    s,
                    "SELECT CONSTRAINT_TYPE, TABLE_NAME FROM ALL_CONSTRAINTS WHERE OWNER='LAB' ORDER BY CONSTRAINT_TYPE",
                    "[P, MEASUREMENTS]",
                    "[R, MEASUREMENTS]");

            // The partition/app-metadata scaffold tables exist as insertable tables.
            s.execute("INSERT INTO ALL_TAB_PARTITIONS(TABLE_OWNER, TABLE_NAME, PARTITION_NAME, PARTITION_POSITION) "
                    + "VALUES('LAB','MEASUREMENTS','P1',1)");
            assertTrue(hasRow(s, "SELECT 1 FROM ALL_TAB_PARTITIONS WHERE TABLE_OWNER='LAB' AND PARTITION_NAME='P1'"));
            s.execute("INSERT INTO USER_TAB_PARTITIONS(TABLE_NAME, PARTITION_NAME) VALUES('MEASUREMENTS','P1')");
            assertTrue(hasRow(
                    s, "SELECT 1 FROM USER_TAB_PARTITIONS WHERE TABLE_NAME='MEASUREMENTS' AND PARTITION_NAME='P1'"));
        }
    }

    @Test
    void installIsIdempotent() throws Exception {
        try (final var c = DriverManager.getConnection("jdbc:h2:mem:dictidem;MODE=Oracle", "sa", "");
                final var s = c.createStatement()) {
            s.execute("CREATE TABLE PUBLIC.T(id INT)");
            OracleDictionaryEmulation.install(c);
            OracleDictionaryEmulation.install(c);
            // Views are replaceable and scaffold tables are IF NOT EXISTS, so both installs succeed and
            // the physical table is still reflected after re-registration.
            assertRows(s, "SELECT OWNER, TABLE_NAME FROM ALL_TABLES", "[PUBLIC, T]");
            assertTrue(hasRow(s, "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='ALL_TAB_PARTITIONS'"));
        }
    }

    private static void assertRows(final Statement s, final String sql, final String... expected) throws SQLException {
        final var actual = new java.util.TreeSet<String>();
        try (final var r = s.executeQuery(sql)) {
            while (r.next()) {
                final var sb = new StringBuilder("[");
                for (var i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                    if (i > 1) {
                        sb.append(", ");
                    }
                    sb.append(r.getString(i));
                }
                sb.append(']');
                actual.add(sb.toString());
            }
        }
        assertEquals(Set.of(expected), actual);
    }

    private static boolean hasRow(final Statement s, final String sql) throws SQLException {
        try (final var r = s.executeQuery(sql)) {
            return r.next();
        }
    }
}
