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
package io.yupiik.hop.junit.database.h2;

/**
 * The H2 compatibility mode applied to an in-memory H2 database.
 *
 * <p>{@link #ORACLE_EXTENDED} is the default for the H2 backend: it switches H2 into Oracle
 * compatibility mode ({@code MODE=Oracle}) and additionally enables identifier uppercasing and the
 * Oracle default null-ordering, so real Oracle SQL runs unchanged against H2. {@link #ORACLE} only
 * enables the Oracle compatibility mode, while {@link #REGULAR} uses plain in-memory H2.
 */
public enum H2DatabaseMode {
    ORACLE_EXTENDED("MODE=Oracle;DATABASE_TO_UPPER=true;DEFAULT_NULL_ORDERING=HIGH"),
    ORACLE("MODE=Oracle"),
    REGULAR("");

    private final String options;

    H2DatabaseMode(final String options) {
        this.options = options;
    }

    /**
     * The H2 URL options carried by this mode (for example
     * {@code MODE=Oracle;DATABASE_TO_UPPER=true;DEFAULT_NULL_ORDERING=HIGH}), appended to the connection URL.
     *
     * @return the semicolon-separated H2 options, possibly empty
     */
    public String options() {
        return options;
    }
}
