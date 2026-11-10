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
package io.yupiik.hop.junit.hop.coverage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes the JSON aggregate-report documents ({@link HopCoverageModels}) that the coverage
 * post-processor produces, using Jackson with {@code NON_NULL} inclusion defaults.
 */
public final class HopCoverageJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    public static final HopCoverageJson INSTANCE = new HopCoverageJson();

    private HopCoverageJson() {
        // immutable singleton, no lifecycle
    }

    public <T> T read(final Class<T> type, final Path path) throws IOException {
        try (final var reader = Files.newBufferedReader(path)) {
            return MAPPER.readValue(reader, type);
        }
    }

    public void write(final Object value, final Path path) throws IOException {
        Files.createDirectories(path.getParent());
        try (final var writer = Files.newBufferedWriter(path)) {
            MAPPER.writeValue(writer, value);
        }
    }

    public String toString(final Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }
}
