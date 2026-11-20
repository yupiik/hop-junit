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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.hop.junit.hop.coverage.HopCoverageConfig.ReportFormat;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("Temporarily changes coverage system properties")
class HopCoverageConfigTest {
    private final Map<String, String> previousProperties = new HashMap<>();

    @BeforeEach
    void clearProperties() {
        for (final var property : Set.of(
                "hop.coverage.workDir",
                "hop.coverage.rawDir",
                "hop.coverage.siteDir",
                "hop.coverage.report.format",
                "hop.report.adoc.output",
                "hop.coverage.report.adoc.output",
                "hop.coverage.report.jacoco.output")) {
            previousProperties.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
    }

    @AfterEach
    void restoreProperties() {
        previousProperties.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    @Test
    void defaultsKeepJacocoOptIn() {
        final var config = HopCoverageConfig.load();

        assertEquals(Set.of(ReportFormat.JSON, ReportFormat.HTML, ReportFormat.ADOC), config.reportFormats());
        assertEquals(absolute("target/hop-coverage"), config.workDir());
        assertEquals(absolute("target/hop-coverage/raw"), config.rawDir());
        assertEquals(absolute("target/hop-coverage/report"), config.siteDir());
        assertEquals(
                absolute("target/hop-coverage/report/coverage.adoc"),
                config.adocReport().output());
        assertEquals(
                config.siteDir().resolve("jacoco.xml"), config.jacocoReport().output());
    }

    @Test
    void onlyNewOutputDefaultsFollowSiteDir() {
        System.setProperty("hop.coverage.workDir", "target/custom-work");
        System.setProperty("hop.coverage.siteDir", "target/custom-site/../site");

        final var config = HopCoverageConfig.load();

        assertEquals(absolute("target/custom-work/raw"), config.rawDir());
        assertEquals(absolute("target/site"), config.siteDir());
        assertEquals(absolute("target/site/jacoco.xml"), config.jacocoReport().output());
        assertEquals(
                absolute("target/hop-coverage/report/coverage.adoc"),
                config.adocReport().output());
    }

    @Test
    void workDirDoesNotChangeExistingSiteDefault() {
        System.setProperty("hop.coverage.workDir", "target/custom-work");

        final var config = HopCoverageConfig.load();

        assertEquals(absolute("target/hop-coverage/report"), config.siteDir());
        assertEquals(
                config.siteDir().resolve("jacoco.xml"), config.jacocoReport().output());
    }

    @Test
    void explicitOutputDoesNotEnableJacoco() {
        System.setProperty("hop.coverage.report.jacoco.output", "target/export/../hop.xml");

        final var config = HopCoverageConfig.load();

        assertEquals(absolute("target/hop.xml"), config.jacocoReport().output());
        assertFalse(config.isReportFormatEnabled(ReportFormat.JACOCO));
    }

    @Test
    void parsesJacocoAlongsideExistingFormats() {
        System.setProperty("hop.coverage.report.format", " json, JaCoCo, HTML, adoc, jacoco ");

        assertEquals(
                Set.of(ReportFormat.JSON, ReportFormat.HTML, ReportFormat.ADOC, ReportFormat.JACOCO),
                HopCoverageConfig.load().reportFormats());
    }

    @Test
    void supportsJacocoOnly() {
        System.setProperty("hop.coverage.report.format", "JACOCO");

        assertEquals(Set.of(ReportFormat.JACOCO), HopCoverageConfig.load().reportFormats());
    }

    @Test
    void rejectsBlankExplicitOutput() {
        for (final var value : new String[] {"", " ", "\t\n"}) {
            System.setProperty("hop.coverage.report.jacoco.output", value);

            final var error = assertThrows(IllegalArgumentException.class, HopCoverageConfig::load);

            assertTrue(error.getMessage().contains("hop.coverage.report.jacoco.output"));
        }
    }

    @Test
    void previousConstructorDelegatesWithoutEnablingJacoco() {
        final var loaded = HopCoverageConfig.load();
        final var config = new HopCoverageConfig(
                loaded.enabled(),
                loaded.baseDir(),
                loaded.workDir(),
                loaded.rawDir(),
                loaded.siteDir(),
                loaded.failOnWriteError(),
                loaded.failOnMalformedReport(),
                loaded.requiredCoverage(),
                loaded.requiredCoverageByArtifact(),
                loaded.reportFormats(),
                loaded.htmlReport(),
                loaded.adocReport());

        assertEquals(loaded, config);
        assertFalse(config.isReportFormatEnabled(ReportFormat.JACOCO));
    }

    private Path absolute(final String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }
}
