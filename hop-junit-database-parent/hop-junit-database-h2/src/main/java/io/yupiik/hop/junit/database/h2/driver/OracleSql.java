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

import java.util.ArrayList;
import java.util.List;

/** Lossless lexical spans; deliberately not a SQL grammar or parser gate. */
final class OracleSql {
    private OracleSql() {}

    static List<Token> tokens(final String sql) {
        final var tokens = new ArrayList<Token>();
        for (var i = 0; i < sql.length(); ) {
            final var start = i;
            final var c = sql.charAt(i++);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '-' && i < sql.length() && sql.charAt(i) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i < sql.length() && sql.charAt(i) == '*') {
                final var end = sql.indexOf("*/", i + 1);
                i = end < 0 ? sql.length() : end + 2;
                continue;
            }
            var literal = false;
            if ((c == 'q' || c == 'Q') && i + 1 < sql.length() && sql.charAt(i) == '\'') {
                final var delimiter = sql.charAt(++i);
                final var closing =
                        switch (delimiter) {
                            case '[' -> ']';
                            case '(' -> ')';
                            case '{' -> '}';
                            case '<' -> '>';
                            default -> delimiter;
                        };
                final var end = sql.indexOf("" + closing + '\'', i + 1);
                i = end < 0 ? sql.length() : end + 2;
                literal = true;
            } else if (c == '\'' || c == '"') {
                while (i < sql.length()) {
                    if (sql.charAt(i++) == c) {
                        if (i < sql.length() && sql.charAt(i) == c) {
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                literal = c == '\'';
            } else if (Character.isJavaIdentifierPart(c) || c == '#') {
                while (i < sql.length() && (Character.isJavaIdentifierPart(sql.charAt(i)) || sql.charAt(i) == '#')) {
                    i++;
                }
            } else if (i < sql.length()
                    && ("<>!|".indexOf(c) >= 0)
                    && (sql.charAt(i) == '=' || sql.charAt(i) == c || c == '<' && sql.charAt(i) == '>')) {
                i++;
            }
            tokens.add(new Token(sql.substring(start, i), start, i, literal));
        }
        return tokens;
    }

    record Token(String text, int start, int end, boolean literal) {
        boolean is(final String keyword) {
            return !literal && text.equalsIgnoreCase(keyword);
        }
    }
}
