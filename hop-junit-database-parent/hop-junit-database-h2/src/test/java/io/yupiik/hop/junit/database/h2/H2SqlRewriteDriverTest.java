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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.database.h2.driver.H2SqlRewriteDriver;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class H2SqlRewriteDriverTest {
    private final H2SqlRewriteDriver driver = new H2SqlRewriteDriver();

    @Test
    void wrapRejectsNonH2Url() {
        final var exception = assertThrows(IllegalArgumentException.class, () -> driver.wrap("jdbc:oracle:thin:@x"));
        assertEquals("Only H2 JDBC URLs can be wrapped: jdbc:oracle:thin:@x", exception.getMessage());
    }

    @Test
    void wrapAndUnwrapRoundTrip() {
        final var h2 = "jdbc:h2:mem:test;MODE=Oracle";
        final var wrapped = driver.wrap(h2);
        assertTrue(wrapped.startsWith("jdbc:testoracleextended:h2:"));
        assertEquals(h2, driver.unwrap(wrapped));
        // unwrap of a plain URL is a no-op
        assertEquals(h2, driver.unwrap(h2));
    }

    @Test
    void acceptsUrlOnlyForWrappedPrefix() {
        assertTrue(driver.acceptsURL("jdbc:testoracleextended:h2:mem:x"));
        assertFalse(driver.acceptsURL("jdbc:h2:mem:x"));
        assertFalse(driver.acceptsURL(null));
    }

    @Test
    void driverIsRegisteredInDriverManager() throws Exception {
        final var registered = DriverManager.getDriver("jdbc:testoracleextended:h2:mem:x");
        assertNotNull(registered);
        assertTrue(registered instanceof H2SqlRewriteDriver);
        assertEquals("2.3", registered.getMajorVersion() + "." + registered.getMinorVersion());
    }

    @Test
    void connectReturnsNullForForeignUrl() throws Exception {
        assertNull(driver.connect("jdbc:other:mem", null));
    }

    @Test
    void connectExecutesRewrittenOracleSql() throws Exception {
        final var url = driver.wrap("jdbc:h2:mem:" + getClass().getSimpleName() + ";MODE=Oracle");
        try (final var connection = DriverManager.getConnection(url);
                final var statement = connection.createStatement()) {
            // Schema qualification is fixture-owned; only the partition syntax is approximated.
            statement.execute("CREATE SCHEMA SA");
            statement.execute("SET SCHEMA SA");
            statement.execute("CREATE TABLE ORDERS (ID INT, NAME VARCHAR(20))");
            statement.execute("INSERT INTO ORDERS VALUES (1, 'a')");
            try (final var prepared = connection.prepareStatement(
                            "SELECT * FROM \"SA\".ORDERS PARTITION(P202601) O WHERE O.ID = 1");
                    final var resultSet = prepared.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
                assertEquals("a", resultSet.getString(2));
            }
        }
    }

    @Test
    void statementExecuteQueryRewritesOnTheFly() throws Exception {
        final var url = driver.wrap("jdbc:h2:mem:" + getClass().getSimpleName() + "2;MODE=Oracle");
        try (final var connection = DriverManager.getConnection(url);
                final var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA SA");
            statement.execute("SET SCHEMA SA");
            statement.execute("CREATE TABLE T (ID INT)");
            statement.execute("INSERT INTO T VALUES (7)");
            try (final var resultSet = statement.executeQuery("SELECT ID FROM \"SA\".T PARTITION(P1)")) {
                assertTrue(resultSet.next());
                assertEquals(7, resultSet.getInt(1));
            }
        }
    }

    @Test
    void proxyStaysTransparentForNonSqlMethods() throws Exception {
        final var url = driver.wrap("jdbc:h2:mem:" + getClass().getSimpleName() + "3;MODE=Oracle");
        try (final var connection = DriverManager.getConnection(url)) {
            assertFalse(connection.isClosed());
            try (final var prepared = connection.prepareStatement("SELECT 1 FROM dual");
                    final var resultSet = prepared.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
                assertFalse(prepared.isClosed());
                assertNotNull(prepared.toString());
            }
        }
    }
}
