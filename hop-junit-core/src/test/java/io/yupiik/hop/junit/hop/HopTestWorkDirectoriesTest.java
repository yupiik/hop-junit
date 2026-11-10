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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;

class HopTestWorkDirectoriesTest {
    private static final String ORIGINAL_TMPDIR = System.getProperty("java.io.tmpdir");

    private final HopTestWorkDirectories workDirectories = new HopTestWorkDirectories();
    /** Captures the real, running ExtensionContext for each test (JUnit only injects it into callbacks). */
    @org.junit.jupiter.api.extension.RegisterExtension
    final ExtensionCapture capture = new ExtensionCapture();

    static final class ExtensionCapture implements org.junit.jupiter.api.extension.BeforeEachCallback {
        private volatile ExtensionContext current;

        @Override
        public void beforeEach(final ExtensionContext context) {
            this.current = context;
        }

        ExtensionContext current() {
            return current;
        }
    }

    @AfterEach
    void clearHopProperties() {
        System.clearProperty("HOP_HOME");
        System.clearProperty("HOP_CONFIG_FOLDER");
        System.clearProperty("HOP_AUDIT_FOLDER");
        System.clearProperty("HOP_METADATA_FOLDER");
        System.clearProperty("HOP_CONFIG_IN_MEMORY");
        System.clearProperty("java.io.tmpdir");
        System.clearProperty("project.build.directory");
    }

    @Test
    void prepareCreatesDirectoriesAndSetsProperties() throws Exception {
        // prepare() only needs getRequiredTestClass(), getRequiredTestMethod() and getUniqueId().
        final var workDirectory = workDirectories.prepare(capture.current());
        assertTrue(Files.isDirectory(workDirectory));
        for (final var sub : new String[] {"home", "config", "audit", "metadata", "tmp"}) {
            assertTrue(Files.isDirectory(workDirectory.resolve(sub)), "missing " + sub + " dir");
        }
        assertEquals(workDirectory.resolve("home").toString(), System.getProperty("HOP_HOME"));
        assertEquals(workDirectory.resolve("config").toString(), System.getProperty("HOP_CONFIG_FOLDER"));
        assertEquals(workDirectory.resolve("audit").toString(), System.getProperty("HOP_AUDIT_FOLDER"));
        assertEquals(workDirectory.resolve("metadata").toString(), System.getProperty("HOP_METADATA_FOLDER"));
        assertEquals("Y", System.getProperty("HOP_CONFIG_IN_MEMORY"));
        assertEquals(workDirectory.resolve("tmp").toString(), System.getProperty("java.io.tmpdir"));
        // Work tree lives under the build directory, not the current working dir.
        assertTrue(workDirectory.toString().contains("hop-tests-work"));
    }

    @Test
    void prepareUsesBuildDirectoryOverride() throws Exception {
        // Captured at class-load time (before any test clears java.io.tmpdir).
        System.setProperty("project.build.directory", ORIGINAL_TMPDIR);
        final var workDirectory = workDirectories.prepare(capture.current());
        assertTrue(
                workDirectory
                        .toString()
                        .startsWith(Path.of(ORIGINAL_TMPDIR)
                                .toAbsolutePath()
                                .normalize()
                                .toString()),
                "work dir must honor project.build.directory");
    }
}
