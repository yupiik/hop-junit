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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.hop.coverage.HopCoverageConfig.ReportFormat;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.AggregateReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Artifact;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.ArtifactReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Edge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.w3c.dom.Document;

@Isolated("Temporarily overrides the application SAX parser factory")
class HopCoverageJacocoRendererTest {
    @Test
    void ignoresApplicationSaxParserFactoryOverride(final @TempDir Path work) throws Exception {
        final var property = "javax.xml.parsers.SAXParserFactory";
        final var previous = System.getProperty(property);
        try {
            // Export must not select an application provider lacking the required JAXP protections.
            System.setProperty(property, "unsupported.application.SAXParserFactory");
            writeSource(work, "p.hpl", "<pipeline><order>" + pipelineHop("A", "B", true) + "</order></pipeline>");
            final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
            final var artifact = artifact("pipeline", "p.hpl", edge("A", "B", true, true, null));
            new HopCoverageJacocoRenderer(config).write(report(artifact));
            counters(readXml(config.jacocoReport().output()), "/report", 0, 1, 0, 1);

            writeSource(
                    work,
                    "p.hpl",
                    "<!DOCTYPE pipeline [<!ENTITY endpoint 'A'>]>" + "<pipeline><order>"
                            + pipelineHop("&endpoint;", "B", true) + "</order></pipeline>");
            assertMappingFailure(config, artifact);
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void mapsOpeningLinesWithCrLfMultilineTagsCommentsAndEscapedEndpoints(final @TempDir Path work) throws Exception {
        final var source = """
                <?xml version="1.0" encoding="UTF-8"?>
                <pipeline>
                <!-- <hop><from>fake</from></hop> -->
                <order>
                  <!-- non-ASCII before the tag: \u00e9 --> <hop
                    note="multiline">
                    <from>Entr\u00e9e &amp; &lt;source&gt;</from>
                    <to>Sortie &quot;A&quot;</to><enabled>Y</enabled>
                  </hop>
                  <hop><from>Sortie &quot;A&quot;</from><to>Idle</to><enabled>N</enabled></hop>
                  <hop><from>Idle</from><to>End</to><enabled>Y</enabled></hop>
                </order>
                <transform><hop><from>not an order hop</from></hop></transform>
                </pipeline>
                """.replace("\n", "\r\n");
        writeSource(work, "src/p & q.hpl", source);
        final var config = config(work, work.resolve("custom/export.xml"), Set.of(ReportFormat.JACOCO));
        new HopCoverageJacocoRenderer(config)
                .write(report(artifact(
                        "pipeline",
                        "src/p & q.hpl",
                        edge("Entr\u00e9e & <source>", "Sortie \"A\"", true, true, null),
                        edge("Sortie \"A\"", "Idle", false, true, null),
                        edge("Idle", "End", true, false, null))));

        final var xml = readXml(config.jacocoReport().output());
        assertEquals("p & q.hpl", value(xml, "/report/package/sourcefile/@name"));
        assertEquals("src", value(xml, "/report/package/@name"));
        assertEquals("2", value(xml, "count(//line)"));
        line(xml, "/report/package/sourcefile/line[1]", 5, 0, 1);
        line(xml, "/report/package/sourcefile/line[2]", 11, 1, 0);
        for (final var scope : List.of("/report", "/report/package", "/report/package/sourcefile")) {
            counters(xml, scope, 1, 1, 1, 1);
        }
        assertFalse(Files.exists(work.resolve("site/jacoco.xml")), "must honor the configured output");
    }

    @Test
    void aggregatesInstructionsOnOneLineWithoutInventingJavaStructure(final @TempDir Path work) throws Exception {
        writeSource(
                work,
                "p.hpl",
                "<pipeline><order>\n"
                        + pipelineHop("A", "B", true) + pipelineHop("A", "C", true)
                        + pipelineHop("A", "D", false) + "\n</order></pipeline>");
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        new HopCoverageJacocoRenderer(config)
                .write(report(artifact(
                        "pipeline",
                        "p.hpl",
                        edge("A", "B", true, true, null),
                        edge("A", "C", true, false, null),
                        edge("A", "D", false, false, null))));

        final var xml = readXml(config.jacocoReport().output());
        assertEquals("", value(xml, "/report/package/@name"));
        assertEquals("1", value(xml, "count(//line)"));
        line(xml, "//line", 2, 1, 1);
        for (final var scope : List.of("/report", "/report/package", "/report/package/sourcefile")) {
            counters(xml, scope, 1, 1, 0, 1);
        }
        assertEquals("0", value(xml, "count(//group | //class | //method | //sessioninfo)"));
        assertEquals("0", value(xml, "count(//counter[@type!='INSTRUCTION' and @type!='LINE'])"));
        assertFalse(Files.readString(config.jacocoReport().output()).contains(".java"));
    }

    @Test
    void distinguishesModulePathsAndSortsPackagesFilesAndLinesDeterministically(final @TempDir Path work)
            throws Exception {
        final var source = "<pipeline><order>\n" + pipelineHop("A", "B", true) + "\n" + pipelineHop("B", "C", true)
                + "\n</order></pipeline>";
        for (final var path : List.of("module-b/p.hpl", "module-a/p.hpl", "module-a/a.hpl", "root.hpl")) {
            writeSource(work, path, source);
        }
        final var missed = edge("B", "C", true, false, null);
        final var covered = edge("A", "B", true, true, null);
        final var b = artifact("pipeline", "module-b/p.hpl", missed, covered);
        final var a = artifact("pipeline", "module-a/p.hpl", missed, covered);
        final var first = artifact("pipeline", "module-a/a.hpl", missed, covered);
        final var root = artifact("pipeline", "root.hpl", missed, covered);
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        final var renderer = new HopCoverageJacocoRenderer(config);
        renderer.write(report(b, a, root, first));
        final var original = Files.readString(config.jacocoReport().output());
        renderer.write(report(first, root, a, b));
        assertEquals(original, Files.readString(config.jacocoReport().output()));
        final var xml = readXml(config.jacocoReport().output());
        assertEquals("3", value(xml, "count(/report/package)"));
        assertEquals("", value(xml, "/report/package[1]/@name"));
        assertEquals("module-a", value(xml, "/report/package[2]/@name"));
        assertEquals("module-b", value(xml, "/report/package[3]/@name"));
        assertEquals("a.hpl", value(xml, "/report/package[2]/sourcefile[1]/@name"));
        assertEquals("p.hpl", value(xml, "/report/package[2]/sourcefile[2]/@name"));
        assertEquals("p.hpl", value(xml, "/report/package[3]/sourcefile/@name"));
        line(xml, "/report/package[2]/sourcefile[1]/line[1]", 2, 0, 1);
        line(xml, "/report/package[2]/sourcefile[1]/line[2]", 3, 1, 0);
        counters(xml, "/report/package[2]/sourcefile[1]", 1, 1, 1, 1);
        counters(xml, "/report/package[2]", 2, 2, 2, 2);
        counters(xml, "/report", 4, 4, 4, 4);
    }

    @Test
    void mapsXmlGeneratedByRealHopPipelineMetadata(final @TempDir Path work) throws Exception {
        // Generated with Hop 2.20 PipelineMeta.getXml(Variables), with one PipelineHopMeta.
        // A checked-in fixture keeps Hop's intentionally excluded OWASP serializer off the test classpath.
        final var source = fixture("pipeline.hpl");
        writeSource(work, "real.hpl", source);
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        new HopCoverageJacocoRenderer(config)
                .write(report(
                        artifact("pipeline", "real.hpl", edge("Source & \u00e9", "Target <end>", true, true, null))));
        final var xml = readXml(config.jacocoReport().output());
        assertEquals("1", value(xml, "count(//line)"));
        line(xml, "//line", openingLine(source, 1), 0, 1);
        counters(xml, "/report", 0, 1, 0, 1);
    }

    @Test
    void mapsRealWorkflowUnconditionalSuccessFailureAndDisabledHops(final @TempDir Path work) throws Exception {
        // Generated with Hop 2.20 WorkflowMeta.getXml(Variables), with four WorkflowHopMeta instances.
        final var source = fixture("workflow.hwf");
        writeSource(work, "real.hwf", source);
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        new HopCoverageJacocoRenderer(config)
                .write(report(artifact(
                        "workflow",
                        "real.hwf",
                        edge("Start & \u00e9", "Next <step>", true, true, "unconditional"),
                        edge("Next <step>", "Success", true, true, "success"),
                        edge("Next <step>", "Failure", true, false, "failure"),
                        edge("Start & \u00e9", "Disabled", false, false, "unconditional"))));
        final var xml = readXml(config.jacocoReport().output());
        assertEquals("3", value(xml, "count(//line)"));
        line(xml, "(//line)[1]", openingLine(source, 1), 0, 1);
        line(xml, "(//line)[2]", openingLine(source, 2), 0, 1);
        line(xml, "(//line)[3]", openingLine(source, 3), 1, 0);
        counters(xml, "/report", 1, 2, 1, 2);
        assertMappingFailure(
                config,
                artifact(
                        "workflow",
                        "real.hwf",
                        edge("Start & \u00e9", "Next <step>", true, true, "unconditional"),
                        edge("Next <step>", "Success", true, true, "success"),
                        edge("Next <step>", "Failure", true, false, "success"),
                        edge("Start & \u00e9", "Disabled", false, false, "unconditional")));
        assertEquals(source, Files.readString(work.resolve("real.hwf")));
    }

    @Test
    void missingMalformedAndDoctypeSourcesRemoveStaleOutput(final @TempDir Path work) throws Exception {
        final var secret = writeSource(work, "secret.txt", "DO NOT RESOLVE");
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        final var invalid = List.of(
                "<pipeline><order>",
                "<!DOCTYPE pipeline [<!ENTITY xxe SYSTEM '" + secret.toUri() + "'>]>"
                        + "<pipeline><order><hop><from>&xxe;</from><to>B</to><enabled>Y</enabled>"
                        + "</hop></order></pipeline>");
        // Missing source is checked separately so it is not accidentally created by the fixture.
        assertMappingFailure(config, artifact("pipeline", "missing.hpl", edge("A", "B", true, true, null)));
        for (final var source : invalid) {
            writeSource(work, "invalid.hpl", source);
            assertMappingFailure(config, artifact("pipeline", "invalid.hpl", edge("A", "B", true, true, null)));
        }
        assertEquals("DO NOT RESOLVE", Files.readString(secret));
    }

    @Test
    void rejectsTraversalAndSymlinkOutsideBaseDirectory(final @TempDir Path work) throws Exception {
        final var base = Files.createDirectories(work.resolve("base"));
        final var source = "<pipeline><order>" + pipelineHop("A", "B", true) + "</order></pipeline>";
        final var outside = writeSource(work, "outside.hpl", source);
        final var config = config(base, base.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        assertMappingFailure(config, artifact("pipeline", "../outside.hpl", edge("A", "B", true, true, null)));
        Files.createSymbolicLink(base.resolve("linked.hpl"), outside);
        assertMappingFailure(config, artifact("pipeline", "linked.hpl", edge("A", "B", true, true, null)));
        assertEquals(source, Files.readString(outside));
        assertTrue(Files.isSymbolicLink(base.resolve("linked.hpl")));
    }

    @Test
    void rejectsChangedMissingExtraAndAmbiguousEdgesWithoutPartialOutput(final @TempDir Path work) throws Exception {
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        writeSource(work, "p.hpl", "<pipeline><order>" + pipelineHop("A", "B", true) + "</order></pipeline>");
        for (final var artifact : List.of(
                artifact("pipeline", "p.hpl", edge("A", "C", true, true, null)),
                artifact("pipeline", "p.hpl", edge("A", "B", false, true, null)),
                artifact("pipeline", "p.hpl", edge("A", "B", true, true, "success")),
                artifact("pipeline", "p.hpl"),
                artifact("pipeline", "p.hpl", edge("A", "B", true, true, null), edge("B", "C", true, false, null)))) {
            assertMappingFailure(config, artifact);
        }
        writeSource(
                work,
                "p.hpl",
                "<pipeline><order>" + pipelineHop("A", "B", true) + pipelineHop("A", "B", true)
                        + "</order></pipeline>");
        assertMappingFailure(config, artifact("pipeline", "p.hpl", edge("A", "B", true, true, null)));
    }

    @Test
    void outputCollisionsPreserveSourcesAndOtherEnabledReports(final @TempDir Path work) throws Exception {
        final var source =
                writeSource(work, "p.hpl", "<pipeline><order>" + pipelineHop("A", "B", true) + "</order></pipeline>");
        final var report = report(artifact("pipeline", "p.hpl", edge("A", "B", true, true, null)));
        final var sourceText = Files.readString(source);
        for (final var output : List.of(
                source,
                work.resolve("site/hop-coverage.json"),
                work.resolve("site/index.html"),
                work.resolve("site/coverage.adoc"))) {
            if (!output.equals(source)) {
                writeSource(work, work.relativize(output).toString(), "existing report");
            }
            final var before = Files.readString(output);
            final var config = config(work, output, Set.of(ReportFormat.values()));
            assertThrows(IOException.class, () -> new HopCoverageJacocoRenderer(config).write(report));
            assertEquals(before, Files.readString(output), output.toString());
            assertEquals(sourceText, Files.readString(source));
        }
    }

    @Test
    void doesNotPublishPartialTotalsWhenALaterSourceFails(final @TempDir Path work) throws Exception {
        writeSource(work, "good.hpl", "<pipeline><order>" + pipelineHop("A", "B", true) + "</order></pipeline>");
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        final var good = artifact("pipeline", "good.hpl", edge("A", "B", true, true, null));
        final var renderer = new HopCoverageJacocoRenderer(config);
        renderer.write(report(good));
        assertTrue(Files.exists(config.jacocoReport().output()));
        assertThrows(
                IOException.class,
                () -> renderer.write(
                        report(good, artifact("pipeline", "missing.hpl", edge("A", "B", true, true, null)))));
        assertFalse(Files.exists(config.jacocoReport().output()));
    }

    @Test
    void outputSymlinkCannotOverwriteAnUnlistedSource(final @TempDir Path work) throws Exception {
        final var source = writeSource(work, "unlisted.hpl", "keep source");
        final var output = work.resolve("jacoco.xml");
        Files.createSymbolicLink(output, source);
        final var config = config(work, output, Set.of(ReportFormat.JACOCO));
        assertThrows(IOException.class, () -> new HopCoverageJacocoRenderer(config).write(report()));
        assertTrue(Files.isSymbolicLink(output));
        assertEquals("keep source", Files.readString(source));
    }

    @Test
    void rejectsIncompatibleGraphDefinitionsAcrossExecutions(final @TempDir Path work) throws Exception {
        writeSource(work, "p.hpl", "<pipeline><order>" + pipelineHop("A", "B", true) + "</order></pipeline>");
        final var config = config(work, work.resolve("site/jacoco.xml"), Set.of(ReportFormat.JACOCO));
        final var artifact = artifact("pipeline", "p.hpl", edge("A", "B", true, true, null));
        final var changed = new HopCoverageModels.Execution(
                "changed",
                "pipeline",
                artifact.artifact(),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(edge("A", "B", false, true, null)),
                List.of());
        final var report =
                new AggregateReport(1, "2026-11-20T00:00:00Z", null, List.of(artifact), List.of(changed), List.of());
        Files.createDirectories(config.jacocoReport().output().getParent());
        Files.writeString(config.jacocoReport().output(), "stale XML");
        final var error = assertThrows(IOException.class, () -> new HopCoverageJacocoRenderer(config).write(report));
        assertTrue(error.getMessage().contains("incompatible edge"));
        assertFalse(Files.exists(config.jacocoReport().output()));
    }

    private HopCoverageConfig config(final Path base, final Path output, final Set<ReportFormat> formats) {
        return new HopCoverageConfig(
                true,
                base,
                base.resolve("work"),
                base.resolve("work/raw"),
                base.resolve("site"),
                true,
                true,
                OptionalDouble.empty(),
                Map.of(),
                formats,
                new HopCoverageConfig.HtmlReport("Hop", "Graph coverage", null, "Edges"),
                new HopCoverageConfig.AdocReport(
                        base.resolve("site/coverage.adoc"), "Hop", "Edges", false, HopCoverageConfig.Language.EN),
                new HopCoverageConfig.JacocoReport(output));
    }

    private ArtifactReport artifact(final String kind, final String path, final Edge... edges) {
        return new ArtifactReport(new Artifact(kind, path, path), null, List.of(), List.of(edges), List.of(), null);
    }

    private AggregateReport report(final ArtifactReport... artifacts) {
        return new AggregateReport(1, "2026-11-20T00:00:00Z", null, List.of(artifacts), List.of(), List.of());
    }

    private Edge edge(
            final String from, final String to, final boolean enabled, final boolean covered, final String kind) {
        return new Edge(from + " -> " + to, from, to, enabled, covered, kind, 0, covered, covered);
    }

    private String pipelineHop(final String from, final String to, final boolean enabled) {
        return "<hop><from>" + from + "</from><to>" + to + "</to><enabled>" + (enabled ? "Y" : "N")
                + "</enabled></hop>";
    }

    private String fixture(final String name) throws IOException {
        try (final var input = getClass().getResourceAsStream("/coverage/jacoco/" + name)) {
            return new String(input.readAllBytes(), UTF_8);
        }
    }

    private Path writeSource(final Path base, final String path, final String source) throws IOException {
        final var file = base.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }

    private void assertMappingFailure(final HopCoverageConfig config, final ArtifactReport artifact)
            throws IOException {
        final var output = config.jacocoReport().output();
        Files.createDirectories(output.getParent());
        Files.writeString(output, "stale XML");
        assertThrows(IOException.class, () -> new HopCoverageJacocoRenderer(config).write(report(artifact)));
        assertFalse(
                Files.exists(output),
                "failed mapping must remove stale output: "
                        + artifact.artifact().path());
    }

    private Document readXml(final Path file) throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (final var input = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private String value(final Document xml, final String expression) throws Exception {
        return XPathFactory.newInstance().newXPath().evaluate(expression, xml);
    }

    private void line(final Document xml, final String path, final int number, final int missed, final int covered)
            throws Exception {
        assertEquals(Integer.toString(number), value(xml, path + "/@nr"), path);
        assertEquals(Integer.toString(missed), value(xml, path + "/@mi"), path);
        assertEquals(Integer.toString(covered), value(xml, path + "/@ci"), path);
        assertEquals("0", value(xml, path + "/@mb"), path);
        assertEquals("0", value(xml, path + "/@cb"), path);
    }

    private void counters(
            final Document xml,
            final String scope,
            final int missedInstructions,
            final int coveredInstructions,
            final int missedLines,
            final int coveredLines)
            throws Exception {
        assertEquals("2", value(xml, "count(" + scope + "/counter)"), scope);
        assertEquals(Integer.toString(missedInstructions), value(xml, scope + "/counter[@type='INSTRUCTION']/@missed"));
        assertEquals(
                Integer.toString(coveredInstructions), value(xml, scope + "/counter[@type='INSTRUCTION']/@covered"));
        assertEquals(Integer.toString(missedLines), value(xml, scope + "/counter[@type='LINE']/@missed"));
        assertEquals(Integer.toString(coveredLines), value(xml, scope + "/counter[@type='LINE']/@covered"));
    }

    private int openingLine(final String source, final int hopNumber) {
        var offset = -1;
        for (var i = 0; i < hopNumber; i++) {
            offset = source.indexOf("<hop>", offset + 1);
            assertTrue(offset >= 0, "expected Hop-generated hop XML in source");
        }
        return 1
                + (int) source.substring(0, offset)
                        .chars()
                        .filter(c -> c == '\n')
                        .count();
    }
}
