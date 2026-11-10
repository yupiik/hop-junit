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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.stream.Collectors;

public record HopCoverageConfig(
        boolean enabled,
        Path baseDir,
        Path workDir,
        Path rawDir,
        Path siteDir,
        boolean failOnWriteError,
        boolean failOnMalformedReport,
        OptionalDouble requiredCoverage,
        Map<String, Double> requiredCoverageByArtifact,
        Set<ReportFormat> reportFormats,
        HtmlReport htmlReport,
        AdocReport adocReport) {
    public static HopCoverageConfig load() {
        final var workDir = path("hop.coverage.workDir", "target/hop-coverage");
        return new HopCoverageConfig(
                bool("hop.coverage.enabled", true),
                path(
                        "hop.coverage.basedir",
                        Path.of(".").toAbsolutePath().normalize().toString()),
                workDir,
                path("hop.coverage.rawDir", workDir.resolve("raw").toString()),
                path("hop.coverage.siteDir", "target/hop-coverage/report"),
                bool("hop.coverage.failOnWriteError", true),
                bool("hop.coverage.failOnMalformedReport", true),
                optionalDouble("hop.coverage.requiredCoverage"),
                scanRequiredCoverageOverrides(),
                parseReportFormats(),
                new HtmlReport(
                        string("hop.coverage.report.html.title", "Hop Coverage Report"),
                        string("hop.coverage.report.html.subtitle", "Apache Hop graph coverage"),
                        string("hop.coverage.report.html.date", null),
                        string(
                                "hop.coverage.report.html.description",
                                "Pipeline and workflow scope coverage collected from JUnit executions.")),
                new AdocReport(
                        path(
                                "hop.report.adoc.output",
                                System.getProperty(
                                        "hop.coverage.report.adoc.output", "target/hop-coverage/report/coverage.adoc")),
                        string("hop.coverage.report.adoc.title", "Couverture de tests Hop"),
                        string(
                                "hop.coverage.report.adoc.description",
                                "Synthèse générée depuis les exécutions JUnit Hop."),
                        bool("hop.coverage.report.adoc.enableMinisiteHopLinking", false),
                        language(
                                "hop.coverage.report.adoc.language",
                                "fr".equalsIgnoreCase(Locale.getDefault().getLanguage()) ? Language.FR : Language.EN)));
    }

    public boolean isReportFormatEnabled(final ReportFormat format) {
        return reportFormats.contains(format);
    }

    public OptionalDouble requiredCoverageFor(final String artifactName) {
        final var specific = requiredCoverageByArtifact.get(artifactName);
        if (specific != null) {
            return OptionalDouble.of(specific);
        }
        return requiredCoverage;
    }

    private static Map<String, Double> scanRequiredCoverageOverrides() {
        final var map = new HashMap<String, Double>();
        for (final var entry : System.getProperties().entrySet()) {
            final var key = String.valueOf(entry.getKey());
            if (key.startsWith("hop.coverage.requiredCoverage.")) {
                map.put(
                        key.substring("hop.coverage.requiredCoverage.".length()),
                        Double.parseDouble(String.valueOf(entry.getValue())));
            }
        }
        return Map.copyOf(map);
    }

    private static boolean bool(final String name, final boolean defaultValue) {
        return Boolean.parseBoolean(System.getProperty(name, Boolean.toString(defaultValue)));
    }

    private static Path path(final String name, final String defaultValue) {
        return Path.of(System.getProperty(name, defaultValue)).toAbsolutePath().normalize();
    }

    private static String string(final String name, final String defaultValue) {
        return System.getProperty(name, defaultValue);
    }

    private static OptionalDouble optionalDouble(final String name) {
        final var value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Double.parseDouble(value));
    }

    private static Language language(final String name, final Language fallback) {
        final var value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "FR" -> Language.FR;
            case "EN" -> Language.EN;
            default -> throw new IllegalArgumentException("Unsupported language '" + value + "' for property " + name);
        };
    }

    private static Set<ReportFormat> parseReportFormats() {
        return Arrays.stream(System.getProperty("hop.coverage.report.format", "JSON,HTML,ADOC")
                        .split(","))
                .map(String::strip)
                .filter(it -> !it.isEmpty())
                .map(it -> ReportFormat.valueOf(it.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }

    public enum ReportFormat {
        JSON,
        HTML,
        ADOC
    }

    public enum Language {
        FR,
        EN
    }

    public record HtmlReport(String title, String subtitle, String date, String description) {}

    public record AdocReport(
            Path output, String title, String description, boolean enableMinisiteHopLinking, Language language) {}
}
