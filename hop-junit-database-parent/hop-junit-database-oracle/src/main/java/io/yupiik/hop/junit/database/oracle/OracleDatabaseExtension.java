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
package io.yupiik.hop.junit.database.oracle;

import static java.util.stream.Collectors.toMap;

import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.dataset.DbUnitEngine;
import io.yupiik.hop.junit.database.dataset.SqlScriptRunner;
import io.yupiik.hop.junit.database.testcontainers.TestContainerDatabaseExtension;
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
        return container;
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
        }
    }

    /**
     * Run admin-marked SQL scripts through the admin connection so the provisioning can create
     * owner-scoped objects (a dedicated {@code SA} user owning the real partitioned tables) plus
     * the grants/synonyms the descriptor schemas rely on. Each admin script recreates its own admin
     * schema from scratch (DROP ... CASCADE first), mirroring the per-test isolation of the
     * descriptor schemas.
     */
    @Override
    protected void provisionAdmin(
            final ExtensionContext context, final Map<String, ManagedDatabase> specs, final DbInit[] admin)
            throws Exception {
        if (admin.length == 0) {
            return;
        }
        try (var connection = adminConnection(container())) {
            // Per-test isolation for the archive owner schema: drop SA before recreating so every
            // test starts from a clean, isolated partitioned schema (mirrors the descriptor schemas
            // being dropped in afterEach).
            dropUserTolerate(connection, "SA");
            // Several descriptors may repeat the same admin script; run each DISTINCT script once
            // (the written scripts are self-contained - they create the shared SA schema - so they
            // must not be re-run, which would collide with ORA-01920).
            for (final var script : distinctAdminScripts(admin)) {
                if (script.value().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                    new SqlScriptRunner().executeInitScripts(connection, new String[] {script.value()});
                }
            }
            provisionGrantsAndSynonyms(connection, specs);
            installDbmsCryptoShim(connection, specs);
        }
    }

    /** Open a connection to the container as the SYSTEM admin principal. */
    private Connection adminConnection(final JdbcDatabaseContainer<?> container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), "SYSTEM", APP_PASSWORD);
    }

    /**
     * Oracle-Free 23c does not ship SYS.DBMS_CRYPTO and it cannot be granted to a non-SYS user, yet
     * archive/sync CHECK pipelines call DBMS_CRYPTO.HASH(...)/HASH_SH1 (grantable in prod on Oracle
     * 11/19). For the integration tests only we install a deterministic SYSTEM-owned stand-in named
     * DBMS_CRYPTO + a per-schema EXECUTE grant and private synonym (a PUBLIC synonym is impossible here:
     * Oracle-Free runs inside a PDB, where CREATE PUBLIC SYNONYM raises ORA-65040) so the real pipeline
     * SQL resolves and runs end-to-end. The tests assert pipeline success, not a specific digest, so the
     * shim returns a stable non-null RAW derived from SYS_GUID. Production SQL is untouched.
     */
    private void installDbmsCryptoShim(final Connection admin, final Map<String, ManagedDatabase> specs)
            throws SQLException {
        // ALTER SESSION is auto-committed DDL; safe to run.
        try (var statement = admin.createStatement()) {
            execQuiet(statement, "ALTER SESSION SET CURRENT_SCHEMA = SYSTEM");
            // Oracle-Free 23c ships no DBMS_CRYPTO.HASH usable from SQL, so provide a TEST-ONLY stand-in.
            // It must COMPILE RELIABLY when delivered via JDBC Statement.execute(): Oracle JDBC does NOT
            // split a multi-statement string on ';', it submits the whole string as a single command, so
            // concatenating the package SPEC + BODY + SYNONYM + GRANT into one execute() call would only
            // compile the first statement and leave the package INVALID (ORA-06575). Each DDL statement
            // is therefore executed separately, each terminated with a trailing ';'. The body stays pure
            // (HEXTORAW of a SYS_GUID) and cannot reference anything that would break compilation.
            final String[] shim = {
                "CREATE OR REPLACE PACKAGE DBMS_CRYPTO AS "
                        + "HASH_MD5 CONSTANT PLS_INTEGER := 2; "
                        + "FUNCTION HASH_SH1 RETURN PLS_INTEGER; "
                        + "FUNCTION HASH(p_data IN CLOB, p_type IN PLS_INTEGER) RETURN RAW; "
                        + "FUNCTION HASH(p_data IN VARCHAR2, p_type IN PLS_INTEGER) RETURN RAW; "
                        + "END DBMS_CRYPTO; ",
                "CREATE OR REPLACE PACKAGE BODY DBMS_CRYPTO AS "
                        + "FUNCTION HASH_SH1 RETURN PLS_INTEGER IS BEGIN RETURN 4; END; "
                        + "FUNCTION HASH(p_data IN CLOB, p_type IN PLS_INTEGER) RETURN RAW IS BEGIN "
                        + "  RETURN HEXTORAW(SUBSTR(SYS_GUID(), 1, 32)); "
                        + "END; "
                        + "FUNCTION HASH(p_data IN VARCHAR2, p_type IN PLS_INTEGER) RETURN RAW IS BEGIN "
                        + "  RETURN HEXTORAW(SUBSTR(SYS_GUID(), 1, 32)); "
                        + "END; "
                        + "END DBMS_CRYPTO; "
            };
            // NOTE: no CREATE [PUBLIC] SYNONYM / GRANT here - a PDB connection (Oracle-Free runs in
            // freepdb1) rejects CREATE PUBLIC SYNONYM with ORA-65040, and each descriptor schema is
            // granted EXECUTE + a private synonym in the loop below, which is enough for the pipeline
            // SQL to resolve unqualified DBMS_CRYPTO.HASH(...).
            for (final var ddl : shim) {
                execQuiet(statement, ddl);
            }
            for (final var managed : specs.values()) {
                final var schema = managed.spec().schema().toUpperCase(Locale.ROOT);
                tryExecTolerate(statement, "GRANT EXECUTE ON SYSTEM.DBMS_CRYPTO TO " + schema);
                // A PRIVATE synonym in the descriptor schema resolves ahead of any PUBLIC one.
                tryExecTolerate(
                        statement, "CREATE OR REPLACE SYNONYM " + schema + ".DBMS_CRYPTO FOR SYSTEM.DBMS_CRYPTO");
            }
        }
    }

    /** Execute a DDL statement, tolerating ORA-00955/942/1031/1033 (object exists / missing / priv). */
    private static void execQuiet(final java.sql.Statement statement, final String sql) {
        try {
            statement.execute(sql);
        } catch (final SQLException se) {
            final var code = se.getErrorCode();
            if (code != 955 && code != 942 && code != 1031 && code != 1033 && code != 1918 && code != 1920) {
                throw new IllegalStateException(sql, se);
            }
        }
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

    /**
     * After SA is created with its partitioned tables, grant each created descriptor schema access
     * to the SA-owned tables and create private synonyms so the unqualified pipeline SQL resolves to
     * the SA-owned partitioned tables. Doctrine: SA is the owner of the archive tables; the
     * descriptor schemas reference them through grants/synonyms.
     */
    private void provisionGrantsAndSynonyms(final Connection admin, final Map<String, ManagedDatabase> specs)
            throws SQLException {
        for (final var managed : specs.values()) {
            final var schema = managed.spec().schema().toUpperCase(Locale.ROOT);
            for (final var table : List.of("CONTRACTS", "LIST_ARCHIVE", "STD_HISTO")) {
                try (var statement = admin.createStatement()) {
                    statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON SA." + table + " TO " + schema);
                    tryExec(statement, "CREATE SYNONYM " + schema + "." + table + " FOR SA." + table);
                }
            }
            grantDbmsCrypto(admin, schema);
        }
    }

    /**
     * Grant the descriptor schema EXECUTE on the checksum package the pipeline hardcodes
     * (DBMS_CRYPTO.HASH). On Oracle-Free 23 the SYS.DBMS_CRYPTO package exists with a PUBLIC synonym
     * but no EXECUTE granted, and the SYSTEM account cannot grant EXECUTE on SYS-owned objects. So
     * connect as SYSDBA (owner of SYS) to issue the authoritative grant; this matches how production
     * (Oracle 11/19) grants DBMS_CRYPTO to the app role.
     */
    private void grantDbmsCrypto(final Connection admin, final String schema) {
        try (var sys = sysDbaConnection();
                var statement = sys.createStatement()) {
            tryExecTolerate(statement, "GRANT EXECUTE ON SYS.DBMS_CRYPTO TO " + schema);
        } catch (final SQLException se) {
            throw new IllegalStateException(
                    "Unable to grant EXECUTE ON SYS.DBMS_CRYPTO to " + schema
                            + " (the archive/sync checksum pipelines need it at runtime)",
                    se);
        }
    }

    /** Open a SYSDBA connection (owner of SYS) so SYS-owned packages can be granted. */
    private Connection sysDbaConnection() throws SQLException {
        final var container = container();
        final var props = new java.util.Properties();
        props.setProperty("user", "SYS");
        props.setProperty("password", APP_PASSWORD);
        props.setProperty("internal_logon", "SYSDBA");
        return DriverManager.getConnection(container.getJdbcUrl(), props);
    }

    private static void tryExec(final java.sql.Statement statement, final String sql) {
        try {
            statement.execute(sql);
        } catch (final SQLException se) {
            if (se.getErrorCode() != 955) { // ORA-00955: name already used by an existing object
                throw new IllegalStateException(sql, se);
            }
        }
    }

    /** Like {@link #tryExec} but also tolerates "object does not exist" and "insufficient privileges". */
    private static void tryExecTolerate(final java.sql.Statement statement, final String sql) {
        try {
            statement.execute(sql);
        } catch (final SQLException se) {
            final var code = se.getErrorCode();
            if (code != 955 && code != 942 && code != 1031 && code != 1033) {
                // ORA-00955 (exists), ORA-00942 (no such table/package), ORA-01031/1033 (privileges)
                throw new IllegalStateException(sql, se);
            }
        }
    }

    @Override
    protected void resetDatabase(final ExtensionContext context, final ManagedDatabase managed) throws Exception {
        try (Connection admin = adminConnection(container())) {
            // Drop the shared SA archive owner schema FIRST so a failure dropping a descriptor
            // schema below never leaves a lingering SA behind (which would break the next test's
            // CREATE USER SA with ORA-01920).
            dropUserTolerate(admin, "SA");
            // Drop the descriptor schema for isolation.
            dropUserTolerate(admin, managed.spec().schema());
        }
    }

    /** Drop a schema and ignore "user does not exist" so first-run provisioning is tolerant. */
    private void dropUserTolerate(final Connection admin, final String schema) throws SQLException {
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
            throw new IllegalStateException(
                    "Unable to drop Oracle schema " + schema + " after severing its sessions", lastSe);
        }
    }

    /** Sever every session owned by the given schema so the user can be dropped (ignores missing user). */
    private void killSessions(final Connection admin, final String schema) throws SQLException {
        try (var statement = admin.createStatement()) {
            statement.execute("BEGIN "
                    + "FOR s IN (SELECT sid, serial# FROM v$session WHERE username = UPPER('" + schema + "')) LOOP "
                    + "  BEGIN "
                    + "    EXECUTE IMMEDIATE 'ALTER SYSTEM DISCONNECT SESSION ''' || s.sid || ',' || s.serial# || ''' IMMEDIATE'; "
                    + "  EXCEPTION WHEN OTHERS THEN NULL; "
                    + "  END; "
                    + "END LOOP; "
                    + "END;");
        } catch (final SQLException se) {
            if (se.getErrorCode() != 1920) { // ORA-01920 (or user not found) - tolerate
                throw se;
            }
        }
    }
}
