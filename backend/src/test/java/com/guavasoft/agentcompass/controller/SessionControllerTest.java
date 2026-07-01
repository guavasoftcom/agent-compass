package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.SessionKpis;
import com.guavasoft.agentcompass.model.SessionSummary;
import com.guavasoft.agentcompass.model.SessionSummaryPage;
import com.guavasoft.agentcompass.model.TokenUsageSummary;
import com.guavasoft.agentcompass.service.MetricService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    MetricService metricService;

    @Test
    void tokenUsageReturnsAggregatedTotalsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(metricService.aggregateTokenUsage(anyInt())).thenReturn(new TokenUsageSummary(
                12_000L,
                8_000L,
                4_000L,
                96_000L,
                0.96,
                900L,
                List.of(new TokenUsageSummary.Point(
                        Instant.parse("2026-05-21T12:00:00Z"), 1_200L, 800L, 400L, 9_600L)),
                List.of(),
                new CostSummary("$0.00", "+0.0%", "$0/h", "$0", "$0.000", List.of(), List.of(), "")));

        mockMvc.perform(get("/api/sessions/token-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTokens").value(12000))
                .andExpect(jsonPath("$.outputTokens").value(8000))
                .andExpect(jsonPath("$.cacheCreationTokens").value(4000))
                .andExpect(jsonPath("$.cacheReadTokens").value(96000))
                .andExpect(jsonPath("$.cacheReadRatio").value(0.96))
                .andExpect(jsonPath("$.bucketSeconds").value(900))
                .andExpect(jsonPath("$.points", hasSize(1)))
                .andExpect(jsonPath("$.points[0].cacheRead").value(9600));

        verify(metricService).aggregateTokenUsage(1440);
    }

    @Test
    void sessionsReturnsPaginatedRowsWithTotalCountHeaderAndDefaults() throws Exception {
        when(metricService.sessionsSummary(anyInt(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SessionSummaryPage(List.of(
                        new SessionSummary(
                                "7b3fc524-7f3c-4db5-9bb4-da27b77df56b",
                                12.34,
                                1500.5,
                                Instant.parse("2026-05-27T00:04:31.320Z"),
                                Instant.parse("2026-05-27T01:07:15.208Z"),
                                3764L,
                                0L,
                                0L,
                                5_400_000L,
                                "non-interactive",
                                "resume"),
                        new SessionSummary(
                                "025a8c32-26ff-409d-b704-dc19dcecbb47",
                                1.5,
                                300.0,
                                Instant.parse("2026-05-27T02:00:00Z"),
                                Instant.parse("2026-05-27T02:10:00Z"),
                                600L,
                                0L,
                                0L,
                                120_000L,
                                "interactive",
                                "fresh")),
                        42L));

        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "42"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sessionId").value("7b3fc524-7f3c-4db5-9bb4-da27b77df56b"))
                .andExpect(jsonPath("$[0].costUsd").value(12.34))
                .andExpect(jsonPath("$[0].activeTimeSeconds").value(1500.5))
                .andExpect(jsonPath("$[0].wallSeconds").value(3764))
                .andExpect(jsonPath("$[0].tokens").value(5400000))
                .andExpect(jsonPath("$[0].terminalType").value("non-interactive"))
                .andExpect(jsonPath("$[0].startType").value("resume"))
                .andExpect(jsonPath("$[1].sessionId").value("025a8c32-26ff-409d-b704-dc19dcecbb47"))
                .andExpect(jsonPath("$[1].startType").value("fresh"));

        verify(metricService).sessionsSummary(1440, null, null, 0, 25);
    }

    @Test
    void sessionsForwardsSortAndPaginationParams() throws Exception {
        when(metricService.sessionsSummary(anyInt(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new SessionSummaryPage(List.of(), 0L));

        mockMvc.perform(get("/api/sessions")
                .param("sort", "wallSeconds")
                .param("direction", "asc")
                .param("page", "2")
                .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));

        verify(metricService).sessionsSummary(1440, "wallSeconds", "asc", 2, 50);
    }

    @Test
    void sessionsSummaryReturnsWindowKpisAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(metricService.sessionsKpis(anyInt()))
                .thenReturn(new SessionKpis(128L, 1.42, 9.87, 0.123, List.of(0L, 1L, 3L)));

        mockMvc.perform(get("/api/sessions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSessions").value(128))
                .andExpect(jsonPath("$.medianCostUsd").value(1.42))
                .andExpect(jsonPath("$.p95CostUsd").value(9.87))
                .andExpect(jsonPath("$.medianCostPerActiveMinuteUsd").value(0.123))
                .andExpect(jsonPath("$.sessionsTrend").value(contains(0, 1, 3)));

        verify(metricService).sessionsKpis(1440);
    }
}
