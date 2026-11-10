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

import java.util.List;
import org.junit.jupiter.api.Test;

class BaseDatabaseExtensionDefaultsTest {
    /** Minimal concrete extension: only the abstract members, everything else keeps api defaults. */
    private static class BareExtension extends BaseDatabaseExtension {
        @Override
        public String name() {
            return "BARE";
        }

        @Override
        protected List<Descriptor> findDatabases(final org.junit.jupiter.api.extension.ExtensionContext context) {
            return List.of();
        }

        @Override
        protected ManagedDatabase createDatabase(
                final org.junit.jupiter.api.extension.ExtensionContext context, final Descriptor descriptor) {
            throw new UnsupportedOperationException("not used here");
        }
    }

    @Test
    void seedDatasetHookThrowsByDefault() {
        final var extension = new BareExtension();
        final var exception =
                assertThrows(UnsupportedOperationException.class, () -> extension.seedDataset(null, new DbInit[0]));
        assertEquals(
                BareExtension.class.getName() + " does not implement seedDataset(ManagedDatabase, DbInit[])",
                exception.getMessage());
    }

    @Test
    void verifyDatasetHookThrowsByDefault() {
        final var extension = new BareExtension();
        final var exception = assertThrows(
                UnsupportedOperationException.class, () -> extension.verifyDataset(null, new DbExpected[0]));
        assertEquals(
                BareExtension.class.getName() + " does not implement verifyDataset(ManagedDatabase, DbExpected[])",
                exception.getMessage());
    }

    @Test
    void lifecycleHooksDefaultToNoOp() {
        final var extension = new BareExtension();
        // must not throw
        extension.beforeDatabases(null);
        assertEquals("BARE", extension.name());
    }

    @Test
    void initsForKeepsInitsWithoutDatabaseRestriction() {
        final var unrestricted = TestInits.unrestricted();
        final var filtered =
                BaseDatabaseExtension.initsFor(new DbInit[] {unrestricted}, DatabaseImplementation.current());
        assertEquals(1, filtered.length);
        assertSame(unrestricted, filtered[0]);
    }

    @Test
    void initsForDropsInitRestrictedToOtherDatabases() {
        final var h2Only = TestInits.h2Only();
        final var current = DatabaseImplementation.current();
        final var filtered = BaseDatabaseExtension.initsFor(new DbInit[] {h2Only}, current);
        if (current == DatabaseImplementation.H2) {
            assertEquals(1, filtered.length);
        } else {
            assertEquals(0, filtered.length);
        }
    }

    @Test
    void initsForReturnsSameArrayWhenNothingFiltered() {
        final var inits = new DbInit[] {TestInits.unrestricted(), TestInits.unrestricted()};
        assertSame(inits, BaseDatabaseExtension.initsFor(inits, DatabaseImplementation.current()));
    }

    /** Annotation instances can only come from reflection; build them reflectively once. */
    private static final class TestInits {
        private static DbInit unrestricted() {
            return annotated("datasets/base.yml", new DatabaseImplementation[0]);
        }

        private static DbInit h2Only() {
            return annotated("datasets/h2.yml", new DatabaseImplementation[] {DatabaseImplementation.H2});
        }

        private static DbInit annotated(final String value, final DatabaseImplementation[] supported) {
            final var proxy = java.lang.reflect.Proxy.newProxyInstance(
                    DbInit.class.getClassLoader(),
                    new Class<?>[] {DbInit.class},
                    (handler, method, args) -> switch (method.getName()) {
                        case "value" -> value;
                        case "supportedDatabases" -> supported;
                        case "admin" -> false;
                        case "format" -> DatasetFormat.AUTO;
                        case "operation" -> DatasetOperation.CLEAN_INSERT;
                        case "caseSensitive" -> false;
                        case "annotationType" -> DbInit.class;
                        default -> defaultValue(method.getReturnType());
                    });
            return (DbInit) proxy;
        }

        private static Object defaultValue(final Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type.isPrimitive()) {
                return 0;
            }
            return null;
        }
    }
}
