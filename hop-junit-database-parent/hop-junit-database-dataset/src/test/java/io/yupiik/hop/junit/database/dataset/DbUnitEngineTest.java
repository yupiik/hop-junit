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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.yupiik.hop.junit.database.DatasetFormat;
import io.yupiik.hop.junit.database.DatasetOperation;
import io.yupiik.hop.junit.database.DbComparison;
import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DbUnitEngineTest {
    private final DbUnitEngine engine = new DbUnitEngine();

    @TempDir
    Path tempDir;

    private static Connection h2Connection() throws Exception {
        Class.forName("org.h2.Driver");
        return DriverManager.getConnection("jdbc:h2:mem:dbunit_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void seedsAndVerifiesYamlDatasetFromClasspath() throws Exception {
        try (final var connection = h2Connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE PEOPLE (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            // YAML dataset lives on the classpath (datasets/people.yml); must be found by fallback.
            final var init = init("datasets/people.yml", DatasetFormat.AUTO, DatasetOperation.CLEAN_INSERT);
            engine.seed(connection, null, new DbInit[] {init});
            final var expected = expected("datasets/people.yml", DatasetFormat.AUTO, DbComparison.ROWCOUNT);
            engine.verify(connection, null, new DbExpected[] {expected});
            try (var rs = statement.executeQuery("SELECT COUNT(*) FROM PEOPLE")) {
                rs.next();
                assertTrue(rs.getInt(1) == 2, "two rows should have been seeded");
            }
        }
    }

    @Test
    void loadsYamlDatasetFromFilesystem() throws Exception {
        // A filesystem dataset must be preferred over the classpath by the resolver.
        final var yaml = tempDir.resolve("people.yml");
        Files.writeString(yaml, "PEOPLE:\n  - ID: 1\n    NAME: Carol\n  - ID: 2\n    NAME: Dave\n");
        try (final var connection = h2Connection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE PEOPLE (ID INT PRIMARY KEY, NAME VARCHAR(20))");
            final var init = init(yaml.toString(), DatasetFormat.AUTO, DatasetOperation.INSERT);
            engine.seed(connection, null, new DbInit[] {init});
            try (var rs = statement.executeQuery("SELECT COUNT(*) FROM PEOPLE")) {
                rs.next();
                assertTrue(rs.getInt(1) == 2, "YAML rows should have been seeded from the filesystem");
            }
        }
    }

    @Test
    void missingDatasetResourceFailsFast() throws Exception {
        try (final var connection = h2Connection()) {
            final var init = init("datasets/does-not-exist.yml", DatasetFormat.AUTO, DatasetOperation.CLEAN_INSERT);
            try {
                engine.seed(connection, null, new DbInit[] {init});
                fail("expected IllegalArgumentException for a missing dataset resource");
            } catch (final IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage().contains("Missing test resource"),
                        "unexpected message: " + expected.getMessage());
            }
        }
    }

    // ---- reflective annotation builders (DBUnit annotations are revealed only by reflection) ----

    private static DbInit init(final String value, final DatasetFormat format, final DatasetOperation operation) {
        return (DbInit) java.lang.reflect.Proxy.newProxyInstance(
                DbInit.class.getClassLoader(),
                new Class<?>[] {DbInit.class},
                (handler, method, args) -> switch (method.getName()) {
                    case "value" -> value;
                    case "format" -> format;
                    case "operation" -> operation;
                    case "caseSensitive" -> false;
                    case "admin" -> false;
                    case "supportedDatabases" -> new io.yupiik.hop.junit.database.DatabaseImplementation[0];
                    case "annotationType" -> DbInit.class;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static DbExpected expected(final String value, final DatasetFormat format, final DbComparison comparison) {
        return (DbExpected) java.lang.reflect.Proxy.newProxyInstance(
                DbExpected.class.getClassLoader(),
                new Class<?>[] {DbExpected.class},
                (handler, method, args) -> switch (method.getName()) {
                    case "value" -> value;
                    case "format" -> format;
                    case "comparison" -> comparison;
                    case "caseSensitive" -> false;
                    case "ignoreTable" -> new String[0];
                    case "ignoreColumn" -> new String[0];
                    case "annotationType" -> DbExpected.class;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type.isPrimitive()) {
            return 0;
        }
        return null;
    }
}
