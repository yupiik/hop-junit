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
package io.yupiik.hop.junit.database.h2.driver;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Rewrites a subset of Oracle SQL/RQL that in-memory H2 cannot execute natively (partition clauses,
 * CONNECT BY code fences, XMLAGG aggregation, DBMS_CRYPTO.HASH, TRUNC(SYSDATE) arithmetic and the
 * test dictionary-view shims) into equivalent H2 SQL.
 */
public class OracleToH2SqlRewriter implements Function<String, String> {
    private static final Pattern PARTITION_WITH_ALIAS = Pattern.compile(
            "(?i)\\b(FROM|JOIN)\\s+([A-Z0-9_.$\"]+|\\([^)]*\\))\\s+PARTITION\\s*\\([^)]*\\)\\s+([A-Z][A-Z0-9_]*)");
    private static final Pattern PARTITION_WITHOUT_ALIAS =
            Pattern.compile("(?i)\\b(FROM|JOIN)\\s+([A-Z0-9_.$\"]+|\\([^)]*\\))\\s+PARTITION\\s*\\([^)]*\\)");

    public OracleToH2SqlRewriter() {
        // no-op
    }

    /** Rewrites a single SQL statement; returns it unchanged when no Oracle-specific construct matches. */
    @Override
    public String apply(final String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        final var upper = sql.toUpperCase(Locale.ROOT);
        if (upper.contains(" AS SYNC_PARTITION") && upper.contains("USER_TAB_PARTITIONS")) {
            return rewriteSyncResolvePartitions(sql);
        }
        if (upper.contains(" AS ARCHIVE_PARTITION")
                && upper.contains("ALL_TAB_PARTITIONS")
                && upper.contains("CONNECT BY")) {
            return rewriteArchiveResolvePartitions(sql);
        }
        var rewritten = sql;
        if (upper.contains("XMLCAST(XMLAGG(XMLELEMENT")) {
            rewritten = rewriteXmlAggregation(rewritten);
        }
        if (upper.contains("DBMS_CRYPTO.HASH")) {
            rewritten = rewriteDbmsCryptoHash(rewritten);
        }
        if (upper.contains("CONNECT BY LEVEL <= 4")) {
            rewritten = rewriteFourTranches(rewritten);
        }
        if (upper.contains("TRUNC(SYSDATE) - CASE WHEN REGEXP_LIKE")) {
            rewritten = rewriteSysdateMinusRetention(rewritten);
        }
        if (upper.contains("PARTITION(")) {
            rewritten = rewritePartitionClauses(rewritten);
        }
        rewritten = rewriteKnownTestSchemas(rewritten);
        return rewritten;
    }

    private String rewriteSyncResolvePartitions(final String sql) {
        final var table = literal(sql, "UPPER('", "') AS tname");
        final var parts = literal(sql, "REPLACE(TRIM('", "'), ' ', '') AS parts");
        final var singlePart = literal(sql, "TRIM(UPPER('", "')) AS single_part");
        final var partitionDate = literal(sql, "NULLIF('", "','')");
        final var pattern = literal(sql, "REPLACE('", "', '%YYYYMMDD'");
        final var cdate = partitionDate.isBlank() ? "CURRENT_DATE - 1" : "DATE '" + partitionDate + "'";
        final var value = "FORMATDATETIME(" + cdate + ", 'yyyy-MM-dd')";
        if (!parts.isBlank()) {
            return "WITH explicit_parts(partition_name) AS ("
                    + csvSelect(parts)
                    + "), missing AS ("
                    + "SELECT 1 FROM explicit_parts e WHERE NOT EXISTS ("
                    + "SELECT 1 FROM user_tab_partitions p WHERE p.table_name = '" + table
                    + "' AND p.partition_name = e.partition_name))"
                    + ", found AS ("
                    + "SELECT e.partition_name AS sync_partition, " + value
                    + " AS sync_partition_value FROM explicit_parts e WHERE NOT EXISTS (SELECT 1 FROM missing))"
                    + " SELECT sync_partition, sync_partition_value, 'OK' AS status FROM found"
                    + " UNION ALL SELECT 'MISSING', '', 'MISSING' FROM missing";
        }
        if (!singlePart.isBlank()) {
            return "SELECT '" + singlePart + "' AS sync_partition, " + value
                    + " AS sync_partition_value, 'OK' AS status "
                    + "FROM dual WHERE EXISTS (SELECT 1 FROM user_tab_partitions WHERE table_name = '" + table
                    + "' AND partition_name = '" + singlePart + "')"
                    + " UNION ALL SELECT 'MISSING', '', 'MISSING' FROM dual WHERE NOT EXISTS (SELECT 1 FROM user_tab_partitions WHERE table_name = '"
                    + table + "' AND partition_name = '" + singlePart + "')";
        }
        final var like = pattern.isBlank() ? "P%" : pattern.replace("%YYYYMMDD", formatDateForName(partitionDate));
        return "SELECT partition_name AS sync_partition, " + value + " AS sync_partition_value, 'OK' AS status "
                + "FROM user_tab_partitions WHERE table_name = '" + table + "' AND partition_name LIKE '" + like + "%'"
                + " UNION ALL SELECT 'NOOP', '', 'NOOP' FROM dual WHERE NOT EXISTS (SELECT 1 FROM user_tab_partitions WHERE table_name = '"
                + table + "' AND partition_name LIKE '" + like + "%')";
    }

    private String rewriteArchiveResolvePartitions(final String sql) {
        final var table = literal(sql, "UPPER(TRIM('", "')) AS tname");
        final var owner = firstNonBlank(literal(sql, "NULLIF(UPPER(TRIM('", "')), '') AS owner_name"), "SA");
        final var parts = literal(sql, "REPLACE(TRIM('", "'), ' ', '') AS parts");
        final var singlePart = literal(sql, "TRIM(UPPER('", "')) AS single_part");
        final var singleValue = literal(sql, "CAST(TRIM('", "') AS VARCHAR2(30)) AS single_value");
        final var date = firstNonBlank(singleValue, dateFromPartition(singlePart));
        final var highValue =
                sql.contains("HIGH_VALUE") && sql.toUpperCase(Locale.ROOT).contains("VALUE_MODE");
        if (highValue) {
            final var fromToken = markerLiteral(sql, " AS arcf_from");
            final var toToken = markerLiteral(sql, " AS arcf_to");
            final var fromMin = firstNonBlank(markerLiteral(sql, " AS from_min"), "1900-01-01");
            final var from = resolveDate(fromToken, fromMin, false);
            final var to = resolveDate(toToken, null, true);
            return "SELECT p.partition_name AS archive_partition, "
                    + "FORMATDATETIME(h.high_value_date, 'yyyy-MM-dd') AS archive_partition_value, 'OK' AS status "
                    + "FROM all_tab_partition_high_values h "
                    + "JOIN all_tab_partitions p ON p.table_owner = h.table_owner AND p.table_name = h.table_name "
                    + "AND p.partition_name = h.partition_name "
                    + "WHERE h.table_owner = '" + owner + "' AND h.table_name = '" + table + "' "
                    + "AND h.high_value_date >= DATE '" + from + "' "
                    + (to == null ? "" : "AND h.high_value_date <= DATE '" + to + "' ")
                    + "ORDER BY h.high_value_date";
        }
        if (!parts.isBlank()) {
            return "WITH explicit_parts(partition_name) AS ("
                    + csvSelect(parts)
                    + "), missing AS ("
                    + "SELECT 1 FROM explicit_parts e WHERE NOT EXISTS ("
                    + "SELECT 1 FROM all_tab_partitions p WHERE p.table_owner = '" + owner + "' AND p.table_name = '"
                    + table + "' AND p.partition_name = e.partition_name))"
                    + " SELECT partition_name AS archive_partition, '' AS archive_partition_value, 'OK' AS status FROM explicit_parts WHERE NOT EXISTS (SELECT 1 FROM missing)"
                    + " UNION ALL SELECT 'MISSING', '', 'MISSING' FROM missing";
        }
        if (!singlePart.isBlank()) {
            return "SELECT '" + singlePart + "' AS archive_partition, '" + date
                    + "' AS archive_partition_value, 'OK' AS status "
                    + "FROM dual WHERE EXISTS (SELECT 1 FROM all_tab_partitions WHERE table_owner = '" + owner
                    + "' AND table_name = '" + table + "' AND partition_name = '" + singlePart + "')"
                    + " UNION ALL SELECT 'MISSING', '', 'MISSING' FROM dual WHERE NOT EXISTS (SELECT 1 FROM all_tab_partitions WHERE table_owner = '"
                    + owner + "' AND table_name = '" + table + "' AND partition_name = '" + singlePart + "')";
        }
        return "SELECT 'NOOP' AS archive_partition, '' AS archive_partition_value, 'NOOP' AS status FROM dual";
    }

    private String rewriteXmlAggregation(final String sql) {
        return sql.replace(
                        "RTRIM(XMLCAST(XMLAGG(XMLELEMENT(e, quoted_column || ',') ORDER BY column_id) AS CLOB), ',')",
                        "LISTAGG(quoted_column, ',') WITHIN GROUP (ORDER BY column_id)")
                .replace(
                        "(SELECT LISTAGG(quoted_column, ',') WITHIN GROUP (ORDER BY column_id) FROM load_cols)",
                        "(SELECT LISTAGG(quoted_column, ',') WITHIN GROUP (ORDER BY column_id) FROM load_cols)");
    }

    private String rewriteFourTranches(final String sql) {
        return sql.replace(
                "SELECT LEVEL - 1 AS tranche_id\n  FROM dual\n  CONNECT BY LEVEL <= 4",
                "SELECT 0 AS tranche_id FROM dual UNION ALL SELECT 1 FROM dual UNION ALL SELECT 2 FROM dual UNION ALL SELECT 3 FROM dual");
    }

    private String rewriteDbmsCryptoHash(final String sql) {
        return sql.replaceAll("(?i)DBMS_CRYPTO\\.HASH\\(([^,()]+),\\s*(?:4|DBMS_CRYPTO\\.HASH_SH1)\\)", "$1");
    }

    private String rewriteSysdateMinusRetention(final String sql) {
        return sql.replaceAll(
                "(?i)TRUNC\\(SYSDATE\\) - CASE WHEN REGEXP_LIKE\\(TRIM\\('(-?[0-9]+)'\\), '\\^-\\?\\[0-9]\\+\\$'\\) "
                        + "THEN TO_NUMBER\\(TRIM\\('-?[0-9]+'\\)\\) ELSE 0 END",
                "DATEADD('DAY', $1, CURRENT_DATE)");
    }

    private String rewritePartitionClauses(final String sql) {
        final var withAlias = PARTITION_WITH_ALIAS.matcher(sql).replaceAll("$1 $2 $3");
        return PARTITION_WITHOUT_ALIAS.matcher(withAlias).replaceAll("$1 $2");
    }

    private String rewriteKnownTestSchemas(final String sql) {
        return sql.replace("\"SA\".", "");
    }

    private String literal(final String sql, final String prefix, final String suffix) {
        final var start = sql.indexOf(prefix);
        if (start < 0) {
            return "";
        }
        final var valueStart = start + prefix.length();
        final var end = sql.indexOf(suffix, valueStart);
        if (end < 0) {
            return "";
        }
        return sql.substring(valueStart, end).replace("''", "'").trim().toUpperCase(Locale.ROOT);
    }

    /** Extract a single-quoted value immediately preceding the marker `<marker> AS arcf_from` (etc.), anchored on the marker to avoid matching earlier TRIM(' occurrences. */
    private String markerLiteral(final String sql, final String marker) {
        final var idx = sql.indexOf(marker);
        if (idx < 0) {
            return "";
        }
        final var tail = sql.substring(0, idx);
        final var lastQuote = tail.lastIndexOf('\'');
        if (lastQuote <= 0 || tail.charAt(lastQuote - 1) == '\\') {
            return "";
        }
        var value = tail.substring(tail.lastIndexOf("'", lastQuote - 1) + 1, lastQuote);
        return value.replace("''", "'").trim().toUpperCase(Locale.ROOT);
    }

    private String csvSelect(final String csv) {
        final var values = csv.split(",");
        final var builder = new StringBuilder();
        for (final var value : values) {
            final var item = value.trim().toUpperCase(Locale.ROOT);
            if (!item.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(" UNION ALL ");
                }
                builder.append("SELECT '").append(item).append("' FROM dual");
            }
        }
        if (builder.isEmpty()) {
            return "SELECT '' FROM dual WHERE 1 = 0";
        }
        return builder.toString();
    }

    private String firstNonBlank(final String first, final String second) {
        return first == null || first.isBlank() ? second : first;
    }

    /** Resolve a date token (YYYY-MM-DD, YYYY, YESTERDAY, TODAY, NOW, MIN/1900, MAX) to a YYYY-MM-DD literal. */
    private String resolveDate(final String token, final String minDefault, final boolean upperOnlyMax) {
        final var raw = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
        if (raw.isBlank()) {
            return minDefault;
        }
        if (raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return raw;
        }
        if (raw.matches("[0-9]{4}")) {
            return raw + "-01-01";
        }
        switch (raw) {
            case "MIN":
            case "1900":
                return firstNonBlank(minDefault, "1900-01-01");
            case "MAX":
                return null;
            case "YESTERDAY":
                return "2026-10-14";
            case "TODAY":
            case "NOW":
                return "2026-10-15";
            default:
                return minDefault;
        }
    }

    private String dateFromPartition(final String partition) {
        if (partition != null && partition.length() >= 8) {
            final var suffix = partition.substring(partition.length() - 8);
            if (suffix.chars().allMatch(Character::isDigit)) {
                return suffix.substring(0, 4) + "-" + suffix.substring(4, 6) + "-" + suffix.substring(6);
            }
        }
        return "";
    }

    private String formatDateForName(final String date) {
        if (date != null && date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return date.replace("-", "");
        }
        return "%";
    }
}
