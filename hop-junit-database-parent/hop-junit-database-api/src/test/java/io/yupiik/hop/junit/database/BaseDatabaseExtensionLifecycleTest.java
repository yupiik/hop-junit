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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

class BaseDatabaseExtensionLifecycleTest {
    @Test
    void cleansFirstDatabaseWhenSecondCreationFails() throws Exception {
        checkFailure("create", List.of("close:first", "reset:first"));
    }

    @Test
    void cleansAllDatabasesWhenAdminProvisioningFails() throws Exception {
        checkFailure("admin", List.of("close:first", "reset:first", "close:second", "reset:second"));
    }

    @Test
    void cleansAllDatabasesWhenSeedingFails() throws Exception {
        checkFailure("seed", List.of("close:first", "reset:first", "close:second", "reset:second"));
    }

    private void checkFailure(final String stage, final List<String> expected) throws Exception {
        final var failure = new IllegalStateException(stage);
        final var events = new ArrayList<String>();
        final var values = new HashMap<Object, Object>();
        final var store = (ExtensionContext.Store) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ExtensionContext.Store.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "put" -> values.put(args[0], args[1]);
                    case "get" -> values.get(args[0]);
                    case "remove" -> values.remove(args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final var context = (ExtensionContext) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ExtensionContext.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> "test";
                    case "getStore" -> store;
                    case "getExecutionException" -> Optional.of(failure);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final var adminInit = getClass().getDeclaredMethod("adminInit").getAnnotation(DbInit.class);
        final var extension = new BaseDatabaseExtension() {
            @Override
            public String name() {
                return "LIFECYCLE";
            }

            @Override
            protected List<Descriptor> findDatabases(final ExtensionContext context) {
                return List.of(
                        descriptor("first", new DbInit[] {adminInit}, new DbExpected[0], null),
                        descriptor("second", new DbInit[0], new DbExpected[0], null));
            }

            @Override
            protected ManagedDatabase createDatabase(final ExtensionContext context, final Descriptor descriptor) {
                if (stage.equals("create") && descriptor.name().equals("second")) {
                    throw failure;
                }
                final var connection = (Connection) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                            if (method.getName().equals("close")) {
                                events.add("close:" + descriptor.name());
                                return null;
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
                return new ManagedDatabase(
                        new JdbcConnectionSpec(
                                descriptor.name(),
                                "user",
                                "password",
                                "url",
                                DatabaseImplementation.H2,
                                descriptor.name()),
                        connection);
            }

            @Override
            protected void provisionAdmin(
                    final ExtensionContext context,
                    final java.util.Map<String, ManagedDatabase> specs,
                    final DbInit[] inits) {
                if (stage.equals("admin")) {
                    throw failure;
                }
            }

            @Override
            protected void seedDataset(final ManagedDatabase managed, final DbInit[] inits) {
                if (stage.equals("seed")) {
                    throw failure;
                }
            }

            @Override
            protected void resetDatabase(final ExtensionContext context, final ManagedDatabase managed) {
                events.add("reset:" + managed.spec().name());
            }
        };
        assertSame(failure, assertThrows(IllegalStateException.class, () -> extension.beforeEach(context)));
        extension.afterEach(context);
        assertEquals(expected, events);
        extension.afterEach(context);
        assertEquals(expected, events, "cleanup must not run twice");
    }

    @DbInit(value = "unused.sql", admin = true)
    private void adminInit() {}
}
