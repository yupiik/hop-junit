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
package io.yupiik.hop.junit.database.h2.driver;

import java.util.List;
import java.util.Locale;

/** Lowers single-source, level-terminated hierarchies, not application queries. */
final class OracleHierarchyRewriter {
    String rewrite(final String sql) {
        final var tokens = OracleSql.tokens(sql);
        for (var connect = tokens.size() - 2; connect >= 0; connect--) {
            if (!tokens.get(connect).is("CONNECT") || !tokens.get(connect + 1).is("BY")) {
                continue;
            }
            var depth = 0;
            var select = -1;
            for (var i = connect - 1; i >= 0; i--) {
                if (tokens.get(i).is(")")) {
                    depth++;
                }
                if (tokens.get(i).is("(")) {
                    depth--;
                }
                if (depth == 0 && tokens.get(i).is("SELECT")) {
                    select = i;
                    break;
                }
            }
            if (select < 0) {
                throw unsupported("missing SELECT");
            }
            var end = tokens.size();
            depth = 0;
            for (var i = connect + 2; i < tokens.size(); i++) {
                final var token = tokens.get(i);
                if (depth == 0
                        && (token.is(")")
                                || token.is("UNION")
                                || token.is("ORDER")
                                || token.is(";")
                                || token.is("GROUP")
                                || token.is("FETCH"))) {
                    end = i;
                    break;
                }
                if (token.is("(")) {
                    depth++;
                }
                if (token.is(")")) {
                    depth--;
                }
            }
            var from = -1;
            var where = -1;
            var start = -1;
            depth = 0;
            for (var i = select + 1; i < connect; i++) {
                final var token = tokens.get(i);
                if (depth == 0) {
                    if (token.is("FROM")) {
                        from = i;
                    }
                    if (token.is("WHERE")) {
                        where = i;
                    }
                    if (token.is("START") && tokens.get(i + 1).is("WITH")) {
                        start = i;
                    }
                }
                if (token.is("(")) {
                    depth++;
                }
                if (token.is(")")) {
                    depth--;
                }
            }
            if (from < 0) {
                throw unsupported("missing FROM");
            }
            final var sourceEnd = Math.min(where < 0 ? connect : where, start < 0 ? connect : start);
            // A single named source, optionally schema-qualified and aliased. Joins and derived
            // sources need an explicit column model before they can be lowered without ambiguity.
            var cursor = from + 2;
            while (cursor + 1 < sourceEnd && tokens.get(cursor).is(".")) {
                cursor += 2;
            }
            var alias = tokens.get(cursor - 1).text();
            final var sourceNameEnd = tokens.get(cursor - 1).end();
            if (cursor < sourceEnd && tokens.get(cursor).is("AS")) {
                cursor++;
            }
            if (cursor < sourceEnd) {
                alias = tokens.get(cursor++).text();
            }
            if (cursor != sourceEnd) {
                throw unsupported("only one named source is supported");
            }
            final var condition = slice(sql, tokens, connect + 2, end);
            if (OracleSql.tokens(condition).stream().noneMatch(t -> t.is("LEVEL"))) {
                throw unsupported("a LEVEL termination condition is required");
            }
            if (tokens.subList(select, end).stream().anyMatch(t -> t.is("?"))) {
                throw unsupported("bind parameters inside a hierarchy require bind remapping");
            }
            final var suffix = "_" + tokens.get(connect).start();
            var prefix = "HOP_H" + suffix;
            while (sql.toUpperCase(Locale.ROOT).contains(prefix)) {
                prefix += "_";
            }
            final var source = prefix + "_SOURCE";
            final var tree = prefix + "_TREE";
            final var id = prefix + "_ID";
            final var parent = prefix + "_PARENT";
            final var projection = expression(
                    slice(sql, tokens, select + 1, from), alias, parent, "CAST(" + tree + ".LVL AS INTEGER)");
            final var filter = where < 0
                    ? ""
                    : " WHERE "
                            + expression(
                                    slice(sql, tokens, where + 1, start < 0 ? connect : start),
                                    alias,
                                    parent,
                                    "CAST(" + tree + ".LVL AS INTEGER)");
            final var roots =
                    start < 0 ? "" : " WHERE " + expression(slice(sql, tokens, start + 2, connect), alias, parent, "1");
            final var recursive = expression(condition, alias, parent, "(CAST(" + tree + ".LVL AS INTEGER) + 1)");
            final var lowered = "WITH " + source + " AS (SELECT " + alias + ".*, ROW_NUMBER() OVER () AS " + id
                    + " FROM "
                    + sql.substring(tokens.get(from + 1).start(), sourceNameEnd) + " " + alias
                    + ") SELECT * FROM (WITH RECURSIVE "
                    + tree + "(RID, LVL) AS (SELECT " + alias + "." + id + ", 1 FROM " + source + " " + alias + roots
                    + " UNION ALL SELECT " + alias + "." + id + ", CAST(" + tree + ".LVL AS INTEGER) + 1 FROM " + tree
                    + " JOIN " + source + " " + parent + " ON " + parent + "." + id + " = CAST(" + tree
                    + ".RID AS BIGINT) CROSS JOIN "
                    + source + " " + alias + " WHERE " + recursive + ") SELECT " + projection + " FROM " + tree
                    + " JOIN " + source + " " + alias + " ON " + alias + "." + id + " = CAST(" + tree
                    + ".RID AS BIGINT)" + filter + ")";
            final var endOffset =
                    end == tokens.size() ? sql.length() : tokens.get(end).start();
            return rewrite(sql.substring(0, tokens.get(select).start()) + "SELECT * FROM (" + lowered + ") "
                    + sql.substring(endOffset));
        }
        return sql;
    }

    private String slice(final String sql, final List<OracleSql.Token> tokens, final int start, final int end) {
        return sql.substring(tokens.get(start).start(), tokens.get(end - 1).end());
    }

    private String expression(final String sql, final String alias, final String parent, final String level) {
        final var tokens = OracleSql.tokens(sql);
        final var out = new StringBuilder();
        var last = 0;
        for (var i = 0; i < tokens.size(); i++) {
            final var token = tokens.get(i);
            if (token.is("LEVEL")) {
                out.append(sql, last, token.start()).append(level);
                last = token.end();
            } else if (token.is("PRIOR")) {
                out.append(sql, last, token.start());
                if (i + 3 < tokens.size()
                        && tokens.get(i + 1).is(alias)
                        && tokens.get(i + 2).is(".")) {
                    out.append(parent);
                    last = tokens.get(++i).end();
                } else if (i + 3 < tokens.size()
                        && tokens.get(i + 1).is("SYS_GUID")
                        && tokens.get(i + 2).is("(")
                        && tokens.get(i + 3).is(")")) {
                    last = token.end();
                } else {
                    throw unsupported("PRIOR must qualify a source column or SYS_GUID()");
                }
            }
        }
        return out.append(sql, last, sql.length()).toString();
    }

    private IllegalArgumentException unsupported(final String detail) {
        return new IllegalArgumentException("Unsupported Oracle CONNECT BY: " + detail);
    }
}
