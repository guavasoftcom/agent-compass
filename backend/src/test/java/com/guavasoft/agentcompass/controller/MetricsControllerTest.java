/*
 * Copyright (c) 2026 Guadalupe Garcia <guad.daniel.garcia@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not,
 * see <https://www.gnu.org/licenses/>.
 */
package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.CatalogMetric;
import com.guavasoft.agentcompass.model.CostModelShare;
import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.DistributionPoint;
import com.guavasoft.agentcompass.model.EventRow;
import com.guavasoft.agentcompass.model.MetricAttributes;
import com.guavasoft.agentcompass.model.MetricDistribution;
import com.guavasoft.agentcompass.model.MetricFacet;
import com.guavasoft.agentcompass.model.MetricFacetValue;
import com.guavasoft.agentcompass.model.MetricPage;
import com.guavasoft.agentcompass.model.MetricSeries;
import com.guavasoft.agentcompass.model.MetricAggregation;
import com.guavasoft.agentcompass.model.MetricSeriesAggregation;
import com.guavasoft.agentcompass.model.MetricSeriesFilter;
import com.guavasoft.agentcompass.model.MetricSplitRow;
import com.guavasoft.agentcompass.service.MetricService;
import com.guavasoft.agentcompass.service.MetricSeriesService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricsController.class)
class MetricsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MetricService metricService;

    @MockitoBean
    MetricSeriesService metricSeriesService;

    @Test
    void metricsReturnsFirstPageWhenNoFilterApplied() throws Exception {
        when(metricService.recentEvents(List.of(), null, null, 0, 25)).thenReturn(new MetricPage(
                List.of(
                        EventRow.builder()
                                .id(1L)
                                .metricName("claude_code.code_edit_tool.decision")
                                .scopeName("test-scope")
                                .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                                .valueLong(3L)
                                .valueKind("long")
                                .build(),
                        EventRow.builder()
                                .id(2L)
                                .metricName("claude_code.code_edit_tool.decision")
                                .scopeName("test-scope")
                                .timestamp(Instant.parse("2026-05-21T11:00:00Z"))
                                .valueLong(1L)
                                .valueKind("long")
                                .build()),
                2L));

        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].metricName").value("claude_code.code_edit_tool.decision"))
                .andExpect(jsonPath("$.items[0].valueLong").value(3))
                .andExpect(jsonPath("$.items[0].valueKind").value("long"));

        verify(metricService).recentEvents(List.of(), null, null, 0, 25);
    }

    @Test
    void metricsNarrowsBySuppliedFiltersAndReturnsFilteredTotalCount() throws Exception {
        when(metricService.recentEvents(List.of("method=GET", "status=200"), null, null, 0, 25)).thenReturn(new MetricPage(
                List.of(
                        EventRow.builder()
                                .id(7L)
                                .metricName("http.server.request.duration")
                                .scopeName("test-scope")
                                .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                                .valueDouble(12.4)
                                .valueKind("double")
                                .build()),
                1L));

        mockMvc.perform(get("/api/metrics")
                .param("filter", "method=GET", "status=200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].metricName").value("http.server.request.duration"));

        verify(metricService).recentEvents(List.of("method=GET", "status=200"), null, null, 0, 25);
    }

    @Test
    void metricsDispatchesSuppliedPageAndSizeToService() throws Exception {
        when(metricService.recentEvents(List.of(), null, null, 2, 50)).thenReturn(new MetricPage(List.of(), 130L));

        mockMvc.perform(get("/api/metrics")
                .param("page", "2")
                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.totalCount").value(130));

        verify(metricService).recentEvents(List.of(), null, null, 2, 50);
    }

    @Test
    void metricAttributesDispatchesByMetricNameAndReturnsTheWrappedKeysWithValueCounts() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        String repositoryUrl = "https://github.com/guavasoftcom/agent-compass";
        when(metricSeriesService.metricAttributes(from, to, repositoryUrl, "claude_code.token.usage"))
                .thenReturn(new MetricAttributes(List.of(
                        new MetricFacet("model", List.of(
                                new MetricFacetValue("claude-sonnet-4", 812L),
                                new MetricFacetValue("claude-opus-4", 340L))),
                        new MetricFacet("terminal.type", List.of(new MetricFacetValue("vscode", 1100L))))));

        mockMvc.perform(get("/api/metrics/attributes")
                .param("metric", "claude_code.token.usage")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("repositoryUrl", repositoryUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes", hasSize(2)))
                .andExpect(jsonPath("$.attributes[0].key").value("model"))
                .andExpect(jsonPath("$.attributes[0].values", hasSize(2)))
                .andExpect(jsonPath("$.attributes[0].values[0].value").value("claude-sonnet-4"))
                .andExpect(jsonPath("$.attributes[0].values[0].count").value(812))
                .andExpect(jsonPath("$.attributes[0].values[1].value").value("claude-opus-4"))
                .andExpect(jsonPath("$.attributes[1].key").value("terminal.type"))
                .andExpect(jsonPath("$.attributes[1].values[0].count").value(1100));

        verify(metricSeriesService).metricAttributes(from, to, repositoryUrl, "claude_code.token.usage");
    }

    @Test
    void metricAttributesTreatsRepositoryUrlAsOptionalAndAnUnseenMetricYieldsAnEmptyList() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricSeriesService.metricAttributes(from, to, null, "claude_code.never_emitted"))
                .thenReturn(new MetricAttributes(List.of()));

        mockMvc.perform(get("/api/metrics/attributes")
                .param("metric", "claude_code.never_emitted")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes", hasSize(0)));

        verify(metricSeriesService).metricAttributes(from, to, null, "claude_code.never_emitted");
    }

    @Test
    void metricAttributesRequiresMetricAndBothWindowBounds() throws Exception {
        mockMvc.perform(get("/api/metrics/attributes")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/metrics/attributes")
                .param("metric", "claude_code.token.usage")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricSeriesService);
    }

    @Test
    void theSeriesFacetsEndpointNoLongerExists() throws Exception {
        mockMvc.perform(get("/api/metrics/series/facets")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("metricId", "token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void metricCatalogDispatchesToServiceWithFromAndTo() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricService.aggregateMetricCatalog(from, to)).thenReturn(List.of(
                new CatalogMetric(
                        "claude_code.token.usage",
                        "tokens",
                        "counter",
                        "1.2K",
                        "ok",
                        List.of(10L, 20L, 15L, 30L, 25L, 40L, 35L, 50L))));

        mockMvc.perform(get("/api/metrics/catalog")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("claude_code.token.usage"))
                .andExpect(jsonPath("$[0].unit").value("tokens"))
                .andExpect(jsonPath("$[0].health").value("ok"))
                .andExpect(jsonPath("$[0].cardinality").value("1.2K"))
                .andExpect(jsonPath("$[0].spark", hasSize(8)));

        verify(metricService).aggregateMetricCatalog(from, to);
    }

    @Test
    void metricCostDispatchesToServiceWithFromAndTo() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricService.aggregateCostSummary(from, to)).thenReturn(new CostSummary(
                "$1,284",
                "+22.4%",
                "$53/h",
                "$38.5K",
                "$0.099",
                List.of(12.0, 18.0, 24.0),
                List.of(new CostModelShare("claude-sonnet-4", "$720", 56, 0)),
                ""));

        mockMvc.perform(get("/api/metrics/cost")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spend24h").value("$1,284"))
                .andExpect(jsonPath("$.deltaPct").value("+22.4%"))
                .andExpect(jsonPath("$.burnRate").value("$53/h"))
                .andExpect(jsonPath("$.projected30d").value("$38.5K"))
                .andExpect(jsonPath("$.costPer1k").value("$0.099"))
                .andExpect(jsonPath("$.trend", hasSize(3)))
                .andExpect(jsonPath("$.byModel", hasSize(1)))
                .andExpect(jsonPath("$.byModel[0].model").value("claude-sonnet-4"))
                .andExpect(jsonPath("$.byModel[0].share").value(56));

        verify(metricService).aggregateCostSummary(from, to);
    }

    @Test
    void metricDistributionDispatchesByFullMetricNameAndReturnsThePointList() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        String repositoryUrl = "https://github.com/guavasoftcom/agent-compass";
        when(metricService.aggregateMetricDistribution(from, to, repositoryUrl, "claude_code.token.usage"))
                .thenReturn(new MetricDistribution(List.of(
                        new DistributionPoint(Instant.parse("2026-05-11T08:12:40Z"), 13_180.0, null, null),
                        new DistributionPoint(
                                Instant.parse("2026-05-11T11:47:13Z"), 27_340.0, "7b22c0f4a1d94e6bb03a5e8f2c1f019d",
                                "00f067aa0ba902b7"))));

        mockMvc.perform(get("/api/metrics/distribution")
                .param("metric", "claude_code.token.usage")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("repositoryUrl", repositoryUrl))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(2)))
                .andExpect(jsonPath("$.points[0].ts").value("2026-05-11T08:12:40Z"))
                .andExpect(jsonPath("$.points[0].value").value(13180.0))
                .andExpect(jsonPath("$.points[0].traceId").value(nullValue()))
                .andExpect(jsonPath("$.points[1].ts").value("2026-05-11T11:47:13Z"))
                .andExpect(jsonPath("$.points[1].value").value(27340.0))
                .andExpect(jsonPath("$.points[1].traceId").value("7b22c0f4a1d94e6bb03a5e8f2c1f019d"))
                .andExpect(jsonPath("$.points[0].spanId").value(nullValue()))
                .andExpect(jsonPath("$.points[1].spanId").value("00f067aa0ba902b7"))
                // The heatmap-era fields are gone, and so is the old metricId parameter's echo.
                .andExpect(jsonPath("$.cells").doesNotExist())
                .andExpect(jsonPath("$.exemplars").doesNotExist())
                .andExpect(jsonPath("$.metricId").doesNotExist());

        verify(metricService).aggregateMetricDistribution(from, to, repositoryUrl, "claude_code.token.usage");
    }

    @Test
    void metricDistributionTreatsRepositoryUrlAsOptionalAndReturnsAnEmptyPointList() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricService.aggregateMetricDistribution(from, to, null, "claude_code.cost.usage"))
                .thenReturn(new MetricDistribution(List.of()));

        mockMvc.perform(get("/api/metrics/distribution")
                .param("metric", "claude_code.cost.usage")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(0)));

        verify(metricService).aggregateMetricDistribution(from, to, null, "claude_code.cost.usage");
    }

    @Test
    void metricDistributionRejectsAMetricThatIsNeitherTokenNorCostWithBadRequest() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricService.aggregateMetricDistribution(from, to, null, "claude_code.session.count"))
                .thenThrow(new IllegalArgumentException("Unsupported metric 'claude_code.session.count'"));

        mockMvc.perform(get("/api/metrics/distribution")
                .param("metric", "claude_code.session.count")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void metricDistributionRequiresMetricAndNoLongerAcceptsTheOldMetricIdParameter() throws Exception {
        mockMvc.perform(get("/api/metrics/distribution")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/metrics/distribution")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("metricId", "token"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricService);
    }

    @Test
    void metricSeriesDispatchesToServiceWithFromAndTo() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricSeriesService.metricSeries(from, to, null, MetricSeriesFilter.NONE, MetricSeriesAggregation.NONE)).thenReturn(List.of(
                new MetricSeries(
                        "token",
                        "claude_code.token.usage",
                        "counter",
                        "tokens",
                        "13.0M",
                        "Sum (24h)",
                        "542K",
                        "/h",
                        "820K",
                        "+18.3%",
                        "up",
                        "Tokens consumed across sessions.",
                        List.of(260.0, 312.0, 820.0),
                        Map.of("Model", List.of(
                                new MetricSplitRow("claude-sonnet-4", "7.8M", 60, 0))),
                        1234L,
                        "ok",
                        true)));

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("token"))
                .andExpect(jsonPath("$[0].name").value("claude_code.token.usage"))
                .andExpect(jsonPath("$[0].type").value("counter"))
                .andExpect(jsonPath("$[0].sum").value("13.0M"))
                .andExpect(jsonPath("$[0].rate").value("542K"))
                .andExpect(jsonPath("$[0].rateUnit").value("/h"))
                .andExpect(jsonPath("$[0].dir").value("up"))
                .andExpect(jsonPath("$[0].trend", hasSize(3)))
                .andExpect(jsonPath("$[0].splits.Model", hasSize(1)))
                .andExpect(jsonPath("$[0].splits.Model[0].label").value("claude-sonnet-4"))
                .andExpect(jsonPath("$[0].splits.Model[0].pct").value(60))
                .andExpect(jsonPath("$[0].cardinality").value(1234))
                .andExpect(jsonPath("$[0].health").value("ok"))
                .andExpect(jsonPath("$[0].hasDistribution").value(true));

        verify(metricSeriesService).metricSeries(from, to, null, MetricSeriesFilter.NONE, MetricSeriesAggregation.NONE);
    }

    @Test
    void metricSeriesForwardsRepeatedFiltersAndRepositoryToTheService() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        String repositoryUrl = "https://github.com/guavasoftcom/agent-compass";
        MetricSeriesFilter filter = MetricSeriesFilter.of(
                "token", List.of("model:claude-opus-4", "terminal.type:vscode"));
        when(metricSeriesService.metricSeries(from, to, repositoryUrl, filter, MetricSeriesAggregation.NONE))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("repositoryUrl", repositoryUrl)
                .param("filterMetricId", "token")
                .param("filter", "model:claude-opus-4", "terminal.type:vscode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(metricSeriesService).metricSeries(from, to, repositoryUrl, filter, MetricSeriesAggregation.NONE);
        assertThat(filter.matches()).containsExactly(
                new MetricSeriesFilter.AttributeMatch("model", "claude-opus-4"),
                new MetricSeriesFilter.AttributeMatch("terminal.type", "vscode"));
    }

    @Test
    void metricSeriesSplitsEachFilterOnItsFirstColonOnlyAndKeepsCommasInValues() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        // A colon in the value survives, and so does a comma: Spring would otherwise split a single
        // collection-typed request parameter on it and turn one filter into two malformed ones.
        MetricSeriesFilter filter = MetricSeriesFilter.of(
                "loc", List.of("file_path:C:\\work\\a.txt", "label:one,two"));
        when(metricSeriesService.metricSeries(from, to, null, filter, MetricSeriesAggregation.NONE))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filterMetricId", "loc")
                .param("filter", "file_path:C:\\work\\a.txt")
                .param("filter", "label:one,two"))
                .andExpect(status().isOk());

        verify(metricSeriesService).metricSeries(from, to, null, filter, MetricSeriesAggregation.NONE);
        assertThat(filter.matches()).containsExactly(
                new MetricSeriesFilter.AttributeMatch("file_path", "C:\\work\\a.txt"),
                new MetricSeriesFilter.AttributeMatch("label", "one,two"));
    }

    @Test
    void metricSeriesTreatsABlankFilterMetricIdWithNoFiltersAsAbsent() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricSeriesService.metricSeries(from, to, null, MetricSeriesFilter.NONE, MetricSeriesAggregation.NONE))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filterMetricId", ""))
                .andExpect(status().isOk());

        verify(metricSeriesService).metricSeries(from, to, null, MetricSeriesFilter.NONE, MetricSeriesAggregation.NONE);
    }

    @Test
    void metricSeriesRejectsAFilterWithoutAMetricIdAndAMetricIdWithoutAFilter() throws Exception {
        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filter", "model:claude-sonnet-4"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filterMetricId", "token"))
                .andExpect(status().isBadRequest());
        // The old filterKey/filterValue pair is gone: it is ignored, leaving a metric id with no filter.
        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filterMetricId", "token")
                .param("filterKey", "model")
                .param("filterValue", "claude-sonnet-4"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricSeriesService);
    }

    @Test
    void metricSeriesRejectsAMalformedFilterPairWithBadRequest() throws Exception {
        // No colon at all, an empty key, a blank key, and one bad pair among good ones.
        for (String malformedPair : List.of("model", ":claude-sonnet-4", "  :claude-sonnet-4", "")) {
            mockMvc.perform(get("/api/metrics/series")
                    .param("from", "2026-05-01T00:00:00Z")
                    .param("to", "2026-05-31T23:59:59Z")
                    .param("filterMetricId", "token")
                    .param("filter", malformedPair))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filterMetricId", "token")
                .param("filter", "model:claude-sonnet-4", "terminal.type"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricSeriesService);
    }

    @Test
    void metricSeriesAcceptsAnEmptyFilterValue() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        MetricSeriesFilter filter = MetricSeriesFilter.of("token", List.of("query_source:"));
        when(metricSeriesService.metricSeries(from, to, null, filter, MetricSeriesAggregation.NONE))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filterMetricId", "token")
                .param("filter", "query_source:"))
                .andExpect(status().isOk());

        assertThat(filter.matches()).containsExactly(new MetricSeriesFilter.AttributeMatch("query_source", ""));
    }

    @Test
    void metricSeriesRejectsAnUnknownFilterMetricIdWithBadRequest() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        MetricSeriesFilter filter = MetricSeriesFilter.of("latency", List.of("model:claude-sonnet-4"));
        when(metricSeriesService.metricSeries(from, to, null, filter, MetricSeriesAggregation.NONE))
                .thenThrow(new IllegalArgumentException("Unknown metricId 'latency'"));

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("filterMetricId", "latency")
                .param("filter", "model:claude-sonnet-4"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void metricSeriesForwardsTheAggregationTogetherWithFilterAndRepository() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        String repositoryUrl = "https://github.com/guavasoftcom/agent-compass";
        MetricSeriesFilter filter = MetricSeriesFilter.of("token", List.of("model:claude-sonnet-4"));
        MetricSeriesAggregation aggregation = new MetricSeriesAggregation("token", MetricAggregation.AVG);
        when(metricSeriesService.metricSeries(from, to, repositoryUrl, filter, aggregation))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("repositoryUrl", repositoryUrl)
                .param("filterMetricId", "token")
                .param("filter", "model:claude-sonnet-4")
                .param("aggMetricId", "token")
                .param("agg", "avg"))
                .andExpect(status().isOk());

        verify(metricSeriesService).metricSeries(from, to, repositoryUrl, filter, aggregation);
    }

    @Test
    void metricSeriesParsesAggCaseInsensitivelyForEverySupportedValue() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        for (String agg : List.of("SUM", "Avg", "p95", "COUNT")) {
            MetricSeriesAggregation aggregation = new MetricSeriesAggregation(
                    "session", MetricAggregation.valueOf(agg.toUpperCase(java.util.Locale.ROOT)));
            when(metricSeriesService.metricSeries(from, to, null, MetricSeriesFilter.NONE, aggregation))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/metrics/series")
                    .param("from", "2026-05-01T00:00:00Z")
                    .param("to", "2026-05-31T23:59:59Z")
                    .param("aggMetricId", "session")
                    .param("agg", agg))
                    .andExpect(status().isOk());

            verify(metricSeriesService).metricSeries(from, to, null, MetricSeriesFilter.NONE, aggregation);
        }
    }

    @Test
    void metricSeriesAggSumIsAValidNoOpRequest() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        MetricSeriesAggregation sum = new MetricSeriesAggregation("token", MetricAggregation.SUM);
        when(metricSeriesService.metricSeries(from, to, null, MetricSeriesFilter.NONE, sum))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("aggMetricId", "token")
                .param("agg", "sum"))
                .andExpect(status().isOk());

        verify(metricSeriesService).metricSeries(from, to, null, MetricSeriesFilter.NONE, sum);
    }

    @Test
    void metricSeriesTreatsBlankAggregationParamsAsAbsent() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricSeriesService.metricSeries(from, to, null, MetricSeriesFilter.NONE, MetricSeriesAggregation.NONE))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("aggMetricId", " ")
                .param("agg", ""))
                .andExpect(status().isOk());

        verify(metricSeriesService)
                .metricSeries(from, to, null, MetricSeriesFilter.NONE, MetricSeriesAggregation.NONE);
    }

    @Test
    void metricSeriesRejectsOnlyOneOfAggMetricIdAndAggWithBadRequest() throws Exception {
        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("aggMetricId", "token"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("agg", "avg"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricSeriesService);
    }

    @Test
    void metricSeriesRejectsAnUnsupportedAggWithBadRequest() throws Exception {
        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("aggMetricId", "token")
                .param("agg", "median"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metricSeriesService);
    }

    @Test
    void metricSeriesRejectsAnUnknownAggMetricIdWithBadRequest() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        MetricSeriesAggregation aggregation = new MetricSeriesAggregation("latency", MetricAggregation.P95);
        when(metricSeriesService.metricSeries(from, to, null, MetricSeriesFilter.NONE, aggregation))
                .thenThrow(new IllegalArgumentException("Unknown metricId 'latency'"));

        mockMvc.perform(get("/api/metrics/series")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z")
                .param("aggMetricId", "latency")
                .param("agg", "p95"))
                .andExpect(status().isBadRequest());
    }
}
