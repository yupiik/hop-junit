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
package io.yupiik.hop.junit.hop.coverage;

import java.util.List;

public final class HopCoverageModels {
    private HopCoverageModels() {
        // no-op
    }

    public record JvmReport(
            int schemaVersion,
            long processId,
            String jvmStartedAt,
            String reportWrittenAt,
            List<Execution> executions) {}

    public record AggregateReport(
            int schemaVersion,
            String generatedAt,
            Summary summary,
            List<ArtifactReport> artifacts,
            List<Execution> executions,
            List<Warning> warnings) {}

    public record ArtifactReport(
            Artifact artifact,
            Summary summary,
            List<Node> nodes,
            List<Edge> edges,
            List<String> executionIds,
            Double requiredCoverage) {}

    public record Execution(
            String id,
            String kind,
            Artifact artifact,
            Test test,
            Run run,
            Result result,
            Summary summary,
            List<Node> nodes,
            List<Edge> edges,
            List<Warning> warnings) {}

    public record Artifact(String kind, String name, String path) {}

    public record Test(String className, String methodName, String displayName, String uniqueId) {}

    public record Run(String threadName, long threadId, String startedAt, String endedAt, long durationMillis) {}

    public record Result(
            boolean success,
            long errors,
            long linesInput,
            long linesOutput,
            long linesRead,
            long linesWritten,
            long linesUpdated,
            long linesRejected) {}

    public record Summary(Ratio coverage, Ratio nodes, Ratio edges, Ratio branches) {}

    public record Ratio(long covered, long total, double ratio) {}

    public record Node(
            String id,
            String name,
            String type,
            boolean enabled,
            boolean covered,
            Integer x,
            Integer y,
            Integer copies,
            List<Integer> startedCopies,
            List<Integer> finishedCopies,
            long linesInput,
            long linesOutput,
            long linesRead,
            long linesWritten,
            long errors,
            Boolean result) {}

    public record Edge(
            String id,
            String from,
            String to,
            boolean enabled,
            boolean covered,
            String kind,
            long rows,
            boolean selected,
            boolean targetExecuted) {}

    public record Warning(String scope, String message) {}
}
