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
package io.yupiik.hop.junit.hop.coverage;

import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Execution;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.JvmReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Ratio;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM-wide singleton that accumulates Hop coverage executions and exposes the coverage configuration
 * and helpers. Because coverage is aggregated across every test in the JVM, the state is global by
 * design; {@link #INSTANCE} is the single shared instance and all its methods are instance methods.
 */
public final class HopCoverageRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(HopCoverageRuntime.class);
    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter JSON_DATE = DateTimeFormatter.ISO_INSTANT;
    private static final Clock CLOCK = Clock.systemUTC();

    public static final HopCoverageRuntime INSTANCE = new HopCoverageRuntime();
    public static final int SCHEMA_VERSION = 1;

    private final HopCoverageConfig config = HopCoverageConfig.load();
    private final long processId = processId();
    private final Instant jvmStartedAt = Instant.now(CLOCK);
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentLinkedQueue<Execution> executions = new ConcurrentLinkedQueue<>();

    private HopCoverageRuntime() {
        if (config.enabled()) {
            new PipelineCoverageExtensionPoint().register();
        }
    }

    /** The shared JVM coverage config. */
    public HopCoverageConfig config() {
        return config;
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    public String nextExecutionId() {
        return "%06d".formatted(sequence.incrementAndGet());
    }

    public String now() {
        return JSON_DATE.format(Instant.now(CLOCK));
    }

    public void addExecution(final Execution execution) {
        if (config.enabled() && execution != null) {
            executions.add(execution);
        }
    }

    public List<Execution> executions() {
        return executions.stream().sorted(Comparator.comparing(Execution::id)).toList();
    }

    public JvmReport jvmReport() {
        return new JvmReport(SCHEMA_VERSION, processId, JSON_DATE.format(jvmStartedAt), now(), executions());
    }

    public Path rawReportPath() {
        return config.rawDir().resolve("run-" + processId + '-' + FILE_DATE.format(jvmStartedAt) + ".json");
    }

    public String relativePath(final Path path) {
        final var absolute = path.toAbsolutePath().normalize();
        final var base = config.baseDir().toAbsolutePath().normalize();
        final Path relative;
        if (absolute.startsWith(base)) {
            relative = base.relativize(absolute);
        } else {
            LOGGER.warn("Path '{}' is not under hop.coverage.basedir '{}'.", absolute, base);
            relative = absolute;
        }
        return relative.toString().replace('\\', '/');
    }

    public Ratio ratio(final long covered, final long total) {
        return new Ratio(covered, total, total == 0 ? 1d : ((double) covered) / total);
    }

    public <T> List<T> sortedCopy(final Iterable<T> values, final Comparator<T> comparator) {
        final var list = new ArrayList<T>();
        values.forEach(list::add);
        list.sort(comparator);
        return List.copyOf(list);
    }

    private static long processId() {
        try {
            return ProcessHandle.current().pid();
        } catch (final RuntimeException ignored) {
            final var name = ManagementFactory.getRuntimeMXBean().getName();
            final var at = name.indexOf('@');
            if (at > 0) {
                return Long.parseLong(name.substring(0, at));
            }
            return Math.abs(name.hashCode());
        }
    }
}
