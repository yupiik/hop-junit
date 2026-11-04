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
package io.yupiik.hop.junit.database.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.database.h2.driver.OracleToH2SqlRewriter;
import org.junit.jupiter.api.Test;

class OracleToH2SqlRewriterTest {
    private final OracleToH2SqlRewriter rewriter = new OracleToH2SqlRewriter();

    @Test
    void nullAndBlankAreReturnedUnchanged() {
        assertNull(rewriter.apply(null));
        assertEquals("", rewriter.apply(""));
        assertEquals("   ", rewriter.apply("   "));
    }

    @Test
    void plainSqlIsUnchanged() {
        final var sql = "SELECT ID, NAME FROM SA.CUSTOMER WHERE ID = ?";
        assertSame(sql, rewriter.apply(sql));
    }

    @Test
    void partitionClauseWithAliasIsRemoved() {
        final var sql = "SELECT * FROM SA.ORDERS PARTITION(P202601) O WHERE O.ID = 1";
        assertEquals("SELECT * FROM SA.ORDERS O WHERE O.ID = 1", rewriter.apply(sql));
    }

    @Test
    void partitionClauseWithoutAliasIsRemoved() {
        final var sql = "SELECT * FROM SA.ORDERS PARTITION(P202601)";
        assertEquals("SELECT * FROM SA.ORDERS", rewriter.apply(sql));
    }

    @Test
    void partitionJoinClauseIsRemoved() {
        final var sql = "SELECT * FROM SA.A A JOIN SA.B PARTITION(P1) B ON A.ID = B.ID";
        assertEquals("SELECT * FROM SA.A A JOIN SA.B B ON A.ID = B.ID", rewriter.apply(sql));
    }

    @Test
    void dbmsCryptoHashSh1IsStripped() {
        final var sql = "SELECT DBMS_CRYPTO.HASH(payload, 4) FROM SA.T";
        assertEquals("SELECT payload FROM SA.T", rewriter.apply(sql));
    }

    @Test
    void dbmsCryptoHashNamedConstantIsStripped() {
        final var sql = "SELECT DBMS_CRYPTO.HASH(payload, DBMS_CRYPTO.HASH_SH1) FROM SA.T";
        assertEquals("SELECT payload FROM SA.T", rewriter.apply(sql));
    }

    @Test
    void knownTestSchemaPrefixIsStripped() {
        assertEquals("SELECT * FROM CUSTOMER", rewriter.apply("SELECT * FROM \"SA\".CUSTOMER"));
    }

    @Test
    void connectByFourTranchesIsExpanded() {
        final var sql = "SELECT LEVEL - 1 AS tranche_id\n  FROM dual\n  CONNECT BY LEVEL <= 4";
        assertEquals(
                "SELECT 0 AS tranche_id FROM dual UNION ALL SELECT 1 FROM dual"
                        + " UNION ALL SELECT 2 FROM dual UNION ALL SELECT 3 FROM dual",
                rewriter.apply(sql));
    }

    @Test
    void xmlAggregationIsReplacedByListAgg() {
        final var sql = "SELECT RTRIM(XMLCAST(XMLAGG(XMLELEMENT(e, quoted_column || ',') "
                + "ORDER BY column_id) AS CLOB), ',') AS columns FROM SA.T";
        final var rewritten = rewriter.apply(sql);
        assertTrue(
                rewritten.contains("LISTAGG(quoted_column, ',') WITHIN GROUP (ORDER BY column_id)"), () -> rewritten);
    }

    @Test
    void sysdateMinusRetentionIsConvertedToDateAdd() {
        final var sql = "TRUNC(SYSDATE) - CASE WHEN REGEXP_LIKE(TRIM('-30'), '^-?[0-9]+$') "
                + "THEN TO_NUMBER(TRIM('-30')) ELSE 0 END";
        assertEquals("DATEADD('DAY', -30, CURRENT_DATE)", rewriter.apply(sql));
    }
}
