package com.guavasoft.agentcompass.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.guavasoft.agentcompass.model.CostSummary;
import com.guavasoft.agentcompass.model.SessionApiRequest;
import com.guavasoft.agentcompass.model.SessionCacheEfficiency;
import com.guavasoft.agentcompass.model.SessionKpis;
import com.guavasoft.agentcompass.model.SessionPrompt;
import com.guavasoft.agentcompass.model.SessionPromptToolCount;
import com.guavasoft.agentcompass.model.SessionSummary;
import com.guavasoft.agentcompass.model.SessionSummaryPage;
import com.guavasoft.agentcompass.model.SessionTokenBreakdown;
import com.guavasoft.agentcompass.model.TokenUsageSummary;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.MetricService;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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

    @MockitoBean
    LogService logService;

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
                                "resume",
                                "Refactor the SessionSummary record",
                                3L,
                                new SessionTokenBreakdown(600_000L, 1_400_000L, 400_000L, 3_000_000L)),
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
                                "fresh",
                                null,
                                0L,
                                new SessionTokenBreakdown(0L, 0L, 0L, 120_000L))),
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
                .andExpect(jsonPath("$[0].tokenBreakdown.input").value(600000))
                .andExpect(jsonPath("$[0].tokenBreakdown.output").value(1400000))
                .andExpect(jsonPath("$[0].tokenBreakdown.cacheCreation").value(400000))
                .andExpect(jsonPath("$[0].tokenBreakdown.cacheRead").value(3000000))
                .andExpect(jsonPath("$[0].terminalType").value("non-interactive"))
                .andExpect(jsonPath("$[0].startType").value("resume"))
                .andExpect(jsonPath("$[0].firstUserPrompt").value("Refactor the SessionSummary record"))
                .andExpect(jsonPath("$[0].userPromptCount").value(3))
                .andExpect(jsonPath("$[1].sessionId").value("025a8c32-26ff-409d-b704-dc19dcecbb47"))
                .andExpect(jsonPath("$[1].startType").value("fresh"))
                .andExpect(jsonPath("$[1].firstUserPrompt").value(nullValue()))
                .andExpect(jsonPath("$[1].userPromptCount").value(0))
                .andExpect(jsonPath("$[1].tokenBreakdown.cacheRead").value(120000));

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

    @Test
    void promptsDispatchesToLogServiceAndReturnsTimelineInAscendingOrder() throws Exception {
        String sessionId = "7b3fc524-7f3c-4db5-9bb4-da27b77df56b";
        when(logService.promptsForSession(sessionId)).thenReturn(List.of(
                new SessionPrompt(Instant.parse("2026-05-27T00:04:31.320Z"), "/ship", null),
                new SessionPrompt(
                        Instant.parse("2026-05-27T00:05:12.100Z"), "Add prompt context to the sessions API",
                        "0102030405060708090a0b0c0d0e0f10",
                        "claude-sonnet-4-5",
                        0.8,
                        new SessionTokenBreakdown(1840L, 3120L, 18400L, 214000L),
                        List.of(new SessionPromptToolCount("Read", 4L), new SessionPromptToolCount("Edit", 2L)),
                        "9a7ac484-195b-4a74-a78d-1cf67c973af5",
                        11L,
                        SessionPrompt.TurnAttribution.REQUEST)));

        mockMvc.perform(get("/api/sessions/{sessionId}/prompts", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].prompt").value("/ship"))
                .andExpect(jsonPath("$[0].traceId").value(nullValue()))
                .andExpect(jsonPath("$[0].model").value(nullValue()))
                .andExpect(jsonPath("$[0].costUsd").value(nullValue()))
                .andExpect(jsonPath("$[0].tokens").value(nullValue()))
                .andExpect(jsonPath("$[0].tools", hasSize(0)))
                .andExpect(jsonPath("$[1].traceId").value("0102030405060708090a0b0c0d0e0f10"))
                .andExpect(jsonPath("$[1].prompt").value("Add prompt context to the sessions API"))
                .andExpect(jsonPath("$[1].model").value("claude-sonnet-4-5"))
                .andExpect(jsonPath("$[1].costUsd").value(0.8))
                .andExpect(jsonPath("$[1].tokens.input").value(1840))
                .andExpect(jsonPath("$[1].tokens.output").value(3120))
                .andExpect(jsonPath("$[1].tokens.cacheCreation").value(18400))
                .andExpect(jsonPath("$[1].tokens.cacheRead").value(214000))
                .andExpect(jsonPath("$[1].tools", hasSize(2)))
                .andExpect(jsonPath("$[1].tools[0].name").value("Read"))
                .andExpect(jsonPath("$[1].tools[0].count").value(4))
                .andExpect(jsonPath("$[1].tools[1].name").value("Edit"))
                .andExpect(jsonPath("$[1].tools[1].count").value(2))
                // Attribution is on the wire so clients can tell exact per-request
                // figures from the older interval-bucketed approximation.
                .andExpect(jsonPath("$[0].attribution").value("INTERVAL"))
                .andExpect(jsonPath("$[0].requestCount").value(0))
                .andExpect(jsonPath("$[1].attribution").value("REQUEST"))
                .andExpect(jsonPath("$[1].requestCount").value(11))
                // promptId is the join key into /requests; the drill-down cannot
                // group a turn's calls without it.
                .andExpect(jsonPath("$[0].promptId").value(nullValue()))
                .andExpect(jsonPath("$[1].promptId").value("9a7ac484-195b-4a74-a78d-1cf67c973af5"));

        verify(logService).promptsForSession(sessionId);
    }

    @Test
    void requestsDispatchesToLogServiceAndReturnsPerCallTokensAndCost() throws Exception {
        String sessionId = "7b3fc524-7f3c-4db5-9bb4-da27b77df56b";
        when(logService.requestsForSession(sessionId)).thenReturn(List.of(
                new SessionApiRequest(
                        "req_011CdqUicd2Zjki38GaLv3VN",
                        Instant.parse("2026-08-08T14:58:46.404Z"),
                        "9a7ac484-195b-4a74-a78d-1cf67c973af5",
                        "claude-opus-5",
                        new SessionTokenBreakdown(2L, 1183L, 1853L, 236033L),
                        0.1661315,
                        16495L,
                        "high",
                        "normal",
                        "0102030405060708090a0b0c0d0e0f10"),
                // effort is absent on a minority of rows; it must serialize as null
                // rather than being defaulted to a value the agent never used.
                new SessionApiRequest(
                        "req_022Dd", Instant.parse("2026-08-08T14:59:10.000Z"),
                        "9a7ac484-195b-4a74-a78d-1cf67c973af5", "claude-opus-5",
                        new SessionTokenBreakdown(4L, 90L, 0L, 240000L),
                        0.02, 800L, null, "normal", null)));

        mockMvc.perform(get("/api/sessions/{sessionId}/requests", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].requestId").value("req_011CdqUicd2Zjki38GaLv3VN"))
                .andExpect(jsonPath("$[0].promptId").value("9a7ac484-195b-4a74-a78d-1cf67c973af5"))
                .andExpect(jsonPath("$[0].model").value("claude-opus-5"))
                .andExpect(jsonPath("$[0].tokens.cacheRead").value(236033))
                .andExpect(jsonPath("$[0].costUsd").value(0.1661315))
                .andExpect(jsonPath("$[0].durationMs").value(16495))
                .andExpect(jsonPath("$[0].effort").value("high"))
                .andExpect(jsonPath("$[1].effort").value(nullValue()))
                .andExpect(jsonPath("$[1].traceId").value(nullValue()));

        verify(logService).requestsForSession(sessionId);
    }

    @Test
    void cacheEfficiencyReturnsRankedSessionsAndDefaultsToTwentyFourHoursAndEightRows() throws Exception {
        when(metricService.worstCacheEfficiencySessions(anyInt(), anyInt())).thenReturn(List.of(
                new SessionCacheEfficiency(
                        "7b3fc524-7f3c-4db5-9bb4-da27b77df56b", 0.41, 410_000L, 1_000_000L, 1_240_000L, 4.12),
                new SessionCacheEfficiency(
                        "025a8c32-26ff-409d-b704-dc19dcecbb47", 0.88, 880_000L, 1_000_000L, 1_100_000L, 1.05)));

        mockMvc.perform(get("/api/sessions/cache-efficiency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].sessionId").value("7b3fc524-7f3c-4db5-9bb4-da27b77df56b"))
                .andExpect(jsonPath("$[0].cacheEfficiency").value(0.41))
                .andExpect(jsonPath("$[0].cacheReadTokens").value(410000))
                .andExpect(jsonPath("$[0].inputSideTokens").value(1000000))
                .andExpect(jsonPath("$[0].totalTokens").value(1240000))
                .andExpect(jsonPath("$[0].costUsd").value(4.12))
                .andExpect(jsonPath("$[1].cacheEfficiency").value(0.88));

        verify(metricService).worstCacheEfficiencySessions(1440, 8);
    }

    @Test
    void cacheEfficiencyDelegatesToTheRangeFormWhenBothBoundsArePresent() throws Exception {
        Instant rangeStart = Instant.parse("2026-01-01T00:00:00Z");
        Instant rangeEnd = Instant.parse("2026-01-02T00:00:00Z");
        when(metricService.worstCacheEfficiencySessionsInRange(rangeStart, rangeEnd, 3)).thenReturn(List.of());

        mockMvc.perform(get("/api/sessions/cache-efficiency")
                        .param("startTimestamp", rangeStart.toString())
                        .param("endTimestamp", rangeEnd.toString())
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(metricService).worstCacheEfficiencySessionsInRange(rangeStart, rangeEnd, 3);
    }
}
