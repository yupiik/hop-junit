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

import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Artifact;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Edge;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Execution;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Node;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Result;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Run;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Summary;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.TransformMeta;

/**
 * Instruments an {@link IPipelineEngine} run to collect Hop coverage (nodes/edges executed) and produces
 * the {@link Execution} recorded by {@link HopCoverageRuntime}. Used internally by the pipeline fixture.
 */
public final class PipelineCoverageCollector {
    private final String id = HopCoverageRuntime.INSTANCE.nextExecutionId();
    private final Path artifactPath;
    private final HopCoverageModels.Test test;
    private final Instant startedAt = Instant.now();
    private final Map<String, NodeState> nodes = new ConcurrentHashMap<>();
    private final Map<String, EdgeState> edges = new ConcurrentHashMap<>();

    public PipelineCoverageCollector(final Path artifactPath, final HopCoverageModels.Test test) {
        this.artifactPath = artifactPath;
        this.test = test;
    }

    public void attach(final IPipelineEngine<PipelineMeta> engine) {
        final var meta = engine.getPipelineMeta();
        for (final var transform : meta.getTransforms()) {
            nodes.putIfAbsent(transform.getName(), new NodeState(transform));
        }
        for (final var hop : meta.getPipelineHops()) {
            edges.putIfAbsent(
                    edgeId(
                            hop.getFromTransform().getName(),
                            hop.getToTransform().getName()),
                    new EdgeState(hop));
        }
        if (engine instanceof Pipeline pipeline) {
            for (final var combi : pipeline.getTransforms()) {
                attach(combi.transformMeta, combi.transform);
            }
        } else {
            for (final var transform : meta.getTransforms()) {
                final var copies = Math.max(1, transform.getCopies(engine));
                for (int copy = 0; copy < copies; copy++) {
                    final var component = engine.findComponent(transform.getName(), copy);
                    if (component instanceof ITransform runtimeTransform) {
                        attach(transform, runtimeTransform);
                    }
                }
            }
        }
    }

    public Execution toExecution(final org.apache.hop.core.Result result) {
        final var endedAt = Instant.now();
        // Our transform-finished listeners can be notified after pipeline completion
        // or never if the transform already ended before attach (nested pipelines), so snapshot runtime state
        nodes.values().forEach(NodeState::snapshot);
        final var outgoingEdges = edges.values().stream()
                .filter(edge -> edge.enabled)
                .collect(Collectors.groupingBy(edge -> edge.from, Collectors.counting()));
        edges.values().stream()
                .filter(edge -> edge.enabled)
                .filter(edge -> {
                    final var from = nodes.get(edge.from);
                    return from != null
                            && from.processedRows()
                            && (outgoingEdges.getOrDefault(edge.from, 0L) == 1 || from.duplicatesRows());
                })
                .map(edge -> nodes.get(edge.to))
                .filter(Objects::nonNull)
                .forEach(NodeState::inferred);
        final var nodeList = HopCoverageRuntime.INSTANCE.sortedCopy(nodes.values(), NodeState::compareTo).stream()
                .map(NodeState::toNode)
                .toList();
        final var edgeList = HopCoverageRuntime.INSTANCE.sortedCopy(edges.values(), EdgeState::compareTo).stream()
                .map(edge -> edge.toEdge(nodes, outgoingEdges.getOrDefault(edge.from, 0L)))
                .toList();
        return new Execution(
                id,
                "pipeline",
                new Artifact("pipeline", artifactName(), HopCoverageRuntime.INSTANCE.relativePath(artifactPath)),
                test,
                new Run(
                        Thread.currentThread().getName(),
                        Thread.currentThread().threadId(),
                        startedAt.toString(),
                        endedAt.toString(),
                        Duration.between(startedAt, endedAt).toMillis()),
                toResult(result),
                summary(nodeList, edgeList),
                nodeList,
                edgeList,
                List.of());
    }

    private void attach(final TransformMeta meta, final ITransform transform) {
        final var state = nodes.computeIfAbsent(meta.getName(), ignored -> new NodeState(meta));
        state.runtimeTransforms.add(transform);
        transform.addTransformStartedListener(
                (pipeline, transformMeta, runtimeTransform) -> state.started(runtimeTransform.getCopy()));
        transform.addTransformFinishedListener((pipeline, transformMeta, runtimeTransform) -> {
            state.finished(runtimeTransform.getCopy());
            state.metrics(runtimeTransform);
        });
        transform.addRowToListener((rowMeta, row, rowSet) -> {
            final var edge = edges.get(edgeId(rowSet.getOriginTransformName(), rowSet.getDestinationTransformName()));
            if (edge != null) {
                edge.rows.incrementAndGet();
            }
        });
    }

    private Summary summary(final List<Node> nodeList, final List<Edge> edgeList) {
        final var enabledEdges = edgeList.stream().filter(Edge::enabled).toList();
        final var branchEdges = enabledEdges.stream()
                .filter(edge -> enabledEdges.stream()
                                .filter(it -> it.from().equals(edge.from()))
                                .count()
                        > 1)
                .toList();
        return new Summary(
                HopCoverageRuntime.INSTANCE.ratio(
                        enabledEdges.stream().filter(Edge::covered).count(), enabledEdges.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        nodeList.stream().filter(Node::covered).count(), nodeList.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        enabledEdges.stream().filter(Edge::covered).count(), enabledEdges.size()),
                HopCoverageRuntime.INSTANCE.ratio(
                        branchEdges.stream().filter(Edge::covered).count(), branchEdges.size()));
    }

    private Result toResult(final org.apache.hop.core.Result result) {
        if (result == null) {
            return new Result(false, 1, 0, 0, 0, 0, 0, 0);
        }
        return new Result(
                result.isResult() && result.getNrErrors() == 0,
                result.getNrErrors(),
                result.getNrLinesInput(),
                result.getNrLinesOutput(),
                result.getNrLinesRead(),
                result.getNrLinesWritten(),
                result.getNrLinesUpdated(),
                result.getNrLinesRejected());
    }

    private String artifactName() {
        final var fileName = artifactPath.getFileName().toString();
        final var dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String edgeId(final String from, final String to) {
        return "hop:" + from + "->" + to;
    }

    private static final class NodeState implements Comparable<NodeState> {
        private final String name;
        private final String type;
        private final boolean distributes;
        private final Integer x;
        private final Integer y;
        private final ConcurrentSkipListSet<Integer> startedCopies = new ConcurrentSkipListSet<>();
        private final ConcurrentSkipListSet<Integer> finishedCopies = new ConcurrentSkipListSet<>();
        private final Map<Integer, CopyMetrics> copyMetrics = new ConcurrentHashMap<>();
        private final List<ITransform> runtimeTransforms = new CopyOnWriteArrayList<>();
        private volatile boolean inferred;

        private NodeState(final TransformMeta meta) {
            name = meta.getName();
            type = meta.getTransformPluginId();
            distributes = meta.isDistributes();
            final var location = meta.getLocation();
            x = location == null ? null : location.x;
            y = location == null ? null : location.y;
        }

        private void started(final int copy) {
            startedCopies.add(copy);
        }

        private void finished(final int copy) {
            finishedCopies.add(copy);
        }

        private void metrics(final ITransform transform) {
            // Serialize observations of the same copy, including the reads, so a late listener
            // cannot overwrite a newer snapshot with metrics read before that snapshot.
            copyMetrics.compute(
                    transform.getCopy(),
                    (copy, previous) -> new CopyMetrics(
                            transform.getLinesInput(),
                            transform.getLinesOutput(),
                            transform.getLinesRead(),
                            transform.getLinesWritten(),
                            transform.getErrors()));
        }

        private void snapshot() {
            for (final var transform : runtimeTransforms) {
                final var status = transform.getStatus();
                if (status == null) {
                    continue;
                }
                switch (status) {
                    case STATUS_EMPTY, STATUS_INIT, STATUS_IDLE -> {
                        // not started
                    }
                    case STATUS_FINISHED, STATUS_STOPPED, STATUS_DISPOSED, STATUS_HALTED -> {
                        startedCopies.add(transform.getCopy());
                        finishedCopies.add(transform.getCopy());
                        metrics(transform);
                    }
                    default -> {
                        startedCopies.add(transform.getCopy());
                        metrics(transform);
                    }
                }
            }
        }

        private void inferred() {
            inferred = true;
        }

        private boolean processedRows() {
            return wroteRows() || readRows();
        }

        private boolean wroteRows() {
            return copyMetrics.values().stream().anyMatch(it -> it.linesWritten() > 0 || it.linesOutput() > 0);
        }

        private boolean duplicatesRows() {
            return distributes && !isConditionalRouter();
        }

        private boolean isConditionalRouter() {
            return type != null && type.toLowerCase(Locale.ROOT).contains("filter");
        }

        private boolean readRows() {
            return copyMetrics.values().stream().anyMatch(it -> it.linesRead() > 0 || it.linesInput() > 0);
        }

        private boolean isCovered() {
            return inferred || !startedCopies.isEmpty() || !finishedCopies.isEmpty() || processedRows();
        }

        private Node toNode() {
            final var covered = isCovered();
            final var metrics = List.copyOf(copyMetrics.values());
            return new Node(
                    "transform:" + name,
                    name,
                    type,
                    true,
                    covered,
                    x,
                    y,
                    Math.max(1, Math.max(startedCopies.size(), finishedCopies.size())),
                    List.copyOf(startedCopies),
                    List.copyOf(finishedCopies),
                    metrics.stream().mapToLong(CopyMetrics::linesInput).sum(),
                    metrics.stream().mapToLong(CopyMetrics::linesOutput).sum(),
                    metrics.stream().mapToLong(CopyMetrics::linesRead).sum(),
                    metrics.stream().mapToLong(CopyMetrics::linesWritten).sum(),
                    metrics.stream().mapToLong(CopyMetrics::errors).sum(),
                    null);
        }

        @Override
        public int compareTo(final NodeState other) {
            return name.compareTo(other.name);
        }

        private record CopyMetrics(long linesInput, long linesOutput, long linesRead, long linesWritten, long errors) {}
    }

    private static final class EdgeState implements Comparable<EdgeState> {
        private final String from;
        private final String to;
        private final boolean enabled;
        private final AtomicLong rows = new AtomicLong();

        private EdgeState(final PipelineHopMeta hop) {
            from = hop.getFromTransform().getName();
            to = hop.getToTransform().getName();
            enabled = hop.isEnabled();
        }

        private Edge toEdge(final Map<String, NodeState> nodes, final long outgoingEdges) {
            final var rowCount = rows.get();
            final var fromNode = nodes.get(from);
            final var toNode = nodes.get(to);
            final var sourceCovered = fromNode != null && fromNode.isCovered();
            final var targetCovered = toNode != null && toNode.isCovered();
            final var covered = enabled
                    && (rowCount > 0
                            || sourceCovered && targetCovered && (outgoingEdges == 1 || fromNode.duplicatesRows())
                            || sourceCovered && toNode != null && toNode.readRows());
            return new Edge(edgeId(from, to), from, to, enabled, covered, null, rowCount, covered, targetCovered);
        }

        @Override
        public int compareTo(final EdgeState other) {
            return (from + '\0' + to).compareTo(other.from + '\0' + other.to);
        }
    }
}
