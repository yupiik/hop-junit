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

import io.yupiik.hop.junit.database.BaseDatabaseExtension;
import io.yupiik.hop.junit.database.DatabaseImplementation;
import java.util.List;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Test-only backend registered for the {@code core} test classpath through a
 * {@code META-INF/services} entry. It always resolves to the current
 * {@link DatabaseImplementation}, so the registry tests can exercise discovery without depending on
 * the concrete H2/Oracle backend modules.
 */
public class FakeBackend extends BaseDatabaseExtension {
    @Override
    public String name() {
        return DatabaseImplementation.current().name();
    }

    @Override
    protected List<Descriptor> findDatabases(final ExtensionContext context) {
        return List.of();
    }

    @Override
    protected ManagedDatabase createDatabase(final ExtensionContext context, final Descriptor descriptor) {
        throw new UnsupportedOperationException("Fake backend never creates databases");
    }
}
