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
package io.yupiik.hop.junit.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.junit.jupiter.api.Test;

class DatabaseConnectionsTest {
    private final DatabaseConnections connections = new DatabaseConnections();

    DatabaseConnectionsTest() {
        // DatabaseMeta resolves its database type through the Hop plugin registry.
        new io.yupiik.hop.junit.hop.HopTestEnvironment().init();
    }

    private static DatabaseMeta saved(
            final DatabaseConnections connections, final String name, final JdbcConnectionSpec spec) throws Exception {
        final var provider = new MemoryMetadataProvider();
        connections.save(provider, name, spec);
        return provider.getSerializer(DatabaseMeta.class).load(name);
    }

    @Test
    void parsesOracleUrlWithExplicitPort() throws Exception {
        final var spec = new JdbcConnectionSpec(
                "src",
                "APP",
                "secret",
                "jdbc:oracle:thin:@//dbhost:1528/FREEPDB1",
                DatabaseImplementation.ORACLE,
                "SRC");
        final var meta = saved(connections, "src", spec);
        assertEquals("dbhost", meta.getHostname());
        assertEquals("1528", meta.getPort());
        assertEquals("/FREEPDB1", meta.getDatabaseName());
        assertEquals("APP", meta.getUsername());
        assertEquals("secret", meta.getPassword());
    }

    @Test
    void parsesOracleUrlDefaultingPort() throws Exception {
        final var spec = new JdbcConnectionSpec(
                "dst", "APP", "secret", "jdbc:oracle:thin:@//dbhost/XEPDB1", DatabaseImplementation.ORACLE, "DST");
        final var meta = saved(connections, "dst", spec);
        assertEquals("dbhost", meta.getHostname());
        assertEquals("1521", meta.getPort());
        assertEquals("/XEPDB1", meta.getDatabaseName());
    }

    @Test
    void parsesOracleShortFormUrlProducedByTestcontainers() throws Exception {
        // Testcontainers OracleContainer#getJdbcUrl() emits the classic thin short form
        // (single '@', no '//'), e.g. jdbc:oracle:thin:@localhost:32797/freepdb1.
        final var spec = new JdbcConnectionSpec(
                "src",
                "APP",
                "secret",
                "jdbc:oracle:thin:@localhost:32797/freepdb1",
                DatabaseImplementation.ORACLE,
                "SRC");
        final var meta = saved(connections, "src", spec);
        assertEquals("localhost", meta.getHostname());
        assertEquals("32797", meta.getPort());
        assertEquals("/freepdb1", meta.getDatabaseName());
    }

    @Test
    void rejectsMalformedOracleUrl() {
        final var spec =
                new JdbcConnectionSpec("bad", "u", "p", "jdbc:oracle:thin:@//", DatabaseImplementation.ORACLE, "BAD");
        final var exception = assertThrows(IllegalArgumentException.class, () -> {
            final var provider = new MemoryMetadataProvider();
            connections.save(provider, "bad", spec);
        });
        assertTrue(exception.getMessage().contains("Unsupported Oracle JDBC URL"));
    }

    @Test
    void usesH2UrlForBothNameAndManualUrl() throws Exception {
        final var spec = new JdbcConnectionSpec("h2", "sa", "", "jdbc:h2:mem:mydb", DatabaseImplementation.H2, null);
        final var meta = saved(connections, "h2", spec);
        assertEquals("mem:mydb", meta.getDatabaseName());
        assertEquals("jdbc:h2:mem:mydb", meta.getManualUrl());
    }
}
