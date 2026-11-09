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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs an Apache Hop pipeline as part of a JUnit test method.
 *
 * <p>The annotation is processed by {@link HopPipelineExtension} in {@code beforeEach}: it resolves the
 * pipeline file, prepares an isolated Hop working tree, merges the effective execution parameters and
 * injects a {@link HopPipelineFixture} into the test method. Call {@code execute()} on that fixture to
 * run the pipeline and obtain a fluent {@link HopPipelineExecution} you can assert on.
 *
 * <p>Method-level only and not repeatable - declare one pipeline per test method. Domain aliases are
 * built on {@link HopParameters} (see {@link HopParametersSupport}), not on this annotation.
 *
 * @see HopPipelineFixture
 * @see HopPipelineExecution
 * @see HopParametersSupport
 */
@Target(METHOD)
@Retention(RUNTIME)
@ExtendWith(HopPipelineExtension.class)
public @interface HopPipeline {
    /** The pipeline file path, resolved against {@link #projectHome()}. */
    String value();

    /** The Hop run configuration used to build the engine; defaults to the local engine. */
    String runConfiguration() default "local";

    /** The Hop project home the pipeline is resolved against; defaults to {@code src/main/repositories}. */
    String projectHome() default "src/main/repositories";

    /** Per-test execution parameters, merged last (highest precedence) after the class and method batches. */
    HopParameter[] parameters() default {};
}
