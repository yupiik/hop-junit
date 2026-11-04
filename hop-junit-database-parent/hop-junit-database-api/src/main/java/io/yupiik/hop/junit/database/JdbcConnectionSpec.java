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
 * Single JDBC connection descriptor for both supported backends. The {@link DatabaseImplementation}
 * selects the engine; {@link #databaseTypeName()} derives the Hop {@code DatabaseMeta} type from it.
 * Host/port/service are not carried separately: the Oracle {@code jdbcUrl} is parsed when needed.
 *
 * @param implementation the engine (H2 or ORACLE), drives {@link #databaseTypeName()}
 * @param schema the schema (SRC, TGT, ...) backed by this named connection (Oracle); null on H2
 */
public record JdbcConnectionSpec(
        String name,
        String username,
        String password,
        String jdbcUrl,
        DatabaseImplementation implementation,
        String schema) {

    /** The Hop {@code DatabaseMeta} database type name ({@code "H2"} or {@code "ORACLE"}). */
    public String databaseTypeName() {
        return implementation.name();
    }
}
