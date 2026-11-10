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
package io.yupiik.hop.junit.hop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Creates an isolated, per-test Hop working tree under the Maven build directory
 * ({@value #DEFAULT_BUILD_DIR} unless overridden by the {@code project.build.directory} system
 * property) and points the Hop system properties at it, so parallel pipeline/workflow tests never
 * share configuration, audit logs, metadata or temp files.
 *
 * <p>Create an instance and call {@link #prepare(ExtensionContext)} before starting a Hop execution
 * (the {@code HopPipeline} and {@code HopWorkflow} extensions do it automatically at
 * {@code beforeEach}). To control the build directory from Maven, set
 * {@code project.build.directory} through surefire's {@code systemPropertyVariables}.
 */
public class HopTestWorkDirectories {
    /** Default Maven build directory when {@code project.build.directory} is not set. */
    public static final String DEFAULT_BUILD_DIR = "target";

    public HopTestWorkDirectories() {
        // no-op
    }

    /**
     * Prepares the isolated work directories for the given test and installs the Hop system
     * properties. Returns the work directory root.
     *
     * @param context the JUnit extension context of the running test
     * @return the absolute, normalized work directory
     */
    public Path prepare(final ExtensionContext context) throws IOException {
        final var testName = sanitize(context.getRequiredTestClass().getSimpleName() + "_"
                + context.getRequiredTestMethod().getName());
        final var shortTestName = testName.length() > 80 ? testName.substring(0, 80) : testName;
        final var workDirectory = Path.of(
                        System.getProperty("project.build.directory", DEFAULT_BUILD_DIR),
                        "hop-tests-work",
                        shortTestName + "_"
                                + Integer.toUnsignedString(context.getUniqueId().hashCode(), 36))
                .toAbsolutePath()
                .normalize();
        final var homeDirectory = workDirectory.resolve("home");
        final var configDirectory = workDirectory.resolve("config");
        final var auditDirectory = workDirectory.resolve("audit");
        final var metadataDirectory = workDirectory.resolve("metadata");
        final var tempDirectory = workDirectory.resolve("tmp");
        Files.createDirectories(homeDirectory);
        Files.createDirectories(configDirectory);
        Files.createDirectories(auditDirectory);
        Files.createDirectories(metadataDirectory);
        Files.createDirectories(tempDirectory);
        System.setProperty("HOP_HOME", homeDirectory.toString());
        System.setProperty("HOP_CONFIG_FOLDER", configDirectory.toString());
        System.setProperty("HOP_AUDIT_FOLDER", auditDirectory.toString());
        System.setProperty("HOP_METADATA_FOLDER", metadataDirectory.toString());
        System.setProperty("HOP_CONFIG_IN_MEMORY", "Y");
        System.setProperty("java.io.tmpdir", tempDirectory.toString());
        return workDirectory;
    }

    private static String sanitize(final String uniqueId) {
        return uniqueId.replaceAll("[^a-zA-Z0-9._-]+", "_").replaceAll("(^_+|_+$)", "");
    }
}
