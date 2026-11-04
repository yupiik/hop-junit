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
package io.yupiik.hop.junit.database.dataset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs SQL schema scripts (from filesystem or classpath resources) against a JDBC connection,
 * splitting statements safely around quoted strings and stripping {@code --} line comments.
 * Each script is looked up on the filesystem first, then on the classpath.
 */
public class SqlScriptRunner {
    private final DatasetResourceResolver resolver;

    public SqlScriptRunner() {
        this(new DatasetResourceResolver());
    }

    public SqlScriptRunner(final DatasetResourceResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Executes each init script resource in order. A resource ending in {@code *.sql} expands to
     * every {@code .sql} file directly under the corresponding directory, sorted by file name.
     *
     * @param connection the connection to execute the scripts on
     * @param init the filesystem or classpath resources to run
     */
    public void executeInitScripts(final Connection connection, final String[] init) throws SQLException, IOException {
        for (final var script : expand(init)) {
            final var sql = Files.readString(script, UTF_8);
            executeScript(connection, sql);
        }
    }

    private List<Path> expand(final String[] init) throws IOException {
        final var scripts = new ArrayList<Path>();
        for (final var entry : init) {
            if (entry.endsWith("*.sql")) {
                final var dir = resourceDirectory(entry.substring(0, entry.length() - "*.sql".length()));
                try (var stream = Files.list(dir)) {
                    stream.filter(it -> it.getFileName().toString().endsWith(".sql"))
                            .sorted(comparing(it -> it.getFileName().toString()))
                            .forEach(scripts::add);
                }
            } else {
                scripts.add(resourcePath(entry));
            }
        }
        return scripts;
    }

    private Path resourcePath(final String resource) {
        return resolver.resolve(resource)
                .orElseThrow(() ->
                        new IllegalArgumentException("Missing test resource (filesystem or classpath): " + resource));
    }

    private Path resourceDirectory(final String resource) {
        return resolver.resolveDirectory(resource)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Missing test resource directory (filesystem or classpath): " + resource));
    }

    private static void executeScript(final Connection connection, final String sql) throws SQLException {
        final var statements = splitStatements(stripLineComments(sql));
        try (var statement = connection.createStatement()) {
            for (final var part : statements) {
                if (!part.isBlank()) {
                    statement.execute(part);
                }
            }
        }
    }

    private static List<String> splitStatements(final String sql) {
        final var statements = new ArrayList<String>();
        final var current = new StringBuilder();
        var inString = false;
        for (var i = 0; i < sql.length(); i++) {
            final var c = sql.charAt(i);
            if (c == '\'') {
                current.append(c);
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    current.append(sql.charAt(++i));
                } else {
                    inString = !inString;
                }
            } else if (c == ';' && !inString) {
                statements.add(stripLineComments(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(stripLineComments(current.toString()));
        }
        return statements;
    }

    /**
     * Remove Oracle/H2-style {@code --} line comments. A comment may begin anywhere on a line
     * (even after SQL), so the scanner tracks single-quoted strings and replaces a {@code --} marker
     * (followed by whitespace/EOL/SQL) outside a string with everything to the end of that line.
     */
    private static String stripLineComments(final String sql) {
        final var out = new StringBuilder(sql.length());
        var inString = false;
        var i = 0;
        while (i < sql.length()) {
            final var c = sql.charAt(i);
            if (c == '\'') {
                out.append(c);
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append(sql.charAt(++i));
                } else {
                    inString = !inString;
                }
                i++;
            } else if (!inString && c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                // Skip to end of line.
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                if (i < sql.length()) {
                    out.append('\n');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
