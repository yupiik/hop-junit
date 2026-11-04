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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlScriptRunnerTest {
    private final SqlScriptRunner runner = new SqlScriptRunner();

    @TempDir
    Path tempDir;

    private static java.sql.Connection h2Connection() throws Exception {
        Class.forName("org.h2.Driver");
        return DriverManager.getConnection("jdbc:h2:mem:sqlrunner_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void executesScriptFromFilesystem() throws Exception {
        final var script = tempDir.resolve("schema.sql");
        Files.writeString(
                script, "CREATE TABLE T (ID INT PRIMARY KEY, NAME VARCHAR(20)); INSERT INTO T VALUES (1, 'a');");
        try (final var connection = h2Connection()) {
            runner.executeInitScripts(connection, new String[] {script.toString()});
            try (var stmt = connection.createStatement();
                    var rs = stmt.executeQuery("SELECT NAME FROM T WHERE ID = 1")) {
                assertEquals(true, rs.next());
                assertEquals("a", rs.getString(1));
            }
        }
    }

    @Test
    void executesScriptFromClasspath() throws Exception {
        try (final var connection = h2Connection()) {
            // resolves via classpath (src/test/resources/sql/schema.sql)
            runner.executeInitScripts(connection, new String[] {"sql/schema.sql"});
            try (var stmt = connection.createStatement();
                    var rs = stmt.executeQuery("SELECT COUNT(*) FROM CP_TABLE")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void stripsLineCommentsAndSplitsAroundQuotedSemicolons() throws Exception {
        final var script = tempDir.resolve("comments.sql");
        Files.writeString(
                script,
                "-- a leading comment\n"
                        + "CREATE TABLE C (\n"
                        + "  ID INT,\n"
                        + "  MSG VARCHAR(10) DEFAULT 'a;b'\n"
                        + ");\n"
                        + "INSERT INTO C (ID, MSG) VALUES (1, 'x;y'); -- trailing comment");
        try (final var connection = h2Connection()) {
            runner.executeInitScripts(connection, new String[] {script.toString()});
            try (var stmt = connection.createStatement();
                    var rs = stmt.executeQuery("SELECT MSG FROM C WHERE ID = 1")) {
                rs.next();
                assertEquals("x;y", rs.getString(1));
            }
        }
    }

    @Test
    void expandsWildcardSqlFromClasspath() throws Exception {
        try (final var connection = h2Connection()) {
            // src/test/resources/sql/ contains schema.sql only; wildcard expands to every .sql in it
            runner.executeInitScripts(connection, new String[] {"sql/*.sql"});
            try (var stmt = connection.createStatement();
                    var rs = stmt.executeQuery("SELECT COUNT(*) FROM CP_TABLE")) {
                rs.next();
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void missingResourceThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runner.executeInitScripts(null, new String[] {"sql/missing.sql"}));
    }
}
