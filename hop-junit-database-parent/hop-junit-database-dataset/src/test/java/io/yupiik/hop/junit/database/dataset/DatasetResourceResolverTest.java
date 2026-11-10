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
package io.yupiik.hop.junit.database.dataset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetResourceResolverTest {
    private final DatasetResourceResolver resolver = new DatasetResourceResolver();

    @TempDir
    Path tempDir;

    @Test
    void prefersFilesystemOverClasspath() throws IOException {
        final var file = tempDir.resolve("data.yml");
        Files.writeString(file, "rows: []");
        // A file on disk must win over any same-named classpath resource.
        assertTrue(resolver.resolve(file.toString()).isPresent(), "existing filesystem file should resolve");
        assertTrue(Files.isSameFile(file, resolver.resolve(file.toString()).orElseThrow()));
    }

    @Test
    void fallsBackToClasspathResource() {
        // not present on disk; must be found on the classpath (placed in src/test/resources)
        final var resolved = resolver.resolve("datasets/classpath-only.yml");
        assertTrue(resolved.isPresent(), "classpath resource should resolve");
    }

    @Test
    void toleratesLeadingSlashOnClasspathResource() {
        final var resolved = resolver.resolve("/datasets/classpath-only.yml");
        assertTrue(resolved.isPresent(), "leading-slash classpath resource should resolve");
    }

    @Test
    void returnsEmptyWhenMissingEverywhere() {
        assertFalse(resolver.resolve("datasets/does-not-exist.yml").isPresent());
    }

    @Test
    void resolvesClasspathDirectory() {
        // datasets/ is packaged as a directory (the classpath-only.yml file lives inside it)
        final var dir = resolver.resolveDirectory("datasets");
        assertTrue(dir.isPresent(), "classpath directory should resolve");
    }

    @Test
    void returnsEmptyForMissingDirectory() {
        assertFalse(resolver.resolveDirectory("datasets/does-not-exist").isPresent());
    }
}
