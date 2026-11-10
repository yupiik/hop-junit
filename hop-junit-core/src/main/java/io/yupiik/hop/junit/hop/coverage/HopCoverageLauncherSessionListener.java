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
package io.yupiik.hop.junit.hop.coverage;

import java.io.IOException;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit platform {@link LauncherSessionListener} that triggers the coverage report generation once the
 * test launcher session closes, when coverage is enabled.
 */
public final class HopCoverageLauncherSessionListener implements LauncherSessionListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(HopCoverageLauncherSessionListener.class);

    @Override
    public void launcherSessionClosed(final LauncherSession session) {
        if (!HopCoverageRuntime.INSTANCE.isEnabled()) {
            return;
        }
        try {
            writeJvmReport();
        } catch (final RuntimeException error) {
            LOGGER.error("Unable to write Hop coverage JVM report.", error);
            if (HopCoverageRuntime.INSTANCE.config().failOnWriteError()) {
                throw error;
            }
        }
        try {
            new HopCoveragePostProcessor(HopCoverageRuntime.INSTANCE.config()).run();
        } catch (final RuntimeException | IOException error) {
            LOGGER.error("Unable to post-process Hop coverage reports.", error);
            if (HopCoverageRuntime.INSTANCE.config().failOnWriteError()) {
                if (error instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException(error);
            }
        }
    }

    private void writeJvmReport() {
        try {
            HopCoverageJson.INSTANCE.write(
                    HopCoverageRuntime.INSTANCE.jvmReport(), HopCoverageRuntime.INSTANCE.rawReportPath());
        } catch (final IOException error) {
            throw new IllegalStateException(error);
        }
    }
}
