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

import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.AggregateReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.ArtifactReport;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Edge;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Node;
import io.yupiik.hop.junit.hop.coverage.HopCoverageModels.Ratio;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders a single self-contained, modern dashboard (inline CSS + vanilla JS, no external assets):
 * a sticky toolbar, an overall coverage gauge, per-metric stat tiles, and a sortable/filterable
 * artifact table with inline drill-down for uncovered nodes/edges and test provenance.
 */
public final class HopCoverageHtmlRenderer {
    private static final double DEFAULT_REQUIRED_COVERAGE = 80d;

    private final HopCoverageConfig config;

    public HopCoverageHtmlRenderer(final HopCoverageConfig config) {
        this.config = config;
    }

    public void write(final AggregateReport report, final Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, render(report));
    }

    private String render(final AggregateReport report) {
        final var html = new StringBuilder(96_000);
        final var metadata = config.htmlReport();
        final var title =
                metadata.title() == null || metadata.title().isBlank() ? "Hop Coverage Report" : metadata.title();
        final var subtitle = metadata.subtitle() == null || metadata.subtitle().isBlank()
                ? "Apache Hop graph coverage"
                : metadata.subtitle();
        final var date = metadata.date() == null || metadata.date().isBlank() ? report.generatedAt() : metadata.date();
        final var artifacts = report.artifacts().stream()
                .sorted(Comparator.comparing(
                        (ArtifactReport it) -> it.artifact().path()))
                .toList();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"color-scheme\" content=\"light dark\">\n")
                .append("<title>")
                .append(escape(title))
                .append("</title>\n")
                .append("<style>\n")
                .append(styles())
                .append("</style>\n")
                .append("</head>\n<body>\n");

        html.append("<header class=\"topbar\"><div class=\"topbar-inner\">")
                .append("<div class=\"brand\"><span class=\"brand-mark\">H</span><span class=\"brand-copy\">")
                .append("<strong>")
                .append(escape(title))
                .append("</strong>")
                .append("<small>")
                .append(escape(subtitle))
                .append("</small></span></div>")
                .append("<div class=\"topbar-meta\"><span class=\"chip\">")
                .append(artifacts.size())
                .append(" artifacts</span><span class=\"chip\">")
                .append(report.executions().size())
                .append(" executions</span><span class=\"chip muted\">Generated ")
                .append(escape(date))
                .append("</span></div>")
                .append(
                        "<button type=\"button\" id=\"theme-toggle\" class=\"icon-btn\" aria-label=\"Toggle color theme\"></button>")
                .append("</div></header>\n");

        html.append("<main class=\"shell\">\n");

        // ---- hero: health gauge + summary + status banner ----
        html.append("<section class=\"hero\">\n")
                .append("<div class=\"gauge-card\">")
                .append(
                        "<svg class=\"gauge\" viewBox=\"0 0 120 120\" role=\"img\" aria-label=\"Overall graph coverage ")
                .append(percent(report.summary().coverage()))
                .append("\">")
                .append("<circle class=\"gauge-track\" cx=\"60\" cy=\"60\" r=\"52\"></circle>")
                .append("<circle class=\"gauge-bar ")
                .append(status(report.summary().coverage()))
                .append("\" data-ratio=\"")
                .append(width(report.summary().coverage()))
                .append("\" cx=\"60\" cy=\"60\" r=\"52\"></circle>")
                .append("</svg><div class=\"gauge-center\"><span class=\"gauge-value\">")
                .append(percent(report.summary().coverage()))
                .append("</span><span class=\"gauge-label\">coverage</span></div>")
                .append("<p class=\"gauge-caption\">")
                .append(report.summary().coverage().covered())
                .append(" of ")
                .append(report.summary().coverage().total())
                .append(" edges covered</p></div>\n")
                .append("<div class=\"hero-copy\"><p class=\"eyebrow\">Apache Hop</p>")
                .append("<h1>")
                .append(escape(title))
                .append("</h1>")
                .append("</div>\n")
                .append("<div class=\"stat-tiles\">");
        statTile(html, "Nodes", report.summary().nodes());
        statTile(html, "Edges", report.summary().edges());
        statTile(html, "Branches", report.summary().branches());
        html.append("</div>\n</section>\n");

        // ---- status banner (isolated when hero wraps) ----
        final var banner = statusBannerMarkup(report);
        if (!banner.isEmpty()) {
            html.append("<section class=\"banner\">").append(banner).append("</section>\n");
        }

        // ---- toolbar + filter ----
        html.append("<section class=\"toolbar\" aria-label=\"Filters\"><div class=\"toolbar-top\">")
                .append(
                        "<input id=\"filter\" type=\"search\" placeholder=\"Search name or path...\" aria-label=\"Search artifacts\" oninput=\"applyFilters()\">")
                .append("<div class=\"chips\" role=\"group\" aria-label=\"Artifact type\">")
                .append(
                        "<button type=\"button\" class=\"chip kind on\" data-kind=\"\" onclick=\"toggleKind(this)\">All</button>")
                .append(
                        "<button type=\"button\" class=\"chip kind\" data-kind=\"pipeline\" onclick=\"toggleKind(this)\">Pipelines</button>")
                .append(
                        "<button type=\"button\" class=\"chip kind\" data-kind=\"workflow\" onclick=\"toggleKind(this)\">Workflows</button></div>")
                .append("<div class=\"chips\" role=\"group\" aria-label=\"Status\">")
                .append(
                        "<button type=\"button\" class=\"chip state on\" data-state=\"\" onclick=\"toggleState(this)\">All states</button>")
                .append(
                        "<button type=\"button\" class=\"chip state\" data-state=\"ok\" onclick=\"toggleState(this)\">OK</button>")
                .append(
                        "<button type=\"button\" class=\"chip state\" data-state=\"warn\" onclick=\"toggleState(this)\">Below</button></div>")
                .append("<span id=\"counts\" class=\"counts\"></span></div>\n")
                .append("</section>\n");

        // ---- artifact table ----
        html.append(
                        "<section class=\"artifacts\"><div class=\"table-scroll\"><table class=\"artifact-table\" id=\"artifact-table\">\n")
                .append(
                        "<thead><tr><th data-key=\"artifact\" aria-sort=\"none\" onclick=\"sortBy(this)\">Artifact</th>")
                .append("<th data-key=\"kind\" aria-sort=\"none\" onclick=\"sortBy(this)\">Type</th>")
                .append(
                        "<th class=\"num\" data-key=\"nodes\" aria-sort=\"none\" onclick=\"sortBy(this)\">Nodes <span class=\"ratio-mini\">");
        ratioMini(html, report.summary().nodes());
        html.append("</span></th>")
                .append(
                        "<th class=\"num\" data-key=\"edges\" aria-sort=\"none\" onclick=\"sortBy(this)\">Edges <span class=\"ratio-mini\">");
        ratioMini(html, report.summary().edges());
        html.append("</span></th>")
                .append(
                        "<th class=\"num\" data-key=\"branches\" aria-sort=\"none\" onclick=\"sortBy(this)\">Branches <span class=\"ratio-mini\">");
        ratioMini(html, report.summary().branches());
        html.append("</span></th>")
                .append(
                        "<th class=\"num\" data-key=\"coverage\" aria-sort=\"none\" onclick=\"sortBy(this)\">Coverage</th>")
                .append(
                        "<th class=\"num\" data-key=\"threshold\" aria-sort=\"none\" onclick=\"sortBy(this)\">Required</th>")
                .append("<th data-key=\"state\" aria-sort=\"none\" onclick=\"sortBy(this)\">Status</th></tr></thead>\n")
                .append("<tbody id=\"artifact-body\">\n");
        artifacts.forEach(artifact -> artifactRow(html, artifact));
        html.append("</tbody></table></div>\n")
                .append(
                        "<p id=\"no-results\" class=\"empty\" hidden>No artifacts match the current filters.</p></section>\n")
                .append("</main>\n");

        html.append("<footer class=\"foot\">Generated on ")
                .append(escape(date))
                .append(" &middot; Coverage is the share of enabled edges executed by the JUnit suite.")
                .append("</footer>\n")
                .append("<script>\n")
                .append(script())
                .append("</script>\n")
                .append("</body>\n</html>\n");
        return html.toString();
    }

    private void ratioMini(final StringBuilder html, final Ratio ratio) {
        html.append(ratio.covered()).append('/').append(ratio.total());
    }

    private void statTile(final StringBuilder html, final String label, final Ratio ratio) {
        html.append("<article class=\"stat ")
                .append(status(ratio))
                .append("\"><span class=\"stat-label\">")
                .append(escape(label))
                .append("</span><strong>")
                .append(percent(ratio))
                .append("</strong>")
                .append("<div class=\"bar\"><i style=\"width:")
                .append(width(ratio))
                .append("%\"></i></div>")
                .append("<small>")
                .append(ratio.covered())
                .append(" covered / ")
                .append(ratio.total())
                .append(" total</small></article>\n");
    }

    private void artifactRow(final StringBuilder html, final ArtifactReport artifact) {
        final var stateClass = artifactStatusClass(artifact);
        final var required = artifact.requiredCoverage();
        final var requiredText = required == null ? "—" : String.format(Locale.ROOT, "%.2f%%", required);
        final var kind = artifact.artifact().kind() == null
                ? ""
                : artifact.artifact().kind().toLowerCase(Locale.ROOT);
        html.append("<tr class=\"artifact-row ")
                .append(stateClass)
                .append("\" data-search=\"")
                .append(attribute((artifact.artifact().kind()
                                + ' '
                                + artifact.artifact().name()
                                + ' '
                                + artifact.artifact().path())
                        .toLowerCase(Locale.ROOT)))
                .append("\" data-kind=\"")
                .append(attribute(kind))
                .append("\" data-state=\"")
                .append(stateClass)
                .append("\" data-nodes=\"")
                .append(percentNum(artifact.summary().nodes()))
                .append("\" data-edges=\"")
                .append(percentNum(artifact.summary().edges()))
                .append("\" data-branches=\"")
                .append(percentNum(artifact.summary().branches()))
                .append("\" data-coverage=\"")
                .append(percentNum(artifact.summary().coverage()))
                .append("\" data-threshold=\"")
                .append(required == null ? -1d : required)
                .append("\">")
                .append(
                        "<td class=\"cell-artifact-row\" onclick=\"toggleRow(this)\"><span class=\"chev\"></span><div class=\"artifact-id\"><strong>")
                .append(escape(artifact.artifact().name()))
                .append("</strong><small>")
                .append(escape(artifact.artifact().path()))
                .append("</small></div></td>")
                .append("<td><span class=\"badge\">")
                .append(escape(artifact.artifact().kind()))
                .append("</span></td>")
                .append("<td class=\"num\"><span class=\"metric-inline\">")
                .append(percent(artifact.summary().nodes()))
                .append("</span></td>")
                .append("<td class=\"num\"><span class=\"metric-inline\">")
                .append(percent(artifact.summary().edges()))
                .append("</span></td>")
                .append("<td class=\"num\"><span class=\"metric-inline\">")
                .append(percent(artifact.summary().branches()))
                .append("</span></td>")
                .append("<td class=\"num\"><strong class=\"coverage\">")
                .append(percent(artifact.summary().coverage()))
                .append("</strong></td>")
                .append("<td class=\"num\">")
                .append(requiredText)
                .append("</td>")
                .append("<td><span class=\"state-pill ")
                .append(stateClass)
                .append("\">")
                .append(stateText(stateClass))
                .append("</span></td></tr>\n")
                .append("<tr class=\"detail-row\"><td colspan=\"8\"><div class=\"detail\">\n")
                .append("<div class=\"detail-grid\">");
        metric(html, "Nodes", artifact.summary().nodes());
        metric(html, "Edges", artifact.summary().edges());
        metric(html, "Branches", artifact.summary().branches());
        html.append("</div>");
        if (required != null) {
            html.append("<p class=\"threshold-detail\"><strong>Required coverage:</strong> ")
                    .append(String.format(Locale.ROOT, "%.2f%%", required))
                    .append(overridden(artifact) ? " (overridden)" : " (global default)")
                    .append("</p>");
        }
        html.append("<div class=\"drill-grid\">");
        uncoveredNodes(html, artifact.nodes());
        uncoveredEdges(html, artifact.edges());
        html.append("</div>");
        if (!artifact.executionIds().isEmpty()) {
            html.append("<p class=\"provenance\"><strong>Exercised by:</strong> ");
            artifact.executionIds().stream()
                    .sorted()
                    .forEach(id -> html.append("<code>").append(escape(id)).append("</code> "));
            html.append("</p>");
        }
        html.append("</div></td></tr>\n");
    }

    private void uncoveredNodes(final StringBuilder html, final List<Node> nodes) {
        final var uncovered =
                nodes.stream().filter(node -> node.enabled() && !node.covered()).toList();
        html.append("<section class=\"drill-col\"><h3>Uncovered nodes</h3>");
        if (uncovered.isEmpty()) {
            html.append("<p class=\"ok-note\">Fully covered</p>");
        } else {
            html.append("<ul class=\"tag-list\">");
            uncovered.stream()
                    .map(Node::name)
                    .sorted()
                    .forEach(name ->
                            html.append("<li><code>").append(escape(name)).append("</code></li>"));
            html.append("</ul>");
        }
        html.append("</section>");
    }

    private void uncoveredEdges(final StringBuilder html, final List<Edge> edges) {
        final var uncovered =
                edges.stream().filter(edge -> edge.enabled() && !edge.covered()).toList();
        html.append("<section class=\"drill-col\"><h3>Uncovered edges</h3>");
        if (uncovered.isEmpty()) {
            html.append("<p class=\"ok-note\">Fully covered</p>");
        } else {
            html.append("<ul class=\"edge-list\">");
            uncovered.stream()
                    .sorted(Comparator.comparing(Edge::from).thenComparing(Edge::to))
                    .forEach(edge -> html.append("<li><code>")
                            .append(escape(edge.from()))
                            .append(" &rarr; ")
                            .append(escape(edge.to()))
                            .append("</code>")
                            .append(kind(edge))
                            .append("</li>"));
            html.append("</ul>");
        }
        html.append("</section>");
    }

    private void metric(final StringBuilder html, final String label, final Ratio ratio) {
        html.append("<div class=\"metric\"><span>")
                .append(escape(label))
                .append("</span>")
                .append("<strong>")
                .append(percent(ratio))
                .append("</strong>")
                .append("<div class=\"bar\"><i style=\"width:")
                .append(width(ratio))
                .append("%\"></i></div>")
                .append("<small>")
                .append(ratio.covered())
                .append("/")
                .append(ratio.total())
                .append("</small></div>");
    }

    private String statusBannerMarkup(final AggregateReport report) {
        final var below = report.artifacts().stream()
                .filter(this::isBelowThreshold)
                .sorted(Comparator.comparingDouble(
                        (ArtifactReport a) -> a.summary().coverage().ratio()))
                .toList();
        if (below.isEmpty()) {
            return "<div class=\"banner-inner ok\"><span class=\"dot\"></span><strong>All artifacts</strong> meet their required coverage threshold.</div>";
        }
        final var first = below.getFirst();
        final var name = first.artifact().name();
        final var gap = effectiveCoverage(first) - first.summary().coverage().ratio();
        return "<div class=\"banner-inner bad\"><span class=\"dot\"></span><strong>" + below.size()
                + " artifact" + (below.size() == 1 ? "" : "s") + " below threshold</strong> &mdash; "
                + escape(name) + " is "
                + String.format(Locale.ROOT, "%.2f%%", Math.max(0, gap * 100))
                + (below.size() > 1 ? " and " + (below.size() - 1) + " more" : "") + ".</div>";
    }

    private boolean isBelowThreshold(final ArtifactReport artifact) {
        return artifact.summary().coverage().ratio() < effectiveCoverage(artifact) - 1e-9;
    }

    private String artifactStatusClass(final ArtifactReport artifact) {
        return artifact.summary().coverage().ratio() >= effectiveCoverage(artifact) ? "ok" : "warn";
    }

    private String stateText(final String stateClass) {
        return "ok".equals(stateClass) ? "OK" : "Below";
    }

    private double effectiveCoverage(final ArtifactReport artifact) {
        final var required = artifact.requiredCoverage();
        return required == null ? DEFAULT_REQUIRED_COVERAGE / 100d : required / 100d;
    }

    private boolean overridden(final ArtifactReport artifact) {
        final var required = artifact.requiredCoverage();
        if (required == null) {
            return false;
        }
        final var global = config.requiredCoverage();
        if (global.isEmpty()) {
            return required != DEFAULT_REQUIRED_COVERAGE;
        }
        return required != global.getAsDouble();
    }

    private String kind(final Edge edge) {
        return edge.kind() == null || edge.kind().isBlank() ? "" : " (" + edge.kind() + ")";
    }

    private String percent(final Ratio ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio.ratio() * 100);
    }

    private double percentNum(final Ratio ratio) {
        return ratio.ratio() * 100;
    }

    private double width(final Ratio ratio) {
        return Math.clamp(ratio.ratio() * 100, 0, 100);
    }

    private String status(final Ratio ratio) {
        if (ratio.ratio() >= 0.9) {
            return "good";
        }
        if (ratio.ratio() >= 0.6) {
            return "warn";
        }
        return "bad";
    }

    private String escape(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String attribute(final String value) {
        return escape(value).replace("\n", " ").replace("\r", " ");
    }

    private String script() {
        return """
                (function () {
                  'use strict';
                  const body = document.body;
                  const TOGGLE = document.getElementById('theme-toggle');
                  const META = document.querySelector('meta[name=color-scheme]');
                  function setTheme(mode) {
                    const dark = mode === 'dark' || (mode === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
                    body.dataset.theme = dark ? 'dark' : 'light';
                    if (TOGGLE) TOGGLE.textContent = dark ? '\\u2600\\uFE0F' : '\\uD83C\\uDF19';
                    if (META) META.content = dark ? 'dark' : 'light';
                    try { localStorage.setItem('hop-theme', mode); } catch (e) {}
                  }
                  const saved = (function () { try { return localStorage.getItem('hop-theme'); } catch (e) { return null; } })();
                  const initial = saved || 'auto';
                  setTheme(initial);
                  if (TOGGLE) TOGGLE.addEventListener('click', function () {
                    const order = ['auto', 'light', 'dark']; const cur = order.indexOf(initial);
                    const next = order[(cur + 1) % order.length]; initial = next; setTheme(next);
                  });
                  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () { if (initial === 'auto') setTheme('auto'); });

                  const GAUGE = document.querySelector('.gauge-bar');
                  if (GAUGE) {
                    const r = 52, c = 2 * Math.PI * r;
                    GAUGE.style.strokeDasharray = c + ' ' + c;
                    const target = Math.max(0, Math.min(100, parseFloat(GAUGE.getAttribute('data-ratio') || '0')));
                    GAUGE.style.strokeDashoffset = c;
                    requestAnimationFrame(function () { requestAnimationFrame(function () { GAUGE.style.strokeDashoffset = c - (target / 100) * c; }); });
                  }

                  window.applyFilters = function () {
                    const q = (document.getElementById('filter').value || '').toLowerCase().trim();
                    const kind = document.querySelector('.chip.kind.on').dataset.kind;
                    const state = document.querySelector('.chip.state.on').dataset.state;
                    let visible = 0;
                    document.querySelectorAll('.artifact-row').forEach(function (row) {
                      let show = true;
                      if (q && !row.dataset.search.includes(q)) show = false;
                      if (kind && row.dataset.kind !== kind) show = false;
                      if (state && row.dataset.state !== state) show = false;
                      row.hidden = !show;
                      const detail = row.nextElementSibling;
                      if (detail && detail.classList.contains('detail-row')) detail.hidden = !show || !row.classList.contains('open');
                      if (show) visible++;
                    });
                    const empty = document.getElementById('no-results');
                    if (empty) empty.hidden = visible !== 0;
                    const counts = document.getElementById('counts');
                    if (counts) counts.textContent = visible + ' of ' + document.querySelectorAll('.artifact-row').length;
                  };
                  window.toggleKind = function (btn) {
                    document.querySelectorAll('.chip.kind').forEach(function (b) { b.classList.remove('on'); });
                    btn.classList.add('on'); applyFilters();
                  };
                  window.toggleState = function (btn) {
                    document.querySelectorAll('.chip.state').forEach(function (b) { b.classList.remove('on'); });
                    btn.classList.add('on'); applyFilters();
                  };
                  window.toggleRow = function (cell) {
                    const row = cell.parentElement; row.classList.toggle('open');
                    const detail = row.nextElementSibling;
                    if (detail && detail.classList.contains('detail-row')) detail.hidden = !row.classList.contains('open');
                  };
                  window.sortBy = function (th) {
                    const key = th.dataset.key;
                    let dir = th.getAttribute('aria-sort') === 'ascending' ? -1 : 1;
                    const tbody = document.getElementById('artifact-body');
                    const rows = Array.prototype.slice.call(tbody.querySelectorAll('.artifact-row'));
                    rows.sort(function (a, b) {
                      if (key === 'artifact') return a.dataset.search.localeCompare(b.dataset.search) * dir;
                      if (key === 'kind') return a.dataset.kind.localeCompare(b.dataset.kind) * dir;
                      if (key === 'state') return a.dataset.state.localeCompare(b.dataset.state) * dir;
                      return (parseFloat(a.dataset[key] || 0) - parseFloat(b.dataset[key] || 0)) * dir;
                    });
                    rows.forEach(function (row) {
                      tbody.insertBefore(row, row.nextElementSibling);
                      const detail = row.nextElementSibling;
                      if (detail && detail.classList.contains('detail-row')) tbody.insertBefore(detail, detail.nextElementSibling);
                    });
                    tbody.querySelectorAll('th').forEach(function (h) { h.setAttribute('aria-sort', 'none'); });
                    th.setAttribute('aria-sort', dir === 1 ? 'ascending' : 'descending');
                    applyFilters();
                  };
                  applyFilters();
                })();
                """;
    }

    private String styles() {
        return """
                :root { color-scheme: light dark; --bg: #f4f6fb; --panel: rgba(255,255,255,.9); --panel-solid: #ffffff; --ink: #101828; --muted: #667085; --soft: #475467; --line: #eaecf0; --brand: #4f46e5; --brand2: #06b6d4; --good: #16a34a; --warn: #d97706; --bad: #dc2626; --shadow: 0 18px 50px rgba(15,23,42,.10); --ring: rgba(79,70,229,.35); }
                body[data-theme="dark"] { color-scheme: dark; --bg: #0b1020; --panel: rgba(22,28,46,.92); --panel-solid: #161c2e; --ink: #ecf1fa; --muted: #9aa5b6; --soft: #cdd5e1; --line: #27304a; --shadow: 0 18px 50px rgba(0,0,0,.45); --ring: rgba(99,102,241,.4); }
                * { box-sizing: border-box; }
                html { scroll-behavior: smooth; }
                body { margin: 0; min-height: 100vh; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; color: var(--ink); background: radial-gradient(circle at top left, rgba(79,70,229,.16), transparent 30rem), radial-gradient(circle at top right, rgba(6,182,212,.14), transparent 26rem), var(--bg); transition: background .3s ease, color .3s ease; }
                .shell { width: min(1180px, calc(100% - 32px)); margin: 0 auto; padding: 28px 0 28px; }
                button { font: inherit; cursor: pointer; }
                :focus-visible { outline: 3px solid var(--ring); outline-offset: 2px; border-radius: 6px; }

                /* top bar */
                .topbar { position: sticky; top: 0; z-index: 20; background: color-mix(in srgb, var(--panel-solid) 82%, transparent); backdrop-filter: blur(14px); border-bottom: 1px solid var(--line); }
                .topbar-inner { width: min(1180px, calc(100% - 32px)); margin: 0 auto; display: flex; align-items: center; gap: 18px; padding: 14px 0; }
                .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
                .brand-mark { width: 40px; height: 40px; display: grid; place-items: center; border-radius: 12px; font-weight: 900; color: #fff; background: linear-gradient(135deg, var(--brand), var(--brand2)); }
                .brand-copy { display: grid; line-height: 1.2; min-width: 0; }
                .brand-copy strong { font-size: 15px; letter-spacing: -.02em; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .brand-copy small { color: var(--muted); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
                .topbar-meta { display: flex; gap: 8px; margin-left: auto; flex-wrap: wrap; }
                .chip { border: 1px solid var(--line); background: var(--panel); color: var(--soft); border-radius: 999px; padding: 6px 11px; font-size: 12px; font-weight: 700; }
                .chip.muted { opacity: .75; }
                .icon-btn { width: 40px; height: 40px; border-radius: 12px; border: 1px solid var(--line); background: var(--panel); color: var(--ink); font-size: 18px; display: grid; place-items: center; }
                .icon-btn:hover { border-color: var(--brand); }

                /* hero */
                .hero { display: grid; grid-template-columns: 300px minmax(0, 1fr) minmax(240px, 320px); gap: 18px; align-items: stretch; margin-top: 20px; }
                .gauge-card, .hero-copy, .stat, .toolbar { background: var(--panel); border: 1px solid var(--line); border-radius: 22px; box-shadow: var(--shadow); }
                .gauge-card { position: relative; display: grid; place-items: center; padding: 24px; text-align: center; }
                .gauge { width: 132px; height: 132px; transform: rotate(-90deg); }
                .gauge-track { fill: none; stroke: var(--line); stroke-width: 11; }
                .gauge-bar { fill: none; stroke-width: 11; stroke-linecap: round; transition: stroke-dashoffset .7s cubic-bezier(.4,0,.2,1); }
                .gauge-bar.good { stroke: var(--good); } .gauge-bar.warn { stroke: var(--warn); } .gauge-bar.bad { stroke: var(--bad); }
                .gauge-center { position: absolute; top: 50%; left: 50%; transform: translate(-50%,-34%); text-align: center; }
                .gauge-value { display: block; font-size: 26px; font-weight: 900; letter-spacing: -.04em; }
                .gauge-label { font-size: 11px; text-transform: uppercase; letter-spacing: .14em; color: var(--muted); font-weight: 800; }
                .gauge-caption { margin: 10px 0 0; font-size: 12px; color: var(--muted); }
                .hero-copy { padding: 26px 28px; display: flex; flex-direction: column; justify-content: center; }
                .eyebrow { margin: 0 0 8px; color: var(--brand); font-size: 11px; text-transform: uppercase; letter-spacing: .18em; font-weight: 800; }
                h1 { margin: 0; font-size: clamp(26px, 4.5vw, 46px); line-height: .98; letter-spacing: -.05em; }
                .stat-tiles { display: grid; gap: 14px; align-content: start; }
                .stat { padding: 18px; display: grid; gap: 6px; }
                .stat-label { color: var(--muted); font-size: 12px; font-weight: 800; text-transform: uppercase; letter-spacing: .1em; }
                .stat strong { font-size: 26px; letter-spacing: -.04em; }
                .stat small { color: var(--muted); }
                .bar { height: 9px; overflow: hidden; border-radius: 999px; background: var(--line); }
                .bar i { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--brand), var(--brand2)); transition: width .6s ease; }
                .stat.good .bar i { background: linear-gradient(90deg, #22c55e, var(--good)); }
                .stat.warn .bar i { background: linear-gradient(90deg, #f59e0b, var(--warn)); }
                .stat.bad .bar i { background: linear-gradient(90deg, #ef4444, var(--bad)); }

                /* status banner */
                .banner { margin-top: 16px; }
                .banner-inner { display: flex; align-items: center; gap: 12px; padding: 14px 18px; border-radius: 14px; border: 1px solid; background: var(--panel); font-size: 14px; }
                .banner-inner.ok { border-color: color-mix(in srgb, var(--good) 45%, transparent); color: var(--soft); }
                .banner-inner.bad { border-color: color-mix(in srgb, var(--bad) 45%, transparent); color: var(--soft); }
                .banner-inner .dot { width: 12px; height: 12px; border-radius: 50%; flex: none; }
                .banner-inner.ok .dot { background: var(--good); box-shadow: 0 0 0 5px color-mix(in srgb, var(--good) 20%, transparent); }
                .banner-inner.bad .dot { background: var(--bad); box-shadow: 0 0 0 5px color-mix(in srgb, var(--bad) 20%, transparent); }

                /* toolbar */
                .toolbar { position: sticky; top: 70px; z-index: 10; margin: 20px 0 14px; padding: 14px; }
                .toolbar-top { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
                .toolbar input { flex: 1 1 240px; min-width: 180px; border: 1px solid var(--line); border-radius: 12px; padding: 12px 14px; font: inherit; background: var(--panel-solid); color: var(--ink); }
                .toolbar input:focus { border-color: var(--brand); box-shadow: 0 0 0 4px var(--ring); }
                .chips { display: flex; gap: 6px; flex-wrap: wrap; }
                .chip.kind, .chip.state { background: transparent; border-color: var(--line); }
                .chip.kind.on, .chip.state.on { background: var(--brand); border-color: var(--brand); color: #fff; }
                .counts { color: var(--muted); font-size: 13px; font-weight: 700; margin-left: auto; }

                /* table */
                .artifacts { }
                .table-scroll { overflow-x: auto; border: 1px solid var(--line); border-radius: 18px; background: var(--panel); box-shadow: var(--shadow); }
                .artifact-table { width: 100%; border-collapse: collapse; min-width: 820px; }
                thead th { text-align: left; font-size: 12px; text-transform: uppercase; letter-spacing: .06em; color: var(--muted); padding: 14px 16px; border-bottom: 1px solid var(--line); cursor: pointer; user-select: none; white-space: nowrap; }
                thead th:hover { color: var(--brand); }
                thead th.num, td.num { text-align: right; }
                .ratio-mini { display: inline-block; margin-left: 4px; font-size: 10px; color: var(--muted); font-weight: 700; }
                .artifact-row td { padding: 14px 16px; border-bottom: 1px solid var(--line); }
                .artifact-row:hover td { background: color-mix(in srgb, var(--brand) 4%, transparent); }
                .artifact-row.warn td .coverage { color: var(--warn); }
                .artifact-row.ok td .coverage { color: var(--good); }
                .cell-artifact-row { cursor: pointer; }
                .chev { display: inline-block; width: 8px; height: 8px; border-right: 2px solid var(--muted); border-bottom: 2px solid var(--muted); transform: rotate(-45deg); margin-right: 10px; transition: transform .2s ease; }
                .open .chev { transform: rotate(45deg); margin-top: -3px; }
                .artifact-id { display: grid; line-height: 1.25; }
                .artifact-id strong { font-size: 14px; }
                .artifact-id small { color: var(--muted); font-size: 12px; word-break: break-all; }
                .badge { display: inline-block; font-size: 11px; font-weight: 800; text-transform: uppercase; letter-spacing: .06em; padding: 5px 9px; border-radius: 999px; border: 1px solid var(--line); background: var(--panel-solid); color: var(--soft); }
                .metric-inline { font-variant-numeric: tabular-nums; }
                .coverage { font-variant-numeric: tabular-nums; }
                .state-pill { font-size: 12px; font-weight: 800; padding: 5px 10px; border-radius: 999px; }
                .state-pill.ok { color: var(--good); background: color-mix(in srgb, var(--good) 14%, transparent); }
                .state-pill.warn { color: var(--warn); background: color-mix(in srgb, var(--warn) 14%, transparent); }

                /* detail rows */
                .detail-row td { padding: 0; border-bottom: 1px solid var(--line); background: color-mix(in srgb, var(--panel-solid) 60%, transparent); }
                .detail-row[hidden] { display: none; }
                .detail { padding: 18px 22px; }
                .detail-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
                .metric { border: 1px solid var(--line); border-radius: 14px; padding: 12px; background: var(--panel); }
                .metric span { font-size: 12px; color: var(--muted); font-weight: 800; text-transform: uppercase; letter-spacing: .06em; }
                .metric strong { display: block; margin: 6px 0; font-size: 20px; }
                .metric small { color: var(--muted); }
                .threshold-detail { margin: 0 0 14px; font-size: 13px; color: var(--soft); }
                .drill-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px; }
                .drill-col h3 { margin: 0 0 10px; font-size: 12px; text-transform: uppercase; letter-spacing: .08em; color: var(--muted); }
                .tag-list { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 8px; }
                .tag-list code, .edge-list code { background: var(--panel-solid); border: 1px solid var(--line); border-radius: 8px; padding: 5px 8px; font-size: 12px; }
                .edge-list { list-style: none; margin: 0; padding: 0; max-height: 220px; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
                .edge-list li { font-size: 12px; color: var(--soft); }
                .ok-note { color: var(--good); font-weight: 800; font-size: 13px; }
                .provenance { margin: 16px 0 0; font-size: 12px; color: var(--muted); }
                .provenance code { margin-right: 6px; }
                .empty { text-align: center; color: var(--muted); padding: 30px 0; font-weight: 700; }
                [hidden] { display: none !important; }

                .foot { width: min(1180px, calc(100% - 32px)); margin: 0 auto; padding: 22px 0 40px; color: var(--muted); font-size: 12px; }

                @media (max-width: 980px) { .hero { grid-template-columns: 1fr; } .gauge-card { min-height: 220px; } .stat-tiles { grid-template-columns: repeat(3, 1fr); } }
                @media (max-width: 860px) { .stat-tiles, .detail-grid, .drill-grid { grid-template-columns: 1fr; } .topbar-meta { display: none; } .toolbar { position: static; } }
                @media (prefers-reduced-motion: reduce) { * { animation: none !important; transition: none !important; } html { scroll-behavior: auto; } }
                @media print { body { background: #fff; color: #000; } .topbar { position: static; } .toolbar, .icon-btn, .banner { display: none; } .detail-row { display: table-row !important; } .artifact-row { break-inside: avoid; } }
                """;
    }
}
