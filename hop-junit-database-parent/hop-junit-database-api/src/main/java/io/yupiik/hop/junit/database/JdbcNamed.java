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

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Resolves a test method parameter into the {@link JdbcConnectionSpec} of a named database created
 * by the current {@code @Database}-family extension.
 *
 * <pre><code>void run(@JdbcNamed("SRC") JdbcConnectionSpec src) { ... }</code></pre>
 */
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface JdbcNamed {
    /** The name of the declared database (must match a {@code @Database} / {@code @H2Database} / {@code @OracleDatabase} {@code name()}). */
    String value();
}
