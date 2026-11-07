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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.database.h2.driver.OracleToH2SqlRewriter;
import org.junit.jupiter.api.Test;

class OracleToH2SqlRewriterTest {
    @Test
    void partitionTargetedWritesAreNeverBroadened() {
        for (final var sql : new String[] {
            "DELETE FROM orders PARTITION (p1)",
            "UPDATE orders PARTITION(p1) SET id=2",
            "INSERT INTO orders PARTITION(p1) VALUES(1)",
            "TRUNCATE TABLE orders PARTITION(p1)"
        }) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> rewriter.apply(sql));
        }
        assertEquals(
                "SELECT 'DELETE PARTITION(p1)' FROM dual", rewriter.apply("SELECT 'DELETE PARTITION(p1)' FROM dual"));
    }

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
    void dbmsCryptoHashSh1UsesH2Sha1() {
        final var sql = "SELECT DBMS_CRYPTO.HASH(payload, 3) FROM SA.T";
        assertEquals("SELECT HASH('SHA-1', payload) FROM SA.T", rewriter.apply(sql));
    }

    @Test
    void dbmsCryptoHashSh256UsesH2Sha256() {
        final var sql = "SELECT DBMS_CRYPTO.HASH(payload, 4) FROM SA.T";
        assertEquals("SELECT HASH('SHA-256', payload) FROM SA.T", rewriter.apply(sql));
    }

    @Test
    void unsupportedDbmsCryptoHashAlgorithmIsPreserved() {
        final var sql = "SELECT DBMS_CRYPTO.HASH(payload, 99) FROM SA.T";
        assertEquals(sql, rewriter.apply(sql));
    }

    @Test
    void dbmsCryptoHashNamedConstantUsesH2Sha1() {
        final var sql = "SELECT DBMS_CRYPTO.HASH(payload, DBMS_CRYPTO.HASH_SH1) FROM SA.T";
        assertEquals("SELECT HASH('SHA-1', payload) FROM SA.T", rewriter.apply(sql));
    }

    @Test
    void quotedSchemaPrefixIsPreserved() {
        assertEquals("SELECT * FROM \"SA\".CUSTOMER", rewriter.apply("SELECT * FROM \"SA\".CUSTOMER"));
    }

    @Test
    void connectByFourTranchesIsExpanded() {
        final var sql = "SELECT LEVEL - 1 AS tranche_id\n  FROM dual\n  CONNECT BY LEVEL <= 4";
        assertTrue(rewriter.apply(sql).contains("WITH RECURSIVE"));
    }

    @Test
    void xmlAggregationIsReplacedByListAgg() {
        final var sql = "SELECT RTRIM(XMLCAST(XMLAGG(XMLELEMENT(e, quoted_column || ',') "
                + "ORDER BY column_id) AS CLOB), ',') AS columns FROM SA.T";
        final var rewritten = rewriter.apply(sql);
        assertTrue(rewritten.contains("LISTAGG(quoted_column || ','"), () -> rewritten);
    }

    @Test
    void dateMinusNumericCaseUsesGenericDayArithmetic() {
        final var sql = "DATE '2042-07-19' - CASE mode WHEN 'fast' THEN 2 ELSE TO_NUMBER('5') END";
        assertEquals(
                "(CAST(DATE '2042-07-19'  AS TIMESTAMP) - (INTERVAL '1 00:00:00' DAY TO SECOND * CAST(("
                        + "CASE mode WHEN 'fast' THEN 2 ELSE TO_NUMBER('5') END) AS NUMERIC(38, 10))))",
                rewriter.apply(sql));
    }

    @Test
    void unknownOrMixedCaseResultsAreNotAssumedNumeric() {
        for (final var operand : new String[] {
            "CASE WHEN flag = 1 THEN delay ELSE 0 END",
            "CASE WHEN flag = 1 THEN 3 ELSE DATE '2042-07-19' END",
            "(CASE WHEN flag = 1 THEN '3' ELSE 0 END)",
            "CASE WHEN flag = 1 THEN 3 ELSE CASE WHEN flag = 2 THEN other_delay ELSE 0 END END"
        }) {
            final var sql = "SELECT DATE '2042-07-19' - " + operand + " FROM measures";
            assertEquals(sql, rewriter.apply(sql));
        }
    }

    @Test
    void rowidToCharRowidIsReplacedForHashBandSplit() {
        final var sql = "SELECT /*+ FULL(t) PARALLEL(t,4) */ SYSTEM_ID "
                + "FROM ORDERS PARTITION(\"P20260101\") t "
                + "WHERE MOD(ORA_HASH(ROWIDTOCHAR(t.ROWID)), 4) = 1";
        final var rewritten = rewriter.apply(sql);
        assertTrue(rewritten.contains("CAST(t._ROWID_ AS VARCHAR)"), () -> rewritten);
        assertFalse(rewritten.contains("ROWIDTOCHAR"), () -> rewritten);
        assertFalse(rewritten.contains("PARTITION("), () -> rewritten);
        assertTrue(rewritten.contains("FROM ORDERS t"), () -> rewritten);
    }

    @Test
    void plainSqlWithoutRowidConstructIsUnchanged() {
        final var sql = "SELECT ID FROM SA.CUSTOMER WHERE ID > 0";
        assertSame(sql, rewriter.apply(sql));
    }

    @Test
    void oracleAlternativeQuotedLiteralsAreNotRewritten() {
        final var sql = "SELECT q'[don't rewrite ROWIDTOCHAR(t.ROWID) FROM T PARTITION(P)]', "
                + "Q'{don't rewrite DBMS_CRYPTO.HASH(x,4)}' FROM dual";
        assertSame(sql, rewriter.apply(sql));
    }
}
