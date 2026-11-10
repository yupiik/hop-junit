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
package io.yupiik.hop.junit.database.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.JdbcDatabaseContainer;

class TestContainerDatabaseExtensionTest {
    /** Minimal backend: only what image() needs, no real container. */
    private static final class ProbeDatabaseExtension extends TestContainerDatabaseExtension<JdbcDatabaseContainer<?>> {
        static final String DEFAULT = "probe-image:1";

        @Override
        public String name() {
            return "PROBE";
        }

        @Override
        protected String defaultImage() {
            return DEFAULT;
        }

        @Override
        protected JdbcDatabaseContainer<?> container() {
            throw new UnsupportedOperationException("no container in unit test");
        }

        @Override
        protected void ensureSchema(final JdbcDatabaseContainer<?> container, final String schema) {
            // no-op
        }

        @Override
        protected String schemaPassword() {
            return "pwd";
        }

        @Override
        protected List<Descriptor> findDatabases(final org.junit.jupiter.api.extension.ExtensionContext context) {
            return List.of();
        }

        /** Expose the protected logic under test. */
        String resolveImage() {
            return image();
        }
    }

    @AfterEach
    void clearImageProperty() {
        System.clearProperty(ProbeDatabaseExtension.class
                        .getSimpleName()
                        .replace("DatabaseExtension", "")
                        .toLowerCase() + ".image");
    }

    @Test
    void fallsBackToDefaultImageWhenNoPropertySet() {
        final var extension = new ProbeDatabaseExtension();
        assertEquals(ProbeDatabaseExtension.DEFAULT, extension.resolveImage());
    }

    @Test
    void usesClassScopedPropertyDerivedFromSimpleName() {
        // key = class simple name minus "DatabaseExtension", lowercased -> probe.image
        final var key = ProbeDatabaseExtension.class
                .getSimpleName()
                .replace("DatabaseExtension", "")
                .toLowerCase();
        assertEquals("probe", key);
        System.setProperty(key + ".image", "override-image:2");
        final var extension = new ProbeDatabaseExtension();
        assertEquals("override-image:2", extension.resolveImage());
    }

    @Test
    void imageKeyIsSimpleNameNotFullyQualified() {
        // The property key must not contain the package/FQN (no 'io.yupiik...').
        assertTrue(
                ProbeDatabaseExtension.class.getSimpleName().startsWith("Probe"),
                "expected ProbeDatabaseExtension class");
    }
}
