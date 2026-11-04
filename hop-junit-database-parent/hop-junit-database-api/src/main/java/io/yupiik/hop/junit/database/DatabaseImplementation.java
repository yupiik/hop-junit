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
package io.yupiik.hop.junit.database;

/**
 * Which database engine backs the {@code @Database} tests.
 *
 * <p>Selected at runtime from the {@code database.default.implementation} system property
 * (set by the downstream {@code -Poracle} Maven profile to {@code ORACLE}); defaults to {@link #H2}
 * which needs no Docker. An unknown value fails fast via {@link Enum#valueOf}.
 */
public enum DatabaseImplementation {
    H2,
    ORACLE;

    public static DatabaseImplementation current() {
        return CURRENT;
    }

    private static final DatabaseImplementation CURRENT =
            valueOf(System.getProperty("database.default.implementation", H2.name()));
}
