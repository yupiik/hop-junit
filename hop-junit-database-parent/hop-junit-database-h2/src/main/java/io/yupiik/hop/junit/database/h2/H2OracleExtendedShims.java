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

    public static String SYS_CONTEXT(final String namespace, final String parameter) {
        if ("USERENV".equalsIgnoreCase(namespace)
                && ("CURRENT_SCHEMA".equalsIgnoreCase(parameter) || "SESSION_USER".equalsIgnoreCase(parameter))) {
            return "SA";
        }
        return null;
    }

    public static int ORA_HASH(final Object value) {
        return value == null ? 0 : Math.abs(value.toString().hashCode());
    }

    public static int ORA_HASH(final Object value, final int maxBucket) {
        final var hash = ORA_HASH(value);
        return maxBucket <= 0 ? hash : hash % (maxBucket + 1);
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
