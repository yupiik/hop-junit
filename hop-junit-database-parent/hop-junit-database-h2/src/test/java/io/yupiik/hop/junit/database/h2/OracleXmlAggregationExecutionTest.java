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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.hop.junit.database.h2.driver.H2SqlRewriteDriver;
import io.yupiik.hop.junit.database.h2.driver.OracleToH2SqlRewriter;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class OracleXmlAggregationExecutionTest {
    @Test
    void scalarTextExpressionsAreOrderedAndNotXmlEscaped() throws Exception {
        try (final var c = DriverManager.getConnection(
                        new H2SqlRewriteDriver().wrap("jdbc:h2:mem:xml_aggregation;MODE=Oracle"));
                final var s = c.createStatement()) {
            s.execute("CREATE TABLE XML_VALUES(SEQ INT, PAYLOAD VARCHAR)");
            s.execute("INSERT INTO XML_VALUES VALUES (2, 'B'), (1, 'A<&'), (3, NULL)");
            try (final var r = s.executeQuery(
                    "SELECT RTRIM(XMLCAST(XMLAGG(XMLELEMENT(e, payload || ' + ') ORDER BY seq) AS CLOB), ' + ') FROM XML_VALUES")) {
                assertTrue(r.next());
                assertEquals("A<& + B", r.getString(1));
            }
            try (final var r = s.executeQuery(
                    "SELECT XMLCAST(XMLAGG(XMLELEMENT(arbitrary_element, UPPER(payload)) ORDER BY seq DESC) AS CLOB) FROM XML_VALUES")) {
                assertTrue(r.next());
                assertEquals("BA<&", r.getString(1));
            }
            try (final var r = s.executeQuery(
                    "SELECT XMLCAST(XMLAGG(XMLELEMENT(e, payload) ORDER BY seq) AS CLOB) FROM XML_VALUES WHERE 1=0")) {
                assertTrue(r.next());
                assertNull(r.getString(1));
            }
            try (final var r = s.executeQuery(
                    "SELECT XMLCAST(XMLAGG(XMLELEMENT(e, RPAD('x', 1000, 'x')) ORDER BY x) AS CLOB) FROM SYSTEM_RANGE(1, 100)")) {
                assertTrue(r.next());
                assertEquals(100000, r.getString(1).length());
            }
        }
    }

    @Test
    void unrelatedAndUnsupportedFormsAreUntouched() {
        final var rewrite = new OracleToH2SqlRewriter();
        for (final var sql : new String[] {
            "SELECT q'[don't change XMLCAST(XMLAGG(XMLELEMENT(e, x)) AS CLOB)]' FROM dual",
            "SELECT XMLCAST(XMLAGG(XMLELEMENT(e, a, b)) AS CLOB) FROM t"
        }) {
            assertEquals(sql, rewrite.apply(sql));
        }
    }
}
