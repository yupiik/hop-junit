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
package io.yupiik.hop.junit.database.h2;

import io.yupiik.hop.junit.database.DbComparison;
import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import io.yupiik.hop.junit.database.JdbcNamed;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

/**
 * Fixtures driven programmatically by {@link H2DatabaseVerifyTest} through the JUnit Platform
 * Test Kit. Two tests share the same seed ({@code h2-verify/people.sql}) and insert the same
 * single row; only the {@code expected} dataset differs - one matches, one mismatches - so the
 * post-test {@code verifyDataset} outcome proves the extension really verifies.
 */
public class H2VerifyFixtures {
    static final Path[] CAPTURED = new Path[1];

    @Test
    @H2Database(
            name = "DB",
            init = {@DbInit("h2-verify/people.sql")},
            expected = {@DbExpected(value = "h2-verify/matching.yml", comparison = DbComparison.STRICT)})
    void verifyRunsAndPasses(@JdbcNamed("DB") final JdbcConnectionSpec spec) throws Exception {
        insertAlice(spec);
        if (CAPTURED[0] == null) {
            CAPTURED[0] = Files.createTempFile("h2-verify-marker", ".txt");
        }
    }

    @Test
    @H2Database(
            name = "DB",
            init = {@DbInit("h2-verify/people.sql")},
            expected = {@DbExpected(value = "h2-verify/mismatching.yml", comparison = DbComparison.STRICT)})
    void verifyRunsAndFails(@JdbcNamed("DB") final JdbcConnectionSpec spec) throws Exception {
        insertAlice(spec);
        // the test body passes; the post-test verification against mismatching.yml must fail
    }

    private static void insertAlice(final JdbcConnectionSpec spec) throws Exception {
        try (var connection = DriverManager.getConnection(spec.jdbcUrl(), spec.username(), spec.password());
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO PEOPLE VALUES (1, 'Alice')");
        }
    }
}
