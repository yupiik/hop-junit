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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.apache.hop.core.Result;
import org.junit.jupiter.api.Test;

class HopFixtureSupportTest {
    @Test
    void escapesXmlParameterValuesBeforeSubstitution() {
        final var xml = "<root>${value}</root>";
        final var substituted = new HopFixtureSupport().substituteXmlParameters(xml, Map.of("value", "A&B<C>\"'"));

        assertTrue(substituted.contains("A&amp;B&lt;C&gt;&quot;&apos;"));
    }

    @Test
    void assertionFailureIncludesArtifactContextAndHopLog() {
        final var result = new Result();
        result.setNrErrors(1);
        result.setLogText("Hop pipeline diagnostics" + System.lineSeparator()
                + "artifact=T_BAD.hpl" + System.lineSeparator()
                + "parameters={archive.table=ORDERS}" + System.lineSeparator()
                + "java.lang.IllegalStateException: boom");

        final var error = assertThrows(
                AssertionError.class,
                () -> new HopPipelineExecution(result, "pipeline T_BAD.hpl (" + Path.of("T_BAD.hpl") + ")")
                        .assertSuccess());

        assertTrue(error.getMessage().contains("pipeline T_BAD.hpl"));
        assertTrue(error.getMessage().contains("artifact=T_BAD.hpl"));
        assertTrue(error.getMessage().contains("parameters={archive.table=ORDERS}"));
        assertTrue(error.getMessage().contains("java.lang.IllegalStateException: boom"));
    }

    @Test
    void executionCanAssertLogContent() {
        final var result = new Result();
        result.setLogText("diagnostic needle");

        new HopWorkflowExecution(result, "workflow T_SAMPLE.hwf").assertLogContains("needle");
    }
}
