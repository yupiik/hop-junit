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
package io.yupiik.hop.junit.hop.coverage;

import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.AggregateReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.ArtifactReport;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Exports graph coverage as a flat JaCoCo XML report for GitLab merge request annotations.
 * Each enabled Hop edge is one synthetic instruction on the opening line of its source XML hop.
 * This is not Java bytecode coverage. Sources must be available and structurally match the executions.
 * Unmappable sources fail the entire export rather than manufacturing line numbers or partial totals.
 */
public final class HopCoverageJacocoRenderer {
    private final HopCoverageConfig config;

    public HopCoverageJacocoRenderer(final HopCoverageConfig config) {
        this.config = config;
    }

    /**
     * Writes the configured XML file after mapping every artifact, using a temporary sibling
     * and an atomic move where supported (otherwise a replacing move).
     * A failed export removes a previous output, but never a source or another selected report.
     *
     * @param report aggregate graph coverage
     * @throws IOException if mapping, output validation or writing fails
     */
    public void write(final AggregateReport report) throws IOException {
        final var output = config.jacocoReport().output().toAbsolutePath().normalize();
        validateOutput(output, report);
        try {
            final var packages = new TreeMap<String, Map<String, Map<Integer, Counts>>>();
            final var base = config.baseDir().toRealPath();
            for (final var artifact : report.artifacts()) {
                validateExecutions(report, artifact);
                final var source = sourcePath(artifact);
                if (!source.startsWith(config.baseDir().toAbsolutePath().normalize())
                        || !source.toRealPath().startsWith(base)) {
                    throw mappingError(artifact, "source is outside hop.coverage.basedir", null);
                }
                final var relative =
                        config.baseDir().toAbsolutePath().normalize().relativize(source);
                final var directory = relative.getParent();
                final var packageName =
                        directory == null ? "" : directory.toString().replace('\\', '/');
                final var files = packages.computeIfAbsent(packageName, ignored -> new TreeMap<>());
                final var lines = map(artifact, source);
                if (files.putIfAbsent(relative.getFileName().toString(), lines) != null) {
                    throw mappingError(artifact, "duplicate source path", null);
                }
            }
            HopCoverageFiles.write(output, temporary -> writeXml(packages, temporary));
        } catch (final IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(output);
            } catch (final IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private Path sourcePath(final ArtifactReport artifact) {
        return config.baseDir()
                .resolve(artifact.artifact().path().replace('\\', '/'))
                .toAbsolutePath()
                .normalize();
    }

    private void validateOutput(final Path output, final AggregateReport report) throws IOException {
        final var reserved = new ArrayList<Path>();
        final var physicalOutput = physicalPath(output);
        // Protect raw reports and the working lock even when those files do not exist yet.
        if (physicalOutput.startsWith(physicalPath(config.rawDir()))
                || physicalOutput.equals(physicalPath(config.workDir().resolve(".post-process.lock")))) {
            throw new IOException("JaCoCo output collides with coverage working files: " + output);
        }
        if (config.isReportFormatEnabled(HopCoverageConfig.ReportFormat.JSON)) {
            reserved.add(config.siteDir().resolve("hop-coverage.json"));
        }
        if (config.isReportFormatEnabled(HopCoverageConfig.ReportFormat.HTML)) {
            reserved.add(config.siteDir().resolve("index.html"));
        }
        if (config.isReportFormatEnabled(HopCoverageConfig.ReportFormat.ADOC)) {
            reserved.add(config.adocReport().output());
        }
        report.artifacts().forEach(artifact -> reserved.add(sourcePath(artifact)));
        for (final var path : reserved) {
            if (physicalOutput.equals(physicalPath(path))
                    || (Files.exists(output) && Files.exists(path) && Files.isSameFile(output, path))) {
                throw new IOException("JaCoCo output collides with source or another report: " + output);
            }
        }
        // A symlink output could otherwise alias an unlisted source; never follow it.
        if (Files.isSymbolicLink(output)) {
            throw new IOException("JaCoCo output must not be a symbolic link: " + output);
        }
    }

    private Path physicalPath(final Path path) throws IOException {
        final var absolute = path.toAbsolutePath().normalize();
        var ancestor = absolute;
        while (!Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
    }

    private void validateExecutions(final AggregateReport report, final ArtifactReport artifact) throws IOException {
        for (final var execution : report.executions()) {
            if (!execution.artifact().kind().equals(artifact.artifact().kind())
                    || !execution.artifact().path().equals(artifact.artifact().path())) {
                continue;
            }
            if (execution.edges().size() != artifact.edges().size()) {
                throw mappingError(artifact, "incompatible graph definitions across executions", null);
            }
            for (final var edge : execution.edges()) {
                if (artifact.edges().stream()
                        .noneMatch(merged -> merged.id().equals(edge.id())
                                && merged.from().equals(edge.from())
                                && merged.to().equals(edge.to())
                                && merged.enabled() == edge.enabled()
                                && Objects.equals(merged.kind(), edge.kind()))) {
                    throw mappingError(artifact, "incompatible edge across executions: " + edge.id(), null);
                }
            }
        }
    }

    private Map<Integer, Counts> map(final ArtifactReport artifact, final Path source) throws IOException {
        final List<SourceHop> hops;
        try {
            final var text = Files.readString(source);
            // Use the JDK provider: application Xerces versions can lack the required JAXP security properties.
            final var factory = SAXParserFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            final var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            final var handler = new SourceHandler(text, artifact.artifact().kind());
            try (final var reader = new StringReader(text)) {
                parser.parse(new InputSource(reader), handler);
            }
            hops = handler.hops;
        } catch (final ParserConfigurationException | SAXException | IOException error) {
            throw mappingError(artifact, "cannot parse source: " + error.getMessage(), error);
        }
        final var definitions = new HashMap<Endpoints, SourceHop>();
        for (final var hop : hops) {
            if (definitions.putIfAbsent(new Endpoints(hop.from(), hop.to()), hop) != null) {
                throw mappingError(artifact, "ambiguous parallel hops: " + hop.from() + " -> " + hop.to(), null);
            }
        }
        final var lines = new TreeMap<Integer, Counts>();
        for (final var edge : artifact.edges()) {
            final var hop = definitions.remove(new Endpoints(edge.from(), edge.to()));
            if (hop == null || hop.enabled() != edge.enabled() || !Objects.equals(hop.kind(), edge.kind())) {
                throw mappingError(artifact, "unmatched or changed edge " + edge.id(), null);
            }
            if (edge.enabled()) {
                lines.merge(hop.line(), new Counts(edge.covered() ? 0 : 1, edge.covered() ? 1 : 0), Counts::add);
            }
        }
        if (!definitions.isEmpty()) {
            throw mappingError(artifact, "source has hops absent from the execution: " + definitions.keySet(), null);
        }
        return lines;
    }

    private IOException mappingError(final ArtifactReport artifact, final String reason, final Throwable cause) {
        return new IOException(
                "Cannot export Hop JaCoCo coverage for " + artifact.artifact().path() + ": " + reason, cause);
    }

    private void writeXml(final Map<String, Map<String, Map<Integer, Counts>>> packages, final Path path)
            throws IOException {
        try (final var stream = Files.newOutputStream(path)) {
            final var xml = XMLOutputFactory.newFactory().createXMLStreamWriter(stream, "UTF-8");
            try {
                xml.writeStartDocument("UTF-8", "1.0");
                xml.writeStartElement("report");
                xml.writeAttribute("name", "Hop Coverage");
                var total = new Totals(0, 0, 0, 0);
                for (final var entry : packages.entrySet()) {
                    xml.writeStartElement("package");
                    xml.writeAttribute("name", entry.getKey());
                    var packageTotal = new Totals(0, 0, 0, 0);
                    for (final var file : entry.getValue().entrySet()) {
                        xml.writeStartElement("sourcefile");
                        xml.writeAttribute("name", file.getKey());
                        var fileTotal = new Totals(0, 0, 0, 0);
                        for (final var line : file.getValue().entrySet()) {
                            xml.writeEmptyElement("line");
                            xml.writeAttribute("nr", Integer.toString(line.getKey()));
                            xml.writeAttribute(
                                    "mi", Long.toString(line.getValue().missed()));
                            xml.writeAttribute(
                                    "ci", Long.toString(line.getValue().covered()));
                            xml.writeAttribute("mb", "0");
                            xml.writeAttribute("cb", "0");
                            fileTotal = fileTotal.add(new Totals(
                                    line.getValue().missed(),
                                    line.getValue().covered(),
                                    line.getValue().covered() == 0 ? 1 : 0,
                                    line.getValue().covered() > 0 ? 1 : 0));
                        }
                        counters(xml, fileTotal);
                        xml.writeEndElement();
                        packageTotal = packageTotal.add(fileTotal);
                    }
                    counters(xml, packageTotal);
                    xml.writeEndElement();
                    total = total.add(packageTotal);
                }
                counters(xml, total);
                xml.writeEndElement();
                xml.writeEndDocument();
            } finally {
                xml.close();
            }
        } catch (final XMLStreamException error) {
            throw new IOException("Unable to write Hop JaCoCo XML", error);
        }
    }

    private void counters(final XMLStreamWriter xml, final Totals total) throws XMLStreamException {
        counter(xml, "INSTRUCTION", total.missedInstructions(), total.coveredInstructions());
        counter(xml, "LINE", total.missedLines(), total.coveredLines());
    }

    private void counter(final XMLStreamWriter xml, final String type, final long missed, final long covered)
            throws XMLStreamException {
        xml.writeEmptyElement("counter");
        xml.writeAttribute("type", type);
        xml.writeAttribute("missed", Long.toString(missed));
        xml.writeAttribute("covered", Long.toString(covered));
    }

    private record Counts(long missed, long covered) {
        private Counts add(final Counts other) {
            return new Counts(missed + other.missed, covered + other.covered);
        }
    }

    private record Totals(long missedInstructions, long coveredInstructions, long missedLines, long coveredLines) {
        private Totals add(final Totals other) {
            return new Totals(
                    missedInstructions + other.missedInstructions,
                    coveredInstructions + other.coveredInstructions,
                    missedLines + other.missedLines,
                    coveredLines + other.coveredLines);
        }
    }

    private record Endpoints(String from, String to) {}

    private record SourceHop(String from, String to, boolean enabled, String kind, int line) {}

    private static final class SourceHandler extends DefaultHandler {
        private final String text;
        private final String root;
        private final String container;
        private final List<Integer> lineStarts = new ArrayList<>();
        private final List<String> elements = new ArrayList<>();
        private final List<SourceHop> hops = new ArrayList<>();
        private final Map<String, String> fields = new HashMap<>();
        private final StringBuilder content = new StringBuilder();
        private Locator locator;
        private int hopLine;

        private SourceHandler(final String text, final String kind) throws SAXException {
            this.text = text;
            root = switch (kind) {
                case "pipeline" -> "pipeline";
                case "workflow" -> "workflow";
                default -> throw new SAXException("Unsupported artifact kind: " + kind);
            };
            container = "pipeline".equals(kind) ? "order" : "hops";
            lineStarts.add(0);
            for (var i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\r') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    lineStarts.add(i + 1);
                } else if (text.charAt(i) == '\n') {
                    lineStarts.add(i + 1);
                }
            }
        }

        @Override
        public void setDocumentLocator(final Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(final String uri, final String local, final String name, final Attributes attributes)
                throws SAXException {
            if (!uri.isEmpty()) {
                throw new SAXException("Namespaced Hop source is unsupported");
            }
            elements.add(name);
            if (elements.size() == 1 && !root.equals(name)) {
                throw new SAXException("Expected source root " + root);
            }
            if (isHop()) {
                fields.clear();
                // SAX locates the end of an opening tag. Recover its opening line even when the
                // tag spans lines. Literal '<' cannot legally occur in an XML attribute value.
                final var end = lineStarts.get(locator.getLineNumber() - 1) + locator.getColumnNumber() - 2;
                final var start = text.lastIndexOf('<', Math.min(end, text.length() - 1));
                if (start < 0 || !text.startsWith("<hop", start)) {
                    throw new SAXException("Cannot locate opening hop tag");
                }
                hopLine = 1;
                while (hopLine < lineStarts.size() && lineStarts.get(hopLine) <= start) {
                    hopLine++;
                }
            }
            content.setLength(0);
        }

        private boolean isHop() {
            return elements.equals(List.of(root, container, "hop"));
        }

        @Override
        public void characters(final char[] chars, final int start, final int length) {
            content.append(chars, start, length);
        }

        @Override
        public void endElement(final String uri, final String local, final String name) throws SAXException {
            if (elements.size() == 4 && elements.subList(0, 3).equals(List.of(root, container, "hop"))) {
                if (fields.putIfAbsent(name, content.toString()) != null) {
                    throw new SAXException("Repeated hop field " + name);
                }
            }
            if (isHop()) {
                final var from = fields.get("from");
                final var to = fields.get("to");
                if (from == null || to == null) {
                    throw new SAXException("Hop has no from/to endpoints");
                }
                final var kind = "pipeline".equals(root)
                        ? null
                        : flag("unconditional") ? "unconditional" : flag("evaluation") ? "success" : "failure";
                hops.add(new SourceHop(from, to, flag("enabled"), kind, hopLine));
            }
            elements.removeLast();
            content.setLength(0);
        }

        private boolean flag(final String name) throws SAXException {
            final var value = fields.get(name);
            if ("Y".equalsIgnoreCase(value)) {
                return true;
            }
            if ("N".equalsIgnoreCase(value)) {
                return false;
            }
            throw new SAXException("Missing or invalid hop flag " + name);
        }

        @Override
        public void error(final SAXParseException error) throws SAXException {
            throw error;
        }

        @Override
        public void fatalError(final SAXParseException error) throws SAXException {
            throw error;
        }
    }
}
