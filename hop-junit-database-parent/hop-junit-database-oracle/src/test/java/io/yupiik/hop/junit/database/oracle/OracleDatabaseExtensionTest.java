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
package io.yupiik.hop.junit.database.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.yupiik.hop.junit.database.DatasetFormat;
import io.yupiik.hop.junit.database.DatasetOperation;
import io.yupiik.hop.junit.database.DbComparison;
import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import io.yupiik.hop.junit.database.JdbcNamed;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests against a real Oracle 23ai Free container (requires Docker).
 *
 * <p>This module can be excluded from a build when Docker is not available with
 * {@code -Pno-oracle-test} (see the module pom).
 */
@ExtendWith(OracleDatabaseExtension.class)
class OracleDatabaseExtensionTest {
    @Test
    @OracleDatabase(name = "CAPACITY")
    void acceptsBurstsOfDedicatedClients(@JdbcNamed("CAPACITY") final JdbcConnectionSpec spec) throws Exception {
        for (var client = 0; client < 250; client++) {
            try (final var connection = connect(spec);
                    final var statement = connection.createStatement();
                    final var rows = statement.executeQuery("SELECT 1 FROM dual")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
        final var clients = new ArrayList<Connection>();
        try {
            // Exceeds the stock image's dedicated-client headroom.
            for (var client = 0; client < 160; client++) {
                clients.add(connect(spec));
            }
            for (final var connection : clients) {
                try (final var statement = connection.createStatement();
                        final var rows = statement.executeQuery("SELECT 1 FROM dual")) {
                    rows.next();
                    assertEquals(1, rows.getInt(1));
                }
            }
        } finally {
            for (final var connection : clients) {
                connection.close();
            }
        }
    }

    @Test
    @OracleDatabase(name = "CRYPTO")
    void grantsRealDbmsCryptoToSchema(@JdbcNamed("CRYPTO") final JdbcConnectionSpec spec) throws Exception {
        try (final var connection = connect(spec);
                final var statement = connection.createStatement();
                final var rows = statement.executeQuery(
                        "SELECT RAWTOHEX(DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW('abc'), 4)) FROM dual")) {
            rows.next();
            assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", rows.getString(1));
        }
    }

    @Test
    @OracleDatabase(name = "XML_REFERENCE")
    void oracleXmlTextAggregationReference(@JdbcNamed("XML_REFERENCE") final JdbcConnectionSpec spec) throws Exception {
        try (final var connection = connect(spec);
                final var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE XML_VALUES (SEQ NUMBER, PAYLOAD VARCHAR2(100))");
            statement.execute("INSERT INTO XML_VALUES VALUES (2, 'B')");
            statement.execute("INSERT INTO XML_VALUES VALUES (1, 'A<&')");
            statement.execute("INSERT INTO XML_VALUES VALUES (3, NULL)");
            try (final var rows = statement.executeQuery(
                    "SELECT RTRIM(XMLCAST(XMLAGG(XMLELEMENT(e, payload || ' + ') ORDER BY seq) AS CLOB), ' + ') FROM XML_VALUES")) {
                rows.next();
                assertEquals("A<& + B", rows.getString(1));
            }
            try (final var rows = statement.executeQuery("SELECT LEVEL FROM dual CONNECT BY LEVEL <= 0")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
                assertEquals(false, rows.next());
            }
        }
    }

    @Test
    @OracleDatabase(
            name = "SRC",
            init = {
                @DbInit(
                        value = "sql/oracle_schema.sql",
                        format = DatasetFormat.AUTO,
                        operation = DatasetOperation.CLEAN_INSERT)
            })
    void seedThenQueryOnDedicatedSchema(@JdbcNamed("SRC") final JdbcConnectionSpec spec) throws Exception {
        try (final var connection = connect(spec);
                final var statement = connection.createStatement()) {
            statement.execute("INSERT INTO PEOPLE (ID, NAME) VALUES (1, 'Alice')");
            try (final ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM PEOPLE")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "seeded schema should contain the inserted row");
            }
        }
    }

    @Test
    @OracleDatabase(
            name = "TGT",
            init = {
                @DbInit(
                        value = "sql/oracle_schema.sql",
                        format = DatasetFormat.AUTO,
                        operation = DatasetOperation.CLEAN_INSERT)
            },
            expected = {
                @DbExpected(
                        value = "datasets/oracle_people.yml",
                        format = DatasetFormat.AUTO,
                        comparison = DbComparison.ROWCOUNT)
            })
    void seedInsertAndVerifyDataset(@JdbcNamed("TGT") final JdbcConnectionSpec spec) throws Exception {
        try (final var connection = connect(spec);
                final var statement = connection.createStatement()) {
            statement.execute("INSERT INTO PEOPLE (ID, NAME) VALUES (1, 'Alice')");
            statement.execute("INSERT INTO PEOPLE (ID, NAME) VALUES (2, 'Bob')");
        }
        // verifyDataset runs in afterEach via the extension: 2 rows must match the dataset.
    }

    @Test
    @OracleDatabase(
            name = "SRC",
            init = {@DbInit(value = "sql/oracle_schema.sql")})
    @OracleDatabase(
            name = "TGT",
            init = {@DbInit(value = "sql/oracle_schema.sql")})
    void twoDatabasesUseDistinctSchemas(
            @JdbcNamed("SRC") final JdbcConnectionSpec src, @JdbcNamed("TGT") final JdbcConnectionSpec tgt)
            throws Exception {
        assertNotEquals(src.schema(), tgt.schema(), "SRC and TGT must be isolated in distinct schemas");
        try (final var srcConnection = connect(src);
                final var tgtConnection = connect(tgt);
                final var srcStatement = srcConnection.createStatement()) {
            srcStatement.execute("INSERT INTO PEOPLE (ID, NAME) VALUES (1, 'Alice')");
            try (final ResultSet rs = tgtConnection.createStatement().executeQuery("SELECT COUNT(*) FROM PEOPLE")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "TGT schema must not see SRC rows (schema isolation)");
            }
        }
    }

    private static Connection connect(final JdbcConnectionSpec spec) throws Exception {
        return DriverManager.getConnection(spec.jdbcUrl(), spec.username(), spec.password());
    }
}
