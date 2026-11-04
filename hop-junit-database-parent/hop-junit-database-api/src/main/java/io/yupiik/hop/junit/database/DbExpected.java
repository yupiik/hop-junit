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

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The post-condition a named database must satisfy after the test method (and the Hop execution)
 * has completed. Declared alongside {@code @Database}-family {@code init}; the extension verifies it
 * in {@code afterEach}, only when the test method itself passed, against the live named connection.
 *
 * <p>The only required attribute is {@link #value()} - the classpath path to a DBUnit expected
 * dataset. All other knobs are defaulted ({@link DbComparison#STRICT}). To skip volatile server-side
 * columns (e.g. {@code CREATED_AT} set by {@code CURRENT_TIMESTAMP}) use {@link #ignoreColumn()};
 * to skip an entire table use {@link #ignoreTable()}.
 */
@Target({METHOD, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface DbExpected {
    /** Classpath path to the expected DBUnit dataset. */
    String value();

    /** Dataset file format; {@link DatasetFormat#AUTO} infers it from the path extension. */
    DatasetFormat format() default DatasetFormat.AUTO;

    /** How the expected dataset is compared with the actual database (default STRICT). */
    DbComparison comparison() default DbComparison.STRICT;

    /** Whether table names are matched case-sensitively (default false). */
    boolean caseSensitive() default false;

    /** Columns ignored in the comparison, as {@code table.column} (or bare {@code column}) names. */
    String[] ignoreColumn() default {};

    /** Tables excluded from the comparison entirely. */
    String[] ignoreTable() default {};
}
