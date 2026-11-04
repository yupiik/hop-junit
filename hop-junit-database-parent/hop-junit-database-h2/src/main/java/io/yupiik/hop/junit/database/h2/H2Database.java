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
package io.yupiik.hop.junit.database.h2;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Declares a named in-memory H2 database for a test method. Multiple databases are created as
 * distinct in-memory H2 databases and injected into the test via {@code @JdbcNamed} +
 * {@link JdbcConnectionSpec}.
 *
 * <p>By default the database runs in {@link H2DatabaseMode#ORACLE_EXTENDED} so real Oracle SQL runs
 * unchanged, with the Oracle-emulation SQL rewrite driver and shim aliases registered automatically.
 */
@Target(METHOD)
@Retention(RUNTIME)
@Repeatable(H2Databases.class)
@ExtendWith(H2DatabaseExtension.class)
public @interface H2Database {
    String name();

    /** DBUnit-friendly init/seed steps ({@code *.sql} schemas or DBUnit datasets), run before the test. */
    DbInit[] init() default {};

    /** Post-condition datasets verified in {@code afterEach} once the test method has passed. */
    DbExpected[] expected() default {};

    /** The H2 compatibility mode applied to this database (default Oracle-extended). */
    H2DatabaseMode mode() default H2DatabaseMode.ORACLE_EXTENDED;
}
