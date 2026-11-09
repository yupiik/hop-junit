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

import static java.util.stream.Collectors.toMap;

import io.yupiik.hop.junit.database.BaseDatabaseExtension;
import io.yupiik.hop.junit.database.DatabaseImplementation;
import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import io.yupiik.hop.junit.database.dataset.DbUnitEngine;
import io.yupiik.hop.junit.database.h2.driver.H2SqlRewriteDriver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The H2 backend: JUnit extension backing {@link H2Database} (and {@link Database} when the selected
 * backend is H2). Creates one in-memory H2 database per named declaration, registers Oracle-emulation
 * SQL shims/aliases when {@link H2DatabaseMode#ORACLE_EXTENDED}, and seeds/verifies datasets through
 * the shared {@link DbUnitEngine}.
 *
 * <p>Registered in {@code META-INF/services/io.yupiik.hop.junit.database.BaseDatabaseExtension} with
 * {@link #name()} = {@code "H2"}, so the {@code @Database} facade resolves it when
 * {@code database.default.implementation=H2}.
 */
public class H2DatabaseExtension extends BaseDatabaseExtension {
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Za-z0-9_]");

    private final H2SqlRewriteDriver rewriteDriver = registeredRewriteDriver();
    private final DbUnitEngine engine = new DbUnitEngine();

    /**
     * Pick the {@link H2SqlRewriteDriver} the consumer registered with DriverManager (subclassing the
     * driver and overriding the rewriter), falling back to the built-in driver when none is present.
     */
    private static H2SqlRewriteDriver registeredRewriteDriver() {
        final var custom = DriverManager.drivers()
                .filter(H2SqlRewriteDriver.class::isInstance)
                .map(H2SqlRewriteDriver.class::cast)
                .filter(it -> it.getClass() != H2SqlRewriteDriver.class)
                .filter(it -> it.acceptsURL("jdbc:testoracleextended:h2:placeholder"))
                .toList();
        if (custom.size() > 1) {
            throw new IllegalStateException("Multiple custom Oracle-to-H2 JDBC drivers registered: " + custom);
        }
        if (!custom.isEmpty()) {
            return custom.getFirst();
        }
        try {
            final var driver = DriverManager.getDriver("jdbc:testoracleextended:h2:placeholder");
            if (driver instanceof H2SqlRewriteDriver rewriteDriver) {
                return rewriteDriver;
            }
        } catch (final SQLException ignored) {
            // no registered rewrite driver - fall back to the built-in
        }
        return new H2SqlRewriteDriver();
    }

    @Override
    public String name() {
        return "H2";
    }

    @Override
    protected List<Descriptor> findDatabases(final ExtensionContext context) {
        final var method = context.getRequiredTestMethod();
        final var descriptors = new ArrayList<>(facadeDescriptors());
        for (final var db : method.getAnnotationsByType(H2Database.class)) {
            descriptors.add(new Descriptor(db.name(), db.init(), db.expected(), db.mode()));
        }
        return descriptors.stream()
                .collect(toMap(Descriptor::name, it -> it, (first, second) -> second, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
    }

    @Override
    protected void seedDataset(final ManagedDatabase managed, final DbInit[] inits) throws Exception {
        engine.seed(managed.connection(), managed.spec().schema(), inits);
    }

    @Override
    protected void verifyDataset(final ManagedDatabase managed, final DbExpected[] expected) throws Exception {
        engine.verify(managed.connection(), managed.spec().schema(), expected);
    }

    @Override
    protected ManagedDatabase createDatabase(final ExtensionContext context, final Descriptor database)
            throws SQLException {
        final var options = resolveMode(database.specificData()).options();
        final var safeName = NON_ALPHANUMERIC.matcher(context.getUniqueId()).replaceAll("_") + "_" + database.name();
        final var suffix = options.isBlank() ? ";DB_CLOSE_DELAY=-1" : ";" + options + ";DB_CLOSE_DELAY=-1";
        final var jdbcUrl = "jdbc:h2:mem:" + safeName + suffix;
        final var connection = DriverManager.getConnection(jdbcUrl, "sa", "");
        final var hopJdbcUrl = resolveMode(database.specificData()) == H2DatabaseMode.ORACLE_EXTENDED
                ? rewriteDriver.wrap(jdbcUrl)
                : jdbcUrl;
        final var spec = new JdbcConnectionSpec(database.name(), "sa", "", hopJdbcUrl, DatabaseImplementation.H2, null);
        if (resolveMode(database.specificData()) == H2DatabaseMode.ORACLE_EXTENDED) {
            registerOracleExtendedShims(connection, H2DatabaseMode.ORACLE_EXTENDED);
            OracleDictionaryEmulation.install(connection);
        }
        return new ManagedDatabase(spec, connection, database.expected());
    }

    /**
     * Registers every public static method of {@link H2OracleExtendedShims} as an H2 SQL alias, so
     * the Oracle-emulation functions resolve. H2's
     * {@code CREATE ALIAS ... FOR "class.method"} resolves them through reflection on the class
     * itself - a JDBC-mechanism requirement, not a framework static-utility API.
     */
    private static void registerOracleExtendedShims(final Connection connection, final H2DatabaseMode mode)
            throws SQLException {
        if (mode != H2DatabaseMode.ORACLE_EXTENDED) {
            return;
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS DBMS_XMLGEN");
            statement.execute(
                    "CREATE ALIAS IF NOT EXISTS DBMS_XMLGEN.GETXML FOR 'io.yupiik.hop.junit.database.h2.driver.OracleXmlFunctions.getXml'");
            for (final var method : H2OracleExtendedShims.class.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                    createAliasIfAbsent(
                            statement, method.getName().toUpperCase(java.util.Locale.ROOT), method.getName());
                }
            }
        }
    }

    private static void createAliasIfAbsent(final Statement statement, final String alias, final String method)
            throws SQLException {
        try {
            statement.execute("CREATE ALIAS IF NOT EXISTS " + alias + " FOR \"" + H2OracleExtendedShims.class.getName()
                    + "." + method + "\"");
        } catch (final SQLException se) {
            if (se.getErrorCode() != 90076) {
                throw se;
            }
        }
    }

    /**
     * Resolves the descriptor-specific data into an {@link H2DatabaseMode}: accepts an
     * {@link H2DatabaseMode} or a mode name ({@code String}, pushed by the {@code core} facade which
     * stays decoupled from the H2 module); defaults to {@link H2DatabaseMode#ORACLE_EXTENDED}.
     */
    private static H2DatabaseMode resolveMode(final Object specificData) {
        if (specificData instanceof H2DatabaseMode mode) {
            return mode;
        }
        if (specificData instanceof String name && !name.isBlank()) {
            return H2DatabaseMode.valueOf(name);
        }
        return H2DatabaseMode.ORACLE_EXTENDED;
    }
}
