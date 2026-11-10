/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.hop.junit.database.dataset;

import io.yupiik.hop.junit.database.BaseDatabaseExtension;
import io.yupiik.hop.junit.database.DatabaseImplementation;
import io.yupiik.hop.junit.database.DatasetFormat;
import io.yupiik.hop.junit.database.DatasetOperation;
import io.yupiik.hop.junit.database.DbExpected;
import io.yupiik.hop.junit.database.DbInit;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.dbunit.assertion.DbUnitAssert;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.SortedTable;
import org.dbunit.dataset.csv.CsvDataSet;
import org.dbunit.dataset.excel.XlsDataSet;
import org.dbunit.dataset.json.JsonDataSet;
import org.dbunit.dataset.yaml.YamlDataSet;
import org.dbunit.ext.h2.H2DataTypeFactory;
import org.dbunit.ext.oracle.OracleDataTypeFactory;
import org.dbunit.operation.DatabaseOperation;
import org.dbunit.util.fileloader.FlatXmlDataFileLoader;
import org.dbunit.util.fileloader.FullXmlDataFileLoader;

/**
 * Headless DBUnit integration: loads {@link DbInit} datasets (and SQL schemas) to seed a named
 * database before a test and verifies {@link DbExpected} datasets against it after the test.
 *
 * <p>DBUnit is used purely as a <em>library</em> here - never through its own JUnit extension or
 * annotations, which are single-connection and cannot express the framework's named multi-database
 * model. {@link BaseDatabaseExtension} orchestrates seeding/verification through this class (via the
 * {@code seedDataset}/{@code verifyDataset} hooks), one live JDBC connection per named database.
 */
public class DbUnitEngine {
    private final DatasetResourceResolver resolver;
    private final SqlScriptRunner sqlScriptRunner;

    public DbUnitEngine() {
        this(new DatasetResourceResolver(), new SqlScriptRunner());
    }

    public DbUnitEngine(final DatasetResourceResolver resolver, final SqlScriptRunner sqlScriptRunner) {
        this.resolver = resolver;
        this.sqlScriptRunner = sqlScriptRunner;
    }

    /**
     * Run each {@link DbInit}: {@code *.sql} through {@link SqlScriptRunner}, other paths as DBUnit
     * datasets.
     *
     * @param connection the live JDBC connection of the named database
     * @param schema the schema to qualify (may be empty or null)
     * @param inits the {@link DbInit}s to apply
     */
    public void seed(final Connection connection, final String schema, final DbInit[] inits) throws Exception {
        for (final var init : inits) {
            if (init.value().toLowerCase(Locale.ROOT).endsWith(".sql")) {
                sqlScriptRunner.executeInitScripts(connection, new String[] {init.value()});
            } else {
                final var dataset = load(init.value(), init.format());
                operation(init.operation()).execute(wrap(connection, schema, init.caseSensitive()), dataset);
            }
        }
    }

    /**
     * Verify each {@link DbExpected} against the current database state.
     *
     * @param connection the live JDBC connection of the named database
     * @param schema the schema to qualify (may be empty or null)
     * @param expecteds the {@link DbExpected}s to verify
     */
    public void verify(final Connection connection, final String schema, final DbExpected[] expecteds)
            throws Exception {
        if (expecteds == null || expecteds.length == 0) {
            return;
        }
        final var assertThat = new DbUnitAssert();
        for (final var expected : expecteds) {
            final var expectedDataset = load(expected.value(), expected.format());
            verify(assertThat, expectedDataset, connection, schema, expected);
        }
    }

    private void verify(
            final DbUnitAssert assertThat,
            final IDataSet expectedDataset,
            final Connection connection,
            final String schema,
            final DbExpected expected)
            throws Exception {
        final var tableNames = verifyTableNames(expectedDataset, expected.ignoreTable());
        if (tableNames.length == 0) {
            return;
        }
        final var actualDataset =
                wrap(connection, schema, expected.caseSensitive()).createDataSet(tableNames);

        for (final var tableName : tableNames) {
            final var expectedTable = expectedDataset.getTable(tableName);
            final var actualTable = actualDataset.getTable(tableName);
            final var ignored = ignoreColumnsFor(tableName, expected.ignoreColumn());
            switch (expected.comparison()) {
                case ROWCOUNT -> assertRowCount(tableName, expectedTable, actualTable);
                case ORDERED -> compareOrdered(assertThat, expectedTable, actualTable, ignored);
                case STRICT -> {
                    final var sortedExpected = new SortedTable(expectedTable);
                    final var sortedActual = new SortedTable(actualTable);
                    if (ignored.isEmpty()) {
                        assertThat.assertEquals(sortedExpected, sortedActual);
                    } else {
                        assertThat.assertEqualsIgnoreCols(sortedExpected, sortedActual, ignored.toArray(String[]::new));
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported comparison: " + expected.comparison());
            }
        }
    }

    private static String[] verifyTableNames(final IDataSet expectedDataset, final String[] ignoreTable)
            throws Exception {
        final var tableNames = expectedDataset.getTableNames();
        if (ignoreTable.length == 0) {
            return tableNames;
        }
        final var result = new ArrayList<String>();
        for (final var name : tableNames) {
            final var skip = Arrays.stream(ignoreTable).anyMatch(name::equalsIgnoreCase);
            if (!skip) {
                result.add(name);
            }
        }
        return result.toArray(String[]::new);
    }

    private static void compareOrdered(
            final DbUnitAssert assertThat, final ITable expected, final ITable actual, final List<String> ignored)
            throws Exception {
        if (ignored.isEmpty()) {
            assertThat.assertEquals(expected, actual);
        } else {
            assertThat.assertEqualsIgnoreCols(expected, actual, ignored.toArray(String[]::new));
        }
    }

    private static void assertRowCount(final String tableName, final ITable expected, final ITable actual) {
        final var expectedRows = expected.getRowCount();
        final var actualRows = actual.getRowCount();
        if (expectedRows != actualRows) {
            throw new AssertionError(
                    "Table " + tableName + ": expected " + expectedRows + " rows but found " + actualRows);
        }
    }

    private static List<String> ignoreColumnsFor(final String tableName, final String[] declared) {
        final var ignored = new ArrayList<String>();
        for (final var column : declared) {
            final var idx = column.indexOf('.');
            if (idx < 0) {
                ignored.add(column);
            } else if (column.substring(0, idx).equalsIgnoreCase(tableName)) {
                ignored.add(column.substring(idx + 1));
            }
        }
        return ignored;
    }

    /** Load a DBUnit dataset from a filesystem file or classpath resource, inferring the format from the extension. */
    private IDataSet load(final String resource, final DatasetFormat declared) throws Exception {
        final var format = declared == null || declared == DatasetFormat.AUTO ? inferFormat(resource) : declared;
        // CSV datasets are directories (<dir>/<TABLE>.csv); the other formats are single files.
        final var path = (format == DatasetFormat.CSV
                        ? resolver.resolveDirectory(resource)
                        : resolver.resolve(resource))
                .orElseThrow(() ->
                        new IllegalArgumentException("Missing test resource (filesystem or classpath): " + resource));
        final var url = path.toUri().toURL();
        return switch (format) {
            case AUTO -> throw new IllegalArgumentException("Unable to infer dataset format for: " + resource);
            case FLAT_XML -> new FlatXmlDataFileLoader().loadDataSet(url);
            case FULL_XML -> new FullXmlDataFileLoader().loadDataSet(url);
            case YAML -> new YamlDataSet(java.nio.file.Files.newInputStream(path));
            case JSON -> new JsonDataSet(java.nio.file.Files.newInputStream(path));
            case XLS -> new XlsDataSet(java.nio.file.Files.newInputStream(path));
            case CSV -> new CsvDataSet(path.toFile());
        };
    }

    /** Wrap a raw JDBC connection in a DBUnit connection; schema-qualified when a schema is provided. */
    private IDatabaseConnection wrap(final Connection connection, final String schema, final boolean caseSensitive)
            throws Exception {
        final var wrapped = schema == null || schema.isBlank()
                ? new DatabaseConnection(connection)
                : new DatabaseConnection(connection, schema);
        final var config = wrapped.getConfig();
        config.setProperty(DatabaseConfig.FEATURE_CASE_SENSITIVE_TABLE_NAMES, caseSensitive);
        config.setProperty(
                DatabaseConfig.PROPERTY_DATATYPE_FACTORY,
                DatabaseImplementation.current() == DatabaseImplementation.ORACLE
                        ? new OracleDataTypeFactory()
                        : new H2DataTypeFactory());
        return wrapped;
    }

    private static DatabaseOperation operation(final DatasetOperation operation) {
        return switch (operation) {
            case INSERT -> DatabaseOperation.INSERT;
            case REPLACE -> DatabaseOperation.REFRESH;
            case DELETE -> DatabaseOperation.DELETE;
            case DELETE_ALL -> DatabaseOperation.DELETE_ALL;
            case TRUNCATE_TABLE -> DatabaseOperation.TRUNCATE_TABLE;
            case CLEAN_INSERT -> DatabaseOperation.CLEAN_INSERT;
        };
    }

    private static DatasetFormat inferFormat(final String resource) {
        final var lower = resource.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xml")) {
            return DatasetFormat.FLAT_XML;
        }
        if (lower.endsWith(".csv")) {
            return DatasetFormat.CSV;
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return DatasetFormat.YAML;
        }
        if (lower.endsWith(".json")) {
            return DatasetFormat.JSON;
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return DatasetFormat.XLS;
        }
        throw new IllegalArgumentException("Unable to infer dataset format from extension: " + resource);
    }
}
