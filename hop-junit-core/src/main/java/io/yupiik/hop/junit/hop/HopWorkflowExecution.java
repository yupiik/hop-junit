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

import java.util.function.Consumer;
import org.apache.hop.core.Result;

/**
 * Fluent result of a {@link HopWorkflowFixture#execute()} call, wrapping the Hop {@link Result}.
 *
 * <p>Provides direct access to the raw {@link Result} and its log text, plus fluent assertions
 * ({@link #assertSuccess()}, {@link #assertFailure()}, {@link #assertLogContains(String)}) and an
 * escape hatch ({@link #assertThat(Consumer)}) for grouping custom assertions. Every assertion method
 * returns this execution to keep the chain fluent.
 */
public final class HopWorkflowExecution {
    private final Result result;
    private final String description;
    private final HopExecutionAssertions assertions = new HopExecutionAssertions();

    public HopWorkflowExecution(final Result result) {
        this(result, "workflow");
    }

    public HopWorkflowExecution(final Result result, final String description) {
        this.result = result;
        this.description = description;
    }

    /** @return the raw Hop {@link Result} produced by the execution */
    public Result result() {
        return result;
    }

    /** @return the full Hop log text captured during the execution */
    public String logText() {
        return assertions.logText(result);
    }

    /**
     * Asserts the execution succeeded ({@code Result} error count is 0); on failure the Hop log is appended
     * to the {@link AssertionError} message.
     *
     * @return this execution
     */
    public HopWorkflowExecution assertSuccess() {
        assertions.assertSuccess(description, result);
        return this;
    }

    /**
     * Asserts the execution failed ({@code Result} error count is {@code > 0}); on success the Hop log is
     * appended to the {@link AssertionError} message.
     *
     * @return this execution
     */
    public HopWorkflowExecution assertFailure() {
        assertions.assertFailure(description, result);
        return this;
    }

    /**
     * Asserts the Hop log text contains the given substring; on mismatch the Hop log is appended to the
     * {@link AssertionError} message.
     *
     * @param expected the substring the log must contain
     * @return this execution
     */
    public HopWorkflowExecution assertLogContains(final String expected) {
        assertions.assertLogContains(description, result, expected);
        return this;
    }

    /**
     * Runs a custom assertion callback on this execution, rethrowing any thrown error.
     *
     * <p>This is the escape hatch for asserting beyond the built-in methods (for example on {@link #result()}
     * or on {@link #logText()}); the callback is invoked as-is and returned {@code this} keeps the chain fluent.
     *
     * @param assertions the callback invoked with this execution
     * @return this execution
     */
    public HopWorkflowExecution assertThat(final Consumer<HopWorkflowExecution> assertions) {
        assertions.accept(this);
        return this;
    }
}
