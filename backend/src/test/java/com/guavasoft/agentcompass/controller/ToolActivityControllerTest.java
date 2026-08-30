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

import com.guavasoft.agentcompass.model.HookExecutionSummary;
import com.guavasoft.agentcompass.model.IdentifierUsageCount;
import com.guavasoft.agentcompass.model.McpServerUsage;
import com.guavasoft.agentcompass.model.ToolCallCount;
import com.guavasoft.agentcompass.model.ToolContextFootprint;
import com.guavasoft.agentcompass.model.ToolDenialCount;
import com.guavasoft.agentcompass.model.ToolFailureRate;
import com.guavasoft.agentcompass.model.ToolRepeatStat;
import com.guavasoft.agentcompass.service.LogService;
import com.guavasoft.agentcompass.service.TraceService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ToolActivityController.class)
class ToolActivityControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LogService logService;

    @MockitoBean
    TraceService traceService;

    @Test
    void toolCallsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolCalls(anyInt())).thenReturn(List.of(
                ToolCallCount.builder().tool("Read").calls(8L).build(),
                ToolCallCount.builder().tool("Bash").calls(1L).build()));

        mockMvc.perform(get("/api/tool-activity/calls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Read"))
                .andExpect(jsonPath("$[0].calls").value(8))
                .andExpect(jsonPath("$[1].tool").value("Bash"));

        verify(logService).aggregateToolCalls(1440);
    }

    @Test
    void skillUsageReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateSkillUsage(anyInt())).thenReturn(List.of(
                new IdentifierUsageCount("verify", 4L, Map.of("claude-opus-4-8", 3L, "claude-sonnet-4-6", 1L),
                        4.20, Map.of("claude-opus-4-8", 3.15, "claude-sonnet-4-6", 1.05)),
                new IdentifierUsageCount("ship", 1L, Map.of("claude-opus-4-8", 1L),
                        0.75, Map.of("claude-opus-4-8", 0.75))));

        mockMvc.perform(get("/api/tool-activity/skill-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("verify"))
                .andExpect(jsonPath("$[0].calls").value(4))
                .andExpect(jsonPath("$[0].byModel.['claude-opus-4-8']").value(3))
                .andExpect(jsonPath("$[0].byModel.['claude-sonnet-4-6']").value(1))
                .andExpect(jsonPath("$[0].costUsd").value(4.20))
                .andExpect(jsonPath("$[0].costByModel.['claude-opus-4-8']").value(3.15))
                .andExpect(jsonPath("$[1].tool").value("ship"))
                .andExpect(jsonPath("$[1].byModel.['claude-opus-4-8']").value(1))
                .andExpect(jsonPath("$[1].costUsd").value(0.75));

        verify(logService).aggregateSkillUsage(1440);
    }

    @Test
    void subagentUsageReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateSubagentUsage(anyInt())).thenReturn(List.of(
                new IdentifierUsageCount("Explore", 7L, Map.of("claude-opus-4-8", 7L),
                        2.45, Map.of("claude-opus-4-8", 2.45))));

        mockMvc.perform(get("/api/tool-activity/subagent-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].tool").value("Explore"))
                .andExpect(jsonPath("$[0].calls").value(7))
                .andExpect(jsonPath("$[0].byModel.['claude-opus-4-8']").value(7))
                .andExpect(jsonPath("$[0].costUsd").value(2.45))
                .andExpect(jsonPath("$[0].costByModel.['claude-opus-4-8']").value(2.45));

        verify(logService).aggregateSubagentUsage(1440);
    }

    @Test
    void toolFailureRatesReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolFailureRates(anyInt())).thenReturn(List.of(
                new ToolFailureRate("Bash", 20L, 5L, 0.25),
                new ToolFailureRate("Read", 100L, 1L, 0.01)));

        mockMvc.perform(get("/api/tool-activity/failure-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Bash"))
                .andExpect(jsonPath("$[0].calls").value(20))
                .andExpect(jsonPath("$[0].failures").value(5))
                .andExpect(jsonPath("$[0].failureRate").value(0.25))
                .andExpect(jsonPath("$[1].tool").value("Read"));

        verify(logService).aggregateToolFailureRates(1440);
    }

    @Test
    void toolRepeatsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolRepeats(anyInt())).thenReturn(List.of(
                new ToolRepeatStat("Edit", "/repo/src/foo.ts", 4L, 8L, 3L),
                new ToolRepeatStat("Bash", "grep", 2L, 3L, 1L)));

        mockMvc.perform(get("/api/tool-activity/repeats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Edit"))
                .andExpect(jsonPath("$[0].scope").value("/repo/src/foo.ts"))
                .andExpect(jsonPath("$[0].medianRunLength").value(4))
                .andExpect(jsonPath("$[0].maxRunLength").value(8))
                .andExpect(jsonPath("$[0].sessions").value(3))
                .andExpect(jsonPath("$[1].tool").value("Bash"));

        verify(logService).aggregateToolRepeats(1440);
    }

    @Test
    void toolCallsPropagatesExplicitMinutesParam() throws Exception {
        when(logService.aggregateToolCalls(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/tool-activity/calls").param("minutes", "4320"))
                .andExpect(status().isOk());

        verify(logService).aggregateToolCalls(4320);
    }

    @Test
    void toolCallsRejectsDateRangeExceedingThirtyDays() throws Exception {
        mockMvc.perform(get("/api/tool-activity/calls")
                .param("startTimestamp", "2026-01-01T00:00:00Z")
                .param("endTimestamp", "2026-02-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void toolCallsAcceptsDateRangeOfExactlyThirtyDays() throws Exception {
        Instant rangeStart = Instant.parse("2026-01-01T00:00:00Z");
        Instant rangeEnd = Instant.parse("2026-01-31T00:00:00Z");
        when(logService.aggregateToolCallsInRange(rangeStart, rangeEnd)).thenReturn(List.of());

        mockMvc.perform(get("/api/tool-activity/calls")
                .param("startTimestamp", rangeStart.toString())
                .param("endTimestamp", rangeEnd.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void contextFootprintReturnsPerToolRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolContextFootprint(anyInt())).thenReturn(List.of(
                new ToolContextFootprint("Bash", 412L, 18_432_000L, 4_608_000L, 96_000L),
                new ToolContextFootprint("Read", 120L, 4_096_000L, 1_024_000L, 51_200L)));

        mockMvc.perform(get("/api/tool-activity/context-footprint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Bash"))
                .andExpect(jsonPath("$[0].calls").value(412))
                .andExpect(jsonPath("$[0].totalBytes").value(18432000))
                .andExpect(jsonPath("$[0].estimatedTokens").value(4608000))
                .andExpect(jsonPath("$[0].p95Bytes").value(96000))
                .andExpect(jsonPath("$[1].tool").value("Read"));

        verify(logService).aggregateToolContextFootprint(1440);
    }

    @Test
    void contextFootprintDelegatesToTheRangeFormWhenBothBoundsArePresent() throws Exception {
        Instant rangeStart = Instant.parse("2026-01-01T00:00:00Z");
        Instant rangeEnd = Instant.parse("2026-01-02T00:00:00Z");
        when(logService.aggregateToolContextFootprintInRange(rangeStart, rangeEnd)).thenReturn(List.of());

        mockMvc.perform(get("/api/tool-activity/context-footprint")
                .param("startTimestamp", rangeStart.toString())
                .param("endTimestamp", rangeEnd.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(logService).aggregateToolContextFootprintInRange(rangeStart, rangeEnd);
    }

    @Test
    void toolDenialsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateToolDenials(anyInt())).thenReturn(List.of(
                new ToolDenialCount("Bash", "config", 12L),
                new ToolDenialCount("Edit", "hook", 3L)));

        mockMvc.perform(get("/api/tool-activity/denials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("Bash"))
                .andExpect(jsonPath("$[0].source").value("config"))
                .andExpect(jsonPath("$[0].count").value(12))
                .andExpect(jsonPath("$[1].tool").value("Edit"));

        verify(logService).aggregateToolDenials(1440);
    }

    @Test
    void mcpUsageReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateMcpServerUsage(anyInt())).thenReturn(List.of(
                new McpServerUsage("playwright", "browser_evaluate", 460L, 54L, 0.1174, 1820L, 13100L,
                        6_500_000L, 1_625_000L, 180_000L),
                new McpServerUsage("CodeGraphContext", "query", 8L, 2L, 0.25, 200L, 400L, 5_000L, 1_250L, 900L)));

        mockMvc.perform(get("/api/tool-activity/mcp-usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].server").value("playwright"))
                .andExpect(jsonPath("$[0].tool").value("browser_evaluate"))
                .andExpect(jsonPath("$[0].calls").value(460))
                .andExpect(jsonPath("$[0].failures").value(54))
                .andExpect(jsonPath("$[0].failureRate").value(0.1174))
                .andExpect(jsonPath("$[0].avgDurationMs").value(1820))
                .andExpect(jsonPath("$[0].p95DurationMs").value(13100))
                .andExpect(jsonPath("$[0].totalBytes").value(6_500_000))
                .andExpect(jsonPath("$[0].estimatedTokens").value(1_625_000))
                .andExpect(jsonPath("$[1].server").value("CodeGraphContext"));

        verify(logService).aggregateMcpServerUsage(1440);
    }

    @Test
    void mcpUsageDelegatesToTheRangeFormWhenBothBoundsArePresent() throws Exception {
        Instant rangeStart = Instant.parse("2026-01-01T00:00:00Z");
        Instant rangeEnd = Instant.parse("2026-01-02T00:00:00Z");
        when(logService.aggregateMcpServerUsageInRange(rangeStart, rangeEnd)).thenReturn(List.of());

        mockMvc.perform(get("/api/tool-activity/mcp-usage")
                .param("startTimestamp", rangeStart.toString())
                .param("endTimestamp", rangeEnd.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        verify(logService).aggregateMcpServerUsageInRange(rangeStart, rangeEnd);
    }

    @Test
    void mcpUsageRejectsDateRangeExceedingThirtyDays() throws Exception {
        mockMvc.perform(get("/api/tool-activity/mcp-usage")
                .param("startTimestamp", "2026-01-01T00:00:00Z")
                .param("endTimestamp", "2026-02-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hookExecutionsReturnsAggregatedRowsAndDefaultsToTwentyFourHoursInMinutes() throws Exception {
        when(logService.aggregateHookExecutions(anyInt())).thenReturn(List.of(
                new HookExecutionSummary("PreToolUse", "PreToolUse:Write", 45L, 40L, 3L, 2L, 0L)));

        mockMvc.perform(get("/api/tool-activity/hook-executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].hookEvent").value("PreToolUse"))
                .andExpect(jsonPath("$[0].hookName").value("PreToolUse:Write"))
                .andExpect(jsonPath("$[0].blockingErrors").value(3));

        verify(logService).aggregateHookExecutions(1440);
    }
}
