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

import io.yupiik.hop.junit.database.BaseDatabaseExtension;
import io.yupiik.hop.junit.database.DatabaseImplementation;
import java.util.Arrays;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Runtime engine-selecting facade used by {@link Database}. It creates a single
 * {@link BaseDatabaseExtension} matching {@link DatabaseImplementation#current()} (H2 by default,
 * Oracle under the downstream {@code -Poracle} profile) and delegates every lifecycle and
 * parameter-resolution callback to it. The same {@code @Database}-annotated test thus runs against
 * either backend without edits.
 *
 * <p>The delegate is resolved through the {@link java.util.ServiceLoader}-registered backends via
 * {@link DatabaseExtensionRegistry}. It owns the engine-agnostic {@code @Database} resolution: it
 * scans the test method's {@link Database} annotations and pushes the resulting
 * {@link BaseDatabaseExtension.Descriptor}s onto the delegate via {@link BaseDatabaseExtension#withDescriptors}.
 * It also owns the H2-mode default configuration (from the {@code database.default.h2.mode} system
 * property, default the H2 module ORACLE_EXTENDED mode), which is only read when the selected
 * backend is H2.
 */
public class DatabaseExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private final BaseDatabaseExtension delegate;

    public DatabaseExtension() {
        delegate = DatabaseExtensionRegistry.INSTANCE.byName(
                DatabaseImplementation.current().name());
    }

    @Override
    public void beforeEach(final ExtensionContext context) throws Exception {
        final var mode = modeFor();
        delegate.withDescriptors(Arrays.stream(context.getRequiredTestMethod().getAnnotationsByType(Database.class))
                        .map(db -> delegate.descriptor(db.name(), db.init(), db.expected(), mode))
                        .toList())
                .beforeEach(context);
    }

    @Override
    public void afterEach(final ExtensionContext context) throws Exception {
        delegate.afterEach(context);
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        return delegate.supportsParameter(parameterContext, extensionContext);
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        return delegate.resolveParameter(parameterContext, extensionContext);
    }

    /**
     * The H2 mode name applied to {@code @Database} descriptors when the selected backend is H2,
     * read from the {@code database.default.h2.mode} system property (default ORACLE_EXTENDED). Only
     * read in the H2 path so the Oracle profile never touches the property. Passed as a plain string
     * so core stays decoupled from the H2 module.
     */
    private Object h2Mode() {
        return System.getProperty("database.default.h2.mode", "ORACLE_EXTENDED");
    }

    /** The mode to stamp on a {@code @Database} descriptor: the H2-mode for H2, otherwise null. */
    private Object modeFor() {
        return "H2".equals(delegate.name()) ? h2Mode() : null;
    }
}
