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
package io.yupiik.hop.junit.database.oracle;

import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedSuccessfully;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * Proves {@code seedDataset} and {@code verifyDataset} actually run, using the JUnit Platform
 * Test Kit ({@code EngineTestKit}) as recommended by the JUnit docs for testing extensions:
 * the fixture whose expected dataset matches must finish successfully, the one whose expected
 * dataset mismatches must FAIL. If {@code verifyDataset} silently did not run (the risk with an
 * {@code afterEach} hook), the mismatching fixture would stay green and this test would fail.
 *
 * <p>This test shares the single Oracle Testcontainers container with the rest of the module
 * (the extension caches it in a static field), so no extra container is started.
 */
class OracleVerifyTest {

    @Test
    void verifyDatasetIsEffectivelyExecuted() {
        EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(OracleVerifyFixtures.class))
                .execute()
                .testEvents()
                .assertThatEvents()
                // matching expected dataset + seeded state passes
                .haveExactly(1, event(test("verifyRunsAndPasses"), finishedSuccessfully()))
                // mismatching expected dataset must fail: proof verifyDataset ran
                .haveExactly(
                        1,
                        event(
                                test("verifyRunsAndFails"),
                                finishedWithFailure(message(it -> it.contains("DIFFERENT")))));
    }
}
