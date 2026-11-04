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
package io.yupiik.hop.junit.database;

import java.util.regex.Pattern;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;

/**
 * Builds the Hop {@link DatabaseMeta} for a {@link JdbcConnectionSpec} and stores it in the metadata
 * provider, so a Hop pipeline/workflow can resolve a named connection from the JDBC spec produced by
 * the {@code @Database}-family extensions.
 */
public class DatabaseConnections {
    private static final Pattern ORACLE_URL =
            Pattern.compile("jdbc:oracle:thin:(?:@|@/)?//(?<host>[^/:]+)(?::(?<port>\\d+))?/(?<service>[^/]+)");

    public DatabaseConnections() {
        // no-op
    }

    public void save(final MemoryMetadataProvider metadataProvider, final String name, final JdbcConnectionSpec spec)
            throws HopException {
        final var database = new DatabaseMeta();
        database.setName(name);
        database.setDatabaseType(spec.databaseTypeName());
        database.setAccessType(DatabaseMeta.TYPE_ACCESS_NATIVE);
        switch (spec.databaseTypeName()) {
            case "ORACLE" -> {
                // The Oracle spec carries only the JDBC URL; host/port/service are derived from it
                // (SERVICE_NAME connection, matching how the Oracle metadata is traditionally written).
                final var url = spec.jdbcUrl();
                final var matcher = ORACLE_URL.matcher(url);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("Unsupported Oracle JDBC URL: " + url);
                }
                database.setHostname(matcher.group("host"));
                database.setPort(
                        matcher.group("port") == null || matcher.group("port").isEmpty()
                                ? "1521"
                                : matcher.group("port"));
                database.setDBName("/" + matcher.group("service"));
            }
            case "H2" -> {
                // The spec's jdbcUrl() is the URL Hop should connect through (the plain or
                // Oracle-emulation wrapped URL); the DB name is the in-memory name after the prefix.
                database.setDBName(spec.jdbcUrl().substring("jdbc:h2:".length()));
                database.setManualUrl(spec.jdbcUrl());
            }
            default -> database.setDBName(spec.jdbcUrl());
        }
        database.setUsername(spec.username());
        database.setPassword(spec.password());
        metadataProvider.getSerializer(DatabaseMeta.class).save(database);
    }
}
