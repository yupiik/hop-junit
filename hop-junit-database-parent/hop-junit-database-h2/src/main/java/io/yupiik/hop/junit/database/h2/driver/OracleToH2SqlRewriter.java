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
package io.yupiik.hop.junit.database.h2.driver;

import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a subset of Oracle SQL/RQL that in-memory H2 cannot execute natively (partition clauses,
 * CONNECT BY code fences, XMLAGG aggregation, DBMS_CRYPTO.HASH, TRUNC(SYSDATE) arithmetic, ROWID
 * band-split predicates and test dictionary-view shims) into H2 SQL. Partition clauses are
 * removed because H2 fixture tables are not partitioned; they do not filter rows.
 */
public class OracleToH2SqlRewriter implements Function<String, String> {
    private static final Pattern PARTITION_WITH_ALIAS = Pattern.compile(
            "(?i)\\b(FROM|JOIN)\\s+([A-Z0-9_.$\"]+|\\([^)]*\\))\\s+PARTITION\\s*\\([^)]*\\)\\s+([A-Z][A-Z0-9_]*)");
    private static final Pattern PARTITION_WITHOUT_ALIAS =
            Pattern.compile("(?i)\\b(FROM|JOIN)\\s+([A-Z0-9_.$\"]+|\\([^)]*\\))\\s+PARTITION\\s*\\([^)]*\\)");
    private static final Pattern ROWID_TO_CHAR_WITH_ROWID = Pattern.compile(
            "(?i)\\bROWIDTOCHAR\\s*\\(\\s*(?:([A-Z0-9_$#]+|\"(?:[^\"]|\"\")+\")\\s*\\.\\s*)?ROWID\\s*\\)");
    private static final Pattern HASH = Pattern.compile("(?i)\\bDBMS_CRYPTO\\s*\\.\\s*HASH\\s*\\(");
    private static final Pattern RAWTOHEX = Pattern.compile("(?i)\\bRAWTOHEX\\s*\\(");
    private static final Pattern CAST_TO_RAW = Pattern.compile("(?i)\\bUTL_RAW\\s*\\.\\s*CAST_TO_RAW\\s*\\(");
    private static final OracleDateArithmetic DATE_ARITHMETIC_REWRITER = new OracleDateArithmetic();
    private static final OracleHierarchyRewriter HIERARCHY_REWRITER = new OracleHierarchyRewriter();
    private static final OracleXmlAggregationRewriter XML_AGGREGATION_REWRITER = new OracleXmlAggregationRewriter();

    public OracleToH2SqlRewriter() {
        // no-op
    }

    /** Rewrites a single SQL statement; returns it unchanged when no Oracle-specific construct matches. */
    @Override
    public String apply(final String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        final var upper = codeOnly(sql).toUpperCase(Locale.ROOT);
        var rewritten = DATE_ARITHMETIC_REWRITER.rewrite(sql);
        if (upper.contains("UTL_RAW")) {
            rewritten = replaceCode(rewritten, CAST_TO_RAW, matcher -> "STRINGTOUTF8(");
        }
        if (upper.contains("XMLAGG")) {
            rewritten = XML_AGGREGATION_REWRITER.rewrite(rewritten);
        }
        if (upper.contains("DBMS_CRYPTO")) {
            rewritten = rewriteDbmsCryptoHash(rewritten);
        }
        if (upper.contains("RAWTOHEX")) {
            rewritten = rewriteRawToHex(rewritten);
        }
        if (upper.contains("CONNECT")) {
            rewritten = HIERARCHY_REWRITER.rewrite(rewritten);
        }
        if (upper.contains("PARTITION")) {
            rewritten = rewritePartitionClauses(rewritten);
        }
        if (upper.contains("ROWIDTOCHAR")) {
            rewritten = rewriteRowIdToChar(rewritten);
        }
        return rewritten;
    }

    private String rewriteDbmsCryptoHash(final String sql) {
        final var code = codeOnly(sql);
        final var matcher = HASH.matcher(code);
        final var out = new StringBuilder(sql.length());
        var last = 0;
        while (matcher.find(last)) {
            var depth = 1;
            var comma = -1;
            var end = matcher.end();
            for (; end < code.length() && depth > 0; end++) {
                final var c = code.charAt(end);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 1) {
                    comma = end;
                }
            }
            if (depth != 0 || comma < 0) {
                break;
            }
            final var algorithm = code.substring(comma + 1, end - 1).replaceAll("\\s+", "");
            final var digest = "3".equals(algorithm) || "DBMS_CRYPTO.HASH_SH1".equalsIgnoreCase(algorithm)
                    ? "SHA-1"
                    : "4".equals(algorithm) ? "SHA-256" : null;
            if (digest == null) {
                out.append(sql, last, end);
            } else {
                out.append(sql, last, matcher.start())
                        .append("HASH('")
                        .append(digest)
                        .append("', ")
                        .append(sql, matcher.end(), comma)
                        .append(')');
            }
            last = end;
        }
        return last == 0 ? sql : out.append(sql, last, sql.length()).toString();
    }

    private String rewriteRawToHex(final String sql) {
        final var code = codeOnly(sql);
        final var matcher = RAWTOHEX.matcher(code);
        final var out = new StringBuilder(sql.length());
        var last = 0;
        while (matcher.find(last)) {
            var depth = 1;
            var end = matcher.end();
            for (; end < code.length() && depth > 0; end++) {
                if (code.charAt(end) == '(') {
                    depth++;
                } else if (code.charAt(end) == ')') {
                    depth--;
                }
            }
            if (depth != 0) {
                break;
            }
            out.append(sql, last, matcher.start())
                    .append("UPPER(")
                    .append(sql, matcher.start(), end)
                    .append(')');
            last = end;
        }
        return last == 0 ? sql : out.append(sql, last, sql.length()).toString();
    }

    private String rewritePartitionClauses(final String sql) {
        final var tokens = OracleSql.tokens(sql);
        final var partitioned = java.util.stream.IntStream.range(0, Math.max(0, tokens.size() - 1))
                .anyMatch(
                        i -> tokens.get(i).is("PARTITION") && tokens.get(i + 1).is("("));
        if (partitioned
                && tokens.stream()
                        .anyMatch(t -> t.is("INSERT")
                                || t.is("UPDATE")
                                || t.is("DELETE")
                                || t.is("MERGE")
                                || t.is("ALTER")
                                || t.is("TRUNCATE"))) {
            throw new IllegalArgumentException(
                    "Partition-targeted writes cannot be approximated on unpartitioned H2 fixtures");
        }
        final var withAlias = replaceCode(
                sql,
                PARTITION_WITH_ALIAS,
                matcher -> matcher.group(1) + " " + matcher.group(2) + " " + matcher.group(3));
        return replaceCode(withAlias, PARTITION_WITHOUT_ALIAS, matcher -> matcher.group(1) + " " + matcher.group(2));
    }

    private String rewriteRowIdToChar(final String sql) {
        return replaceCode(
                sql,
                ROWID_TO_CHAR_WITH_ROWID,
                matcher -> "CAST(" + (matcher.group(1) == null ? "" : matcher.group(1) + ".") + "_ROWID_ AS VARCHAR)");
    }

    private String replaceCode(final String sql, final Pattern pattern, final Function<Matcher, String> replacement) {
        final var code = codeOnly(sql);
        final var matcher = pattern.matcher(sql);
        final var result = new StringBuilder(sql.length());
        var last = 0;
        while (matcher.find()) {
            if (!code.regionMatches(true, matcher.start(), sql, matcher.start(), keywordLength(sql, matcher.start()))) {
                continue;
            }
            result.append(sql, last, matcher.start()).append(replacement.apply(matcher));
            last = matcher.end();
        }
        return last == 0 ? sql : result.append(sql, last, sql.length()).toString();
    }

    private int keywordLength(final String sql, final int start) {
        var end = start;
        while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
            end++;
        }
        return end == start ? 1 : end - start;
    }

    // Mask literals, comments, and quoted identifier interiors without changing SQL offsets.
    private String codeOnly(final String sql) {
        final var code = new StringBuilder(sql);
        for (var i = 0; i < sql.length(); i++) {
            final var c = sql.charAt(i);
            if ((c == 'q' || c == 'Q')
                    && (i == 0 || !Character.isJavaIdentifierPart(sql.charAt(i - 1)))
                    && i + 2 < sql.length()
                    && sql.charAt(i + 1) == '\'') {
                final var opening = sql.charAt(i + 2);
                final var closing =
                        switch (opening) {
                            case '[' -> ']';
                            case '{' -> '}';
                            case '(' -> ')';
                            case '<' -> '>';
                            default -> opening;
                        };
                final var start = i;
                i += 3;
                while (i + 1 < sql.length() && !(sql.charAt(i) == closing && sql.charAt(i + 1) == '\'')) {
                    i++;
                }
                i = Math.min(i + 1, sql.length() - 1);
                for (var j = start; j <= i; j++) {
                    code.setCharAt(j, ' ');
                }
            } else if (c == '\'' || c == '"') {
                final var quote = c;
                final var start = i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == quote) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                for (var j = start + (quote == '"' ? 1 : 0); j <= i && j < sql.length(); j++) {
                    code.setCharAt(j, ' ');
                }
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    code.setCharAt(i++, ' ');
                }
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                code.setCharAt(i++, ' ');
                code.setCharAt(i++, ' ');
                while (i < sql.length()) {
                    if (sql.charAt(i) == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                        code.setCharAt(i, ' ');
                        code.setCharAt(++i, ' ');
                        break;
                    }
                    code.setCharAt(i++, ' ');
                }
            }
        }
        return code.toString();
    }
}
