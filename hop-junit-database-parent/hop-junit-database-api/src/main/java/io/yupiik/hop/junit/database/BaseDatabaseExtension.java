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
package io.yupiik.hop.junit.database;

import java.sql.Connection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Abstract base shared by the backend database extensions (H2, Oracle) registered through
 * {@link java.util.ServiceLoader} and resolved by the {@code @Database} facade.
 *
 * <p>It implements the common lifecycle: create and initialize each named database (schema +
 * seed), store the connection specs in the JUnit store, verify the
 * {@link DbExpected} post-conditions afterwards (only once the test method passed), and close
 * everything. It also owns the shared {@code @JdbcNamed} parameter resolution into
 * {@link JdbcConnectionSpec}.
 *
 * <p>Subclasses provide: the backend {@link #name()} used for engine selection, which named
 * databases the method declares ({@link #findDatabases}), how to open a physical database for one
 * descriptor ({@link #createDatabase}), and how to seed/verify datasets ({@link #seedDataset} /
 * {@link #verifyDataset}).
 */
public abstract class BaseDatabaseExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    /** A named database declared by a test. */
    public record Descriptor(String name, DbInit[] init, DbExpected[] expected, Object specificData) {
        /**
         * @param name the logical database name ({@code @JdbcNamed} value)
         * @param init the {@code @DbInit} datasets to seed
         * @param expected the {@code @DbExpected} post-conditions to verify
         * @param specificData backend-specific extension data (may be null)
         */
        public Descriptor {}
    }

    /** Public factory mirroring the {@link Descriptor} canonical constructor (keeps call sites terse). */
    public Descriptor descriptor(
            final String name, final DbInit[] init, final DbExpected[] expected, final Object specificData) {
        return new Descriptor(name, init, expected, specificData);
    }

    /** {@code @Database} (facade) descriptors pushed by downstream extensions for the current test. */
    private List<Descriptor> facadeDescriptors = List.of();

    /**
     * The backend name, matching {@link DatabaseImplementation#name()} (for example {@code "H2"} or
     * {@code "ORACLE"}). The {@code @Database} facade selects the registered backend whose
     * {@link #name()} equals {@link DatabaseImplementation#current()}.
     */
    public abstract String name();

    /**
     * (Facade only) Supply the engine-agnostic {@code @Database} descriptors resolved for the
     * current test. Called by downstream extensions before the delegate lifecycle.
     */
    public final BaseDatabaseExtension withDescriptors(final List<Descriptor> descriptors) {
        this.facadeDescriptors = descriptors;
        return this;
    }

    /** The {@code @Database} descriptors pushed by the facade; empty when a concrete annotation is used directly. */
    protected final List<Descriptor> facadeDescriptors() {
        return facadeDescriptors;
    }

    /**
     * A created database: the descriptor-aware spec, the live JDBC connection, and the
     * {@link DbExpected} post-conditions to verify after the test.
     */
    public record ManagedDatabase(JdbcConnectionSpec spec, Connection connection, DbExpected[] expected) {
        /**
         * @param spec the descriptor-aware connection spec of the created database
         * @param connection the live JDBC connection
         * @param expected the {@link DbExpected} post-conditions to verify after the test
         */
        public ManagedDatabase {}

        public ManagedDatabase(final JdbcConnectionSpec spec, final Connection connection) {
            this(spec, connection, new DbExpected[0]);
        }
    }

    @Override
    public final void beforeEach(final ExtensionContext context) throws Exception {
        beforeDatabases(context);
        final var specs = new LinkedHashMap<String, ManagedDatabase>();
        // Register before creating/seeding anything: afterEach must see partial setup too.
        context.getStore(namespace(context)).put(context.getUniqueId(), specs);
        final var database = DatabaseImplementation.current();
        final var descriptors = findDatabases(context);
        for (final var descriptor : descriptors) {
            final var created = createDatabase(context, descriptor);
            specs.put(
                    descriptor.name(),
                    new ManagedDatabase(created.spec(), created.connection(), descriptor.expected()));
        }
        final var adminInits = descriptors.stream()
                .flatMap(d -> Arrays.stream(initsFor(d.init(), database)))
                .filter(DbInit::admin)
                .toArray(DbInit[]::new);
        if (adminInits.length > 0) {
            provisionAdmin(context, specs, adminInits);
        }
        for (final var descriptor : descriptors) {
            final var managed = specs.get(descriptor.name());
            final var all = initsFor(descriptor.init(), database);
            final var normal = Arrays.stream(all).filter(it -> !it.admin()).toArray(DbInit[]::new);
            seedDataset(managed, normal);
        }
    }

    @Override
    public final void afterEach(final ExtensionContext context) throws Exception {
        @SuppressWarnings("unchecked")
        final var specs = (Map<String, ManagedDatabase>)
                context.getStore(namespace(context)).remove(context.getUniqueId());
        if (specs == null) {
            return;
        }
        var first = (Exception) null;
        final var testPassed = context.getExecutionException().isEmpty();
        for (final var managed : specs.values()) {
            Exception failure = null;
            try {
                if (testPassed && managed.expected().length > 0) {
                    verifyDataset(managed, managed.expected());
                }
            } catch (final Exception ex) {
                failure = ex;
            }
            // Always attempt to close and reset the managed database - never skip cleanup even
            // if verification failed, otherwise a lingering schema corrupts the next test.
            try {
                managed.connection().close();
            } catch (final Exception ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
            try {
                resetDatabase(context, managed);
            } catch (final Exception ex) {
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
            if (failure != null) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    public final boolean supportsParameter(
            final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        return parameterContext.isAnnotated(JdbcNamed.class)
                && JdbcConnectionSpec.class.isAssignableFrom(
                        parameterContext.getParameter().getType());
    }

    @Override
    public final Object resolveParameter(
            final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        final var name =
                parameterContext.findAnnotation(JdbcNamed.class).orElseThrow().value();
        @SuppressWarnings("unchecked")
        final var specs = (Map<String, ManagedDatabase>)
                extensionContext.getStore(namespace(extensionContext)).get(extensionContext.getUniqueId());
        if (specs == null || !specs.containsKey(name)) {
            throw new ParameterResolutionException(
                    "No JDBC database named '" + name + "' for " + parameter(parameterContext));
        }
        return specs.get(name).spec();
    }

    /**
     * Seed a managed database with the given (non-admin) {@link DbInit}s before the test. The default
     * throws {@link UnsupportedOperationException}; each backend implements it with its dataset
     * engine (for example DBUnit for both backends).
     *
     * @param managed the created database (spec plus live connection)
     * @param inits the non-admin {@link DbInit}s declared across the matching descriptors
     */
    protected void seedDataset(final ManagedDatabase managed, final DbInit[] inits) throws Exception {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not implement seedDataset(ManagedDatabase, DbInit[])");
    }

    /**
     * Verify the {@link DbExpected} post-conditions of a managed database after the test method has
     * passed. The default throws {@link UnsupportedOperationException}; each backend implements it
     * with its dataset engine (for example DBUnit for both backends).
     *
     * @param managed the created database (spec plus live connection)
     * @param expected the {@link DbExpected}s declared on the matching descriptor
     */
    protected void verifyDataset(final ManagedDatabase managed, final DbExpected[] expected) throws Exception {
        throw new UnsupportedOperationException(
                getClass().getName() + " does not implement verifyDataset(ManagedDatabase, DbExpected[])");
    }

    /**
     * Hook invoked once per test before the named databases are created. Defaults to a no-op.
     */
    protected void beforeDatabases(final ExtensionContext context) {
        // no-op by default
    }

    /**
     * Hook invoked once per managed database right after its connection is closed in
     * {@code afterEach}. Defaults to a no-op; H2 gets a fresh in-memory database per test so needs
     * nothing, while the Testcontainers-backed extensions drop their persistent schema here so the
     * next test method starts from a clean database (mirroring H2's fresh-per-test semantics).
     */
    protected void resetDatabase(final ExtensionContext context, final ManagedDatabase managed) throws Exception {
        // no-op by default
    }

    /** Ordered, de-duplicated (by name, last wins) list of named databases the method declares. */
    protected abstract List<Descriptor> findDatabases(ExtensionContext context);

    /** Open a physical database for a declared descriptor and return its spec + live connection. */
    protected abstract ManagedDatabase createDatabase(ExtensionContext context, Descriptor descriptor) throws Exception;

    private ExtensionContext.Namespace namespace(final ExtensionContext context) {
        return ExtensionContext.Namespace.create(getClass());
    }

    /**
     * Hook invoked once per test (after all named databases are created, before any seeding) to run
     * the {@link DbInit#admin()} schema scripts. Defaults to seeding each managed database's own
     * connection with the admin scripts, which is correct for H2 (no separate admin principal). The
     * Oracle backend overrides this to execute the scripts through the shared admin connection.
     *
     * @param context the JUnit extension context
     * @param specs every named database created for the current test, keyed by descriptor name
     * @param admin the admin-marked {@link DbInit}s declared across all descriptors
     */
    protected void provisionAdmin(
            final ExtensionContext context, final Map<String, ManagedDatabase> specs, final DbInit[] admin)
            throws Exception {
        for (final var managed : specs.values()) {
            seedDataset(managed, admin);
        }
    }

    /** Filter the declared {@link DbInit}s to those supported by the current database implementation. */
    protected static DbInit[] initsFor(final DbInit[] inits, final DatabaseImplementation database) {
        final var supported = Arrays.stream(inits)
                .filter(it -> it.supportedDatabases().length == 0
                        || Arrays.asList(it.supportedDatabases()).contains(database))
                .toArray(DbInit[]::new);
        return inits.length == supported.length ? inits : supported;
    }

    private static String parameter(final ParameterContext parameterContext) {
        final var parameter = parameterContext.getParameter();
        return parameter.getDeclaringExecutable() + " parameter " + parameter.getName();
    }
}
