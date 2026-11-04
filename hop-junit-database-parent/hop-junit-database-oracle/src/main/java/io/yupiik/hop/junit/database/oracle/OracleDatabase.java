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
package io.yupiik.hop.junit.database.oracle;

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
 * Declares a named real-Oracle database (backed by a Testcontainers Oracle 23ai Free container) for
 * a test method, mirroring {@link H2Database}. Unlike H2 no {@link H2DatabaseMode} is needed because
 * a real Oracle engine natively provides all Oracle semantics.
 *
 * <p>Multiple databases are created under distinct schemas (SRC, TGT, ...) on a single shared
 * container and are injected into the test via {@code @JdbcNamed} + {@link JdbcConnectionSpec}.
 */
@Target(METHOD)
@Retention(RUNTIME)
@Repeatable(OracleDatabases.class)
@ExtendWith(OracleDatabaseExtension.class)
public @interface OracleDatabase {
    String name();

    /** DBUnit-friendly init/seed steps ({@code *.sql} schemas or DBUnit datasets), run before the test. */
    DbInit[] init() default {};

    /** Post-condition datasets verified in {@code afterEach} once the test method has passed. */
    DbExpected[] expected() default {};
}
