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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Converts provable date differences to Oracle numeric days, retaining fractional days. */
final class OracleDateArithmetic {
    String rewrite(final String sql) {
        final var tokens = OracleSql.tokens(sql);
        final var dates = new HashSet<Integer>();
        for (var i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).is("TO_DATE") && tokens.get(i + 1).is("(")) {
                final var end = right(tokens, i);
                if (end + 1 < tokens.size() && tokens.get(end).is("AS")) {
                    dates.add(end);
                }
            }
            if (tokens.get(i).is("CASE")) {
                final var end = right(tokens, i);
                if (end + 1 < tokens.size() && tokens.get(end).is("AS") && caseResults(tokens, i, end, false)) {
                    dates.add(end);
                }
            }
        }
        return rewrite(sql, qualifiedDates(tokens, dates));
    }

    // Do not infer a physical table column's type from an unrelated projection with the
    // same name. Only propagate declarations through named CTEs and simple star projections.
    private Set<String> qualifiedDates(final List<OracleSql.Token> tokens, final Set<Integer> declared) {
        final var relations = new HashMap<String, Set<String>>();
        final var ranges = new HashMap<String, int[]>();
        for (var i = 0; i + 2 < tokens.size(); i++) {
            if (!tokens.get(i + 1).is("AS") || !tokens.get(i + 2).is("(")) {
                continue;
            }
            final var end = right(tokens, i + 2);
            final var names = new HashSet<String>();
            var depth = 0;
            for (var j = i + 3; j + 1 < end; j++) {
                if (tokens.get(j).is("(")) {
                    depth++;
                } else if (tokens.get(j).is(")")) {
                    depth--;
                }
                if (depth == 0 && declared.contains(j)) {
                    names.add(tokens.get(j + 1).text().toUpperCase(Locale.ROOT));
                }
            }
            final var relation = tokens.get(i).text().toUpperCase(Locale.ROOT);
            ranges.put(relation, new int[] {i + 3, end - 1});
            relations.put(relation, names);
        }
        for (var pass = 0; pass < ranges.size(); pass++) {
            for (final var entry : ranges.entrySet()) {
                final var range = entry.getValue();
                final var body = tokens.subList(range[0], range[1]);
                if (body.stream().noneMatch(t -> t.is("*"))) {
                    continue;
                }
                for (var i = range[0]; i + 1 < range[1]; i++) {
                    if (tokens.get(i).is("FROM")) {
                        relations
                                .get(entry.getKey())
                                .addAll(relations.getOrDefault(
                                        tokens.get(i + 1).text().toUpperCase(Locale.ROOT), Set.of()));
                    }
                }
            }
        }
        final var bindings = new HashMap<String, Set<String>>();
        for (var i = 0; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).is("FROM") && !tokens.get(i).is("JOIN")) {
                continue;
            }
            final var source = tokens.get(i + 1).text().toUpperCase(Locale.ROOT);
            var alias = source;
            var next = i + 2;
            if (next < tokens.size() && tokens.get(next).is("AS")) {
                next++;
            }
            if (next < tokens.size()
                    && Character.isJavaIdentifierStart(tokens.get(next).text().charAt(0))
                    && !Set.of(
                                    "WHERE", "CONNECT", "START", "UNION", "JOIN", "LEFT", "RIGHT", "CROSS", "ON",
                                    "ORDER", "GROUP")
                            .contains(tokens.get(next).text().toUpperCase(Locale.ROOT))) {
                alias = tokens.get(next).text().toUpperCase(Locale.ROOT);
            }
            final var names = relations.getOrDefault(source, Set.of());
            if (!bindings.containsKey(alias)) {
                bindings.put(alias, new HashSet<>(names));
            } else {
                bindings.get(alias).retainAll(names);
            }
        }
        final var qualified = new HashSet<String>();
        for (final var entry : bindings.entrySet()) {
            for (final var name : entry.getValue()) {
                qualified.add(entry.getKey() + "." + name);
            }
        }
        return qualified;
    }

    private String rewrite(final String sql, final Set<String> dates) {
        final var tokens = OracleSql.tokens(sql);
        for (var i = 1; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).is("-") && !tokens.get(i).is("+")) {
                continue;
            }
            final var start = left(tokens, i - 1);
            var end = right(tokens, i + 1);
            while (end + 1 < tokens.size()
                    && (tokens.get(end).is("*") || tokens.get(end).is("/"))) {
                end = right(tokens, end + 1);
            }
            if (!isDate(tokens, start, i, dates)
                    || containsCase(tokens, i + 1, end) && !isNumeric(tokens, i + 1, end)) {
                continue;
            }
            final var a = sql.substring(tokens.get(start).start(), tokens.get(i).start());
            final var b =
                    sql.substring(tokens.get(i + 1).start(), tokens.get(end - 1).end());
            if (tokens.get(i + 1).is("(")
                    && i + 2 < tokens.size()
                    && tokens.get(i + 2).is("INTERVAL")) {
                continue;
            }
            final var replacement =
                    isDate(tokens, i + 1, end, dates) && tokens.get(i).is("-")
                            ? "(-DATEDIFF('SECOND', " + a + ", " + b + ") / 86400.0)"
                            : "(CAST(" + a + " AS TIMESTAMP) " + tokens.get(i).text()
                                    + " (INTERVAL '1 00:00:00' DAY TO SECOND * CAST(("
                                    + b + ") AS NUMERIC(38, 10))))";
            return rewrite(
                    sql.substring(0, tokens.get(start).start())
                            + replacement
                            + sql.substring(tokens.get(end - 1).end()),
                    dates);
        }
        return sql;
    }

    private boolean containsCase(final List<OracleSql.Token> tokens, final int start, final int end) {
        return tokens.subList(start, end).stream().anyMatch(t -> t.is("CASE"));
    }

    // Conditions and simple-CASE selectors are opaque; only result branches determine the type.
    private boolean caseResults(
            final List<OracleSql.Token> tokens, final int start, final int end, final boolean numeric) {
        if (end <= start + 2 || !tokens.get(end - 1).is("END")) {
            return false;
        }
        var resultStart = -1;
        var branches = 0;
        for (var i = start + 1; i < end; i++) {
            final var token = tokens.get(i);
            if (token.is("CASE") || token.is("(")) {
                i = right(tokens, i) - 1;
                continue;
            }
            if (token.is("WHEN") || token.is("ELSE") || token.is("END")) {
                if (resultStart >= 0) {
                    final var valid = numeric
                            ? isNumeric(tokens, resultStart, i)
                            : isDate(tokens, resultStart, i, Set.of())
                                    || i == resultStart + 1
                                            && tokens.get(resultStart).is("NULL");
                    if (!valid) {
                        return false;
                    }
                    if (numeric
                            || !(i == resultStart + 1 && tokens.get(resultStart).is("NULL"))) {
                        branches++;
                    }
                    resultStart = -1;
                }
            }
            if (token.is("THEN") || token.is("ELSE")) {
                resultStart = i + 1;
            }
        }
        return branches > 0;
    }

    private boolean isNumeric(final List<OracleSql.Token> tokens, final int start, final int end) {
        if (start >= end) {
            return false;
        }
        if (tokens.get(start).is("+") || tokens.get(start).is("-")) {
            return isNumeric(tokens, start + 1, end);
        }
        final var atomEnd = right(tokens, start);
        if (atomEnd < end) {
            return (tokens.get(atomEnd).is("+")
                            || tokens.get(atomEnd).is("-")
                            || tokens.get(atomEnd).is("*")
                            || tokens.get(atomEnd).is("/"))
                    && isNumeric(tokens, start, atomEnd)
                    && isNumeric(tokens, atomEnd + 1, end);
        }
        if (atomEnd != end) {
            return false;
        }
        final var first = tokens.get(start);
        if (first.is("(")) {
            return isNumeric(tokens, start + 1, end - 1);
        }
        if (first.is("CASE")) {
            return caseResults(tokens, start, end, true);
        }
        if (first.is("TO_NUMBER") && start + 1 < end && tokens.get(start + 1).is("(")) {
            return true;
        }
        if (first.is("CAST") && end > start + 4 && tokens.get(start + 1).is("(")) {
            for (var i = start + 2; i + 1 < end; i++) {
                if (tokens.get(i).is("(")) {
                    i = right(tokens, i) - 1;
                } else if (tokens.get(i).is("AS")) {
                    return Set.of("NUMBER", "NUMERIC", "DECIMAL", "INTEGER", "INT", "BIGINT", "SMALLINT")
                            .contains(tokens.get(i + 1).text().toUpperCase(Locale.ROOT));
                }
            }
        }
        return end == start + 1
                        && (first.is("?") || first.is("NULL") || first.text().matches("[0-9]+"))
                || end == start + 3
                        && first.text().matches("[0-9]+")
                        && tokens.get(start + 1).is(".")
                        && tokens.get(start + 2).text().matches("[0-9]+");
    }

    private boolean isDate(
            final List<OracleSql.Token> tokens, final int start, final int end, final Set<String> dates) {
        if (start >= end) {
            return false;
        }
        if (tokens.get(start).is("(")) {
            return isDate(tokens, start + 1, end - 1, dates);
        }
        final var first = tokens.get(start);
        if (first.is("CAST")) {
            final var castEnd = right(tokens, start);
            if (castEnd > start + 3
                    && tokens.get(castEnd - 3).is("AS")
                    && tokens.get(castEnd - 2).is("TIMESTAMP")) {
                return true;
            }
        }
        if (first.is("DATEADD")
                || first.is("TO_DATE")
                || first.is("SYSDATE")
                || first.is("CURRENT_DATE")
                || first.is("DATE")) {
            return true;
        }
        if ((first.is("TRUNC") || first.is("GREATEST") || first.is("LEAST") || first.is("NVL") || first.is("COALESCE"))
                && start + 2 < end) {
            return isDate(tokens, start + 2, end - 1, dates);
        }
        return start + 2 < end
                && tokens.get(start + 1).is(".")
                && dates.contains((first.text() + "." + tokens.get(start + 2).text()).toUpperCase(Locale.ROOT));
    }

    private int left(final List<OracleSql.Token> tokens, final int end) {
        var start = end;
        if (tokens.get(start).is(")")) {
            var depth = 1;
            while (--start >= 0) {
                if (tokens.get(start).is(")")) {
                    depth++;
                }
                if (tokens.get(start).is("(") && --depth == 0) {
                    break;
                }
            }
            if (start > 0
                    && !Set.of("SELECT", "WHEN", "THEN", "ELSE", "AND", "OR", "WHERE", "ON", "BY", "NOT", "IN")
                            .contains(tokens.get(start - 1).text().toUpperCase(Locale.ROOT))
                    && Character.isJavaIdentifierStart(
                            tokens.get(start - 1).text().charAt(0))) {
                start--;
            }
        } else {
            if (start > 0
                    && tokens.get(start).literal()
                    && tokens.get(start - 1).is("DATE")) {
                return start - 1;
            }
            while (start > 1 && tokens.get(start - 1).is(".")) {
                start -= 2;
            }
        }
        return Math.max(0, start);
    }

    private int right(final List<OracleSql.Token> tokens, final int start) {
        if ((tokens.get(start).is("-") || tokens.get(start).is("+")) && start + 1 < tokens.size()) {
            return right(tokens, start + 1);
        }
        var end = start + 1;
        if (tokens.get(start).is("CASE")) {
            var depth = 1;
            for (; end < tokens.size(); end++) {
                if (tokens.get(end).is("CASE")) {
                    depth++;
                } else if (tokens.get(end).is("END") && --depth == 0) {
                    return end + 1;
                }
            }
            return end;
        }
        if (tokens.get(start).is("DATE")
                && end < tokens.size()
                && tokens.get(end).literal()) {
            return end + 1;
        }
        if (end < tokens.size() && tokens.get(end).is("(")) {
            end++;
        } else if (!tokens.get(start).is("(")) {
            while (end + 1 < tokens.size() && tokens.get(end).is(".")) {
                end += 2;
            }
            return end;
        }
        var depth = 1;
        for (; end < tokens.size(); end++) {
            if (tokens.get(end).is("(")) {
                depth++;
            }
            if (tokens.get(end).is(")") && --depth == 0) {
                return end + 1;
            }
        }
        return end;
    }
}
