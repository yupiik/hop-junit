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
package io.yupiik.hop.junit.database;

/**
 * DBUnit database operation applied when seeding a {@link DbInit} dataset. Maps directly onto the
 * corresponding {@code org.dbunit.operation.DatabaseOperation} constant. SQL ({@code *.sql})
 * {@link DbInit} entries are always run as plain scripts via the configured backend runner and ignore this.
 */
public enum DatasetOperation {
    INSERT,
    REPLACE,
    DELETE,
    DELETE_ALL,
    TRUNCATE_TABLE,
    CLEAN_INSERT
}
