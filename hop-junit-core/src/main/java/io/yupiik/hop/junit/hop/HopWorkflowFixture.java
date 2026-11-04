/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
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
import io.yupiik.hop.junit.hop.coverage.WorkflowCoverageCollector;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.hop.core.Const;
import org.apache.hop.core.Result;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.logging.LoggingObject;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.serializer.memory.MemoryMetadataProvider;
import org.apache.hop.pipeline.config.PipelineRunConfiguration;
import org.apache.hop.pipeline.engines.local.LocalPipelineRunConfiguration;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.config.WorkflowRunConfiguration;
import org.apache.hop.workflow.engine.IWorkflowEngine;
import org.apache.hop.workflow.engine.WorkflowEngineFactory;
import org.apache.hop.workflow.engines.local.LocalWorkflowRunConfiguration;

public final class HopWorkflowFixture {
    private final Path projectHome;
    private final Path workflowPath;
    private final Path workDirectory;
    private final String runConfiguration;
    private final HopCoverageModels.Test test;
    private final HopFixtureSupport support = new HopFixtureSupport();
    private final DatabaseConnections databaseConnections = new DatabaseConnections();
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private final Map<String, JdbcConnectionSpec> connections = new LinkedHashMap<>();

    public HopWorkflowFixture(
            final Path projectHome,
            final Path workflowPath,
            final Path workDirectory,
            final String runConfiguration,
            final HopCoverageModels.Test test,
            final Map<String, String> initialParameters) {
        this.projectHome = projectHome;
        this.workflowPath = workflowPath;
        this.workDirectory = workDirectory;
        this.runConfiguration = runConfiguration;
        this.test = test;
        this.parameters.putAll(initialParameters);
    }

    public HopWorkflowFixture with(final Map<String, String> parameters) {
        this.parameters.putAll(parameters);
        return this;
    }

    public HopWorkflowFixture with(final String name, final String value) {
        parameters.put(name, value);
        return this;
    }

    public HopWorkflowFixture withConnection(final String hopConnectionName, final JdbcConnectionSpec spec) {
        connections.put(hopConnectionName, spec);
        return this;
    }

    public HopWorkflowFixture with(final String hopConnectionName, final JdbcConnectionSpec spec) {
        return withConnection(hopConnectionName, spec);
    }

    public HopWorkflowExecution execute() {
        Result result = null;
        IWorkflowEngine<WorkflowMeta> engine = null;
        Throwable failure = null;
        String logChannelId = null;
        final var firstLogLine = support.firstLogLine();
        final var context = support.context(
                "workflow", workflowPath, projectHome, workDirectory, runConfiguration, parameters, connections);
        try {
            final var variables = new Variables();
            variables.setVariable("PROJECT_HOME", projectHome.toAbsolutePath().toString());
            variables.setVariable(
                    Const.INTERNAL_VARIABLE_WORKFLOW_FILENAME_FOLDER,
                    workflowPath.getParent().toString());
            variables.setVariable(
                    Const.INTERNAL_VARIABLE_WORKFLOW_FILENAME_NAME,
                    workflowPath.getFileName().toString());
            variables.setVariable(
                    Const.INTERNAL_VARIABLE_WORKFLOW_NAME,
                    workflowPath.getFileName().toString());
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
            final var config = saveRunConfiguration(metadataProvider);
            savePipelineRunConfiguration(metadataProvider);
            for (final var entry : connections.entrySet()) {
                saveConnection(metadataProvider, entry.getKey(), entry.getValue());
            }
            final var workflowFile = workDirectory.resolve("workflow.hwf");
            support.copyXmlWithParameters(workflowPath, workflowFile, parameters);
            final var workflowMeta =
                    new WorkflowMeta(variables, workflowFile.toAbsolutePath().toString(), metadataProvider);
            engine = WorkflowEngineFactory.createWorkflowEngine(
                    variables, runConfiguration, metadataProvider, workflowMeta, new LoggingObject(workflowMeta));
            logChannelId = engine.getLogChannel().getLogChannelId();
            engine.setWorkflowRunConfiguration(config);
            final var coverage =
                    HopCoverageRuntime.INSTANCE.isEnabled() ? new WorkflowCoverageCollector(workflowPath, test) : null;
            if (coverage != null) {
                coverage.attach(engine);
            }
            result = support.resultOrEmpty(engine.startExecution());
            logChannelId = support.firstNonBlank(
                    result.getLogChannelId(), engine.getLogChannel().getLogChannelId());
            if (coverage != null) {
                HopCoverageRuntime.INSTANCE.addExecution(coverage.toExecution(result));
            }
        } catch (final Throwable error) {
            failure = error;
            if (engine != null) {
                result = support.resultOrEmpty(engine.getResult());
                logChannelId = support.firstNonBlank(
                        result.getLogChannelId(), engine.getLogChannel().getLogChannelId());
            }
        } finally {
            failure = support.stopWorkflow(engine, failure);
        }
        result = support.resultOrEmpty(result);
        if (failure != null) {
            support.markFailure(result, failure);
        }
        support.addFailureDiagnostics(result, context, logChannelId, firstLogLine);
        return new HopWorkflowExecution(result, support.displayName("workflow", workflowPath));
    }

    private WorkflowRunConfiguration saveRunConfiguration(final MemoryMetadataProvider metadataProvider)
            throws HopException {
        final var config = new WorkflowRunConfiguration();
        config.setName(runConfiguration);
        config.setDescription("JUnit local workflow run configuration");
        final var engineConfig = new LocalWorkflowRunConfiguration();
        engineConfig.setEnginePluginId("Local");
        engineConfig.setEnginePluginName("Hop local workflow engine");
        config.setEngineRunConfiguration(engineConfig);
        metadataProvider.getSerializer(WorkflowRunConfiguration.class).save(config);
        return config;
    }

    private void savePipelineRunConfiguration(final MemoryMetadataProvider metadataProvider) throws HopException {
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

    public Path workflowPath() {
        return workflowPath;
    }

    public Path projectHome() {
        return projectHome;
    }

    public Path workDirectory() {
        return workDirectory;
    }
}
