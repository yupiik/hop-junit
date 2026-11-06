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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * The Oracle-emulation SQL functions installed by {@link H2DatabaseExtension} when a database runs in
 * {@link H2DatabaseMode#ORACLE_EXTENDED} mode. Every {@code public static} method of this class is
 * registered as an H2 alias (reflectively, by method name) so Oracle SQL calls resolve unchanged;
 * the alias name is the upper-cased method name, mirroring Oracle's case-insensitive function names.
 *
 * <p>The {@code public static} shape is a requirement of H2's {@code CREATE ALIAS ... FOR
 * "class.method"} resolution mechanism, not a framework static-utility API.
 */
public class H2OracleExtendedShims {
    public H2OracleExtendedShims() {
        // no-op
    }

    /** Oracle SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'|'SESSION_USER'|'DB_NAME') using the real H2 connection state. */
    public static String SYS_CONTEXT(final Connection connection, final String namespace, final String parameter)
            throws SQLException {
        if (!"USERENV".equalsIgnoreCase(namespace)) {
            return null;
        }
        final var name = parameter == null ? "" : parameter.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (name) {
            case "CURRENT_SCHEMA", "SESSION_USER" -> scalar(connection, "SELECT SCHEMA()");
            case "DB_NAME" -> scalar(connection, "SELECT DATABASE()");
            default -> null;
        };
    }

    private static String scalar(final Connection connection, final String sql) throws SQLException {
        try (var statement = connection.createStatement();
                final var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    public static int ORA_HASH(final Object value) {
        return value == null ? 0 : value.toString().hashCode() & Integer.MAX_VALUE;
    }

    public static int ORA_HASH(final Object value, final int maxBucket) {
        final var hash = ORA_HASH(value);
        if (maxBucket < 0) {
            throw new IllegalArgumentException("ORA_HASH max bucket must be non-negative");
        }
        return (int) (hash % ((long) maxBucket + 1));
    }

    public static String TO_CLOB(final String value) {
        return value;
    }

    public static long TO_NUMBER(final String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Long.parseLong(value.replaceFirst("[.,].*$", ""));
    }

    public static int REGEXP_COUNT(final String value, final String pattern) {
        if (value == null || pattern == null || pattern.isEmpty()) {
            return 0;
        }
        final var matcher = Pattern.compile(pattern).matcher(value);
        var count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public static String REGEXP_SUBSTR(
            final String value, final String pattern, final int position, final int occurrence) {
        if (value == null || pattern == null || occurrence <= 0) {
            return null;
        }
        final var matcher = Pattern.compile(pattern).matcher(value);
        var count = 0;
        final var start = Math.max(0, position - 1);
        if (!matcher.find(start)) {
            return null;
        }
        do {
            count++;
            if (count == occurrence) {
                return matcher.group();
            }
        } while (matcher.find());
        return null;
    }

    public static Object NVL2(final Object value, final Object ifNotNull, final Object ifNull) {
        return value == null ? ifNull : ifNotNull;
    }

    public static String RAWTOHEX(final Object value) {
        if (value == null) {
            return null;
        }
        final var text = value.toString();
        final var hex = new StringBuilder(text.length() * 2);
        for (final var c : text.toCharArray()) {
            hex.append(String.format("%02X", (int) c));
        }
        return hex.toString();
    }
}
