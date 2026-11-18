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

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.hop.HopTestEnvironment;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Execution;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Node;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.Pipeline;
import org.apache.hop.pipeline.PipelineHopMeta;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engines.local.LocalPipelineEngine;
import org.apache.hop.pipeline.transform.BaseTransform;
import org.apache.hop.pipeline.transform.BaseTransformData;
import org.apache.hop.pipeline.transform.BaseTransformMeta;
import org.apache.hop.pipeline.transform.ITransform;
import org.apache.hop.pipeline.transform.ITransformData;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class PipelineCoverageCollectorLifecycleTest {
    @BeforeAll
    static void initializeHop() {
        new HopTestEnvironment().init();
    }

    @Test
    void collectsRowsMetricsAndAllCopiesAtPipelineCompletion() throws Exception {
        final var execution = execute(false);

        assertCompletedNodes(execution);
        assertEquals(1, execution.edges().size());
        final var edge = execution.edges().getFirst();
        assertEquals("Source", edge.from());
        assertEquals("Sink", edge.to());
        assertTrue(edge.enabled());
        assertTrue(edge.covered());
        assertEquals(4, edge.rows());
    }

    @Test
    void recoversMetricsAndFinishedCopiesWhenAttachedAfterCompletion() throws Exception {
        // Represents discovering an already-finished child pipeline in a nested execution:
        // no started/finished/row listener installed by the collector can have observed this run.
        final var execution = execute(true);

        assertCompletedNodes(execution);
        assertEquals(1, execution.edges().size());
        final var edge = execution.edges().getFirst();
        assertTrue(edge.covered()); // inferred from the completed source/sink, not row callbacks
        assertEquals(0, edge.rows());
    }

    private Execution execute(final boolean lateAttach) throws Exception {
        final var source = new TransformMeta("LifecycleSource", "Source", null);
        source.setTransform(new TestTransformMeta(true));
        source.setDistributes(true);
        final var sink = new TransformMeta("LifecycleSink", "Sink", null);
        sink.setTransform(new TestTransformMeta(false));
        sink.setCopies(2);
        final var meta = new PipelineMeta();
        meta.setName("coverage-lifecycle");
        meta.addTransform(source);
        meta.addTransform(sink);
        meta.addPipelineHop(new PipelineHopMeta(source, sink));

        final var pipeline = new LocalPipelineEngine(meta);
        pipeline.setMetadataProvider(new MemoryMetadataProvider());
        final var collector = new PipelineCoverageCollector(Path.of("coverage-lifecycle.hpl"), null);
        final var report = new CompletableFuture<Execution>();
        final var pipelineFinished = new CountDownLatch(1);
        final var transformsFinished = new CountDownLatch(3);
        boolean started = false;
        try {
            pipeline.prepareExecution();
            assertEquals(3, pipeline.getTransforms().size());
            if (!lateAttach) {
                collector.attach(pipeline);
                final var prepared = collector.toExecution(pipeline.getResult());
                assertTrue(prepared.nodes().stream().noneMatch(Node::covered));
                assertTrue(prepared.nodes().stream()
                        .allMatch(node -> node.finishedCopies().isEmpty()));
            }
            for (final var combi : pipeline.getTransforms()) {
                combi.transform.addTransformFinishedListener(
                        (engine, transformMeta, transform) -> transformsFinished.countDown());
            }
            pipeline.addExecutionFinishedListener(engine -> {
                try {
                    if (!lateAttach) {
                        // Snapshot in the engine completion callback, not after waiting for transform
                        // listeners, which can be notified after the pipeline completion callback.
                        report.complete(collector.toExecution(engine.getResult()));
                    }
                } catch (final Throwable error) {
                    report.completeExceptionally(error);
                } finally {
                    pipelineFinished.countDown();
                }
            });
            started = true;
            pipeline.startThreads();
            assertTrue(pipelineFinished.await(10, SECONDS), "Pipeline did not finish");
            assertTrue(transformsFinished.await(10, SECONDS), "Transform listeners did not finish");
            pipeline.waitUntilFinished();
            assertEquals(0, pipeline.getErrors());
            assertTrue(pipeline.isFinished());
            if (lateAttach) {
                collector.attach(pipeline);
                return collector.toExecution(pipeline.getResult());
            }
            return report.get(1, SECONDS);
        } finally {
            try {
                if (!pipeline.isFinished()) {
                    pipeline.stopAll();
                }
                if (started) {
                    // Bounded even on failure; stopAll unblocks the sink's getRow().
                    assertTrue(transformsFinished.await(5, SECONDS), "Transforms did not stop");
                }
            } finally {
                pipeline.cleanup();
            }
        }
    }

    private void assertCompletedNodes(final Execution execution) {
        assertTrue(execution.result().success());
        assertEquals(2, execution.nodes().size());
        final var nodes = execution.nodes().stream().collect(toMap(Node::name, Function.identity()));
        final var source = nodes.get("Source");
        assertTrue(source.covered());
        assertEquals(1, source.copies());
        assertEquals(List.of(0), source.startedCopies());
        assertEquals(List.of(0), source.finishedCopies());
        assertEquals(4, source.linesInput());
        assertEquals(4, source.linesWritten());
        assertEquals(0, source.linesRead());
        assertEquals(0, source.linesOutput());
        assertEquals(0, source.errors());

        final var sink = nodes.get("Sink");
        assertTrue(sink.covered());
        assertEquals(2, sink.copies());
        assertEquals(List.of(0, 1), sink.startedCopies());
        assertEquals(List.of(0, 1), sink.finishedCopies());
        assertEquals(4, sink.linesRead());
        assertEquals(4, sink.linesOutput());
        assertEquals(0, sink.linesInput());
        assertEquals(0, sink.linesWritten());
        assertEquals(0, sink.errors());
    }

    // Explicit factories avoid plugin registration and reflective constructor requirements.
    // Row transport, counters, threads, statuses and lifecycle notifications are Hop's real ones.
    private static final class TestTransformMeta extends BaseTransformMeta<TestTransform, BaseTransformData> {
        private final boolean source;

        private TestTransformMeta(final boolean source) {
            this.source = source;
        }

        @Override
        public ITransformData createTransformData() {
            return new BaseTransformData() {};
        }

        @Override
        public ITransform createTransform(
                final TransformMeta transformMeta,
                final ITransformData data,
                final int copyNr,
                final PipelineMeta pipelineMeta,
                final Pipeline pipeline) {
            return new TestTransform(transformMeta, this, (BaseTransformData) data, copyNr, pipelineMeta, pipeline);
        }
    }

    private static final class TestTransform extends BaseTransform<TestTransformMeta, BaseTransformData> {
        private final RowMeta rows = new RowMeta();
        private long emitted;

        private TestTransform(
                final TransformMeta transformMeta,
                final TestTransformMeta meta,
                final BaseTransformData data,
                final int copyNr,
                final PipelineMeta pipelineMeta,
                final Pipeline pipeline) {
            super(transformMeta, meta, data, copyNr, pipelineMeta, pipeline);
            rows.addValueMeta(new ValueMetaInteger("value"));
        }

        @Override
        public boolean processRow() throws HopException {
            if (meta.source) {
                if (emitted == 4) {
                    setOutputDone();
                    return false;
                }
                incrementLinesInput();
                // Route explicitly so Hop emits destination-aware row callbacks as well as counters.
                final var outputs = getOutputRowSets();
                final var output = outputs.get((int) (emitted % outputs.size()));
                putRowTo(rows, new Object[] {++emitted}, output);
                return true;
            }
            if (getRow() == null) {
                setOutputDone();
                return false;
            }
            incrementLinesOutput();
            return true;
        }
    }
}
