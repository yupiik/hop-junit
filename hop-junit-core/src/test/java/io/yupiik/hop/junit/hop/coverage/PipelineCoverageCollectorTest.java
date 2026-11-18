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

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Edge;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Node;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.hop.core.Result;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.EngineComponent.ComponentExecutionStatus;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.ITransformFinishedListener;
import org.apache.hop.pipeline.transform.ITransformStartedListener;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.Test;

class PipelineCoverageCollectorTest {
    @Test
    void creditsTransformsWhoseListenersDidNotFireBeforeTheReport() {
        final var source = new TransformMeta("DataGrid", "Source", null);
        final var target = new TransformMeta("TextFileOutput", "Target", null);
        final var idle = new TransformMeta("TextFileOutput", "Idle", null);
        source.setDistributes(false); // two outputs without copy: only row evidence credits a target
        final var meta = new PipelineMeta();
        meta.addTransform(source);
        meta.addTransform(target);
        meta.addTransform(idle);
        meta.addPipelineHop(new PipelineHopMeta(source, target));
        meta.addPipelineHop(new PipelineHopMeta(source, idle));

        // listeners are accepted but never notified: the engine reports the run done before the
        // transforms announce themselves, or they finished before being attached
        final var transforms = Map.of(
                "Source", List.of(transform(ComponentExecutionStatus.STATUS_FINISHED, 3)),
                "Target", List.of(transform(ComponentExecutionStatus.STATUS_FINISHED, 3)),
                "Idle", List.of(transform(ComponentExecutionStatus.STATUS_INIT, 0)));
        final var collector = new PipelineCoverageCollector(Path.of("p.hpl"), null);
        collector.attach(engine(meta, transforms));

        final var execution = collector.toExecution(new Result());
        final var nodes = execution.nodes().stream().collect(toMap(Node::name, Function.identity()));
        final var edges = execution.edges().stream().collect(toMap(Edge::to, Function.identity()));

        assertTrue(nodes.get("Source").covered());
        assertTrue(nodes.get("Target").covered());
        assertEquals(List.of(0), nodes.get("Target").finishedCopies());
        assertEquals(3, nodes.get("Target").linesWritten());
        assertTrue(edges.get("Target").covered());
        assertFalse(nodes.get("Idle").covered());
        assertFalse(edges.get("Idle").covered());
    }

    @Test
    void sumsCopyMetricsWithoutCountingListenerAndSnapshotObservationsTwice() {
        final var meta = new PipelineMeta();
        final var transformMeta = new TransformMeta("DataGrid", "Source", null);
        transformMeta.setCopies(2);
        meta.addTransform(transformMeta);
        final var first = new RuntimeTransform(0, ComponentExecutionStatus.STATUS_FINISHED, 3);
        final var second = new RuntimeTransform(1, ComponentExecutionStatus.STATUS_FINISHED, 5);
        final var collector = new PipelineCoverageCollector(Path.of("copies.hpl"), null);
        collector.attach(engine(meta, Map.of("Source", List.of(first.transform, second.transform))));

        first.startedListeners.forEach(it -> it.transformStarted(null, transformMeta, first.transform));
        first.finishedListeners.forEach(it -> it.transformFinished(null, transformMeta, first.transform));
        final var beforeLateListener =
                collector.toExecution(new Result()).nodes().getFirst();
        assertEquals(List.of(0, 1), beforeLateListener.startedCopies());
        assertEquals(List.of(0, 1), beforeLateListener.finishedCopies());
        assertEquals(2, beforeLateListener.copies());
        assertMetrics(beforeLateListener, 8);

        // The other copy announces itself only after the pipeline report has been built.
        second.finishedListeners.forEach(it -> it.transformFinished(null, transformMeta, second.transform));
        assertEquals(
                beforeLateListener, collector.toExecution(new Result()).nodes().getFirst());
        assertEquals(
                beforeLateListener, collector.toExecution(new Result()).nodes().getFirst());
    }

    @Test
    void replacesEarlierObservationsOfEachCopyWithItsLatestMetrics() {
        final var meta = new PipelineMeta();
        final var transformMeta = new TransformMeta("DataGrid", "Source", null);
        meta.addTransform(transformMeta);
        final var runtime = new RuntimeTransform(0, ComponentExecutionStatus.STATUS_RUNNING, 3);
        final var collector = new PipelineCoverageCollector(Path.of("progress.hpl"), null);
        collector.attach(engine(meta, Map.of("Source", List.of(runtime.transform))));

        final var running = collector.toExecution(new Result()).nodes().getFirst();
        assertEquals(List.of(0), running.startedCopies());
        assertEquals(List.of(), running.finishedCopies());
        assertMetrics(running, 3);

        runtime.rows = 5;
        runtime.status = ComponentExecutionStatus.STATUS_FINISHED;
        runtime.finishedListeners.forEach(it -> it.transformFinished(null, transformMeta, runtime.transform));
        final var finished = collector.toExecution(new Result()).nodes().getFirst();
        assertEquals(List.of(0), finished.finishedCopies());
        assertMetrics(finished, 5);
        assertMetrics(running, 3); // Previously emitted executions are immutable.
    }

    private static void assertMetrics(final Node node, final long rows) {
        assertEquals(rows, node.linesInput());
        assertEquals(rows * 2, node.linesOutput());
        assertEquals(rows * 3, node.linesRead());
        assertEquals(rows * 4, node.linesWritten());
        assertEquals(rows * 5, node.errors());
    }

    @SuppressWarnings("unchecked")
    private static IPipelineEngine<PipelineMeta> engine(
            final PipelineMeta meta, final Map<String, List<ITransform>> transforms) {
        return (IPipelineEngine<PipelineMeta>) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {IPipelineEngine.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPipelineMeta" -> meta;
                    case "findComponent" -> transforms.get((String) args[0]).get((Integer) args[1]);
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ITransform transform(final ComponentExecutionStatus status, final long linesWritten) {
        return (ITransform) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {ITransform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStatus" -> status;
                    case "getLinesWritten" -> linesWritten;
                    case "getLinesRead" -> linesWritten;
                    case "getCopy" -> 0;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class RuntimeTransform {
        private final List<ITransformStartedListener> startedListeners = new ArrayList<>();
        private final List<ITransformFinishedListener> finishedListeners = new ArrayList<>();
        private final ITransform transform;
        private ComponentExecutionStatus status;
        private long rows;

        private RuntimeTransform(final int copy, final ComponentExecutionStatus status, final long rows) {
            this.status = status;
            this.rows = rows;
            transform = (ITransform) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {ITransform.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCopy" -> copy;
                        case "getStatus" -> this.status;
                        case "getLinesInput" -> this.rows;
                        case "getLinesOutput" -> this.rows * 2;
                        case "getLinesRead" -> this.rows * 3;
                        case "getLinesWritten" -> this.rows * 4;
                        case "getErrors" -> this.rows * 5;
                        case "addTransformStartedListener" -> {
                            startedListeners.add((ITransformStartedListener) args[0]);
                            yield null;
                        }
                        case "addTransformFinishedListener" -> {
                            finishedListeners.add((ITransformFinishedListener) args[0]);
                            yield null;
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }
    }

    private static Object defaultValue(final Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
