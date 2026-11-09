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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.hop.junit.database.h2.driver.H2SqlRewriteDriver;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class OracleXmlExecutionTest {
    @Test
    void xmlUsesCurrentTransactionEscapesAndOmitsNulls() throws Exception {
        try (final var c = DriverManager.getConnection(new H2SqlRewriteDriver().wrap("jdbc:h2:mem:xml;MODE=Oracle"));
                final var s = c.createStatement()) {
            s.execute("CREATE SCHEMA DBMS_XMLGEN");
            s.execute(
                    "CREATE ALIAS DBMS_XMLGEN.GETXML FOR 'io.yupiik.hop.junit.database.h2.driver.OracleXmlFunctions.getXml'");
            s.execute("CREATE TABLE sample(id INT, payload VARCHAR, missing VARCHAR)");
            try (final var r = s.executeQuery("SELECT SYS_GUID(), RAWTOHEX(HEXTORAW('00FF80'))")) {
                assertTrue(r.next());
                assertEquals(16, r.getBytes(1).length);
                assertEquals("00FF80", r.getString(2));
            }
            c.setAutoCommit(false);
            s.execute("INSERT INTO sample VALUES(1, '<tag>&', NULL)");
            try (final var r =
                    s.executeQuery("SELECT DBMS_XMLGEN.GETXML('SELECT payload, missing FROM sample WHERE id=1')")) {
                assertTrue(r.next());
                assertTrue(
                        r.getString(1).contains("<ROWSET><ROW><PAYLOAD>&lt;tag&gt;&amp;</PAYLOAD></ROW></ROWSET>"),
                        r.getString(1));
            }
            try (final var r = s.executeQuery("SELECT DBMS_XMLGEN.GETXML('SELECT * FROM sample WHERE id=99')")) {
                assertTrue(r.next());
                assertNull(r.getString(1));
            }
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT DBMS_XMLGEN.GETXML('DELETE FROM sample')"));
            c.rollback();
            try (final var r = s.executeQuery("SELECT COUNT(*) FROM sample")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
    }
}
