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
package io.yupiik.hop.junit.hop;

import io.yupiik.hop.junit.database.DatabaseConnections;
import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels;
import io.yupiik.hop.junit.hop.coverage.HopCoverageRuntime;
import io.yupiik.hop.junit.hop.coverage.PipelineCoverageCollector;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hop.core.Const;
import org.apache.hop.core.Result;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.pipeline.engine.PipelineEngineFactory;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;

/**
 * Fluent builder injected into a test method when {@link HopPipeline} is declared.
 *
 * <p>Use it to override execution parameters and JDBC connections, then call {@link #execute()} to run the
 * pipeline once and obtain a {@link HopPipelineExecution} to assert on. The fixture is created by
 * {@link HopPipelineExtension}, wired to an isolated Hop working tree, and already carries the parameters
 * declared on the annotation.
 *
 * <p>Parameter and connection registration is last-one-wins: subsequent {@link #with(String, String)} /
 * {@link #withConnection(String, JdbcConnectionSpec)} calls override earlier values, including those
 * introduced by the {@link HopPipeline} annotation.
 *
 * @see HopPipelineExecution
 */
public final class HopPipelineFixture {
    private final Path projectHome;
    private final Path pipelinePath;
    private final Path workDirectory;
    private final String runConfiguration;
    private final HopCoverageModels.Test test;
    private final HopFixtureSupport support = new HopFixtureSupport();
    private final DatabaseConnections databaseConnections = new DatabaseConnections();
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private final Map<String, JdbcConnectionSpec> connections = new LinkedHashMap<>();

    public HopPipelineFixture(
            final Path projectHome,
            final Path pipelinePath,
            final Path workDirectory,
            final String runConfiguration,
            final HopCoverageModels.Test test,
            final Map<String, String> initialParameters) {
        this.projectHome = projectHome;
        this.pipelinePath = pipelinePath;
        this.workDirectory = workDirectory;
        this.runConfiguration = runConfiguration;
        this.test = test;
        this.parameters.putAll(initialParameters);
    }

    /**
     * Merges the given parameters into the execution parameters, overriding existing keys (last wins).
     *
     * @param parameters the name/value pairs to merge
     * @return this fixture
     */
    public HopPipelineFixture with(final Map<String, String> parameters) {
        this.parameters.putAll(parameters);
        return this;
    }

    /**
     * Sets a single execution parameter, overriding any existing value for {@code name} (last wins).
     *
     * @param name the parameter name
     * @param value the parameter value
     * @return this fixture
     */
    public HopPipelineFixture with(final String name, final String value) {
        parameters.put(name, value);
        return this;
    }

    /**
     * Registers (or overrides) the {@link JdbcConnectionSpec} used by the named Hop connection at runtime.
     *
     * @param hopConnectionName the Hop connection name the pipeline resolves
     * @param spec the connection descriptor
     * @return this fixture
     */
    public HopPipelineFixture withConnection(final String hopConnectionName, final JdbcConnectionSpec spec) {
        connections.put(hopConnectionName, spec);
        return this;
    }

    /**
     * Alias of {@link #withConnection(String, JdbcConnectionSpec)} for symmetry with {@link #with(String, String)}.
     *
     * @param hopConnectionName the Hop connection name the pipeline resolves
     * @param spec the connection descriptor
     * @return this fixture
     */
    public HopPipelineFixture with(final String hopConnectionName, final JdbcConnectionSpec spec) {
        return withConnection(hopConnectionName, spec);
    }

    /**
     * Runs the pipeline (a single, terminal call) and returns the final execution result to assert on.
     *
     * <p>The pipeline is started and awaited to completion; the returned {@link HopPipelineExecution} exposes the
     * Hop {@link Result}, its log text and the {@code assertSuccess()}/{@code assertFailure()}/{@code
     * assertLogContains()}/{@code assertThat()} assertions.
     *
     * @return a fluent execution object wrapping the pipeline result
     */
    public HopPipelineExecution execute() {
        Result result = null;
        IPipelineEngine<PipelineMeta> engine = null;
        Throwable failure = null;
        String logChannelId = null;
        final var firstLogLine = support.firstLogLine();
        final var context = support.context(
                "pipeline", pipelinePath, projectHome, workDirectory, runConfiguration, parameters, connections);
        try {
            final var variables = new Variables();
            variables.setVariable("PROJECT_HOME", projectHome.toAbsolutePath().toString());
            variables.setVariable(
                    Const.INTERNAL_VARIABLE_PIPELINE_FILENAME_DIRECTORY,
                    pipelinePath.getParent().toString());
            variables.setVariable(
                    Const.INTERNAL_VARIABLE_PIPELINE_FILENAME_NAME,
                    pipelinePath.getFileName().toString());
            variables.setVariable(
                    Const.INTERNAL_VARIABLE_PIPELINE_NAME,
                    pipelinePath.getFileName().toString());
            variables.setVariable("HOP_HOME", workDirectory.resolve("home").toString());
            variables.setVariable(
                    "HOP_CONFIG_FOLDER", workDirectory.resolve("config").toString());
            variables.setVariable(
                    "HOP_AUDIT_FOLDER", workDirectory.resolve("audit").toString());
            variables.setVariable(
                    "HOP_METADATA_FOLDER", workDirectory.resolve("metadata").toString());
            variables.setVariable("HOP_TEST_WORK", workDirectory.toString());
            parameters.forEach(variables::setVariable);
            final var metadataProvider = new MemoryMetadataProvider();
            saveRunConfiguration(metadataProvider);
            for (final var entry : connections.entrySet()) {
                saveConnection(metadataProvider, entry.getKey(), entry.getValue());
            }
            final var pipelineFile = workDirectory.resolve("pipeline.hpl");
            support.copyXmlWithParameters(pipelinePath, pipelineFile, parameters);
            final var pipelineMeta =
                    new PipelineMeta(pipelineFile.toAbsolutePath().toString(), metadataProvider, variables);
            engine = PipelineEngineFactory.createPipelineEngine(
                    variables, runConfiguration, metadataProvider, pipelineMeta);
            logChannelId = engine.getLogChannelId();
            engine.setMetadataProvider(metadataProvider);
            final var coverage =
                    HopCoverageRuntime.INSTANCE.isEnabled() ? new PipelineCoverageCollector(pipelinePath, test) : null;
            engine.prepareExecution();
            if (coverage != null) {
                coverage.attach(engine);
            }
            engine.startThreads();
            engine.waitUntilFinished();
            result = support.resultOrEmpty(engine.getResult());
            logChannelId = support.firstNonBlank(result.getLogChannelId(), engine.getLogChannelId());
            if (coverage != null) {
                HopCoverageRuntime.INSTANCE.addExecution(coverage.toExecution(result));
            }
        } catch (final Throwable error) {
            failure = error;
            if (engine != null) {
                result = support.resultOrEmpty(engine.getResult());
                logChannelId = support.firstNonBlank(result.getLogChannelId(), engine.getLogChannelId());
            }
        } finally {
            failure = support.stopPipeline(engine, failure);
            failure = support.cleanupPipeline(engine, failure);
        }
        result = support.resultOrEmpty(result);
        if (failure != null) {
            support.markFailure(result, failure);
        }
        support.addFailureDiagnostics(result, context, logChannelId, firstLogLine);
        return new HopPipelineExecution(result, support.displayName("pipeline", pipelinePath));
    }

    private void saveRunConfiguration(final MemoryMetadataProvider metadataProvider) throws HopException {
        final var config = new PipelineRunConfiguration();
        config.setName(runConfiguration);
        config.setDescription("JUnit local pipeline run configuration");
        final var engineConfig = new LocalPipelineRunConfiguration();
        engineConfig.setEnginePluginId("Local");
        engineConfig.setEnginePluginName("Hop local pipeline engine");
        config.setEngineRunConfiguration(engineConfig);
        metadataProvider.getSerializer(PipelineRunConfiguration.class).save(config);
    }

    private void saveConnection(
            final MemoryMetadataProvider metadataProvider, final String name, final JdbcConnectionSpec spec)
            throws HopException {
        databaseConnections.save(metadataProvider, name, spec);
    }

    /** @return the resolved pipeline file path */
    public Path pipelinePath() {
        return pipelinePath;
    }

    /** @return the Hop project home */
    public Path projectHome() {
        return projectHome;
    }

    /** @return the isolated Hop working directory prepared for this test */
    public Path workDirectory() {
        return workDirectory;
    }
}
