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

import io.yupiik.hop.junit.hop.coverage.HopCoverageConfig.ReportFormat;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Artifact;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Edge;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Execution;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.JvmReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Changes default locale and captures the post-processor logger")
class HopCoveragePostProcessorGitlabTest {
    private static final Pattern TOTAL = Pattern.compile("^Hop Coverage Total: ([0-9]+\\.[0-9]{2})%$");

    @Test
    void logsWeightedTotalsWithDotRegardlessOfDefaultLocale(final @TempDir Path work) throws IOException {
        final var original = Locale.getDefault();
        final var display = Locale.getDefault(Locale.Category.DISPLAY);
        final var format = Locale.getDefault(Locale.Category.FORMAT);
        try (final var logs = new Logs()) {
            for (final var locale : List.of(Locale.GERMAN, Locale.FRENCH)) {
                Locale.setDefault(locale);
                final var config = config(work.resolve(locale.getLanguage()), Set.of(), true, OptionalDouble.empty());
                raw(config, execution("small", 1, 1), execution("large", 0, 3));
                logs.messages.clear();
                final var report = new HopCoveragePostProcessor(config).run();
                assertEquals(1, report.summary().coverage().covered());
                assertEquals(4, report.summary().coverage().total());
                assertEquals(List.of("25.00"), logs.totals());
            }
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test
    void canonicalLogHandlesEndpointsFractionsEmptyInputAndZeroEdges(final @TempDir Path work) throws IOException {
        try (final var logs = new Logs()) {
            for (final var covered : List.of(0, 6, 5)) {
                final var config = config(work.resolve("covered-" + covered), Set.of(), true, OptionalDouble.empty());
                raw(config, execution("graph", covered, 6));
                logs.messages.clear();
                new HopCoveragePostProcessor(config).run();
                assertEquals(List.of(covered == 0 ? "0.00" : covered == 6 ? "100.00" : "83.33"), logs.totals());
            }
            logs.messages.clear();
            new HopCoveragePostProcessor(config(work.resolve("empty"), Set.of(), true, OptionalDouble.empty())).run();
            assertTrue(logs.totals().isEmpty());
            assertTrue(logs.messages.contains("Hop Coverage Total: unavailable (no artifacts)."));

            final var zero = config(work.resolve("zero"), Set.of(), true, OptionalDouble.empty());
            raw(zero, execution("no-edges", 0, 0));
            logs.messages.clear();
            new HopCoveragePostProcessor(zero).run();
            assertEquals(List.of("100.00"), logs.totals());
            assertTrue(logs.messages.stream()
                    .anyMatch(it -> it.contains("no enabled edges") && it.contains("fully covered by convention")));
        }
    }

    @Test
    void publishesXmlBeforeThresholdFailureEvenWhenWriteErrorsAreLenient(final @TempDir Path work) throws IOException {
        final var config = config(work, Set.of(ReportFormat.JACOCO), false, OptionalDouble.of(100));
        final var execution = execution("threshold", 0, 1);
        source(config, execution);
        raw(config, execution);
        final var error = assertThrows(IllegalStateException.class, () -> new HopCoveragePostProcessor(config).run());
        assertTrue(error.getMessage().contains("below required coverage 100.00%"));
        final var xml = Files.readString(config.jacocoReport().output());
        assertTrue(xml.contains("<report"));
        assertTrue(xml.contains("mi=\"1\""));
        assertOnlyXml(config);
    }

    @Test
    void unmappableSourceRemovesStaleXmlAndStillAttemptsAllOtherFormats(final @TempDir Path work) throws IOException {
        for (final var strict : List.of(false, true)) {
            final var config = config(
                    work.resolve("strict-" + strict), Set.of(ReportFormat.values()), strict, OptionalDouble.empty());
            final var execution = execution("changed", 1, 1);
            source(config, execution);
            raw(config, execution);
            new HopCoveragePostProcessor(config).run();
            assertTrue(Files.isRegularFile(config.jacocoReport().output()));
            // Valid XML, but no longer the graph represented by the raw report.
            Files.writeString(config.baseDir().resolve(execution.artifact().path()), "<pipeline><order/></pipeline>");
            for (final var name : List.of("hop-coverage.json", "index.html", "coverage.adoc")) {
                Files.delete(config.siteDir().resolve(name));
            }
            if (strict) {
                final var error = assertThrows(IOException.class, () -> new HopCoveragePostProcessor(config).run());
                assertTrue(error.getMessage().contains("JACOCO"));
            } else {
                final var report = new HopCoveragePostProcessor(config).run();
                assertEquals(1, report.artifacts().size());
            }
            assertFalse(Files.exists(config.jacocoReport().output()));
            assertOtherFormats(config);
        }
    }

    @Test
    void disabledJacocoDoesNotParseMissingSource(final @TempDir Path work) throws IOException {
        final var config = config(
                work, Set.of(ReportFormat.JSON, ReportFormat.HTML, ReportFormat.ADOC), true, OptionalDouble.empty());
        raw(config, execution("missing-source", 1, 1));
        new HopCoveragePostProcessor(config).run();
        assertOtherFormats(config);
        assertFalse(Files.exists(config.jacocoReport().output()));
    }

    @Test
    void jacocoOnlyProducesNoOtherFormats(final @TempDir Path work) throws IOException {
        final var config = config(work, Set.of(ReportFormat.JACOCO), true, OptionalDouble.empty());
        final var execution = execution("xml-only", 1, 1);
        source(config, execution);
        raw(config, execution);
        new HopCoveragePostProcessor(config).run();
        assertTrue(Files.readString(config.jacocoReport().output()).contains("ci=\"1\""));
        assertOnlyXml(config);
    }

    @Test
    void scannerIgnoresInFlightTemporaryJsonUntilAtomicPublication(final @TempDir Path work) throws Exception {
        final var config = config(work, Set.of(), true, OptionalDouble.empty());
        raw(config, execution("previous", 1, 1));
        final var destination = config.rawDir().resolve("run.json");
        final var started = new CountDownLatch(1);
        final var publish = new CountDownLatch(1);
        final var replacement = jvm(execution("replacement", 0, 3));
        try (final var executor = Executors.newSingleThreadExecutor()) {
            final var writer = executor.submit(() -> {
                HopCoverageFiles.write(destination, temporary -> {
                    Files.writeString(temporary, "{unfinished");
                    started.countDown();
                    try {
                        if (!publish.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to publish");
                        }
                    } catch (final InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                    Files.writeString(temporary, HopCoverageJson.INSTANCE.toString(replacement));
                });
                return null;
            });
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertEquals(
                        "previous",
                        HopCoverageJson.INSTANCE
                                .read(JvmReport.class, destination)
                                .executions()
                                .getFirst()
                                .id());
                final var report = new HopCoveragePostProcessor(config).run();
                assertTrue(report.warnings().isEmpty());
                assertEquals(1, report.summary().coverage().total());
            } finally {
                publish.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
        }
        assertEquals(replacement, HopCoverageJson.INSTANCE.read(JvmReport.class, destination));
        assertEquals(
                3,
                new HopCoveragePostProcessor(config).run().summary().coverage().total());
        try (final var files = Files.list(config.rawDir())) {
            assertEquals(List.of(destination), files.toList());
        }
    }

    @Test
    void failedJsonSerializationPreservesPreviousReportAndLeavesNoPartialFiles(final @TempDir Path work)
            throws IOException {
        final var destination = work.resolve("run.json");
        final var previous = jvm(execution("previous", 1, 1));
        HopCoverageJson.INSTANCE.write(previous, destination);
        assertThrows(
                IOException.class, () -> HopCoverageJson.INSTANCE.write(new BrokenPayload("ignored"), destination));
        assertEquals(previous, HopCoverageJson.INSTANCE.read(JvmReport.class, destination));
        final var absent = work.resolve("absent.json");
        assertThrows(IOException.class, () -> HopCoverageJson.INSTANCE.write(new BrokenPayload("ignored"), absent));
        try (final var files = Files.list(work)) {
            assertEquals(List.of(destination), files.toList());
        }
    }

    @Test
    void listenerDoesNotSwallowThresholdFailureWhenWritesAreLenient(final @TempDir Path work) throws Exception {
        final var output = work.resolve("listener.log");
        final var process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                        "-Dhop.coverage.enabled=true",
                        "-Dhop.coverage.failOnWriteError=false",
                        "-Dhop.coverage.requiredCoverage=100",
                        "-Dhop.coverage.report.format=JSON",
                        "-Dhop.coverage.workDir=" + work.resolve("work"),
                        "-Dhop.coverage.rawDir=" + work.resolve("raw"),
                        "-Dhop.coverage.siteDir=" + work.resolve("site"),
                        HopCoveragePostProcessorGitlabTest.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Listener subprocess timed out");
            final var log = Files.readString(output);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("LISTENER_THRESHOLD_PROPAGATED"), log);
        } finally {
            process.destroyForcibly();
        }
    }

    public static void main(final String[] args) {
        HopCoverageRuntime.INSTANCE.addExecution(
                new HopCoveragePostProcessorGitlabTest().execution("listener-uncovered", 0, 1));
        try {
            new HopCoverageLauncherSessionListener().launcherSessionClosed(null);
        } catch (final IllegalStateException error) {
            if (error.getMessage() != null && error.getMessage().contains("below required coverage 100.00%")) {
                System.out.println("LISTENER_THRESHOLD_PROPAGATED");
                return;
            }
            throw error;
        }
        throw new AssertionError("Listener swallowed the coverage threshold failure");
    }

    public record BrokenPayload(String value) {
        @Override
        public String value() {
            throw new IllegalStateException("Deliberate serialization failure");
        }
    }

    private HopCoverageConfig config(
            final Path work, final Set<ReportFormat> formats, final boolean strict, final OptionalDouble required) {
        final var site = work.resolve("site");
        return new HopCoverageConfig(
                true,
                work,
                work.resolve("work"),
                work.resolve("raw"),
                site,
                strict,
                true,
                required,
                Map.of(),
                formats,
                new HopCoverageConfig.HtmlReport("Hop", "Coverage", null, "Graph edges"),
                new HopCoverageConfig.AdocReport(
                        site.resolve("coverage.adoc"), "Hop", "Graph edges", false, HopCoverageConfig.Language.EN),
                new HopCoverageConfig.JacocoReport(site.resolve("jacoco.xml")));
    }

    private Execution execution(final String id, final int covered, final int total) {
        final var edges = new ArrayList<Edge>();
        for (var i = 0; i < total; i++) {
            edges.add(new Edge(
                    "edge-" + i,
                    "N" + i,
                    "N" + (i + 1),
                    true,
                    i < covered,
                    null,
                    i < covered ? 1 : 0,
                    i < covered,
                    i < covered));
        }
        return new Execution(
                id,
                "pipeline",
                new Artifact("pipeline", id, "sources/" + id + ".hpl"),
                new HopCoverageModels.Test("GitlabTest", id, id, id),
                new HopCoverageModels.Run("main", 1, "2026-11-20T00:00:00Z", "2026-11-20T00:00:01Z", 1000),
                new HopCoverageModels.Result(true, 0, 0, 0, 0, 0, 0, 0),
                null,
                List.of(),
                edges,
                List.of());
    }

    private JvmReport jvm(final Execution... executions) {
        return new JvmReport(
                HopCoverageRuntime.SCHEMA_VERSION,
                1,
                "2026-11-20T00:00:00Z",
                "2026-11-20T00:00:01Z",
                List.of(executions));
    }

    private void raw(final HopCoverageConfig config, final Execution... executions) throws IOException {
        HopCoverageJson.INSTANCE.write(jvm(executions), config.rawDir().resolve("run.json"));
    }

    private void source(final HopCoverageConfig config, final Execution execution) throws IOException {
        final var file = config.baseDir().resolve(execution.artifact().path());
        Files.createDirectories(file.getParent());
        final var xml = new StringBuilder("<pipeline><order>\n");
        for (final var edge : execution.edges()) {
            xml.append("<hop><from>")
                    .append(edge.from())
                    .append("</from><to>")
                    .append(edge.to())
                    .append("</to><enabled>Y</enabled></hop>\n");
        }
        Files.writeString(file, xml.append("</order></pipeline>").toString());
    }

    private void assertOtherFormats(final HopCoverageConfig config) throws IOException {
        for (final var name : List.of("hop-coverage.json", "index.html", "coverage.adoc")) {
            assertTrue(Files.size(config.siteDir().resolve(name)) > 0, name);
        }
    }

    private void assertOnlyXml(final HopCoverageConfig config) throws IOException {
        try (final var files = Files.list(config.siteDir())) {
            assertEquals(List.of(config.jacocoReport().output()), files.toList());
        }
    }

    private static final class Logs extends AbstractAppender implements AutoCloseable {
        private final Logger logger = (Logger) LogManager.getLogger(HopCoveragePostProcessor.class);
        private final Level previous = logger.getLevel();
        private final List<String> messages = new ArrayList<>();

        private Logs() {
            super("gitlab-coverage-test", null, null, false, Property.EMPTY_ARRAY);
            start();
            logger.addAppender(this);
            logger.setLevel(Level.INFO);
        }

        @Override
        public void append(final LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        private List<String> totals() {
            return messages.stream()
                    .map(TOTAL::matcher)
                    .filter(it -> it.matches())
                    .map(it -> it.group(1))
                    .toList();
        }

        @Override
        public void close() {
            try {
                logger.removeAppender(this);
                logger.setLevel(previous);
            } finally {
                stop();
            }
        }
    }
}
