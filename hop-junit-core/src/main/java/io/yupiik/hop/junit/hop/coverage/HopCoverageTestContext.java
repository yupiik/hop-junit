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
package io.yupiik.hop.junit.hop.coverage;

import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Builds the {@link HopCoverageModels.Test} descriptor for the current JUnit test. */
public class HopCoverageTestContext {
    public HopCoverageTestContext() {
        // no-op
    }

    public HopCoverageModels.Test from(final ExtensionContext context) {
        final var method = context.getTestMethod();
        final var type = context.getTestClass();
        return new HopCoverageModels.Test(
                type.map(Class::getName).orElse(null),
                method.map(Method::getName).orElse(null),
                context.getDisplayName(),
                context.getUniqueId());
    }
}
