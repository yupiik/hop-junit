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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.hop.junit.database.JdbcConnectionSpec;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.hop.core.Result;
import org.apache.hop.core.logging.HopLogStore;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.engine.IPipelineEngine;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.engine.IWorkflowEngine;

/**
 * Shared helpers for the pipeline and workflow fixtures: XML parameter substitution, Hop execution
 * stop/cleanup, failure marking and diagnostics formatting.
 */
public class HopFixtureSupport {
    public HopFixtureSupport() {
        // no-op
    }

    public void copyXmlWithParameters(final Path source, final Path target, final Map<String, String> parameters)
            throws Exception {
        Files.writeString(target, substituteXmlParameters(Files.readString(source, UTF_8), parameters), UTF_8);
    }

    public String substituteXmlParameters(final String xml, final Map<String, String> parameters) {
        var substituted = xml;
        for (final var entry : parameters.entrySet()) {
            substituted = substituted.replace("${" + entry.getKey() + "}", escapeXml(entry.getValue()));
        }
        return substituted;
    }

    private static String escapeXml(final String value) {
        if (value == null) {
            return "";
        }
        final var escaped = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    public Result resultOrEmpty(final Result result) {
        return result == null ? new Result() : result;
    }

    public void markFailure(final Result result, final Throwable error) {
        result.setNrErrors(Math.max(1, result.getNrErrors()));
        result.setResult(false);
        appendLogText(result, stackTrace(error));
    }

    public void addFailureDiagnostics(
            final Result result, final String context, final String logChannelId, final int firstLogLine) {
        if (result.getNrErrors() <= 0) {
            return;
        }
        final var diagnostics = new StringBuilder(context);
        final var hopLog = hopLog(logChannelId, firstLogLine);
        if (!hopLog.isBlank()) {
            diagnostics
                    .append(System.lineSeparator())
                    .append("Hop log:")
                    .append(System.lineSeparator())
                    .append(hopLog);
        }
        appendLogText(result, diagnostics.toString());
    }

    public int firstLogLine() {
        return HopLogStore.isInitialized() ? HopLogStore.getLastBufferLineNr() : 0;
    }

    public String displayName(final String kind, final Path path) {
        return kind + " " + path.getFileName() + " (" + path.toAbsolutePath().normalize() + ")";
    }

    public String context(
            final String kind,
            final Path path,
            final Path projectHome,
            final Path workDirectory,
            final String runConfiguration,
            final Map<String, String> parameters,
            final Map<String, JdbcConnectionSpec> connections) {
        return "Hop " + kind + " diagnostics" + System.lineSeparator()
                + "artifact=" + path.getFileName() + System.lineSeparator()
                + "path=" + path.toAbsolutePath().normalize() + System.lineSeparator()
                + "projectHome=" + projectHome.toAbsolutePath().normalize() + System.lineSeparator()
                + "workDirectory=" + workDirectory.toAbsolutePath().normalize() + System.lineSeparator()
                + "runConfiguration=" + runConfiguration + System.lineSeparator()
                + "parameters=" + describeParameters(parameters) + System.lineSeparator()
                + "connections=" + connections.keySet();
    }

    public Throwable stopPipeline(final IPipelineEngine<PipelineMeta> engine, final Throwable failure) {
        if (engine == null) {
            return failure;
        }
        try {
            if (engine.isRunning() && !engine.isFinished()) {
                engine.stopAll();
            }
        } catch (final Throwable error) {
            return chain(failure, "Unable to stop Hop pipeline engine", error);
        }
        return failure;
    }

    public Throwable cleanupPipeline(final IPipelineEngine<PipelineMeta> engine, final Throwable failure) {
        if (engine == null) {
            return failure;
        }
        try {
            engine.cleanup();
        } catch (final Throwable error) {
            return chain(failure, "Unable to cleanup Hop pipeline engine", error);
        }
        return failure;
    }

    public Throwable stopWorkflow(final IWorkflowEngine<WorkflowMeta> engine, final Throwable failure) {
        if (engine == null) {
            return failure;
        }
        try {
            if (engine.isActive() && !engine.isFinished()) {
                engine.stopExecution();
            }
        } catch (final Throwable error) {
            return chain(failure, "Unable to stop Hop workflow engine", error);
        }
        return failure;
    }

    public String firstNonBlank(final String first, final String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static Throwable chain(final Throwable failure, final String message, final Throwable error) {
        final var wrapped = new IllegalStateException(message, error);
        if (failure == null) {
            return wrapped;
        }
        failure.addSuppressed(wrapped);
        return failure;
    }

    private static String describeParameters(final Map<String, String> parameters) {
        final var builder = new StringBuilder("{");
        var first = true;
        for (final var entry : parameters.entrySet()) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(mask(entry.getKey(), entry.getValue()));
        }
        return builder.append('}').toString();
    }

    private static String mask(final String key, final String value) {
        final var normalized = key.toLowerCase();
        if (normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")) {
            return "***";
        }
        return String.valueOf(value);
    }

    private static String hopLog(final String logChannelId, final int firstLogLine) {
        if (!HopLogStore.isInitialized() || logChannelId == null || logChannelId.isBlank()) {
            return "";
        }
        try {
            return HopLogStore.getAppender()
                    .getBuffer(logChannelId, true, firstLogLine, HopLogStore.getLastBufferLineNr())
                    .toString();
        } catch (final Throwable error) {
            return "Unable to read Hop log buffer:" + System.lineSeparator() + stackTrace(error);
        }
    }

    private static void appendLogText(final Result result, final String logText) {
        if (logText == null || logText.isBlank()) {
            return;
        }
        final var existing = result.getLogText();
        if (existing == null || existing.isBlank()) {
            result.setLogText(logText);
            return;
        }
        if (existing.contains(logText)) {
            return;
        }
        result.setLogText(existing + System.lineSeparator() + logText);
    }

    private static String stackTrace(final Throwable error) {
        final var writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
