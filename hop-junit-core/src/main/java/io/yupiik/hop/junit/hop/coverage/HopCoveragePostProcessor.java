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
package io.yupiik.hop.junit.hop.coverage;

import io.yupiik.hop.junit.hop.coverage.HopCoverageConfig.ReportFormat;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.AggregateReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.ArtifactReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Edge;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Execution;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.JvmReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Node;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Summary;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Warning;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates per-test pipeline/workflow {@link Execution}s into a {@code JvmReport}/{@code AggregateReport}
 * and renders the configured report format ({@link ReportFormat}) when the JUnit launcher session closes.
 */
public final class HopCoveragePostProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(HopCoveragePostProcessor.class);

    private final HopCoverageConfig config;

    public HopCoveragePostProcessor(final HopCoverageConfig config) {
        this.config = config;
    }

    public AggregateReport run() throws IOException {
        Files.createDirectories(config.workDir());
        final var lockPath = config.workDir().resolve(".post-process.lock");
        try (final var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                final var lock = channel.lock()) {
            if (!lock.isValid()) {
                throw new IOException("Failed to acquire post-process lock");
            }
            return doRun();
        }
    }

    private AggregateReport doRun() throws IOException {
        final var executions = new ArrayList<Execution>();
        final var warnings = new ArrayList<Warning>();
        if (Files.isDirectory(config.rawDir())) {
            try (final var files = Files.walk(config.rawDir())) {
                files.filter(Files::isRegularFile)
                        .filter(it -> it.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(file -> {
                            try {
                                final var report = HopCoverageJson.INSTANCE.read(JvmReport.class, file);
                                if (report.schemaVersion() != HopCoverageRuntime.SCHEMA_VERSION) {
                                    throw new IllegalStateException(
                                            "Unsupported schema version: " + report.schemaVersion());
                                }
                                executions.addAll(report.executions());
                            } catch (final RuntimeException | IOException error) {
                                final var warning =
                                        new Warning(HopCoverageRuntime.INSTANCE.relativePath(file), error.getMessage());
                                warnings.add(warning);
                                if (config.failOnMalformedReport()) {
                                    throw new MalformedReportException(error);
                                }
                            }
                        });
            } catch (final MalformedReportException error) {
                throw new IOException("Malformed Hop coverage report", error.getCause());
            }
        }
        executions.sort(Comparator.comparing(Execution::id));
        final var artifacts = aggregate(executions);
        final var report = new AggregateReport(
                HopCoverageRuntime.SCHEMA_VERSION,
                HopCoverageRuntime.INSTANCE.now(),
                summary(artifacts),
                artifacts,
                List.copyOf(executions),
                List.copyOf(warnings));
        Files.createDirectories(config.siteDir());
        if (config.isReportFormatEnabled(ReportFormat.JSON)) {
            HopCoverageJson.INSTANCE.write(report, config.siteDir().resolve("hop-coverage.json"));
        }
        if (config.isReportFormatEnabled(ReportFormat.HTML)) {
            new HopCoverageHtmlRenderer(config).write(report, config.siteDir().resolve("index.html"));
        }
        if (config.isReportFormatEnabled(ReportFormat.ADOC)) {
            new HopCoverageAdocRenderer(config).write(report);
        }
        log(report);
        enforce(report);
        return report;
    }

    private List<ArtifactReport> aggregate(final List<Execution> executions) {
        final var grouped = new TreeMap<String, List<Execution>>();
        for (final var execution : executions) {
            final var artifact = execution.artifact();
            grouped.computeIfAbsent(artifact.kind() + ':' + artifact.path(), ignored -> new ArrayList<>())
                    .add(execution);
        }
        final var reports = new ArrayList<ArtifactReport>();
        for (final var group : grouped.values()) {
            final var artifact = group.getFirst().artifact();
            final var nodes = mergeNodes(group);
            final var edges = mergeEdges(group);
            reports.add(new ArtifactReport(
                    artifact,
                    summary(nodes, edges),
                    nodes,
                    edges,
                    group.stream().map(Execution::id).sorted().toList(),
                    config.requiredCoverageFor(artifact.name()).stream()
                            .boxed()
                            .findFirst()
                            .orElse(null)));
        }
        return List.copyOf(reports);
    }

    private List<Node> mergeNodes(final List<Execution> executions) {
        final var nodes = new LinkedHashMap<String, Node>();
        for (final var node : executions.stream()
                .flatMap(it -> it.nodes().stream())
                .sorted(Comparator.comparing(Node::id))
                .toList()) {
            nodes.merge(node.id(), node, this::merge);
        }
        return List.copyOf(nodes.values());
    }

    private Node merge(final Node first, final Node second) {
        return new Node(
                first.id(),
                first.name(),
                first.type(),
                first.enabled() || second.enabled(),
                first.covered() || second.covered(),
                first.x(),
                first.y(),
                Math.max(first.copies() == null ? 0 : first.copies(), second.copies() == null ? 0 : second.copies()),
                mergeIntegers(first.startedCopies(), second.startedCopies()),
                mergeIntegers(first.finishedCopies(), second.finishedCopies()),
                first.linesInput() + second.linesInput(),
                first.linesOutput() + second.linesOutput(),
                first.linesRead() + second.linesRead(),
                first.linesWritten() + second.linesWritten(),
                first.errors() + second.errors(),
                second.result() == null ? first.result() : second.result());
    }

    private List<Edge> mergeEdges(final List<Execution> executions) {
        final var edges = new LinkedHashMap<String, Edge>();
        for (final var edge : executions.stream()
                .flatMap(it -> it.edges().stream())
                .sorted(Comparator.comparing(Edge::id))
                .toList()) {
            edges.merge(edge.id(), edge, this::merge);
        }
        return List.copyOf(edges.values());
    }

    private Edge merge(final Edge first, final Edge second) {
        return new Edge(
                first.id(),
                first.from(),
                first.to(),
                first.enabled() || second.enabled(),
                first.covered() || second.covered(),
                first.kind(),
                first.rows() + second.rows(),
                first.selected() || second.selected(),
                first.targetExecuted() || second.targetExecuted());
    }

    private Summary summary(final List<ArtifactReport> artifacts) {
        return new Summary(
                HopCoverageRuntime.INSTANCE.ratio(
                        artifacts.stream()
                                .mapToLong(it -> it.summary().coverage().covered())
                                .sum(),
                        artifacts.stream()
                                .mapToLong(it -> it.summary().coverage().total())
                                .sum()),
                HopCoverageRuntime.INSTANCE.ratio(
                        artifacts.stream()
                                .mapToLong(it -> it.summary().nodes().covered())
                                .sum(),
                        artifacts.stream()
                                .mapToLong(it -> it.summary().nodes().total())
                                .sum()),
                HopCoverageRuntime.INSTANCE.ratio(
                        artifacts.stream()
                                .mapToLong(it -> it.summary().edges().covered())
                                .sum(),
                        artifacts.stream()
                                .mapToLong(it -> it.summary().edges().total())
                                .sum()),
                HopCoverageRuntime.INSTANCE.ratio(
                        artifacts.stream()
                                .mapToLong(it -> it.summary().branches().covered())
                                .sum(),
                        artifacts.stream()
                                .mapToLong(it -> it.summary().branches().total())
                                .sum()));
    }

    private Summary summary(final List<Node> nodes, final List<Edge> edges) {
        final var enabledEdges = edges.stream().filter(Edge::enabled).toList();
        final var branchEdges = enabledEdges.stream()
                .filter(edge -> enabledEdges.stream()
                                        .filter(it -> it.from().equals(edge.from()))
                                        .count()
                                > 1
                        || !"unconditional".equals(edge.kind()))
                .toList();
        return new Summary(
                HopCoverageRuntime.INSTANCE.ratio(
                        enabledEdges.stream().filter(Edge::covered).count(), enabledEdges.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        nodes.stream().filter(Node::covered).count(), nodes.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        enabledEdges.stream().filter(Edge::covered).count(), enabledEdges.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        branchEdges.stream().filter(Edge::covered).count(), branchEdges.size()));
    }

    private void log(final AggregateReport report) {
        LOGGER.info(
                "Hop coverage: {} graph scope coverage, {} artifacts, {} executions, nodes {}, edges {}, branches {}.",
                percent(report.summary().coverage()),
                report.artifacts().size(),
                report.executions().size(),
                percent(report.summary().nodes()),
                percent(report.summary().edges()),
                percent(report.summary().branches()));
        if (config.isReportFormatEnabled(ReportFormat.JSON)) {
            LOGGER.info("Hop coverage JSON report: {}", config.siteDir().resolve("hop-coverage.json"));
        }
        if (config.isReportFormatEnabled(ReportFormat.HTML)) {
            LOGGER.info("Hop coverage HTML report: {}", config.siteDir().resolve("index.html"));
        }
        if (config.isReportFormatEnabled(ReportFormat.ADOC)) {
            LOGGER.info("Hop coverage Asciidoc report: {}", config.adocReport().output());
        }
        if (LOGGER.isTraceEnabled()) {
            for (final var artifact : report.artifacts()) {
                LOGGER.trace(
                        "Artifact {} {} coverage={}",
                        artifact.artifact().kind(),
                        artifact.artifact().path(),
                        percent(artifact.summary().coverage()));
                artifact.edges().stream()
                        .filter(edge -> edge.enabled() && !edge.covered())
                        .forEach(edge -> LOGGER.trace("  uncovered edge: {} -> {}", edge.from(), edge.to()));
            }
        }
    }

    private String percent(final HopCoverageModels.Ratio ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio.ratio() * 100);
    }

    private void enforce(final AggregateReport report) {
        for (final var artifact : report.artifacts()) {
            final var required = artifact.requiredCoverage();
            if (required == null) {
                continue;
            }
            final var actual = artifact.summary().coverage().ratio() * 100;
            if (actual < required) {
                throw new IllegalStateException(String.format(
                        Locale.ROOT,
                        "Hop coverage %.2f%% for %s is below required coverage %.2f%%.",
                        actual,
                        artifact.artifact().path(),
                        required));
            }
        }
    }

    private List<Integer> mergeIntegers(final List<Integer> first, final List<Integer> second) {
        final var values = new ArrayList<Integer>();
        values.addAll(first);
        values.addAll(second);
        return values.stream().distinct().sorted().toList();
    }

    private static final class MalformedReportException extends RuntimeException {
        private MalformedReportException(final Throwable cause) {
            super(cause);
        }
    }
}
