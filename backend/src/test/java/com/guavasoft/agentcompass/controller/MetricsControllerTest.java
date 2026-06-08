package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.CatalogMetric;
import com.guavasoft.agentcompass.model.CostModelShare;
import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.EventRow;
import com.guavasoft.agentcompass.model.ExemplarPoint;
import com.guavasoft.agentcompass.model.MetricSeries;
import com.guavasoft.agentcompass.model.MetricSplitRow;
import com.guavasoft.agentcompass.model.TokenDistribution;
import com.guavasoft.agentcompass.service.MetricQueryService;
import com.guavasoft.agentcompass.service.MetricSeriesService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetricsController.class)
class MetricsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MetricQueryService metricQueryService;

    @MockitoBean
    MetricSeriesService metricSeriesService;

    @Test
    void metricsReturnsAllEventRowsWhenNoFilterApplied() throws Exception {
        when(metricQueryService.recentEvents(List.of(), null, null)).thenReturn(List.of(
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
                        .build()));

        mockMvc.perform(get("/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].metricName").value("claude_code.code_edit_tool.decision"))
                .andExpect(jsonPath("$[0].valueLong").value(3))
                .andExpect(jsonPath("$[0].valueKind").value("long"));

        verify(metricQueryService).recentEvents(List.of(), null, null);
    }

    @Test
    void metricsNarrowsBySuppliedFiltersAndReturnsFilteredCount() throws Exception {
        when(metricQueryService.recentEvents(List.of("method=GET", "status=200"), null, null)).thenReturn(List.of(
                EventRow.builder()
                        .id(7L)
                        .metricName("http.server.request.duration")
                        .scopeName("test-scope")
                        .timestamp(Instant.parse("2026-05-21T12:00:00Z"))
                        .valueDouble(12.4)
                        .valueKind("double")
                        .build()));

        mockMvc.perform(get("/api/metrics")
                .param("filter", "method=GET", "status=200"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].metricName").value("http.server.request.duration"));

        verify(metricQueryService).recentEvents(List.of("method=GET", "status=200"), null, null);
    }

    @Test
    void metricAttributesReturnsDistinctKeyValuePairsWhenNoFilterApplied() throws Exception {
        when(metricQueryService.availableAttributePairs(List.of(), null, null)).thenReturn(List.of(
                "method=GET",
                "route=/users",
                "status=200"));

        mockMvc.perform(get("/api/metrics/attributes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0]").value("method=GET"))
                .andExpect(jsonPath("$[1]").value("route=/users"))
                .andExpect(jsonPath("$[2]").value("status=200"));

        verify(metricQueryService).availableAttributePairs(List.of(), null, null);
    }

    @Test
    void metricAttributesNarrowsBySuppliedFilters() throws Exception {
        when(metricQueryService.availableAttributePairs(List.of("method=GET", "status=200"), null, null))
                .thenReturn(List.of("method=GET", "route=/users", "status=200"));

        mockMvc.perform(get("/api/metrics/attributes")
                .param("filter", "method=GET", "status=200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        verify(metricQueryService).availableAttributePairs(List.of("method=GET", "status=200"), null, null);
    }

    @Test
    void metricCatalogDispatchesToServiceWithFromAndTo() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricQueryService.aggregateMetricCatalog(from, to)).thenReturn(List.of(
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

        verify(metricQueryService).aggregateMetricCatalog(from, to);
    }

    @Test
    void metricCostDispatchesToServiceWithFromAndTo() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricQueryService.aggregateCostSummary(from, to)).thenReturn(new CostSummary(
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

        verify(metricQueryService).aggregateCostSummary(from, to);
    }

    @Test
    void metricDistributionDispatchesToServiceWithFromAndTo() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricQueryService.aggregateTokenDistribution(from, to)).thenReturn(new TokenDistribution(
                List.of("256K", "128K", "64K", "32K", "16K", "8K", "4K", "0"),
                List.of(new ExemplarPoint(
                        3, 4,
                        "13.2K", "1.9s",
                        "sonnet-4", "ok", "a13f·9c7e2",
                        "8K – 16K", "14:03:51", true,
                        List.of(), List.of(List.of("model", "claude-sonnet-4"))))));

        mockMvc.perform(get("/api/metrics/distribution")
                .param("from", "2026-05-01T00:00:00Z")
                .param("to", "2026-05-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bands", hasSize(8)))
                .andExpect(jsonPath("$.bands[0]").value("256K"))
                .andExpect(jsonPath("$.exemplars", hasSize(1)))
                .andExpect(jsonPath("$.exemplars[0].tokens").value("13.2K"))
                .andExpect(jsonPath("$.exemplars[0].model").value("sonnet-4"))
                .andExpect(jsonPath("$.exemplars[0].status").value("ok"))
                .andExpect(jsonPath("$.exemplars[0].worst").value(true));

        verify(metricQueryService).aggregateTokenDistribution(from, to);
    }

    @Test
    void metricSeriesDispatchesToServiceWithFromAndTo() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T23:59:59Z");
        when(metricSeriesService.metricSeries(from, to)).thenReturn(List.of(
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
                                new MetricSplitRow("claude-sonnet-4", "7.8M", 60, 0))))));

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
                .andExpect(jsonPath("$[0].splits.Model[0].pct").value(60));

        verify(metricSeriesService).metricSeries(from, to);
    }
}
