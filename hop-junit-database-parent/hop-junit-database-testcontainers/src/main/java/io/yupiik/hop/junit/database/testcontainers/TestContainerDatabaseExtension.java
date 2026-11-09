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
package io.yupiik.hop.junit.database.testcontainers;

import io.yupiik.hop.junit.database.BaseDatabaseExtension;
import io.yupiik.hop.junit.database.DatabaseImplementation;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import java.sql.DriverManager;
import java.util.Locale;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Abstract Testcontainers-backed database extension. It owns the shared, container-agnostic
 * lifecycle: resolve the image, expose the single shared container, provision a per-name schema,
 * open a JDBC connection scoped to it, and assemble the connection spec.
 *
 * <p>Subclasses are responsible only for the engine-specific bits: which {@link JdbcDatabaseContainer}
 * type and image to use, the schema provisioning SQL, the app schema password, and the container
 * singleton (see {@link #container()}). The {@code @Database} descriptor resolution also lives in the
 * concrete extension.
 */
public abstract class TestContainerDatabaseExtension<C extends JdbcDatabaseContainer<?>> extends BaseDatabaseExtension {
    /** The pinned default image as a string; subclasses override with their constant. */
    protected abstract String defaultImage();

    /**
     * The single shared container. Implementations return a lazily-started, JVM-wide singleton so
     * all named databases in a run share one container.
     */
    protected abstract C container();

    /** Schema provisioning SQL is engine-specific; subclasses create the schema if missing. */
    protected abstract void ensureSchema(C container, String schema);

    /** The password used by the per-schema application user. */
    protected abstract String schemaPassword();

    /**
     * Resolved image: a class-scoped system property key derived from this backend's simple class
     * name ({@code OracleDatabaseExtension} -> {@code oracle.image}), then {@link #defaultImage()}.
     * The concrete backend keeps control of the default via {@link #defaultImage()}; nothing
     * Oracle-specific is hardcoded in this shared base class.
     */
    protected final String image() {
        final var key =
                getClass().getSimpleName().replace("DatabaseExtension", "").toLowerCase(Locale.ROOT) + ".image";
        final var override = System.getProperty(key);
        if (override != null && !override.isBlank()) {
            return override;
        }
        return defaultImage();
    }

    @Override
    protected ManagedDatabase createDatabase(final ExtensionContext context, final Descriptor database)
            throws Exception {
        final var running = container();
        final var schema = database.name().toUpperCase(Locale.ROOT);
        ensureSchema(running, schema);

        final var jdbcUrl = running.getJdbcUrl();
        final var service = serviceFrom(jdbcUrl);
        final var connection = DriverManager.getConnection(jdbcUrl, schema, schemaPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schema);
        }
        final var spec = new JdbcConnectionSpec(
                database.name(), schema, schemaPassword(), jdbcUrl, DatabaseImplementation.ORACLE, schema);
        return new ManagedDatabase(spec, connection, database.expected());
    }

    private static String serviceFrom(final String jdbcUrl) {
        final var idx = jdbcUrl.lastIndexOf('/');
        return idx >= 0 && idx + 1 < jdbcUrl.length() ? jdbcUrl.substring(idx + 1) : "FREEPDB1";
    }
}
