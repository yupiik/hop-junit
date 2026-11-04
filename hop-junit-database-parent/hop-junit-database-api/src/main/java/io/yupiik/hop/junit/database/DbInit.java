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
 * A database initialisation/seed step applied to the owning {@code @Database}-family database
 * before the test method runs. It is the DBUnit-friendly replacement for the legacy {@code String[]}
 * {@code init} list: each element describes one dataset (or SQL schema script).
 *
 * <p>The only required attribute is {@link #value()} - the classpath path to the resource. Every
 * other knob is defaulted so the common cases are written as {@code @DbInit("datasets/prep.yml")}.
 *
 * <p>Path handling:
 * <ul>
 *   <li>{@code *.sql} - run as raw statements (schema/DDL only; this is the only way to create
 *       tables, since DBUnit datasets are row-data only).</li>
 *   <li>any other extension - loaded as a DBUnit dataset and applied with {@link #operation()}
 *       (see {@link DatasetFormat#AUTO}).</li>
 * </ul>
 *
 * <p>A seed can be restricted to a subset of the {@link DatabaseImplementation}s with
 * {@link #supportedDatabases()}; defaults to all databases.
 */
@Target({METHOD, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface DbInit {
    /** Classpath path to the SQL schema script or DBUnit dataset. */
    String value();

    /**
     * Databases this seed applies to; empty array (default) = all databases. A seed whose
     * {@link DatabaseImplementation#current()} is not listed here is skipped, letting a script such
     * as the H2-only Oracle dictionary-view shims be applied on H2 but ignored on real Oracle.
     */
    DatabaseImplementation[] supportedDatabases() default {};

    /**
     * Whether this {@code *.sql} schema script must run through the shared admin/owner connection
     * (for the Oracle backend: the container {@code SYSTEM} connection) instead of the
     * descriptor-scoped application connection. Used to provision owner-scoped objects that a test's
     * application schema cannot create itself. Ignored on H2 and for non-{@code *.sql} datasets.
     */
    boolean admin() default false;

    /** Dataset file format; {@link DatasetFormat#AUTO} infers it from the path extension. */
    DatasetFormat format() default DatasetFormat.AUTO;

    /**
     * DBUnit operation to apply when {@code value} is a dataset ({@link DatasetOperation#CLEAN_INSERT}
     * truncates + inserts). Ignored for {@code *.sql} schemas.
     */
    DatasetOperation operation() default DatasetOperation.CLEAN_INSERT;

    /** Whether table names in the dataset are compared case-sensitively (default false). */
    boolean caseSensitive() default false;
}
