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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.hop.junit.database.h2.driver.H2SqlRewriteDriver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OracleHierarchyExecutionTest {
    @Test
    void unrelatedNumericColumnDoesNotInheritADateAlias() throws Exception {
        try (final var c = DriverManager.getConnection(
                        new H2SqlRewriteDriver().wrap("jdbc:h2:mem:date_alias_scope;MODE=Oracle"));
                final var s = c.createStatement()) {
            s.execute("CREATE TABLE numbers(checkpoint INT)");
            s.execute("INSERT INTO numbers VALUES (7)");
            try (final var r = s.executeQuery(
                    "WITH cfg AS (SELECT TO_DATE('2020-01-01','YYYY-MM-DD') AS checkpoint FROM dual) SELECT n.checkpoint + 1 FROM numbers n")) {
                assertTrue(r.next());
                assertEquals(8, r.getInt(1));
            }
        }
    }

    @Test
    void nullConditionalDateRetainsItsType() throws Exception {
        try (final var c = DriverManager.getConnection(
                        new H2SqlRewriteDriver().wrap("jdbc:h2:mem:null_date;MODE=Oracle"));
                final var s = c.createStatement();
                final var r = s.executeQuery(
                        "WITH cfg AS (SELECT CASE WHEN 1=0 THEN TO_DATE('2020-01-01', 'YYYY-MM-DD') ELSE NULL END AS checkpoint FROM dual) SELECT c.checkpoint + 1 FROM cfg c")) {
            assertTrue(r.next());
            assertNull(r.getObject(1));
        }
    }

    @Test
    void csvOccurrenceAndDuplicateSourcesKeepTheirCardinality() throws Exception {
        try (final var c =
                        DriverManager.getConnection(new H2SqlRewriteDriver().wrap("jdbc:h2:mem:csv_rows;MODE=Oracle"));
                final var s = c.createStatement()) {
            s.execute("CREATE TABLE csv_rows(k INT, csv VARCHAR)");
            s.execute("INSERT INTO csv_rows VALUES(1, 'a,b'), (1, 'a,b')");
            try (final var r = s.executeQuery(
                    "SELECT REGEXP_SUBSTR(c.csv, '[^,]+', 1, LEVEL), LEVEL FROM csv_rows c CONNECT BY REGEXP_SUBSTR(c.csv, '[^,]+', 1, LEVEL) IS NOT NULL AND PRIOR c.k = c.k AND PRIOR SYS_GUID() IS NOT NULL")) {
                var roots = 0;
                var children = 0;
                while (r.next()) {
                    if (r.getInt(2) == 1) {
                        roots++;
                        assertEquals("a", r.getString(1));
                    } else {
                        children++;
                        assertEquals("b", r.getString(1));
                    }
                }
                assertEquals(2, roots);
                assertEquals(4, children);
            }
        }
    }

    @Test
    void arithmeticRetainsPrecedenceFractionalDaysAndBindOrder() throws Exception {
        try (final var c = DriverManager.getConnection(
                        new H2SqlRewriteDriver().wrap("jdbc:h2:mem:date_operators;MODE=Oracle"));
                final var s = c.createStatement()) {
            for (final var expression : List.of(
                    "TO_DATE('2020-01-01','YYYY-MM-DD') + 2 * 3",
                    "TO_DATE('2020-01-01','YYYY-MM-DD') - -6",
                    "DATE '2020-01-01' + 6")) {
                try (final var r = s.executeQuery("SELECT " + expression)) {
                    assertTrue(r.next());
                    assertEquals("2020-01-07", r.getDate(1).toLocalDate().toString());
                }
            }
            try (final var p = c.prepareStatement("SELECT TO_DATE(?, 'YYYY-MM-DD') + ? - ?")) {
                p.setString(1, "2020-01-01");
                p.setDouble(2, 1.5);
                p.setDouble(3, .5);
                try (final var r = p.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals("2020-01-02", r.getDate(1).toLocalDate().toString());
                }
            }
        }
    }

    @Test
    void conditionalDatesAndNestedDateDifferences() throws Exception {
        try (final var c = DriverManager.getConnection(
                        new H2SqlRewriteDriver().wrap("jdbc:h2:mem:conditional_dates;MODE=Oracle"));
                final var s = c.createStatement();
                final var r = s.executeQuery(
                        "WITH cfg AS (SELECT CASE WHEN 1=1 THEN TO_DATE('2020-01-01', 'YYYY-MM-DD') ELSE NULL END AS checkpoint FROM dual) SELECT GREATEST(0, (TRUNC(SYSDATE) - 2) - GREATEST(NVL(c.checkpoint + 1, TRUNC(SYSDATE) - 5), TRUNC(SYSDATE) - 5) + 1) FROM cfg c")) {
            assertTrue(r.next());
            assertEquals(4, r.getInt(1));
        }
    }

    @Test
    void dateDifferencesAreNumericDays() throws Exception {
        try (final var c = DriverManager.getConnection(new H2SqlRewriteDriver().wrap("jdbc:h2:mem:dates;MODE=Oracle"));
                final var s = c.createStatement();
                final var r = s.executeQuery(
                        "WITH cfg AS (SELECT TO_DATE('2020-01-01', 'YYYY-MM-DD') AS first_day, TO_DATE('2020-01-03', 'YYYY-MM-DD') AS last_day FROM dual) SELECT c.last_day - c.first_day + 1 FROM cfg c")) {
            assertTrue(r.next());
            assertEquals(3, r.getInt(1));
        }
    }

    @Test
    void generatorsRetainRootsEvenForZeroBounds() throws Exception {
        final var driver = new H2SqlRewriteDriver();
        try (final var c = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:hierarchy;MODE=Oracle"));
                final var s = c.createStatement()) {
            for (final var bound : new int[] {0, 1, 7}) {
                try (final var r =
                        s.executeQuery("SELECT LEVEL FROM dual CONNECT /* comment */ BY LEVEL <= " + bound)) {
                    for (var i = 1; i <= Math.max(1, bound); i++) {
                        assertTrue(r.next());
                        assertEquals(i, r.getInt(1));
                    }
                    assertFalse(r.next());
                }
            }
            try (final var r = s.executeQuery(
                    "WITH cfg AS (SELECT 3 n FROM dual), generated AS (SELECT LEVEL l FROM cfg c CONNECT BY LEVEL <= c.n) SELECT l FROM generated UNION ALL SELECT 9")) {
                final var rows = new ArrayList<Integer>();
                while (r.next()) {
                    rows.add(r.getInt(1));
                }
                assertEquals(List.of(1, 2, 3, 9), rows);
            }
        }
    }

    @Test
    void priorCorrelationAndPostHierarchyFiltering() throws Exception {
        final var driver = new H2SqlRewriteDriver();
        try (final var c = DriverManager.getConnection(driver.wrap("jdbc:h2:mem:correlation;MODE=Oracle"));
                final var s = c.createStatement()) {
            s.execute("CREATE TABLE nodes(id INT, parent INT, label VARCHAR)");
            s.execute("INSERT INTO nodes VALUES (1, NULL, 'root'), (2, 1, 'hidden'), (3, 2, 'leaf')");
            try (final var r = s.executeQuery(
                    "SELECT c.label, LEVEL FROM nodes c WHERE c.label <> 'hidden' START WITH c.parent IS NULL CONNECT BY PRIOR c.id = c.parent AND LEVEL <= 5")) {
                assertTrue(r.next());
                assertEquals("root", r.getString(1));
                assertEquals(1, r.getInt(2));
                assertTrue(r.next());
                assertEquals("leaf", r.getString(1));
                assertEquals(3, r.getInt(2));
                assertFalse(r.next());
            }
        }
    }
}
