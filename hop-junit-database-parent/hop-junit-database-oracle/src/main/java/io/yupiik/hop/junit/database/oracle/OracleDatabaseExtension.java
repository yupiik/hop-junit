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
package io.yupiik.hop.junit.database.oracle;

import static java.util.stream.Collectors.toMap;

import io.yupiik.hop.junit.database.DatabaseImplementation;
import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import io.yupiik.hop.junit.database.dataset.DbUnitEngine;
import io.yupiik.hop.junit.database.dataset.SqlScriptRunner;
import io.yupiik.hop.junit.database.testcontainers.TestContainerDatabaseExtension;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The Oracle {@code @OracleDatabase} (and {@code @Database}-when-Oracle) JUnit extension. This is the
 * concrete Testcontainers-backed extension: it resolves the {@code @OracleDatabase}/{@code @Database}
 * descriptors, holds the Oracle app + schema credentials, maintains the single shared
 * {@link OracleContainer}, provisions per-schema Oracle users, and seeds/verifies datasets through
 * the shared {@link DbUnitEngine}.
 *
 * <p>Registered in {@code META-INF/services/io.yupiik.hop.junit.database.BaseDatabaseExtension} with
 * {@link #name()} = {@code "ORACLE"}. Downstream consumers add this module inside their own
 * {@code -Poracle} Maven profile and set {@code database.default.implementation=ORACLE}.
 */
public class OracleDatabaseExtension extends TestContainerDatabaseExtension<OracleContainer> {
    /** Oracle 23ai Free, slim faststart, pinned by digest for build reproducibility. */
    private static final String DEFAULT_IMAGE =
            "gvenzl/oracle-free:23-slim-faststart@sha256:f5ff19033860d662c821cb04eb10483fa94f14f78eae252d054291ea07028093";

    private static final String APP_USER = "ora_test";
    private static final String APP_PASSWORD = "OraTest123!";
    private static final String SCHEMA_PASSWORD = "OraSchema123!";

    private final DbUnitEngine engine = new DbUnitEngine();

    private static volatile JdbcDatabaseContainer<?> shared;

    private static final ExtensionContext.Namespace LIFECYCLE =
            ExtensionContext.Namespace.create(OracleDatabaseExtension.class, "lifecycle");

    @Override
    protected void beforeDatabases(final ExtensionContext context) {
        try {
            // Reserve cleanup access before test code can exhaust the listener's process slots.
            context.getStore(LIFECYCLE).put(context.getUniqueId(), new OracleLifecycle(adminConnection(container())));
        } catch (final SQLException se) {
            throw new IllegalStateException("Unable to open Oracle lifecycle connection", se);
        }
    }

    private OracleLifecycle lifecycle(final ExtensionContext context) {
        return context.getStore(LIFECYCLE).get(context.getUniqueId(), OracleLifecycle.class);
    }

    @Override
    protected ManagedDatabase createDatabase(final ExtensionContext context, final Descriptor descriptor)
            throws Exception {
        final var lifecycle = lifecycle(context);
        final var schema = descriptor.name().toUpperCase(Locale.ROOT);
        // Track even failed CREATE/GRANT/connect operations for store-close cleanup.
        lifecycle.schemas.add(schema);
        dropUserTolerate(lifecycle.admin, schema);
        createSchema(lifecycle.admin, schema);
        final var jdbcUrl = container().getJdbcUrl();
        final var connection = DriverManager.getConnection(jdbcUrl, schema, SCHEMA_PASSWORD);
        try {
            try (final var statement = connection.createStatement()) {
                statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + schema);
            }
            return new ManagedDatabase(
                    new JdbcConnectionSpec(
                            descriptor.name(), schema, SCHEMA_PASSWORD, jdbcUrl, DatabaseImplementation.ORACLE, schema),
                    connection,
                    descriptor.expected());
        } catch (final Exception failure) {
            try {
                connection.close();
            } catch (final SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private final class OracleLifecycle implements AutoCloseable {
        private final Connection admin;
        private final LinkedHashSet<String> schemas = new LinkedHashSet<>();

        private OracleLifecycle(final Connection admin) {
            this.admin = admin;
        }

        @Override
        public void close() throws SQLException {
            // Also runs when beforeEach failed before returning a ManagedDatabase.
            try (admin) {
                SQLException failure = null;
                for (final var schema : schemas) {
                    try {
                        dropUserTolerate(admin, schema);
                    } catch (final SQLException se) {
                        if (failure == null) {
                            failure = se;
                        } else {
                            failure.addSuppressed(se);
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        }
    }

    @Override
    public String name() {
        return "ORACLE";
    }

    @Override
    protected String defaultImage() {
        return DEFAULT_IMAGE;
    }

    @Override
    protected String schemaPassword() {
        return SCHEMA_PASSWORD;
    }

    @Override
    protected OracleContainer container() {
        final var local = shared;
        if (local == null) {
            synchronized (OracleDatabaseExtension.class) {
                if (shared == null) {
                    shared = newContainer();
                }
            }
        }
        return (OracleContainer) shared;
    }

    private OracleContainer newContainer() {
        final var resolved = DockerImageName.parse(image()).asCompatibleSubstituteFor("gvenzl/oracle-free");
        final var container = new OracleContainer(resolved)
                .withUsername(APP_USER)
                .withPassword(APP_PASSWORD)
                .withStartupTimeout(Duration.ofMinutes(10));
        container.start();
        try {
            initializeCapacity(container);
            initializeCryptoGrant(container);
            awaitReady(container);
        } catch (final RuntimeException failure) {
            try {
                container.stop();
            } catch (final RuntimeException stopFailure) {
                failure.addSuppressed(stopFailure);
            }
            throw failure;
        }
        return container;
    }

    private void initializeCapacity(final OracleContainer container) {
        // The pinned image uses 200 processes, about 80 of which are Oracle's own.
        // Dedicated clients need headroom while disconnected server processes are reaped.
        final var output = executeSysSql(container, """
                SET SERVEROUTPUT ON
                DECLARE
                  process_limit NUMBER;
                BEGIN
                  SELECT TO_NUMBER(value) INTO process_limit FROM v$parameter WHERE name = 'processes';
                  IF process_limit < 500 THEN
                    EXECUTE IMMEDIATE 'ALTER SYSTEM SET processes = 500 SCOPE = SPFILE';
                    DBMS_OUTPUT.PUT_LINE('HOP_RESTART_REQUIRED');
                  END IF;
                END;
                /
                """);
        if (output.contains("HOP_RESTART_REQUIRED")) {
            executeSysSql(container, "SHUTDOWN IMMEDIATE;\nSTARTUP;\n");
        }
        executeSysSql(container, "ALTER SYSTEM REGISTER;\n");
    }

    private String executeSysSql(final OracleContainer container, final String sql) {
        try {
            final var result = container.execInContainer("sh", "-c", """
                    sqlplus -s / as sysdba <<'SQL'
                    WHENEVER OSERROR EXIT FAILURE
                    WHENEVER SQLERROR EXIT FAILURE
                    %s
                    EXIT SUCCESS
                    SQL
                    """.formatted(sql));
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "Unable to initialize Oracle capacity: " + result.getStdout() + result.getStderr());
            }
            return result.getStdout();
        } catch (final IOException ioe) {
            throw new IllegalStateException("Unable to initialize Oracle capacity", ioe);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted initializing Oracle capacity", ie);
        }
    }

    private void awaitReady(final OracleContainer container) {
        final var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        SQLException failure;
        do {
            try (final var connection = adminConnection(container);
                    final var statement = connection.createStatement();
                    final var rows = statement.executeQuery("SELECT 1 FROM dual")) {
                if (rows.next() && rows.getInt(1) == 1) {
                    return;
                }
                throw new SQLException("Oracle readiness query returned no result");
            } catch (final SQLException se) {
                failure = se;
            }
            try {
                Thread.sleep(200);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for Oracle readiness", ie);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Oracle PDB did not become ready", failure);
    }

    private void initializeCryptoGrant(final OracleContainer container) {
        try {
            // SYSTEM cannot grant access to SYS packages without an explicit grant option.
            // Bootstrap once as SYS in the PDB, before publishing the shared container.
            final var result = container.execInContainer("sh", "-c", """
                    sqlplus -s / as sysdba <<'SQL'
                    WHENEVER OSERROR EXIT FAILURE
                    WHENEVER SQLERROR EXIT FAILURE
                    ALTER SESSION SET CONTAINER = FREEPDB1;
                    GRANT EXECUTE ON SYS.DBMS_CRYPTO TO SYSTEM WITH GRANT OPTION;
                    EXIT SUCCESS
                    SQL
                    """);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "Unable to initialize Oracle DBMS_CRYPTO grant: " + result.getStdout() + result.getStderr());
            }
        } catch (final IOException ioe) {
            throw new IllegalStateException("Unable to initialize Oracle DBMS_CRYPTO grant", ioe);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted initializing Oracle DBMS_CRYPTO grant", ie);
        }
    }

    @Override
    protected List<Descriptor> findDatabases(final ExtensionContext context) {
        final var method = context.getRequiredTestMethod();
        final var descriptors = new ArrayList<>(facadeDescriptors());
        for (final var db : method.getAnnotationsByType(OracleDatabase.class)) {
            descriptors.add(descriptor(db.name(), db.init(), db.expected(), null));
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
    protected void ensureSchema(final OracleContainer container, final String schema) {
        try (Connection admin = adminConnection(container)) {
            // Guaranteed-fresh per-test isolation: drop any lingering recreation of the descriptor
            // schema from a previous test (and kill its sessions) before (re)creating it, so every
            // test starts from a clean schema regardless of prior afterEach success.
            dropUserTolerate(admin, schema);
            createSchema(admin, schema);
        } catch (final SQLException se) {
            throw new IllegalStateException("Unable to ensure Oracle schema " + schema, se);
        }
    }

    private void createSchema(final Connection admin, final String schema) throws SQLException {
        try (var statement = admin.createStatement()) {
            statement.execute("CREATE USER " + schema + " IDENTIFIED BY \"" + SCHEMA_PASSWORD + "\"");
            statement.execute("GRANT CONNECT, RESOURCE TO " + schema);
            statement.execute("ALTER USER " + schema + " QUOTA UNLIMITED ON USERS");
            statement.execute("GRANT CREATE VIEW TO " + schema);
            statement.execute("GRANT EXECUTE ON SYS.DBMS_CRYPTO TO " + schema);
        }
    }

    /**
     * Run each distinct admin-marked SQL script through the admin connection, in declaration order,
     * then invoke every registered {@link OracleAdminProvisioner} (ServiceLoader) on the same
     * SYSTEM connection. When no admin script is declared, neither scripts nor provisioners run.
     */
    @Override
    protected void provisionAdmin(
            final ExtensionContext context, final Map<String, ManagedDatabase> specs, final DbInit[] admin)
            throws Exception {
        if (admin.length == 0) {
            return;
        }
        final var connection = lifecycle(context).admin;
        for (final var script : distinctAdminScripts(admin)) {
            if (script.value().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                new SqlScriptRunner().executeInitScripts(connection, new String[] {script.value()});
            }
        }
        for (final var provisioner : ServiceLoader.load(OracleAdminProvisioner.class)) {
            provisioner.provision(connection, specs);
        }
    }

    /** Open a connection to the container as the SYSTEM admin principal. */
    private Connection adminConnection(final JdbcDatabaseContainer<?> container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), "SYSTEM", APP_PASSWORD);
    }

    /** De-duplicate admin {@link DbInit}s by resource path, keeping the first occurrence order. */
    private DbInit[] distinctAdminScripts(final DbInit[] admin) {
        final var seen = new LinkedHashSet<String>();
        final var unique = new ArrayList<DbInit>();
        for (final var script : admin) {
            if (seen.add(script.value())) {
                unique.add(script);
            }
        }
        return unique.toArray(DbInit[]::new);
    }

    @Override
    protected void resetDatabase(final ExtensionContext context, final ManagedDatabase managed) throws Exception {
        final var lifecycle = lifecycle(context);
        dropUserTolerate(lifecycle.admin, managed.spec().schema());
        lifecycle.schemas.remove(managed.spec().schema());
    }

    /** Drop a schema and ignore "user does not exist" so first-run provisioning is tolerant. */
    private void dropUserTolerate(final Connection admin, final String schema) throws SQLException {
        try (final var statement = admin.createStatement()) {
            // Prevent pools or still-stopping Hop workers from reconnecting during teardown.
            statement.execute("ALTER USER " + schema + " ACCOUNT LOCK");
        } catch (final SQLException se) {
            if (se.getErrorCode() == 1918) {
                return;
            }
            throw se;
        }
        killSessions(admin, schema);
        var lastSe = (java.sql.SQLException) null;
        try (var statement = admin.createStatement()) {
            // Sessions may still be terminating, so retry the drop briefly against ORA-01940.
            for (var attempt = 0; attempt < 10; attempt++) {
                try {
                    statement.execute("DROP USER " + schema + " CASCADE");
                    return;
                } catch (final SQLException se) {
                    lastSe = se;
                    if (se.getErrorCode() == 1918) {
                        return; // ORA-01918: user does not exist - already clean
                    }
                    if (se.getErrorCode() != 1940) {
                        throw se; // ORA-01940: cannot drop connected user - retry
                    }
                    killSessions(admin, schema);
                    try {
                        Thread.sleep(500L);
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw se;
                    }
                }
            }
        }
        if (lastSe != null && lastSe.getErrorCode() == 1940) {
            // Give up loudly instead of silently leaking the user into the next test.
            throw new SQLException("Unable to drop Oracle schema " + schema + " after severing its sessions", lastSe);
        }
    }

    /** Sever every session owned by the given schema so the user can be dropped (ignores missing user). */
    private void killSessions(final Connection admin, final String schema) throws SQLException {
        try (var statement = admin.createStatement()) {
            statement.execute("BEGIN "
                    + "FOR s IN (SELECT sid, serial# FROM v$session WHERE username = UPPER('" + schema + "')) LOOP "
                    + "  BEGIN "
                    + "    EXECUTE IMMEDIATE 'ALTER SYSTEM DISCONNECT SESSION ''' || s.sid || ',' || s.serial# || ''' IMMEDIATE'; "
                    + "  EXCEPTION WHEN OTHERS THEN "
                    + "    IF SQLCODE NOT IN (-30, -31) THEN RAISE; END IF; "
                    + "  END; "
                    + "END LOOP; "
                    + "END;");
        }
    }
}
