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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hop.workflow.IActionListener;
import org.apache.hop.workflow.WorkflowHopMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.action.IAction;
import org.apache.hop.workflow.engine.IWorkflowEngine;

/**
 * Instruments an {@link IWorkflowEngine} run to collect Hop coverage (jobs/actions executed and their
 * transitions) and produces the {@link Execution} recorded by {@link HopCoverageRuntime}. Used internally
 * by the workflow fixture.
 */
public final class WorkflowCoverageCollector {
    private final String id = HopCoverageRuntime.INSTANCE.nextExecutionId();
    private final Path artifactPath;
    private final HopCoverageModels.Test test;
    private final Instant startedAt = Instant.now();
    private final ConcurrentHashMap<String, NodeState> nodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EdgeState> edges = new ConcurrentHashMap<>();

    public WorkflowCoverageCollector(final Path artifactPath, final HopCoverageModels.Test test) {
        this.artifactPath = artifactPath;
        this.test = test;
    }

    public void attach(final IWorkflowEngine<WorkflowMeta> engine) {
        final var meta = engine.getWorkflowMeta();
        for (final var action : meta.getWorkflowActions()) {
            nodes.putIfAbsent(action.getName(), new NodeState(action));
        }
        for (final var hop : meta.getWorkflowHops()) {
            edges.putIfAbsent(
                    edgeId(hop.getFromAction().getName(), hop.getToAction().getName()), new EdgeState(hop));
        }
        engine.addActionListener(new IActionListener<>() {
            @Override
            public void beforeExecution(
                    final IWorkflowEngine<WorkflowMeta> workflow, final ActionMeta actionMeta, final IAction action) {
                nodes.computeIfAbsent(actionMeta.getName(), ignored -> new NodeState(actionMeta))
                        .started();
            }

            @Override
            public void afterExecution(
                    final IWorkflowEngine<WorkflowMeta> workflow,
                    final ActionMeta actionMeta,
                    final IAction action,
                    final org.apache.hop.core.Result result) {
                final var state = nodes.computeIfAbsent(actionMeta.getName(), ignored -> new NodeState(actionMeta));
                state.finished(result);
                for (final var edge : edges.values()) {
                    if (edge.from.equals(actionMeta.getName())) {
                        edge.evaluate(result);
                    }
                }
            }
        });
    }

    public Execution toExecution(final org.apache.hop.core.Result result) {
        final var endedAt = Instant.now();
        final var nodeList = HopCoverageRuntime.INSTANCE.sortedCopy(nodes.values(), NodeState::compareTo).stream()
                .map(NodeState::toNode)
                .toList();
        final var edgeList = HopCoverageRuntime.INSTANCE.sortedCopy(edges.values(), EdgeState::compareTo).stream()
                .map(edge -> edge.toEdge(nodes))
                .toList();
        return new Execution(
                id,
                "workflow",
                new Artifact("workflow", artifactName(), HopCoverageRuntime.INSTANCE.relativePath(artifactPath)),
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

    private Summary summary(final List<Node> nodeList, final List<Edge> edgeList) {
        final var enabledEdges = edgeList.stream().filter(Edge::enabled).toList();
        final var branchEdges = enabledEdges.stream()
                .filter(edge -> !"unconditional".equals(edge.kind()))
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
        private final Integer x;
        private final Integer y;
        private final AtomicLong errors = new AtomicLong();
        private final AtomicReference<Boolean> result = new AtomicReference<>();
        private volatile boolean started;
        private volatile boolean finished;

        private NodeState(final ActionMeta meta) {
            name = meta.getName();
            type = meta.getAction() == null
                    ? meta.getTypeDesc()
                    : meta.getAction().getPluginId();
            final var location = meta.getLocation();
            x = location == null ? null : location.x;
            y = location == null ? null : location.y;
        }

        private void started() {
            started = true;
        }

        private void finished(final org.apache.hop.core.Result actionResult) {
            finished = true;
            if (actionResult != null) {
                errors.set(actionResult.getNrErrors());
                result.set(actionResult.isResult());
            }
        }

        private Node toNode() {
            return new Node(
                    "action:" + name,
                    name,
                    type,
                    true,
                    started || finished,
                    x,
                    y,
                    1,
                    started ? List.of(0) : List.of(),
                    finished ? List.of(0) : List.of(),
                    0,
                    0,
                    0,
                    0,
                    errors.get(),
                    result.get());
        }

        @Override
        public int compareTo(final NodeState other) {
            return name.compareTo(other.name);
        }
    }

    private static final class EdgeState implements Comparable<EdgeState> {
        private final String from;
        private final String to;
        private final boolean enabled;
        private final boolean unconditional;
        private final boolean evaluation;
        private volatile boolean selected;

        private EdgeState(final WorkflowHopMeta hop) {
            from = hop.getFromAction().getName();
            to = hop.getToAction().getName();
            enabled = hop.isEnabled();
            unconditional = hop.isUnconditional();
            evaluation = hop.isEvaluation();
        }

        private void evaluate(final org.apache.hop.core.Result result) {
            if (!enabled || result == null) {
                return;
            }
            if (unconditional || result.isResult() == evaluation) {
                selected = true;
            }
        }

        private Edge toEdge(final ConcurrentHashMap<String, NodeState> nodes) {
            final var target = nodes.get(to);
            final var targetExecuted = target != null && (target.started || target.finished);
            final var covered = enabled && (selected || targetExecuted);
            return new Edge(
                    edgeId(from, to),
                    from,
                    to,
                    enabled,
                    covered,
                    unconditional ? "unconditional" : (evaluation ? "success" : "failure"),
                    0,
                    selected,
                    targetExecuted);
        }

        @Override
        public int compareTo(final EdgeState other) {
            return (from + '\0' + to).compareTo(other.from + '\0' + other.to);
        }
    }
}
