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
package io.yupiik.hop.junit.database.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.hop.junit.database.BaseDatabaseExtension;
import io.yupiik.hop.junit.database.DatabaseImplementation;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class DatabaseExtensionRegistryTest {
    private final String currentName = DatabaseImplementation.current().name();

    @Test
    void staticInstanceResolvesCurrentBackendFromClasspath() {
        final var backend = DatabaseExtensionRegistry.INSTANCE.byName(currentName);
        assertNotNull(backend);
        assertEquals(currentName, backend.name());
        // same backend instance returned on repeated lookups (immutable map)
        assertSame(backend, DatabaseExtensionRegistry.INSTANCE.byName(currentName));
    }

    @Test
    void unknownNameFailsFastWithGuidance() {
        final var exception =
                assertThrows(NoSuchElementException.class, () -> DatabaseExtensionRegistry.INSTANCE.byName("DB2"));
        assertEquals(
                true,
                exception.getMessage().contains("No registered database backend named 'DB2'")
                        && exception.getMessage().contains("hop-junit-database-h2")
                        && exception.getMessage().contains("Registered backends"));
    }

    @Test
    void customLoaderPicksUpTestDiscoveredBackends() {
        final var registry = new DatabaseExtensionRegistry(
                ServiceLoader.load(BaseDatabaseExtension.class).stream().map(ServiceLoader.Provider::get));
        final var backend = registry.byName(currentName);
        assertEquals(currentName, backend.name());
    }
}
