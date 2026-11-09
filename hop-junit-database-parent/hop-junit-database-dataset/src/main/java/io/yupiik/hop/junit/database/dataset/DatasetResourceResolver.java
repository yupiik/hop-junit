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
package io.yupiik.hop.junit.database.dataset;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a dataset/SQL-script resource name to a {@link Path}, preferring the filesystem and
 * falling back to the classpath. This lets tests keep datasets either under {@code src/test/resources}
 * (packaged and discoverable on the classpath) or as plain files relative to the working directory.
 */
public class DatasetResourceResolver {
    public DatasetResourceResolver() {
        // no-op
    }

    /**
     * Resolve a resource name to an existing {@link Path}.
     *
     * @param resource the resource name (leading slash tolerated)
     * @return the resolved {@link Path}, or {@link Optional#empty()} when neither a filesystem file
     *     nor a classpath resource exists
     */
    public Optional<Path> resolve(final String resource) {
        final var onDisk = Path.of(resource);
        if (Files.isRegularFile(onDisk)) {
            return Optional.of(onDisk);
        }
        // Classpath resources never start with a root slash; strip it for the resource lookup only.
        final var normalized = stripLeadingSlash(resource);
        final var loader = Thread.currentThread().getContextClassLoader();
        final var url = loader.getResource(normalized);
        return Optional.ofNullable(url).flatMap(this::toPath);
    }

    /**
     * Resolve a directory (used for directory-based CSV datasets and for {@code *.sql} expansion),
     * preferring the filesystem and falling back to a classpath directory. Returns empty when neither exists.
     *
     * @param resource the resource name of the directory (leading slash tolerated)
     * @return the resolved directory {@link Path}, or {@link Optional#empty()} when neither a filesystem
     *     directory nor a classpath resource resolves to a directory
     */
    public Optional<Path> resolveDirectory(final String resource) {
        final var onDisk = Path.of(resource);
        if (Files.isDirectory(onDisk)) {
            return Optional.of(onDisk);
        }
        final var normalized = stripLeadingSlash(resource);
        final var loader = Thread.currentThread().getContextClassLoader();
        final var url = loader.getResource(normalized);
        if (url == null) {
            return Optional.empty();
        }
        return toPath(url).filter(Files::isDirectory);
    }

    private Optional<Path> toPath(final URL url) {
        try {
            return Optional.of(Path.of(url.toURI()));
        } catch (final URISyntaxException use) {
            return Optional.empty();
        }
    }

    private static String stripLeadingSlash(final String resource) {
        return resource.startsWith("/") ? resource.substring(1) : resource;
    }
}
