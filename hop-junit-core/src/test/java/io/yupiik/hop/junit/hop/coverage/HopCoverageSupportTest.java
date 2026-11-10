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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.hop.coverage.HopCoverageConfig.Language;
import io.yupiik.hop.junit.hop.coverage.HopCoverageConfig.ReportFormat;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Artifact;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.ArtifactReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Edge;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Execution;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.JvmReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Node;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Result;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Run;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Summary;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(Lifecycle.PER_METHOD)
class HopCoverageSupportTest {
    @org.junit.jupiter.api.Test
    void jsonRoundTrip(final @TempDir Path work) throws IOException {
        final var report = new JvmReport(
                HopCoverageRuntime.SCHEMA_VERSION,
                123,
                "2026-10-25T00:00:00Z",
                "2026-10-25T00:00:01Z",
                List.of(execution("000001", "pipeline", "src/main/repositories/p.hpl", "A", "B")));
        final var file = work.resolve("run-123-20261025000000.json");

        HopCoverageJson.INSTANCE.write(report, file);
        final var reread = HopCoverageJson.INSTANCE.read(JvmReport.class, file);

        assertEquals(report.schemaVersion(), reread.schemaVersion());
        assertEquals(report.processId(), reread.processId());
        assertEquals(
                "src/main/repositories/p.hpl",
                reread.executions().get(0).artifact().path());
    }

    @org.junit.jupiter.api.Test
    void jsonOmitsNullsAndIgnoresUnknownProperties(final @TempDir Path work) throws IOException {
        final var file = work.resolve("report.json");

        // requiredCoverage is a boxed Double, deliberately null -> must be omitted from the payload.
        HopCoverageJson.INSTANCE.write(
                new ArtifactReport(
                        new Artifact("pipeline", "p.hpl", "p.hpl"), null, List.of(), List.of(), List.of("1"), null),
                file);

        final var raw = Files.readString(file);
        assertFalse(raw.contains("requiredCoverage"), "null requiredCoverage must not be serialized: " + raw);

        // Unknown fields must be ignored, not rejected, when reading.
        Files.writeString(
                file,
                "{\"schemaVersion\":1,\"processId\":42,\"jvmStartedAt\":\"2026-10-25T00:00:00Z\","
                        + "\"reportWrittenAt\":\"2026-10-25T00:00:01Z\",\"executions\":[],\"futureField\":true}");
        final var reread = HopCoverageJson.INSTANCE.read(JvmReport.class, file);
        assertEquals(1, reread.schemaVersion());
        assertEquals(42, reread.processId());
        assertTrue(reread.executions().isEmpty());
    }

    @org.junit.jupiter.api.Test
    void requiredCoverageForUsesInjectedOverridesWithoutGlobalState(final @TempDir Path work) {
        final var cfg = config(work, work, work, OptionalDouble.of(80), Map.of("w", 90d));

        assertEquals(90d, cfg.requiredCoverageFor("w").orElseThrow(), 0d);
        // artifacts without an explicit override fall back to the global threshold.
        assertEquals(80d, cfg.requiredCoverageFor("other").orElseThrow(), 0d);
    }

    @org.junit.jupiter.api.Test
    void nestedPipelineExtensionPointRegistrationIsIdempotent() {
        // The extension point is registered (idempotently) from HopCoverageRuntime's static
        // initializer when coverage is enabled; calling register() directly must not throw and
        // must stay idempotent (a second call is a no-op).
        new PipelineCoverageExtensionPoint().register();
        new PipelineCoverageExtensionPoint().register();
        assertTrue(PipelineCoverageExtensionPoint.isRegistered());
    }

    @org.junit.jupiter.api.Test
    void relativePathsAreBasedirRelative() {
        final var path = HopCoverageRuntime.INSTANCE.config().baseDir().resolve("src/main/repositories/p.hpl");

        final var relative = HopCoverageRuntime.INSTANCE.relativePath(path);

        assertEquals("src/main/repositories/p.hpl", relative);
        assertFalse(relative.startsWith("/"));
    }

    @org.junit.jupiter.api.Test
    void postProcessorMergesReportsAndAppliesRequiredCoverage(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        final var site = work.resolve("site");
        final var config = config(work, raw, site, OptionalDouble.of(100));
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:00:01Z",
                        List.of(execution("000001", "pipeline", "src/main/repositories/p.hpl", "A", "B"))),
                raw.resolve("run-1-20261025000000.json"));
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        2,
                        "2026-10-25T00:00:02Z",
                        "2026-10-25T00:00:03Z",
                        List.of(execution("000002", "pipeline", "src/main/repositories/p.hpl", "A", "C"))),
                raw.resolve("run-2-20261025000002.json"));

        final var aggregate = new HopCoveragePostProcessor(config).run();

        assertEquals(1, aggregate.artifacts().size());
        assertEquals(2, aggregate.artifacts().get(0).edges().size());
        assertEquals(1d, aggregate.summary().coverage().ratio());
        assertTrue(Files.isRegularFile(site.resolve("hop-coverage.json")));
        assertTrue(Files.isRegularFile(site.resolve("index.html")));
        final var html = Files.readString(site.resolve("index.html"));
        assertTrue(html.contains("Test Hop Coverage"));
        assertTrue(html.contains("src/main/repositories/p.hpl"));
    }

    @org.junit.jupiter.api.Test
    void postProcessorFailsOnMalformedReportByDefault(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        Files.createDirectories(raw);
        Files.writeString(raw.resolve("run-1-20261025000000.json"), "not-json");
        final var config = config(work, raw, work.resolve("site"), OptionalDouble.empty());

        assertThrows(IOException.class, () -> new HopCoveragePostProcessor(config).run());
    }

    @org.junit.jupiter.api.Test
    void postProcessorFailsWhenRequiredCoverageIsNotReached(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:00:01Z",
                        List.of(execution("000001", "workflow", "src/main/repositories/w.hwf", "A", "B", false))),
                raw.resolve("run-1-20261025000000.json"));
        final var config = config(work, raw, work.resolve("site"), OptionalDouble.of(1));

        assertThrows(IllegalStateException.class, () -> new HopCoveragePostProcessor(config).run());
    }

    @org.junit.jupiter.api.Test
    void postProcessorAcceptsPerArtifactOverride(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        final var site = work.resolve("site");
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:00:01Z",
                        List.of(execution("000001", "workflow", "src/main/repositories/w.hwf", "A", "B", false))),
                raw.resolve("run-1-20261025000000.json"));
        new HopCoveragePostProcessor(config(work, raw, site, OptionalDouble.of(80), Map.of("w", 0d))).run();
    }

    @org.junit.jupiter.api.Test
    void postProcessorRejectsPerArtifactOverrideTooHigh(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        final var site = work.resolve("site");
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:01:00Z",
                        List.of(execution("000001", "workflow", "src/main/repositories/w.hwf", "A", "B", false))),
                raw.resolve("run-1-20261025000000.json"));
        final var config = config(work, raw, site, OptionalDouble.of(80), Map.of("w", 90d));
        assertThrows(IllegalStateException.class, () -> new HopCoveragePostProcessor(config).run());
    }

    @org.junit.jupiter.api.Test
    void postProcessorWritesAdocThresholdOverride(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        final var site = work.resolve("site");
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:00:01Z",
                        List.of(execution("000001", "workflow", "src/main/repositories/w.hwf", "A", "B", false))),
                raw.resolve("run-1-20261025000000.json"));
        new HopCoveragePostProcessor(
                        config(work, raw, site, OptionalDouble.of(80), Map.of("w", 0d), Set.of(ReportFormat.ADOC)))
                .run();

        final var adoc = Files.readString(site.resolve("coverage.adoc"));
        assertTrue(adoc.contains("surcharg\u00e9 pour cet artefact"));
        assertTrue(adoc.contains("Seuil de couverture requis: `0.00%`"));
    }

    @org.junit.jupiter.api.Test
    void postProcessorWritesDetailedAdocReport(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        final var site = work.resolve("site");
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:00:01Z",
                        List.of(execution(
                                "000001", "workflow", "src/main/repositories/utilities/oracle_sync/w.hwf", "A", "B"))),
                raw.resolve("run-1-20261025000000.json"));

        new HopCoveragePostProcessor(config(work, raw, site, OptionalDouble.of(80), Set.of(ReportFormat.ADOC), true))
                .run();

        final var adoc = Files.readString(site.resolve("coverage.adoc"));
        assertTrue(adoc.contains("=== Couverture par artefact"));
        assertTrue(adoc.contains(
                "|Statut de couverture |Type |Artefact |N\u0153uds |Arcs |Branches |Ex\u00e9cutions |Manquants"));
        assertTrue(adoc.contains("icon:check[role=green] OK"));
        assertTrue(adoc.contains("=== Synth\u00e8se globale"));
        assertTrue(adoc.contains("Seuil de couverture requis: `80.00%`"));
        assertTrue(adoc.contains("(seuil global par d\u00e9faut)"));
        assertTrue(adoc.contains("=== D\u00e9tail par pipeline/workflow"));
        assertTrue(adoc.contains("==== workflow - `src/main/repositories/utilities/oracle_sync/w.hwf`"));
        assertTrue(adoc.contains("===== \u00c9l\u00e9ments non couverts"));
        assertTrue(adoc.contains("===== N\u0153uds"));
        assertTrue(adoc.contains("===== Arcs"));
        assertTrue(adoc.contains("|`A`"));
        assertTrue(adoc.contains("|`B`"));
        // glossary checks
        assertTrue(adoc.contains("=== Glossaire"));
        assertTrue(adoc.contains("Nœud (node)::"));
        assertTrue(adoc.contains("représentant le flux de données ou la relation de contrôle"));
        assertTrue(adoc.contains("Hop (arc)::"));
        // xref/link should use asciidoc xref:anchor[label], not backticks inside xref
        assertTrue(adoc.contains("xref:hop-coverage-"));
        assertFalse(adoc.contains("<<"));
        assertTrue(adoc.contains("Documentation Hop: link:hop/w.html[w]"));
        assertTrue(adoc.contains("image::diagrams/utilities/oracle_sync/w.svg[Diagramme w,width=900]"));
    }

    @org.junit.jupiter.api.Test
    void postProcessorWritesEnglishAdocReport(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        final var site = work.resolve("site");
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:00:01Z",
                        List.of(execution("000001", "workflow", "src/main/repositories/w.hwf", "A", "B"))),
                raw.resolve("run-1-20261025000000.json"));

        new HopCoveragePostProcessor(config(
                        work, raw, site, OptionalDouble.of(80), Map.of(), Set.of(ReportFormat.ADOC), Language.EN))
                .run();

        final var adoc = Files.readString(site.resolve("coverage.adoc"));
        // English headings and labels
        assertTrue(adoc.contains("=== Coverage by artifact"));
        assertTrue(adoc.contains("|Coverage status |Type |Artifact |Nodes |Edges |Branches |Executions |Missing"));
        assertTrue(adoc.contains("=== Global summary"));
        assertTrue(adoc.contains("|Metric |Coverage |Covered |Total"));
        assertTrue(adoc.contains("=== Glossary"));
        assertTrue(adoc.contains("Required coverage threshold: `80.00%`"));
        assertTrue(adoc.contains("(default global threshold)"));
        assertTrue(adoc.contains("=== Detail by pipeline/workflow"));
        assertTrue(adoc.contains("===== Uncovered elements"));
        assertTrue(adoc.contains("===== Nodes"));
        assertTrue(adoc.contains("===== Edges"));
        assertTrue(adoc.contains("Coverage status:"));
        assertTrue(adoc.contains("None."));
        assertTrue(adoc.contains("|yes\n"));
        // structural fragments shared with the French report
        assertTrue(adoc.contains("xref:hop-coverage-"));
        assertTrue(adoc.contains("icon:check[role=green] OK"));
        assertTrue(adoc.contains("|==="));
        // no French leakage into the English report
        assertFalse(adoc.contains("Synth\u00e8se globale"));
        assertFalse(adoc.contains("Couverture par artefact"));
        assertFalse(adoc.contains("Statut de couverture"));
    }

    @org.junit.jupiter.api.Test
    void loadLanguageResolvesFrenchByDefaultAndRejectsUnknown() {
        // The test JVM forces hop.coverage.report.adoc.language=fr (see hop-junit-core pom surefire
        // config), so load() must resolve FR deterministically regardless of the OS locale.
        assertEquals(Language.FR, HopCoverageConfig.load().adocReport().language());

        System.setProperty("hop.coverage.report.adoc.language", "en");
        try {
            assertEquals(Language.EN, HopCoverageConfig.load().adocReport().language());
        } finally {
            System.clearProperty("hop.coverage.report.adoc.language");
        }

        System.setProperty("hop.coverage.report.adoc.language", "de");
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> HopCoverageConfig.load().adocReport().language());
        } finally {
            System.clearProperty("hop.coverage.report.adoc.language");
        }
    }

    @org.junit.jupiter.api.Test
    void postProcessorHonorsHtmlOnlyFormat(final @TempDir Path work) throws IOException {
        final var raw = work.resolve("raw");
        final var site = work.resolve("site");
        HopCoverageJson.INSTANCE.write(
                new JvmReport(
                        HopCoverageRuntime.SCHEMA_VERSION,
                        1,
                        "2026-10-25T00:00:00Z",
                        "2026-10-25T00:00:01Z",
                        List.of(execution("000001", "workflow", "src/main/repositories/w.hwf", "A", "B"))),
                raw.resolve("run-1-20261025000000.json"));

        new HopCoveragePostProcessor(config(work, raw, site, OptionalDouble.empty(), Set.of(ReportFormat.HTML))).run();

        assertFalse(Files.exists(site.resolve("hop-coverage.json")));
        assertTrue(Files.isRegularFile(site.resolve("index.html")));
        final var html = Files.readString(site.resolve("index.html"));
        assertTrue(html.contains("Test Hop Coverage"));
        assertTrue(html.contains("src/main/repositories/w.hwf"));
    }

    @org.junit.jupiter.api.Test
    void nestedPipelineExtensionPointRegisterIsIdempotent() {
        new PipelineCoverageExtensionPoint().register();
        new PipelineCoverageExtensionPoint().register(); // second call must not throw / re-register
        assertTrue(PipelineCoverageExtensionPoint.isRegistered());
    }

    private HopCoverageConfig config(
            final Path work, final Path raw, final Path site, final OptionalDouble requiredCoverage) {
        return config(work, raw, site, requiredCoverage, Map.of());
    }

    private HopCoverageConfig config(
            final Path work,
            final Path raw,
            final Path site,
            final OptionalDouble requiredCoverage,
            final Map<String, Double> requiredCoverageByArtifact) {
        return config(
                work,
                raw,
                site,
                requiredCoverage,
                requiredCoverageByArtifact,
                Set.of(ReportFormat.JSON, ReportFormat.HTML));
    }

    private HopCoverageConfig config(
            final Path work,
            final Path raw,
            final Path site,
            final OptionalDouble requiredCoverage,
            final Map<String, Double> requiredCoverageByArtifact,
            final Set<ReportFormat> formats) {
        return config(work, raw, site, requiredCoverage, requiredCoverageByArtifact, formats, Language.FR);
    }

    private HopCoverageConfig config(
            final Path work,
            final Path raw,
            final Path site,
            final OptionalDouble requiredCoverage,
            final Map<String, Double> requiredCoverageByArtifact,
            final Set<ReportFormat> formats,
            final Language language) {
        return new HopCoverageConfig(
                true,
                work,
                work,
                raw,
                site,
                true,
                true,
                requiredCoverage,
                requiredCoverageByArtifact,
                formats,
                new HopCoverageConfig.HtmlReport(
                        "Test Hop Coverage", "JUnit report", "20261025", "Synthetic test report."),
                new HopCoverageConfig.AdocReport(
                        site.resolve("coverage.adoc"), "Test Hop Coverage", "Synthetic test report.", false, language));
    }

    private HopCoverageConfig config(
            final Path work,
            final Path raw,
            final Path site,
            final OptionalDouble requiredCoverage,
            final Set<ReportFormat> formats) {
        return config(work, raw, site, requiredCoverage, Map.of(), formats);
    }

    private HopCoverageConfig config(
            final Path work,
            final Path raw,
            final Path site,
            final OptionalDouble requiredCoverage,
            final Set<ReportFormat> formats,
            final boolean enableMinisiteHopLinking) {
        return new HopCoverageConfig(
                true,
                work,
                work,
                raw,
                site,
                true,
                true,
                requiredCoverage,
                Map.of(),
                formats,
                new HopCoverageConfig.HtmlReport(
                        "Test Hop Coverage", "JUnit report", "20261025", "Synthetic test report."),
                new HopCoverageConfig.AdocReport(
                        site.resolve("coverage.adoc"),
                        "Test Hop Coverage",
                        "Synthetic test report.",
                        enableMinisiteHopLinking,
                        Language.FR));
    }

    private Execution execution(
            final String id, final String kind, final String path, final String from, final String to) {
        return execution(id, kind, path, from, to, true);
    }

    private String artifactName(final String path) {
        final var fileName = Path.of(path).getFileName().toString();
        final var dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private Execution execution(
            final String id,
            final String kind,
            final String path,
            final String from,
            final String to,
            final boolean covered) {
        final var nodes = List.of(node(from, true), node(to, covered));
        final var edges = List.of(edge(from, to, covered));
        return new Execution(
                id,
                kind,
                new Artifact(kind, artifactName(path), path),
                new Test("TestClass", id, id + "()", "unique-" + id),
                new Run("main", 1, "2026-10-25T00:00:00Z", "2026-10-25T00:00:01Z", 1),
                new Result(true, 0, 0, 0, 0, covered ? 1 : 0, 0, 0),
                summary(nodes, edges),
                nodes,
                edges,
                List.of());
    }

    private Node node(final String name, final boolean covered) {
        return new Node(
                "node:" + name,
                name,
                "type",
                true,
                covered,
                1,
                2,
                1,
                covered ? List.of(0) : List.of(),
                covered ? List.of(0) : List.of(),
                0,
                0,
                0,
                covered ? 1 : 0,
                0,
                null);
    }

    private Edge edge(final String from, final String to, final boolean covered) {
        return new Edge(
                "hop:" + from + "->" + to, from, to, true, covered, "success", covered ? 1 : 0, covered, covered);
    }

    private Summary summary(final List<Node> nodes, final List<Edge> edges) {
        return new Summary(
                HopCoverageRuntime.INSTANCE.ratio(
                        edges.stream().filter(Edge::covered).count(), edges.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        nodes.stream().filter(Node::covered).count(), nodes.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        edges.stream().filter(Edge::covered).count(), edges.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        edges.stream().filter(Edge::covered).count(), edges.size()));
    }
}
