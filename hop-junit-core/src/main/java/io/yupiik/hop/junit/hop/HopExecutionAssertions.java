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
package io.yupiik.hop.junit.hop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.hop.core.Result;

/**
 * JUnit assertion helpers for a Hop {@link Result}. Instantiate and use the instance methods; the
 * assertions throw JUnit assertion errors with the Hop log text appended when they fail.
 */
public class HopExecutionAssertions {
    public HopExecutionAssertions() {
        // no-op
    }

    public void assertSuccess(final String kind, final Result result) {
        assertEquals(0, result.getNrErrors(), () -> message(kind, "success", result));
    }

    public void assertFailure(final String kind, final Result result) {
        if (result.getNrErrors() <= 0) {
            throw new AssertionError(message(kind, "failure", result));
        }
    }

    public void assertLogContains(final String kind, final Result result, final String expected) {
        assertTrue(
                logText(result).contains(expected), () -> message(kind, "log containing '" + expected + "'", result));
    }

    public String logText(final Result result) {
        final var log = result.getLogText();
        return log == null ? "" : log;
    }

    private String message(final String kind, final String expectation, final Result result) {
        final var log = logText(result);
        if (log.isBlank()) {
            return "Expected " + kind + " " + expectation + " but got result=" + result;
        }
        return "Expected " + kind + " " + expectation + " but got result=" + result + System.lineSeparator() + log;
    }
}
