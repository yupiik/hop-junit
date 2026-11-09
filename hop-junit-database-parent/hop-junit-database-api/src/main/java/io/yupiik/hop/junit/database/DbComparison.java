/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://yupiik.io/
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
 * How a {@link DbExpected} dataset is compared with the actual database state after a test.
 *
 * <ul>
 *   <li>{@link #STRICT} - default: tables match with the same rows regardless of row order
 *       (DBUnit {@code SortedTable}).</li>
 *   <li>{@link #ORDERED} - rows must appear in the same order as in the expected file.</li>
 *   <li>{@link #ROWCOUNT} - only the number of rows per expected table must match.</li>
 * </ul>
 */
public enum DbComparison {
    STRICT,
    ORDERED,
    ROWCOUNT
}
