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
package io.yupiik.hop.junit.database.core;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import io.yupiik.hop.junit.database.BaseDatabaseExtension;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.stream.Stream;

/**
 * Resolves the {@link BaseDatabaseExtension} backend registered through {@link java.util.ServiceLoader}
 * whose {@link BaseDatabaseExtension#name()} matches {@link io.yupiik.hop.junit.database.DatabaseImplementation#current()}.
 *
 * <p>The {@link ServiceLoader} is created once, statically, so the backend registry is scanned a
 * single time per classloader. The lookup itself is an error-free, instance method - creating the
 * registry never touches any concrete backend type, so the backend jars may be absent from the
 * classpath until the facade actually needs them.
 */
public class DatabaseExtensionRegistry {
    /** The bootstrapped backend registry (safe to share; lookups are cheap map hits). */
    public static final DatabaseExtensionRegistry INSTANCE = new DatabaseExtensionRegistry(
            ServiceLoader.load(BaseDatabaseExtension.class).stream().map(ServiceLoader.Provider::get));

    private final Map<String, BaseDatabaseExtension> backends;

    /**
     * Build a registry from a ServiceLoader. Backends are materialized eagerly at construction time:
     * each discovered implementation must expose a no-arg constructor so ServiceLoader can instantiate it.
     */
    public DatabaseExtensionRegistry(final Stream<BaseDatabaseExtension> loader) {
        this.backends = loader.collect(toMap(BaseDatabaseExtension::name, identity()));
    }

    /**
     * Returns the backend whose {@link BaseDatabaseExtension#name()} equals the requested name.
     *
     * @param name the backend name, i.e. {@link io.yupiik.hop.junit.database.DatabaseImplementation#current()} (for example {@code "H2"})
     * @throws NoSuchElementException when no matching backend is registered, with the classpath
     *     guidance to add the corresponding backend module
     */
    public BaseDatabaseExtension byName(final String name) {
        final var backend = backends.get(name);
        if (backend == null) {
            throw new NoSuchElementException("No registered database backend named '" + name + "'. "
                    + "Add the matching backend module to the test classpath: "
                    + "add hop-junit-database-h2 (and set database.default.implementation=H2) "
                    + "or hop-junit-database-oracle (and set database.default.implementation=ORACLE). "
                    + "Registered backends: " + backends.keySet());
        }
        return backend;
    }
}
