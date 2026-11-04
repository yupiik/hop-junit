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

import java.util.function.Consumer;
import org.apache.hop.core.Result;

public final class HopPipelineExecution {
    private final Result result;
    private final String description;
    private final HopExecutionAssertions assertions = new HopExecutionAssertions();

    public HopPipelineExecution(final Result result) {
        this(result, "pipeline");
    }

    public HopPipelineExecution(final Result result, final String description) {
        this.result = result;
        this.description = description;
    }

    public Result result() {
        return result;
    }

    public String logText() {
        return assertions.logText(result);
    }

    public HopPipelineExecution assertSuccess() {
        assertions.assertSuccess(description, result);
        return this;
    }

    public HopPipelineExecution assertFailure() {
        assertions.assertFailure(description, result);
        return this;
    }

    public HopPipelineExecution assertLogContains(final String expected) {
        assertions.assertLogContains(description, result, expected);
        return this;
    }

    public HopPipelineExecution assertThat(final Consumer<HopPipelineExecution> assertions) {
        assertions.accept(this);
        return this;
    }
}
