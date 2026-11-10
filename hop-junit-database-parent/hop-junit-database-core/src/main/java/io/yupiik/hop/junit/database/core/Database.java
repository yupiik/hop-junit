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
package io.yupiik.hop.junit.database.core;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Engine-agnostic facade for {@code @H2Database} and {@code @OracleDatabase}: the same test body
 * runs against either backend without edits. The engine is selected at runtime from the
 * {@code database.default.implementation} system property (see {@link DatabaseImplementation});
 * it defaults to H2, and the downstream {@code -Poracle} Maven profile switches it to a real Oracle
 * 23ai Free Testcontainers container.
 *
 * <p>The H2 backend's Oracle-compatibility mode is selected globally for {@code @Database} tests via the
 * {@code database.default.h2.mode} system property (defaulting to the H2 module's ORACLE_EXTENDED
 * mode); the real Oracle backend ignores it and provides the semantics natively.
 */
@Target(METHOD)
@Retention(RUNTIME)
@Repeatable(Databases.class)
@ExtendWith(DatabaseExtension.class)
public @interface Database {
    String name();

    /** DBUnit-friendly init/seed steps ({@code *.sql} schemas or DBUnit datasets), run before the test. */
    DbInit[] init() default {};

    /** Post-condition datasets verified in {@code afterEach} once the test method has passed. */
    DbExpected[] expected() default {};
}
