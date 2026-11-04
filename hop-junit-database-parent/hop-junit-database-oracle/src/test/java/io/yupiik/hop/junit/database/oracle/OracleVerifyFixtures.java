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
package io.yupiik.hop.junit.database.oracle;

import io.yupiik.hop.junit.database.DbComparison;
import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import io.yupiik.hop.junit.database.JdbcNamed;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

/**
 * Fixtures driven programmatically by {@link OracleVerifyTest} through the JUnit Platform Test Kit.
 * Two tests share the same seed ({@code sql/oracle_schema.sql}) and insert the same single row; only
 * the {@code expected} dataset differs - one matches, one mismatches - so the post-test
 * {@code verifyDataset} outcome proves the extension really verifies.
 *
 * <p>Deliberately not collected by Surefire as a standalone test class (no {@code *Test} suffix):
 * it runs only through {@link OracleVerifyTest}'s EngineTestKit, so the mismatching fixture can fail
 * without failing the build.
 */
public class OracleVerifyFixtures {

    @Test
    @OracleDatabase(
            name = "VERIFY",
            init = {@DbInit("sql/oracle_schema.sql")},
            expected = {@DbExpected(value = "datasets/oracle_people_matching.yml", comparison = DbComparison.STRICT)})
    void verifyRunsAndPasses(@JdbcNamed("VERIFY") final JdbcConnectionSpec spec) throws Exception {
        insertSeeded(spec);
    }

    @Test
    @OracleDatabase(
            name = "VERIFY",
            init = {@DbInit("sql/oracle_schema.sql")},
            expected = {@DbExpected(value = "datasets/oracle_people_mismatching.yml", comparison = DbComparison.STRICT)
            })
    void verifyRunsAndFails(@JdbcNamed("VERIFY") final JdbcConnectionSpec spec) throws Exception {
        insertSeeded(spec);
        // the test body passes; the post-test verification against mismatching.yml must fail
    }

    private static void insertSeeded(final JdbcConnectionSpec spec) throws Exception {
        try (var connection = DriverManager.getConnection(spec.jdbcUrl(), spec.username(), spec.password());
                var statement = connection.createStatement()) {
            statement.execute("INSERT INTO PEOPLE (ID, NAME) VALUES (1, 'SEEDED')");
        }
    }
}
