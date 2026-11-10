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

import io.yupiik.hop.junit.hop.coverage.HopCoverageTestContext;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class HopPipelineExtension implements BeforeEachCallback, ParameterResolver {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(HopPipelineExtension.class);

    private final HopTestEnvironment environment = new HopTestEnvironment();
    private final HopParametersSupport parameters = new HopParametersSupport();
    private final HopTestWorkDirectories workDirectories = new HopTestWorkDirectories();
    private final HopCoverageTestContext coverageTestContext = new HopCoverageTestContext();

    public HopPipelineExtension() {
        environment.init();
    }

    @Override
    public void beforeEach(final ExtensionContext context) throws Exception {
        final var annotation = context.getRequiredTestMethod().getAnnotation(HopPipeline.class);
        if (annotation == null) {
            return;
        }
        final var projectHome =
                Path.of(annotation.projectHome()).toAbsolutePath().normalize();
        final var pipelinePath =
                projectHome.resolve(annotation.value()).toAbsolutePath().normalize();
        final var workDirectory = workDirectories.prepare(context);
        final var mergedParameters = this.parameters.parametersFor(
                context.getRequiredTestClass(), context.getRequiredTestMethod(), annotation.parameters());
        context.getStore(NAMESPACE)
                .put(
                        context.getUniqueId(),
                        new HopPipelineFixture(
                                projectHome,
                                pipelinePath,
                                workDirectory,
                                annotation.runConfiguration(),
                                coverageTestContext.from(context),
                                mergedParameters));
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == HopPipelineFixture.class;
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        final var fixture =
                extensionContext.getStore(NAMESPACE).get(extensionContext.getUniqueId(), HopPipelineFixture.class);
        if (fixture == null) {
            throw new ParameterResolutionException("No @HopPipeline fixture for " + parameterContext.getParameter());
        }
        return fixture;
    }
}
