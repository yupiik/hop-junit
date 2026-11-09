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
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class H2JdbcProxyTest {
    @Test
    void registeredSubclassWinsEvenAfterBuiltinRegistration() throws Exception {
        final var builtin = new H2SqlRewriteDriver();
        final var custom =
                new H2SqlRewriteDriver(sql -> sql.replace("CUSTOM_VALUE", "43"), "jdbc:testoracleextended:h2:") {};
        DriverManager.registerDriver(custom);
        try {
            assertNotNull(new H2DatabaseExtension());
            try (final var c = DriverManager.getConnection(builtin.wrap("jdbc:h2:mem:custom_driver"));
                    final var s = c.createStatement();
                    final var r = s.executeQuery("SELECT CUSTOM_VALUE")) {
                assertTrue(r.next());
                assertEquals(43, r.getInt(1));
            }
        } finally {
            DriverManager.deregisterDriver(custom);
        }
    }

    @Test
    void preservesInterfacesIdentityAndConnection() throws Exception {
        final var driver = new H2SqlRewriteDriver(sql -> sql.replace("MAGIC_VALUE", "42"), "jdbc:proxytest:h2:");
        try (final var c = driver.connect(driver.wrap("jdbc:h2:mem:proxytest"), new Properties());
                final var statement = c.createStatement();
                final var prepared = c.prepareStatement("SELECT MAGIC_VALUE");
                final var callable = c.prepareCall("CALL MAGIC_VALUE")) {
            assertSame(c, c.unwrap(Connection.class));
            assertTrue(c.equals(c));
            assertFalse(c.equals(c.unwrap(org.h2.jdbc.JdbcConnection.class)));
            for (final var s : new Statement[] {statement, prepared, callable}) {
                assertSame(c, s.getConnection());
                assertSame(s, s.unwrap(Statement.class));
                assertTrue(s.equals(s));
                assertTrue(s.isWrapperFor(Statement.class));
            }
            assertSame(prepared, prepared.unwrap(PreparedStatement.class));
            assertSame(callable, callable.unwrap(CallableStatement.class));
            try (final var r = prepared.executeQuery()) {
                assertTrue(r.next());
                assertEquals(42, r.getInt(1));
            }
            try (final var r = callable.executeQuery()) {
                assertTrue(r.next());
                assertEquals(42, r.getInt(1));
            }
            try (final var r = statement.getConnection().createStatement();
                    final var rows = r.executeQuery("SELECT MAGIC_VALUE")) {
                assertTrue(rows.next());
                assertEquals(42, rows.getInt(1));
            }
            statement.execute("CREATE TABLE batch_test(id INT)");
            statement.addBatch("INSERT INTO batch_test VALUES(MAGIC_VALUE)");
            assertArrayEquals(new int[] {1}, statement.executeBatch());
            try (final var p = c.prepareStatement("INSERT INTO batch_test VALUES(?)")) {
                p.setInt(1, 7);
                p.addBatch();
                assertArrayEquals(new int[] {1}, p.executeBatch());
            }
        }
    }
}
