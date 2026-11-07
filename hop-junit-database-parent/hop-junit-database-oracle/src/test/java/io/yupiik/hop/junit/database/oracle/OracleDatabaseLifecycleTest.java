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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.database.BaseDatabaseExtension.ManagedDatabase;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Real-Oracle regressions for leftover client sessions and partial setup (requires Docker). */
class OracleDatabaseLifecycleTest {
    @Test
    void repeatedlyDisconnectsLeftoverClientsBeforeRecreatingSchema() throws Exception {
        final var extension = new OracleDatabaseExtension();
        final var clients = new ArrayList<Connection>();
        try {
            for (var iteration = 0; iteration < 12; iteration++) {
                try (final var context = new TestContext("database")) {
                    extension.beforeEach(context.context);
                    final var spec = context.spec("LEFTOVER");
                    for (var client = 0; client < 8; client++) {
                        clients.add(DriverManager.getConnection(spec.jdbcUrl(), spec.username(), spec.password()));
                    }
                    extension.afterEach(context.context);
                    for (final var connection : clients) {
                        assertFalse(connection.isValid(2), "teardown must sever idle clients, not just drop objects");
                        connection.close();
                    }
                    clients.clear();
                    assertThrows(SQLException.class, () -> {
                        try (final var ignored =
                                DriverManager.getConnection(spec.jdbcUrl(), spec.username(), spec.password())) {
                            assertFalse(ignored.isClosed());
                        }
                    });
                }
            }
        } finally {
            for (final var connection : clients) {
                connection.close();
            }
        }
    }

    @Test
    void failedSeedStillClosesConnectionAndDropsSchema() throws Exception {
        final var extension = new OracleDatabaseExtension();
        try (final var context = new TestContext("brokenSeed")) {
            assertThrows(Exception.class, () -> extension.beforeEach(context.context));
            final var managed = context.managed("LEFTOVER");
            context.failure = Optional.of(new IllegalStateException("setup failed"));
            extension.afterEach(context.context);
            assertTrue(managed.connection().isClosed());
            final var spec = managed.spec();
            assertThrows(SQLException.class, () -> {
                try (final var ignored =
                        DriverManager.getConnection(spec.jdbcUrl(), spec.username(), spec.password())) {
                    assertFalse(ignored.isClosed());
                }
            });
        }
    }

    @OracleDatabase(name = "LEFTOVER")
    private void database() {}

    @OracleDatabase(name = "LEFTOVER", init = @DbInit("missing-oracle-lifecycle-seed.sql"))
    private void brokenSeed() {}

    private static final class TestContext implements AutoCloseable {
        private final Map<ExtensionContext.Namespace, Map<Object, Object>> stores = new HashMap<>();
        private Optional<Throwable> failure = Optional.empty();
        private final ExtensionContext context;

        private TestContext(final String methodName) throws Exception {
            final var testMethod = OracleDatabaseLifecycleTest.class.getDeclaredMethod(methodName);
            context = (ExtensionContext) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ExtensionContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> "lifecycle";
                        case "getRequiredTestMethod" -> testMethod;
                        case "getExecutionException" -> failure;
                        case "getStore" -> store((ExtensionContext.Namespace) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private ExtensionContext.Store store(final ExtensionContext.Namespace namespace) {
            final var values = stores.computeIfAbsent(namespace, key -> new HashMap<>());
            return (ExtensionContext.Store) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {ExtensionContext.Store.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "put" -> values.put(args[0], args[1]);
                        case "get" -> values.get(args[0]);
                        case "remove" -> values.remove(args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private JdbcConnectionSpec spec(final String name) {
            return managed(name).spec();
        }

        private ManagedDatabase managed(final String name) {
            @SuppressWarnings("unchecked")
            final var specs = (Map<String, ManagedDatabase>)
                    stores.get(ExtensionContext.Namespace.create(OracleDatabaseExtension.class))
                            .get("lifecycle");
            return specs.get(name);
        }

        @Override
        public void close() {
            for (final var values : stores.values()) {
                for (final var value : values.values()) {
                    if (value instanceof AutoCloseable resource) {
                        try {
                            resource.close();
                        } catch (final Throwable failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                }
            }
        }
    }
}
