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

import io.yupiik.hop.junit.database.BaseDatabaseExtension.ManagedDatabase;
import java.sql.Connection;
import java.util.Map;

/**
 * ServiceLoader hook run by {@link OracleDatabaseExtension} after the admin-marked SQL scripts, on
 * the SYSTEM admin connection. Consumers use it for owner-scoped provisioning that cannot be
 * expressed in plain SQL scripts (PL/SQL package shims, per-schema grants/synonyms, owner cleanup),
 * keeping the extension generic. If no provider is registered, nothing extra runs.
 */
public interface OracleAdminProvisioner {
    /**
     * Performs owner-scoped provisioning on the SYSTEM admin connection.
     *
     * @param admin the SYSTEM admin JDBC connection
     * @param specs the named databases the current test created (name to {@link ManagedDatabase}), for
     *     example to map owners to their schemas
     * @throws Exception any error aborting the test setup
     */
    void provision(Connection admin, Map<String, ManagedDatabase> specs) throws Exception;
}
