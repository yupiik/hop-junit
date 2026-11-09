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

import java.nio.file.Path;
import org.apache.hop.core.Result;
import org.apache.hop.core.extension.ExtensionPoint;
import org.apache.hop.core.extension.ExtensionPointPluginType;
import org.apache.hop.core.extension.IExtensionPoint;
import org.apache.hop.core.logging.ILogChannel;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;

/**
 * Captures coverage of pipelines that are executed as a nested action of a workflow (or
 * nested inside another pipeline). Top-level pipelines started from the {@code @HopPipeline}
 * fixture are captured by the fixture itself and are deliberately skipped here to avoid
 * counting them twice.
 *
 * <p>Every nested engine produces exactly one {@link HopCoverageModels.Execution}; executions of
 * the same pipeline are deduplicated (union) by the post-processor keyed on {@code kind:path}
 * and node/edge ids, so a pipeline run standalone and nested in one or several workflows only
 * counts its nodes/edges once.
 */
@ExtensionPoint(
        id = "HopCoverageNestedPipeline",
        extensionPointId = "PipelineStart",
        description = "Hop coverage collector for nested pipeline executions")
public final class PipelineCoverageExtensionPoint implements IExtensionPoint<IPipelineEngine<PipelineMeta>> {
    private static boolean registered;
    private static final Object REGISTRATION_LOCK = new Object();

    public PipelineCoverageExtensionPoint() {
        // no-op
    }

    /**
     * Registers this extension point once, before any workflow starts running nested pipelines.
     * Idempotent across instantiations via the JVM-wide guard.
     */
    public void register() {
        if (registered) {
            return;
        }
        synchronized (REGISTRATION_LOCK) {
            if (registered) {
                return;
            }
            try {
                PluginRegistry.getInstance()
                        .registerPluginClass(
                                PipelineCoverageExtensionPoint.class.getName(),
                                ExtensionPointPluginType.class,
                                ExtensionPoint.class);
                registered = true;
            } catch (final Exception error) {
                throw new IllegalStateException(
                        "Unable to register Hop coverage nested-pipeline extension point", error);
            }
        }
    }

    public static boolean isRegistered() {
        return registered;
    }

    @Override
    public void callExtensionPoint(
            final ILogChannel log, final IVariables variables, final IPipelineEngine<PipelineMeta> engine) {
        if (!HopCoverageRuntime.INSTANCE.isEnabled() || engine == null) {
            return;
        }
        try {
            capture(engine);
        } catch (final Exception error) {
            log.logError(
                    "Unable to capture coverage for nested pipeline '"
                            + (engine.getPipelineMeta() == null
                                    ? "?"
                                    : engine.getPipelineMeta().getName()) + "'.",
                    error);
        }
    }

    private void capture(final IPipelineEngine<PipelineMeta> engine) {
        // Skip top-level pipelines: the @HopPipeline fixture already records them. Only capture
        // pipelines launched from within a workflow or another pipeline (nested).
        if (engine.getParentWorkflow() == null && engine.getParentPipeline() == null) {
            return;
        }
        final var meta = engine.getPipelineMeta();
        if (meta == null || meta.getFilename() == null || meta.getFilename().isBlank()) {
            return;
        }
        final var test = test(engine);
        final var collector = new PipelineCoverageCollector(Path.of(meta.getFilename()), test);
        collector.attach(engine);
        engine.addExecutionFinishedListener(p -> {
            final var result = p.getResult();
            HopCoverageRuntime.INSTANCE.addExecution(collector.toExecution(result == null ? new Result() : result));
        });
    }

    private HopCoverageModels.Test test(final IPipelineEngine<PipelineMeta> engine) {
        final var meta = engine.getPipelineMeta();
        final var name = meta.getFilename() == null ? "unknown" : meta.getFilename();
        return new HopCoverageModels.Test(
                PipelineCoverageExtensionPoint.class.getName(),
                "nested",
                "Nested pipeline " + name,
                "unique-" + engine.getLogChannelId());
    }
}
