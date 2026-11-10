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
package io.yupiik.hop.junit.database.h2.driver;

import java.io.StringWriter;
import java.sql.Connection;
import java.sql.SQLException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;

/** H2 Java alias entry point for Oracle's query-to-XML function. */
public final class OracleXmlFunctions {
    private OracleXmlFunctions() {}

    /** Executes a single SELECT using the injected connection, including its uncommitted data. */
    public static String getXml(final Connection connection, final String query) throws SQLException {
        if (query == null) {
            return null;
        }
        final var tokens = OracleSql.tokens(query);
        // Deliberately exclude WITH (which can contain writes), multiple statements and SELECT
        // INTO/FOR UPDATE. This validates statement shape, not the safety of user-installed functions.
        if (tokens.isEmpty()
                || !tokens.getFirst().is("SELECT")
                || tokens.stream()
                        .anyMatch(t -> t.is(";")
                                || t.is("INTO")
                                || t.is("UPDATE")
                                || t.is("DELETE")
                                || t.is("INSERT")
                                || t.is("MERGE"))) {
            throw new SQLException("DBMS_XMLGEN.GETXML requires one read-only SELECT", "42000");
        }
        try (final var statement = connection.createStatement();
                final var rows = statement.executeQuery(new OracleToH2SqlRewriter().apply(query))) {
            if (!rows.next()) {
                return null;
            }
            final var out = new StringWriter();
            final var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            try {
                writer.writeStartDocument("UTF-8", "1.0");
                writer.writeStartElement("ROWSET");
                final var metadata = rows.getMetaData();
                do {
                    writer.writeStartElement("ROW");
                    for (var col = 1; col <= metadata.getColumnCount(); col++) {
                        final var value = rows.getString(col);
                        if (value == null) {
                            continue;
                        }
                        final var name = metadata.getColumnLabel(col);
                        if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                            throw new SQLException("Unsupported XML column name: " + name, "0A000");
                        }
                        writer.writeStartElement(name);
                        writer.writeCharacters(value);
                        writer.writeEndElement();
                    }
                    writer.writeEndElement();
                } while (rows.next());
                writer.writeEndElement();
                writer.writeEndDocument();
            } finally {
                writer.close();
            }
            return out.toString();
        } catch (final XMLStreamException e) {
            throw new SQLException("Cannot serialize DBMS_XMLGEN result", e);
        }
    }
}
