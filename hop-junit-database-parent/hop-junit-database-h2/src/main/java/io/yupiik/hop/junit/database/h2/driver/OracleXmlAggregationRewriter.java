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

import java.util.List;

/** XML-to-character aggregation of one element's text content, retaining surrounding expressions. */
final class OracleXmlAggregationRewriter {
    String rewrite(final String sql) {
        final var tokens = OracleSql.tokens(sql);
        final var out = new StringBuilder(sql.length());
        var last = 0;
        for (var i = 0; i + 9 < tokens.size(); i++) {
            if (!tokens.get(i).is("XMLCAST")
                    || !tokens.get(i + 1).is("(")
                    || !tokens.get(i + 2).is("XMLAGG")
                    || !tokens.get(i + 3).is("(")
                    || !tokens.get(i + 4).is("XMLELEMENT")
                    || !tokens.get(i + 5).is("(")
                    || !tokens.get(i + 7).is(",")) {
                continue;
            }
            final var elementEnd = close(tokens, i + 5);
            final var aggregateEnd = close(tokens, i + 3);
            final var castEnd = close(tokens, i + 1);
            if (elementEnd < 0 || aggregateEnd < 0 || castEnd < 0 || elementEnd == i + 8) {
                continue;
            }
            if (castEnd != aggregateEnd + 3
                    || !tokens.get(aggregateEnd + 1).is("AS")
                    || !(tokens.get(aggregateEnd + 2).is("CLOB")
                            || tokens.get(aggregateEnd + 2).is("VARCHAR2"))) {
                continue;
            }
            final var hasOrder = elementEnd + 1 < aggregateEnd;
            if (hasOrder
                    && (elementEnd + 3 >= aggregateEnd
                            || !tokens.get(elementEnd + 1).is("ORDER")
                            || !tokens.get(elementEnd + 2).is("BY"))) {
                continue;
            }
            // A second top-level element argument is not a scalar text value.
            var depth = 0;
            var scalar = true;
            for (var j = i + 8; j < elementEnd; j++) {
                if (tokens.get(j).is("(") || tokens.get(j).is("CASE")) {
                    depth++;
                }
                if (tokens.get(j).is(")") || tokens.get(j).is("END")) {
                    depth--;
                }
                if (depth == 0 && tokens.get(j).is(",")) {
                    scalar = false;
                }
            }
            if (!scalar) {
                continue;
            }
            out.append(sql, last, tokens.get(i).start())
                    .append("CAST(LISTAGG(")
                    .append(
                            sql,
                            tokens.get(i + 8).start(),
                            tokens.get(elementEnd).start())
                    .append(", '')");
            if (hasOrder) {
                out.append(" WITHIN GROUP (")
                        .append(
                                sql,
                                tokens.get(elementEnd + 1).start(),
                                tokens.get(aggregateEnd).start())
                        .append(')');
            }
            out.append(" AS CLOB)");
            last = tokens.get(castEnd).end();
            i = castEnd;
        }
        return last == 0 ? sql : out.append(sql, last, sql.length()).toString();
    }

    private int close(final List<OracleSql.Token> tokens, final int opening) {
        var depth = 1;
        for (var i = opening + 1; i < tokens.size(); i++) {
            if (tokens.get(i).is("(")) {
                depth++;
            }
            if (tokens.get(i).is(")") && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
